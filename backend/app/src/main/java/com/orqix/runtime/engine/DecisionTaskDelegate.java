package com.orqix.runtime.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.dmn.api.DecisionExecutionAuditContainer;
import org.flowable.dmn.api.RuleExecutionAuditContainer;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;
import org.springframework.stereotype.Component;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.ConditionMatcher;
import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.DecisionTables;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunStore;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delegate {@code orqixDecisionTask} (S5 section 5): evaluates the release's DMN decision pinned to the process
 * definition's own deployment ({@code parentDeploymentId}) and stores the typed outputs in
 * {@code orqix_step_<nodeId>}.
 */
@Component("orqixDecisionTask")
public class DecisionTaskDelegate implements JavaDelegate {

    /** DMN input variable name used by the compiler for the tested field. */
    static final String INPUT_VARIABLE = "in1";

    private final Json json;
    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;

    public DecisionTaskDelegate(Json json, RunStore runs, RunEvents events, ReleaseQueries releases) {
        this.json = json;
        this.runs = runs;
        this.events = events;
        this.releases = releases;
    }

    @Override
    public void execute(DelegateExecution execution) {
        FlowElement element = execution.getCurrentFlowElement();
        ExtensionElement decision = OrqixModel.requireExtension(element, "decision");
        String decisionKey = OrqixModel.attribute(decision, "ref");
        String version = OrqixModel.attribute(decision, "version");
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);

        RunStore.RunRow run = runs.find(workspaceId, runId).orElseThrow();
        JsonNode table = releases.require(workspaceId, run.releaseId()).decisionTable(decisionKey);
        if (table == null) {
            throw new IllegalStateException("Decision table " + decisionKey + " not in release");
        }
        Map<String,Object> values=new LinkedHashMap<>();
        Map<String,Object> variables=new LinkedHashMap<>();
        java.util.Set<String> referenced=new java.util.TreeSet<>();
        for(JsonNode row:table.path("rows")) {
            JsonNode condition=row.path("when");
            if(condition.has("all")) for(JsonNode term:condition.path("all")) referenced.add(term.path("fieldRef").asString());
            else if(condition.has("any")) for(JsonNode term:condition.path("any")) referenced.add(term.path("fieldRef").asString());
            else referenced.add(condition.path("fieldRef").asString());
        }
        // Dated rows are judged by the day the request was submitted, so a replay or a migration never changes the answer.
        java.time.LocalDate asOf = null;
        for(JsonNode row:table.path("rows")) if(row.has("effective")) asOf = run.createdAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        if(asOf!=null) variables.put("asOf", asOf.getYear()*10000+asOf.getMonthValue()*100+asOf.getDayOfMonth());
        var fields=List.copyOf(referenced);
        for(int i=0;i<fields.size();i++) {
            Object value=ProcessData.requireScalar(execution,json,fields.get(i));
            values.put(fields.get(i),value); variables.put("in"+(i+1),value);
        }
        String tableJson=json.write(table);
        boolean collect="COLLECT".equals(table.path("hitPolicy").asString(""));
        Result result=evaluatePinnedVariables(execution.getProcessDefinitionId(),execution.getTenantId(),decisionKey,variables,
                DecisionTables.ruleOwners(tableJson),collect);
        // The canonical evaluation must select the same rows; under COLLECT it also gives the exact combined number.
        Map<String,Object> canonicalValues=new LinkedHashMap<>(values);
        if(asOf!=null) canonicalValues.put("$asOf",asOf);
        DecisionEvaluator.Result canonical=DecisionTables.evaluate(tableJson,canonicalValues);
        ObjectNode outputs=confirmedOutputs(decisionKey,result,canonical,collect);
        execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(outputs));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decisionKey", decisionKey);
        data.put("version", Integer.valueOf(version));
        data.put("matched", result.matched());
        data.put("outputs", outputs);
        data.put("evidence", evidenceValues(table, values, result.matched(), asOf));
        events.append(workspaceId, runId, "DECISION_EVALUATED", element.getId(), null, data);
    }

    /**
     * Outcome of a pinned DMN evaluation: {@code matched} is {@code r<n>} (1-based) or {@code default} (the trailing
     * catch-all rule after the canonical rows); under COLLECT every matching row, {@code r1,r3}, or {@code default}
     * when none matched. {@code rows} are the zero-based canonical rows behind {@code matched}.
     */
    public record Result(String matched, ObjectNode outputs, List<Integer> rows) {
        public Result(String matched, ObjectNode outputs) {
            this(matched, outputs, List.of());
        }
    }

    /**
     * The step's results after the pinned DMN result and the canonical evaluation agree on the selected rows. COLLECT
     * stores the canonical exact decimal, after checking the engine's double-precision combination is the same number
     * (the engine adds doubles; 0.1 + 0.2 must still give 0.3); with no match it stores the default.
     */
    ObjectNode confirmedOutputs(String decisionKey, Result result, DecisionEvaluator.Result canonical, boolean collect) {
        List<Integer> expected = canonical.matchedRows();
        if (!expected.equals(result.rows())) {
            throw new IllegalStateException("Pinned decision " + decisionKey + " selected rows " + result.rows()
                    + " but the canonical evaluation selects " + expected);
        }
        if (!collect) {
            return result.outputs();
        }
        ObjectNode outputs = json.object();
        canonical.output().forEach((name, value) -> {
            if (!canonical.usedDefault()) {
                BigDecimal exact = (BigDecimal) value;
                JsonNode engine = result.outputs().get(name);
                if (engine == null || !engine.isNumber()) {
                    throw new IllegalStateException("Pinned decision " + decisionKey + " returned no number for " + name);
                }
                BigDecimal tolerance = exact.abs().multiply(new BigDecimal("1E-12")).max(new BigDecimal("1E-9"));
                if (engine.decimalValue().subtract(exact).abs().compareTo(tolerance) > 0) {
                    throw new IllegalStateException("Pinned decision " + decisionKey + " combined " + engine.decimalValue()
                            + " but the canonical evaluation gives " + exact);
                }
            }
            outputs.set(name, json.fromScalar(value));
        });
        return outputs;
    }

    /**
     * Executes decision {@code decisionKey} from the DMN deployment whose parent is the process definition's
     * deployment. Refuses to fall back to another deployment (Flowable would otherwise use the latest version).
     */
    public Result evaluatePinned(String processDefinitionId, String tenantId, String decisionKey, Object value,
            int rowCount) {
        return evaluatePinnedVariables(processDefinitionId,tenantId,decisionKey,Map.of(INPUT_VARIABLE,value),rowCount);
    }

    public Result evaluatePinnedVariables(String processDefinitionId, String tenantId, String decisionKey,
            Map<String,Object> values, int rowCount) {
        return evaluatePinnedVariables(processDefinitionId, tenantId, decisionKey, values,
                java.util.stream.IntStream.range(0, rowCount).boxed().toList());
    }

    public Result evaluatePinnedVariables(String processDefinitionId, String tenantId, String decisionKey,
            Map<String,Object> values, List<Integer> owners) {
        return evaluatePinnedVariables(processDefinitionId, tenantId, decisionKey, values, owners, false);
    }

    /**
     * @param collect true for a COLLECT table: no catch-all rule, so no match means no result (the default applies),
     *                and every valid rule names a row that counts
     */
    public Result evaluatePinnedVariables(String processDefinitionId, String tenantId, String decisionKey,
            Map<String,Object> values, List<Integer> owners, boolean collect) {
        String deploymentId = ProcessDefinitionUtil.getProcessDefinition(processDefinitionId).getDeploymentId();
        long pinned = CommandContextUtil.getDmnRepositoryService().createDecisionQuery()
                .decisionKey(decisionKey).parentDeploymentId(deploymentId).decisionTenantId(tenantId).count();
        if (pinned != 1) {
            throw new IllegalStateException("Decision " + decisionKey + " is not deployed with " + deploymentId);
        }
        DecisionExecutionAuditContainer audit = CommandContextUtil.getDmnRuleService().createExecuteDecisionBuilder()
                .decisionKey(decisionKey)
                .parentDeploymentId(deploymentId)
                .tenantId(tenantId)
                .variables(values)
                .executeWithAuditTrail();
        if (Boolean.TRUE.equals(audit.isFailed())) {
            throw new IllegalStateException("Decision " + decisionKey + " failed: " + audit.getExceptionMessage());
        }
        List<Map<String, Object>> results = audit.getDecisionResult();
        if (collect) {
            List<Integer> rows = audit.getRuleExecutions().values().stream()
                    .filter(r -> Boolean.TRUE.equals(r.isValid()))
                    .map(r -> owners.get(r.getRuleNumber() - 1)).distinct().sorted().toList();
            ObjectNode outputs = json.object();
            if (results == null || results.isEmpty()) {
                if (!rows.isEmpty()) {
                    throw new IllegalStateException("Decision " + decisionKey + " matched rules but combined nothing");
                }
                return new Result("default", outputs, List.of());
            }
            if (results.size() != 1) {
                throw new IllegalStateException("Decision " + decisionKey + " must combine to exactly one row");
            }
            results.get(0).forEach((k, v) -> outputs.set(k, json.fromScalar(normalize(v))));
            return new Result(String.join(",", rows.stream().map(i -> "r" + (i + 1)).toList()), outputs, rows);
        }
        if (results == null || results.size() != 1) {
            throw new IllegalStateException("Decision " + decisionKey + " must yield exactly one row");
        }
        int matchedRule = audit.getRuleExecutions().values().stream()
                .filter(r -> Boolean.TRUE.equals(r.isValid()))
                .mapToInt(RuleExecutionAuditContainer::getRuleNumber).min()
                .orElseThrow(() -> new IllegalStateException("No rule matched in " + decisionKey));
        String matched = matchedRule > owners.size() ? "default" : "r" + (owners.get(matchedRule - 1) + 1);
        ObjectNode outputs = json.object();
        results.get(0).forEach((k, v) -> outputs.set(k, json.fromScalar(normalize(v))));
        return new Result(matched, outputs, matchedRule > owners.size() ? List.of() : List.of(owners.get(matchedRule - 1)));
    }

    /** All bounded canonical candidates are checked with the compiler's typed scalar matcher.
     * Selection is confirmed against the actual pinned DMN result, never substituted for it. */
    ObjectNode evidence(JsonNode table, String fieldRef, Object value, String selected) {
        return evidenceValues(table,Map.of(fieldRef,value),selected);
    }

    ObjectNode evidenceValues(JsonNode table, Map<String,Object> values, String selected) {
        return evidenceValues(table, values, selected, null);
    }

    ObjectNode evidenceValues(JsonNode table, Map<String,Object> values, String selected, java.time.LocalDate asOf) {
        JsonNode rows = table.path("rows");
        if (!rows.isArray() || rows.size() < 1 || rows.size() > 50)
            throw new IllegalStateException("Decision evidence requires 1..50 canonical rows");
        ObjectNode evidence = json.object();
        if (asOf != null) evidence.put("asOf", asOf.toString());
        var inputs=evidence.putArray("inputs");
        values.forEach((field,value)->inputs.add(json.objectOf(Map.of("fieldRef",field,"value",value))));
        var candidates = evidence.putArray("rows");
        java.util.List<String> matchedIds = new java.util.ArrayList<>();
        java.util.Set<String> selectedIds = "default".equals(selected) ? java.util.Set.of() : java.util.Set.of(selected.split(","));
        for (int i = 0; i < rows.size(); i++) {
            JsonNode condition = rows.get(i).path("when");
            boolean any=condition.has("any");
            var terms=condition.has("all")?condition.path("all"):any?condition.path("any"):json.object().putArray("terms").add(condition);
            boolean matched=!any;
            for(JsonNode term:terms) {
                String ref=term.path("fieldRef").asString();
                if(!values.containsKey(ref)) throw new IllegalStateException("Decision evidence input missing");
                String operator=term.path("operator").asString();
                boolean hit=ConditionMatcher.matches(operator,values.get(ref),ConditionMatcher.operand(operator,term.path("value")));
                matched = any ? matched | hit : matched & hit;
            }
            String id = "r" + (i + 1);
            JsonNode window = rows.get(i).path("effective");
            boolean inWindow = true;
            if (window.isObject()) {
                if (asOf == null) throw new IllegalStateException("Decision evidence for a dated row needs the submission date");
                inWindow = (!window.has("from") || !asOf.isBefore(java.time.LocalDate.parse(window.path("from").asString())))
                        && (!window.has("to") || !asOf.isAfter(java.time.LocalDate.parse(window.path("to").asString())));
                matched &= inWindow;
            }
            if (matched) matchedIds.add(id);
            ObjectNode row = json.object();
            row.put("rowId", id).put("matched", matched).put("selected", selectedIds.contains(id));
            if (window.isObject()) {
                row.put("inWindow", inWindow);
                row.set("effective", window.deepCopy());
            }
            row.set("condition", condition.deepCopy());
            candidates.add(row);
        }
        // The selection must follow from the candidate rows: every match under COLLECT, one of the matches under
        // PRIORITY (the highest ranked, confirmed by the canonical evaluation), the first match otherwise.
        String policy = table.path("hitPolicy").asString("UNIQUE");
        boolean consistent = switch (policy) {
            case "COLLECT" -> matchedIds.isEmpty() ? "default".equals(selected) : String.join(",", matchedIds).equals(selected);
            case "PRIORITY" -> matchedIds.isEmpty() ? "default".equals(selected) : matchedIds.contains(selected);
            default -> (matchedIds.isEmpty() ? "default" : matchedIds.get(0)).equals(selected);
        };
        if (!consistent) throw new IllegalStateException("Pinned decision result differs from canonical evidence");
        evidence.put("hitPolicy", policy);
        if (table.has("aggregation")) evidence.put("aggregation", table.path("aggregation").asString());
        evidence.put("defaultSelected", "default".equals(selected));
        return evidence;
    }

    private static Object normalize(Object value) {
        if (value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return value;
    }
}
