package br.gov.serpro.rtc.util;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.test.util.JsonPathExpectationsHelper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;

import br.gov.serpro.rtc.api.exceptionhandler.dto.ErroDetalhe;
import br.gov.serpro.rtc.api.model.input.OperacaoInput;
import br.gov.serpro.rtc.api.model.roc.ObservabilidadeItemDomain;
import br.gov.serpro.rtc.domain.service.CalculadoraService;
import br.gov.serpro.rtc.worker.CalculoNotaService;
import br.gov.serpro.rtc.worker.NotaEntrada;
import br.gov.serpro.rtc.worker.ResultadoNota;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

/**
 * Apoio aos testes de integração que antes chamavam os endpoints HTTP.
 *
 * <p>Oferece duas portas de entrada sem HTTP:
 * <ul>
 * <li>{@link #regimeGeral(String)}: equivale ao antigo
 * {@code POST /calculadora/regime-geral} (desserializa, valida como o
 * {@code @Valid} do controller e calcula com o {@link CalculadoraService});</li>
 * <li>{@link #calcularNota(String)}: calcula pelo {@link CalculoNotaService},
 * o mesmo caminho que o worker de filas usará.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CalculoTestSupport {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final CalculadoraService calculadoraService;
    private final CalculoNotaService calculoNotaService;

    /** Calcula pelo serviço de regime geral e devolve o ROC serializado em JSON. */
    public String regimeGeral(String jsonOperacao) throws JsonProcessingException {
        final OperacaoInput operacao = objectMapper.readValue(jsonOperacao, OperacaoInput.class);
        final var violacoes = validator.validate(operacao);
        if (!violacoes.isEmpty()) {
            throw new ConstraintViolationException(violacoes);
        }
        return objectMapper.writeValueAsString(calculadoraService.calcularTributos(operacao));
    }

    /** Calcula a nota pelo caminho do worker. */
    public ResultadoNota calcularNota(String jsonOperacao) throws JsonProcessingException {
        final OperacaoInput operacao = objectMapper.readValue(jsonOperacao, OperacaoInput.class);
        return calculoNotaService.calcular(
                new NotaEntrada("1", UUID.randomUUID(), "nota-teste", "lote-teste", OffsetDateTime.now(), operacao));
    }

    /** Calcula a nota pelo caminho do worker e devolve o envelope serializado em JSON. */
    public String calcularNotaJson(String jsonOperacao) throws JsonProcessingException {
        return objectMapper.writeValueAsString(calcularNota(jsonOperacao));
    }

    /**
     * Calcula a nota pelo caminho do worker e devolve só o bloco {@code resultado}
     * serializado, que tem o mesmo formato da resposta do antigo endpoint de
     * observabilidade.
     */
    public String calcularNotaResultadoJson(String jsonOperacao) throws JsonProcessingException {
        return objectMapper.writeValueAsString(calcularNota(jsonOperacao).resultado());
    }

    /** Títulos de todos os erros do resultado: os da nota e os de cada item. */
    public List<String> titulosErro(String jsonOperacao) throws JsonProcessingException {
        return erros(calcularNota(jsonOperacao)).stream().map(ErroDetalhe::title).toList();
    }

    /** Códigos de todos os erros do resultado: os da nota e os de cada item. */
    public List<String> codigosErro(String jsonOperacao) throws JsonProcessingException {
        return erros(calcularNota(jsonOperacao)).stream().map(ErroDetalhe::code).toList();
    }

    public static List<ErroDetalhe> erros(ResultadoNota resultado) {
        final List<ErroDetalhe> erros = new ArrayList<>();
        if (resultado.errosNota() != null) {
            erros.addAll(resultado.errosNota());
        }
        if (resultado.resultado() != null && resultado.resultado().getObjetos() != null) {
            for (ObservabilidadeItemDomain item : resultado.resultado().getObjetos()) {
                if (item.getErros() != null) {
                    erros.addAll(item.getErros());
                }
            }
        }
        return erros;
    }

    /** Mesma verificação feita por {@code jsonPath(path).value(expected)} no MockMvc. */
    public static void assertJsonPath(String json, String path, Object expected) {
        new JsonPathExpectationsHelper(path, (Configuration) null).assertValue(json, expected);
    }

    /** Mesma verificação feita por {@code jsonPath(path).exists()} no MockMvc. */
    public static void assertJsonPathExists(String json, String path) {
        new JsonPathExpectationsHelper(path, (Configuration) null).exists(json);
    }
}
