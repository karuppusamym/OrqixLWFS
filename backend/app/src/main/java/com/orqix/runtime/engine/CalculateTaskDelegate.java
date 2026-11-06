package com.orqix.runtime.engine;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.stereotype.Component;

import com.orqix.compiler.expr.Calculation;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delegate {@code orqixCalculate} of a Calculate step (CG-X01, docs/build/S137): evaluates the outputs with the Orqix
 * expression engine over the request and earlier step results, with the types the compiler pinned in the BPMN, and
 * stores them in {@code orqix_step_<nodeId>}. Pure: no call out, no I/O. {@code today()} uses the engine clock. An
 * output without a value (unless optional) or an exceeded bound fails the job, so the request needs attention.
 */
@Component("orqixCalculate")
public class CalculateTaskDelegate implements JavaDelegate {

    private final Json json;
    private final RunEvents events;

    public CalculateTaskDelegate(Json json, RunEvents events) {
        this.json = json;
        this.events = events;
    }

    static JsonNode base64(Json json, String value) {
        if (value == null) {
            throw new IllegalStateException("Generated BPMN without expression settings");
        }
        return json.parse(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
    }

    @Override
    public void execute(DelegateExecution execution) {
        FlowElement element = execution.getCurrentFlowElement();
        ExtensionElement calculate = OrqixModel.requireExtension(element, "calculate");
        JsonNode config = base64(json, OrqixModel.attribute(calculate, "configBase64"));
        JsonNode types = base64(json, OrqixModel.attribute(calculate, "typesBase64"));
        Instant now = CommandContextUtil.getProcessEngineConfiguration().getClock().getCurrentTime().toInstant();
        ObjectNode outputs;
        try {
            outputs = Calculation.run(config, types, ProcessData.input(execution, json),
                    id -> ProcessData.step(execution, json, id).orElse(null), now);
        } catch (Calculation.Failure f) {
            throw new IllegalStateException("EXPR_CALCULATION: " + element.getId() + " " + f.code() + ": " + f.getMessage());
        }
        execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(outputs));
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outputs", outputs);
        events.append(workspaceId, runId, "STEP_CALCULATED", element.getId(), null, data);
    }
}
