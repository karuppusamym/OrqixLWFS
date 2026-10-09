package com.orqix.integration;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Parsed, immutable connection definition version (connection-definition-v0.schema.json). Secret-free and portable;
 * environment details live in the binding.
 */
public record ConnectionDefinition(String key, int version, String name, JsonNode document, String definitionHash,
        Map<String, Operation> operations) {

    public enum EffectClass {
        READ_ONLY("readOnly"), IDEMPOTENT_WITH_KEY("idempotentWithKey"), NON_IDEMPOTENT("nonIdempotent");

        private final String value;

        EffectClass(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static EffectClass of(String value) {
            for (EffectClass c : values()) {
                if (c.value.equals(value)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown effect class " + value);
        }
    }

    public record Operation(String name, String label, String method, String path, JsonNode requestSchema,
            JsonNode responseSchema, Set<Integer> successStatuses, EffectClass effectClass, String idempotencyHeader,
            int maxTimeoutSeconds, boolean testable) {
    }

    public static ConnectionDefinition parse(JsonNode document, String definitionHash) {
        Map<String, Operation> operations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : document.path("operations").properties()) {
            JsonNode op = entry.getValue();
            Set<Integer> statuses = new LinkedHashSet<>();
            op.path("successStatuses").forEach(s -> statuses.add(s.asInt()));
            operations.put(entry.getKey(), new Operation(entry.getKey(), op.path("label").asString(entry.getKey()),
                    op.path("method").asString(), op.path("path").asString(),
                    op.has("requestSchema") ? op.get("requestSchema") : null, op.path("responseSchema"),
                    Set.copyOf(statuses), EffectClass.of(op.path("effectClass").asString()),
                    op.has("idempotencyHeader") ? op.get("idempotencyHeader").asString() : null,
                    op.path("maxTimeoutSeconds").asInt(30), op.path("testable").asBoolean(false)));
        }
        return new ConnectionDefinition(document.path("key").asString(), document.path("version").asInt(),
                document.path("name").asString(), document, definitionHash, java.util.Collections.unmodifiableMap(operations));
    }

    public Operation operation(String name) {
        Operation operation = operations.get(name);
        if (operation == null) {
            throw new IllegalArgumentException("Operation " + name + " not in " + key + " v" + version);
        }
        return operation;
    }
}
