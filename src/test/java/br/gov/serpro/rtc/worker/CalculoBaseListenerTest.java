package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Caminhos de ack, reject e nack do consumidor da fila de base de cálculo.
 */
class CalculoBaseListenerTest {

    private static final long TAG = 7L;
    private static final String CNPJ = "12ABC34501DE35";

    private CalculoBaseService calculo;
    private ResultadoPublisher publisher;
    private Channel canal;
    private SimpleMeterRegistry registry;
    private CalculoBaseListener listener;
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        calculo = mock(CalculoBaseService.class);
        publisher = mock(ResultadoPublisher.class);
        canal = mock(Channel.class);
        registry = new SimpleMeterRegistry();
        listener = new CalculoBaseListener(calculo, publisher, new ObjectMapper().findAndRegisterModules(),
                new WorkerProperties(), registry);
    }

    @Test
    void sucessoPublicaEConfirma() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(ResultadoBaseCalculo.Status.SUCESSO));

        listener.receber(mensagem(pedidoValido(), "base." + CNPJ, CNPJ), canal);

        final var enviado = ArgumentCaptor.forClass(BaseCalculoEntrada.class);
        verify(calculo).calcular(enviado.capture());
        assertThat(enviado.getValue().cnpj()).isEqualTo(CNPJ);
        verify(publisher).publicarBaseConfirmado(any(ResultadoBaseCalculo.class));
        verify(canal).basicAck(TAG, false);
        assertThat(registry.counter("rtc.worker.base", "resultado", "publicada", "status", "SUCESSO").count())
                .isEqualTo(1);
    }

    @Test
    void dadosInvalidosTambemSaoPublicadosComoResultado() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(ResultadoBaseCalculo.Status.ERRO));

        listener.receber(mensagem(pedidoValido(), "base." + CNPJ, CNPJ), canal);

        verify(publisher).publicarBaseConfirmado(any(ResultadoBaseCalculo.class));
        verify(canal).basicAck(TAG, false);
    }

    @Test
    void routingKeyDeNotaNaoServeParaABase() throws Exception {
        // a empresa precisa publicar com base.<CNPJ>; nota.<CNPJ> nesta fila é recusada
        listener.receber(mensagem(pedidoValido(), "nota." + CNPJ, CNPJ), canal);

        verifyNoInteractions(calculo, publisher);
        verify(canal).basicReject(TAG, false);
        assertThat(registry.counter("rtc.worker.base", "resultado", "rejeitada_empresa", "status", "nenhum").count())
                .isEqualTo(1);
    }

    @Test
    void headerDeOutraEmpresaVaiParaADlq() throws Exception {
        listener.receber(mensagem(pedidoValido(), "base." + CNPJ, "11222333000181"), canal);

        verifyNoInteractions(calculo, publisher);
        verify(canal).basicReject(TAG, false);
    }

    @Test
    void mensagemIlegivelVaiParaADlq() throws Exception {
        listener.receber(mensagem("{nao e json", "base." + CNPJ, CNPJ), canal);

        verifyNoInteractions(calculo, publisher);
        verify(canal).basicReject(TAG, false);
    }

    @Test
    void falhaAoPublicarDevolveParaAFila() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(ResultadoBaseCalculo.Status.SUCESSO));
        doThrow(new ResultadoPublisher.PublicacaoFalhouException("sem confirmação", null))
                .when(publisher).publicarBaseConfirmado(any(ResultadoBaseCalculo.class));

        listener.receber(mensagem(pedidoValido(), "base." + CNPJ, CNPJ), canal);

        verify(canal).basicNack(TAG, false, true);
        verify(canal, never()).basicAck(anyLong(), anyBoolean());
    }

    private String pedidoValido() {
        return """
                {"schemaVersion":"1","correlationId":"%s","idCalculo":"item-1","loteId":"l",
                 "produzidoEm":"2026-11-18T10:00:00-03:00",
                 "payload":{"anoFatoGerador":2026,"tipoDocumento":55,"natureza":"bem","vProd":100}}
                """.formatted(correlationId);
    }

    private ResultadoBaseCalculo resultado(ResultadoBaseCalculo.Status status) {
        return new ResultadoBaseCalculo("1", correlationId, CNPJ, "item-1", "l", status, "x", null, 1, null, null);
    }

    private static Message mensagem(String corpo, String routingKey, String headerCnpj) {
        final var props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setReceivedRoutingKey(routingKey);
        props.setHeader("x-cnpj", headerCnpj);
        return new Message(corpo.getBytes(StandardCharsets.UTF_8), props);
    }
}
