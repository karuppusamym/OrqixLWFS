package com.orqix.shared.json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON helpers shared by every module. Orqix data (inputs, step outputs, results) travels as JSON text and is parsed
 * only here, with {@link BigDecimal} for every non-integer number (S4 section 8).
 */
@Component
public class Json {

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public JsonMapper mapper() {
        return mapper;
    }

    public JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Malformed JSON", e);
        }
    }

    public JsonNode parse(byte[] json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Malformed JSON", e);
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize value", e);
        }
    }

    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public JsonNode toTree(Object value) {
        return mapper.valueToTree(value);
    }

    public ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** @return nesting depth of objects/arrays (a scalar has depth 0, {} has depth 1) */
    public static int depth(JsonNode node) {
        if (node == null || !(node.isObject() || node.isArray())) {
            return 0;
        }
        int max = 0;
        Iterator<JsonNode> it = node.values().iterator();
        while (it.hasNext()) {
            max = Math.max(max, depth(it.next()));
        }
        return max + 1;
    }

    /** SHA-256 hex of the RFC 8785 (JCS) form of the given JSON text. */
    public static String canonicalSha256(String json) {
        try {
            String canonical = new JsonCanonicalizer(json).getEncodedString();
            return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot canonicalize JSON", e);
        }
    }

    public String canonicalSha256(JsonNode node) {
        return canonicalSha256(write(node));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Converts a JSON scalar to the Java value used by evaluators: BigDecimal, String or Boolean (null otherwise). */
    public static Object scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isString()) {
            return node.stringValue();
        }
        return null;
    }

    public JsonNode fromScalar(Object value) {
        return mapper.valueToTree(value);
    }

    public ObjectNode objectOf(Map<String, ?> values) {
        ObjectNode node = object();
        values.forEach((k, v) -> node.set(k, mapper.valueToTree(v)));
        return node;
    }
}
