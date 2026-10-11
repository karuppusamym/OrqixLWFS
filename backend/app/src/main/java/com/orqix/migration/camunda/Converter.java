package com.orqix.migration.camunda;

import java.util.*;
import com.orqix.migration.dmn.DmnConverter;
import com.orqix.migration.dmn.DmnTable;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Converts only the analysed subset. Original executable extensions are never evaluated. */
public final class Converter {
    public record Result(ObjectNode definition, Report.CompatibilityReport report, Map<String, String> sourceMap) { }

    /**
     * Only open blockers, open actions and open info items stop a conversion. Review items never do: they are
     * converted constructs that need a human check, kept (open or acknowledged) in the report as evidence.
     */
    public static Report.CompatibilityReport report(Analysis a, List<Report.Issue> issues) {
        return new Report.CompatibilityReport(a.sourceSystem(), a.sourceVersion(), Analyzer.ADAPTER_VERSION,
                a.sourceHash(), a.processId(), a.processName(), Report.ElementCounts.of(issues), issues,
                a.assumptions(), new Report.RequiredMappings(a.userTasks().values().stream()
                    .flatMap(t -> (t.mappingKeys().isEmpty() ? List.of(t.id()) : t.mappingKeys()).stream()).distinct().toList(),
                    a.userTasks().keySet().stream().toList(), a.services().keySet().stream().toList(), a.children.stream().toList(), a.serviceGroups,
                    a.decisions.keySet().stream().toList()),
                issues.stream().noneMatch(Report.Issue::blocksPublishing)
                    && issues.stream().noneMatch(i -> Report.INFO.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus())),
                List.of(), a.hints());
    }

    public static Result convert(Analysis a, JsonNode request, Json json, Map<String, JsonNode> serviceInputTypes) {
        return convert(a, request, json, serviceInputTypes, Map.of());
    }

    /**
     * @param decisionTables business rule task id to the converted DMN table the designer mapped it to (already checked to be
     *                       convertible by the caller)
     */
    public static Result convert(Analysis a, JsonNode request, Json json, Map<String, JsonNode> serviceInputTypes,
            Map<String, DmnTable> decisionTables) {
        return convert(a, request, json, serviceInputTypes, decisionTables, Map.of());
    }

    /**
     * @param uploadedForms start event or user task id to the parsed Camunda form the designer mapped under
     *                      {@code formMappings} (already matched to the form id the element references)
     */
    public static Result convert(Analysis a, JsonNode request, Json json, Map<String, JsonNode> serviceInputTypes,
            Map<String, DmnTable> decisionTables, Map<String, FormImport.Model> uploadedForms) {
        JsonNode effectiveRequest = expandServiceGroups(a,request,json);
        rejectUnknownMappings(effectiveRequest.path("formMappings"), a.linkedForms.keySet(), "formMappings");
        Set<String> assignments = new HashSet<>(report(a, List.of()).requiredMappings().groups());
        rejectUnknownMappings(effectiveRequest.path("groupMappings"), assignments, "groupMappings");
        rejectUnknownMappings(effectiveRequest.path("approvalRoutes"), a.userTasks.keySet(), "approvalRoutes");
        rejectUnknownMappings(effectiveRequest.path("serviceTaskMappings"), a.services.keySet(), "serviceTaskMappings");
        rejectUnknownMappings(effectiveRequest.path("childWorkflowMappings"), a.children, "childWorkflowMappings");
        rejectUnknownMappings(effectiveRequest.path("decisionMappings"), a.decisions.keySet(), "decisionMappings");
        for (var entry : effectiveRequest.path("childWorkflowMappings").properties()) {
            if (entry.getValue().has("forEach") && !a.multiInstance.containsKey(entry.getKey()))
                throw SourceException.invalid("childWorkflowMappings/" + entry.getKey() + " sets forEach, but this call activity is not a "
                        + "multi-instance call over a plain collection.");
            if (entry.getValue().has("collectionMapping") && !entry.getValue().has("forEach"))
                throw SourceException.invalid("childWorkflowMappings/" + entry.getKey() + " sets collectionMapping, which needs forEach.");
        }
        Map<String, String> groups = new LinkedHashMap<>();
        Map<String, Analysis.Route> routes = new LinkedHashMap<>();
        for (var task : a.userTasks.values()) {
            Set<String> targets = new LinkedHashSet<>();
            for (String key : task.mappingKeys) {
                String mapped = effectiveRequest.path("groupMappings").path(key).asString("");
                if (!mapped.isBlank()) targets.add(mapped);
            }
            // Tasks without a source assignment can be mapped explicitly by their source id.
            if (task.mappingKeys.isEmpty()) {
                String mapped = effectiveRequest.path("groupMappings").path(task.id()).asString("");
                if (!mapped.isBlank()) targets.add(mapped);
            }
            if (targets.size() == 1 && (task.mappingKeys.isEmpty() || task.mappingKeys.stream()
                    .allMatch(k -> !effectiveRequest.path("groupMappings").path(k).asString("").isBlank()))) {
                groups.put(task.id(), targets.iterator().next());
            }
            if (task.recognized != null) {
                var supplied = effectiveRequest.path("approvalRoutes").path(task.id());
                if (supplied.isObject() && (!task.recognized.approvedFlowId().equals(supplied.path("approvedFlowId").asString())
                        || !task.recognized.rejectedFlowId().equals(supplied.path("rejectedFlowId").asString())))
                    throw SourceException.invalid("Explicit approval routes disagree with the recognized source gateway for '" + task.id() + "'.");
                routes.put(task.id(), task.recognized);
            }
            else {
                var supplied = effectiveRequest.path("approvalRoutes").path(task.id());
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
        boolean acknowledgeInfo = effectiveRequest.path("acknowledgeInfo").asBoolean(false);
        boolean acknowledgeReview = effectiveRequest.path("acknowledgeReview").asBoolean(false);
        for (var item : a.items) {
            boolean resolved = switch (item.need) {
                case TASK -> groups.containsKey(item.subject) && routes.containsKey(item.subject);
                case OUTCOME_GATEWAY -> routes.containsKey(item.subject);
                case SERVICE -> effectiveRequest.path("serviceTaskMappings").path(item.subject).isObject();
                case SERVICE_GROUP -> a.serviceGroups.get(item.subject).stream()
                        .allMatch(task -> effectiveRequest.path("serviceTaskMappings").path(task).isObject());
                case CHILD -> childMapped(a, item.subject, effectiveRequest.path("childWorkflowMappings").path(item.subject), effectiveRequest);
                case DECISION -> effectiveRequest.path("decisionMappings").path(item.subject).isObject() && decisionTables.containsKey(item.subject);
                case FORM -> uploadedForms.containsKey(item.subject) || acknowledgeInfo;
                case ACKNOWLEDGE -> Report.REVIEW.equals(item.issue.severity()) ? acknowledgeReview : acknowledgeInfo;
                default -> false;
            };
            Report.Issue shown = item.issue;
            if (item.need == Analysis.Need.FORM && resolved && !uploadedForms.containsKey(item.subject)) {
                shown = item.issue.withResolution(Report.ACKNOWLEDGED); // the designer chose to leave the form out
            } else if (resolved && item.reviewAfterResolution != null) {
                // A designer mapping converts the construct; its behaviour can still differ, so it becomes a review item.
                shown = item.reviewAfterResolution.withResolution(acknowledgeReview ? Report.ACKNOWLEDGED : Report.OPEN);
            } else if (resolved) {
                shown = item.issue.withResolution(item.need == Analysis.Need.ACKNOWLEDGE ? Report.ACKNOWLEDGED : Report.RESOLVED);
            }
            issues.add(shown);
        }
        for (var form : uploadedForms.entrySet()) {
            String target = a.linkedForms.containsKey(form.getKey()) && a.nodes.containsKey(form.getKey())
                    && "startEvent".equals(a.nodes.get(form.getKey()).type) ? "start" : "userTask";
            for (Report.Issue issue : form.getValue().issuesFor(target, a.nodes.containsKey(form.getKey()) ? a.nodes.get(form.getKey()).name : null)) {
                issues.add(switch (issue.severity()) {
                    case Report.SUPPORTED -> issue;
                    case Report.REVIEW -> issue.withResolution(acknowledgeReview ? Report.ACKNOWLEDGED : Report.OPEN);
                    case Report.INFO -> issue.withResolution(acknowledgeInfo ? Report.ACKNOWLEDGED : Report.OPEN);
                    default -> issue;
                });
            }
        }
        var compatibility = report(a, issues);
        if (!compatibility.publishable()) return new Result(null, compatibility, Map.of());
        ObjectNode def = json.object();
        def.put("schemaVersion", "0.1"); def.put("workflowKey", effectiveRequest.path("workflowKey").asString());
        def.put("name", effectiveRequest.path("name").asString());
        ObjectNode metadata = json.object();
        metadata.put("description", "Imported BPMN draft; review source report and test before publishing.");
        def.set("metadata", metadata);
        ObjectNode input = json.object(); input.put("type", "object"); input.put("additionalProperties", false);
        ObjectNode properties = input.putObject("properties");
        Map<String, String> inferred = new LinkedHashMap<>();
        Map<String, ObjectNode> structured = new LinkedHashMap<>();
        Map<String, DecisionUse> decisionUses = decisionUses(a, effectiveRequest, decisionTables, json);
        for (var branch : a.branches.values()) {
            for (var variable : branch.variables().entrySet()) {
                if (a.decisionResults.containsKey(variable.getKey())) continue; // read from the decision step, not the request
                mergeType(inferred, Analyzer.fieldKey(variable.getKey()), variable.getValue());
            }
        }
        for (DecisionUse use : decisionUses.values()) {
            if (use.fieldRef.startsWith("/input/") && use.fieldRef.indexOf('/', 7) < 0) {
                String key = use.fieldRef.substring(7);
                mergeType(inferred, key, use.table.inputType());
                if (use.table.inputEnum() != null) structured.put(key, use.table.inputField(json));
            }
        }
        serviceInputTypes.forEach((key, schema) -> {
            String type = schema.path("type").asString();
            String previous = inferred.putIfAbsent(key, type);
            if (previous != null && !previous.equals(type) && !(Set.of(previous, type).contains("number") && Set.of(previous, type).contains("integer")))
                throw SourceException.invalid("Incompatible inferred types for request field '" + key + "'. Supply a reviewed request schema.");
        });
        effectiveRequest.path("serviceTaskMappings").properties().forEach(e -> e.getValue().path("inputMapping").properties()
                .forEach(m -> { String ref = m.getValue().asString(""); if (ref.matches("/input/[a-z][A-Za-z0-9]{0,63}")) inferred.putIfAbsent(ref.substring(7), "string"); }));
        // Multi-instance collections are arrays with a designer-set bound; they are not scalar fields.
        Map<String, ObjectNode> collections = new LinkedHashMap<>();
        a.multiInstance.forEach((task, mi) -> {
            JsonNode forEach = effectiveRequest.path("childWorkflowMappings").path(task).path("forEach");
            ObjectNode field = json.object(); field.put("type", "array"); field.put("title", mi.fieldKey());
            ObjectNode items = field.putObject("items"); items.put("type", forEach.path("itemType").asString("string")); items.put("title", "Item");
            field.put("minItems", 1); field.put("maxItems", forEach.path("maxItems").asInt(1));
            if (collections.putIfAbsent(mi.fieldKey(), field) != null || inferred.containsKey(mi.fieldKey()))
                throw SourceException.invalid("The collection field '" + mi.fieldKey() + "' is also used as another request field. Supply a reviewed request schema.");
        });
        // Forms on start events supply typed request fields; a comparison or service call on the same variable must agree with them.
        Map<String, FormImport.Field> formFields = new LinkedHashMap<>();
        for (String startId : a.startIds) {
            FormImport.Model form = a.embeddedForms.containsKey(startId) ? a.embeddedForms.get(startId) : uploadedForms.get(startId);
            if (form == null) continue;
            for (FormImport.Field field : form.fields()) {
                if (formFields.putIfAbsent(field.fieldKey(), field) != null)
                    throw SourceException.invalid("Two start forms both define the request field '" + field.fieldKey() + "'.");
            }
        }
        Map<String, String> dateFields = new LinkedHashMap<>();
        a.waitDates.forEach((node, variable) -> dateFields.putIfAbsent(Analyzer.fieldKey(variable), variable));
        Set<String> gatewayFields = new LinkedHashSet<>(inferred.keySet());
        for (var entry : formFields.entrySet()) {
            String previous = inferred.remove(entry.getKey());
            if (previous != null && !previous.equals(entry.getValue().type())
                    && !(Set.of(previous, entry.getValue().type()).contains("number") && Set.of(previous, entry.getValue().type()).contains("integer")))
                throw SourceException.invalid("The form field '" + entry.getKey() + "' is " + entry.getValue().type() + ", but a choice or service call of the process uses it as "
                        + previous + ". Fix the form or the condition.");
            JsonNode service = serviceInputTypes.get(entry.getKey());
            if (service != null && !service.path("type").asString("").equals(entry.getValue().type())
                    && !(Set.of(service.path("type").asString(""), entry.getValue().type()).contains("number") && Set.of(service.path("type").asString(""), entry.getValue().type()).contains("integer")))
                throw SourceException.invalid("The form field '" + entry.getKey() + "' is " + entry.getValue().type() + ", but the mapped operation expects "
                        + service.path("type").asString("") + ".");
            if (dateFields.containsKey(entry.getKey()) && !"date".equals(entry.getValue().schema().get("format")))
                throw SourceException.invalid("The form field '" + entry.getKey() + "' is the date a timer waits for, but it is not a date field.");
        }
        inferred.forEach((key, type) -> { var field = properties.putObject(key); field.put("type", type); field.put("title", key); });
        structured.forEach(properties::set);
        serviceInputTypes.forEach((key, schema) -> {
            ObjectNode field = (ObjectNode) schema.deepCopy();
            if (!field.has("title")) field.put("title", key);
            properties.set(key, field);
        });
        dateFields.forEach((key, variable) -> {
            if (formFields.containsKey(key) || properties.has(key)) {
                if (!formFields.containsKey(key)) throw SourceException.invalid("The date variable '" + variable + "' is also used as another request field. Supply a reviewed request schema.");
                return;
            }
            var field = properties.putObject(key); field.put("type", "string"); field.put("title", variable); field.put("format", "date");
        });
        ObjectNode ui = json.object();
        ArrayNode order = ui.putArray("order");
        ObjectNode widgets = ui.putObject("widgets");
        formFields.values().forEach(field -> {
            properties.set(field.fieldKey(), json.toTree(field.schema()));
            order.add(field.fieldKey());
            widgets.put(field.fieldKey(), field.widget());
        });
        collections.forEach(properties::set);
        Set<String> required = new LinkedHashSet<>(inferred.keySet()); required.addAll(collections.keySet()); required.addAll(dateFields.keySet());
        for (FormImport.Field field : formFields.values()) {
            if (field.required() || gatewayFields.contains(field.fieldKey())) required.add(field.fieldKey());
            if (!field.required() && gatewayFields.contains(field.fieldKey()))
                a.assumptions.add("The form field '" + field.fieldKey() + "' is optional in the source form, but a choice reads it; it is required in the request form.");
        }
        input.set("required", json.toTree(required));
        def.set("inputSchema", effectiveRequest.path("inputSchema").isObject() ? effectiveRequest.path("inputSchema").deepCopy() : input);
        if (!formFields.isEmpty() && !effectiveRequest.path("inputSchema").isObject()) def.set("inputUi", ui);
        def.set("outputSchema", json.toTree(Map.of("type", "object", "additionalProperties", false, "properties", Map.of())));
        def.set("outputMapping", json.object());
        ArrayNode tables = def.putArray("decisions");
        def.set("triggers", json.toTree(List.of("manual", "api")));
        def.set("security", json.toTree(Map.of("selfApproval", "deny")));
        def.set("execution", json.toTree(Map.of("maxConcurrentRuns", 20, "maxAttempts", 3, "retryDelaySeconds", 30)));
        var nodes = def.putArray("nodes"); var transitions = def.putArray("transitions");
        Map<String, String> sourceMap = new LinkedHashMap<>();
        Set<String> outcomeGateways = new HashSet<>(a.outcomeGatewayOf.values());
        Map<String, Integer> versions = new TreeMap<>();
        Map<String, String> tableKeys = new LinkedHashMap<>();
        Set<String> usedTableKeys = new HashSet<>();
        for (var node : a.nodes.values()) {
            if (outcomeGateways.contains(node.id) || a.mergeGateways.contains(node.id) || a.timeoutBoundaries.containsKey(node.id)) continue;
            ObjectNode config = json.object();
            String type = switch (node.type) {
                case "startEvent" -> "start"; case "endEvent" -> "end"; case "userTask" -> "approval";
                case "serviceTask" -> "http"; case "exclusiveGateway" -> "branch"; case "businessRuleTask" -> "decision";
                case "parallelGateway" -> a.parallelJoins.containsKey(node.id) ? "parallelSplit" : "parallelJoin";
                case "intermediateCatchEvent" -> "wait"; case "callActivity" -> "startWorkflow";
                default -> throw new IllegalStateException("Unresolved source element");
            };
            if (type.equals("parallelSplit")) config.put("joinRef", a.nodes.get(a.parallelJoins.get(node.id)).canonicalId);
            else if (type.equals("wait")) {
                if (a.waitDates.containsKey(node.id)) config.put("untilField", "/input/" + Analyzer.fieldKey(a.waitDates.get(node.id)));
                else config.put("duration", a.waits.get(node.id));
            }
            else if (type.equals("startWorkflow")) config = childConfig(a, node, effectiveRequest, json);
            else if (type.equals("decision")) {
                DecisionUse use = decisionUses.get(node.id);
                String shareKey = use.table.decisionId() + "@" + use.fieldRef;
                String key = tableKeys.get(shareKey);
                if (key == null) {
                    key = Identifiers.unique(DmnConverter.tableKey(use.table.decisionId()), usedTableKeys);
                    tableKeys.put(shareKey, key);
                    ObjectNode table = use.table.table(key, use.fieldRef, json);
                    if (table == null) throw SourceException.invalid("The decision '" + use.table.decisionId() + "' cannot be converted.");
                    tables.add(table);
                }
                config.put("decisionRef", key);
            }
            else if (type.equals("end")) {
                String outcome = node.name == null || node.name.isBlank() ? "Completed" : node.name;
                config.put("businessOutcome", outcome);
                config.put("outcomeKind", outcome.toLowerCase(Locale.ROOT).matches(".*(reject|denied|decline|refus).*") ? "rejected" : "success");
            } else if (type.equals("approval")) {
                config.put("candidateGroupRef", groups.get(node.id)); config.put("rejectReasonRequired", true);
                Analysis.UserTask task = a.userTasks.get(node.id);
                if (task.dueAfterHours != null) config.put("dueAfterHours", task.dueAfterHours.intValue());
                if (task.timeoutAfter != null) config.put("timeoutAfter", task.timeoutAfter);
                FormImport.Model taskForm = a.embeddedForms.containsKey(node.id) ? a.embeddedForms.get(node.id) : uploadedForms.get(node.id);
                if (taskForm != null) {
                    ObjectNode notes = metadata.has("stepNotes") ? (ObjectNode) metadata.get("stepNotes") : metadata.putObject("stepNotes");
                    notes.put(node.canonicalId, taskForm.note(node.id));
                }
            }
            else if (type.equals("http")) {
                config = (ObjectNode) effectiveRequest.path("serviceTaskMappings").path(node.id).deepCopy();
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
            if (a.timeoutBoundaries.containsKey(flow.source.id)) {
                from = a.userTasks.get(a.timeoutBoundaries.get(flow.source.id)).node; on = "timedOut";
            } else
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
                if (!entry.isDefault()) transition.set("condition", condition(a, entry, decisionUses, json));
            }
            transition.put("on", on); if (flow.name != null && !flow.name.isBlank()) transition.put("label", flow.name);
            sourceMap.put(flow.id, id);
        }
        a.timeoutBoundaries.forEach((boundary, task) -> sourceMap.put(boundary, a.nodes.get(task).canonicalId));
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

    // ------------------------------------------------------------------------------------------------ helpers

    /** A mapped business rule task: the table, and the field it tests (request field unless the designer chose another). */
    private record DecisionUse(DmnTable table, String fieldRef, String taskId) { }

    private static Map<String, DecisionUse> decisionUses(Analysis a, JsonNode request, Map<String, DmnTable> tables, Json json) {
        Map<String, DecisionUse> uses = new LinkedHashMap<>();
        for (String task : a.decisions.keySet()) {
            DmnTable table = tables.get(task);
            JsonNode mapping = request.path("decisionMappings").path(task);
            if (table == null || !mapping.isObject()) continue;
            String ref = mapping.path("inputRef").asString("");
            if (ref.isBlank()) ref = "/input/" + table.inputKey();
            uses.put(task, new DecisionUse(table, ref, task));
        }
        return uses;
    }

    private static void mergeType(Map<String, String> inferred, String key, String type) {
        String previous = inferred.putIfAbsent(key, type);
        if (previous != null && !previous.equals(type) && !(Set.of(previous, type).contains("number") && Set.of(previous, type).contains("integer")))
            throw SourceException.invalid("Incompatible inferred types for request field '" + key + "'. Supply a reviewed request schema.");
    }

    /** True once the call activity has a complete mapping; a multi-instance call also needs its for-each settings. */
    private static boolean childMapped(Analysis a, String task, JsonNode mapping, JsonNode request) {
        if (!mapping.isObject()) return false;
        if (!a.multiInstance.containsKey(task)) return true;
        JsonNode each = mapping.path("forEach");
        boolean typed = request.path("inputSchema").isObject() || each.has("itemType");
        return each.isObject() && each.has("itemInput") && each.has("maxItems") && typed;
    }

    /** startWorkflow configuration from the reviewed mapping; a multi-instance call becomes a bounded for-each. */
    private static ObjectNode childConfig(Analysis a, Analysis.Node node, JsonNode request, Json json) {
        ObjectNode config = (ObjectNode) request.path("childWorkflowMappings").path(node.id).deepCopy();
        Analysis.MultiInstance mi = a.multiInstance.get(node.id);
        if (mi == null) return config;
        ObjectNode each = (ObjectNode) config.remove("forEach");
        ObjectNode forEach = json.object();
        forEach.put("itemsRef", "/input/" + mi.fieldKey());
        forEach.put("itemInput", each.path("itemInput").asString());
        forEach.put("maxItems", each.path("maxItems").asInt());
        forEach.put("mode", mi.sequential() ? "sequential" : "parallel");
        config.set("forEach", forEach);
        return config;
    }

    /** Transition condition of a branch path: one comparison, or a flat all/any group. Original operators are kept exactly. */
    private static JsonNode condition(Analysis a, Analysis.BranchEntry entry, Map<String, DecisionUse> decisionUses, Json json) {
        List<Map<String, Object>> terms = new ArrayList<>();
        for (Expressions.Comparison term : entry.terms()) {
            String ref = "/input/" + Analyzer.fieldKey(term.variable());
            String task = a.decisionResults.get(term.variable());
            if (task != null) {
                DecisionUse use = decisionUses.get(task);
                if (use == null || use.table.outputs().size() != 1)
                    throw SourceException.invalid("The result variable '" + term.variable() + "' needs a decision with exactly one output, but "
                            + (use == null ? "the business rule task '" + task + "' is not mapped." : "'" + use.table.decisionId() + "' has "
                            + use.table.outputs().size() + "."));
                DmnTable.Output output = use.table.outputs().get(0);
                String type = term.valueType();
                boolean numeric = Set.of("number", "integer").contains(output.type()) && type.equals("number");
                if (!numeric && !output.type().equals(type))
                    throw SourceException.invalid("The gateway compares '" + term.variable() + "' as " + type + ", but output '" + output.key()
                            + "' of decision '" + use.table.decisionId() + "' is " + output.type() + ".");
                ref = "/steps/" + a.nodes.get(task).canonicalId + "/" + output.key();
            }
            Map<String, Object> atom = new LinkedHashMap<>();
            atom.put("fieldRef", ref); atom.put("operator", term.op().canonical()); atom.put("value", term.literal());
            terms.add(atom);
        }
        return json.toTree(entry.compound() ? Map.of(entry.group(), terms) : terms.get(0));
    }

    public static JsonNode expandServiceGroups(Analysis analysis,JsonNode request,Json json) {
        rejectUnknownMappings(request.path("serviceGroupMappings"),analysis.serviceGroups.keySet(),"serviceGroupMappings");
        ObjectNode expanded=(ObjectNode)request.deepCopy(); ObjectNode tasks=json.object();
        request.path("serviceGroupMappings").properties().forEach(entry -> analysis.serviceGroups.get(entry.getKey()).forEach(task -> tasks.set(task,entry.getValue().deepCopy())));
        request.path("serviceTaskMappings").properties().forEach(entry -> tasks.set(entry.getKey(),entry.getValue().deepCopy()));
        expanded.set("serviceTaskMappings",tasks); return expanded;
    }
    private static void rejectUnknownMappings(JsonNode mappings, Set<String> known, String kind) {
        for (String key : mappings.propertyNames()) if (!known.contains(key))
            throw SourceException.invalid(kind + " references unknown source assignment/task '" + key + "'.");
    }
}
