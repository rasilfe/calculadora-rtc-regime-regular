package br.gov.serpro.rtc.worker;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import br.gov.serpro.rtc.worker.empresa.IdentificacaoEmpresa;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * Consome a fila de base de cálculo ({@code rtc.basecalculo.entrada}): uma mensagem é
 * o pedido de base unificada de um item.
 *
 * <p>Mesmo fluxo da nota, com ack manual: mensagem ilegível ou sem empresa válida
 * (header {@code x-cnpj} conferido com a routing key {@code base.<CNPJ>}) vai para a
 * DLQ; dados inválidos voltam como resultado {@code ERRO}; o resultado é publicado em
 * {@code resultado-base.<CNPJ>} e só depois da confirmação do broker o pedido é
 * confirmado. Falha transitória devolve o pedido para a fila.
 *
 * <p>Sem idempotência no Redis: o cálculo é puro e leva milissegundos. Uma reentrega
 * recalcula e publica de novo; a empresa deduplica pelo {@code correlationId}
 * (entrega pelo menos uma vez, como nas notas).
 */
@Slf4j
@Component
@ConditionalOnExpression("${worker.habilitado:true} and ${worker.base-calculo.habilitado:true}")
public class CalculoBaseListener {

    public static final String ID_CONTAINER = "calculoBase";

    private final CalculoBaseService calculoBaseService;
    private final ResultadoPublisher publisher;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;
    private final MeterRegistry registry;
    private final Timer duracao;

    public CalculoBaseListener(CalculoBaseService calculoBaseService, ResultadoPublisher publisher,
            ObjectMapper objectMapper, WorkerProperties properties, MeterRegistry registry) {
        this.calculoBaseService = calculoBaseService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.registry = registry;
        this.duracao = Timer.builder("rtc.worker.base.duracao")
                .description("Tempo para calcular e publicar uma base de cálculo (sem a espera na fila)")
                .publishPercentileHistogram()
                .register(registry);
    }

    @RabbitListener(id = ID_CONTAINER, queues = "${worker.base-calculo.fila-entrada:rtc.basecalculo.entrada}",
            ackMode = "MANUAL")
    public void receber(Message mensagem, Channel canal) throws IOException {
        final long tag = mensagem.getMessageProperties().getDeliveryTag();

        final BaseCalculoEntrada lido = ler(mensagem);
        if (lido == null) {
            contar("rejeitada_ilegivel", "nenhum");
            canal.basicReject(tag, false);
            return;
        }
        final var empresa = IdentificacaoEmpresa.identificar(mensagem.getMessageProperties(), lido.cnpj(),
                properties.getMultiEmpresa(), properties.getBaseCalculo().getPrefixoRoutingKeyEntrada());
        if (!empresa.identificada()) {
            log.error("Base de cálculo {} sem empresa válida; enviando para a DLQ: {}", lido.correlationId(),
                    empresa.motivoRecusa());
            contar("rejeitada_empresa", "nenhum");
            canal.basicReject(tag, false);
            return;
        }
        final String cnpj = empresa.cnpj().valor();
        final BaseCalculoEntrada pedido = lido.comCnpj(cnpj);

        try (var mdcEmpresa = MDC.putCloseable("cnpj", cnpj);
                var mdcCorrelacao = MDC.putCloseable("correlationId", pedido.correlationId().toString())) {
            final Timer.Sample amostra = Timer.start(registry);
            try {
                final ResultadoBaseCalculo resultado = calculoBaseService.calcular(pedido);
                publisher.publicarBaseConfirmado(resultado);
                canal.basicAck(tag, false);
                amostra.stop(duracao);
                contar("publicada", resultado.status().name());
            } catch (RuntimeException e) {
                log.warn("Falha transitória ao calcular a base; devolvendo para a fila: {}", e.toString());
                contar("devolvida_fila", "nenhum");
                canal.basicNack(tag, false, true);
            }
        }
    }

    private BaseCalculoEntrada ler(Message mensagem) {
        try {
            final BaseCalculoEntrada pedido = objectMapper.readValue(mensagem.getBody(), BaseCalculoEntrada.class);
            if (pedido == null || pedido.correlationId() == null || pedido.payload() == null) {
                log.error("Base de cálculo sem correlationId ou payload (messageId={}); enviando para a DLQ",
                        mensagem.getMessageProperties().getMessageId());
                return null;
            }
            return pedido;
        } catch (IOException e) {
            log.error("Base de cálculo ilegível (messageId={}); enviando para a DLQ: {}",
                    mensagem.getMessageProperties().getMessageId(), e.getMessage());
            return null;
        }
    }

    private void contar(String resultado, String status) {
        registry.counter("rtc.worker.base", "resultado", resultado, "status", status).increment();
    }
}
