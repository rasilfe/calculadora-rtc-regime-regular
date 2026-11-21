package br.gov.serpro.rtc.worker;

import java.util.Locale;
import java.util.Optional;

/**
 * Cálculo pedido na fila {@code rtc.basecalculo.entrada}, escolhido pelo campo {@code tipo}
 * do envelope.
 */
public enum TipoBaseCalculo {

    /** Base unificada do item de NF-e e NFC-e (RG, IS e SN); é o padrão quando {@code tipo} é omitido. */
    UNIFICADA,

    /** Base da NFS-e (IBS/CBS e Simples Nacional), algoritmo oficial do endpoint {@code base-calculo-unificada-nfse}. */
    NFSE,

    /** Base do Imposto Seletivo sobre mercadorias, algoritmo oficial do endpoint {@code is-mercadorias}. */
    IS_MERCADORIAS;

    /**
     * Lê o {@code tipo} do envelope (sem diferenciar maiúsculas).
     *
     * @return o tipo; {@link #UNIFICADA} se ausente; vazio se desconhecido
     */
    public static Optional<TipoBaseCalculo> doEnvelope(String tipo) {
        if (tipo == null || tipo.isBlank()) {
            return Optional.of(UNIFICADA);
        }
        try {
            return Optional.of(valueOf(tipo.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
