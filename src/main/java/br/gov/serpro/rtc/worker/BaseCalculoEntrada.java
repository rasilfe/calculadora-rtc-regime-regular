package br.gov.serpro.rtc.worker;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import br.gov.serpro.rtc.api.model.input.basecalculo.BaseCalculoUnificadaInput;

/**
 * Pedido de cálculo da base unificada (contrato da fila {@code rtc.basecalculo.entrada}).
 *
 * <p>Mesmo envelope da nota, com o {@code payload} do antigo
 * {@code POST /calculadora/base-calculo/base-calculo-unificada}.
 *
 * @param schemaVersion versão do contrato do envelope
 * @param correlationId identificador único do pedido (volta no resultado)
 * @param cnpj          CNPJ da empresa (opcional; se informado, igual ao header {@code x-cnpj})
 * @param idCalculo     identificador do pedido na empresa (opcional, volta no resultado)
 * @param loteId        agrupador opcional
 * @param produzidoEm   instante em que a empresa produziu o pedido
 * @param payload       componentes e contexto do item (ano, tipo de documento, natureza)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BaseCalculoEntrada(
        String schemaVersion,
        UUID correlationId,
        String cnpj,
        String idCalculo,
        String loteId,
        @JsonDeserialize(using = DataHoraIsoDeserializer.class) OffsetDateTime produzidoEm,
        BaseCalculoUnificadaInput payload) implements PedidoBaseCalculo {

    @Override
    public TipoBaseCalculo tipo() {
        return TipoBaseCalculo.UNIFICADA;
    }

    /** Cópia com o CNPJ já validado do envelope AMQP. */
    @Override
    public BaseCalculoEntrada comCnpj(String cnpjValidado) {
        return new BaseCalculoEntrada(schemaVersion, correlationId, cnpjValidado, idCalculo, loteId, produzidoEm, payload);
    }
}
