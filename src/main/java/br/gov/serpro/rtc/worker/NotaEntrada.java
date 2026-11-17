package br.gov.serpro.rtc.worker;

import java.time.OffsetDateTime;
import java.util.UUID;

import br.gov.serpro.rtc.api.model.input.OperacaoInput;

/**
 * Envelope de uma nota fiscal a calcular (contrato da Fila de Entrada).
 *
 * <p>O {@code payload} é o mesmo {@link OperacaoInput} aceito pela antiga API
 * HTTP, de modo que validações e regras de negócio permanecem inalteradas.
 *
 * @param schemaVersion versão do contrato do envelope
 * @param correlationId identificador único da mensagem, usado para idempotência
 * @param idNota        chave natural da nota (ex.: chave de acesso)
 * @param loteId        identificador da rodada à qual a nota pertence
 * @param produzidoEm   instante em que o produtor gerou a mensagem
 * @param payload       operação a calcular, com todos os itens da nota
 */
public record NotaEntrada(
        String schemaVersion,
        UUID correlationId,
        String idNota,
        String loteId,
        OffsetDateTime produzidoEm,
        OperacaoInput payload) {
}
