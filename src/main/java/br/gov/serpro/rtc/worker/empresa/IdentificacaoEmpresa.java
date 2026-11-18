package br.gov.serpro.rtc.worker.empresa;

import org.springframework.amqp.core.MessageProperties;

import br.gov.serpro.rtc.worker.WorkerProperties;

/**
 * Descobre de qual empresa (CNPJ) é a mensagem da Fila de Entrada.
 *
 * <p>A fila de entrada é compartilhada entre empresas. A empresa vem do header
 * {@code x-cnpj} e precisa coincidir com:
 * <ul>
 * <li>a routing key {@code nota.<CNPJ>} usada na publicação. No broker, a
 * <em>topic permission</em> de cada empresa só permite publicar com o próprio CNPJ,
 * então essa conferência impede que uma empresa se passe por outra;</li>
 * <li>o campo {@code cnpj} do corpo, quando informado.</li>
 * </ul>
 * O CNPJ resolvido define a fila de saída ({@code resultado.<CNPJ>}) e isola a
 * idempotência por empresa.
 */
public final class IdentificacaoEmpresa {

    /** Resultado da identificação: CNPJ válido, ou o motivo da recusa. */
    public record Resultado(Cnpj cnpj, String motivoRecusa) {
        public boolean identificada() {
            return cnpj != null;
        }

        static Resultado ok(Cnpj cnpj) {
            return new Resultado(cnpj, null);
        }

        static Resultado recusa(String motivo) {
            return new Resultado(null, motivo);
        }
    }

    private IdentificacaoEmpresa() {
    }

    public static Resultado identificar(MessageProperties props, String cnpjDoCorpo, WorkerProperties.MultiEmpresa config) {
        return identificar(props, cnpjDoCorpo, config, config.getPrefixoRoutingKeyEntrada());
    }

    /**
     * Como {@link #identificar(MessageProperties, String, WorkerProperties.MultiEmpresa)}, para
     * filas cuja routing key de entrada tem outro prefixo (ex.: {@code base.<CNPJ>}).
     */
    public static Resultado identificar(MessageProperties props, String cnpjDoCorpo, WorkerProperties.MultiEmpresa config,
            String prefixoRoutingKeyEntrada) {
        final Object header = props.getHeader(config.getHeaderCnpj());
        if (header == null) {
            return Resultado.recusa("header " + config.getHeaderCnpj() + " ausente");
        }
        final var cnpj = Cnpj.de(header.toString());
        if (cnpj.isEmpty()) {
            return Resultado.recusa("header " + config.getHeaderCnpj() + " com CNPJ inválido: " + header);
        }
        if (config.isExigirRoutingKeyDaEmpresa()) {
            final String esperada = prefixoRoutingKeyEntrada + cnpj.get().valor();
            final String recebida = props.getReceivedRoutingKey();
            if (!esperada.equals(recebida)) {
                return Resultado.recusa("routing key '" + recebida + "' não corresponde ao CNPJ do header (esperada '"
                        + esperada + "')");
            }
        }
        if (cnpjDoCorpo != null && !cnpjDoCorpo.isBlank()) {
            final var doCorpo = Cnpj.de(cnpjDoCorpo);
            if (doCorpo.isEmpty() || !doCorpo.get().equals(cnpj.get())) {
                return Resultado.recusa("cnpj do corpo (" + cnpjDoCorpo + ") diferente do header");
            }
        }
        return Resultado.ok(cnpj.get());
    }
}
