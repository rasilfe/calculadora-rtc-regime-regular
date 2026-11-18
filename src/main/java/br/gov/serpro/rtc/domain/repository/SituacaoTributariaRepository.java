/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.domain.repository;

import java.time.LocalDate;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import br.gov.serpro.rtc.domain.model.entity.SituacaoTributaria;

/**
 * Repositório Spring Data JPA para acesso a {@link SituacaoTributaria}, com
 * consultas de situações tributárias por tributo, validação de CST e busca por
 * código vigente.
 */
@Repository
public interface SituacaoTributariaRepository extends JpaRepository<SituacaoTributaria, Long> {

	/*
	 * Entradas recomendadas na cache: 240
	 * Memória estimada: ~28 KB
	 */
	@Query("""
			SELECT EXISTS (
			    SELECT 1
			    FROM TributoSituacaoTributaria t
			    WHERE t.tributo.id = :idTributo
			    AND t.situacaoTributaria.codigo = :cst
			    AND :data BETWEEN t.inicioVigencia AND COALESCE(t.fimVigencia, :data)
			    AND :data BETWEEN t.situacaoTributaria.inicioVigencia AND COALESCE(t.situacaoTributaria.fimVigencia, :data)
			)
			""")
	@Cacheable(cacheNames = "SituacaoTributariaRepository.existeCst")
	boolean existeCst(
			@Param("cst") String cst,
			@Param("idTributo") Long idTributo,
			@Param("data") LocalDate data);

	/**
	 * Consulta uma situação tributária por código, tributo e data de vigência.
	 * Query nativa (PostgreSQL), usando COALESCE para todas as tabelas envolvidas;
	 * início de vigência nulo equivale a "sem limite inferior".
	 *
	 * <p>Executada várias vezes por item calculado, por isso o resultado fica em
	 * cache: a base de regras é somente leitura.
	 */
	@Query(value = """
			    SELECT s.*
			    FROM SITUACAO_TRIBUTARIA s
			    JOIN TRIBUTO_SITUACAO_TRIBUTARIA tst ON tst.TRST_SITR_ID = s.SITR_ID
			    JOIN TRIBUTO t ON t.TBTO_ID = tst.TRST_TBTO_ID
			    WHERE COALESCE(s.SITR_CD, '') = COALESCE(CAST(:cst AS text), '')
			      AND COALESCE(t.TBTO_ID, 0) = COALESCE(CAST(:idTributo AS integer), 0)
			      AND CAST(:data AS date) BETWEEN COALESCE(tst.TRST_INICIO_VIGENCIA, DATE '-infinity') AND COALESCE(tst.TRST_FIM_VIGENCIA, CAST(:data AS date))
			      AND CAST(:data AS date) BETWEEN COALESCE(s.SITR_INICIO_VIGENCIA, DATE '-infinity') AND COALESCE(s.SITR_FIM_VIGENCIA, CAST(:data AS date))
			      AND CAST(:data AS date) BETWEEN COALESCE(t.TBTO_INICIO_VIGENCIA, DATE '-infinity') AND COALESCE(t.TBTO_FIM_VIGENCIA, CAST(:data AS date))
			    ORDER BY s.SITR_ID, tst.TRST_ID
			    LIMIT 1
			""", nativeQuery = true)
	@Cacheable(cacheNames = "SituacaoTributariaRepository.consultarSituacaoTributariaPorCodigo")
	SituacaoTributaria consultarSituacaoTributariaPorCodigo(
			@Param("cst") String cst,
			@Param("idTributo") Long idTributo,
			@Param("data") String data);

}
