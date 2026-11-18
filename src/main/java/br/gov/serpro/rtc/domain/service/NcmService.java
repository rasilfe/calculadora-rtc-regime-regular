/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.domain.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import br.gov.serpro.rtc.domain.repository.NcmRepository;
import lombok.RequiredArgsConstructor;

/**
 * Serviço de domínio para consulta de NCMs (Nomenclatura Comum do Mercosul).
 * <p>
 * Responsabilidades:
 * <ul>
 *   <li>Verificar existência e vigência de um NCM em uma data específica.</li>
 *   <li>Listar todos os NCMs vigentes e completos (8 dígitos) em uma data.</li>
 * </ul>
 * <p>
 * Delega a lógica de consulta para {@link NcmRepository}, que aplica filtros de vigência
 * e tamanho de código via JPQL.
 * 
 */
@RequiredArgsConstructor
@Service
public class NcmService {

    private final NcmRepository repository;

    /**
     * Verifica se um NCM específico existe e está vigente na data informada.
     * <p>
     * Critério: data deve estar entre {@code inicioVigencia} e {@code fimVigencia}
     * (inclusivo; quando {@code fimVigencia} é nulo, considera vigente indefinidamente).
     * 
     * @param ncm código do NCM a verificar
     * @param data data de referência para validar vigência
     * @return {@code true} se NCM existe e está vigente; {@code false} caso contrário
     */
    public boolean existeNcm(String ncm, LocalDate data) {
        return repository.existeNcm(ncm, data);
    }

}
