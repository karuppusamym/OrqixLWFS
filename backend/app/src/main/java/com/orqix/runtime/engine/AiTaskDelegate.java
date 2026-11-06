package com.orqix.runtime.engine;

import java.math.BigDecimal;
import java.util.Set;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.event.FlowableActivityCancelledEvent;
import org.flowable.engine.impl.delegate.TriggerableJavaDelegate;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.AiTaskJobs;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delegate {@code orqixAiTask} of an AI task (docs/build/S134, CG-A01/A05). The service task is asynchronous and
 * triggerable: this delegate resolves the mapped values once, queues the AI job (input sealed) in the job transaction
 * and returns, so the engine waits at the step. The AI task worker runs the guardrail pipeline under a renewable lease
 * and triggers the step with the route; the generated gateway then takes it. When the engine cancels the step, the
 * open AI job is cancelled and its turn state deleted.
 */
@Component("orqixAiTask")
public class AiTaskDelegate implements JavaDelegate, TriggerableJavaDelegate, FlowableEventListener {

    private final AiTaskJobs jobs;
    private final Json json;

    private final OrqixForEach forEach;

    public AiTaskDelegate(@org.springframework.context.annotation.Lazy AiTaskJobs jobs, Json json,
            @org.springframework.context.annotation.Lazy OrqixForEach forEach) {
        this.jobs = jobs;
        this.json = json;
        this.forEach = forEach;
    }

    @Override
    public void execute(DelegateExecution execution) {
        FlowElement element = execution.getCurrentFlowElement();
        var ai = OrqixModel.requireExtension(element, "aiTask");
        ObjectNode input = json.object();
        OrqixModel.childMap(ai, "input", "fieldRef").forEach((name, ref) -> {
            JsonNode value = ProcessData.resolve(execution, json, ref);
            if (!value.isMissingNode() && !value.isNull()) {
                input.set(name, value);
            }
        });
        String nodeId = execution.getCurrentActivityId();
        boolean needsReview = false;
        // Shadow tasks have no route gateway: they continue straight to their next step.
        FlowElement gateway = OrqixModel.model(execution.getProcessDefinitionId()).getFlowElement(nodeId + "__aiRoute");
        if (gateway instanceof org.flowable.bpmn.model.FlowNode node) {
            for (SequenceFlow flow : node.getOutgoingFlows()) {
                if (flow.getConditionExpression() != null && flow.getConditionExpression().contains("'needsReview'")) {
                    needsReview = true;
                }
            }
        }
        String hash = OrqixModel.attribute(ai, "contentHash");
        jobs.publish(new AiTaskJobs.Spec(ProcessData.workspaceId(execution), ProcessData.runId(execution), nodeId,
                OrqixModel.attribute(ai, "skill"), Integer.parseInt(OrqixModel.attribute(ai, "skillVersion")), hash,
                OrqixModel.attribute(ai, "mode"), OrqixModel.attribute(ai, "autonomy"),
                new BigDecimal(OrqixModel.attribute(ai, "minConfidence")),
                Integer.parseInt(OrqixModel.attribute(ai, "reviewSamplePercent")),
                OrqixModel.childMap(ai, "redaction", "rule"), needsReview, input));
    }

    @Override
    public void trigger(Context context) {
        // The AI task worker set the route and the outputs; the generated gateway takes the route. A for-each item
        // (S138) keeps its outputs for the collected lists; an item succeeded when AI decided it.
        DelegateExecution execution = context.getExecution();
        Object result = execution.getVariable(ProcessData.stepVariable(execution.getCurrentActivityId()));
        if (result != null) {
            JsonNode outputs = json.parse(result.toString());
            forEach.record(execution, execution.getCurrentActivityId(), outputs,
                    "decided".equals(outputs.path("status").asString(null)));
        }
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() != FlowableEngineEventType.ACTIVITY_CANCELLED
                || !(event instanceof FlowableActivityCancelledEvent cancelled)
                || cancelled.getProcessDefinitionId() == null || cancelled.getActivityId() == null) {
            return;
        }
        FlowElement element = OrqixModel.model(cancelled.getProcessDefinitionId()).getFlowElement(cancelled.getActivityId());
        if (!(element instanceof ServiceTask) || OrqixModel.extension(element, "aiTask").isEmpty()) {
            return;
        }
        ExecutionEntity process = CommandContextUtil.getExecutionEntityManager().findById(cancelled.getProcessInstanceId());
        if (process == null || process.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        jobs.cancelOpen(ProcessData.workspaceId(process), ProcessData.runId(process), cancelled.getActivityId());
    }

    @Override
    public Set<? extends FlowableEventType> getTypes() {
        return Set.of(FlowableEngineEventType.ACTIVITY_CANCELLED);
    }

    @Override
    public boolean isFailOnException() {
        return true;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
