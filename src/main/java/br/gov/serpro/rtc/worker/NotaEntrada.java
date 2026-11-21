package br.gov.serpro.rtc.worker;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.annotation.JsonDeserialize;

import br.gov.serpro.rtc.api.model.input.OperacaoInput;

/**
 * Envelope de uma nota fiscal a calcular (contrato da Fila de Entrada).
 *
 * <p>O {@code payload} é o mesmo {@link OperacaoInput} aceito pela antiga API
 * HTTP, de modo que validações e regras de negócio permanecem inalteradas.
 *
 * @param schemaVersion versão do contrato do envelope
 * @param correlationId identificador único da mensagem, usado para idempotência
 * @param cnpj          CNPJ da empresa dona da nota. Opcional no corpo: a fonte oficial
 *                      é o header {@code x-cnpj} (e a routing key {@code nota.<CNPJ>});
 *                      se informado aqui, precisa ser o mesmo
 * @param idNota        chave natural da nota (ex.: chave de acesso)
 * @param loteId        identificador da rodada à qual a nota pertence
 * @param produzidoEm   instante em que o produtor gerou a mensagem. Metadado do envelope:
 *                      aceita qualquer data ISO-8601 (com ou sem segundos ou fração), ao
 *                      contrário das datas da operação, que seguem o formato estrito da API
 * @param payload       operação a calcular, com todos os itens da nota
 */
public record NotaEntrada(
        String schemaVersion,
        UUID correlationId,
        String cnpj,
        String idNota,
        String loteId,
        @JsonDeserialize(using = DataHoraIsoDeserializer.class) OffsetDateTime produzidoEm,
        OperacaoInput payload) {

    /** Cópia com o CNPJ já validado do envelope AMQP. */
    public NotaEntrada comCnpj(String cnpjValidado) {
        return new NotaEntrada(schemaVersion, correlationId, cnpjValidado, idNota, loteId, produzidoEm, payload);
    }
}
