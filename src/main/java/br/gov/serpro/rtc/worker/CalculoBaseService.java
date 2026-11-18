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
import br.gov.serpro.rtc.api.model.output.basecalculo.BaseCalculoISMercadoriasModel;
import br.gov.serpro.rtc.api.model.output.basecalculo.BaseCalculoUnificadaNfseOutput;
import br.gov.serpro.rtc.domain.service.basecalculo.BaseCalculoService;
import br.gov.serpro.rtc.domain.service.basecalculo.BaseCalculoUnificadaNfseService;
import br.gov.serpro.rtc.domain.service.basecalculo.BaseCalculoUnificadaService;
import br.gov.serpro.rtc.domain.service.exception.CampoInvalidoException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Calcula a base de um pedido da fila de base de cálculo: base unificada (NF-e e NFC-e),
 * base da NFS-e ou base do Imposto Seletivo sobre mercadorias, conforme o {@code tipo}
 * do envelope.
 *
 * <p>Aplica as mesmas validações dos antigos endpoints ({@code @Valid} do input e
 * {@link CampoInvalidoException} do serviço). Dados inválidos viram um resultado
 * {@code ERRO} com os erros no mesmo formato dos erros de nota; nunca são lançados.
 * Exceções inesperadas propagam: o pedido volta para a fila (falha técnica).
 */
@Service
public class CalculoBaseService {

    /** Prefixo do campo {@code @JsonUnwrapped}: no JSON os componentes ficam na raiz do payload. */
    private static final String PREFIXO_COMPONENTES = "componentes.";

    private final BaseCalculoUnificadaService baseCalculoUnificadaService;
    private final BaseCalculoUnificadaNfseService baseCalculoUnificadaNfseService;
    private final BaseCalculoService baseCalculoService;
    private final Validator validator;
    private final String versaoApp;
    private final String baseUrlErros;

    public CalculoBaseService(BaseCalculoUnificadaService baseCalculoUnificadaService,
            BaseCalculoUnificadaNfseService baseCalculoUnificadaNfseService, BaseCalculoService baseCalculoService,
            Validator validator,
            @Value("${info.app.version:unknown}") String versaoApp,
            @Value("${calculadora.erros.base-url:https://piloto-cbs.tributos.gov.br/servico/calculadora-consumo}") String baseUrlErros) {
        this.baseCalculoUnificadaService = baseCalculoUnificadaService;
        this.baseCalculoUnificadaNfseService = baseCalculoUnificadaNfseService;
        this.baseCalculoService = baseCalculoService;
        this.validator = validator;
        this.versaoApp = versaoApp;
        this.baseUrlErros = baseUrlErros;
    }

    /** Despacha o pedido pelo {@code tipo} do envelope. */
    public ResultadoBaseCalculo calcular(PedidoBaseCalculo pedido) {
        return switch (pedido) {
            case BaseCalculoEntrada unificada -> calcularUnificada(unificada);
            case BaseCalculoNfseEntrada nfse -> calcularNfse(nfse);
            case BaseCalculoISMercadoriasEntrada is -> calcularIsMercadorias(is);
        };
    }

    private ResultadoBaseCalculo calcularUnificada(BaseCalculoEntrada pedido) {
        final long inicio = System.nanoTime();
        final var erros = validar(pedido.payload());
        if (!erros.isEmpty()) {
            return erro(pedido, pedido.idCalculo(), pedido.loteId(), inicio, erros, null);
        }
        try {
            final var bases = baseCalculoUnificadaService.calcular(pedido.payload());
            return new ResultadoBaseCalculo(ResultadoBaseCalculo.SCHEMA_VERSION, pedido.correlationId(), pedido.cnpj(),
                    pedido.idCalculo(), pedido.loteId(), TipoBaseCalculo.UNIFICADA, ResultadoBaseCalculo.Status.SUCESSO,
                    versaoApp, agora(), duracaoMs(inicio), null, bases, null, null);
        } catch (CampoInvalidoException e) {
            return erro(pedido, pedido.idCalculo(), pedido.loteId(), inicio,
                    List.of(erroDeNegocio(e, "#/tipoDocumento")), null);
        }
    }

    /**
     * Base da NFS-e pelo algoritmo oficial ({@link BaseCalculoUnificadaNfseService}). Como no
     * endpoint original, erros de negócio (E1534, BASE-NEGATIVA, EXXX-REGAP...) vêm junto das
     * bases apuradas: o resultado traz {@code status} {@code ERRO}, os erros também em
     * {@code erros} e as bases em {@code resultadoNfse}.
     */
    private ResultadoBaseCalculo calcularNfse(BaseCalculoNfseEntrada pedido) {
        final long inicio = System.nanoTime();
        final var erros = validar(pedido.payload());
        if (!erros.isEmpty()) {
            return erro(pedido, pedido.idCalculo(), pedido.loteId(), inicio, erros, null);
        }
        try {
            final BaseCalculoUnificadaNfseOutput bases = baseCalculoUnificadaNfseService.calcular(pedido.payload());
            final List<ErroDetalhe> errosNegocio = bases.getErros().stream()
                    .map(e -> new ErroDetalhe(uri(e.codigo()), "Erro no cálculo da base da NFS-e", e.codigo(), null,
                            e.mensagem()))
                    .toList();
            if (!errosNegocio.isEmpty()) {
                return erro(pedido, pedido.idCalculo(), pedido.loteId(), inicio, errosNegocio, bases);
            }
            return new ResultadoBaseCalculo(ResultadoBaseCalculo.SCHEMA_VERSION, pedido.correlationId(), pedido.cnpj(),
                    pedido.idCalculo(), pedido.loteId(), TipoBaseCalculo.NFSE, ResultadoBaseCalculo.Status.SUCESSO,
                    versaoApp, agora(), duracaoMs(inicio), null, null, bases, null);
        } catch (CampoInvalidoException e) {
            // única recusa estrutural do serviço: situacao locacao9903 sem o grupo locacao
            return erro(pedido, pedido.idCalculo(), pedido.loteId(), inicio, List.of(erroDeNegocio(e, "#/locacao")),
                    null);
        }
    }

    /**
     * Base do Imposto Seletivo sobre mercadorias pelo algoritmo oficial
     * ({@link BaseCalculoService#calcularISMercadorias}). As recusas do serviço (ano anterior a
     * 2027, ICMS ou ISS a partir de 2033, base negativa) viram {@code ERRO} com {@code REG-024}.
     */
    private ResultadoBaseCalculo calcularIsMercadorias(BaseCalculoISMercadoriasEntrada pedido) {
        final long inicio = System.nanoTime();
        final var erros = validar(pedido.payload());
        if (!erros.isEmpty()) {
            return erro(pedido, pedido.idCalculo(), pedido.loteId(), inicio, erros, null);
        }
        try {
            final BaseCalculoISMercadoriasModel base = baseCalculoService.calcularISMercadorias(pedido.payload());
            return new ResultadoBaseCalculo(ResultadoBaseCalculo.SCHEMA_VERSION, pedido.correlationId(), pedido.cnpj(),
                    pedido.idCalculo(), pedido.loteId(), TipoBaseCalculo.IS_MERCADORIAS,
                    ResultadoBaseCalculo.Status.SUCESSO, versaoApp, agora(), duracaoMs(inicio), null, null, null, base);
        } catch (CampoInvalidoException e) {
            final String ponteiro = e.getMessage().startsWith("Para IS, o ano") ? "#/anoFatoGerador" : null;
            return erro(pedido, pedido.idCalculo(), pedido.loteId(), inicio, List.of(erroDeNegocio(e, ponteiro)),
                    null);
        }
    }

    private ErroDetalhe erroDeNegocio(CampoInvalidoException e, String ponteiro) {
        final ProblemType tipo = ProblemType.from(e);
        final String codigo = tipo.getCodigoErro().codigo();
        return new ErroDetalhe(uri(codigo), tipo.getTitulo(), codigo, ponteiro, e.getMessage());
    }

    private <T> List<ErroDetalhe> validar(T payload) {
        return validator.validate(payload).stream()
                .sorted(Comparator.comparing((ConstraintViolation<T> v) -> v.getPropertyPath().toString()))
                .map(this::erroDeValidacao)
                .toList();
    }

    private ErroDetalhe erroDeValidacao(ConstraintViolation<?> v) {
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

    private ResultadoBaseCalculo erro(PedidoBaseCalculo pedido, String idCalculo, String loteId, long inicio,
            List<ErroDetalhe> erros, BaseCalculoUnificadaNfseOutput basesNfse) {
        return new ResultadoBaseCalculo(ResultadoBaseCalculo.SCHEMA_VERSION, pedido.correlationId(), pedido.cnpj(),
                idCalculo, loteId, pedido.tipo(), ResultadoBaseCalculo.Status.ERRO, versaoApp, agora(),
                duracaoMs(inicio), erros, null, basesNfse, null);
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
