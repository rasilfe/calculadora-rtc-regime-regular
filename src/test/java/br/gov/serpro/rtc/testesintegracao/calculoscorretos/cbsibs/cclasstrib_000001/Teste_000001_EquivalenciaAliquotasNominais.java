/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.testesintegracao.calculoscorretos.cbsibs.cclasstrib_000001;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

/**
 * A partir de 01/01/2027 as alíquotas de referência não existem em lei e o
 * usuário é obrigado a informar as alíquotas nominais quando o CST exige o
 * grupo IBS/CBS. Este teste cobre o novo contrato para o cClassTrib 000001:
 * com as alíquotas informadas o cálculo é aceito (simulação); sem elas (ou com
 * preenchimento parcial), a operação é criticada.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class Teste_000001_EquivalenciaAliquotasNominais {

    @Autowired
    private ObjectMapper objectMapper;


    @Autowired
    private CalculoTestSupport calculoTestSupport;

    @Test
    void deveCalcularComAliquotasNominaisInformadasEm2027(
            final @Value("classpath:entradas/calculoscorretos/Teste_000001_2.json") Resource resourceFile)
            throws Exception {
        OperacaoInput operacao = lerOperacao(resourceFile);

        assertThatCode(() -> calculoTestSupport.regimeGeral(objectMapper.writeValueAsString(operacao))).doesNotThrowAnyException();
    }

    @Test
    void deveCriticarAusenciaDeAliquotasNominaisEm2027(
            final @Value("classpath:entradas/calculoscorretos/Teste_000001_2.json") Resource resourceFile)
            throws Exception {
        OperacaoInput operacao = lerOperacao(resourceFile);
        operacao.getItens().get(0).setAliquotasNominais(null);

        assertThat(calculoTestSupport.titulosErro(objectMapper.writeValueAsString(operacao))).contains("Alíquotas nominais não informadas");
    }

    @Test
    void deveCriticarPreenchimentoParcialDeAliquotasNominaisEm2027(
            final @Value("classpath:entradas/calculoscorretos/Teste_000001_2.json") Resource resourceFile)
            throws Exception {
        OperacaoInput operacao = lerOperacao(resourceFile);
        operacao.getItens().get(0).getAliquotasNominais().setIbsEstadual(null);
        operacao.getItens().get(0).getAliquotasNominais().setIbsMunicipal(null);

        assertThat(calculoTestSupport.titulosErro(objectMapper.writeValueAsString(operacao))).contains("Alíquotas nominais não informadas");
    }

    private OperacaoInput lerOperacao(Resource resourceFile) throws Exception {
        return objectMapper.readValue(resourceFile.getInputStream(), OperacaoInput.class);
    }
}
