package br.gov.serpro.rtc.worker.empresa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CnpjTest {

    @ParameterizedTest
    @CsvSource({
            "11222333000181, 11222333000181",
            "11.222.333/0001-81, 11222333000181",
            // exemplo oficial da Receita para o CNPJ alfanumérico
            "12.ABC.345/01DE-35, 12ABC34501DE35",
            "12abc34501de35, 12ABC34501DE35",
            "ABCDEFGHIJKL80, ABCDEFGHIJKL80",
            "00000000000191, 00000000000191" })
    void aceitaNumericoEAlfanumerico(String entrada, String canonico) {
        assertThat(Cnpj.de(entrada)).hasValueSatisfying(c -> assertThat(c.valor()).isEqualTo(canonico));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "11222333000182",      // DV errado
            "12ABC34501DE36",      // DV errado (alfanumérico)
            "12ABC34501DEA5",      // DV não numérico
            "1122233300018",       // curto
            "112223330001810",     // longo
            "11111111111111",      // repetido
            "00000000000000",
            "12ABC34501DE3*",
            "nota.11222333000181" })
    void rejeitaInvalidos(String entrada) {
        assertThat(Cnpj.de(entrada)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void construtorExigeFormaCanonicaValida() {
        assertThatThrownBy(() -> new Cnpj("11.222.333/0001-81")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Cnpj("11222333000181")).hasToString("11222333000181");
    }
}
