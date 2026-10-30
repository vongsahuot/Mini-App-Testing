package com.kosign.mock.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON helpers: ordered maps that allow nulls, and null-safe field reads. */
public final class Json {
    private Json() {}

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String str(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static String required(JsonNode n, String field) {
        String v = str(n, field);
        if (v == null || v.isBlank()) throw ApiException.badRequest("INVALID_REQUEST", field + " is required");
        return v;
    }

    public static BigDecimal money(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return new BigDecimal(v.asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_REQUEST", field + " must be a number");
        }
    }
}
