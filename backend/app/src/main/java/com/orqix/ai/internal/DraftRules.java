package com.orqix.ai.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic part of AI draft generation (S10 sections 2 and 3): parses the model's envelope
 * {@code {definition, assumptions[], missingConfiguration[], notAWorkflow}}, post-processes the definition (self-approval
 * forced to deny, dependencies the workspace does not have removed, missing boilerplate filled in, layout dropped) and
 * classifies the compiler report. Binding-layer blockers become missing configuration for the designer; every other
 * blocker is repaired by the model. Pure (no Spring, no I/O): the same rules run in the service and in the
 * {@code tools/ai-eval} harness.
 */
public final class DraftRules {

    /** Blockers that a person resolves by configuring the workspace; never sent back to the model. */
    public static final Set<String> BINDING_CODES = Set.of("WF_GROUP_UNBOUND", "WF_CONNECTION_UNDECLARED",
            "WF_CONNECTION_UNKNOWN", "WF_CONNECTION_UNBOUND", "WF_OPERATION_UNKNOWN");

    static final int MAX_NOTES = 15;
    static final int MAX_NOTE_LENGTH = 300;
    private static final String KEY_PATTERN = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";

    /** A workspace task group as shown to the model (key and name only, never members). */
    public record GroupFact(String key, String name) {
    }

    /** One registered connection definition version (operations with their schemas, never bindings or URLs). */
    public record ConnectionFact(String key, int version, String name, JsonNode document) {
    }

    /**
     * Workspace facts for one generation.
     *
     * @param connections every registered version of every connection
     * @param compileContext the same context the catalog validates drafts with
     */
    public record Workspace(List<GroupFact> groups, List<ConnectionFact> connections, CompileContext compileContext) {

        public Workspace {
            groups = List.copyOf(groups);
            connections = List.copyOf(connections);
        }
    }

    /** OpenAPI {@code AiDraftResult.missingConfiguration[]} item. */
    public record Missing(String message, String nodeId, String code) {
    }

    public enum Status {
        /** No blockers other than binding-layer items: the draft can be created. */
        VALID,
        /** Unparseable output or blockers the model must fix. */
        REPAIR,
        /** The model declined: the description is not a workflow. */
        NOT_A_WORKFLOW
    }

    /**
     * Result of evaluating one model reply.
     *
     * @param definition the post-processed definition (null when there is none)
     * @param repairable blockers to send back to the model (synthetic WF_SCHEMA_INVALID for unparseable output)
     * @param report     the compiler report, null when the reply had no definition
     */
    public record Evaluation(Status status, ObjectNode definition, List<String> assumptions, List<Missing> missing,
            List<ValidationIssue> repairable, ValidationReport report, String notAWorkflow) {
    }

    private final Json json;
    private final WorkflowCompiler compiler;

    public DraftRules(Json json, WorkflowCompiler compiler) {
        this.json = json;
        this.compiler = compiler;
    }

    /**
     * Compact JSON describing the workspace for the model: group keys and names; for each connection its latest
     * version with operation names, labels and request/response schemas. Never members, bindings, URLs or secrets.
     */
    public String workspaceContext(Workspace workspace) {
        ObjectNode root = json.object();
        ArrayNode groups = root.putArray("groups");
        for (GroupFact group : workspace.groups()) {
            groups.addObject().put("key", group.key()).put("name", group.name());
        }
        Map<String, ConnectionFact> latest = new TreeMap<>();
        for (ConnectionFact connection : workspace.connections()) {
            ConnectionFact current = latest.get(connection.key());
            if (current == null || current.version() < connection.version()) {
                latest.put(connection.key(), connection);
            }
        }
        ArrayNode connections = root.putArray("connections");
        for (ConnectionFact connection : latest.values()) {
            ObjectNode node = connections.addObject();
            node.put("key", connection.key());
            node.put("definitionVersion", connection.version());
            node.put("name", connection.name());
            ArrayNode operations = node.putArray("operations");
            for (Map.Entry<String, JsonNode> op : connection.document().path("operations").properties()) {
                ObjectNode operation = operations.addObject();
                operation.put("name", op.getKey());
                operation.put("label", op.getValue().path("label").asString(op.getKey()));
                if (op.getValue().has("requestSchema")) {
                    operation.set("requestSchema", op.getValue().get("requestSchema"));
                }
                if (op.getValue().has("responseSchema")) {
                    operation.set("responseSchema", op.getValue().get("responseSchema"));
                }
                operation.put("maxTimeoutSeconds", op.getValue().path("maxTimeoutSeconds").asInt(30));
            }
        }
        return json.write(root);
    }

    /**
     * Parses, post-processes and validates one model reply.
     *
     * @param content   the raw assistant message
     * @param name      workflow name to force (the request's name), or null to keep the model's
     */
    public Evaluation evaluate(String content, String name, Workspace workspace) {
        ObjectNode envelope;
        try {
            envelope = parseEnvelope(content);
        } catch (IllegalArgumentException e) {
            return repair(e.getMessage());
        }
        List<String> assumptions = notes(envelope.path("assumptions"));
        String notAWorkflow = text(envelope.path("notAWorkflow"));
        JsonNode raw = envelope.path("definition");
        if (!raw.isObject()) {
            if (notAWorkflow != null) {
                return new Evaluation(Status.NOT_A_WORKFLOW, null, assumptions, List.of(), List.of(), null,
                        clip(notAWorkflow));
            }
            return repair("The reply has no \"definition\" object.");
        }
        ObjectNode definition = postProcess(((ObjectNode) raw).deepCopy(), name, workspace);
        ValidationReport report = compiler.validate(json.write(definition), workspace.compileContext());
        List<ValidationIssue> repairable = new ArrayList<>();
        List<ValidationIssue> binding = new ArrayList<>();
        for (ValidationIssue issue : report.issues()) {
            if (issue.severity() != Severity.BLOCKER) {
                continue;
            }
            (BINDING_CODES.contains(issue.code()) ? binding : repairable).add(issue);
        }
        List<Missing> missing = missing(binding, envelope.path("missingConfiguration"), definition);
        return new Evaluation(repairable.isEmpty() ? Status.VALID : Status.REPAIR, definition, assumptions, missing,
                List.copyOf(repairable), report, null);
    }

    /** Missing-configuration items for binding-layer blockers of a stored draft (idempotent replays). */
    public List<Missing> bindingItems(List<ValidationIssue> issues, ObjectNode definition) {
        List<ValidationIssue> binding = issues.stream()
                .filter(i -> i.severity() == Severity.BLOCKER && BINDING_CODES.contains(i.code())).toList();
        return missing(binding, json.object().putArray("none"), definition);
    }

    /** The user turn asking the model to fix its previous reply. */
    public static String repairMessage(List<ValidationIssue> issues) {
        StringBuilder message = new StringBuilder(
                "Your reply cannot be used yet. Fix every problem below and reply again with the complete JSON object "
                        + "(definition, assumptions, missingConfiguration, notAWorkflow). Keep everything that was "
                        + "correct.\n\nProblems:\n");
        int count = 0;
        for (ValidationIssue issue : issues) {
            if (++count > 40) {
                message.append("- ... and ").append(issues.size() - 40).append(" more of the same kinds\n");
                break;
            }
            message.append("- ").append(issue.code());
            if (issue.path() != null && !issue.path().isEmpty()) {
                message.append(" at ").append(issue.path());
            }
            if (issue.nodeId() != null) {
                message.append(" (step ").append(issue.nodeId()).append(')');
            }
            message.append(": ").append(issue.message()).append('\n');
        }
        return message.toString();
    }

    /** Workflow key derived from a name: lowercase ASCII words joined by hyphens, at most 64 characters. */
    public static String keyFromName(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String key = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (key.isEmpty() || !Character.isLetter(key.charAt(0))) {
            key = key.isEmpty() ? "ai-draft" : "wf-" + key;
        }
        if (key.length() > 56) {
            key = key.substring(0, 56);
        }
        key = key.replaceAll("-+$", "");
        return key.matches(KEY_PATTERN) ? key : "ai-draft";
    }

    /** The name to use: the requested one, else the model's (when usable), else "AI draft". */
    public static String nameFor(String requested, JsonNode definition) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String proposed = definition == null ? null : definition.path("name").asString(null);
        if (proposed != null) {
            proposed = proposed.replaceAll("\\s+", " ").trim();
            if (!proposed.isEmpty() && proposed.length() <= 200 && !proposed.contains("${")
                    && !proposed.contains("#{")) {
                return proposed;
            }
        }
        return "AI draft";
    }

    // ---------------------------------------------------------------- parsing

    /** The envelope object; tolerates Markdown fences and text around the object. */
    ObjectNode parseEnvelope(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("The reply was empty.");
        }
        String text = content.strip();
        if (!text.startsWith("{")) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("The reply is not a JSON object.");
            }
            text = text.substring(start, end + 1);
        }
        JsonNode parsed;
        try {
            parsed = json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The reply is not valid JSON (check quotes, commas, brackets and "
                    + "duplicate property names).");
        }
        if (!parsed.isObject()) {
            throw new IllegalArgumentException("The reply is not a JSON object.");
        }
        ObjectNode envelope = (ObjectNode) parsed;
        if (!envelope.has("definition") && envelope.has("schemaVersion") && envelope.has("nodes")) {
            // The model returned the bare document: accept it as the definition.
            ObjectNode wrapped = json.object();
            wrapped.set("definition", envelope);
            return wrapped;
        }
        return envelope;
    }

    private Evaluation repair(String reason) {
        ValidationIssue issue = new ValidationIssue("WF_SCHEMA_INVALID", Severity.BLOCKER, reason, "", null, null);
        return new Evaluation(Status.REPAIR, null, List.of(), List.of(), List.of(issue), null, null);
    }

    // ---------------------------------------------------------------- post-processing

    private ObjectNode postProcess(ObjectNode definition, String requestedName, Workspace workspace) {
        String name = nameFor(requestedName, definition);
        definition.remove("uiLayout");
        definition.put("schemaVersion", "0.1");
        definition.put("workflowKey", keyFromName(name));
        definition.put("name", name);
        // S10 section 2: AI drafts never allow self-approval.
        definition.putObject("security").put("selfApproval", "deny");
        if (!definition.path("metadata").isObject()) {
            definition.putObject("metadata");
        }
        ObjectNode metadata = (ObjectNode) definition.get("metadata");
        if (!metadata.path("description").isString()) {
            metadata.put("description", name);
        }
        if (!definition.has("triggers")) {
            definition.putArray("triggers").add("manual");
        }
        if (!definition.has("decisions")) {
            definition.putArray("decisions");
        }
        if (!definition.path("execution").isObject()) {
            definition.putObject("execution");
        }
        // Execution limits are boilerplate: out-of-range or missing values get the defaults.
        ObjectNode execution = (ObjectNode) definition.get("execution");
        inRange(execution, "maxConcurrentRuns", 1, 1000, 100);
        inRange(execution, "maxAttempts", 1, 5, 3);
        inRange(execution, "retryDelaySeconds", 5, 3600, 30);
        for (String form : List.of("inputSchema", "outputSchema")) {
            for (JsonNode field : definition.path(form).path("properties")) {
                if (field instanceof ObjectNode object) {
                    // Models write -1 or 0 for "no limit"; the limits are optional, so drop invalid ones.
                    JsonNode min = object.path("minLength");
                    if (!min.isMissingNode() && !(min.isIntegralNumber() && min.asLong() >= 0)) {
                        object.remove("minLength");
                    }
                    JsonNode max = object.path("maxLength");
                    if (!max.isMissingNode() && !(max.isIntegralNumber() && max.asLong() >= 1)) {
                        object.remove("maxLength");
                    } else if (max.isIntegralNumber() && max.asLong() > 4000) {
                        object.put("maxLength", 4000);
                    }
                }
            }
        }
        for (JsonNode node : definition.path("nodes")) {
            JsonNode due = node.path("config").path("dueAfterHours");
            if (node.path("config").isObject() && !due.isMissingNode()
                    && !(due.isIntegralNumber() && due.asInt() >= 1 && due.asInt() <= 720)) {
                // Display-only due date; models write -1 or 0 for "no deadline".
                ((ObjectNode) node.get("config")).remove("dueAfterHours");
            }
        }
        if (!definition.has("outputSchema")) {
            ObjectNode output = definition.putObject("outputSchema");
            output.put("type", "object");
            output.put("additionalProperties", false);
            output.putObject("properties");
        }
        if (!definition.has("outputMapping")) {
            definition.putObject("outputMapping");
        }
        dependencies(definition, workspace);
        return definition;
    }

    private static void inRange(ObjectNode object, String field, int min, int max, int fallback) {
        JsonNode value = object.path(field);
        if (!(value.isIntegralNumber() && value.asLong() >= min && value.asLong() <= max)) {
            object.put(field, fallback);
        }
    }

    /** Keeps only connection dependencies the workspace has; an unknown version of a known key is pinned to latest. */
    private void dependencies(ObjectNode definition, Workspace workspace) {
        Map<String, Integer> latest = new LinkedHashMap<>();
        Set<String> versions = new HashSet<>();
        for (ConnectionFact connection : workspace.connections()) {
            latest.merge(connection.key(), connection.version(), Math::max);
            versions.add(connection.key() + "|" + connection.version());
        }
        JsonNode existing = definition.path("dependencies").path("connections");
        JsonNode assets = definition.path("dependencies").path("assets");
        ObjectNode dependencies = definition.putObject("dependencies");
        ArrayNode connections = dependencies.putArray("connections");
        if (assets.isArray()) {
            // Shared asset references (S61) are kept as they are; the server checks them at validation and publish.
            dependencies.set("assets", assets.deepCopy());
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode dependency : existing) {
            String key = dependency.path("key").asString(null);
            if (key == null || !latest.containsKey(key) || !seen.add(key)) {
                continue;
            }
            JsonNode version = dependency.path("definitionVersion");
            int pinned = version.isIntegralNumber() && versions.contains(key + "|" + version.asInt())
                    ? version.asInt() : latest.get(key);
            connections.addObject().put("key", key).put("definitionVersion", pinned);
        }
    }

    // ---------------------------------------------------------------- notes

    private List<Missing> missing(List<ValidationIssue> binding, JsonNode proposed, ObjectNode definition) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        for (JsonNode node : definition.path("nodes")) {
            String id = node.path("id").asString(null);
            if (id != null) {
                nodes.putIfAbsent(id, node);
            }
        }
        List<Missing> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> subjects = new LinkedHashMap<>();
        for (ValidationIssue issue : binding) {
            String nodeId = issue.nodeId();
            JsonNode config = nodeId == null ? null : nodes.getOrDefault(nodeId, json.object()).path("config");
            String group = config == null ? null : config.path("candidateGroupRef").asString(null);
            String connection = config == null ? null : config.path("connectionRef").asString(null);
            if (connection == null && issue.path() != null && issue.path().startsWith("/dependencies/connections/")) {
                // Dependency-level findings: name the connection and point at the first step that calls it.
                connection = definition.at(issue.path().replaceAll("/(key|definitionVersion)$", "") + "/key")
                        .asString(null);
                for (JsonNode node : nodes.values()) {
                    if (nodeId == null && connection != null
                            && connection.equals(node.path("config").path("connectionRef").asString(null))) {
                        nodeId = node.path("id").asString();
                    }
                }
            }
            String message = switch (issue.code()) {
                case "WF_GROUP_UNBOUND" -> group == null ? issue.message()
                        : "Create the group \"" + group + "\" in Settings > Access, or choose an existing group for "
                                + "this approval step.";
                case "WF_CONNECTION_UNDECLARED", "WF_CONNECTION_UNKNOWN" -> connection == null ? issue.message()
                        : "The system \"" + connection + "\" is not connected in this workspace. Register the "
                                + "connection or remove this step.";
                case "WF_CONNECTION_UNBOUND" -> connection == null ? issue.message()
                        : "Bind the \"" + connection + "\" connection in Settings > Connections.";
                case "WF_OPERATION_UNKNOWN" -> connection == null ? issue.message()
                        : "Choose an operation that the \"" + connection + "\" connection offers.";
                default -> issue.message();
            };
            if (seen.add(nodeId + "|" + message)) {
                result.add(new Missing(clip(message), nodeId, issue.code()));
                if (nodeId != null) {
                    subjects.put(nodeId, group != null ? group : connection);
                }
            }
        }
        int count = 0;
        for (JsonNode item : proposed) {
            String message = item.isString() ? text(item) : text(item.path("message"));
            if (message == null || count >= MAX_NOTES) {
                continue;
            }
            String nodeId = item.path("nodeId").asString(null);
            if (nodeId != null && !nodes.containsKey(nodeId)) {
                nodeId = null;
            }
            String subject = nodeId == null ? null : subjects.get(nodeId);
            if (subject != null && message.contains(subject)) {
                continue; // already reported from the compiler finding for this step
            }
            if (seen.add(nodeId + "|" + message)) {
                result.add(new Missing(clip(message), nodeId, null));
                count++;
            }
        }
        return List.copyOf(result);
    }

    private static List<String> notes(JsonNode items) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : items) {
            String note = text(item);
            if (note != null && result.size() < MAX_NOTES && !result.contains(clip(note))) {
                result.add(clip(note));
            }
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.stringValue().replaceAll("[\\p{Cntrl}&&[^\n]]", " ").replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? null : value;
    }

    static String clip(String value) {
        return value.length() <= MAX_NOTE_LENGTH ? value : value.substring(0, MAX_NOTE_LENGTH - 1).trim() + "…";
    }
}
