package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import br.gov.serpro.rtc.api.exceptionhandler.dto.ErroDetalhe;
import br.gov.serpro.rtc.api.model.roc.ObservabilidadeItemDomain;
import br.gov.serpro.rtc.api.model.roc.ObservabilidadeROCDomain;
import br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum;
import br.gov.serpro.rtc.worker.idempotencia.IdempotenciaService;
import br.gov.serpro.rtc.worker.idempotencia.IdempotenciaService.Aquisicao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Caminhos de ack, reject e nack do consumidor da Fila de Entrada.
 */
class CalculoNotaListenerTest {

    private static final long TAG = 42L;
    private static final String CNPJ = "12ABC34501DE35";

    private CalculoNotaService calculo;
    private ResultadoPublisher publisher;
    private IdempotenciaService idempotencia;
    private Channel canal;
    private WorkerProperties properties;
    private SimpleMeterRegistry registry;
    private CalculoNotaListener listener;
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        calculo = mock(CalculoNotaService.class);
        publisher = mock(ResultadoPublisher.class);
        idempotencia = mock(IdempotenciaService.class);
        canal = mock(Channel.class);
        properties = new WorkerProperties();
        registry = new SimpleMeterRegistry();
        listener = new CalculoNotaListener(calculo, publisher, idempotencia, new ObjectMapper(),
                properties, registry);
        when(idempotencia.adquirir(anyString(), any(), anyString(), anyBoolean())).thenReturn(Aquisicao.ADQUIRIDA);
    }

    @Test
    void sucessoPublicaConcluiEConfirma() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.PARCIAL, EstadoItemEnum.CALCULADO,
                EstadoItemEnum.INCONSISTENCIA_ENTRADA));

        listener.receber(mensagem(notaValida(), false), canal);

        verify(publisher).publicarConfirmado(any());
        verify(idempotencia).concluir(eq(CNPJ), eq(correlationId), anyString());
        verify(canal).basicAck(TAG, false);
        verify(canal, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(registry.counter("rtc.worker.notas", "resultado", "publicada", "status", "PARCIAL").count())
                .isEqualTo(1);
    }

    @Test
    void mensagemIlegivelVaiParaDlq() throws Exception {
        listener.receber(mensagem("{nao e json", false), canal);

        verify(canal).basicReject(TAG, false);
        verifyNoInteractions(calculo, publisher, idempotencia);
    }

    @Test
    void mensagemSemCorrelationIdVaiParaDlq() throws Exception {
        listener.receber(mensagem("{\"idNota\":\"n1\",\"payload\":{}}", false), canal);

        verify(canal).basicReject(TAG, false);
        verifyNoInteractions(calculo, publisher);
    }

    @Test
    void notaJaConcluidaConfirmaSemRecalcular() throws Exception {
        when(idempotencia.adquirir(anyString(), any(), anyString(), anyBoolean())).thenReturn(Aquisicao.JA_CONCLUIDA);

        listener.receber(mensagem(notaValida(), true), canal);

        verify(canal).basicAck(TAG, false);
        verifyNoInteractions(calculo, publisher);
    }

    @Test
    void envioDuplicadoEmAndamentoConfirmaSemRecalcular() throws Exception {
        when(idempotencia.adquirir(anyString(), any(), anyString(), anyBoolean())).thenReturn(Aquisicao.EM_ANDAMENTO);

        listener.receber(mensagem(notaValida(), false), canal);

        verify(canal).basicAck(TAG, false);
        verifyNoInteractions(calculo, publisher);
    }

    @Test
    void conflitoDeConteudoVaiParaDlq() throws Exception {
        when(idempotencia.adquirir(anyString(), any(), anyString(), anyBoolean())).thenReturn(Aquisicao.CONFLITO);

        listener.receber(mensagem(notaValida(), false), canal);

        verify(canal).basicReject(TAG, false);
        verifyNoInteractions(calculo, publisher);
    }

    @Test
    void reentregaEhInformadaAIdempotencia() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.SUCESSO, EstadoItemEnum.CALCULADO));

        listener.receber(mensagem(notaValida(), true), canal);

        verify(idempotencia).adquirir(eq(CNPJ), eq(correlationId), anyString(), eq(true));
    }

    @Test
    void falhaNaPublicacaoDevolveParaFilaELiberaLease() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.SUCESSO, EstadoItemEnum.CALCULADO));
        doThrow(new ResultadoPublisher.PublicacaoFalhouException("sem confirmação", null))
                .when(publisher).publicarConfirmado(any());

        listener.receber(mensagem(notaValida(), false), canal);

        verify(idempotencia).liberar(CNPJ, correlationId);
        verify(idempotencia, never()).concluir(anyString(), any(), anyString());
        verify(canal).basicNack(TAG, false, true);
        verify(canal, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void falhaTecnicaEmItemDevolveParaFilaSemPublicar() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.PARCIAL, EstadoItemEnum.CALCULADO,
                EstadoItemEnum.FALHA_TECNICA));

        listener.receber(mensagem(notaValida(), false), canal);

        verify(publisher, never()).publicarConfirmado(any());
        verify(canal).basicNack(TAG, false, true);
    }

    @Test
    void falhaTecnicaNaNotaDevolveParaFila() throws Exception {
        final var erro = new ErroDetalhe("t", "Erro interno", CalculoNotaListener.CODIGO_FALHA_TECNICA, "#/itens/-1", "x");
        when(calculo.calcular(any())).thenReturn(new ResultadoNota("1", correlationId, CNPJ, "n1", "l1", StatusNota.ERRO,
                "v", "b", null, null, 1, List.of(erro), null));

        listener.receber(mensagem(notaValida(), false), canal);

        verify(publisher, never()).publicarConfirmado(any());
        verify(canal).basicNack(TAG, false, true);
    }

    @Test
    void falhaTecnicaPublicadaQuandoReprocessamentoDesligado() throws Exception {
        properties.setReprocessarFalhaTecnica(false);
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.PARCIAL, EstadoItemEnum.CALCULADO,
                EstadoItemEnum.FALHA_TECNICA));

        listener.receber(mensagem(notaValida(), false), canal);

        verify(publisher).publicarConfirmado(any());
        verify(canal).basicAck(TAG, false);
    }

    @Test
    void erroDeNegocioNaNotaEhPublicado() throws Exception {
        final var erro = new ErroDetalhe("t", "Município não encontrado", "REG-010", "#/municipio", "x");
        when(calculo.calcular(any())).thenReturn(new ResultadoNota("1", correlationId, CNPJ, "n1", "l1", StatusNota.ERRO,
                "v", "b", null, null, 1, List.of(erro), null));

        listener.receber(mensagem(notaValida(), false), canal);

        verify(publisher).publicarConfirmado(any());
        verify(canal).basicAck(TAG, false);
    }

    @Test
    void calculoRecebeANotaComOCnpjDaEmpresa() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.SUCESSO, EstadoItemEnum.CALCULADO));

        listener.receber(mensagem(notaValida(), false, "12.abc.345/01de-35", "nota." + CNPJ), canal);

        final var captor = org.mockito.ArgumentCaptor.forClass(NotaEntrada.class);
        verify(calculo).calcular(captor.capture());
        assertThat(captor.getValue().cnpj()).isEqualTo(CNPJ);
        verify(canal).basicAck(TAG, false);
    }

    @Test
    void semHeaderDeEmpresaVaiParaDlq() throws Exception {
        listener.receber(mensagem(notaValida(), false, null, "nota." + CNPJ), canal);

        verify(canal).basicReject(TAG, false);
        verifyNoInteractions(calculo, publisher, idempotencia);
        assertThat(registry.counter("rtc.worker.notas", "resultado", "rejeitada_empresa", "status", "nenhum").count())
                .isEqualTo(1);
    }

    @Test
    void cnpjInvalidoNoHeaderVaiParaDlq() throws Exception {
        listener.receber(mensagem(notaValida(), false, "12ABC34501DE36", "nota.12ABC34501DE36"), canal);

        verify(canal).basicReject(TAG, false);
        verifyNoInteractions(calculo, publisher, idempotencia);
    }

    @Test
    void routingKeyDeOutraEmpresaVaiParaDlq() throws Exception {
        // o header diz uma empresa, mas a publicação (validada pela topic permission) foi de outra
        listener.receber(mensagem(notaValida(), false, CNPJ, "nota.11222333000181"), canal);

        verify(canal).basicReject(TAG, false);
        verifyNoInteractions(calculo, publisher, idempotencia);
    }

    @Test
    void routingKeyNaoConferidaQuandoDesligado() throws Exception {
        properties.getMultiEmpresa().setExigirRoutingKeyDaEmpresa(false);
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.SUCESSO, EstadoItemEnum.CALCULADO));

        listener.receber(mensagem(notaValida(), false, CNPJ, "nota"), canal);

        verify(canal).basicAck(TAG, false);
    }

    @Test
    void cnpjDoCorpoDiferenteDoHeaderVaiParaDlq() throws Exception {
        final String corpo = "{\"schemaVersion\":\"1\",\"correlationId\":\"" + correlationId
                + "\",\"cnpj\":\"11222333000181\",\"idNota\":\"n1\",\"payload\":{}}";

        listener.receber(mensagem(corpo, false), canal);

        verify(canal).basicReject(TAG, false);
        verifyNoInteractions(calculo, publisher, idempotencia);
    }

    @Test
    void cnpjDoCorpoIgualAoHeaderEhAceito() throws Exception {
        when(calculo.calcular(any())).thenReturn(resultado(StatusNota.SUCESSO, EstadoItemEnum.CALCULADO));
        final String corpo = "{\"schemaVersion\":\"1\",\"correlationId\":\"" + correlationId
                + "\",\"cnpj\":\"12.ABC.345/01DE-35\",\"idNota\":\"n1\",\"payload\":{}}";

        listener.receber(mensagem(corpo, false), canal);

        verify(canal).basicAck(TAG, false);
    }

    @Test
    void mesmoCorpoGeraMesmoHash() {
        final byte[] corpo = notaValida().getBytes(StandardCharsets.UTF_8);
        assertThat(CalculoNotaListener.sha256(corpo)).isEqualTo(CalculoNotaListener.sha256(corpo.clone())).hasSize(64);
    }

    private String notaValida() {
        return "{\"schemaVersion\":\"1\",\"correlationId\":\"" + correlationId
                + "\",\"idNota\":\"n1\",\"loteId\":\"l1\",\"payload\":{}}";
    }

    private static Message mensagem(String corpo, boolean reentrega) {
        return mensagem(corpo, reentrega, CNPJ, "nota." + CNPJ);
    }

    private static Message mensagem(String corpo, boolean reentrega, String headerCnpj, String routingKey) {
        final var props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setRedelivered(reentrega);
        props.setReceivedRoutingKey(routingKey);
        if (headerCnpj != null) {
            props.setHeader("x-cnpj", headerCnpj);
        }
        return new Message(corpo.getBytes(StandardCharsets.UTF_8), props);
    }

    private ResultadoNota resultado(StatusNota status, EstadoItemEnum... estados) {
        final List<ObservabilidadeItemDomain> itens = java.util.stream.IntStream.range(0, estados.length)
                .mapToObj(i -> ObservabilidadeItemDomain.builder().nObj(i + 1).estadoItem(estados[i]).build())
                .toList();
        return new ResultadoNota("1", correlationId, CNPJ, "n1", "l1", status, "v", "b", null, null, 1, null,
                ObservabilidadeROCDomain.builder().objetos(itens).build());
    }
}
