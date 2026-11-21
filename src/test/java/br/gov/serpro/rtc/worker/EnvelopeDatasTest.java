package br.gov.serpro.rtc.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

/**
 * Datas do envelope com o ObjectMapper da aplicação (que tem um desserializador
 * estrito para as datas da operação).
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-testes.yml")
@ActiveProfiles("testes")
class EnvelopeDatasTest {

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-11-17T21:24-03:00",          // toString() de OffsetDateTime com segundos zero
            "2026-11-17T21:24:00-03:00",
            "2026-11-17T21:24:05.123456-03:00",
            "2026-11-18T00:24:00Z" })
    void produzidoEmAceitaIso8601ComOuSemSegundos(String data) throws Exception {
        final String json = "{\"correlationId\":\"" + UUID.randomUUID() + "\",\"produzidoEm\":\"" + data + "\",\"payload\":{}}";

        final NotaEntrada nota = objectMapper.readValue(json, NotaEntrada.class);

        assertThat(nota.produzidoEm()).isEqualTo(OffsetDateTime.parse(data));
    }

    @Test
    void produzidoEmInvalidoContinuaSendoRecusado() {
        final String json = "{\"correlationId\":\"" + UUID.randomUUID() + "\",\"produzidoEm\":\"17/11/2026\",\"payload\":{}}";

        assertThatThrownBy(() -> objectMapper.readValue(json, NotaEntrada.class)).isInstanceOf(DatabindException.class);
    }

    @Test
    void processadoEmSaiSempreComSegundos() throws Exception {
        final var resultado = new ResultadoNota("1", UUID.randomUUID(), "11222333000181", "n", "l", StatusNota.SUCESSO,
                "v", "b", null, OffsetDateTime.of(2026, 11, 17, 21, 24, 0, 0, ZoneOffset.ofHours(-3)), 1, null, null);

        assertThat(objectMapper.writeValueAsString(resultado)).contains("\"processadoEm\":\"2026-11-17T21:24:00-03:00\"");
    }
}
