/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.domain.repository;

import java.time.LocalDate;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.gov.serpro.rtc.domain.model.entity.Ncm;

/**
 * Repositório Spring Data JPA para acesso a {@link Ncm}, com consultas de
 * descrição e existência de NCM vigentes.
 */
public interface NcmRepository extends JpaRepository<Ncm, String> {

    /*
     * Entradas recomendadas na cache: 21.000
     * Memória estimada: ~2,5 MB
     */
    @Query("""
            SELECT EXISTS (
                SELECT 1
                FROM Ncm n
                WHERE n.codigo = :codigo
                AND :data BETWEEN n.inicioVigencia AND COALESCE(n.fimVigencia, :data)
            )
            """)
    @Cacheable(cacheNames = "NcmRepository.existeNcm")
    boolean existeNcm(
            @Param("codigo") String codigo,
            @Param("data") LocalDate data);

}