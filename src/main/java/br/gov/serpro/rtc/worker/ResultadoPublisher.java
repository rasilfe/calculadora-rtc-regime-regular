package br.gov.serpro.rtc.worker;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publica o {@link ResultadoNota} na Fila de Saída e só retorna depois que o
 * broker confirma a publicação (publisher confirms).
 *
 * <p>As confirmações são assíncronas no canal AMQP; quem espera é apenas a thread
 * virtual do consumidor, o que é barato. Com {@code mandatory=true}, uma mensagem
 * sem fila de destino volta como {@code basic.return}, que chega antes da
 * confirmação e também é tratada como falha.
 */
@Component
@ConditionalOnProperty(prefix = "worker", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class ResultadoPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;

    public ResultadoPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, WorkerProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publicarConfirmado(ResultadoNota resultado) {
        final Message mensagem = criarMensagem(resultado);
        final var correlacao = new CorrelationData(resultado.correlationId().toString());
        rabbitTemplate.send(properties.getExchangeSaida(), properties.getRoutingKeySaida(), mensagem, correlacao);
        final CorrelationData.Confirm confirmacao;
        try {
            confirmacao = correlacao.getFuture().get(properties.getTimeoutConfirmacao().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublicacaoFalhouException("Interrompido aguardando a confirmação do broker", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublicacaoFalhouException("Sem confirmação do broker em " + properties.getTimeoutConfirmacao(), e);
        }
        if (!confirmacao.isAck()) {
            throw new PublicacaoFalhouException("Broker recusou a publicação: " + confirmacao.getReason(), null);
        }
        if (correlacao.getReturned() != null) {
            throw new PublicacaoFalhouException("Mensagem devolvida pelo broker (sem fila para a routing key '"
                    + properties.getRoutingKeySaida() + "'): " + correlacao.getReturned().getReplyText(), null);
        }
    }

    private Message criarMensagem(ResultadoNota resultado) {
        final byte[] corpo;
        try {
            corpo = objectMapper.writeValueAsBytes(resultado);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o resultado da nota " + resultado.correlationId(), e);
        }
        final var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(resultado.correlationId().toString());
        props.setCorrelationId(resultado.correlationId().toString());
        props.setHeader("x-id-nota", resultado.idNota());
        props.setHeader("x-status", resultado.status().name());
        props.setHeader("x-versao-base", resultado.versaoBase());
        return new Message(corpo, props);
    }

    /** Falha ao publicar: a nota volta para a Fila de Entrada. */
    public static class PublicacaoFalhouException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PublicacaoFalhouException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
