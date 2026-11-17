package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * The JSON mapper of the Beckn path. Decimals are read as exact {@code BigDecimal}s so that the
 * FHIR resource travels as the emitter encoded it ({@code 1.50} stays {@code 1.50}).
 */
public final class BecknJson {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private BecknJson() {
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** The text of a field, or null when it is missing, not text, or blank. */
    public static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || !v.isValueNode() || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
