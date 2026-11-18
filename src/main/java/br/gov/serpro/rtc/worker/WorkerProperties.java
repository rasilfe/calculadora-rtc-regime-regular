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

    /**
     * Exchange (topic) onde os resultados são publicados, com routing key
     * {@code <prefixo-routing-key-saida><CNPJ>}: cada empresa tem a própria fila de saída.
     */
    private String exchangeSaida = "rtc.calculo.out";

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

    private final MultiEmpresa multiEmpresa = new MultiEmpresa();

    private final BaseCalculo baseCalculo = new BaseCalculo();

    /**
     * Fila de cálculo da base unificada (RG/IBS-CBS, IS e Simples Nacional de um item),
     * separada da fila de notas para não esperar atrás de uma rodada.
     */
    @Getter
    @Setter
    public static class BaseCalculo {
        /** Liga o consumo da fila de base de cálculo. */
        private boolean habilitado = true;
        /** Fila de onde os pedidos de base de cálculo são consumidos. */
        private String filaEntrada = "rtc.basecalculo.entrada";
        /** Routing key de entrada: {@code base.<CNPJ>}. */
        private String prefixoRoutingKeyEntrada = "base.";
        /** Routing key de saída: {@code resultado-base.<CNPJ>} (fila {@code rtc.basecalculo.saida.<CNPJ>}). */
        private String prefixoRoutingKeySaida = "resultado-base.";
    }

    @Getter
    @Setter
    public static class MultiEmpresa {
        /** Header AMQP com o CNPJ da empresa dona da nota. */
        private String headerCnpj = "x-cnpj";
        /** Routing key de entrada: {@code nota.<CNPJ>}. */
        private String prefixoRoutingKeyEntrada = "nota.";
        /** Routing key de saída: {@code resultado.<CNPJ>}. */
        private String prefixoRoutingKeySaida = "resultado.";
        /**
         * Exige que a routing key de entrada seja a da empresa do header. Com a topic
         * permission por empresa no broker, impede que uma empresa publique em nome de outra.
         */
        private boolean exigirRoutingKeyDaEmpresa = true;
    }

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
