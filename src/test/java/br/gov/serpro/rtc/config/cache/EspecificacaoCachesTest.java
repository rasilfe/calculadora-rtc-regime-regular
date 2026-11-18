package br.gov.serpro.rtc.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Todo {@code @Cacheable} precisa de uma especificação em {@code cache.specs} (tamanho
 * máximo e expiração). Sem ela, o CaffeineCacheManager cria o cache dinamicamente e
 * <strong>sem limite</strong>, o que num worker de longa duração vira vazamento de memória.
 */
class EspecificacaoCachesTest {

    private static final Pattern CACHEABLE = Pattern
            .compile("@Cacheable\\(\\s*(?:(?:cacheNames|value)\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern SPEC = Pattern.compile("(?m)^    - name: (\\S+)");

    @ParameterizedTest
    @ValueSource(strings = { "src/main/resources/application-offline.yml", "src/test/resources/application-testes.yml" })
    void todoCacheTemEspecificacaoETodaEspecificacaoTemCache(String perfil) throws IOException {
        final Set<String> caches = new TreeSet<>();
        try (Stream<Path> fontes = Files.walk(Path.of("src/main/java"))) {
            for (Path f : fontes.filter(p -> p.toString().endsWith(".java")).toList()) {
                CACHEABLE.matcher(Files.readString(f, StandardCharsets.UTF_8)).results()
                        .forEach(m -> caches.add(m.group(1)));
            }
        }
        final Set<String> specs = new TreeSet<>();
        SPEC.matcher(Files.readString(Path.of(perfil), StandardCharsets.UTF_8)).results()
                .forEach(m -> specs.add(m.group(1)));

        assertThat(caches).as("@Cacheable sem especificação em " + perfil).isSubsetOf(specs);
        assertThat(specs).as("especificação sem @Cacheable em " + perfil).isSubsetOf(caches);
    }
}
