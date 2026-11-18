package br.gov.serpro.rtc.worker;

import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.CALCULADO;
import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.CALCULO_SIMULADO;
import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.FALHA_TECNICA;
import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.INCONSISTENCIA_ENTRADA;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.gov.serpro.rtc.api.model.roc.ObservabilidadeItemDomain;
import br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum;

/**
 * Regras de consolidação do status da nota a partir dos estados dos itens.
 */
class StatusNotaTest {

    @Test
    void todosCalculadosOuSimuladosResultaEmSucesso() {
        assertThat(CalculoNotaService.statusDe(itens(CALCULADO, CALCULO_SIMULADO))).isEqualTo(StatusNota.SUCESSO);
    }

    @Test
    void misturaDeCalculadosEComErroResultaEmParcial() {
        assertThat(CalculoNotaService.statusDe(itens(CALCULADO, INCONSISTENCIA_ENTRADA))).isEqualTo(StatusNota.PARCIAL);
    }

    @Test
    void nenhumCalculadoResultaEmErro() {
        assertThat(CalculoNotaService.statusDe(itens(INCONSISTENCIA_ENTRADA, FALHA_TECNICA))).isEqualTo(StatusNota.ERRO);
    }

    @Test
    void semItensResultaEmErro() {
        assertThat(CalculoNotaService.statusDe(List.of())).isEqualTo(StatusNota.ERRO);
        assertThat(CalculoNotaService.statusDe(null)).isEqualTo(StatusNota.ERRO);
    }

    private static List<ObservabilidadeItemDomain> itens(EstadoItemEnum... estados) {
        return Arrays.stream(estados)
                .map(e -> ObservabilidadeItemDomain.builder().estadoItem(e).build())
                .toList();
    }
}
