package br.gov.serpro.rtc.worker;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Publica o {@link ResultadoNota} na Fila de Saída e só retorna depois que o
 * broker confirma a publicação (publisher confirms).
 *
 * <p>A routing key é {@code resultado.<CNPJ>} (notas) ou {@code resultado-base.<CNPJ>}
 * (base de cálculo): cada empresa só recebe os próprios resultados. Se a empresa não tiver fila de saída, a mensagem volta
 * ({@code basic.return}) e a nota permanece na Fila de Entrada.
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
        final Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x-id-nota", resultado.idNota());
        headers.put("x-status", resultado.status().name());
        headers.put("x-versao-base", resultado.versaoBase());
        publicar(resultado, resultado.correlationId().toString(), resultado.cnpj(),
                routingKeyDaEmpresa(resultado.cnpj()), headers);
    }

    /** Resultado da base de cálculo: routing key {@code resultado-base.<CNPJ>}. */
    public void publicarBaseConfirmado(ResultadoBaseCalculo resultado) {
        final Map<String, Object> headers = new LinkedHashMap<>();
        if (resultado.idCalculo() != null) {
            headers.put("x-id-calculo", resultado.idCalculo());
        }
        headers.put("x-status", resultado.status().name());
        if (resultado.tipo() != null) {
            headers.put("x-tipo", resultado.tipo().name());
        }
        publicar(resultado, resultado.correlationId().toString(), resultado.cnpj(),
                routingKeyBaseDaEmpresa(resultado.cnpj()), headers);
    }

    private void publicar(Object resultado, String correlationId, String cnpj, String routingKey,
            Map<String, Object> headers) {
        final Message mensagem = criarMensagem(resultado, correlationId, cnpj, headers);
        final var correlacao = new CorrelationData(correlationId);
        rabbitTemplate.send(properties.getExchangeSaida(), routingKey, mensagem, correlacao);
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
                    + routingKey + "'; a empresa tem fila de saída?): " + correlacao.getReturned().getReplyText(), null);
        }
    }

    /** {@code resultado.<CNPJ>}: só a fila de saída da empresa recebe o resultado. */
    String routingKeyDaEmpresa(String cnpj) {
        if (cnpj == null || cnpj.isBlank()) {
            throw new IllegalStateException("Resultado sem CNPJ: não há como rotear para a fila da empresa");
        }
        return properties.getMultiEmpresa().getPrefixoRoutingKeySaida() + cnpj;
    }

    /** {@code resultado-base.<CNPJ>}: só a fila de base de cálculo da empresa recebe o resultado. */
    String routingKeyBaseDaEmpresa(String cnpj) {
        if (cnpj == null || cnpj.isBlank()) {
            throw new IllegalStateException("Resultado sem CNPJ: não há como rotear para a fila da empresa");
        }
        return properties.getBaseCalculo().getPrefixoRoutingKeySaida() + cnpj;
    }

    private Message criarMensagem(Object resultado, String correlationId, String cnpj, Map<String, Object> headers) {
        final byte[] corpo;
        try {
            corpo = objectMapper.writeValueAsBytes(resultado);
        } catch (JacksonException e) {
            throw new IllegalStateException("Falha ao serializar o resultado " + correlationId, e);
        }
        final var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(correlationId);
        props.setCorrelationId(correlationId);
        props.setHeader(properties.getMultiEmpresa().getHeaderCnpj(), cnpj);
        headers.forEach(props::setHeader);
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
