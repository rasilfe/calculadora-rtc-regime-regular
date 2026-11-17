package br.gov.serpro.rtc.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuração do worker de cálculo (prefixo {@code worker}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    /** Liga o consumo da Fila de Entrada. Desligado nos testes. */
    private boolean habilitado = true;

    /** Fila de onde as notas são consumidas. */
    private String filaEntrada = "rtc.calculo.entrada";

    /** Exchange e routing key onde os resultados são publicados. */
    private String exchangeSaida = "rtc.calculo.out";
    private String routingKeySaida = "resultado";

    /** Tempo máximo de espera pela confirmação do broker ao publicar o resultado. */
    private Duration timeoutConfirmacao = Duration.ofSeconds(10);

    /**
     * Declara exchanges, filas, bindings e DLQs ao iniciar. Em k3s a topologia é
     * criada pelo Messaging Topology Operator; útil para desenvolvimento local.
     */
    private boolean declararTopologia = false;

    /**
     * Itens ou notas com falha técnica (código CAL-999, exceção não mapeada) fazem a
     * mensagem voltar para a fila, até o delivery-limit; depois disso, a mensagem vai
     * para a DLQ. Assim, uma indisponibilidade momentânea não vira um resultado de erro.
     */
    private boolean reprocessarFalhaTecnica = true;

    private final Idempotencia idempotencia = new Idempotencia();

    @Getter
    @Setter
    public static class Idempotencia {
        /** {@code redis} ou {@code nenhuma}. */
        private String tipo = "redis";
        /** Prefixo das chaves no Redis. */
        private String prefixo = "rtc:nota:";
        /** Duração do lease enquanto a nota está em processamento. */
        private Duration lease = Duration.ofMinutes(5);
        /** Por quanto tempo uma nota concluída é lembrada. */
        private Duration retencao = Duration.ofDays(2);
    }
}
