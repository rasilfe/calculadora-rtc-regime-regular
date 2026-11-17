package br.gov.serpro.rtc.worker;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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
    static final String ROUTING_KEY_ENTRADA = "nota";
    static final String EXCHANGE_DLX = "rtc.dlx";
    static final String FILA_SAIDA = "rtc.calculo.saida";

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
     * ({@code k8s/rabbitmq/topologia.yaml}), com os limites definidos em policies.
     */
    @Bean
    @ConditionalOnProperty(prefix = "worker", name = "declarar-topologia", havingValue = "true")
    Declarables topologiaCalculo(WorkerProperties properties) {
        final DirectExchange entrada = ExchangeBuilder.directExchange(EXCHANGE_ENTRADA).durable(true).build();
        final DirectExchange saida = ExchangeBuilder.directExchange(properties.getExchangeSaida()).durable(true).build();
        final DirectExchange dlx = ExchangeBuilder.directExchange(EXCHANGE_DLX).durable(true).build();

        final Queue filaEntrada = filaQuorum(properties.getFilaEntrada(), 200_000);
        final Queue filaSaida = filaQuorum(FILA_SAIDA, 500_000);
        final Queue dlqEntrada = QueueBuilder.durable(properties.getFilaEntrada() + ".dlq").quorum().build();
        final Queue dlqSaida = QueueBuilder.durable(FILA_SAIDA + ".dlq").quorum().build();

        final Binding bEntrada = BindingBuilder.bind(filaEntrada).to(entrada).with(ROUTING_KEY_ENTRADA);
        final Binding bSaida = BindingBuilder.bind(filaSaida).to(saida).with(properties.getRoutingKeySaida());
        final Binding bDlqEntrada = BindingBuilder.bind(dlqEntrada).to(dlx).with(dlqEntrada.getName());
        final Binding bDlqSaida = BindingBuilder.bind(dlqSaida).to(dlx).with(dlqSaida.getName());

        return new Declarables(entrada, saida, dlx, filaEntrada, filaSaida, dlqEntrada, dlqSaida,
                bEntrada, bSaida, bDlqEntrada, bDlqSaida);
    }

    private static Queue filaQuorum(String nome, int tamanhoMaximo) {
        return QueueBuilder.durable(nome)
                .quorum()
                .deadLetterExchange(EXCHANGE_DLX)
                .deadLetterRoutingKey(nome + ".dlq")
                .deliveryLimit(5)
                .maxLength(tamanhoMaximo)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .build();
    }
}
