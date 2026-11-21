package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Overlay enxuto ({@code k8s/ambientes/enxuto}): nó único de cerca de 6 GB que divide o
 * host com outras VMs. Mesmo desenho do overlay vps (que ele repete, pois o kustomize não
 * empilha overlays da mesma base), com um worker só e limites de memória menores.
 */
class AmbienteEnxutoTest {

    private static final Path BASE = Path.of("k8s/base");
    private static final Path ENXUTO = Path.of("k8s/ambientes/enxuto");
    private static final Path VPS = Path.of("k8s/ambientes/vps");

    /** 6 GB da VM menos reservas do kubelet (~1,2 GiB) e k3s, cert-manager, operadores e KEDA (~1,3 GiB). */
    private static final double MEMORIA_DISPONIVEL_GIB = 6.0 - 2.5;
    private static final double CPU_DISPONIVEL = 4.0 - 0.5 - 0.5;

    private static final List<Map<String, Object>> BASE_DOCS = new ArrayList<>();
    private static final List<Map<String, Object>> PATCHES = new ArrayList<>();
    private static final List<Map<String, Object>> PATCHES_VPS = new ArrayList<>();
    private static Map<String, Object> kustomization;
    private static Map<String, Object> patchPostgres;

    @BeforeAll
    static void carregar() throws IOException {
        kustomization = carregar(ENXUTO.resolve("kustomization.yaml")).get(0);
        for (Object r : (List<?>) carregar(BASE.resolve("kustomization.yaml")).get(0).get("resources")) {
            BASE_DOCS.addAll(carregar(BASE.resolve(r.toString())));
        }
        for (Object p : (List<?>) kustomization.get("patches")) {
            PATCHES.addAll(carregar(ENXUTO.resolve((String) ((Map<?, ?>) p).get("path"))));
        }
        for (Object p : (List<?>) carregar(VPS.resolve("kustomization.yaml")).get(0).get("patches")) {
            PATCHES_VPS.addAll(carregar(VPS.resolve((String) ((Map<?, ?>) p).get("path"))));
        }
        final var banco = carregar(ENXUTO.resolve("banco/kustomization.yaml")).get(0);
        assertThat(((List<?>) banco.get("resources")).stream().map(String::valueOf).toList())
                .containsExactly("../../vps/banco");
        patchPostgres = carregar(ENXUTO.resolve("banco/postgres.yaml")).get(0);
    }

    @Test
    void parteDaBaseETodoPatchTemAlvo() {
        assertThat(((List<?>) kustomization.get("resources")).stream().map(String::valueOf).toList())
                .containsExactly("../../base", "rabbitmq-lan.yaml", "metricas-lan.yaml");
        for (var patch : PATCHES) {
            final String kind = (String) patch.get("kind");
            final String nome = (String) mapa(patch, "metadata").get("name");
            assertThat(BASE_DOCS).as("patch %s/%s sem alvo na base", kind, nome).anySatisfy(d -> {
                assertThat(d.get("kind")).isEqualTo(kind);
                assertThat(mapa(d, "metadata").get("name")).isEqualTo(nome);
            });
        }
    }

    @Test
    void repeteOQueOOverlayVpsAjustaParaNoUnico() {
        // todo recurso que o vps altera, o enxuto também altera
        for (var p : PATCHES_VPS) {
            doc(PATCHES, (String) p.get("kind"), (String) mapa(p, "metadata").get("name"));
        }
        final var rabbit = mapa(doc(PATCHES, "RabbitmqCluster", "rtc-rabbit"), "spec");
        assertThat(rabbit).containsEntry("replicas", 1).containsKey("affinity");
        assertThat(rabbit.get("affinity")).isNull();
        assertThat(doc(PATCHES, "PodDisruptionBudget", "rtc-worker")).containsEntry("$patch", "delete");

        final var podSpec = mapa(mapa(mapa(doc(PATCHES, "Deployment", "rtc-worker"), "spec"), "template"), "spec");
        assertThat(podSpec).containsKey("topologySpreadConstraints");
        assertThat(podSpec.get("topologySpreadConstraints")).isNull();
        assertThat(nomesEnv(doc(PATCHES, "Deployment", "rtc-worker")))
                .containsAll(nomesEnv(doc(PATCHES_VPS, "Deployment", "rtc-worker")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void metricasNaRedeLocalSoParaOPrometheusESemNodePort() throws IOException {
        final var servicos = carregar(ENXUTO.resolve("metricas-lan.yaml"));
        final var prometheus = Files.readString(Path.of("infra/monitoramento/prometheus/prometheus.yml"),
                StandardCharsets.UTF_8);
        assertThat(servicos).hasSize(2).allSatisfy(svc -> {
            final var spec = mapa(svc, "spec");
            assertThat(spec).containsEntry("type", "LoadBalancer")
                    .containsEntry("allocateLoadBalancerNodePorts", false);
            assertThat((List<String>) spec.get("loadBalancerSourceRanges")).containsExactly("192.168.5.50/32");
            final int porta = (int) ((List<Map<String, Object>>) spec.get("ports")).get(0).get("port");
            // o Prometheus coleta exatamente a porta que o Service abre
            assertThat(prometheus).contains("192.168.5.120:" + porta);
        });
        assertThat(servicos).extracting(s -> mapa(mapa(s, "spec"), "selector").get("app.kubernetes.io/name"))
                .containsExactlyInAnyOrder("rtc-worker", "rtc-rabbit");
    }

    @Test
    @SuppressWarnings("unchecked")
    void amqpNaRedeLocalSoParaOTraefikESemNodePort() throws IOException {
        final var svc = carregar(ENXUTO.resolve("rabbitmq-lan.yaml")).get(0);
        final var spec = mapa(svc, "spec");
        assertThat(spec).containsEntry("type", "LoadBalancer")
                // NodePort ficaria aberta para qualquer origem, fora de loadBalancerSourceRanges
                .containsEntry("allocateLoadBalancerNodePorts", false)
                .containsEntry("externalTrafficPolicy", "Local");
        assertThat((List<String>) spec.get("loadBalancerSourceRanges")).isNotEmpty()
                .allSatisfy(r -> assertThat(r).endsWith("/32"));
        assertThat(mapa(spec, "selector")).containsEntry("app.kubernetes.io/name", "rtc-rabbit");
        assertThat((List<Map<String, Object>>) spec.get("ports")).singleElement()
                .satisfies(p -> assertThat(p).containsEntry("port", 5672));

        // o Traefik repassa para o mesmo IP que o filtro libera do outro lado
        final String traefik = Files.readString(Path.of("docs/traefik/rtc-amqps.yml"), StandardCharsets.UTF_8);
        assertThat(traefik).contains("entryPoints:", "- amqps", "HostSNI(", ":5672\"", "minVersion: VersionTLS12");
        final var dinamico = carregar(Path.of("docs/traefik/rtc-amqps.yml")).get(0);
        assertThat(mapa(mapa(dinamico, "tcp"), "routers")).containsKey("rtc-amqps");
        final var estatico = carregar(Path.of("docs/traefik/traefik-estatico.yml")).get(0);
        assertThat(mapa(mapa(estatico, "entryPoints"), "amqps")).containsEntry("address", ":5671");
    }

    @Test
    void ateDoisWorkersERolloutSemPararOConsumo() {
        final var keda = mapa(doc(PATCHES, "ScaledObject", "rtc-worker"), "spec");
        assertThat(keda).containsEntry("minReplicaCount", 1).containsEntry("maxReplicaCount", 2);
        final var rolling = mapa(mapa(mapa(doc(PATCHES, "Deployment", "rtc-worker"), "spec"), "strategy"), "rollingUpdate");
        assertThat(rolling).containsEntry("maxSurge", 1).containsEntry("maxUnavailable", 0);
        // heap menor que o limite padrão de 70%: sobra memória fora do heap no pod de 768 MiB
        assertThat(env(doc(PATCHES, "Deployment", "rtc-worker"), "JAVA_TOOL_OPTIONS"))
                .contains("-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError");
    }

    @Test
    void recursosCabemNaVm() {
        final var worker = mapa(container(doc(PATCHES, "Deployment", "rtc-worker")), "resources");
        final var rabbit = mapa(mapa(doc(PATCHES, "RabbitmqCluster", "rtc-rabbit"), "spec"), "resources");
        final var redis = mapa(container(doc(BASE_DOCS, "Deployment", "rtc-redis")), "resources");
        final var postgres = mapa(container(patchPostgres), "resources");

        final int workers = maxWorkers();

        final double memoria = workers * gib(worker, "limits") + gib(rabbit, "limits") + gib(redis, "limits")
                + gib(postgres, "limits");
        final double cpu = workers * cpu(worker, "requests") + cpu(rabbit, "requests") + cpu(redis, "requests")
                + cpu(postgres, "requests");
        assertThat(memoria).as("limite de memória somado (GiB)").isLessThanOrEqualTo(MEMORIA_DISPONIVEL_GIB);
        assertThat(cpu).as("CPU reservada (vCPU)").isLessThanOrEqualTo(CPU_DISPONIVEL);
    }

    @Test
    void conexoesCabemNoPostgres() {
        final int pool = Integer.parseInt(env(doc(PATCHES, "Deployment", "rtc-worker"), "DB_POOL_MAX"));
        int maxConexoes = -1;
        for (Object a : (List<?>) container(patchPostgres).get("args")) {
            final Matcher m = Pattern.compile("max_connections=(\\d+)").matcher(a.toString());
            if (m.matches()) {
                maxConexoes = Integer.parseInt(m.group(1));
            }
        }
        // todos os workers + Job de migração (pool padrão do Hikari) + folga para administração;
        // no rollout há um pod extra (maxSurge: 1)
        assertThat((maxWorkers() + 1) * pool + 10 + 5).isLessThanOrEqualTo(maxConexoes);
    }

    // ------------------------------------------------------------------ apoio

    private static int maxWorkers() {
        return ((Number) mapa(doc(PATCHES, "ScaledObject", "rtc-worker"), "spec").get("maxReplicaCount")).intValue();
    }

    private static Map<String, Object> doc(List<Map<String, Object>> docs, String kind, String nome) {
        return docs.stream().filter(d -> kind.equals(d.get("kind")) && nome.equals(mapa(d, "metadata").get("name")))
                .findFirst().orElseThrow(() -> new AssertionError(kind + "/" + nome + " não encontrado"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> container(Map<String, Object> carga) {
        final var podSpec = mapa(mapa(mapa(carga, "spec"), "template"), "spec");
        return (Map<String, Object>) ((List<?>) podSpec.get("containers")).get(0);
    }

    private static List<String> nomesEnv(Map<String, Object> carga) {
        return ((List<?>) container(carga).get("env")).stream().map(e -> String.valueOf(((Map<?, ?>) e).get("name"))).toList();
    }

    private static String env(Map<String, Object> carga, String nome) {
        return ((List<?>) container(carga).get("env")).stream().map(e -> (Map<?, ?>) e)
                .filter(e -> nome.equals(e.get("name"))).map(e -> String.valueOf(e.get("value")))
                .findFirst().orElseThrow();
    }

    private static double cpu(Map<String, Object> recursos, String tipo) {
        final String v = String.valueOf(mapa(recursos, tipo).get("cpu"));
        return v.endsWith("m") ? Double.parseDouble(v.substring(0, v.length() - 1)) / 1000 : Double.parseDouble(v);
    }

    private static double gib(Map<String, Object> recursos, String tipo) {
        final String v = String.valueOf(mapa(recursos, tipo).get("memory"));
        if (v.endsWith("Gi")) {
            return Double.parseDouble(v.substring(0, v.length() - 2));
        }
        if (v.endsWith("Mi")) {
            return Double.parseDouble(v.substring(0, v.length() - 2)) / 1024;
        }
        throw new AssertionError("unidade de memória não suportada no teste: " + v);
    }

    private static List<Map<String, Object>> carregar(Path arquivo) throws IOException {
        final List<Map<String, Object>> docs = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            for (Object d : new Yaml().loadAll(r)) {
                if (d != null) {
                    @SuppressWarnings("unchecked")
                    final var mapa = (Map<String, Object>) d;
                    docs.add(mapa);
                }
            }
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Map<String, Object> origem, String chave) {
        return (Map<String, Object>) Objects.requireNonNull(origem.get(chave), chave);
    }
}
