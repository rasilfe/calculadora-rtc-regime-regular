package br.gov.serpro.rtc.worker.idempotencia;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Sem controle no worker ({@code worker.idempotencia.tipo=nenhuma}): toda mensagem
 * é processada, e a deduplicação fica só no consumidor da Fila de Saída.
 */
@Service
@ConditionalOnProperty(prefix = "worker.idempotencia", name = "tipo", havingValue = "nenhuma")
public class SemIdempotenciaService implements IdempotenciaService {

    @Override
    public Aquisicao adquirir(String cnpj, UUID correlationId, String hashConteudo, boolean reentrega) {
        return Aquisicao.ADQUIRIDA;
    }

    @Override
    public void concluir(String cnpj, UUID correlationId, String hashConteudo) {
        // nada a registrar
    }

    @Override
    public void liberar(String cnpj, UUID correlationId) {
        // nada a liberar
    }
}
