package com.orqix.runtime.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Decides whether a request waiting at one step can continue on another release of the same workflow (docs/build/S38).
 * The rules are deliberately conservative: the part of the graph the request has already travelled must be identical in
 * both releases, and the step it waits at must be unchanged. Only what lies ahead may differ.
 */
public final class ReleaseCompatibility {

    /** One reason a request cannot move to the target release. {@code nodeId} is null for definition-wide findings. */
    public record Finding(String code, String message, String nodeId) {
    }

    /** Step kinds with a stable engine representation that can safely be re-pointed at another definition. */
    private static final Set<String> MIGRATABLE_STEPS = Set.of("approval", "wait", "waitForMessage");

    private ReleaseCompatibility() {
    }

    public static List<Finding> check(JsonNode source, JsonNode target, List<String> activeNodeIds) {
        List<Finding> findings = new ArrayList<>();
        if (activeNodeIds.size() != 1) {
            findings.add(new Finding("STEP_COUNT", activeNodeIds.isEmpty()
                    ? "The request is not waiting at a step, so there is nothing to move."
                    : "The request waits at more than one step at once; only a single waiting step can be moved.", null));
            return findings;
        }
        String active = activeNodeIds.getFirst();
        JsonNode sourceNode = node(source, active);
        JsonNode targetNode = node(target, active);
        if (sourceNode == null) {
            findings.add(new Finding("STEP_UNKNOWN", "The current step is not part of the current release.", active));
            return findings;
        }
        String kind = sourceNode.path("type").asString("");
        if (!MIGRATABLE_STEPS.contains(kind)) {
            findings.add(new Finding("STEP_KIND", "A request waiting at a '" + kind
                    + "' step cannot be moved; only approvals and waits can.", active));
        }
        if (!sourceNode.path("config").path("multiInstance").isMissingNode()
                && !sourceNode.path("config").path("multiInstance").isNull()) {
            findings.add(new Finding("MULTI_INSTANCE", "The step '" + label(sourceNode)
                    + "' asks several people to approve; a request waiting at it cannot be moved.", active));
        }
        if (targetNode == null) {
            findings.add(new Finding("STEP_MISSING", "The target release no longer has the step '"
                    + label(sourceNode) + "' where the request is waiting.", active));
            return findings;
        }
        if (!kind.equals(targetNode.path("type").asString(""))) {
            findings.add(new Finding("STEP_CHANGED", "The step '" + label(sourceNode)
                    + "' is a different kind of step in the target release.", active));
        } else if (!sourceNode.path("config").equals(targetNode.path("config"))
                || !references(source, sourceNode).equals(references(target, targetNode))) {
            findings.add(new Finding("STEP_CHANGED", "The settings of the step '" + label(sourceNode)
                    + "' differ in the target release (for example its group, deadline or time limit).", active));
        }

        Set<String> sourceHistory = ancestors(source, active);
        Set<String> targetHistory = ancestors(target, active);
        Set<String> missing = new LinkedHashSet<>(sourceHistory);
        missing.removeAll(targetHistory);
        Set<String> added = new LinkedHashSet<>(targetHistory);
        added.removeAll(sourceHistory);
        for (String id : missing) {
            findings.add(new Finding("HISTORY_CHANGED", "The step '" + label(node(source, id))
                    + "' the request already passed no longer leads to this point in the target release.", id));
        }
        for (String id : added) {
            findings.add(new Finding("HISTORY_CHANGED", "The target release adds the step '" + label(node(target, id))
                    + "' before this point; the request has not been through it.", id));
        }
        for (String id : sourceHistory) {
            JsonNode a = node(source, id);
            JsonNode b = node(target, id);
            if (b == null || !targetHistory.contains(id)) {
                continue;
            }
            if (!a.path("type").equals(b.path("type")) || !a.path("config").equals(b.path("config"))
                    || !references(source, a).equals(references(target, b))) {
                findings.add(new Finding("HISTORY_CHANGED", "The step '" + label(a)
                        + "' the request already passed is configured differently in the target release.", id));
            }
        }
        Set<String> scope = new LinkedHashSet<>(sourceHistory);
        scope.add(active);
        if (!flows(source, scope).equals(flows(target, scope))) {
            findings.add(new Finding("HISTORY_CHANGED",
                    "The routes between the steps the request already passed differ in the target release.", null));
        }
        for (String id : sourceHistory) {
            JsonNode n = node(source, id);
            if (n != null && "parallelSplit".equals(n.path("type").asString())
                    && !sourceHistory.contains(n.path("config").path("joinRef").asString(""))) {
                findings.add(new Finding("PARALLEL_REGION",
                        "The request is inside a parallel section; requests in a parallel section cannot be moved.", id));
            }
        }
        if (!source.path("stages").equals(target.path("stages"))) {
            findings.add(new Finding("STAGES_CHANGED",
                    "The case stages differ between the releases, so stage progress could not be carried over.", null));
        }
        return findings;
    }

    /**
     * What a node points at outside its own settings: the decision table it evaluates and the connection version it
     * calls. A request that already passed the node was decided by exactly those, so they must be unchanged too.
     */
    private static List<JsonNode> references(JsonNode definition, JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        String decisionRef = node.path("config").path("decisionRef").asString("");
        if (!decisionRef.isEmpty()) {
            for (JsonNode table : definition.path("decisions")) {
                if (decisionRef.equals(table.path("key").asString(""))) {
                    result.add(table);
                }
            }
        }
        String connectionRef = node.path("config").path("connectionRef").asString("");
        if (!connectionRef.isEmpty()) {
            for (JsonNode dependency : definition.path("dependencies")) {
                if (connectionRef.equals(dependency.path("key").asString(""))) {
                    result.add(dependency);
                }
            }
        }
        return result;
    }

    /** Every node from which {@code nodeId} can be reached, excluding itself unless it lies on a cycle. */
    static Set<String> ancestors(JsonNode definition, String nodeId) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (JsonNode t : definition.path("transitions")) {
                if (current.equals(t.path("to").asString(""))) {
                    String from = t.path("from").asString("");
                    if (seen.add(from)) {
                        queue.add(from);
                    }
                }
            }
        }
        seen.remove(nodeId);
        return seen;
    }

    /** (from, to, on) triples whose both ends lie in {@code scope}, as a comparable set. */
    private static Set<String> flows(JsonNode definition, Set<String> scope) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode t : definition.path("transitions")) {
            String from = t.path("from").asString("");
            String to = t.path("to").asString("");
            if (scope.contains(from) && scope.contains(to)) {
                result.add(from + "|" + to + "|" + t.path("on").asString(""));
            }
        }
        return result;
    }

    private static JsonNode node(JsonNode definition, String id) {
        for (JsonNode n : definition.path("nodes")) {
            if (id.equals(n.path("id").asString(null))) {
                return n;
            }
        }
        return null;
    }

    private static String label(JsonNode node) {
        return node == null ? "?" : node.path("label").asString(node.path("id").asString("?"));
    }
}
