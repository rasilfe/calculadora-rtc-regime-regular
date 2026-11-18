package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Valida os manifestos de {@code k8s/}: YAML bem formado, campos obrigatórios e
 * coerência entre topologia do RabbitMQ, modelo por empresa, Deployment, KEDA e a
 * configuração do worker.
 */
class ManifestosK8sTest {

    private static final Path RAIZ = Path.of("k8s");
    private static final Path MODELO_EMPRESA = RAIZ.resolve("empresas/modelo-empresa.yaml");
    private static final List<Map<String, Object>> DOCUMENTOS = new ArrayList<>();
    private static final WorkerProperties PADRAO = new WorkerProperties();

    @BeforeAll
    static void carregar() throws IOException {
        try (Stream<Path> arquivos = Files.walk(RAIZ)) {
            // ambientes/ contém overlays (patches que repetem tipo e nome da base): ver AmbienteVpsTest
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".yaml"))
                    .filter(p -> !p.equals(MODELO_EMPRESA))
                    .filter(p -> !p.startsWith(RAIZ.resolve("ambientes"))).toList()) {
                try (Reader r = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
                    DOCUMENTOS.addAll(carregarTodos(r, arquivo.toString()));
                }
            }
        }
    }

    @Test
    void todosOsDocumentosTemApiVersionKindENome() {
        assertThat(DOCUMENTOS).hasSizeGreaterThan(15);
        for (var doc : DOCUMENTOS) {
            assertThat(doc).as(doc.get("__arquivo").toString()).containsKeys("apiVersion", "kind");
            if (!"Kustomization".equals(doc.get("kind"))) {
                assertThat(mapa(doc, "metadata")).as(doc.get("__arquivo").toString()).containsKey("name");
            }
        }
    }

    @Test
    void kustomizationListaArquivosExistentes() {
        final var kustomizations = doTipo(DOCUMENTOS, "Kustomization");
        assertThat(kustomizations).hasSize(2);
        for (var kustomization : kustomizations) {
            final Path dir = Path.of(kustomization.get("__arquivo").toString()).getParent();
            for (Object recurso : (List<?>) kustomization.get("resources")) {
                assertThat(dir.resolve(recurso.toString())).exists();
            }
        }
    }

    @Test
    void exchangesSaoTopicParaRotearPorEmpresa() {
        assertThat(doTipo(DOCUMENTOS, "Exchange")).hasSize(3)
                .allSatisfy(e -> assertThat(mapa(e, "spec")).containsEntry("type", "topic"));
    }

    @Test
    void filaDeEntradaCompartilhadaEDlqs() {
        final List<String> filas = nomes(doTipo(DOCUMENTOS, "Queue"));
        final String filaBase = PADRAO.getBaseCalculo().getFilaEntrada();
        assertThat(filas).containsExactlyInAnyOrder(PADRAO.getFilaEntrada(), PADRAO.getFilaEntrada() + ".dlq",
                WorkerConfig.DLQ_SAIDA, filaBase, filaBase + ".dlq");
        assertThat(doTipo(DOCUMENTOS, "Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                .containsEntry("source", WorkerConfig.EXCHANGE_ENTRADA)
                .containsEntry("destination", filaBase)
                .containsEntry("routingKey", WorkerConfig.BINDING_BASE_ENTRADA));
        assertThat(doTipo(DOCUMENTOS, "Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                .containsEntry("source", WorkerConfig.EXCHANGE_DLX)
                .containsEntry("destination", WorkerConfig.DLQ_SAIDA)
                .containsEntry("routingKey", PADRAO.getBaseCalculo().getPrefixoRoutingKeySaida() + "#"));
        assertThat(doTipo(DOCUMENTOS, "Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                .containsEntry("source", WorkerConfig.EXCHANGE_DLX)
                .containsEntry("destination", filaBase + ".dlq")
                .containsEntry("routingKey", filaBase + ".dlq"));
        doTipo(DOCUMENTOS, "Queue").forEach(q -> assertThat(mapa(q, "spec")).containsEntry("type", "quorum"));

        assertThat(doTipo(DOCUMENTOS, "Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                .containsEntry("source", WorkerConfig.EXCHANGE_ENTRADA)
                .containsEntry("destination", PADRAO.getFilaEntrada())
                .containsEntry("routingKey", WorkerConfig.BINDING_ENTRADA));
        assertThat(doTipo(DOCUMENTOS, "Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                .containsEntry("source", WorkerConfig.EXCHANGE_DLX)
                .containsEntry("destination", WorkerConfig.DLQ_SAIDA)
                .containsEntry("routingKey", PADRAO.getMultiEmpresa().getPrefixoRoutingKeySaida() + "#"));

        final List<String> exchanges = doTipo(DOCUMENTOS, "Exchange").stream()
                .map(e -> (String) mapa(e, "spec").get("name")).toList();
        doTipo(DOCUMENTOS, "Binding").forEach(b -> assertThat(exchanges).contains((String) mapa(b, "spec").get("source")));
    }

    @Test
    void policiesProtegemContraPerdaEMensagemEnvenenada() {
        final var entrada = policyQueAplicaA(PADRAO.getFilaEntrada());
        assertThat(entrada).containsEntry("dead-letter-exchange", WorkerConfig.EXCHANGE_DLX)
                .containsEntry("dead-letter-routing-key", PADRAO.getFilaEntrada() + ".dlq")
                .containsEntry("dead-letter-strategy", "at-least-once")
                .containsEntry("overflow", "reject-publish")
                .containsKeys("max-length", "delivery-limit");

        final String filaBase = PADRAO.getBaseCalculo().getFilaEntrada();
        assertThat(policyQueAplicaA(filaBase)).containsEntry("dead-letter-exchange", WorkerConfig.EXCHANGE_DLX)
                .containsEntry("dead-letter-routing-key", filaBase + ".dlq")
                .containsEntry("overflow", "reject-publish")
                .containsKeys("max-length", "delivery-limit");

        for (String cnpj : List.of("11222333000181", "12ABC34501DE35")) {
            for (String fila : List.of(WorkerConfig.filaSaidaDaEmpresa(cnpj), WorkerConfig.filaBaseSaidaDaEmpresa(cnpj))) {
                assertThat(policyQueAplicaA(fila)).as(fila).containsEntry("dead-letter-exchange", WorkerConfig.EXCHANGE_DLX)
                        .doesNotContainKey("dead-letter-routing-key")
                        .containsEntry("overflow", "reject-publish")
                        .containsKeys("max-length", "delivery-limit");
            }
        }
        // a DLQ de saída não é capturada pela policy das filas por empresa
        assertThat(doTipo(DOCUMENTOS, "Policy").stream()
                .filter(p -> WorkerConfig.DLQ_SAIDA.matches((String) mapa(p, "spec").get("pattern")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "11222333000181", "12ABC34501DE35" })
    void modeloDeEmpresaIsolaPublicacaoELeitura(String cnpj) throws IOException {
        final var docs = renderizarModelo(cnpj);
        final String outro = cnpj.equals("11222333000181") ? "12ABC34501DE35" : "11222333000181";
        final var prefixoEntrada = PADRAO.getMultiEmpresa().getPrefixoRoutingKeyEntrada();
        final var prefixoSaida = PADRAO.getMultiEmpresa().getPrefixoRoutingKeySaida();

        final var topic = mapa(mapa(unico(docs, "TopicPermission", null), "spec"), "permissions");
        assertThat(topic).containsEntry("exchange", WorkerConfig.EXCHANGE_ENTRADA);
        assertThat(prefixoEntrada + cnpj).matches((String) topic.get("write"));
        assertThat(prefixoEntrada + outro).doesNotMatch((String) topic.get("write"));
        assertThat(prefixoEntrada + cnpj + ".x").doesNotMatch((String) topic.get("write"));
        final var prefixoBase = PADRAO.getBaseCalculo().getPrefixoRoutingKeyEntrada();
        assertThat(prefixoBase + cnpj).matches((String) topic.get("write"));
        assertThat(prefixoBase + outro).doesNotMatch((String) topic.get("write"));
        assertThat("outro." + cnpj).doesNotMatch((String) topic.get("write"));

        final var permissoes = mapa(mapa(unico(docs, "Permission", null), "spec"), "permissions");
        assertThat(WorkerConfig.EXCHANGE_ENTRADA).matches((String) permissoes.get("write"));
        assertThat(PADRAO.getExchangeSaida()).doesNotMatch((String) permissoes.get("write"));
        assertThat(WorkerConfig.filaSaidaDaEmpresa(cnpj)).matches((String) permissoes.get("read"));
        assertThat(WorkerConfig.filaSaidaDaEmpresa(outro)).doesNotMatch((String) permissoes.get("read"));
        assertThat(PADRAO.getFilaEntrada()).doesNotMatch((String) permissoes.get("read"));
        assertThat(WorkerConfig.filaBaseSaidaDaEmpresa(cnpj)).matches((String) permissoes.get("read"));
        assertThat(WorkerConfig.filaBaseSaidaDaEmpresa(outro)).doesNotMatch((String) permissoes.get("read"));
        assertThat(PADRAO.getBaseCalculo().getFilaEntrada()).doesNotMatch((String) permissoes.get("read"));

        // uma fila de saída para notas e outra para base de cálculo
        final var prefixoBaseSaida = PADRAO.getBaseCalculo().getPrefixoRoutingKeySaida();
        assertThat(doTipo(docs, "Queue")).hasSize(2);
        assertThat(doTipo(docs, "Binding")).hasSize(2);
        for (var esperado : List.of(List.of(WorkerConfig.filaSaidaDaEmpresa(cnpj), prefixoSaida + cnpj),
                List.of(WorkerConfig.filaBaseSaidaDaEmpresa(cnpj), prefixoBaseSaida + cnpj))) {
            final String nomeFila = esperado.get(0);
            assertThat(doTipo(docs, "Queue")).anySatisfy(q -> assertThat(mapa(q, "spec"))
                    .containsEntry("name", nomeFila).containsEntry("type", "quorum"));
            assertThat(policyQueAplicaA(nomeFila)).isNotNull();
            assertThat(doTipo(docs, "Binding")).anySatisfy(b -> assertThat(mapa(b, "spec"))
                    .containsEntry("source", PADRAO.getExchangeSaida())
                    .containsEntry("destination", nomeFila)
                    .containsEntry("routingKey", esperado.get(1)));
        }

        // nomes de objetos do Kubernetes em minúsculas (CNPJ alfanumérico tem letras)
        docs.forEach(d -> assertThat((String) mapa(d, "metadata").get("name")).isEqualTo(
                ((String) mapa(d, "metadata").get("name")).toLowerCase(Locale.ROOT)));
    }

    @Test
    void deploymentEKedaApontamParaAMesmaFila() {
        final var deployment = unico(DOCUMENTOS, "Deployment", "rtc-worker");
        final var container = mapa(lista(mapa(mapa(mapa(deployment, "spec"), "template"), "spec"), "containers"), 0);
        final Map<String, Object> env = new HashMap<>();
        for (Object e : (List<?>) container.get("env")) {
            final var item = (Map<?, ?>) e;
            env.put((String) item.get("name"), item.get("value"));
        }
        assertThat(env).containsEntry("WORKER_FILA_ENTRADA", PADRAO.getFilaEntrada())
                .containsEntry("WORKER_EXCHANGE_SAIDA", PADRAO.getExchangeSaida())
                .containsEntry("WORKER_EXIGIR_ROUTING_KEY_DA_EMPRESA", "true")
                .containsEntry("SPRING_PROFILES_ACTIVE", "offline");
        assertThat(mapa(mapa(container, "resources"), "limits")).doesNotContainKey("cpu");

        final var scaled = unico(DOCUMENTOS, "ScaledObject", "rtc-worker");
        assertThat(mapa(mapa(scaled, "spec"), "scaleTargetRef")).containsEntry("name", "rtc-worker");
        final var trigger = mapa(lista(mapa(scaled, "spec"), "triggers"), 0);
        assertThat(mapa(trigger, "metadata")).containsEntry("queueName", PADRAO.getFilaEntrada())
                .containsEntry("vhostName", "rtc");
        final var triggerBase = mapa(lista(mapa(scaled, "spec"), "triggers"), 1);
        assertThat(mapa(triggerBase, "metadata")).containsEntry("queueName", PADRAO.getBaseCalculo().getFilaEntrada())
                .containsEntry("vhostName", "rtc");
        assertThat((int) mapa(scaled, "spec").get("minReplicaCount")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void usuariosDoOperadorCorrespondemAosSecretsUsados() {
        final List<String> users = doTipo(DOCUMENTOS, "User").stream()
                .map(u -> (String) mapa(u, "metadata").get("name")).toList();
        assertThat(users).containsExactlyInAnyOrder("rtc-worker", "rtc-keda");
        assertThat(DOCUMENTOS.toString()).contains("rtc-worker-user-credentials", "rtc-keda-user-credentials");
    }

    @Test
    void workerLeAsDuasFilasDeEntradaEPublicaSoNaSaida() {
        final var permissoes = mapa(mapa(unico(doTipo(DOCUMENTOS, "Permission"), "Permission", "rtc-worker"), "spec"),
                "permissions");
        final String leitura = (String) permissoes.get("read");
        assertThat(PADRAO.getFilaEntrada()).matches(leitura);
        assertThat(PADRAO.getBaseCalculo().getFilaEntrada()).matches(leitura);
        assertThat(WorkerConfig.filaSaidaDaEmpresa("11222333000181")).doesNotMatch(leitura);
        assertThat(PADRAO.getExchangeSaida()).matches((String) permissoes.get("write"));
        assertThat(WorkerConfig.EXCHANGE_ENTRADA).doesNotMatch((String) permissoes.get("write"));
    }

    // ------------------------------------------------------------------ apoio

    private static List<Map<String, Object>> renderizarModelo(String cnpj) throws IOException {
        final String yaml = Files.readString(MODELO_EMPRESA, StandardCharsets.UTF_8)
                .replace("__CNPJ__", cnpj)
                .replace("__cnpj__", cnpj.toLowerCase(Locale.ROOT));
        assertThat(yaml).doesNotContain("__");
        return carregarTodos(new StringReader(yaml), MODELO_EMPRESA.toString());
    }

    private static List<Map<String, Object>> carregarTodos(Reader r, String origem) {
        final List<Map<String, Object>> docs = new ArrayList<>();
        for (Object doc : new Yaml().loadAll(r)) {
            if (doc != null) {
                @SuppressWarnings("unchecked")
                final var mapa = (Map<String, Object>) doc;
                mapa.put("__arquivo", origem);
                docs.add(mapa);
            }
        }
        return docs;
    }

    private static Map<String, Object> policyQueAplicaA(String fila) {
        final var policies = doTipo(DOCUMENTOS, "Policy").stream()
                .filter(p -> fila.matches((String) mapa(p, "spec").get("pattern")))
                .toList();
        assertThat(policies).as("policy para " + fila).hasSize(1);
        return mapa(mapa(policies.get(0), "spec"), "definition");
    }

    private static List<String> nomes(List<Map<String, Object>> docs) {
        return docs.stream().map(d -> mapa(d, "spec").get("name")).filter(Objects::nonNull)
                .map(Object::toString).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Map<String, Object> origem, String chave) {
        return (Map<String, Object>) Objects.requireNonNull(origem.get(chave), chave);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(List<?> lista, int indice) {
        return (Map<String, Object>) lista.get(indice);
    }

    private static List<?> lista(Map<String, Object> origem, String chave) {
        return (List<?>) Objects.requireNonNull(origem.get(chave), chave);
    }

    private static List<Map<String, Object>> doTipo(List<Map<String, Object>> docs, String kind) {
        return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
    }

    private static Map<String, Object> unico(List<Map<String, Object>> docs, String kind, String nome) {
        final var lista = doTipo(docs, kind).stream()
                .filter(d -> nome == null || nome.equals(mapa(d, "metadata").get("name")))
                .toList();
        assertThat(lista).as(kind + " " + nome).hasSize(1);
        return lista.get(0);
    }
}
