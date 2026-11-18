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

import br.gov.serpro.rtc.api.exceptionhandler.dto.ErroDetalhe;
import br.gov.serpro.rtc.api.model.input.OperacaoInput;
import br.gov.serpro.rtc.domain.service.CalculadoraService;
import br.gov.serpro.rtc.domain.service.ObservabilidadeService;
import br.gov.serpro.rtc.domain.service.collector.ErrosCalculoException;
import br.gov.serpro.rtc.domain.service.exception.ErroFaltaImplementacaoException;
import br.gov.serpro.rtc.util.CalculoTestSupport;
import br.gov.serpro.rtc.worker.StatusNota;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class Teste_validacao_22_ErroFaltaImplementacaoException {

    @Autowired
    private CalculoTestSupport calculoTestSupport;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CalculadoraService calculadoraService;

    @Autowired
    private ObservabilidadeService observabilidadeService;
    
    private OperacaoInput operacao; 

    @BeforeEach
    void beforeEach(
            final @Value("classpath:entradas/validacoes/Teste_validacao_22_ErroFaltaImplementacaoException.json") Resource resourceFile)
            throws Exception {
        operacao = objectMapper.readValue(resourceFile.getInputStream(), OperacaoInput.class);
    }

    @Test
    void teste_service_CalcularTributos() {
        assertThatThrownBy(() -> calculadoraService.calcularTributos(operacao))
                .isExactlyInstanceOf(ErroFaltaImplementacaoException.class);
    }

    @Test
    void teste_service_CalcularTributos_NFeAbi() {
        operacao.setTpDoc(77);
        assertThatThrownBy(() -> calculadoraService.calcularTributos(operacao))
                .isExactlyInstanceOf(ErroFaltaImplementacaoException.class);
    }

    @Test
    void teste_service_CalcularTributos_Dere() {
        operacao.setTpDoc(94);
        assertThatThrownBy(() -> calculadoraService.calcularTributos(operacao))
                .isExactlyInstanceOf(ErroFaltaImplementacaoException.class);
    }

    @Test
    void teste_calculoNota_CalcularTributos() throws Exception {
        final String jsonContent = objectMapper.writeValueAsString(operacao);
        assertThat(calculoTestSupport.titulosErro(jsonContent)).contains("Classificação tributária em desenvolvimento");
    }

    @Test
    void teste_calculoNota_CalcularTributos_NFeAbi() throws Exception {
        operacao.setTpDoc(77);
        final String jsonContent = objectMapper.writeValueAsString(operacao);
        assertThat(calculoTestSupport.titulosErro(jsonContent)).contains("Classificação tributária em desenvolvimento");
    }

    @Test
    void teste_observabilidade_CalcularTributos_NFeAbi() {
        operacao.setTpDoc(77);
        assertThatThrownBy(() -> observabilidadeService.processarOperacao(operacao, "http://localhost:8080"))
                .isExactlyInstanceOf(ErrosCalculoException.class);
    }

    @Test
    void teste_calculoNota_CalcularTributos_NFeAbi_codigoErro() throws Exception {
        operacao.setTpDoc(77);
        final var resultado = calculoTestSupport.calcularNota(objectMapper.writeValueAsString(operacao));
        assertThat(resultado.status()).isEqualTo(StatusNota.ERRO);
        assertThat(resultado.errosNota()).extracting(ErroDetalhe::code).first().isEqualTo("CAL-007");
    }

}
