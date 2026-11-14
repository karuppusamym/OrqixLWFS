package com.orqix.runtime.engine;

import java.util.Optional;
import java.util.UUID;

import org.flowable.variable.api.delegate.VariableScope;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Orqix process variables (S4 section 8). Data is JSON text parsed only by Orqix code with BigDecimal floats, so the
 * engine's own JSON mapping can never change money precision.
 */
public final class ProcessData {

    public static final String RUN_ID = "orqix_runId";
    public static final String WORKSPACE_ID = "orqix_workspaceId";
    public static final String INITIATOR = "orqix_initiator";
    public static final String INPUT = "orqix_input";
    public static final String STEP_PREFIX = "orqix_step_";

    private ProcessData() {
    }

    public static UUID runId(VariableScope scope) {
        return UUID.fromString(required(scope, RUN_ID));
    }

    public static UUID workspaceId(VariableScope scope) {
        return UUID.fromString(required(scope, WORKSPACE_ID));
    }

    public static UUID initiator(VariableScope scope) {
        return UUID.fromString(required(scope, INITIATOR));
    }

    /**
     * Instance-local variable of a list-driven approval task (S88): the 0-based position of its list item. Must equal
     * the element variable the compiler writes ({@code BpmnGenerator.ITEM_VARIABLE}).
     */
    public static final String ITEM_VARIABLE = "orqix_item";

    /** S138: visit number of a step inside a bounded loop; must equal the compiler's {@code Loops.OCCURRENCE_VARIABLE}. */
    public static final String OCCURRENCE_PREFIX = "orqix_occ_";
    /** S138: traversals of a bounded loop path; must equal the compiler's {@code Loops.TRAVERSAL_VARIABLE}. */
    public static final String TRAVERSAL_PREFIX = "orqix_loop_";
    /**
     * S138: local variable of a for-each item execution, JSON {@code {"i": position, "v": value}}; must equal
     * {@code BpmnGenerator.FOR_EACH_ITEM}.
     */
    public static final String FOR_EACH_ITEM = "orqix_forEachItem";
    /** S138: local variable of a batch of a parallel for-each; must equal {@code BpmnGenerator.FOR_EACH_BATCH}. */
    public static final String FOR_EACH_BATCH = "orqix_forEachBatch";

    /** The item a for-each step execution runs for (S138). */
    public record Item(int index, JsonNode value) {
    }

    /** @return the for-each item of this execution (its own or an enclosing one), or empty outside for-each steps */
    public static Optional<Item> item(VariableScope scope, Json json) {
        Object value = scope.getVariable(FOR_EACH_ITEM);
        if (value == null) {
            return Optional.empty();
        }
        JsonNode node = json.parse(value.toString());
        return Optional.of(new Item(node.path("i").asInt(), node.path("v")));
    }

    /** @return the visit number of the step (1 unless it is inside a bounded loop and visited again) */
    public static int occurrence(VariableScope scope, String nodeId) {
        return OrqixLoop.occurrence(scope, nodeId);
    }

    /** @return the effect journal step key of a call: the step, or {@code <step>#i<position>} for a for-each item */
    public static String effectNode(VariableScope scope, Json json, String nodeId) {
        return item(scope, json).map(i -> nodeId + "#i" + i.index()).orElse(nodeId);
    }

    public static String stepVariable(String nodeId) {
        return STEP_PREFIX + nodeId;
    }

    public static Optional<JsonNode> step(VariableScope scope, Json json, String nodeId) {
        Object value = scope.getVariable(stepVariable(nodeId));
        return value == null ? Optional.empty() : Optional.of(json.parse(value.toString()));
    }

    public static JsonNode input(VariableScope scope, Json json) {
        String text = required(scope, INPUT);
        JsonNode input = json.parse(text);
        // CG-I05 (S151): sensitive answers are stored encrypted in the variable and decrypted only here.
        java.util.function.BiFunction<UUID, JsonNode, JsonNode> opener = inputOpener;
        return opener != null && text.contains("orqix-enc:v1:") ? opener.apply(workspaceId(scope), input) : input;
    }

    private static volatile java.util.function.BiFunction<UUID, JsonNode, JsonNode> inputOpener;

    /** Installed by {@code FieldProtection} at start-up (CG-I05, S151). */
    public static void installInputOpener(java.util.function.BiFunction<UUID, JsonNode, JsonNode> opener) {
        inputOpener = opener;
    }

    /** As {@link #resolve(VariableScope, Json, String)}, with an explicit for-each item (a batch of items, S138). */
    public static JsonNode resolve(VariableScope scope, Json json, String fieldRef, Item item) {
        String[] parts = fieldRef.split("/");
        if (fieldRef.startsWith("/input/") && parts.length >= 4 && "*".equals(parts[3])) {
            return parts.length == 5 ? item.value().path(parts[4]) : item.value();
        }
        return resolve(scope, json, fieldRef);
    }

    /**
     * Resolves {@code /input/<k>} or {@code /steps/<node>/<k>} to a JSON value, or a missing node when absent.
     */
    public static JsonNode resolve(VariableScope scope, Json json, String fieldRef) {
        String[] parts = fieldRef.split("/");
        if (fieldRef.startsWith("/input/") && parts.length >= 4 && "*".equals(parts[3])) {
            // S138: the item of a for-each step, or one column of a row item.
            JsonNode item = item(scope, json).map(Item::value)
                    .orElseThrow(() -> new IllegalStateException(fieldRef + " is used outside a for-each item"));
            return parts.length == 5 ? item.path(parts[4]) : item;
        }
        if (fieldRef.startsWith("/input/")) {
            return com.orqix.compiler.InputValues.resolve(input(scope, json), fieldRef);
        }
        if (parts.length == 4 && "steps".equals(parts[1])) {
            return step(scope, json, parts[2]).map(node -> node.path(parts[3]))
                    .orElse(tools.jackson.databind.node.MissingNode.getInstance());
        }
        throw new IllegalArgumentException("Unsupported fieldRef " + fieldRef);
    }

    /**
     * Evaluates an Orqix expression (S137) over the request and the step results, with the types the compiler pinned
     * in the BPMN ({@code typesBase64}); {@code today()} uses the engine clock.
     *
     * @return the value as JSON (null when the expression gives none)
     * @throws IllegalStateException when the expression exceeds its bounds (the job fails, the request needs attention)
     */
    public static JsonNode expression(VariableScope scope, Json json, String source, String typesBase64) {
        JsonNode types = CalculateTaskDelegate.base64(json, typesBase64);
        try {
            return com.orqix.compiler.Expressions.evaluate(source, types, input(scope, json),
                    id -> step(scope, json, id).orElse(null),
                    org.flowable.engine.impl.util.CommandContextUtil.getProcessEngineConfiguration().getClock()
                            .getCurrentTime().toInstant());
        } catch (com.orqix.compiler.Expressions.Failure e) {
            throw new IllegalStateException("EXPR_EVALUATION: " + e.code() + ": " + e.getMessage());
        }
    }

    /**
     * The value of one mapping of a call: a field reference, or an {@code =} expression (S137) whose types are on the
     * same {@code orqix:input} element.
     */
    public static JsonNode mapping(VariableScope scope, Json json, String source, String typesBase64) {
        return source.startsWith("=") ? expression(scope, json, source.substring(1), typesBase64)
                : resolve(scope, json, source);
    }

    /** Resolves a field to BigDecimal/String/Boolean; absence is a compiler-guaranteed defect (S5 section 5). */
    public static Object requireScalar(VariableScope scope, Json json, String fieldRef) {
        Object value = Json.scalar(resolve(scope, json, fieldRef));
        if (value == null) {
            throw new IllegalStateException("Field " + fieldRef + " is not available; the compiler guarantees it");
        }
        return value;
    }

    private static String required(VariableScope scope, String name) {
        Object value = scope.getVariable(name);
        if (value == null) {
            throw new IllegalStateException("Process variable " + name + " is missing");
        }
        return value.toString();
    }
}
