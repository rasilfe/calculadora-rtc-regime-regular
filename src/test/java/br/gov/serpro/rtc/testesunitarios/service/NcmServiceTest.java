/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.testesunitarios.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.gov.serpro.rtc.domain.repository.NcmRepository;
import br.gov.serpro.rtc.domain.service.NcmService;

@ExtendWith(MockitoExtension.class)
class NcmServiceTest {

    private static final LocalDate DATA = LocalDate.parse("2026-01-01");

    @Mock
    private NcmRepository repository;

    @InjectMocks
    private NcmService service;
    
    @Test
    void deveRetornarTrueQuandoNcmExistirNaData() {
        String ncm = "23099070";
        when(repository.existeNcm(eq(ncm), eq(DATA))).thenReturn(true);

        boolean resultado = service.existeNcm(ncm, DATA);

        verify(repository).existeNcm(eq(ncm), eq(DATA));
        assertEquals(true, resultado);
    }

    @Test
    void deveRetornarFalseQuandoNcmNaoExistirNaData() {
        String ncm = "99999999";
        when(repository.existeNcm(eq(ncm), eq(DATA))).thenReturn(false);

        boolean resultado = service.existeNcm(ncm, DATA);

        verify(repository).existeNcm(eq(ncm), eq(DATA));
        assertEquals(false, resultado);
    }
}
