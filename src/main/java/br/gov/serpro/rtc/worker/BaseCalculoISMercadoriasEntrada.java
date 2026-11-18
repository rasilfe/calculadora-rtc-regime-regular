package br.gov.serpro.rtc.worker;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import br.gov.serpro.rtc.api.model.input.basecalculo.BaseCalculoISMercadoriasInput;

/**
 * Pedido de cálculo da base do Imposto Seletivo sobre mercadorias (envelope com
 * {@code "tipo": "IS_MERCADORIAS"} na fila {@code rtc.basecalculo.entrada}).
 *
 * <p>Mesmo envelope da base unificada, com o {@code payload} do antigo
 * {@code POST /calculadora/base-calculo/is-mercadorias}.
 *
 * @param schemaVersion versão do contrato do envelope
 * @param correlationId identificador único do pedido (volta no resultado)
 * @param cnpj          CNPJ da empresa (opcional; se informado, igual ao header {@code x-cnpj})
 * @param idCalculo     identificador do pedido na empresa (opcional, volta no resultado)
 * @param loteId        agrupador opcional
 * @param produzidoEm   instante em que a empresa produziu o pedido
 * @param payload       ano e valores que integram e que não integram a base do IS
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BaseCalculoISMercadoriasEntrada(
        String schemaVersion,
        UUID correlationId,
        String cnpj,
        String idCalculo,
        String loteId,
        @JsonDeserialize(using = DataHoraIsoDeserializer.class) OffsetDateTime produzidoEm,
        BaseCalculoISMercadoriasInput payload) implements PedidoBaseCalculo {

    @Override
    public TipoBaseCalculo tipo() {
        return TipoBaseCalculo.IS_MERCADORIAS;
    }

    @Override
    public BaseCalculoISMercadoriasEntrada comCnpj(String cnpjValidado) {
        return new BaseCalculoISMercadoriasEntrada(schemaVersion, correlationId, cnpjValidado, idCalculo, loteId,
                produzidoEm, payload);
    }
}
