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
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Valida os manifestos de {@code k8s/}: YAML bem formado, campos obrigatórios e
 * coerência entre topologia do RabbitMQ, Deployment, KEDA e a configuração do worker.
 */
class ManifestosK8sTest {

    private static final Path RAIZ = Path.of("k8s");
    private static final List<Map<String, Object>> DOCUMENTOS = new ArrayList<>();

    @BeforeAll
    static void carregar() throws IOException {
        try (Stream<Path> arquivos = Files.walk(RAIZ)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                try (Reader r = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
                    for (Object doc : new Yaml().loadAll(r)) {
                        if (doc != null) {
                            @SuppressWarnings("unchecked")
                            final var mapa = (Map<String, Object>) doc;
                            mapa.put("__arquivo", arquivo.toString());
                            DOCUMENTOS.add(mapa);
                        }
                    }
                }
            }
        }
    }

    @Test
    void todosOsDocumentosTemApiVersionKindENome() {
        assertThat(DOCUMENTOS).hasSizeGreaterThan(20);
        for (var doc : DOCUMENTOS) {
            assertThat(doc).as(doc.get("__arquivo").toString()).containsKeys("apiVersion", "kind");
            if (!"Kustomization".equals(doc.get("kind"))) {
                assertThat(mapa(doc, "metadata")).as(doc.get("__arquivo").toString()).containsKey("name");
            }
        }
    }

    @Test
    void kustomizationListaArquivosExistentes() {
        final var kustomization = unico("Kustomization", null);
        for (Object recurso : (List<?>) kustomization.get("resources")) {
            assertThat(RAIZ.resolve(recurso.toString())).exists();
        }
    }

    @Test
    void filasEBindingsCoerentesComOWorker() {
        final var padrao = new WorkerProperties();
        final List<String> filas = doTipo("Queue").stream().map(q -> (String) mapa(q, "spec").get("name")).toList();
        assertThat(filas).contains(padrao.getFilaEntrada(), WorkerConfig.FILA_SAIDA,
                padrao.getFilaEntrada() + ".dlq", WorkerConfig.FILA_SAIDA + ".dlq");
        doTipo("Queue").forEach(q -> assertThat(mapa(q, "spec")).containsEntry("type", "quorum"));

        assertThat(doTipo("Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                .containsEntry("source", WorkerConfig.EXCHANGE_ENTRADA)
                .containsEntry("destination", padrao.getFilaEntrada())
                .containsEntry("routingKey", WorkerConfig.ROUTING_KEY_ENTRADA));
        assertThat(doTipo("Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                .containsEntry("source", padrao.getExchangeSaida())
                .containsEntry("destination", WorkerConfig.FILA_SAIDA)
                .containsEntry("routingKey", padrao.getRoutingKeySaida()));

        // Toda exchange usada em bindings existe.
        final List<String> exchanges = doTipo("Exchange").stream().map(e -> (String) mapa(e, "spec").get("name")).toList();
        doTipo("Binding").forEach(b -> assertThat(exchanges).contains((String) mapa(b, "spec").get("source")));
    }

    @Test
    void policiesProtegemContraPerdaEMensagemEnvenenada() {
        for (String fila : List.of(new WorkerProperties().getFilaEntrada(), WorkerConfig.FILA_SAIDA)) {
            final var policy = doTipo("Policy").stream()
                    .filter(p -> fila.matches(((String) mapa(p, "spec").get("pattern"))))
                    .findFirst().orElseThrow(() -> new AssertionError("sem policy para " + fila));
            final var definicao = mapa(mapa(policy, "spec"), "definition");
            assertThat(definicao)
                    .containsEntry("dead-letter-exchange", WorkerConfig.EXCHANGE_DLX)
                    .containsEntry("dead-letter-routing-key", fila + ".dlq")
                    .containsEntry("dead-letter-strategy", "at-least-once")
                    .containsEntry("overflow", "reject-publish")
                    .containsKeys("max-length", "delivery-limit");
        }
    }

    @Test
    void deploymentEKedaApontamParaAMesmaFila() {
        final var padrao = new WorkerProperties();
        final var deployment = unico("Deployment", "rtc-worker");
        final var container = (Map<String, Object>) ((List<?>) mapa(mapa(mapa(deployment, "spec"), "template"), "spec")
                .get("containers")).get(0);
        final Map<String, Object> env = new java.util.HashMap<>();
        for (Object e : (List<?>) container.get("env")) {
            final var item = (Map<?, ?>) e;
            env.put((String) item.get("name"), item.get("value"));
        }
        assertThat(env).containsEntry("WORKER_FILA_ENTRADA", padrao.getFilaEntrada())
                .containsEntry("WORKER_EXCHANGE_SAIDA", padrao.getExchangeSaida())
                .containsEntry("WORKER_ROUTING_KEY_SAIDA", padrao.getRoutingKeySaida())
                .containsEntry("SPRING_PROFILES_ACTIVE", "offline");
        assertThat(mapa(mapa(container, "resources"), "limits")).doesNotContainKey("cpu");

        final var scaled = unico("ScaledObject", "rtc-worker");
        assertThat(mapa(mapa(scaled, "spec"), "scaleTargetRef")).containsEntry("name", "rtc-worker");
        final var trigger = (Map<String, Object>) ((List<?>) mapa(scaled, "spec").get("triggers")).get(0);
        assertThat(mapa(trigger, "metadata")).containsEntry("queueName", padrao.getFilaEntrada())
                .containsEntry("vhostName", "rtc");
        assertThat((int) mapa(scaled, "spec").get("minReplicaCount")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void secretsDosUsuariosDoRabbitCorrespondemAosUsersDoOperador() {
        final List<String> users = doTipo("User").stream().map(u -> (String) mapa(u, "metadata").get("name")).toList();
        assertThat(users).contains("rtc-worker", "rtc-keda");
        final String yaml = DOCUMENTOS.toString();
        assertThat(yaml).contains("rtc-worker-user-credentials", "rtc-keda-user-credentials");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Map<String, Object> origem, String chave) {
        return (Map<String, Object>) Objects.requireNonNull(origem.get(chave), chave);
    }

    private static List<Map<String, Object>> doTipo(String kind) {
        return DOCUMENTOS.stream().filter(d -> kind.equals(d.get("kind"))).toList();
    }

    private static Map<String, Object> unico(String kind, String nome) {
        final var lista = doTipo(kind).stream()
                .filter(d -> nome == null || nome.equals(mapa(d, "metadata").get("name")))
                .toList();
        assertThat(lista).as(kind + " " + nome).hasSize(1);
        return lista.get(0);
    }
}
