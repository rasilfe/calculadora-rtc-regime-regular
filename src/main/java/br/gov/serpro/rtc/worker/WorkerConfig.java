package br.gov.serpro.rtc.worker;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import lombok.extern.slf4j.Slf4j;

/**
 * Configuração do worker de filas.
 *
 * <p>O container do listener nasce parado ({@code spring.rabbitmq.listener.simple.auto-startup=false})
 * e só começa a consumir quando a aplicação está pronta (Flyway, JPA e caches
 * inicializados). Assim, um pod novo criado pelo KEDA não pega mensagens antes de
 * conseguir processá-las.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

    static final String EXCHANGE_ENTRADA = "rtc.calculo.in";
    /** Binding da fila de entrada compartilhada: aceita {@code nota.<CNPJ>} de qualquer empresa. */
    static final String BINDING_ENTRADA = "nota.*";
    static final String EXCHANGE_DLX = "rtc.dlx";
    /** Prefixo das filas de saída por empresa: {@code rtc.calculo.saida.<CNPJ>}. */
    static final String PREFIXO_FILA_SAIDA = "rtc.calculo.saida.";
    /** DLQ única das filas de saída (mensagens mantêm a routing key {@code resultado.<CNPJ>}). */
    static final String DLQ_SAIDA = "rtc.calculo.saida.dlq";
    /** Fila de saída só para desenvolvimento local: recebe os resultados de todas as empresas. */
    static final String FILA_SAIDA_DEV = "rtc.calculo.saida.dev";

    /** Nome da fila de saída de uma empresa. */
    public static String filaSaidaDaEmpresa(String cnpj) {
        return PREFIXO_FILA_SAIDA + cnpj;
    }

    @Bean
    @ConditionalOnProperty(prefix = "worker", name = "habilitado", havingValue = "true", matchIfMissing = true)
    InicioConsumo inicioConsumo(RabbitListenerEndpointRegistry registry) {
        return new InicioConsumo(registry);
    }

    /** Inicia o consumo da Fila de Entrada quando a aplicação está pronta. */
    static class InicioConsumo {
        private final RabbitListenerEndpointRegistry registry;

        InicioConsumo(RabbitListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @EventListener(ApplicationReadyEvent.class)
        void iniciar() {
            final var container = registry.getListenerContainer(CalculoNotaListener.ID_CONTAINER);
            if (container != null && !container.isRunning()) {
                container.start();
                log.info("Consumo da Fila de Entrada iniciado");
            }
        }
    }

    /**
     * Topologia para desenvolvimento local ({@code worker.declarar-topologia=true}).
     * Em k3s, a mesma topologia é criada pelo Messaging Topology Operator
     * ({@code k8s/base/rabbitmq/topologia.yaml}), com os limites definidos em policies.
     */
    @Bean
    @ConditionalOnProperty(prefix = "worker", name = "declarar-topologia", havingValue = "true")
    Declarables topologiaCalculo(WorkerProperties properties) {
        final String prefixoSaida = properties.getMultiEmpresa().getPrefixoRoutingKeySaida();
        final TopicExchange entrada = ExchangeBuilder.topicExchange(EXCHANGE_ENTRADA).durable(true).build();
        final TopicExchange saida = ExchangeBuilder.topicExchange(properties.getExchangeSaida()).durable(true).build();
        final TopicExchange dlx = ExchangeBuilder.topicExchange(EXCHANGE_DLX).durable(true).build();

        final String dlqEntradaNome = properties.getFilaEntrada() + ".dlq";
        final Queue filaEntrada = filaQuorum(properties.getFilaEntrada(), 200_000, dlqEntradaNome);
        final Queue filaSaidaDev = filaQuorum(FILA_SAIDA_DEV, 500_000, null);
        final Queue dlqEntrada = QueueBuilder.durable(dlqEntradaNome).quorum().build();
        final Queue dlqSaida = QueueBuilder.durable(DLQ_SAIDA).quorum().build();

        return new Declarables(entrada, saida, dlx, filaEntrada, filaSaidaDev, dlqEntrada, dlqSaida,
                BindingBuilder.bind(filaEntrada).to(entrada).with(BINDING_ENTRADA),
                BindingBuilder.bind(filaSaidaDev).to(saida).with(prefixoSaida + "#"),
                BindingBuilder.bind(dlqEntrada).to(dlx).with(dlqEntradaNome),
                BindingBuilder.bind(dlqSaida).to(dlx).with(prefixoSaida + "#"));
    }

    /**
     * Fila quorum com DLX. Sem {@code dlqRoutingKey}, a mensagem morta mantém a routing
     * key original (ex.: {@code resultado.<CNPJ>}).
     */
    private static Queue filaQuorum(String nome, int tamanhoMaximo, String dlqRoutingKey) {
        final QueueBuilder builder = QueueBuilder.durable(nome)
                .quorum()
                .deadLetterExchange(EXCHANGE_DLX)
                .deliveryLimit(5)
                .maxLength(tamanhoMaximo)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .withArgument("x-dead-letter-strategy", "at-least-once");
        if (dlqRoutingKey != null) {
            builder.deadLetterRoutingKey(dlqRoutingKey);
        }
        return builder.build();
    }
}
