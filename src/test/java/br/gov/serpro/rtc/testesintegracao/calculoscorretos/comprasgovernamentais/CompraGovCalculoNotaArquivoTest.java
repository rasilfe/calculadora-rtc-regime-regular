package br.gov.serpro.rtc.testesintegracao.calculoscorretos.comprasgovernamentais;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StreamUtils;

import br.gov.serpro.rtc.util.CalculoTestSupport;

/**
 * Cenários de compras governamentais pelo caminho do worker
 * ({@code CalculoNotaService}). O bloco {@code resultado} do envelope é
 * comparado com as mesmas saídas de referência do antigo endpoint de
 * observabilidade.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class CompraGovCalculoNotaArquivoTest {

    private static final String TESTS_PATH = "entradas/calculoscorretos/compras-governamentais/testes/";

    @Autowired
    private CalculoTestSupport calculoTestSupport;

    @ParameterizedTest(name = "Input = {0}, Output = {1}")
    @MethodSource("cenariosSucesso")
    void deveCalcularTributosCompraGov(String input, String output) throws Exception {
        final var resposta = calculoTestSupport.calcularNotaResultadoJson(getContentFrom(input));
        JSONAssert.assertEquals(getContentFrom(output), resposta, JSONCompareMode.LENIENT);
    }

    /**
     * Códigos de enumeração inválidos impedem a leitura do JSON. No worker,
     * isso corresponde a uma mensagem ilegível, encaminhada para a DLQ.
     */
    @ParameterizedTest(name = "Input = {0}")
    @MethodSource("cenariosFalha")
    void deveFalharComValoresInvalidos(String input) throws Exception {
        final var jsonRequest = getContentFrom(input);
        assertThatThrownBy(() -> calculoTestSupport.calcularNota(jsonRequest));
    }

    private static Stream<Arguments> cenariosSucesso() {
        return Stream.of(
                Arguments.of("cenario_01_uniao_input.json", "cenario_01_uniao_output_observabilidade.json"),
                Arguments.of("cenario_02_estado_input.json", "cenario_02_estado_output_observabilidade.json"),
                Arguments.of("cenario_03_distrito_federal_input.json", "cenario_03_distrito_federal_output_observabilidade.json"),
                Arguments.of("cenario_04_municipio_input.json", "cenario_04_municipio_output_observabilidade.json"),
                Arguments.of("cenario_05_consorcio_input.json", "cenario_05_consorcio_output_observabilidade.json"),
                Arguments.of("cenario_06_comite_gestor_input.json", "cenario_06_comite_gestor_output_observabilidade.json"));
    }

    private static Stream<Arguments> cenariosFalha() {
        return Stream.of(
                Arguments.of("cenario_00_tipo_ente_invalido_input.json"),
                Arguments.of("cenario_00_tipo_operacao_invalido_input.json"));
    }

    private static String getContentFrom(String filename) throws Exception {
        var resource = new ClassPathResource(TESTS_PATH + filename);
        return StreamUtils.copyToString(resource.getInputStream(), UTF_8);
    }
}
