/*
* Versão de Homologação/Testes
*/
package br.gov.serpro.rtc.testesintegracao.tpdoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import br.gov.serpro.rtc.domain.service.exception.TipoDfeNaoEncontradoException;
import br.gov.serpro.rtc.util.CalculoTestSupport;
import br.gov.serpro.rtc.worker.StatusNota;

/**
 * Caso de aceitação 5: tpDoc inexistente ou fora de vigência é erro de
 * operação nas duas APIs; na observabilidade interrompe todo o processamento
 * (erro global).
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class Teste_tpdoc_5_TpDocInvalido {

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
    void beforeEach(final @Value("classpath:entradas/tpdoc/tpdoc_sem_anexo_000001.json") Resource resourceFile)
            throws IOException {
        operacao = objectMapper.readValue(resourceFile.getInputStream(), OperacaoInput.class);
    }

    @Test
    @DisplayName("tpDoc inexistente: erro de operação na API sem observabilidade")
    void teste_service_tpDocInexistente() {
        operacao.setTpDoc(99);
        assertThatThrownBy(() -> calculadoraService.calcularTributos(operacao))
                .isExactlyInstanceOf(TipoDfeNaoEncontradoException.class);
    }

    @Test
    @DisplayName("tpDoc fora de vigência na data do fato gerador: erro de operação")
    void teste_service_tpDocForaDeVigencia() {
        // TIPO_DFE vigente a partir de 2025-01-01; fato gerador anterior
        operacao.setDhFatoGerador(OffsetDateTime.parse("2024-06-01T09:50:05-03:00"));
        assertThatThrownBy(() -> calculadoraService.calcularTributos(operacao))
                .isExactlyInstanceOf(TipoDfeNaoEncontradoException.class);
    }

    @Test
    @DisplayName("Controller sem observabilidade: tpDoc inexistente retorna 4xx")
    void teste_calculoNota_tpDocInexistente() throws Exception {
        operacao.setTpDoc(99);
        final String jsonContent = objectMapper.writeValueAsString(operacao);
        assertThat(calculoTestSupport.titulosErro(jsonContent)).contains("Tipo de documento fiscal não encontrado");
    }

    @Test
    @DisplayName("Observabilidade: tpDoc inexistente é erro global que interrompe o processamento")
    void teste_observabilidade_tpDocInexistente() {
        operacao.setTpDoc(99);
        assertThatThrownBy(() -> observabilidadeService.processarOperacao(operacao, "http://localhost:8080"))
                .isExactlyInstanceOf(ErrosCalculoException.class);
    }

    @Test
    @DisplayName("Cálculo de nota: tpDoc inexistente resulta em nota com ERRO e código REG-028")
    void teste_calculoNota_tpDocInexistente_codigoErro() throws Exception {
        operacao.setTpDoc(99);
        final var resultado = calculoTestSupport.calcularNota(objectMapper.writeValueAsString(operacao));
        assertThat(resultado.status()).isEqualTo(StatusNota.ERRO);
        assertThat(resultado.errosNota()).extracting(ErroDetalhe::code).first().isEqualTo("REG-028");
    }

}
