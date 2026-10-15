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

    public static String stepVariable(String nodeId) {
        return STEP_PREFIX + nodeId;
    }

    public static Optional<JsonNode> step(VariableScope scope, Json json, String nodeId) {
        Object value = scope.getVariable(stepVariable(nodeId));
        return value == null ? Optional.empty() : Optional.of(json.parse(value.toString()));
    }

    public static JsonNode input(VariableScope scope, Json json) {
        return json.parse(required(scope, INPUT));
    }

    /**
     * Resolves {@code /input/<k>} or {@code /steps/<node>/<k>} to a JSON value, or a missing node when absent.
     */
    public static JsonNode resolve(VariableScope scope, Json json, String fieldRef) {
        String[] parts = fieldRef.split("/");
        if (fieldRef.startsWith("/input/")) {
            return com.orqix.compiler.InputValues.resolve(input(scope, json), fieldRef);
        }
        if (parts.length == 4 && "steps".equals(parts[1])) {
            return step(scope, json, parts[2]).map(node -> node.path(parts[3]))
                    .orElse(tools.jackson.databind.node.MissingNode.getInstance());
        }
        throw new IllegalArgumentException("Unsupported fieldRef " + fieldRef);
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
