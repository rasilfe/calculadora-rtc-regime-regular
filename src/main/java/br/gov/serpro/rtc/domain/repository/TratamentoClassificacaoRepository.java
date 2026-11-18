/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.domain.repository;

import java.time.LocalDate;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import br.gov.serpro.rtc.domain.model.dto.TratamentoClassificacaoDTO;
import br.gov.serpro.rtc.domain.model.entity.TratamentoClassificacao;

/**
 * Repositório Spring Data JPA central para consultas de vínculo entre
 * classificação tributária e tratamento tributário. Dá suporte à recuperação de
 * regras de tributação, situações tributárias, validações para DF-e e
 * relatórios usados pela calculadora.
 */
public interface TratamentoClassificacaoRepository extends JpaRepository<TratamentoClassificacao, Long> {

    /*
     * Entradas recomendadas na cache: 400
     * Memória estimada: ~49 KB
     */
    @Query("""
            SELECT new br.gov.serpro.rtc.domain.model.dto.TratamentoClassificacaoDTO(
                t.tratamentoTributario.id,
                t.classificacaoTributaria.id,
                t.tratamentoTributario.inIncompativelComSuspensao,
                t.tratamentoTributario.inExigeGrupoDesoneracao)
            FROM TratamentoClassificacao t
            WHERE t.classificacaoTributaria.id = :idClassificacaoTributaria
                AND (t.inicioVigencia <= :data AND (t.fimVigencia IS NULL OR t.fimVigencia >= :data))
            """)
    @Cacheable(cacheNames = "TratamentoClassificacaoRepository.buscarTratamentoClassificacao")
    TratamentoClassificacaoDTO buscarTratamentoClassificacao(Long idClassificacaoTributaria, LocalDate data);

}
