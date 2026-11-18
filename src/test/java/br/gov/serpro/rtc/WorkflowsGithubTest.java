package br.gov.serpro.rtc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Coerência dos workflows do GitHub Actions com o repositório: arquivos referenciados
 * existem, deploy só depois do CI, runners e ambientes corretos, e os manifestos têm as
 * linhas que a action de deploy reescreve para fixar a imagem por digest.
 */
class WorkflowsGithubTest {

    private static final Path WORKFLOWS = Path.of(".github/workflows");
    private static final Path ACTION_DEPLOY = Path.of(".github/actions/deploy-k3s/action.yml");

    @Test
    void todosOsYamlDoGithubSaoValidos() throws IOException {
        try (Stream<Path> arquivos = Files.walk(Path.of(".github"))) {
            final List<Path> yamls = arquivos.filter(p -> p.toString().matches(".*\\.ya?ml$")).toList();
            assertThat(yamls).hasSizeGreaterThanOrEqualTo(3);
            for (Path p : yamls) {
                assertThat(carregar(p)).as(p.toString()).isNotEmpty();
            }
        }
    }

    @Test
    void ciPodeSerChamadoPeloDeploySemConstruirImagem() throws IOException {
        final var ci = carregar(WORKFLOWS.resolve("ci.yml"));
        final var gatilhos = mapa(ci, "on");
        assertThat(gatilhos).containsKeys("pull_request", "workflow_call");
        assertThat(mapa(mapa(mapa(gatilhos, "workflow_call"), "inputs"), "construir-imagem"))
                .containsEntry("type", "boolean");
        assertThat(mapa(ci, "jobs")).containsKeys("migracoes", "testes", "manifestos", "imagem");
        assertThat((String) mapa(mapa(ci, "jobs"), "imagem").get("if")).contains("inputs.construir-imagem");
    }

    @Test
    void deployPublicaDepoisDoCiEUsaRunnersSelfHostedPorAmbiente() throws IOException {
        final var deploy = carregar(WORKFLOWS.resolve("deploy.yml"));
        final var jobs = mapa(deploy, "jobs");

        final var ci = mapa(jobs, "ci");
        assertThat(ci.get("uses")).isEqualTo("./.github/workflows/ci.yml");
        assertThat(Path.of(((String) ci.get("uses")).substring(2))).exists();
        assertThat(mapa(ci, "with")).containsEntry("construir-imagem", false);

        final var imagem = mapa(jobs, "imagem");
        assertThat(imagem.get("needs")).isEqualTo("ci");
        assertThat(mapa(imagem, "permissions")).containsEntry("packages", "write");

        for (String ambiente : List.of("homologacao", "producao")) {
            final var job = mapa(jobs, ambiente);
            assertThat(job.get("needs")).isEqualTo("imagem");
            assertThat(((List<?>) job.get("runs-on")).stream().map(String::valueOf).toList())
                    .containsExactly("self-hosted", "k3s", ambiente);
            assertThat(mapa(job, "environment")).containsEntry("name", ambiente);
            final var passos = (List<?>) job.get("steps");
            assertThat(passos).anySatisfy(p -> assertThat(((Map<?, ?>) p).get("uses"))
                    .isEqualTo("./" + ACTION_DEPLOY.getParent().toString().replace('\\', '/')));
        }
        assertThat(ACTION_DEPLOY).exists();
        // produção só por tag; homologação pela main
        assertThat((String) mapa(jobs, "producao").get("if")).contains("refs/tags/v");
        assertThat((String) mapa(jobs, "homologacao").get("if")).contains("refs/heads/main");
    }

    @Test
    void manifestosTemAsLinhasQueAActionReescreve() throws IOException {
        final String action = Files.readString(ACTION_DEPLOY, StandardCharsets.UTF_8);
        assertThat(action).contains("k8s/kustomization.yaml", "k8s/migracao/job.yaml", "calculadora-rtc-worker");

        final String kustomization = Files.readString(Path.of("k8s/kustomization.yaml"), StandardCharsets.UTF_8);
        assertThat(kustomization).containsPattern("(?m)^\\s*- name: calculadora-rtc-worker$")
                .containsPattern("(?m)^\\s*newName: ghcr\\.io/[a-z0-9-]+/calculadora-rtc-worker$")
                .containsPattern("(?m)^\\s*newTag:");

        // o Job não está na kustomization: a imagem é trocada por sed nesta linha
        final String job = Files.readString(Path.of("k8s/migracao/job.yaml"), StandardCharsets.UTF_8);
        assertThat(job).containsPattern("(?m)^\\s*image: calculadora-rtc-worker\\s*$");
        // o Deployment usa o mesmo nome lógico, resolvido pela kustomization
        final String deployment = Files.readString(Path.of("k8s/worker/deployment.yaml"), StandardCharsets.UTF_8);
        assertThat(deployment).containsPattern("(?m)^\\s*image: calculadora-rtc-worker\\s*$");
    }

    private static Map<String, Object> carregar(Path arquivo) throws IOException {
        try (Reader r = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            final Map<Object, Object> bruto = new Yaml().load(r);
            final Map<String, Object> mapa = new java.util.LinkedHashMap<>();
            // YAML 1.1 (SnakeYAML) lê a chave "on" dos workflows como booleano true
            bruto.forEach((k, v) -> mapa.put(Boolean.TRUE.equals(k) ? "on" : String.valueOf(k), v));
            return mapa;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Map<String, Object> origem, String chave) {
        return (Map<String, Object>) Objects.requireNonNull(origem.get(chave), chave);
    }
}
