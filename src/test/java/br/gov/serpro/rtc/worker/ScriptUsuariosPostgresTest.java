package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Executa o SQL do script de inicialização do PostgreSQL da VPS
 * ({@code k8s/ambientes/vps/banco/postgres.yaml}) num PostgreSQL real e confere o
 * modelo de permissões: rtc_migracao cria e grava (Flyway); rtc_leitura só lê (workers).
 */
class ScriptUsuariosPostgresTest {

    private static final String BANCO = "calculadora_pro_teste";
    private static EmbeddedPostgres pg;

    @BeforeAll
    static void executarScript() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String sql = sqlDoScript();
        // variáveis do psql (-v) substituídas pelos valores do teste
        sql = sql.replace(":'migracao'", "'senha-m'").replace(":'leitura'", "'senha-l'").replace(":\"banco\"", "\"" + BANCO + "\"");

        Connection atual = conectar("postgres", "postgres");
        try {
            for (String linha : sql.split("\n")) {
                final String l = linha.strip();
                if (l.isEmpty() || l.startsWith("--")) {
                    continue;
                }
                if (l.startsWith("\\connect")) {
                    atual.close();
                    atual = conectar("postgres", l.substring("\\connect".length()).strip().replace("\"", ""));
                    continue;
                }
                try (Statement st = atual.createStatement()) {
                    st.execute(l.endsWith(";") ? l.substring(0, l.length() - 1) : l);
                }
            }
        } finally {
            atual.close();
        }
    }

    @AfterAll
    static void parar() throws IOException {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void migracaoCriaTabelasELeituraSoConsegueLer() throws SQLException {
        try (Connection m = conectar("rtc_migracao", BANCO); Statement st = m.createStatement()) {
            st.execute("CREATE TABLE aliquota (id integer PRIMARY KEY, valor numeric)");
            st.execute("INSERT INTO aliquota VALUES (1, 0.9)");
        }
        try (Connection l = conectar("rtc_leitura", BANCO); Statement st = l.createStatement()) {
            try (var rs = st.executeQuery("SELECT valor FROM aliquota WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("0.9");
            }
            assertThatThrownBy(() -> st.execute("INSERT INTO aliquota VALUES (2, 0.1)"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.execute("UPDATE aliquota SET valor = 0"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.execute("DROP TABLE aliquota")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void bancoPertenceAoUsuarioDeMigracao() throws SQLException {
        try (Connection c = conectar("postgres", "postgres"); Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '" + BANCO + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("rtc_migracao");
        }
    }

    private static Connection conectar(String usuario, String banco) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(usuario, banco), usuario, "qualquer");
    }

    @SuppressWarnings("unchecked")
    private static String sqlDoScript() throws IOException {
        try (Reader r = Files.newBufferedReader(Path.of("k8s/ambientes/vps/banco/postgres.yaml"), StandardCharsets.UTF_8)) {
            for (Object d : new Yaml().loadAll(r)) {
                final var doc = (Map<String, Object>) d;
                if ("ConfigMap".equals(doc.get("kind"))) {
                    final String script = (String) ((Map<String, Object>) doc.get("data")).get("01-usuarios-e-banco.sh");
                    final int ini = script.indexOf("<<'SQL'\n") + "<<'SQL'\n".length();
                    final int fim = script.indexOf("\nSQL", ini);
                    return script.substring(ini, fim);
                }
            }
        }
        throw new IllegalStateException("ConfigMap do script não encontrado");
    }
}
