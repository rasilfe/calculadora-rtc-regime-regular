package br.gov.serpro.rtc.worker;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

import br.gov.serpro.rtc.api.model.input.basecalculo.BaseCalculoUnificadaNfseInput;

/**
 * Pedido de cálculo da base da NFS-e (envelope com {@code "tipo": "NFSE"} na fila
 * {@code rtc.basecalculo.entrada}).
 *
 * <p>Mesmo envelope da base unificada, com o {@code payload} do antigo
 * {@code POST /calculadora/base-calculo/base-calculo-unificada-nfse}.
 *
 * @param schemaVersion versão do contrato do envelope
 * @param correlationId identificador único do pedido (volta no resultado)
 * @param cnpj          CNPJ da empresa (opcional; se informado, igual ao header {@code x-cnpj})
 * @param idCalculo     identificador do pedido na empresa (opcional, volta no resultado)
 * @param loteId        agrupador opcional
 * @param produzidoEm   instante em que a empresa produziu o pedido
 * @param payload       ano, opção pelo Simples Nacional, situação, valores, documentos de ajuste e locação
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BaseCalculoNfseEntrada(
        String schemaVersion,
        UUID correlationId,
        String cnpj,
        String idCalculo,
        String loteId,
        @JsonDeserialize(using = DataHoraIsoDeserializer.class) OffsetDateTime produzidoEm,
        BaseCalculoUnificadaNfseInput payload) implements PedidoBaseCalculo {

    @Override
    public TipoBaseCalculo tipo() {
        return TipoBaseCalculo.NFSE;
    }

    @Override
    public BaseCalculoNfseEntrada comCnpj(String cnpjValidado) {
        return new BaseCalculoNfseEntrada(schemaVersion, correlationId, cnpjValidado, idCalculo, loteId, produzidoEm,
                payload);
    }
}
