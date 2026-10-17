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
        var fields=List.copyOf(referenced);
        for(int i=0;i<fields.size();i++) {
            Object value=ProcessData.requireScalar(execution,json,fields.get(i));
            values.put(fields.get(i),value); variables.put("in"+(i+1),value);
        }
        Result result=evaluatePinnedVariables(execution.getProcessDefinitionId(),execution.getTenantId(),decisionKey,variables,ruleOwners(table));
        execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(result.outputs()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decisionKey", decisionKey);
        data.put("version", Integer.valueOf(version));
        data.put("matched", result.matched());
        data.put("outputs", result.outputs());
        data.put("evidence", evidenceValues(table, values, result.matched()));
        events.append(workspaceId, runId, "DECISION_EVALUATED", element.getId(), null, data);
    }

    /** Outcome of a pinned DMN evaluation: {@code matched} is {@code r<n>} (1-based) or {@code default}
     * (the trailing catch-all rule after the {@code rowCount} canonical rows). */
    public record Result(String matched, ObjectNode outputs) {
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

    /**
     * The canonical row behind each generated DMN rule: an OR row ({@code any}) occupies one rule per alternative, every
     * other row exactly one (mirrors the compiler's DMN generation).
     */
    static List<Integer> ruleOwners(JsonNode table) {
        List<Integer> owners = new java.util.ArrayList<>();
        JsonNode rows = table.path("rows");
        for (int i = 0; i < rows.size(); i++) {
            int rules = rows.get(i).path("when").has("any") ? rows.get(i).path("when").path("any").size() : 1;
            for (int k = 0; k < rules; k++) owners.add(i);
        }
        return owners;
    }

    public Result evaluatePinnedVariables(String processDefinitionId, String tenantId, String decisionKey,
            Map<String,Object> values, List<Integer> owners) {
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
        return new Result(matched, outputs);
    }

    /** All bounded canonical candidates are checked with the compiler's typed scalar matcher.
     * Selection is confirmed against the actual pinned DMN result, never substituted for it. */
    ObjectNode evidence(JsonNode table, String fieldRef, Object value, String selected) {
        return evidenceValues(table,Map.of(fieldRef,value),selected);
    }

    ObjectNode evidenceValues(JsonNode table, Map<String,Object> values, String selected) {
        JsonNode rows = table.path("rows");
        if (!rows.isArray() || rows.size() < 1 || rows.size() > 50)
            throw new IllegalStateException("Decision evidence requires 1..50 canonical rows");
        ObjectNode evidence = json.object();
        var inputs=evidence.putArray("inputs");
        values.forEach((field,value)->inputs.add(json.objectOf(Map.of("fieldRef",field,"value",value))));
        var candidates = evidence.putArray("rows");
        String expected = "default";
        for (int i = 0; i < rows.size(); i++) {
            JsonNode condition = rows.get(i).path("when");
            boolean any=condition.has("any");
            var terms=condition.has("all")?condition.path("all"):any?condition.path("any"):json.object().putArray("terms").add(condition);
            boolean matched=!any;
            for(JsonNode term:terms) {
                String ref=term.path("fieldRef").asString();
                if(!values.containsKey(ref)) throw new IllegalStateException("Decision evidence input missing");
                boolean hit=ConditionMatcher.matches(term.path("operator").asString(),values.get(ref),Json.scalar(term.path("value")));
                matched = any ? matched | hit : matched & hit;
            }
            String id = "r" + (i + 1);
            if (matched && "default".equals(expected)) expected = id;
            ObjectNode row = json.object();
            row.put("rowId", id).put("matched", matched).put("selected", id.equals(selected));
            row.set("condition", condition.deepCopy());
            candidates.add(row);
        }
        if (!expected.equals(selected)) throw new IllegalStateException("Pinned decision result differs from canonical evidence");
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
