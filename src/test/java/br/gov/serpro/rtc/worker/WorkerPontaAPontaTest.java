package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.github.fridujo.rabbitmq.mock.MockConnectionFactory;
import com.rabbitmq.client.GetResponse;

/**
 * Teste ponta a ponta do worker com um broker em memória que implementa a API do
 * cliente Java do RabbitMQ (rabbitmq-mock):
 * produtor → rtc.calculo.in → fila de entrada → worker (cálculo + publicação com
 * confirmação + ack manual) → rtc.calculo.out → fila de saída da empresa.
 *
 * <p>Exercita o container real do Spring AMQP, a conversão da mensagem, o ack/reject
 * manual, as confirmações do broker e o roteamento por CNPJ. As propriedades repetem as
 * do perfil {@code offline}. Não cobre a rede nem recursos exclusivos do servidor
 * RabbitMQ (quorum, topic permission, delivery-limit), validados pelos manifestos; a
 * idempotência usa a implementação sem Redis.
 */
@SpringBootTest(properties = {
        "worker.habilitado=true",
        "worker.idempotencia.tipo=nenhuma",
        "spring.threads.virtual.enabled=true",
        "spring.rabbitmq.template.mandatory=true",
        "spring.rabbitmq.listener.simple.acknowledge-mode=manual",
        "spring.rabbitmq.listener.simple.concurrency=2",
        "spring.rabbitmq.listener.simple.max-concurrency=2",
        "spring.rabbitmq.listener.simple.prefetch=3",
        "spring.rabbitmq.listener.simple.default-requeue-rejected=false",
        "spring.rabbitmq.listener.simple.auto-startup=false" })
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class WorkerPontaAPontaTest {

    private static final String EMPRESA_A = "11222333000181";
    private static final String EMPRESA_B = "12ABC34501DE35";
    private static final Duration ESPERA = Duration.ofSeconds(30);

    /** Broker em memória e topologia equivalente à de k8s/base/rabbitmq/topologia.yaml. */
    @TestConfiguration
    static class Broker {
        /**
         * Com uma ConnectionFactory própria, o Spring Boot não aplica
         * spring.rabbitmq.publisher-*; o mesmo efeito do perfil offline é configurado aqui.
         */
        @Bean
        @Primary
        CachingConnectionFactory connectionFactory() {
            final var fabrica = new CachingConnectionFactory(new MockConnectionFactory());
            fabrica.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            fabrica.setPublisherReturns(true);
            return fabrica;
        }

        @Bean
        Declarables topologiaTeste() {
            final var entrada = new TopicExchange(WorkerConfig.EXCHANGE_ENTRADA, true, false);
            final var saida = new TopicExchange("rtc.calculo.out", true, false);
            final var filaEntrada = new Queue("rtc.calculo.entrada", true);
            final var saidaA = new Queue(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), true);
            final var saidaB = new Queue(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_B), true);
            final var filaBase = new Queue("rtc.basecalculo.entrada", true);
            final var baseA = new Queue(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_A), true);
            final var baseB = new Queue(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_B), true);
            return new Declarables(entrada, saida, filaEntrada, saidaA, saidaB, filaBase, baseA, baseB,
                    BindingBuilder.bind(filaEntrada).to(entrada).with(WorkerConfig.BINDING_ENTRADA),
                    BindingBuilder.bind(saidaA).to(saida).with("resultado." + EMPRESA_A),
                    BindingBuilder.bind(saidaB).to(saida).with("resultado." + EMPRESA_B),
                    BindingBuilder.bind(filaBase).to(entrada).with(WorkerConfig.BINDING_BASE_ENTRADA),
                    BindingBuilder.bind(baseA).to(saida).with("resultado-base." + EMPRESA_A),
                    BindingBuilder.bind(baseB).to(saida).with("resultado-base." + EMPRESA_B));
        }
    }

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("classpath:entradas/calculoscorretos/Teste_000001_1.json")
    private Resource operacao;

    @BeforeEach
    void esvaziarFilas() {
        admin.purgeQueue(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), false);
        admin.purgeQueue(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_B), false);
        admin.purgeQueue(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_A), false);
        admin.purgeQueue(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_B), false);
    }

    @Test
    void baseDaNfseUsaAMesmaFilaComTipoNfse() throws Exception {
        final UUID id = UUID.randomUUID();
        final ObjectNode payload = objectMapper.createObjectNode();
        payload.put("anoFatoGerador", 2026);
        payload.put("opSimpNac", 1);
        payload.put("situacao", "comum");
        payload.put("vServ", 100.00);
        payload.put("vDescIncond", 5.00);
        payload.put("vISSQN", 5.00);
        payload.put("vPIS", 5.00);
        payload.put("vCOFINS", 5.00);
        final ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", "1");
        envelope.put("tipo", "NFSE");
        envelope.put("correlationId", id.toString());
        envelope.put("idCalculo", "nfse-" + id);
        envelope.set("payload", payload);
        final var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("x-cnpj", EMPRESA_A);
        rabbit.send(WorkerConfig.EXCHANGE_ENTRADA, "base." + EMPRESA_A,
                new Message(objectMapper.writeValueAsBytes(envelope), props));

        final Message resposta = receber(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_A), ESPERA.toMillis());
        assertThat(resposta).as("resultado na fila de base da empresa A").isNotNull();
        final ResultadoBaseCalculo resultado = objectMapper.readValue(resposta.getBody(), ResultadoBaseCalculo.class);
        assertThat(resultado.correlationId()).isEqualTo(id);
        assertThat(resultado.tipo()).isEqualTo(TipoBaseCalculo.NFSE);
        assertThat(resultado.status()).isEqualTo(ResultadoBaseCalculo.Status.SUCESSO);
        assertThat(resultado.resultado()).isNull();
        assertThat(resultado.resultadoNfse().getBaseRG()).isEqualByComparingTo("80.00");
        assertThat((String) resposta.getMessageProperties().getHeader("x-tipo")).isEqualTo("NFSE");
        aguardarFilaVazia("rtc.basecalculo.entrada");
    }

    @Test
    void baseDeCalculoChegaNaFilaDeBaseDaEmpresaENaoNaDeNotas() throws Exception {
        final UUID id = UUID.randomUUID();

        publicarBase(id, EMPRESA_A, "base." + EMPRESA_A, 55);

        final Message resposta = receber(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_A), ESPERA.toMillis());
        assertThat(resposta).as("resultado na fila de base da empresa A").isNotNull();
        final ResultadoBaseCalculo resultado = objectMapper.readValue(resposta.getBody(), ResultadoBaseCalculo.class);
        assertThat(resultado.correlationId()).isEqualTo(id);
        assertThat(resultado.cnpj()).isEqualTo(EMPRESA_A);
        assertThat(resultado.status()).isEqualTo(ResultadoBaseCalculo.Status.SUCESSO);
        assertThat(resultado.resultado().getBaseRG()).isEqualByComparingTo("1000.00");
        assertThat((String) resposta.getMessageProperties().getHeader("x-status")).isEqualTo("SUCESSO");

        assertThat(receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), 500)).as("fila de notas").isNull();
        assertThat(receber(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_B), 500)).as("outra empresa").isNull();
        aguardarFilaVazia("rtc.basecalculo.entrada");
    }

    @Test
    void baseComDadosInvalidosVoltaComoErroParaAEmpresa() throws Exception {
        final UUID id = UUID.randomUUID();

        publicarBase(id, EMPRESA_B, "base." + EMPRESA_B, 57);

        final Message resposta = receber(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_B), ESPERA.toMillis());
        final ResultadoBaseCalculo resultado = objectMapper.readValue(resposta.getBody(), ResultadoBaseCalculo.class);
        assertThat(resultado.status()).isEqualTo(ResultadoBaseCalculo.Status.ERRO);
        assertThat(resultado.erros()).extracting(e -> e.code()).containsExactly("REG-024");
        aguardarFilaVazia("rtc.basecalculo.entrada");
    }

    @Test
    void baseComRoutingKeyDeOutraEmpresaNaoGeraResultado() throws Exception {
        publicarBase(UUID.randomUUID(), EMPRESA_B, "base." + EMPRESA_A, 55);

        aguardarFilaVazia("rtc.basecalculo.entrada");
        assertThat(receber(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_A), 1_000)).isNull();
        assertThat(receber(WorkerConfig.filaBaseSaidaDaEmpresa(EMPRESA_B), 500)).isNull();
    }

    @Test
    void notaPublicadaPelaEmpresaChegaCalculadaNaFilaDela() throws Exception {
        final UUID id = UUID.randomUUID();

        publicar(id, EMPRESA_A, "nota." + EMPRESA_A);

        final Message resposta = receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), ESPERA.toMillis());
        assertThat(resposta).as("resultado na fila da empresa A").isNotNull();
        // lido como JSON, como faz a empresa: o ROC é só serializado pelo worker
        final JsonNode resultado = objectMapper.readTree(resposta.getBody());
        assertThat(resultado.path("correlationId").asString()).isEqualTo(id.toString());
        assertThat(resultado.path("cnpj").asString()).isEqualTo(EMPRESA_A);
        assertThat(resultado.path("status").asString()).isEqualTo(StatusNota.SUCESSO.name());
        assertThat(resultado.path("resultado").path("objetos").size()).isPositive();
        assertThat(resposta.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat((String) resposta.getMessageProperties().getHeader("x-cnpj")).isEqualTo(EMPRESA_A);

        // a outra empresa não recebe nada; a mensagem de entrada foi confirmada (ack)
        assertThat(receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_B), 500)).isNull();
        aguardarFilaDeEntradaVazia();
    }

    @Test
    void empresaComCnpjAlfanumericoRecebeSoOsProprios() throws Exception {
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();

        publicar(idA, EMPRESA_A, "nota." + EMPRESA_A);
        publicar(idB, "12.abc.345/01de-35", "nota." + EMPRESA_B);

        final Message paraB = receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_B), ESPERA.toMillis());
        final Message paraA = receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), ESPERA.toMillis());
        assertThat(objectMapper.readTree(paraB.getBody()).path("correlationId").asString()).isEqualTo(idB.toString());
        assertThat(objectMapper.readTree(paraA.getBody()).path("correlationId").asString()).isEqualTo(idA.toString());
        assertThat(receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), 500)).isNull();
        assertThat(receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_B), 500)).isNull();
    }

    @Test
    void notaComHeaderDeOutraEmpresaNaoGeraResultadoESaiDaFila() throws Exception {
        // header diz B, mas a publicação foi com a routing key de A
        publicar(UUID.randomUUID(), EMPRESA_B, "nota." + EMPRESA_A);

        aguardarFilaDeEntradaVazia();
        assertThat(receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), 1_000)).isNull();
        assertThat(receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_B), 500)).isNull();
    }

    @Test
    void mensagemIlegivelSaiDaFilaSemResultado() {
        final var props = new MessageProperties();
        props.setHeader("x-cnpj", EMPRESA_A);
        rabbit.send(WorkerConfig.EXCHANGE_ENTRADA, "nota." + EMPRESA_A,
                new Message("{isto nao e json".getBytes(StandardCharsets.UTF_8), props));

        aguardarFilaDeEntradaVazia();
        assertThat(receber(WorkerConfig.filaSaidaDaEmpresa(EMPRESA_A), 1_000)).isNull();
    }

    /**
     * Lê uma mensagem com ack explícito ({@code basicGet} + {@code basicAck}), como um
     * consumidor real; espera até {@code esperaMs}.
     */
    private Message receber(String fila, long esperaMs) {
        final long limite = System.currentTimeMillis() + esperaMs;
        do {
            final Message mensagem = rabbit.execute(canal -> {
                final GetResponse resposta = canal.basicGet(fila, false);
                if (resposta == null) {
                    return null;
                }
                canal.basicAck(resposta.getEnvelope().getDeliveryTag(), false);
                final var props = new MessageProperties();
                props.setMessageId(resposta.getProps().getMessageId());
                if (resposta.getProps().getHeaders() != null) {
                    resposta.getProps().getHeaders().forEach((k, v) -> props.setHeader(k, v == null ? null : v.toString()));
                }
                return new Message(resposta.getBody(), props);
            });
            if (mensagem != null) {
                return mensagem;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < limite);
        return null;
    }

    private void publicar(UUID id, String headerCnpj, String routingKey) throws Exception {
        final ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", "1");
        envelope.put("correlationId", id.toString());
        envelope.put("idNota", "nota-" + id);
        envelope.put("loteId", "e2e");
        envelope.put("produzidoEm", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        envelope.set("payload", objectMapper.readTree(operacao.getInputStream()));

        final var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(id.toString());
        props.setHeader("x-cnpj", headerCnpj);
        rabbit.send(WorkerConfig.EXCHANGE_ENTRADA, routingKey, new Message(objectMapper.writeValueAsBytes(envelope), props));
    }

    private void publicarBase(UUID id, String headerCnpj, String routingKey, int tipoDocumento) throws Exception {
        final ObjectNode payload = objectMapper.createObjectNode();
        payload.put("anoFatoGerador", 2026);
        payload.put("tipoDocumento", tipoDocumento);
        payload.put("natureza", "bem");
        payload.put("vProd", 1000.00);
        final ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", "1");
        envelope.put("correlationId", id.toString());
        envelope.put("idCalculo", "item-" + id);
        envelope.put("produzidoEm", OffsetDateTime.now().toString());
        envelope.set("payload", payload);

        final var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("x-cnpj", headerCnpj);
        rabbit.send(WorkerConfig.EXCHANGE_ENTRADA, routingKey, new Message(objectMapper.writeValueAsBytes(envelope), props));
    }

    private void aguardarFilaDeEntradaVazia() {
        aguardarFilaVazia("rtc.calculo.entrada");
    }

    private void aguardarFilaVazia(String fila) {
        Awaitility.await().atMost(ESPERA).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            final var info = admin.getQueueInfo(fila);
            assertThat(info).isNotNull();
            assertThat(info.getMessageCount()).isZero();
            assertThat(info.getConsumerCount()).isPositive();
        });
    }
}
