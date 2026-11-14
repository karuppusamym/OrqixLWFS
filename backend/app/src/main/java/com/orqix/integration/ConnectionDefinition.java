package com.orqix.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Parsed, immutable connection definition version (connection-definition-v0.schema.json or, for schemaVersion 1.0,
 * connection-definition-v1.schema.json). Secret-free and portable;
 * environment details live in the binding.
 */
public record ConnectionDefinition(String key, int version, String name, JsonNode document, String definitionHash,
        Map<String, Operation> operations, String kind) {

    public static final String KIND_HTTP = "http";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_MICROSOFT_GRAPH = "microsoftGraph";
    /** A registered MCP tool server (CG-M01, S135): a tool catalog, no operations. */
    public static final String KIND_MCP_SERVER = "mcpServer";

    /** Data classes a tool may receive when it declares none. */
    public static final List<String> DEFAULT_CLASSES = List.of("public", "internal");

    public boolean isMcpServer() {
        return KIND_MCP_SERVER.equals(kind);
    }

    /** @return the tool catalog of an {@code mcpServer} connection, or null for other kinds */
    public McpServerSpec mcpServer() {
        if (!isMcpServer()) {
            return null;
        }
        JsonNode node = document.path("mcpServer");
        List<ServerTool> tools = new ArrayList<>();
        node.path("tools").forEach(t -> tools.add(new ServerTool(t.path("name").asString(), t.path("label").asString(),
                t.path("description").asString(), strings(t.path("dataClasses"), DEFAULT_CLASSES))));
        List<ServerResource> resources = new ArrayList<>();
        node.path("resources").forEach(r -> resources.add(new ServerResource(r.path("name").asString(),
                r.path("label").asString(), r.path("uri").asString(), r.path("maxChars").asInt(2000))));
        return new McpServerSpec(List.copyOf(tools), List.copyOf(resources));
    }

    public record McpServerSpec(List<ServerTool> tools, List<ServerResource> resources) {

        public ServerTool tool(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        }

        public ServerResource resource(String name) {
            return resources.stream().filter(r -> r.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** @param description administrator-written; the only description the model sees */
    public record ServerTool(String name, String label, String description, List<String> dataClasses) {
    }

    public record ServerResource(String name, String label, String uri, int maxChars) {
    }

    public boolean isMicrosoftGraph() {
        return KIND_MICROSOFT_GRAPH.equals(kind);
    }

    /** True for connections whose operations are AI tasks over an MCP server (S13). */
    public boolean isAgent() {
        return KIND_AGENT.equals(kind);
    }

    public enum EffectClass {
        READ_ONLY("readOnly"), IDEMPOTENT_WITH_KEY("idempotentWithKey"),
        /** v1 (CG-C02): an uncertain outcome is settled by the operation's read-only lookup. */
        IDEMPOTENT_BY_LOOKUP("idempotentByLookup"),
        /** v1: repeating the same values gives the same result; retried like idempotentWithKey without a header. */
        IDEMPOTENT_NATURAL("idempotentNatural"),
        NON_IDEMPOTENT("nonIdempotent");

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

    /**
     * @param source the operation as declared; v1 request parts, results, errors, pagination and reconcile are read
     *               from it (docs/build/S130-connection-definition-v1.md)
     * @param v1     true for a {@code schemaVersion "1.0"} definition
     */
    public record Operation(String name, String label, String method, String path, JsonNode requestSchema,
            JsonNode responseSchema, Set<Integer> successStatuses, EffectClass effectClass, String idempotencyHeader,
            int maxTimeoutSeconds, boolean testable, AgentSpec agent, JsonNode source, boolean v1) {

        public Operation(String name, String label, String method, String path, JsonNode requestSchema,
                JsonNode responseSchema, Set<Integer> successStatuses, EffectClass effectClass, String idempotencyHeader,
                int maxTimeoutSeconds, boolean testable, AgentSpec agent) {
            this(name, label, method, path, requestSchema, responseSchema, successStatuses, effectClass,
                    idempotencyHeader, maxTimeoutSeconds, testable, agent, null, false);
        }

        /** @return the paginated-read settings, or null */
        public JsonNode pagination() {
            return v1 && source.has("pagination") ? source.get("pagination") : null;
        }

        /** @return the lookup reconciliation settings (idempotentByLookup), or null */
        public JsonNode reconcile() {
            return v1 && source.has("reconcile") ? source.get("reconcile") : null;
        }
    }

    /**
     * The agent part of an operation of an {@code agent} connection: fixed instructions, model profile, the tool
     * allow-list and the call bound. {@code modelProfile} and {@code summary} may be null. {@code loop} is null for a
     * synchronous agent (S13); with it the agent is a durable loop (CG-M03, S135).
     */
    public record AgentSpec(String instructions, String modelProfile, List<AgentTool> tools, int maxToolCalls,
            String summary, List<WriteTool> writeTools, LoopLimits loop, List<String> resources,
            String elicitationGroup, Map<String, String> inputDataClasses) {

        public AgentSpec(String instructions, String modelProfile, List<AgentTool> tools, int maxToolCalls,
                String summary) {
            this(instructions, modelProfile, tools, maxToolCalls, summary, List.of());
        }

        public AgentSpec(String instructions, String modelProfile, List<AgentTool> tools, int maxToolCalls,
                String summary, List<WriteTool> writeTools) {
            this(instructions, modelProfile, tools, maxToolCalls, summary, writeTools, null, List.of(), null, Map.of());
        }

        public WriteTool writeTool(String name) {
            for (WriteTool tool : writeTools) {
                if (tool.name().equals(name)) {
                    return tool;
                }
            }
            return null;
        }

        public AgentTool tool(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        }

        public boolean durable() {
            return loop != null;
        }

        /** Tool calls allowed in one execution: the loop's bound for a durable agent, else maxToolCalls. */
        public int toolCallLimit() {
            return loop != null ? loop.maxToolCalls() : maxToolCalls;
        }

        /** Class of a request field (default internal). */
        public String dataClass(String field) {
            return inputDataClasses.getOrDefault(field, "internal");
        }

        /** True when at least one tool runs on the MCP server of the agent's own binding. */
        public boolean usesOwnBinding() {
            return tools.stream().anyMatch(t -> t.source() == null && t.serverKey() == null);
        }
    }

    /** CG-M03 limits of a durable loop. */
    public record LoopLimits(int maxTurns, int maxToolCalls, java.time.Duration maxDuration,
            java.math.BigDecimal budgetUsd) {
    }

    /** CG-M02: where an Orqix tool comes from. */
    public sealed interface ToolSource permits OperationSource, DecisionSource, WorkflowSource, AskSource {
    }

    public record OperationSource(String connection, String operation) implements ToolSource {
    }

    public record DecisionSource(String key, int version) implements ToolSource {
    }

    public record WorkflowSource(String key, int release) implements ToolSource {
    }

    /** @param answerSchema flat object of scalars the person fills in */
    public record AskSource(String group, JsonNode answerSchema) implements ToolSource {
    }

    /**
     * An approval-gated write tool of an agent operation (IN04, S75). Targets are fixed here ({@code to} for email; the
     * adapter's channel for Teams); the model only writes the message text. Declaring it authorizes nothing.
     *
     * @param kind     {@code email} or {@code teams}
     * @param to       fixed recipients for email, empty for Teams
     * @param maxCalls uses allowed in one execution (1-3)
     */
    public record WriteTool(String name, String label, String description, String adapter, String kind, List<String> to,
            int maxCalls) {
    }

    /**
     * @param description   administrator-written description offered to the model; null: the server's own, truncated
     *                      (only for tools of the agent's own binding; registered servers always carry one)
     * @param source        null for an MCP tool; otherwise an Orqix tool (CG-M02)
     * @param approvalGroup non-null: every call needs an approval by a member of this group first (CG-M03)
     * @param write         an operation tool whose operation is nonIdempotent, used only under an S75 approval
     * @param dataClasses   classes it may receive; null: from the server definition or the default
     */
    public record AgentTool(String name, String label, String description, ToolSource source, String approvalGroup,
            boolean write, List<String> dataClasses) {

        public AgentTool(String name, String label, String description) {
            this(name, label, description, null, null, false, null);
        }

        /** Registered server key of {@code server.tool}; null for a tool of the agent's own binding or a source. */
        public String serverKey() {
            int dot = name.indexOf('.');
            return source == null && dot > 0 ? name.substring(0, dot) : null;
        }

        /** Tool name on the MCP server. */
        public String serverToolName() {
            String server = serverKey();
            return server == null ? name : name.substring(server.length() + 1);
        }

        /** True for tools that make the loop wait (child workflow, ask a person). */
        public boolean waits() {
            return source instanceof WorkflowSource || source instanceof AskSource;
        }
    }

    public static ConnectionDefinition parse(JsonNode document, String definitionHash) {
        Map<String, Operation> operations = new LinkedHashMap<>();
        boolean v1 = "1.0".equals(document.path("schemaVersion").asString(""));
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
                    op.has("agent") ? agent(op.get("agent")) : null, op, v1));
        }
        return new ConnectionDefinition(document.path("key").asString(), document.path("version").asInt(),
                document.path("name").asString(), document, definitionHash, java.util.Collections.unmodifiableMap(operations),
                document.path("kind").asString(KIND_HTTP));
    }

    private static AgentSpec agent(JsonNode node) {
        List<AgentTool> tools = new ArrayList<>();
        node.path("tools").forEach(t -> tools.add(new AgentTool(t.path("name").asString(), t.path("label").asString(),
                t.has("description") ? t.get("description").asString() : null, source(t.path("source")),
                t.path("requiresApproval").has("group") ? t.path("requiresApproval").path("group").asString() : null,
                t.path("write").asBoolean(false), t.has("dataClasses") ? strings(t.get("dataClasses"), null) : null)));
        List<WriteTool> writeTools = new ArrayList<>();
        node.path("writeTools").forEach(t -> {
            List<String> to = new ArrayList<>();
            t.path("to").forEach(a -> to.add(a.asString()));
            writeTools.add(new WriteTool(t.path("name").asString(), t.path("label").asString(),
                    t.path("description").asString(), t.path("adapter").asString(), t.path("kind").asString(),
                    List.copyOf(to), t.path("maxCalls").asInt(1)));
        });
        LoopLimits loop = null;
        JsonNode l = node.path("loop");
        if (l.isObject()) {
            java.time.Duration duration;
            try {
                duration = java.time.Duration.parse(l.path("maxDuration").asString("PT1H"));
            } catch (java.time.format.DateTimeParseException e) {
                duration = java.time.Duration.ofHours(1);
            }
            loop = new LoopLimits(l.path("maxTurns").asInt(10), l.path("maxToolCalls").asInt(10), duration,
                    l.path("budgetUsd").decimalValue());
        }
        Map<String, String> classes = new LinkedHashMap<>();
        node.path("inputDataClasses").properties().forEach(e -> classes.put(e.getKey(), e.getValue().asString()));
        return new AgentSpec(node.path("instructions").asString(),
                node.has("modelProfile") ? node.get("modelProfile").asString() : null, List.copyOf(tools),
                node.path("maxToolCalls").asInt(1), node.has("summary") ? node.get("summary").asString() : null,
                List.copyOf(writeTools), loop, strings(node.path("resources"), List.of()),
                node.path("elicitation").has("group") ? node.path("elicitation").path("group").asString() : null,
                java.util.Collections.unmodifiableMap(classes));
    }

    private static ToolSource source(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        if (node.has("operation")) {
            return new OperationSource(node.path("operation").path("connection").asString(),
                    node.path("operation").path("operation").asString());
        }
        if (node.has("decision")) {
            return new DecisionSource(node.path("decision").path("key").asString(),
                    node.path("decision").path("version").asInt(1));
        }
        if (node.has("workflow")) {
            return new WorkflowSource(node.path("workflow").path("key").asString(),
                    node.path("workflow").path("release").asInt(1));
        }
        if (node.has("askPerson")) {
            return new AskSource(node.path("askPerson").path("group").asString(),
                    node.path("askPerson").path("answerSchema"));
        }
        return null;
    }

    private static List<String> strings(JsonNode array, List<String> fallback) {
        if (!array.isArray() || array.isEmpty()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return List.copyOf(values);
    }

    public Operation operation(String name) {
        Operation operation = operations.get(name);
        if (operation == null) {
            throw new IllegalArgumentException("Operation " + name + " not in " + key + " v" + version);
        }
        return operation;
    }
}
