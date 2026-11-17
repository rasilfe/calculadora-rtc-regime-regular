/*
* Versão de Homologação/Testes
*/
package br.gov.serpro.rtc.testesintegracao.validacoes.pedagio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.gov.serpro.rtc.api.model.input.pedagio.PedagioInput;
import br.gov.serpro.rtc.domain.service.exception.CampoInvalidoException;
import br.gov.serpro.rtc.domain.service.pedagio.PedagioService;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class Teste_pedagio_validacao_1_NumeroDuplicado {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PedagioService pedagioService;

    private PedagioInput operacao;

    @BeforeEach
    void beforeEach(
            final @Value("classpath:entradas/validacoes/pedagio/Teste_pedagio_validacao_1_NumeroDuplicado.json") Resource resourceFile)
            throws Exception {
        operacao = objectMapper.readValue(resourceFile.getInputStream(), PedagioInput.class);
    }

    @Test
    void teste_service_NumeroDuplicado() {
        assertThatThrownBy(() -> pedagioService.calcularCIBS(operacao))
                .isExactlyInstanceOf(CampoInvalidoException.class);
    }


}
