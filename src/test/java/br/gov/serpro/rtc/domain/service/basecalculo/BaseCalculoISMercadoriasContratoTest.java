/*
 * Versão de Homologação/Testes
 */
package br.gov.serpro.rtc.domain.service.basecalculo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import br.gov.serpro.rtc.api.model.input.basecalculo.BaseCalculoISMercadoriasInput;
import br.gov.serpro.rtc.api.model.output.basecalculo.BaseCalculoISMercadoriasModel;
import br.gov.serpro.rtc.domain.service.exception.CampoInvalidoException;
import jakarta.validation.Valid;

/**
 * Testes oficiais do endpoint {@code is-mercadorias} (release 1.6.1 da calculadora),
 * mantidos sobre o mesmo serviço depois que a API HTTP foi trocada pela fila: um
 * controlador só de teste reproduz o contrato JSON e o 400 de entrada inválida.
 */
class BaseCalculoISMercadoriasContratoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ContratoOficial(new BaseCalculoService()))
            .build();

    @RestController
    @RequestMapping("/calculadora/base-calculo")
    static class ContratoOficial {

        private final BaseCalculoService service;

        ContratoOficial(BaseCalculoService service) {
            this.service = service;
        }

        @PostMapping(value = "is-mercadorias", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        BaseCalculoISMercadoriasModel calcular(@RequestBody @Valid BaseCalculoISMercadoriasInput input) {
            return service.calcularISMercadorias(input);
        }

        @ExceptionHandler(CampoInvalidoException.class)
        ResponseEntity<String> campoInvalido(CampoInvalidoException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Test
    void teste_calculoBasicoComSucesso() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        input.setValorBem(new BigDecimal("100.00"));
        input.setAjusteAcrescimos(new BigDecimal("10.00"));
        input.setJuros(new BigDecimal("5.00"));
        input.setIcms(new BigDecimal("15.00"));

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCalculo").value(100.00));
    }

    @Test
    void teste_todosCamposQueIntegram_SemImpostoSeletivo() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        input.setValorBem(new BigDecimal("1000.00"));
        input.setAjusteAcrescimos(new BigDecimal("50.00"));
        input.setJuros(new BigDecimal("20.00"));
        input.setMultas(new BigDecimal("10.00"));
        input.setEncargos(new BigDecimal("30.00"));
        input.setFreteCobrado(new BigDecimal("40.00"));
        input.setOutrosTributos(new BigDecimal("25.00"));
        input.setDemaisImportancias(new BigDecimal("15.00"));
        // Note: IS não tem impostoSeletivo

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCalculo").value(1190.00));
    }

    @Test
    void teste_todosCamposQueNaoIntegram_ComBonificacaoEDevolucao() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        input.setValorBem(new BigDecimal("2000.00"));
        input.setIcms(new BigDecimal("100.00"));
        input.setIss(new BigDecimal("50.00"));
        input.setCosip(new BigDecimal("5.00"));
        input.setIpi(new BigDecimal("150.00"));
        input.setDescontoIncondicional(new BigDecimal("50.00"));
        input.setBonificacao(new BigDecimal("100.00"));
        input.setDevolucaoVendas(new BigDecimal("200.00"));

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCalculo").value(1345.0));
    }

    @Test
    void teste_baseNegativaRetornaErro() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        input.setValorBem(new BigDecimal("100.00"));
        input.setBonificacao(new BigDecimal("50.00"));
        input.setDevolucaoVendas(new BigDecimal("60.00"));

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teste_valoresZeroRetornaZero() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        // Todos os campos já inicializam com BigDecimal.ZERO

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCalculo").value(0.00));
    }

    @Test
    void teste_bonificacaoReduzBase_Art417() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        input.setValorBem(new BigDecimal("1000.00"));
        input.setBonificacao(new BigDecimal("150.00"));

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCalculo").value(850.00));
    }

    @Test
    void teste_devolucaoVendasReduzBase_Art418() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        input.setValorBem(new BigDecimal("1000.00"));
        input.setDevolucaoVendas(new BigDecimal("200.00"));

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCalculo").value(800.00));
    }

    @Test
    void teste_bonificacaoEDevolucaoJuntos() throws Exception {
        BaseCalculoISMercadoriasInput input = new BaseCalculoISMercadoriasInput();
        input.setAnoFatoGerador(2027);
        input.setValorBem(new BigDecimal("1000.00"));
        input.setBonificacao(new BigDecimal("100.00"));
        input.setDevolucaoVendas(new BigDecimal("50.00"));

        mockMvc.perform(post("/calculadora/base-calculo/is-mercadorias")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCalculo").value(850.00));
    }
}
