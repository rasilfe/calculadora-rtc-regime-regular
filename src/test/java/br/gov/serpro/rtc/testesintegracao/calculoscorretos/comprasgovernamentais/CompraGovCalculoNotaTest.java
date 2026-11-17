package br.gov.serpro.rtc.testesintegracao.calculoscorretos.comprasgovernamentais;

import static br.gov.serpro.rtc.util.CalculoTestSupport.assertJsonPath;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StreamUtils;

import br.gov.serpro.rtc.util.CalculoTestSupport;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class CompraGovCalculoNotaTest {

    @Autowired
    private CalculoTestSupport calculoTestSupport;

    @Value("classpath:entradas/calculoscorretos/compras-governamentais/entrada.json")
    private Resource entradaJson;

    @Test
    void deveCalcularTributosCompraGov() throws Exception {
        final String json = StreamUtils.copyToString(entradaJson.getInputStream(), StandardCharsets.UTF_8);

        final String resposta = calculoTestSupport.calcularNotaJson(json);

        assertJsonPath(resposta, "$.status", "SUCESSO");
        // oper.gCompraGov
        assertJsonPath(resposta, "$.resultado.oper.gCompraGov.tpEnteGov", 2);
        assertJsonPath(resposta, "$.resultado.oper.gCompraGov.pRedutor", "0.00");
        assertJsonPath(resposta, "$.resultado.oper.gCompraGov.tpOperGov", 1);

        // objetos[0]
        assertJsonPath(resposta, "$.resultado.objetos[0].nObj", 1);

        // objetos[0].tribCalc.IS
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IS.vBCIS", "200.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IS.pIS", "14.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IS.pISEspec", "21.30");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IS.qTrib", "1");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IS.vIS", "49.30");

        // objetos[0].tribCalc.IBSCBS.gIBSCBS
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.vBC", "249.30");

        // gIBSUF
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSUF.pIBSUF", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSUF.gRed.pRedAliq", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSUF.gRed.pAliqEfet", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSUF.vIBSUF", "0.00");

        // gIBSMun
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSMun.pIBSMun", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSMun.gRed.pRedAliq", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSMun.gRed.pAliqEfet", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gIBSMun.vIBSMun", "0.00");

        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.vIBS", "0.00");

        // gCBS
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gCBS.pCBS", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gCBS.gRed.pRedAliq", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gCBS.gRed.pAliqEfet", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gCBS.vCBS", "0.00");

        // gTribRegular
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribRegular.cClassTribReg", "200032");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribRegular.pAliqEfetRegIBSUF", "1.08");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribRegular.vTribRegIBSUF", "2.69");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribRegular.pAliqEfetRegIBSMun", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribRegular.vTribRegIBSMun", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribRegular.pAliqEfetRegCBS", "3.06");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribRegular.vTribRegCBS", "7.63");

        // gTribCompraGov
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribCompraGov.pAliqIBSUF", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribCompraGov.vTribIBSUF", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribCompraGov.pAliqIBSMun", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribCompraGov.vTribIBSMun", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribCompraGov.pAliqCBS", "0.00");
        assertJsonPath(resposta, "$.resultado.objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribCompraGov.vTribCBS", "0.00");

        // total.tribCalc.ISTot
        assertJsonPath(resposta, "$.resultado.total.tribCalc.ISTot.vIS", "49.30");

        // total.tribCalc.IBSCBSTot
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.vBCIBSCBS", "249.30");

        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.gIBSUF.vDif", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.gIBSUF.vDevTrib", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.gIBSUF.vIBSUF", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.gIBSMun.vDif", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.gIBSMun.vDevTrib", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.gIBSMun.vIBSMun", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.vIBS", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.vCredPres", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gIBS.vCredPresCondSus", "0.00");

        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gCBS.vDif", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gCBS.vDevTrib", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gCBS.vCBS", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gCBS.vCredPres", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gCBS.vCredPresCondSus", "0.00");

        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gMono.vIBSMono", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gMono.vCBSMono", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gMono.vIBSMonoReten", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gMono.vCBSMonoReten", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gMono.vIBSMonoRet", "0.00");
        assertJsonPath(resposta, "$.resultado.total.tribCalc.IBSCBSTot.gMono.vCBSMonoRet", "0.00");
    }
}