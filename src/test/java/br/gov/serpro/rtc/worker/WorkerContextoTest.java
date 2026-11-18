package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import br.gov.serpro.rtc.worker.idempotencia.IdempotenciaService;
import br.gov.serpro.rtc.worker.idempotencia.RedisIdempotenciaService;

/**
 * Fiação do worker com o consumo ligado, sem broker: o início do consumo é
 * substituído por um mock, então nenhuma conexão AMQP é aberta.
 */
@SpringBootTest(properties = {
        "worker.habilitado=true",
        "worker.idempotencia.tipo=redis",
        "worker.fila-entrada=fila.teste.entrada",
        "spring.threads.virtual.enabled=true",
        "spring.rabbitmq.listener.simple.concurrency=3",
        "spring.rabbitmq.listener.simple.max-concurrency=3",
        "spring.rabbitmq.listener.simple.prefetch=7" })
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class WorkerContextoTest {

    @MockitoBean
    private WorkerConfig.InicioConsumo inicioConsumo;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private IdempotenciaService idempotencia;

    @Autowired
    private ResultadoPublisher publisher;

    @Test
    void listenerRegistradoComAckManualEParadoAteAAplicacaoFicarPronta() {
        final var container = (AbstractMessageListenerContainer) registry
                .getListenerContainer(CalculoNotaListener.ID_CONTAINER);

        assertThat(container).isNotNull();
        assertThat(container.getQueueNames()).containsExactly("fila.teste.entrada");
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(container.isAutoStartup()).isFalse();
        assertThat(container.isRunning()).isFalse();
        assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(7);
        assertThat(ReflectionTestUtils.getField(container, "taskExecutor").getClass().getSimpleName())
                .contains("VirtualThread");
    }

    @Test
    void idempotenciaRedisEPublicadorDisponiveis() {
        assertThat(idempotencia).isInstanceOf(RedisIdempotenciaService.class);
        assertThat(publisher).isNotNull();
    }
}
