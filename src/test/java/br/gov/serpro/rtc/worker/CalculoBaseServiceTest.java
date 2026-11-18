package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.gov.serpro.rtc.api.exceptionhandler.dto.ErroDetalhe;
import br.gov.serpro.rtc.api.model.input.basecalculo.BaseCalculoUnificadaInput;
import br.gov.serpro.rtc.domain.service.basecalculo.BaseCalculoUnificadaService;
import jakarta.validation.Validation;

/**
 * Cálculo da base unificada pela fila: mesmos números e mesmas validações do antigo
 * endpoint, com dados inválidos devolvidos como resultado ERRO.
 */
class CalculoBaseServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static CalculoBaseService service;

    @BeforeAll
    static void criar() {
        service = new CalculoBaseService(new BaseCalculoUnificadaService(),
                Validation.buildDefaultValidatorFactory().getValidator(), "9.9.9", "https://erros.exemplo");
    }

    @Test
    void calculaAsTresBasesComOsMesmosValoresDoEndpoint() {
        final var pedido = pedido(payload(2026, 55, "bem", Map.ofEntries(
                Map.entry("vProd", "1000.00"), Map.entry("vFrete", "50.00"), Map.entry("vSeg", "20.00"),
                Map.entry("vOutro", "10.00"), Map.entry("vII", "150.00"), Map.entry("vDesc", "50.00"),
                Map.entry("vPIS", "9.25"), Map.entry("vCOFINS", "46.25"), Map.entry("vICMS", "180.00"),
                Map.entry("vICMSUFDest", "20.00"), Map.entry("vFCP", "10.00"), Map.entry("vFCPUFDest", "5.00"),
                Map.entry("vICMSMono", "100.00"))));

        final ResultadoBaseCalculo r = service.calcular(pedido);

        assertThat(r.status()).isEqualTo(ResultadoBaseCalculo.Status.SUCESSO);
        assertThat(r.erros()).isNull();
        assertThat(r.resultado().getBaseRG()).isEqualByComparingTo("809.50");
        assertThat(r.resultado().getBaseIS()).isEqualByComparingTo("809.50");
        assertThat(r.resultado().getBaseSN()).isEqualByComparingTo("1030.00");
        assertThat(r.correlationId()).isEqualTo(pedido.correlationId());
        assertThat(r.cnpj()).isEqualTo("11222333000181");
        assertThat(r.idCalculo()).isEqualTo("item-1");
        assertThat(r.versaoApp()).isEqualTo("9.9.9");
        assertThat(r.processadoEm()).isNotNull();
    }

    @Test
    void camposObrigatoriosAusentesViramErroDeValidacao() {
        final ResultadoBaseCalculo r = service.calcular(pedido(payload(null, null, null, Map.of("vProd", "10"))));

        assertThat(r.status()).isEqualTo(ResultadoBaseCalculo.Status.ERRO);
        assertThat(r.resultado()).isNull();
        assertThat(r.erros()).extracting(ErroDetalhe::pointer)
                .containsExactly("#/anoFatoGerador", "#/natureza", "#/tipoDocumento");
        assertThat(r.erros()).allSatisfy(e -> {
            assertThat(e.code()).isNotBlank();
            assertThat(e.type()).startsWith("https://erros.exemplo/").endsWith("codigo=" + e.code());
        });
    }

    @Test
    void valorNegativoApontaParaOCampoNaRaizDoPayload() {
        final ResultadoBaseCalculo r = service.calcular(pedido(payload(2026, 55, "bem", Map.of("vFrete", "-1"))));

        assertThat(r.status()).isEqualTo(ResultadoBaseCalculo.Status.ERRO);
        // os componentes são @JsonUnwrapped: no JSON ficam na raiz, sem "componentes/"
        assertThat(r.erros()).singleElement().satisfies(e -> {
            assertThat(e.pointer()).isEqualTo("#/vFrete");
            assertThat(e.detail()).contains("vFrete", "-1");
        });
    }

    @Test
    void tipoDeDocumentoForaDe55e65ViraErroDeNegocio() {
        final ResultadoBaseCalculo r = service.calcular(pedido(payload(2026, 57, "bem", Map.of("vProd", "10"))));

        assertThat(r.status()).isEqualTo(ResultadoBaseCalculo.Status.ERRO);
        assertThat(r.erros()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("REG-024");
            assertThat(e.pointer()).isEqualTo("#/tipoDocumento");
            assertThat(e.detail()).contains("55", "65");
        });
    }

    private static BaseCalculoEntrada pedido(BaseCalculoUnificadaInput payload) {
        return new BaseCalculoEntrada("1", UUID.randomUUID(), "11222333000181", "item-1", "lote", null, payload);
    }

    private static BaseCalculoUnificadaInput payload(Integer ano, Integer tipoDocumento, String natureza,
            Map<String, Object> valores) {
        final Map<String, Object> json = new HashMap<>(valores);
        json.put("anoFatoGerador", ano);
        json.put("tipoDocumento", tipoDocumento);
        json.put("natureza", natureza);
        return MAPPER.convertValue(json, BaseCalculoUnificadaInput.class);
    }
}
