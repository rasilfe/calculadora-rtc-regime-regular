package br.gov.serpro.rtc.worker;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.gov.serpro.rtc.api.exceptionhandler.dto.ErroDetalhe;
import br.gov.serpro.rtc.api.exceptionhandler.enumeration.CodigoValidacao;
import br.gov.serpro.rtc.api.exceptionhandler.enumeration.ProblemType;
import br.gov.serpro.rtc.api.model.input.basecalculo.BaseCalculoUnificadaInput;
import br.gov.serpro.rtc.domain.service.basecalculo.BaseCalculoUnificadaService;
import br.gov.serpro.rtc.domain.service.exception.CampoInvalidoException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Calcula a base unificada de um pedido da fila de base de cálculo.
 *
 * <p>Aplica as mesmas validações do antigo endpoint ({@code @Valid} do input e
 * {@link CampoInvalidoException} do serviço). Dados inválidos viram um resultado
 * {@code ERRO} com os erros no mesmo formato dos erros de nota; nunca são lançados.
 * Exceções inesperadas propagam: o pedido volta para a fila (falha técnica).
 */
@Service
public class CalculoBaseService {

    /** Prefixo do campo {@code @JsonUnwrapped}: no JSON os componentes ficam na raiz do payload. */
    private static final String PREFIXO_COMPONENTES = "componentes.";

    private final BaseCalculoUnificadaService baseCalculoUnificadaService;
    private final Validator validator;
    private final String versaoApp;
    private final String baseUrlErros;

    public CalculoBaseService(BaseCalculoUnificadaService baseCalculoUnificadaService, Validator validator,
            @Value("${info.app.version:unknown}") String versaoApp,
            @Value("${calculadora.erros.base-url:https://piloto-cbs.tributos.gov.br/servico/calculadora-consumo}") String baseUrlErros) {
        this.baseCalculoUnificadaService = baseCalculoUnificadaService;
        this.validator = validator;
        this.versaoApp = versaoApp;
        this.baseUrlErros = baseUrlErros;
    }

    public ResultadoBaseCalculo calcular(BaseCalculoEntrada pedido) {
        final long inicio = System.nanoTime();
        final var erros = validar(pedido.payload());
        if (!erros.isEmpty()) {
            return erro(pedido, inicio, erros);
        }
        try {
            final var bases = baseCalculoUnificadaService.calcular(pedido.payload());
            return new ResultadoBaseCalculo(ResultadoBaseCalculo.SCHEMA_VERSION, pedido.correlationId(), pedido.cnpj(),
                    pedido.idCalculo(), pedido.loteId(), ResultadoBaseCalculo.Status.SUCESSO, versaoApp, agora(),
                    duracaoMs(inicio), null, bases);
        } catch (CampoInvalidoException e) {
            final ProblemType tipo = ProblemType.from(e);
            final String codigo = tipo.getCodigoErro().codigo();
            return erro(pedido, inicio, List.of(new ErroDetalhe(uri(codigo), tipo.getTitulo(), codigo, "#/tipoDocumento",
                    e.getMessage())));
        }
    }

    private List<ErroDetalhe> validar(BaseCalculoUnificadaInput payload) {
        return validator.validate(payload).stream()
                .sorted(Comparator.comparing((ConstraintViolation<BaseCalculoUnificadaInput> v) -> v.getPropertyPath().toString()))
                .map(this::erroDeValidacao)
                .toList();
    }

    private ErroDetalhe erroDeValidacao(ConstraintViolation<BaseCalculoUnificadaInput> v) {
        String campo = v.getPropertyPath().toString();
        if (campo.startsWith(PREFIXO_COMPONENTES)) {
            campo = campo.substring(PREFIXO_COMPONENTES.length());
        }
        final String anotacao = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        final String codigo = CodigoValidacao.codeFrom(anotacao);
        final String titulo = CodigoValidacao.fromCodigo(codigo).map(CodigoValidacao::getTitulo).orElse("Erro de validação");
        final String detalhe = v.getInvalidValue() != null
                ? String.format("Campo '%s' %s, recebido: '%s'", campo, v.getMessage(), v.getInvalidValue())
                : String.format("Campo '%s' %s", campo, v.getMessage());
        return new ErroDetalhe(uri(codigo), titulo, codigo, "#/" + campo.replace('.', '/'), detalhe);
    }

    private ResultadoBaseCalculo erro(BaseCalculoEntrada pedido, long inicio, List<ErroDetalhe> erros) {
        return new ResultadoBaseCalculo(ResultadoBaseCalculo.SCHEMA_VERSION, pedido.correlationId(), pedido.cnpj(),
                pedido.idCalculo(), pedido.loteId(), ResultadoBaseCalculo.Status.ERRO, versaoApp, agora(),
                duracaoMs(inicio), erros, null);
    }

    private String uri(String codigo) {
        return String.format("%s/api/calculadora/observabilidade/erros?codigo=%s", baseUrlErros, codigo);
    }

    private static OffsetDateTime agora() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static long duracaoMs(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000;
    }
}
