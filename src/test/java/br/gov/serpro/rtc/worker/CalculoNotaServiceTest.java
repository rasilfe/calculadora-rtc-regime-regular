package br.gov.serpro.rtc.worker;

import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.CALCULADO;
import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.CALCULO_SIMULADO;
import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.INCONSISTENCIA_ENTRADA;
import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.NAO_IMPLEMENTADO;
import static br.gov.serpro.rtc.util.CalculoTestSupport.assertJsonPath;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StreamUtils;

import tools.jackson.databind.ObjectMapper;

import br.gov.serpro.rtc.api.model.input.OperacaoInput;
import br.gov.serpro.rtc.api.model.roc.ObservabilidadeItemDomain;

/**
 * Testes de integração do {@link CalculoNotaService}: cálculo de uma nota com
 * isolamento de erros por item e montagem do envelope de saída.
 *
 * <p>Usa as mesmas entradas dos antigos testes do endpoint
 * {@code /calculadora/observabilidade/regime-geral}.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class CalculoNotaServiceTest {

    @Autowired
    private CalculoNotaService calculoNotaService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("classpath:entradas/calculoscorretos/observabilidade/entrada.json")
    private Resource entradaJson;

    @Value("classpath:entradas/calculoscorretos/observabilidade/entrada_com_erro_global.json")
    private Resource entradaErroGlobalJson;

    @Value("classpath:entradas/calculoscorretos/observabilidade/entrada_com_erro_422.json")
    private Resource entradaErro422Json;

    @Test
    void deveCalcularCadaItemIsoladamenteEConsolidarStatusParcial() throws IOException {
        final NotaEntrada nota = nota(entradaJson);

        final ResultadoNota resultado = calculoNotaService.calcular(nota);

        assertThat(resultado.status()).isEqualTo(StatusNota.PARCIAL);
        assertThat(resultado.correlationId()).isEqualTo(nota.correlationId());
        assertThat(resultado.idNota()).isEqualTo(nota.idNota());
        assertThat(resultado.loteId()).isEqualTo(nota.loteId());
        assertThat(resultado.schemaVersion()).isEqualTo(ResultadoNota.SCHEMA_VERSION);
        assertThat(resultado.versaoBase()).isNotBlank();
        assertThat(resultado.errosNota()).isNull();
        assertThat(resultado.resultado().getObjetos())
                .extracting(ObservabilidadeItemDomain::getNObj, ObservabilidadeItemDomain::getEstadoItem)
                .containsExactly(
                        tuple(1, INCONSISTENCIA_ENTRADA),
                        tuple(2, NAO_IMPLEMENTADO),
                        tuple(3, INCONSISTENCIA_ENTRADA),
                        tuple(4, INCONSISTENCIA_ENTRADA),
                        tuple(5, CALCULADO),
                        // Item 6 informa aliquotasNominais (obrigatórias a partir de 2027 para
                        // CST que exige o grupo IBS/CBS): o cálculo passa a ser uma simulação.
                        tuple(6, CALCULO_SIMULADO));
        // O total considera apenas os itens calculados.
        assertThat(resultado.resultado().getTotal()).isNotNull();
    }

    @Test
    void deveSerializarEnvelopeDeSaida() throws IOException {
        final NotaEntrada nota = nota(entradaJson);

        final String json = objectMapper.writeValueAsString(calculoNotaService.calcular(nota));

        assertJsonPath(json, "$.schemaVersion", "1");
        assertJsonPath(json, "$.correlationId", nota.correlationId().toString());
        assertJsonPath(json, "$.idNota", "nota-1");
        assertJsonPath(json, "$.status", "PARCIAL");
        assertJsonPath(json, "$.resultado.objetos[4].estadoItem", "CALCULADO");
        assertThat(json).doesNotContain("\"errosNota\"");
    }

    @Test
    void deveDevolverErroDeNotaQuandoValidacaoGlobalFalha() throws IOException {
        assertErroDeNota(calculoNotaService.calcular(nota(entradaErroGlobalJson)));
    }

    @Test
    void deveDevolverErroDeNotaQuandoRegraGlobalFalha() throws IOException {
        assertErroDeNota(calculoNotaService.calcular(nota(entradaErro422Json)));
    }

    private static void assertErroDeNota(ResultadoNota resultado) {
        assertThat(resultado.status()).isEqualTo(StatusNota.ERRO);
        assertThat(resultado.errosNota()).isNotEmpty();
        assertThat(resultado.resultado()).isNull();
    }

    private NotaEntrada nota(Resource resource) throws IOException {
        final var operacao = objectMapper.readValue(StreamUtils.copyToString(resource.getInputStream(), UTF_8),
                OperacaoInput.class);
        return new NotaEntrada("1", UUID.randomUUID(), "11222333000181", "nota-1", "lote-1", OffsetDateTime.now(), operacao);
    }
}
