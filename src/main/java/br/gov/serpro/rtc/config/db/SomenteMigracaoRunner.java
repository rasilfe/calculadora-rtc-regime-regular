package br.gov.serpro.rtc.config.db;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Modo "somente migração" ({@code calculadora.somente-migracao=true}): a aplicação
 * sobe, o Flyway aplica as migrações durante a inicialização e o processo termina.
 * Usado pelo Job de migração do k3s, com usuário de escrita; os workers usam um
 * usuário somente leitura e não migram o banco.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "calculadora", name = "somente-migracao", havingValue = "true")
public class SomenteMigracaoRunner implements ApplicationRunner {

    private final ApplicationContext contexto;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Migrações aplicadas; encerrando (calculadora.somente-migracao=true)");
        System.exit(SpringApplication.exit(contexto, () -> 0));
    }
}
