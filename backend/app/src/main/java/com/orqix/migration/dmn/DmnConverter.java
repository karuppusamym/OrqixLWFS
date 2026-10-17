package com.orqix.migration.dmn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.migration.camunda.Identifiers;
import com.orqix.migration.camunda.Report;
import com.orqix.shared.json.Json;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decision tables exist in the product only inside workflows (workflow-v0 {@code decisions[]}, used by a decision step),
 * so a DMN file has no standalone target. This converter hosts one converted decision in the smallest valid workflow: a
 * request form with the tested input, one decision step, and a result that returns the decision's outputs. The draft is
 * validated by the normal compiler and its table can be exercised with the draft's "test this rule" endpoint. For
 * decisions called from a BPMN file, see {@code Converter} (business rule task to decision step).
 */
public final class DmnConverter {

    /** Node id of the hosted decision step. */
    public static final String DECISION_NODE = "decide";

    private DmnConverter() {
    }

    /** Canonical table key for a source decision id. */
    public static String tableKey(String decisionId) {
        return Identifiers.sanitize(decisionId, true);
    }

    /** The smallest workflow that exposes one converted decision. */
    public static ObjectNode workflow(DmnTable table, String workflowKey, String name, Json json) {
        String key = tableKey(table.decisionId());
        ObjectNode def = json.object();
        def.put("schemaVersion", "0.1");
        def.put("workflowKey", workflowKey);
        def.put("name", name);
        def.set("metadata", json.toTree(Map.of("description", "Imported DMN decision '" + DmnTable.limit(table.name(), 120)
                + "'; review the compatibility report and the generated test cases before using it.")));
        ObjectNode input = def.putObject("inputSchema");
        input.put("type", "object");
        input.put("additionalProperties", false);
        input.putArray("required").add(table.inputKey());
        input.putObject("properties").set(table.inputKey(), table.inputField(json));
        ObjectNode output = def.putObject("outputSchema");
        output.put("type", "object");
        output.put("additionalProperties", false);
        ObjectNode outputProperties = output.putObject("properties");
        ObjectNode mapping = def.putObject("outputMapping");
        for (DmnTable.Output out : table.outputs()) {
            ObjectNode field = outputProperties.putObject(out.key());
            field.put("type", out.type());
            field.put("title", out.title());
            ObjectNode entry = mapping.putObject(out.key());
            entry.put("fieldRef", "/steps/" + DECISION_NODE + "/" + out.key());
            entry.put("required", true);
        }
        def.set("triggers", json.toTree(List.of("manual", "api")));
        ArrayNode nodes = def.putArray("nodes");
        node(nodes, "start", "start", "Decision requested", json.object());
        ObjectNode config = json.object();
        config.put("decisionRef", key);
        node(nodes, DECISION_NODE, "decision", DmnTable.limit(table.name(), 190), config);
        ObjectNode end = json.object();
        end.put("businessOutcome", "Decided");
        end.put("outcomeKind", "success");
        node(nodes, "done", "end", "Decided", end);
        ArrayNode transitions = def.putArray("transitions");
        transition(transitions, "t1", "start", DECISION_NODE);
        transition(transitions, "t2", DECISION_NODE, "done");
        ArrayNode decisions = def.putArray("decisions");
        decisions.add(table.table(key, "/input/" + table.inputKey(), json));
        def.set("execution", json.toTree(Map.of("maxConcurrentRuns", 100, "maxAttempts", 3, "retryDelaySeconds", 30)));
        def.set("security", json.toTree(Map.of("selfApproval", "deny")));
        def.set("dependencies", json.toTree(Map.of("connections", List.of())));
        return def;
    }

    /** Source element ids of the selected decision (and file-level items) mapped to the hosted target step. */
    public static Map<String, String> sourceMap(DmnAnalysis analysis, String decisionId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (DmnAnalysis.Item item : analysis.items()) {
            if (decisionId.equals(item.decisionId())) {
                map.put(item.issue().sourceElementId(), DECISION_NODE);
            }
        }
        return map;
    }

    /** The report with target node ids filled in for the converted decision's items. */
    public static Report.CompatibilityReport withTargets(Report.CompatibilityReport report, Map<String, String> sourceMap) {
        List<Report.Issue> issues = report.issues().stream()
                .map(i -> sourceMap.containsKey(i.sourceElementId()) ? i.withTarget(sourceMap.get(i.sourceElementId())) : i)
                .toList();
        return new Report.CompatibilityReport(report.sourceSystem(), report.sourceVersion(), report.adapterVersion(),
                report.sourceHash(), report.processId(), report.processName(), report.elementCounts(), issues, report.assumptions(),
                report.requiredMappings(), report.publishable(), report.testCases());
    }

    private static void node(ArrayNode nodes, String id, String type, String label, ObjectNode config) {
        ObjectNode node = nodes.addObject();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        node.set("config", config);
    }

    private static void transition(ArrayNode transitions, String id, String from, String to) {
        ObjectNode t = transitions.addObject();
        t.put("id", id);
        t.put("from", from);
        t.put("to", to);
        t.put("on", "next");
    }
}
