/*
* Versão de Homologação/Testes
*/
package br.gov.serpro.rtc.testesintegracao.validacoes.negocio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.gov.serpro.rtc.api.model.input.OperacaoInput;
import br.gov.serpro.rtc.domain.service.CalculadoraService;
import br.gov.serpro.rtc.domain.service.exception.BaseCalculoNaoInformadaException;
import br.gov.serpro.rtc.util.CalculoTestSupport;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class Teste_validacao_23_BaseCalculoNaoInformadaException {

    @Autowired
    private CalculoTestSupport calculoTestSupport;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CalculadoraService calculadoraService;

    private OperacaoInput operacao;

    @BeforeEach
    void beforeEach(
            final @Value("classpath:entradas/validacoes/Teste_validacao_23_BaseCalculoNaoInformadaException.json") Resource resourceFile)
            throws Exception {
        operacao = objectMapper.readValue(resourceFile.getInputStream(), OperacaoInput.class);
    }

    @Test
    void teste_service_CalcularTributos() {
        assertThatThrownBy(() -> calculadoraService.calcularTributos(operacao))
                .isExactlyInstanceOf(BaseCalculoNaoInformadaException.class);
    }

    @Test
    void teste_calculoNota_CalcularTributos() throws Exception {
        final String jsonContent = objectMapper.writeValueAsString(operacao);
        assertThat(calculoTestSupport.titulosErro(jsonContent)).contains("Base de cálculo não informada");
    }

    @Test
    void teste_calculoNota_GrupoImpostoSeletivoSemBaseCalculo(
            final @Value("classpath:entradas/calculoscorretos/Teste_000001_2.json") Resource resourceFile)
            throws Exception {
        final OperacaoInput operacaoComIs = objectMapper.readValue(resourceFile.getInputStream(), OperacaoInput.class);
        operacaoComIs.getItens().get(0).getImpostoSeletivo().setBaseCalculo(null);
        final String jsonContent = objectMapper.writeValueAsString(operacaoComIs);
        // A antiga API respondia com o título genérico "Campos inválidos." do handler HTTP
        // de Bean Validation. No cálculo de nota, a validação é feita por item e usa o
        // título do catálogo de códigos de validação.
        assertThat(calculoTestSupport.titulosErro(jsonContent)).contains("Campo obrigatório não informado");
    }

}
