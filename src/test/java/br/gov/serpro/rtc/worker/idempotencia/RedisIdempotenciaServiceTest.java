package br.gov.serpro.rtc.worker.idempotencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import br.gov.serpro.rtc.worker.WorkerProperties;
import br.gov.serpro.rtc.worker.idempotencia.IdempotenciaService.Aquisicao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RedisIdempotenciaServiceTest {

    private static final String HASH = "abc123";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valores;
    private SimpleMeterRegistry registry;
    private RedisIdempotenciaService servico;
    private final UUID id = UUID.randomUUID();
    private String chave;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        registry = new SimpleMeterRegistry();
        final var props = new WorkerProperties();
        servico = new RedisIdempotenciaService(redis, props, registry);
        chave = props.getIdempotencia().getPrefixo() + id;
    }

    @Test
    void adquireQuandoChaveNaoExiste() {
        when(valores.setIfAbsent(eq(chave), eq("P|" + HASH), any(Duration.class))).thenReturn(true);

        assertThat(servico.adquirir(id, HASH, false)).isEqualTo(Aquisicao.ADQUIRIDA);
    }

    @Test
    void jaConcluidaComMesmoConteudo() {
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valores.get(chave)).thenReturn("C|" + HASH);

        assertThat(servico.adquirir(id, HASH, true)).isEqualTo(Aquisicao.JA_CONCLUIDA);
    }

    @Test
    void conflitoQuandoConteudoDifere() {
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valores.get(chave)).thenReturn("C|outro-hash");

        assertThat(servico.adquirir(id, HASH, false)).isEqualTo(Aquisicao.CONFLITO);
    }

    @Test
    void envioDuplicadoComLeaseAtivoEstaEmAndamento() {
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valores.get(chave)).thenReturn("P|" + HASH);

        assertThat(servico.adquirir(id, HASH, false)).isEqualTo(Aquisicao.EM_ANDAMENTO);
        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void reentregaComLeaseAtivoAssumeANota() {
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valores.get(chave)).thenReturn("P|" + HASH);

        assertThat(servico.adquirir(id, HASH, true)).isEqualTo(Aquisicao.ADQUIRIDA);
        verify(valores).set(eq(chave), eq("P|" + HASH), any(Duration.class));
    }

    @Test
    void leaseExpiradoEntreAsChamadasTentaDeNovo() {
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false, true);
        when(valores.get(chave)).thenReturn(null);

        assertThat(servico.adquirir(id, HASH, false)).isEqualTo(Aquisicao.ADQUIRIDA);
    }

    @Test
    void redisIndisponivelProcessaMesmoAssim() {
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("fora do ar"));

        assertThat(servico.adquirir(id, HASH, false)).isEqualTo(Aquisicao.ADQUIRIDA);
        assertThat(registry.counter("rtc.worker.idempotencia.indisponivel").count()).isEqualTo(1);
    }

    @Test
    void concluirGravaMarcaComRetencao() {
        servico.concluir(id, HASH);

        verify(valores).set(chave, "C|" + HASH, Duration.ofDays(2));
    }

    @Test
    void falhasDoRedisAoConcluirOuLiberarNaoPropagam() {
        when(redis.delete(chave)).thenThrow(new RedisConnectionFailureException("fora do ar"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("fora do ar"))
                .when(valores).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> {
            servico.concluir(id, HASH);
            servico.liberar(id);
        }).doesNotThrowAnyException();
        assertThat(registry.counter("rtc.worker.idempotencia.indisponivel").count()).isEqualTo(2);
    }
}
