/*
* Versão de Homologação/Testes
*/
package br.gov.serpro.rtc.testesintegracao.validacoes.negocio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;

import br.gov.serpro.rtc.api.model.input.OperacaoInput;
import br.gov.serpro.rtc.util.CalculoTestSupport;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class Teste_validacao_88 {

    @Autowired
    private CalculoTestSupport calculoTestSupport;

    @Autowired
    private ObjectMapper objectMapper;

    private OperacaoInput operacao;

    @BeforeEach
    void beforeEach(final @Value("classpath:entradas/validacoes/Teste_validacao_88.json") Resource resourceFile)
            throws Exception {
        operacao = objectMapper.readValue(resourceFile.getInputStream(), OperacaoInput.class);
    }


    @Test
    void teste_regimeGeral_CalcularTributos() throws Exception {
        final String jsonContent = objectMapper.writeValueAsString(operacao);
        assertThatThrownBy(() -> calculoTestSupport.regimeGeral(jsonContent));

    }

}