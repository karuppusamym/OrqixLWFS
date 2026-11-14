package com.orqix.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * CG-I05 (docs/build/S151): classification of request questions ({@code classification} on a request form field:
 * public, internal, personal or sensitive). personal and sensitive answers are masked in every response, export and
 * evidence pack; only the audited reveal returns them. Pure functions over the pinned request schema.
 */
public final class FieldClassification {

    /** What a masked answer shows. The same text is used by the database function {@code orqix_mask_input}. */
    public static final String MASK = "••••••";

    private FieldClassification() {
    }

    /** Protected request fields of a request schema, in schema order, with their classification. */
    public static Map<String, String> protectedFields(JsonNode inputSchema) {
        Map<String, String> found = new LinkedHashMap<>();
        if (inputSchema == null) {
            return found;
        }
        for (Map.Entry<String, JsonNode> e : inputSchema.path("properties").properties()) {
            String classification = e.getValue().path("classification").asString(null);
            if ("personal".equals(classification) || "sensitive".equals(classification)) {
                found.put(e.getKey(), classification);
            }
        }
        return found;
    }

    /** Fields whose answers are encrypted at rest. */
    public static List<String> sensitiveFields(JsonNode inputSchema) {
        List<String> found = new ArrayList<>();
        protectedFields(inputSchema).forEach((k, c) -> {
            if ("sensitive".equals(c)) {
                found.add(k);
            }
        });
        return found;
    }

    public static boolean isProtected(JsonNode inputSchema, String field) {
        String classification = inputSchema == null ? null
                : inputSchema.path("properties").path(field).path("classification").asString(null);
        return "personal".equals(classification) || "sensitive".equals(classification);
    }

    /** A copy of {@code input} with every present protected answer replaced by {@link #MASK}; null stays null. */
    public static JsonNode mask(JsonNode input, JsonNode inputSchema) {
        if (input == null || !input.isObject()) {
            return input;
        }
        Map<String, String> fields = protectedFields(inputSchema);
        if (fields.isEmpty()) {
            return input;
        }
        ObjectNode copy = ((ObjectNode) input).deepCopy();
        for (String key : fields.keySet()) {
            if (copy.has(key) && !copy.path(key).isNull()) {
                copy.set(key, StringNode.valueOf(MASK));
            }
        }
        return copy;
    }

    /** True when the value is the mask text (a client sent back what it was shown). */
    public static boolean isMask(JsonNode value) {
        return value != null && value.isString() && MASK.equals(value.asString());
    }
}
