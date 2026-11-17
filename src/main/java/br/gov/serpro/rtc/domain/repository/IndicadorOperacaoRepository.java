/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.domain.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import br.gov.serpro.rtc.api.model.output.nfse.NfseSituacaoClassificacaoOutput;
import br.gov.serpro.rtc.domain.model.entity.IndicadorOpNbsClassificacao;

/**
 * Repositório Spring Data JPA responsável por validar e consultar indicadores
 * de operação associados a serviços. Recupera combinações entre NBS,
 * classificação tributária e indicador de operação, além de dados usados na
 * determinação do local de incidência.
 */
@Repository
public interface IndicadorOperacaoRepository extends JpaRepository<IndicadorOpNbsClassificacao, Long> {

    @NativeQuery(value = """
        SELECT CASE WHEN EXISTS (
            SELECT 1
            FROM CLASSIF_NBS_INDOP_LC c
            INNER JOIN CLASSIFICACAO_TRIBUTARIA ct ON c.CNIL_CLTR_ID = ct.CLTR_ID
            INNER JOIN INDICADOR_OPERACAO_IBS_CBS io ON c.CNIL_IOIC_ID = io.IOIC_ID
            WHERE c.CNIL_NBS_CD = :nbsCd
              AND ct.CLTR_CD = :cClassTrib
              AND io.IOIC_CD = :cIndOp
              AND (CAST(:cTribNac AS text) IS NULL OR c.CNIL_LSLC_CD = :cTribNac)
              AND c.CNIL_INICIO_VIGENCIA <= CAST(:dataOcorrenciaFatoGerador AS date)
              AND (c.CNIL_FIM_VIGENCIA IS NULL 
                   OR c.CNIL_FIM_VIGENCIA >= CAST(:dataOcorrenciaFatoGerador AS date))
        ) THEN 1 ELSE 0 END
            """)
    int validarCombinacaoExiste(
            @Param("nbsCd") String nbsCd,
            @Param("cClassTrib") String cClassTrib,
            @Param("cIndOp") String cIndOp,
            @Param("cTribNac") String cTribNac,
            @Param("dataOcorrenciaFatoGerador") String dataOcorrenciaFatoGerador);

    @NativeQuery(value = """
        SELECT 
            io.IOIC_CD_LOCAL_FORNECIMENTO_DFE,
            io.IOIC_CD_LOCAL_INCIDENCIA
        FROM INDICADOR_OPERACAO_IBS_CBS io
        WHERE io.IOIC_CD = :cIndOp
          AND CAST(:data AS date) BETWEEN io.IOIC_INICIO_VIGENCIA AND COALESCE(io.IOIC_FIM_VIGENCIA, CAST(:data AS date))
            """)
    Object[] buscarLocalOperacaoPorIndOp(
            @Param("cIndOp") String cIndOp,
            @Param("data") String data);
    
    @NativeQuery(value = """
        SELECT 
            io.IOIC_CD,
            io.IOIC_TIPO_OPERACAO,
            io.IOIC_CD_LOCAL_FORNECIMENTO_DFE,
            io.IOIC_LOCAL_INCIDENCIA,
            io.IOIC_CD_LOCAL_INCIDENCIA,
            NULL,
            NULL
        FROM
            INDICADOR_OPERACAO_IBS_CBS io
        WHERE
            io.IOIC_TIPO_OPERACAO IS NOT NULL
            AND :data BETWEEN io.IOIC_INICIO_VIGENCIA AND COALESCE(io.IOIC_FIM_VIGENCIA, :data)
        ORDER BY io.IOIC_CD
        """)
    List<Object[]> buscarIndicadorOperacaoPorData(@Param("data") LocalDate data);
    
    @NativeQuery(value = """
        SELECT DISTINCT
            io.IOIC_CD,
            io.IOIC_TIPO_OPERACAO,
            io.IOIC_CD_LOCAL_FORNECIMENTO_DFE,
            io.IOIC_LOCAL_INCIDENCIA,
            io.IOIC_CD_LOCAL_INCIDENCIA,
            c.CNIL_IN_PS_ONEROSA,
            c.CNIL_IN_ADQ_EXTERIOR
        FROM
            INDICADOR_OPERACAO_IBS_CBS io
            INNER JOIN CLASSIF_NBS_INDOP_LC c ON io.IOIC_ID = c.CNIL_IOIC_ID
        WHERE
            c.CNIL_NBS_CD = :nbs
            AND :data BETWEEN io.IOIC_INICIO_VIGENCIA AND COALESCE(io.IOIC_FIM_VIGENCIA, :data)
            AND :data BETWEEN c.CNIL_INICIO_VIGENCIA AND COALESCE(c.CNIL_FIM_VIGENCIA, :data)
            AND io.IOIC_TIPO_OPERACAO IS NOT NULL
        ORDER BY io.IOIC_CD
        """)
    List<Object[]> buscarIndicadorOperacaoPorDataComNbs(
            @Param("data") LocalDate data,
            @Param("nbs") String nbs);
    
    @NativeQuery(value = """
        SELECT DISTINCT
            st.SITR_CD AS codigoSituacaoTributaria,
            st.SITR_DESCRICAO AS descricaoSituacaoTributaria,
            ct.CLTR_CD AS codigoClassificacaoTributaria,
            ct.CLTR_DESCRICAO AS descricaoClassificacaoTributaria
        FROM
            CLASSIF_NBS_INDOP_LC c
            INNER JOIN CLASSIFICACAO_TRIBUTARIA ct ON c.CNIL_CLTR_ID = ct.CLTR_ID
            INNER JOIN SITUACAO_TRIBUTARIA st ON ct.CLTR_SITR_ID = st.SITR_ID
        WHERE
            c.CNIL_NBS_CD = :nbs
            AND CAST(:data AS date) BETWEEN c.CNIL_INICIO_VIGENCIA AND COALESCE(c.CNIL_FIM_VIGENCIA, CAST(:data AS date))
            AND CAST(:data AS date) BETWEEN ct.CLTR_INICIO_VIGENCIA AND COALESCE(ct.CLTR_FIM_VIGENCIA, CAST(:data AS date))
            AND CAST(:data AS date) BETWEEN st.SITR_INICIO_VIGENCIA AND COALESCE(st.SITR_FIM_VIGENCIA, CAST(:data AS date))
        ORDER BY ct.CLTR_CD, st.SITR_CD
        """)
    List<NfseSituacaoClassificacaoOutput> buscarSituacoesClassificacoesPorNbsEData(
            @Param("nbs") String nbs,
            @Param("data") String data);

    @NativeQuery(value = """
        SELECT
            st.SITR_CD AS codigoSituacaoTributaria,
            st.SITR_DESCRICAO AS descricaoSituacaoTributaria,
            ct.CLTR_CD AS codigoClassificacaoTributaria,
            ct.CLTR_DESCRICAO AS descricaoClassificacaoTributaria
        FROM CLASSIFICACAO_TRIBUTARIA ct
        INNER JOIN SITUACAO_TRIBUTARIA st ON ct.CLTR_SITR_ID = st.SITR_ID
        WHERE ct.CLTR_CD = :codigoClassificacaoTributaria
          AND CAST(:data AS date) BETWEEN ct.CLTR_INICIO_VIGENCIA AND COALESCE(ct.CLTR_FIM_VIGENCIA, CAST(:data AS date))
          AND CAST(:data AS date) BETWEEN st.SITR_INICIO_VIGENCIA AND COALESCE(st.SITR_FIM_VIGENCIA, CAST(:data AS date))
        LIMIT 1
        """)
    NfseSituacaoClassificacaoOutput buscarSituacaoClassificacaoPorClassificacao(
            @Param("codigoClassificacaoTributaria") String codigoClassificacaoTributaria,
            @Param("data") String data);
}
