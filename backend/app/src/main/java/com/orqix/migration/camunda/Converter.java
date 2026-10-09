package com.orqix.migration.camunda;

import java.util.*;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Converts only the analysed subset. Original executable extensions are never evaluated. */
public final class Converter {
    public record Result(ObjectNode definition, Report.CompatibilityReport report, Map<String, String> sourceMap) { }

    public static Report.CompatibilityReport report(Analysis a, List<Report.Issue> issues) {
        return new Report.CompatibilityReport(a.sourceSystem(), a.sourceVersion(), Analyzer.ADAPTER_VERSION,
                a.sourceHash(), a.processId(), a.processName(), Report.ElementCounts.of(issues), issues,
                a.assumptions(), new Report.RequiredMappings(a.userTasks().values().stream()
                    .flatMap(t -> (t.mappingKeys().isEmpty() ? List.of(t.id()) : t.mappingKeys()).stream()).distinct().toList(),
                    a.userTasks().keySet().stream().toList(), a.services().keySet().stream().toList()),
                issues.stream().noneMatch(Report.Issue::blocksPublishing)
                    && issues.stream().noneMatch(i -> Report.INFO.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus())));
    }

    public static Result convert(Analysis a, JsonNode request, Json json, Map<String, JsonNode> serviceInputTypes) {
        Set<String> assignments = new HashSet<>(report(a, List.of()).requiredMappings().groups());
        rejectUnknownMappings(request.path("groupMappings"), assignments, "groupMappings");
        rejectUnknownMappings(request.path("approvalRoutes"), a.userTasks.keySet(), "approvalRoutes");
        rejectUnknownMappings(request.path("serviceTaskMappings"), a.services.keySet(), "serviceTaskMappings");
        Map<String, String> groups = new LinkedHashMap<>();
        Map<String, Analysis.Route> routes = new LinkedHashMap<>();
        for (var task : a.userTasks.values()) {
            Set<String> targets = new LinkedHashSet<>();
            for (String key : task.mappingKeys) {
                String mapped = request.path("groupMappings").path(key).asString("");
                if (!mapped.isBlank()) targets.add(mapped);
            }
            // Tasks without a source assignment can be mapped explicitly by their source id.
            if (task.mappingKeys.isEmpty()) {
                String mapped = request.path("groupMappings").path(task.id()).asString("");
                if (!mapped.isBlank()) targets.add(mapped);
            }
            if (targets.size() == 1 && (task.mappingKeys.isEmpty() || task.mappingKeys.stream()
                    .allMatch(k -> !request.path("groupMappings").path(k).asString("").isBlank()))) {
                groups.put(task.id(), targets.iterator().next());
            }
            if (task.recognized != null) {
                var supplied = request.path("approvalRoutes").path(task.id());
                if (supplied.isObject() && (!task.recognized.approvedFlowId().equals(supplied.path("approvedFlowId").asString())
                        || !task.recognized.rejectedFlowId().equals(supplied.path("rejectedFlowId").asString())))
                    throw SourceException.invalid("Explicit approval routes disagree with the recognized source gateway for '" + task.id() + "'.");
                routes.put(task.id(), task.recognized);
            }
            else {
                var supplied = request.path("approvalRoutes").path(task.id());
                String approved = supplied.path("approvedFlowId").asString("");
                String rejected = supplied.path("rejectedFlowId").asString("");
                Set<String> candidates = new HashSet<>(task.candidateFlows.stream().map(Analysis.Flow::id).toList());
                if (candidates.size() == 2 && !approved.equals(rejected) && candidates.containsAll(List.of(approved, rejected))) {
                    routes.put(task.id(), new Analysis.Route(approved, rejected,
                            task.outcomeGateway == null ? null : task.outcomeGateway.id, null, false));
                }
            }
        }
        List<Report.Issue> issues = new ArrayList<>();
        for (var item : a.items) {
            boolean resolved = switch (item.need) {
                case TASK -> groups.containsKey(item.subject) && routes.containsKey(item.subject);
                case OUTCOME_GATEWAY -> routes.containsKey(item.subject);
                case SERVICE -> request.path("serviceTaskMappings").path(item.subject).isObject();
                case ACKNOWLEDGE -> request.path("acknowledgeInfo").asBoolean(false);
                default -> false;
            };
            issues.add(resolved ? item.issue.withResolution(item.need == Analysis.Need.ACKNOWLEDGE
                    ? Report.ACKNOWLEDGED : Report.RESOLVED) : item.issue);
        }
        var compatibility = report(a, issues);
        if (!compatibility.publishable()) return new Result(null, compatibility, Map.of());
        ObjectNode def = json.object();
        def.put("schemaVersion", "0.1"); def.put("workflowKey", request.path("workflowKey").asString());
        def.put("name", request.path("name").asString());
        def.set("metadata", json.toTree(Map.of("description", "Imported BPMN draft; review source report and test before publishing.")));
        ObjectNode input = json.object(); input.put("type", "object"); input.put("additionalProperties", false);
        ObjectNode properties = input.putObject("properties");
        Map<String, String> inferred = new LinkedHashMap<>();
        a.branches.values().forEach(b -> inferred.put(Analyzer.fieldKey(b.variable), b.valueType));
        serviceInputTypes.forEach((key, schema) -> {
            String type = schema.path("type").asString();
            String previous = inferred.putIfAbsent(key, type);
            if (previous != null && !previous.equals(type) && !(Set.of(previous, type).contains("number") && Set.of(previous, type).contains("integer")))
                throw SourceException.invalid("Incompatible inferred types for request field '" + key + "'. Supply a reviewed request schema.");
        });
        request.path("serviceTaskMappings").properties().forEach(e -> e.getValue().path("inputMapping").properties()
                .forEach(m -> { String ref = m.getValue().asString(""); if (ref.matches("/input/[a-z][A-Za-z0-9]{0,63}")) inferred.putIfAbsent(ref.substring(7), "string"); }));
        inferred.forEach((key, type) -> { var field = properties.putObject(key); field.put("type", type); field.put("title", key); });
        serviceInputTypes.forEach((key, schema) -> {
            ObjectNode field = (ObjectNode) schema.deepCopy();
            if (!field.has("title")) field.put("title", key);
            properties.set(key, field);
        });
        input.set("required", json.toTree(inferred.keySet()));
        def.set("inputSchema", request.path("inputSchema").isObject() ? request.path("inputSchema").deepCopy() : input);
        def.set("outputSchema", json.toTree(Map.of("type", "object", "additionalProperties", false, "properties", Map.of())));
        def.set("outputMapping", json.object()); def.set("decisions", json.toTree(List.of()));
        def.set("triggers", json.toTree(List.of("manual", "api")));
        def.set("security", json.toTree(Map.of("selfApproval", "deny")));
        def.set("execution", json.toTree(Map.of("maxConcurrentRuns", 20, "maxAttempts", 3, "retryDelaySeconds", 30)));
        var nodes = def.putArray("nodes"); var transitions = def.putArray("transitions");
        Map<String, String> sourceMap = new LinkedHashMap<>();
        Set<String> outcomeGateways = new HashSet<>(a.outcomeGatewayOf.values());
        Map<String, Integer> versions = new TreeMap<>();
        for (var node : a.nodes.values()) {
            if (outcomeGateways.contains(node.id) || a.mergeGateways.contains(node.id)) continue;
            ObjectNode config = json.object();
            String type = switch (node.type) {
                case "startEvent" -> "start"; case "endEvent" -> "end"; case "userTask" -> "approval";
                case "serviceTask" -> "http"; case "exclusiveGateway" -> "branch";
                default -> throw new IllegalStateException("Unresolved source element");
            };
            if (type.equals("end")) {
                String outcome = node.name == null || node.name.isBlank() ? "Completed" : node.name;
                config.put("businessOutcome", outcome);
                config.put("outcomeKind", outcome.toLowerCase(Locale.ROOT).matches(".*(reject|denied|decline|refus).*") ? "rejected" : "success");
            } else if (type.equals("approval")) { config.put("candidateGroupRef", groups.get(node.id)); config.put("rejectReasonRequired", true); }
            else if (type.equals("http")) {
                config = (ObjectNode) request.path("serviceTaskMappings").path(node.id).deepCopy();
                int version = config.path("definitionVersion").asInt(1); config.remove("definitionVersion");
                String key = config.path("connectionRef").asString();
                Integer previous = versions.putIfAbsent(key, version);
                if (previous != null && previous != version) throw SourceException.invalid("One connection cannot use different definition versions in the same draft.");
                if (!config.has("timeoutSeconds")) config.put("timeoutSeconds", 10);
            }
            var target = nodes.addObject(); target.put("id", node.canonicalId); target.put("type", type);
            target.put("label", node.name == null || node.name.isBlank() ? node.id : node.name); target.set("config", config);
            sourceMap.put(node.id, node.canonicalId);
        }
        Set<String> used = new HashSet<>();
        for (var flow : a.flows.values()) {
            if (a.mergeGateways.contains(flow.source.id)) continue;
            Analysis.Node from = flow.source;
            String on = "next";
            for (var task : a.userTasks.values()) {
                var route = routes.get(task.id());
                if (task.outcomeGateway != null && flow.source.id.equals(task.id())) { from = null; break; }
                if ((route.gatewayId() != null && flow.source.id.equals(route.gatewayId())) || flow.source.id.equals(task.id())) {
                    from = task.node; on = flow.id.equals(route.approvedFlowId()) ? "approved" : "rejected"; break;
                }
            }
            if (from == null) continue;
            Analysis.Node to = flow.target;
            Set<String> visited = new HashSet<>();
            while (a.mergeGateways.contains(to.id)) {
                if (!visited.add(to.id)) throw SourceException.invalid("Cycle between merge gateways.");
                to = to.out.get(0).target;
            }
            var transition = transitions.addObject();
            String id = Identifiers.unique(Identifiers.sanitize(flow.id, false), used);
            transition.put("id", id); transition.put("from", from.canonicalId); transition.put("to", to.canonicalId);
            var branch = a.branches.get(from.id);
            if (branch != null) {
                var entry = branch.entries.stream().filter(e -> e.flowId().equals(flow.id)).findFirst().orElseThrow();
                on = entry.isDefault() ? "default" : "condition";
                if (!entry.isDefault()) transition.set("condition", json.toTree(Map.of("fieldRef", "/input/" + Analyzer.fieldKey(branch.variable),
                        "operator", entry.op().canonical(), "value", entry.literal())));
            }
            transition.put("on", on); if (flow.name != null && !flow.name.isBlank()) transition.put("label", flow.name);
            sourceMap.put(flow.id, id);
        }
        outcomeGateways.forEach(g -> a.userTasks.values().stream().filter(t -> t.outcomeGateway != null && t.outcomeGateway.id.equals(g))
                .findFirst().ifPresent(t -> {
                    sourceMap.put(g, t.node.canonicalId);
                    t.node.out.forEach(f -> sourceMap.put(f.id, t.node.canonicalId));
                }));
        for (String gateway : a.mergeGateways) {
            Analysis.Node target = a.nodes.get(gateway);
            while (a.mergeGateways.contains(target.id)) target = target.out.get(0).target;
            sourceMap.put(gateway, target.canonicalId);
            for (var flow : a.nodes.get(gateway).out) sourceMap.put(flow.id, target.canonicalId);
        }
        def.set("dependencies", json.toTree(Map.of("connections", versions.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "definitionVersion", e.getValue())).toList())));
        compatibility = report(a, issues.stream().map(i -> sourceMap.containsKey(i.sourceElementId())
                ? i.withTarget(sourceMap.get(i.sourceElementId())) : i).toList());
        return new Result(def, compatibility, sourceMap);
    }
    private static void rejectUnknownMappings(JsonNode mappings, Set<String> known, String kind) {
        for (String key : mappings.propertyNames()) if (!known.contains(key))
            throw SourceException.invalid(kind + " references unknown source assignment/task '" + key + "'.");
    }
}
