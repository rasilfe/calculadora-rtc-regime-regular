package br.gov.serpro.rtc.worker;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import br.gov.serpro.rtc.api.exceptionhandler.dto.ErroDetalhe;
import br.gov.serpro.rtc.api.model.roc.ObservabilidadeROCDomain;

/**
 * Resultado do cálculo de uma nota fiscal (contrato da Fila de Saída).
 *
 * <p>Cada item traz seu próprio {@code estadoItem} e, quando houver, seus
 * erros; um item com erro não impede o cálculo dos demais. O total é somado
 * apenas a partir dos itens calculados.
 *
 * @param schemaVersion       versão do contrato do envelope
 * @param correlationId       identificador da mensagem de entrada
 * @param idNota              chave natural da nota
 * @param loteId              rodada à qual a nota pertence
 * @param status              situação consolidada da nota
 * @param versaoApp           versão da calculadora que produziu o resultado
 * @param versaoBase          versão da base de regras usada no cálculo
 * @param avisoDadosSimulados código do aviso de dados simulados, quando houver
 * @param processadoEm        instante do fim do processamento
 * @param duracaoMs           tempo de cálculo da nota em milissegundos
 * @param errosNota           erros que impediram o processamento da nota inteira
 * @param resultado           resultado por item e totais (ausente se {@code status} for ERRO por erro de nota)
 */
@JsonInclude(NON_NULL)
public record ResultadoNota(
        String schemaVersion,
        UUID correlationId,
        String idNota,
        String loteId,
        StatusNota status,
        String versaoApp,
        String versaoBase,
        Integer avisoDadosSimulados,
        OffsetDateTime processadoEm,
        long duracaoMs,
        List<ErroDetalhe> errosNota,
        ObservabilidadeROCDomain resultado) {

    public static final String SCHEMA_VERSION = "1";
}
