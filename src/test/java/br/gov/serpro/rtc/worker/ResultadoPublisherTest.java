package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publicação com confirmação do broker (publisher confirms).
 */
class ResultadoPublisherTest {

    private RabbitTemplate template;
    private WorkerProperties properties;
    private ResultadoPublisher publisher;
    private final ResultadoNota resultado = new ResultadoNota("1", UUID.randomUUID(), "12ABC34501DE35", "nota-1", "lote-1",
            StatusNota.SUCESSO, "1.6.1", "V0059", null, null, 3, null, null);

    @BeforeEach
    void setUp() {
        template = mock(RabbitTemplate.class);
        properties = new WorkerProperties();
        properties.setTimeoutConfirmacao(Duration.ofMillis(200));
        publisher = new ResultadoPublisher(template, new ObjectMapper().findAndRegisterModules(), properties);
    }

    @Test
    void retornaQuandoBrokerConfirma() {
        aoEnviar(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertThatCode(() -> publisher.publicarConfirmado(resultado)).doesNotThrowAnyException();

        final var captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("rtc.calculo.out"), eq("resultado.12ABC34501DE35"), captor.capture(), any(CorrelationData.class));
        final MessageProperties props = captor.getValue().getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(resultado.correlationId().toString());
        assertThat(props.getCorrelationId()).isEqualTo(resultado.correlationId().toString());
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat((String) props.getHeader("x-status")).isEqualTo("SUCESSO");
        assertThat((String) props.getHeader("x-cnpj")).isEqualTo("12ABC34501DE35");
        assertThat(new String(captor.getValue().getBody())).contains("\"idNota\":\"nota-1\"");
    }

    @Test
    void falhaQuandoBrokerRecusa() {
        aoEnviar(cd -> cd.getFuture().complete(new CorrelationData.Confirm(false, "fila cheia")));

        assertThatThrownBy(() -> publisher.publicarConfirmado(resultado))
                .isInstanceOf(ResultadoPublisher.PublicacaoFalhouException.class)
                .hasMessageContaining("fila cheia");
    }

    @Test
    void falhaQuandoMensagemEhDevolvidaSemRota() {
        aoEnviar(cd -> {
            cd.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "rtc.calculo.out", "resultado"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatThrownBy(() -> publisher.publicarConfirmado(resultado))
                .isInstanceOf(ResultadoPublisher.PublicacaoFalhouException.class)
                .hasMessageContaining("NO_ROUTE");
    }

    @Test
    void falhaQuandoConfirmacaoNaoChegaNoPrazo() {
        aoEnviar(cd -> { /* broker não responde */ });

        assertThatThrownBy(() -> publisher.publicarConfirmado(resultado))
                .isInstanceOf(ResultadoPublisher.PublicacaoFalhouException.class)
                .hasMessageContaining("Sem confirmação");
    }

    @Test
    void resultadoSemCnpjNaoEhPublicado() {
        final var semEmpresa = new ResultadoNota("1", UUID.randomUUID(), null, "nota-1", "lote-1",
                StatusNota.SUCESSO, "1.6.1", "V0059", null, null, 3, null, null);

        assertThatThrownBy(() -> publisher.publicarConfirmado(semEmpresa)).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(template);
    }

    @Test
    void baseDeCalculoVaiParaARoutingKeyDaBaseDaEmpresa() {
        aoEnviar(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));
        final var base = new ResultadoBaseCalculo("1", UUID.randomUUID(), "12ABC34501DE35", "item-9", null,
                TipoBaseCalculo.NFSE, ResultadoBaseCalculo.Status.ERRO, "1.6.1", null, 1, null, null, null, null);

        assertThatCode(() -> publisher.publicarBaseConfirmado(base)).doesNotThrowAnyException();

        final var captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("rtc.calculo.out"), eq("resultado-base.12ABC34501DE35"), captor.capture(),
                any(CorrelationData.class));
        final MessageProperties props = captor.getValue().getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(base.correlationId().toString());
        assertThat((String) props.getHeader("x-status")).isEqualTo("ERRO");
        assertThat((String) props.getHeader("x-id-calculo")).isEqualTo("item-9");
        assertThat((String) props.getHeader("x-tipo")).isEqualTo("NFSE");
        assertThat((String) props.getHeader("x-cnpj")).isEqualTo("12ABC34501DE35");
        assertThat(props.getHeaders()).doesNotContainKey("x-id-nota");
        // campos nulos não vão para o JSON
        assertThat(new String(captor.getValue().getBody())).doesNotContain("loteId", "resultado\"");
    }

    private void aoEnviar(Consumer<CorrelationData> broker) {
        doAnswer(inv -> {
            broker.accept(inv.getArgument(3));
            return null;
        }).when(template).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }
}
