package br.gov.serpro.rtc.worker;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import br.gov.serpro.rtc.api.model.roc.ObservabilidadeItemDomain;
import br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum;
import br.gov.serpro.rtc.worker.idempotencia.IdempotenciaService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * Consome a Fila de Entrada: uma mensagem é uma nota fiscal completa.
 *
 * <p>Fluxo, com ack manual:
 * <ol>
 * <li>converte o JSON; mensagem ilegível vai direto para a DLQ ({@code basicReject} sem requeue);</li>
 * <li>verifica a idempotência pelo {@code correlationId};</li>
 * <li>calcula a nota ({@link CalculoNotaService}); erros de negócio por item fazem parte do resultado;</li>
 * <li>publica o resultado e espera a confirmação do broker;</li>
 * <li>só então confirma a mensagem de entrada ({@code basicAck}).</li>
 * </ol>
 * Falhas transitórias (publicação, banco, erro técnico não mapeado) devolvem a
 * mensagem para a fila ({@code basicNack} com requeue); o {@code delivery-limit}
 * da fila quorum manda para a DLQ a mensagem que falhar repetidamente.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "worker", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class CalculoNotaListener {

    public static final String ID_CONTAINER = "calculoNota";
    static final String CODIGO_FALHA_TECNICA = "CAL-999";

    private final CalculoNotaService calculoNotaService;
    private final ResultadoPublisher publisher;
    private final IdempotenciaService idempotencia;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;
    private final MeterRegistry registry;
    private final Timer duracao;

    public CalculoNotaListener(CalculoNotaService calculoNotaService, ResultadoPublisher publisher,
            IdempotenciaService idempotencia, ObjectMapper objectMapper, WorkerProperties properties,
            MeterRegistry registry) {
        this.calculoNotaService = calculoNotaService;
        this.publisher = publisher;
        this.idempotencia = idempotencia;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.registry = registry;
        this.duracao = Timer.builder("rtc.worker.nota.duracao")
                .description("Tempo para calcular e publicar uma nota (sem a espera na fila)")
                .publishPercentileHistogram()
                .register(registry);
    }

    @RabbitListener(id = ID_CONTAINER, queues = "${worker.fila-entrada}", ackMode = "MANUAL")
    public void receber(Message mensagem, Channel canal) throws IOException {
        final long tag = mensagem.getMessageProperties().getDeliveryTag();
        final boolean reentrega = Boolean.TRUE.equals(mensagem.getMessageProperties().isRedelivered());

        final NotaEntrada nota = ler(mensagem);
        if (nota == null) {
            contar("rejeitada_ilegivel");
            canal.basicReject(tag, false);
            return;
        }

        try (var mdcCorrelacao = MDC.putCloseable("correlationId", nota.correlationId().toString());
                var mdcNota = MDC.putCloseable("idNota", nota.idNota())) {
            final String hash = sha256(mensagem.getBody());
            switch (idempotencia.adquirir(nota.correlationId(), hash, reentrega)) {
                case JA_CONCLUIDA -> {
                    log.info("Nota já processada; confirmando sem recalcular");
                    contar("duplicada_concluida");
                    canal.basicAck(tag, false);
                    return;
                }
                case EM_ANDAMENTO -> {
                    log.info("Envio duplicado de nota em processamento; descartando esta cópia");
                    contar("duplicada_em_andamento");
                    canal.basicAck(tag, false);
                    return;
                }
                case CONFLITO -> {
                    log.error("correlationId reutilizado com conteúdo diferente; enviando para a DLQ");
                    contar("rejeitada_conflito");
                    canal.basicReject(tag, false);
                    return;
                }
                case ADQUIRIDA -> { /* segue */ }
            }

            final Timer.Sample amostra = Timer.start(registry);
            try {
                final ResultadoNota resultado = calculoNotaService.calcular(nota);
                if (properties.isReprocessarFalhaTecnica() && possuiFalhaTecnica(resultado)) {
                    throw new FalhaTecnicaException();
                }
                publisher.publicarConfirmado(resultado);
                idempotencia.concluir(nota.correlationId(), hash);
                canal.basicAck(tag, false);
                amostra.stop(duracao);
                registry.counter("rtc.worker.notas", "resultado", "publicada", "status", resultado.status().name())
                        .increment();
            } catch (RuntimeException e) {
                log.warn("Falha transitória ao processar a nota (reentrega={}); devolvendo para a fila: {}",
                        reentrega, e.toString());
                contar("devolvida_fila");
                idempotencia.liberar(nota.correlationId());
                canal.basicNack(tag, false, true);
            }
        }
    }

    private NotaEntrada ler(Message mensagem) {
        try {
            final NotaEntrada nota = objectMapper.readValue(mensagem.getBody(), NotaEntrada.class);
            if (nota == null || nota.correlationId() == null || nota.payload() == null) {
                log.error("Mensagem sem correlationId ou payload (messageId={}); enviando para a DLQ",
                        mensagem.getMessageProperties().getMessageId());
                return null;
            }
            return nota;
        } catch (IOException e) {
            log.error("Mensagem ilegível (messageId={}); enviando para a DLQ: {}",
                    mensagem.getMessageProperties().getMessageId(), e.getMessage());
            return null;
        }
    }

    /** Erro técnico não mapeado (CAL-999) na nota ou em algum item. */
    static boolean possuiFalhaTecnica(ResultadoNota resultado) {
        if (resultado.errosNota() != null
                && resultado.errosNota().stream().anyMatch(e -> CODIGO_FALHA_TECNICA.equals(e.code()))) {
            return true;
        }
        return resultado.resultado() != null && resultado.resultado().getObjetos().stream()
                .map(ObservabilidadeItemDomain::getEstadoItem)
                .anyMatch(EstadoItemEnum.FALHA_TECNICA::equals);
    }

    private void contar(String resultado) {
        registry.counter("rtc.worker.notas", "resultado", resultado, "status", "nenhum").increment();
    }

    static String sha256(byte[] corpo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Falha técnica no cálculo: a nota volta para a fila para nova tentativa. */
    static final class FalhaTecnicaException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FalhaTecnicaException() {
            super("Falha técnica (" + CODIGO_FALHA_TECNICA + ") no cálculo da nota");
        }
    }
}
