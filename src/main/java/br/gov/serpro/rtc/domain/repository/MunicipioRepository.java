/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.gov.serpro.rtc.domain.model.entity.Municipio;

import java.util.List;

/**
 * Repositório Spring Data JPA para acesso a {@link Municipio}, com validação de
 * municípios por código e UF e listagem por unidade federativa.
 */
@Repository
public interface MunicipioRepository extends JpaRepository<Municipio, Long> {

    @Query("FROM Municipio m WHERE LOWER(m.uf.sigla) = LOWER(:uf)")
    List<Municipio> consultarTodosPorSiglaUf(
            @Param("uf") String uf);

    @Query("""
            SELECT 
            CASE 
                WHEN COUNT(m) = 0 THEN 1
                WHEN COUNT(m) > 0 AND MAX(LOWER(m.uf.sigla)) != LOWER(:siglaUf) THEN 2
                ELSE 0
            END
            FROM Municipio m
            WHERE m.codigo = :codigoMunicipio
            """)
    // TODO - Cacheable? Ajustar a consulta para nao fazer join desnecessario com UF? usar exists? consultar se inicio do municipio corresponde ao código da uf ao inves da sigla?
    int validarMunicipio(
            @Param("codigoMunicipio") Long codigoMunicipio,
            @Param("siglaUf") String siglaUf);

}
