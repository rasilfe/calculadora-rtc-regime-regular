package br.gov.serpro.rtc.worker;

/**
 * Situação consolidada de uma nota após o cálculo.
 */
public enum StatusNota {
    /** Todos os itens foram calculados (inclusive cálculos simulados). */
    SUCESSO,
    /** Ao menos um item foi calculado e ao menos um item teve erro. */
    PARCIAL,
    /** Nenhum item foi calculado, ou a nota falhou antes do cálculo dos itens. */
    ERRO
}
