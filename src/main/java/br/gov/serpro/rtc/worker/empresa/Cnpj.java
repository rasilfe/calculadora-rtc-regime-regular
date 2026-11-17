package br.gov.serpro.rtc.worker.empresa;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CNPJ da empresa dona da mensagem (identificador do inquilino na fila).
 *
 * <p>Aceita o formato numérico e o alfanumérico (IN RFB nº 2.229/2024): 12
 * caracteres {@code [0-9A-Z]} seguidos de 2 dígitos verificadores numéricos. Cada
 * caractere vale {@code código ASCII - 48} (dígitos 0-9, letras A=17 ... Z=42), com
 * os mesmos pesos e o mesmo módulo 11 do CNPJ numérico.
 *
 * <p>A forma canônica tem 14 caracteres, sem pontuação e em maiúsculas.
 */
public record Cnpj(String valor) {

    private static final Pattern FORMATO = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");
    private static final Pattern PONTUACAO = Pattern.compile("[./\\-\\s]");
    private static final int[] PESOS_DV1 = { 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };
    private static final int[] PESOS_DV2 = { 6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };

    public Cnpj {
        if (!valido(valor)) {
            throw new IllegalArgumentException("CNPJ inválido: " + valor);
        }
    }

    /** Normaliza (remove pontuação, maiúsculas) e valida; vazio se inválido. */
    public static Optional<Cnpj> de(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        final String normalizado = PONTUACAO.matcher(texto.trim()).replaceAll("").toUpperCase(java.util.Locale.ROOT);
        return valido(normalizado) ? Optional.of(new Cnpj(normalizado)) : Optional.empty();
    }

    static boolean valido(String cnpj) {
        if (cnpj == null || !FORMATO.matcher(cnpj).matches() || cnpj.chars().distinct().count() == 1) {
            return false;
        }
        final int dv1 = digito(cnpj, PESOS_DV1);
        final int dv2 = digito(cnpj, PESOS_DV2);
        return cnpj.charAt(12) - '0' == dv1 && cnpj.charAt(13) - '0' == dv2;
    }

    private static int digito(String cnpj, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (cnpj.charAt(i) - '0') * pesos[i];
        }
        final int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    @Override
    public String toString() {
        return valor;
    }
}
