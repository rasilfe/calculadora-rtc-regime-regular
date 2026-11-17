package br.gov.serpro.rtc.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Sobe um PostgreSQL embarcado (binários baixados pelo Maven, sem Docker) uma
 * única vez por JVM e o expõe como {@link DataSource} dos testes. O Flyway da
 * aplicação aplica as migrações de {@code db/postgres} no primeiro contexto; os
 * demais contextos reutilizam o mesmo banco já migrado.
 */
@Profile("testes")
@Configuration(proxyBeanMethods = false)
public class PostgresEmbarcadoTestConfig {

    private static final class Servidor {
        static final EmbeddedPostgres INSTANCIA = iniciar();

        private static EmbeddedPostgres iniciar() {
            try {
                final EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        pg.close();
                    } catch (IOException e) {
                        // encerramento da JVM: nada a fazer
                    }
                }, "postgres-embarcado-shutdown"));
                return pg;
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao iniciar o PostgreSQL embarcado", e);
            }
        }
    }

    @Bean
    @Primary
    DataSource dataSource(@Value("${application.db.name}") String nomeBanco) throws SQLException {
        criarBancoSeNecessario(nomeBanco);
        final var ds = new HikariDataSource();
        ds.setJdbcUrl(Servidor.INSTANCIA.getJdbcUrl("postgres", nomeBanco));
        ds.setUsername("postgres");
        ds.setMaximumPoolSize(8);
        return ds;
    }

    private static synchronized void criarBancoSeNecessario(String nomeBanco) throws SQLException {
        try (Connection c = Servidor.INSTANCIA.getPostgresDatabase().getConnection();
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + nomeBanco.replace("'", "''") + "'")) {
            if (!rs.next()) {
                st.execute("CREATE DATABASE \"" + nomeBanco.replace("\"", "") + "\"");
            }
        }
    }
}
