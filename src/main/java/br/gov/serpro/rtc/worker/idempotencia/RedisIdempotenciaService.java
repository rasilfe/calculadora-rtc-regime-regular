package br.gov.serpro.rtc.worker.idempotencia;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import br.gov.serpro.rtc.worker.WorkerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Idempotência com Redis: {@code SET NX} com lease enquanto a nota é processada
 * e marca de concluída com retenção maior.
 *
 * <p>Valor da chave: {@code P|<hash>} (em processamento) ou {@code C|<hash>}
 * (concluída). Se o Redis estiver indisponível, a nota é processada mesmo assim:
 * o pior caso é calcular de novo uma nota idêntica.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "worker.idempotencia", name = "tipo", havingValue = "redis", matchIfMissing = true)
public class RedisIdempotenciaService implements IdempotenciaService {

    private static final String EM_PROCESSAMENTO = "P|";
    private static final String CONCLUIDA = "C|";

    private final StringRedisTemplate redis;
    private final WorkerProperties.Idempotencia config;
    private final Counter indisponivel;

    public RedisIdempotenciaService(StringRedisTemplate redis, WorkerProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.config = properties.getIdempotencia();
        this.indisponivel = Counter.builder("rtc.worker.idempotencia.indisponivel")
                .description("Operações de idempotência ignoradas por indisponibilidade do Redis")
                .register(registry);
    }

    @Override
    public Aquisicao adquirir(String cnpj, UUID correlationId, String hashConteudo, boolean reentrega) {
        final String chave = chave(cnpj, correlationId);
        try {
            for (int tentativa = 0; tentativa < 2; tentativa++) {
                if (Boolean.TRUE.equals(redis.opsForValue()
                        .setIfAbsent(chave, EM_PROCESSAMENTO + hashConteudo, config.getLease()))) {
                    return Aquisicao.ADQUIRIDA;
                }
                final String atual = redis.opsForValue().get(chave);
                if (atual == null) {
                    continue; // o lease expirou entre as duas chamadas
                }
                if (!atual.endsWith(hashConteudo)) {
                    return Aquisicao.CONFLITO;
                }
                if (atual.startsWith(CONCLUIDA)) {
                    return Aquisicao.JA_CONCLUIDA;
                }
                if (reentrega) {
                    // O worker anterior caiu com o lease ativo: assume a nota.
                    redis.opsForValue().set(chave, EM_PROCESSAMENTO + hashConteudo, config.getLease());
                    return Aquisicao.ADQUIRIDA;
                }
                return Aquisicao.EM_ANDAMENTO;
            }
            return Aquisicao.EM_ANDAMENTO;
        } catch (DataAccessException e) {
            indisponivel.increment();
            log.warn("Redis indisponível; processando a nota {} sem controle de idempotência: {}", correlationId, e.getMessage());
            return Aquisicao.ADQUIRIDA;
        }
    }

    @Override
    public void concluir(String cnpj, UUID correlationId, String hashConteudo) {
        try {
            redis.opsForValue().set(chave(cnpj, correlationId), CONCLUIDA + hashConteudo, config.getRetencao());
        } catch (DataAccessException e) {
            indisponivel.increment();
            log.warn("Redis indisponível; não foi possível marcar a nota {} como concluída: {}", correlationId, e.getMessage());
        }
    }

    @Override
    public void liberar(String cnpj, UUID correlationId) {
        try {
            redis.delete(chave(cnpj, correlationId));
        } catch (DataAccessException e) {
            indisponivel.increment();
            log.warn("Redis indisponível; o lease da nota {} expira sozinho: {}", correlationId, e.getMessage());
        }
    }

    private String chave(String cnpj, UUID correlationId) {
        return config.getPrefixo() + cnpj + ":" + correlationId;
    }
}
