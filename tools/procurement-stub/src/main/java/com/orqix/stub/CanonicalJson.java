package com.orqix.stub;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical form used to decide whether two request bodies are "the same": object keys are sorted and numbers
 * are compared by value (1800.070 equals 1800.07), so formatting differences never turn a replay into a conflict.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    static String hash(JsonNode node, JsonMapper mapper) {
        StringBuilder out = new StringBuilder();
        write(node, mapper, out);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(out.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(JsonNode node, JsonMapper mapper, StringBuilder out) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(mapper.writeValueAsString(entry.getKey())).append(':');
                write(entry.getValue(), mapper, out);
            }
            out.append('}');
        } else if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(items::add);
            out.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(items.get(i), mapper, out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            out.append(value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString());
        } else {
            out.append(mapper.writeValueAsString(node));
        }
    }
}
