package com.orqix.compiler.internal.bpmn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.internal.json.Jcs;

/**
 * Canonical ID to BPMN ID mapping (S4 section 9). Generated helpers map back to their owning node. Serialized as
 * pretty-printed JSON (2-space indent, LF) with members in canonical order.
 */
public final class SourceMap {

    private final Map<String, List<String>> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> transitions = new LinkedHashMap<>();
    private final Map<String, String> bpmnToCanonical = new LinkedHashMap<>();

    void node(String nodeId, String bpmnId) {
        nodes.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(bpmnId);
        bpmnToCanonical.put(bpmnId, nodeId);
    }

    void transition(String transitionId, String bpmnId) {
        transitions.computeIfAbsent(transitionId, k -> new ArrayList<>()).add(bpmnId);
        bpmnToCanonical.put(bpmnId, transitionId);
    }

    public Map<String, List<String>> nodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public Map<String, List<String>> transitions() {
        return Collections.unmodifiableMap(transitions);
    }

    public Map<String, String> bpmnToCanonical() {
        return Collections.unmodifiableMap(bpmnToCanonical);
    }

    /** @return {"nodes":{...},"transitions":{...},"bpmnToCanonical":{...}} pretty-printed, ending with LF */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        lists(sb, "nodes", nodes);
        sb.append(",\n");
        lists(sb, "transitions", transitions);
        sb.append(",\n  ").append(Jcs.quote("bpmnToCanonical")).append(": {");
        boolean first = true;
        for (Map.Entry<String, String> e : bpmnToCanonical.entrySet()) {
            sb.append(first ? "\n" : ",\n").append("    ").append(Jcs.quote(e.getKey())).append(": ")
                    .append(Jcs.quote(e.getValue()));
            first = false;
        }
        sb.append(first ? "}" : "\n  }").append("\n}\n");
        return sb.toString();
    }

    private static void lists(StringBuilder sb, String name, Map<String, List<String>> map) {
        sb.append("  ").append(Jcs.quote(name)).append(": {");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            sb.append(first ? "\n" : ",\n").append("    ").append(Jcs.quote(e.getKey())).append(": [");
            for (int i = 0; i < e.getValue().size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(Jcs.quote(e.getValue().get(i)));
            }
            sb.append(']');
            first = false;
        }
        sb.append(first ? "}" : "\n  }");
    }
}
