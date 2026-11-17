package br.gov.serpro.rtc.worker;

import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.CALCULADO;
import static br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum.CALCULO_SIMULADO;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.gov.serpro.rtc.api.model.roc.ObservabilidadeItemDomain;
import br.gov.serpro.rtc.api.model.roc.ObservabilidadeROCDomain;
import br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum;
import br.gov.serpro.rtc.domain.model.enumeration.TipoWarningDadosSimulados;
import br.gov.serpro.rtc.domain.service.AvisoDadosSimuladosService;
import br.gov.serpro.rtc.domain.service.ObservabilidadeService;
import br.gov.serpro.rtc.domain.service.VersaoBaseDadosService;
import br.gov.serpro.rtc.domain.service.collector.ErrosCalculoException;
import lombok.extern.slf4j.Slf4j;

/**
 * Ponto de entrada do cálculo de uma nota fiscal, sem dependência de HTTP.
 *
 * <p>Delega todo o cálculo ao {@link ObservabilidadeService}, que já processa
 * cada item de forma isolada e registra os erros por item; nenhuma regra de
 * negócio é implementada aqui. Este serviço apenas monta o envelope de
 * resultado ({@link ResultadoNota}) com versões, status consolidado e tempos.
 */
@Slf4j
@Service
public class CalculoNotaService {

    private final ObservabilidadeService observabilidadeService;
    private final AvisoDadosSimuladosService avisoDadosSimuladosService;
    private final VersaoBaseDadosService versaoBaseDadosService;
    private final String versaoApp;
    private final String baseUrlErros;

    public CalculoNotaService(ObservabilidadeService observabilidadeService,
            AvisoDadosSimuladosService avisoDadosSimuladosService,
            VersaoBaseDadosService versaoBaseDadosService,
            @Value("${info.app.version:unknown}") String versaoApp,
            @Value("${calculadora.erros.base-url:https://piloto-cbs.tributos.gov.br/servico/calculadora-consumo}") String baseUrlErros) {
        this.observabilidadeService = observabilidadeService;
        this.avisoDadosSimuladosService = avisoDadosSimuladosService;
        this.versaoBaseDadosService = versaoBaseDadosService;
        this.versaoApp = versaoApp;
        this.baseUrlErros = baseUrlErros;
    }

    /**
     * Calcula todos os itens de uma nota. Erros de negócio ou de validação
     * (da nota ou de itens) são devolvidos no resultado; nunca são lançados.
     */
    public ResultadoNota calcular(NotaEntrada nota) {
        final long inicio = System.nanoTime();
        final var operacao = nota.payload();
        final String versaoBase = versaoBaseDadosService.getUltimaVersao().getNumeroVersao();

        try {
            final ObservabilidadeROCDomain resultado = observabilidadeService.processarOperacao(operacao, baseUrlErros);
            final TipoWarningDadosSimulados aviso = avisoDadosSimuladosService.getWarningDadosSimulados(operacao);
            return new ResultadoNota(ResultadoNota.SCHEMA_VERSION, nota.correlationId(), nota.idNota(), nota.loteId(),
                    statusDe(resultado.getObjetos()), versaoApp, versaoBase,
                    aviso != null ? aviso.getValor() : null,
                    agora(), duracaoMs(inicio), null, resultado);
        } catch (ErrosCalculoException e) {
            log.debug("Nota {} ({}) rejeitada antes do cálculo dos itens: {}", nota.idNota(), nota.correlationId(), e.getErros());
            return new ResultadoNota(ResultadoNota.SCHEMA_VERSION, nota.correlationId(), nota.idNota(), nota.loteId(),
                    StatusNota.ERRO, versaoApp, versaoBase, null,
                    agora(), duracaoMs(inicio), e.getErros(), null);
        }
    }

    static StatusNota statusDe(List<ObservabilidadeItemDomain> itens) {
        if (itens == null || itens.isEmpty()) {
            return StatusNota.ERRO;
        }
        final long calculados = itens.stream().map(ObservabilidadeItemDomain::getEstadoItem)
                .filter(CalculoNotaService::foiCalculado)
                .count();
        if (calculados == itens.size()) {
            return StatusNota.SUCESSO;
        }
        return calculados == 0 ? StatusNota.ERRO : StatusNota.PARCIAL;
    }

    private static boolean foiCalculado(EstadoItemEnum estado) {
        return estado == CALCULADO || estado == CALCULO_SIMULADO;
    }

    /** Mesma precisão (segundos) exigida pelo desserializador de datas da aplicação. */
    private static OffsetDateTime agora() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static long duracaoMs(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000;
    }
}
