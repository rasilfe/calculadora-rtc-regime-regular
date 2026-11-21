package br.gov.serpro.rtc.testesintegracao.calculoscorretos.memoriacalculo;


import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.JsonPathExpectationsHelper;
import org.springframework.util.StreamUtils;

import br.gov.serpro.rtc.util.CalculoTestSupport;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class MemoriaCalculoTest {

    private static final String IBSCBS_1 = "$.objetos[0].tribCalc.IBSCBS";
    private static final String IBSCBS_2 = "$.objetos[1].tribCalc.IBSCBS";

    @Autowired
    private CalculoTestSupport calculoTestSupport;

    @Value("classpath:entradas/calculoscorretos/memoria-calculo/entrada.json")
    private Resource entradaJson;

    @Test
    void deveGerarMemoriaCalculoNoGrupoIBSCBSInclusiveSemGIBSCBS() throws Exception {
        final String json = StreamUtils.copyToString(entradaJson.getInputStream(), StandardCharsets.UTF_8);

        final String r = calculoTestSupport.regimeGeral(json);
        new JsonPathExpectationsHelper(IBSCBS_1 + ".gIBSCBS.gCBS").exists(r);
        new JsonPathExpectationsHelper(IBSCBS_1 + ".gIBSCBS.gCBS.memoriaCalculo").doesNotExist(r);
        new JsonPathExpectationsHelper(IBSCBS_1 + ".memoriaCalculo.cbs").assertValue(r, allOf(containsString("R$ 200,00"), containsString("8,40%")));
        new JsonPathExpectationsHelper(IBSCBS_1 + ".memoriaCalculo.ibsUF").exists(r);
        new JsonPathExpectationsHelper(IBSCBS_1 + ".memoriaCalculo.ibsMun").exists(r);


        // item 2 (410001): sem gIBSCBS, memoriaCalculo presente
        new JsonPathExpectationsHelper(IBSCBS_2 + ".gIBSCBS").doesNotExist(r);
        new JsonPathExpectationsHelper(IBSCBS_2 + ".memoriaCalculo.cbs").exists(r);
        new JsonPathExpectationsHelper(IBSCBS_2 + ".memoriaCalculo.ibsUF").exists(r);
        new JsonPathExpectationsHelper(IBSCBS_2 + ".memoriaCalculo.ibsMun").exists(r);;
    }

}
