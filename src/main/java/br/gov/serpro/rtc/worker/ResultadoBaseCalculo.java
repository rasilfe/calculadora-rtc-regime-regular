package br.gov.serpro.rtc.worker;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import br.gov.serpro.rtc.api.exceptionhandler.dto.ErroDetalhe;
import br.gov.serpro.rtc.api.model.output.basecalculo.BaseCalculoUnificadaOutput;

/**
 * Resultado do cálculo da base unificada (contrato da fila {@code rtc.basecalculo.saida.<CNPJ>}).
 *
 * @param schemaVersion versão do contrato do envelope
 * @param correlationId identificador do pedido de entrada
 * @param cnpj          CNPJ da empresa (define a fila de saída)
 * @param idCalculo     identificador do pedido na empresa, se informado
 * @param loteId        agrupador, se informado
 * @param status        {@code SUCESSO} ou {@code ERRO} (dados inválidos)
 * @param versaoApp     versão da calculadora que produziu o resultado
 * @param processadoEm  instante do fim do processamento
 * @param duracaoMs     tempo de cálculo em milissegundos
 * @param erros         motivos da recusa, quando {@code status} for {@code ERRO}
 * @param resultado     bases RG, IS e SN e avisos (ausente quando {@code status} for {@code ERRO})
 */
@JsonInclude(NON_NULL)
public record ResultadoBaseCalculo(
        String schemaVersion,
        UUID correlationId,
        String cnpj,
        String idCalculo,
        String loteId,
        Status status,
        String versaoApp,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX") OffsetDateTime processadoEm,
        long duracaoMs,
        List<ErroDetalhe> erros,
        BaseCalculoUnificadaOutput resultado) {

    public static final String SCHEMA_VERSION = "1";

    /** Situação do pedido. */
    public enum Status {
        /** Bases calculadas (podem vir avisos sobre campos ignorados). */
        SUCESSO,
        /** Dados inválidos: nenhuma base calculada; ver {@code erros}. */
        ERRO
    }
}
