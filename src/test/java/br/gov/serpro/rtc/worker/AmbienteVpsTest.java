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
 * Overlay de nó único ({@code k8s/ambientes/vps}) para uma VPS de 4 vCPU e 16 GB:
 * patches apontam para recursos da base, os recursos cabem na máquina e as conexões
 * ao PostgreSQL cabem no max_connections.
 */
class AmbienteVpsTest {

    private static final Path BASE = Path.of("k8s/base");
    private static final Path VPS = Path.of("k8s/ambientes/vps");
    private static final Path BANCO = VPS.resolve("banco");

    /** Capacidade da VPS, descontada uma reserva para k3s, operadores, KEDA e cert-manager. */
    private static final double CPU_DISPONIVEL = 4.0 - 0.5;
    private static final double MEMORIA_DISPONIVEL_GIB = 16.0 - 3.0;

    private static final List<Map<String, Object>> BASE_DOCS = new ArrayList<>();
    private static Map<String, Object> kustomization;
    private static final List<Map<String, Object>> PATCHES = new ArrayList<>();
    private static final List<Map<String, Object>> BANCO_DOCS = new ArrayList<>();

    @BeforeAll
    static void carregar() throws IOException {
        kustomization = carregar(VPS.resolve("kustomization.yaml")).get(0);
        final var baseKustomization = carregar(BASE.resolve("kustomization.yaml")).get(0);
        for (Object r : (List<?>) baseKustomization.get("resources")) {
            BASE_DOCS.addAll(carregar(BASE.resolve(r.toString())));
        }
        for (Object p : (List<?>) kustomization.get("patches")) {
            PATCHES.addAll(carregar(VPS.resolve((String) ((Map<?, ?>) p).get("path"))));
        }
        for (Object r : (List<?>) carregar(BANCO.resolve("kustomization.yaml")).get(0).get("resources")) {
            BANCO_DOCS.addAll(carregar(BANCO.resolve(r.toString())));
        }
    }

    @Test
    void overlayUsaABaseETodoPatchApontaParaUmRecursoDela() {
        assertThat(((List<?>) kustomization.get("resources")).stream().map(String::valueOf).toList()).containsExactly("../../base");
        assertThat(kustomization).containsEntry("namespace", "rtc");
        for (var patch : PATCHES) {
            final String kind = (String) patch.get("kind");
            final String nome = (String) mapa(patch, "metadata").get("name");
            assertThat(BASE_DOCS).as("patch %s/%s sem alvo na base", kind, nome)
                    .anySatisfy(d -> {
                        assertThat(d.get("kind")).isEqualTo(kind);
                        assertThat(mapa(d, "metadata").get("name")).isEqualTo(nome);
                    });
        }
    }

    @Test
    void rabbitmqComUmaReplicaSemAntiAffinity() {
        final var spec = mapa(patch("RabbitmqCluster", "rtc-rabbit"), "spec");
        assertThat(spec).containsEntry("replicas", 1);
        // CRD: o kustomize aplica JSON merge patch, em que null remove a chave
        assertThat(spec).containsKey("affinity");
        assertThat(spec.get("affinity")).isNull();
    }

    @Test
    void pdbDoWorkerRemovidoEKedaLimitadoADoisPods() {
        assertThat(patch("PodDisruptionBudget", "rtc-worker")).containsEntry("$patch", "delete");
        final var keda = mapa(patch("ScaledObject", "rtc-worker"), "spec");
        assertThat(keda).containsEntry("minReplicaCount", 1).containsEntry("maxReplicaCount", 2);
    }

    @Test
    void recursosCabemNaVps() {
        final int maxWorkers = (int) mapa(patch("ScaledObject", "rtc-worker"), "spec").get("maxReplicaCount");
        final var worker = recursosDoContainer(patch("Deployment", "rtc-worker"));
        final var rabbit = mapa(mapa(patch("RabbitmqCluster", "rtc-rabbit"), "spec"), "resources");
        final var redis = recursosDoContainer(doc(BASE_DOCS, "Deployment", "rtc-redis"));
        final var postgres = recursosDoContainer(doc(BANCO_DOCS, "StatefulSet", "rtc-postgres"));

        final double cpu = cpu(worker, "requests") * maxWorkers + cpu(rabbit, "requests") + cpu(redis, "requests")
                + cpu(postgres, "requests");
        final double memoria = gib(worker, "limits") * maxWorkers + gib(rabbit, "limits") + gib(redis, "limits")
                + gib(postgres, "limits");

        assertThat(cpu).as("CPU reservada (vCPU)").isLessThanOrEqualTo(CPU_DISPONIVEL);
        assertThat(memoria).as("limite de memória somado (GiB)").isLessThanOrEqualTo(MEMORIA_DISPONIVEL_GIB);
        assertThat(mapa(worker, "limits")).doesNotContainKey("cpu");
    }

    @Test
    void conexoesDosWorkersCabemNoPostgres() {
        final int maxWorkers = (int) mapa(patch("ScaledObject", "rtc-worker"), "spec").get("maxReplicaCount");
        final int pool = Integer.parseInt(env(patch("Deployment", "rtc-worker"), "DB_POOL_MAX"));
        final int maxConexoes = maxConnections(doc(BANCO_DOCS, "StatefulSet", "rtc-postgres"));
        // + Job de migração (pool padrão do Hikari) + folga para administração
        assertThat(pool * maxWorkers + 10 + 5).isLessThanOrEqualTo(maxConexoes);
    }

    @Test
    void postgresTemScriptDeUsuariosExecutavelESecretsEsperados() throws IOException {
        final var sts = doc(BANCO_DOCS, "StatefulSet", "rtc-postgres");
        final var podSpec = mapa(mapa(mapa(sts, "spec"), "template"), "spec");
        final var volumeInit = ((List<?>) podSpec.get("volumes")).stream().map(v -> (Map<?, ?>) v)
                .filter(v -> "init".equals(v.get("name"))).findFirst().orElseThrow();
        // 0555 em YAML 1.1 (octal) = 365: o script roda como processo próprio
        assertThat(((Map<?, ?>) volumeInit.get("configMap")).get("defaultMode")).isEqualTo(0555);

        final String yaml = Files.readString(BANCO.resolve("postgres.yaml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("key: senha-admin", "key: senha-migracao", "key: senha-leitura",
                "CREATE ROLE rtc_migracao", "CREATE ROLE rtc_leitura",
                "GRANT SELECT ON TABLES TO rtc_leitura");
        final var configMap = doc(BANCO_DOCS, "ConfigMap", "rtc-postgres-init");
        assertThat((String) mapa(configMap, "data").get("RTC_DB_NAME")).matches("calculadora_pro_v\\d{4}");
    }

    // ------------------------------------------------------------------ apoio

    private static Map<String, Object> patch(String kind, String nome) {
        return doc(PATCHES, kind, nome);
    }

    private static Map<String, Object> doc(List<Map<String, Object>> docs, String kind, String nome) {
        return docs.stream().filter(d -> kind.equals(d.get("kind")) && nome.equals(mapa(d, "metadata").get("name")))
                .findFirst().orElseThrow(() -> new AssertionError(kind + "/" + nome + " não encontrado"));
    }

    private static Map<String, Object> container(Map<String, Object> carga) {
        final var podSpec = mapa(mapa(mapa(carga, "spec"), "template"), "spec");
        @SuppressWarnings("unchecked")
        final var c = (Map<String, Object>) ((List<?>) podSpec.get("containers")).get(0);
        return c;
    }

    private static Map<String, Object> recursosDoContainer(Map<String, Object> carga) {
        return mapa(container(carga), "resources");
    }

    private static String env(Map<String, Object> carga, String nome) {
        return ((List<?>) container(carga).get("env")).stream().map(e -> (Map<?, ?>) e)
                .filter(e -> nome.equals(e.get("name"))).map(e -> String.valueOf(e.get("value")))
                .findFirst().orElseThrow();
    }

    private static int maxConnections(Map<String, Object> sts) {
        final var args = (List<?>) container(sts).get("args");
        for (Object a : args) {
            final Matcher m = Pattern.compile("max_connections=(\\d+)").matcher(a.toString());
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
        }
        throw new AssertionError("max_connections não definido");
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
