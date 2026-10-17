package com.orqix.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Parsed, immutable connection definition version (connection-definition-v0.schema.json). Secret-free and portable;
 * environment details live in the binding.
 */
public record ConnectionDefinition(String key, int version, String name, JsonNode document, String definitionHash,
        Map<String, Operation> operations, String kind) {

    public static final String KIND_HTTP = "http";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_MICROSOFT_GRAPH = "microsoftGraph";

    public boolean isMicrosoftGraph() {
        return KIND_MICROSOFT_GRAPH.equals(kind);
    }

    /** True for connections whose operations are AI tasks over an MCP server (S13). */
    public boolean isAgent() {
        return KIND_AGENT.equals(kind);
    }

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
            int maxTimeoutSeconds, boolean testable, AgentSpec agent) {
    }

    /**
     * The agent part of an operation of an {@code agent} connection: fixed instructions, model profile, the tool
     * allow-list and the call bound. {@code modelProfile} and {@code summary} may be null.
     */
    public record AgentSpec(String instructions, String modelProfile, List<AgentTool> tools, int maxToolCalls,
            String summary) {
    }

    /** @param description administrator-written description offered to the model; null: the server's own, truncated */
    public record AgentTool(String name, String label, String description) {
    }

    public static ConnectionDefinition parse(JsonNode document, String definitionHash) {
        Map<String, Operation> operations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : document.path("operations").properties()) {
            JsonNode op = entry.getValue();
            Set<Integer> statuses = new LinkedHashSet<>();
            op.path("successStatuses").forEach(s -> statuses.add(s.asInt()));
            operations.put(entry.getKey(), new Operation(entry.getKey(), op.path("label").asString(entry.getKey()),
                    op.path("method").asString(null), op.path("path").asString(null),
                    op.has("requestSchema") ? op.get("requestSchema") : null, op.path("responseSchema"),
                    Set.copyOf(statuses), EffectClass.of(op.path("effectClass").asString()),
                    op.has("idempotencyHeader") ? op.get("idempotencyHeader").asString() : null,
                    op.path("maxTimeoutSeconds").asInt(30), op.path("testable").asBoolean(false),
                    op.has("agent") ? agent(op.get("agent")) : null));
        }
        return new ConnectionDefinition(document.path("key").asString(), document.path("version").asInt(),
                document.path("name").asString(), document, definitionHash, java.util.Collections.unmodifiableMap(operations),
                document.path("kind").asString(KIND_HTTP));
    }

    private static AgentSpec agent(JsonNode node) {
        List<AgentTool> tools = new ArrayList<>();
        node.path("tools").forEach(t -> tools.add(new AgentTool(t.path("name").asString(), t.path("label").asString(),
                t.has("description") ? t.get("description").asString() : null)));
        return new AgentSpec(node.path("instructions").asString(),
                node.has("modelProfile") ? node.get("modelProfile").asString() : null, List.copyOf(tools),
                node.path("maxToolCalls").asInt(1), node.has("summary") ? node.get("summary").asString() : null);
    }

    public Operation operation(String name) {
        Operation operation = operations.get(name);
        if (operation == null) {
            throw new IllegalArgumentException("Operation " + name + " not in " + key + " v" + version);
        }
        return operation;
    }
}
