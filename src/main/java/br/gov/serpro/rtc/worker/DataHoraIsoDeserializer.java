package br.gov.serpro.rtc.worker;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Data e hora ISO-8601 com fuso, com segundos e fração opcionais
 * ({@code 2026-11-17T21:24-03:00}, {@code 2026-11-17T21:24:00.123Z}).
 *
 * <p>Usado nos metadados do envelope ({@code produzidoEm}). O {@code toString()} de
 * {@link OffsetDateTime} omite os segundos quando são zero; com o formato estrito das
 * datas da operação, uma em cada 60 mensagens de um produtor Java seria recusada.
 */
public class DataHoraIsoDeserializer extends JsonDeserializer<OffsetDateTime> {

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext contexto) throws IOException {
        final String texto = parser.getValueAsString();
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(texto.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return (OffsetDateTime) contexto.handleWeirdStringValue(OffsetDateTime.class, texto,
                    "data e hora ISO-8601 com fuso esperada (ex.: 2026-11-17T21:24:00-03:00)");
        }
    }
}
