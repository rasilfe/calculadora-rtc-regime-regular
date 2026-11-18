package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import br.gov.serpro.rtc.api.model.roc.ObservabilidadeItemDomain;
import br.gov.serpro.rtc.domain.model.enumeration.EstadoItemEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * Benchmark de vazão do cálculo de notas (Fase 0 do plano de migração para filas).
 *
 * <p>Mede o trabalho que um worker fará por mensagem, exceto o transporte AMQP:
 * desserializar a {@link NotaEntrada}, calcular com o {@link CalculoNotaService}
 * e serializar o {@link ResultadoNota}. As notas são montadas a partir dos 262
 * itens reais de {@code Teste_Varios_Itens.json}, com 8 ou 9 itens por nota
 * (média de 8,75, como no volume esperado).
 *
 * <p>Não roda no {@code mvn test} padrão. Para executar:
 *
 * <pre>
 * ./mvnw test -Dsurefire.excludedGroups=nenhum -Dgroups=benchmark
 * </pre>
 *
 * Propriedades opcionais: {@code -Dbenchmark.notas=8000},
 * {@code -Dbenchmark.aquecimento=2000} e {@code -Dbenchmark.concorrencias=1,4,8}
 * (consumidores simultâneos; padrão = número de CPUs). O relatório é gravado em
 * {@code target/benchmark/calculo-nota.txt}.
 */
@Slf4j
@Tag("benchmark")
@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml", properties = "logging.level.br.gov.serpro.rtc=WARN")
@ActiveProfiles("testes")
class CalculoNotaBenchmarkTest {

    private static final int[] ITENS_POR_NOTA = { 9, 9, 9, 8 };

    @Autowired
    private CalculoNotaService calculoNotaService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @Value("classpath:entradas/calculoscorretos/varios_itens/Teste_Varios_Itens.json")
    private Resource baseItens;

    @Test
    void medirVazao() throws Exception {
        final int totalNotas = Integer.getInteger("benchmark.notas", 8000);
        final int aquecimento = Integer.getInteger("benchmark.aquecimento", 2000);
        final int cpus = Runtime.getRuntime().availableProcessors();
        final int[] concorrencias = Arrays.stream(System.getProperty("benchmark.concorrencias", String.valueOf(cpus))
                .split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();

        final List<byte[]> mensagens = gerarMensagens(totalNotas);
        final long totalItens = contarItens(mensagens);

        final var relatorio = new StringBuilder();
        relatorio.append(String.format(Locale.ROOT, "Benchmark CalculoNotaService%nJVM %s, %d CPUs, heap máx. %d MB%n",
                Runtime.version(), cpus, Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        relatorio.append(String.format(Locale.ROOT, "Carga: %d notas, %d itens (%.2f itens/nota)%n%n",
                totalNotas, totalItens, (double) totalItens / totalNotas));

        // Aquecimento: JIT e caches Caffeine (o banco só é lido em cache miss).
        executar(mensagens.subList(0, Math.min(aquecimento, mensagens.size())), cpus);
        final CacheStats aposAquecimento = estatisticasCache();

        for (int concorrencia : concorrencias) {
            final Medicao m = executar(mensagens, concorrencia);
            relatorio.append(m.formatar(concorrencia, totalNotas, totalItens));
            assertThat(m.processadas.sum()).isEqualTo(totalNotas);
            assertThat(m.falhas.sum()).as("notas que lançaram exceção").isZero();
        }

        final CacheStats medido = estatisticasCache().minus(aposAquecimento);
        relatorio.append(String.format(Locale.ROOT,
                "Caffeine após aquecimento: %d consultas, taxa de acerto %.2f%%, %d misses (leituras no banco)%n",
                medido.requestCount(), medido.hitRate() * 100, medido.missCount()));

        final Path arquivo = Path.of("target", "benchmark", "calculo-nota.txt");
        Files.createDirectories(arquivo.getParent());
        Files.writeString(arquivo, relatorio, StandardCharsets.UTF_8);
        System.out.println(relatorio);
    }

    private Medicao executar(List<byte[]> mensagens, int concorrencia) throws Exception {
        final var medicao = new Medicao();
        final var limite = new Semaphore(concorrencia);
        final var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long cpuInicio = os.getProcessCpuTime();
        final long inicio = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<?>> futuros = new ArrayList<>(mensagens.size());
            for (byte[] mensagem : mensagens) {
                limite.acquire();
                futuros.add(executor.submit(() -> {
                    try {
                        processar(mensagem, medicao);
                    } finally {
                        limite.release();
                    }
                }));
            }
            for (Future<?> f : futuros) {
                f.get();
            }
        }
        medicao.wallNanos = System.nanoTime() - inicio;
        medicao.cpuNanos = os.getProcessCpuTime() - cpuInicio;
        return medicao;
    }

    /** O que o worker fará por mensagem, exceto o transporte AMQP. */
    private void processar(byte[] mensagem, Medicao medicao) {
        final long t0 = System.nanoTime();
        try {
            final NotaEntrada nota = objectMapper.readValue(mensagem, NotaEntrada.class);
            final ResultadoNota resultado = calculoNotaService.calcular(nota);
            medicao.bytesSaida.add(objectMapper.writeValueAsBytes(resultado).length);
            medicao.status.computeIfAbsent(resultado.status(), s -> new LongAdder()).increment();
            if (resultado.resultado() != null) {
                for (ObservabilidadeItemDomain item : resultado.resultado().getObjetos()) {
                    medicao.estados.computeIfAbsent(item.getEstadoItem(), e -> new LongAdder()).increment();
                }
            }
            medicao.processadas.increment();
        } catch (Exception e) {
            medicao.falhas.increment();
            log.error("Falha ao processar nota no benchmark", e);
        } finally {
            medicao.registrarLatencia(System.nanoTime() - t0);
        }
    }

    private List<byte[]> gerarMensagens(int totalNotas) throws IOException {
        final JsonNode base;
        try (InputStream in = baseItens.getInputStream()) {
            base = objectMapper.readTree(in);
        }
        final ArrayNode itensBase = (ArrayNode) base.get("itens");
        final List<byte[]> mensagens = new ArrayList<>(totalNotas);
        int cursor = 0;
        for (int n = 0; n < totalNotas; n++) {
            final ObjectNode operacao = ((ObjectNode) base).deepCopy();
            final ArrayNode itens = operacao.putArray("itens");
            final int quantidade = ITENS_POR_NOTA[n % ITENS_POR_NOTA.length];
            for (int i = 0; i < quantidade; i++) {
                final ObjectNode item = ((ObjectNode) itensBase.get(cursor++ % itensBase.size())).deepCopy();
                item.put("numero", i + 1);
                itens.add(item);
            }
            final ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("schemaVersion", "1");
            envelope.put("correlationId", UUID.randomUUID().toString());
            envelope.put("idNota", "nota-" + n);
            envelope.put("loteId", "benchmark");
            envelope.put("produzidoEm", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
            envelope.set("payload", operacao);
            mensagens.add(objectMapper.writeValueAsBytes(envelope));
        }
        return mensagens;
    }

    private long contarItens(List<byte[]> mensagens) throws IOException {
        long total = 0;
        for (byte[] m : mensagens) {
            total += objectMapper.readTree(m).get("payload").get("itens").size();
        }
        return total;
    }

    private CacheStats estatisticasCache() {
        CacheStats total = CacheStats.empty();
        for (String nome : cacheManager.getCacheNames()) {
            if (cacheManager.getCache(nome) instanceof CaffeineCache cache) {
                total = total.plus(cache.getNativeCache().stats());
            }
        }
        return total;
    }

    private static final class Medicao {
        final LongAdder processadas = new LongAdder();
        final LongAdder falhas = new LongAdder();
        final LongAdder bytesSaida = new LongAdder();
        final Map<StatusNota, LongAdder> status = new ConcurrentHashMap<>();
        final Map<EstadoItemEnum, LongAdder> estados = new ConcurrentHashMap<>();
        final List<Long> latencias = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicLong maiorLatencia = new AtomicLong();
        long wallNanos;
        long cpuNanos;

        void registrarLatencia(long nanos) {
            latencias.add(nanos);
            maiorLatencia.accumulateAndGet(nanos, Math::max);
        }

        String formatar(int concorrencia, int notas, long itens) {
            final long[] ordenadas = latencias.stream().mapToLong(Long::longValue).sorted().toArray();
            final double segundos = wallNanos / 1e9;
            final Map<EstadoItemEnum, Long> porEstado = new EnumMap<>(EstadoItemEnum.class);
            estados.forEach((k, v) -> porEstado.put(k, v.sum()));
            final Map<StatusNota, Long> porStatus = new EnumMap<>(StatusNota.class);
            status.forEach((k, v) -> porStatus.put(k, v.sum()));
            return String.format(Locale.ROOT, """
                    Concorrência %d (consumidores simultâneos)
                      tempo total ........ %.2f s
                      vazão .............. %.0f notas/s | %.0f itens/s
                      CPU do processo .... %.2f s  => %.3f ms de CPU por item | %.3f ms por nota
                      latência por nota .. p50 %.2f ms | p99 %.2f ms | máx %.2f ms
                      saída média ........ %.1f KB por nota
                      status das notas ... %s
                      estado dos itens ... %s

                    """,
                    concorrencia, segundos, notas / segundos, itens / segundos,
                    cpuNanos / 1e9, cpuNanos / 1e6 / itens, cpuNanos / 1e6 / notas,
                    percentil(ordenadas, 0.50), percentil(ordenadas, 0.99), maiorLatencia.get() / 1e6,
                    bytesSaida.sum() / 1024.0 / notas, porStatus, porEstado);
        }

        private static double percentil(long[] ordenadas, double p) {
            if (ordenadas.length == 0) {
                return 0;
            }
            return ordenadas[(int) Math.min(ordenadas.length - 1, Math.round(p * (ordenadas.length - 1)))] / 1e6;
        }
    }
}
