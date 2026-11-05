package com.orqix.runtime.engine;

import java.util.Set;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.event.FlowableActivityCancelledEvent;
import org.flowable.engine.impl.delegate.TriggerableJavaDelegate;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.ExternalJobService;
import com.orqix.shared.json.Json;

/**
 * Publishes the job of a "Work by an external worker" step (docs/build/S43). The service task is triggerable, so the
 * engine waits at the step until a worker's completion triggers it. A worker business error (S132, CG-E10) triggers
 * it with {@link #raiseVariable(String)} set: the trigger then throws that BPMN error, which the step's own failure
 * boundary ({@code ORQIX_STEP_FAILED}) or its section's error boundary catches in the same command. When the engine
 * cancels the step (a section's time limit, another step's error, the request ending), the open job is cancelled so
 * no worker can lease or complete it any more.
 */
@Component("orqixExternalTask")
public class ExternalTaskDelegate implements JavaDelegate, TriggerableJavaDelegate, FlowableEventListener {

    /** Variable prefix carrying the BPMN error code a worker business error raises at a step. */
    static final String RAISE_PREFIX = "orqix_raise_";

    private final ExternalJobService jobs;
    private final Json json;

    public ExternalTaskDelegate(@org.springframework.context.annotation.Lazy ExternalJobService jobs, Json json) {
        this.jobs = jobs;
        this.json = json;
    }

    /** @return the process variable that tells the trigger of this step to raise a BPMN error instead of leaving */
    public static String raiseVariable(String nodeId) {
        return RAISE_PREFIX + nodeId;
    }

    @Override
    public void execute(DelegateExecution execution) {
        var external = OrqixModel.requireExtension(execution.getCurrentFlowElement(), "external");
        var input = json.object();
        OrqixModel.childMap(external, "input", "fieldRef").forEach((name, ref) -> {
            var value = ProcessData.resolve(execution, json, ref);
            if (value.isMissingNode() || value.isNull()) {
                throw new IllegalStateException("A value for the worker is unavailable: " + ref);
            }
            input.set(name, value);
        });
        jobs.publish(ProcessData.workspaceId(execution), ProcessData.runId(execution), execution.getCurrentActivityId(),
                OrqixModel.attribute(external, "topic"), input,
                Integer.parseInt(OrqixModel.attribute(external, "leaseSeconds")),
                Integer.parseInt(OrqixModel.attribute(external, "maxAttempts")),
                Integer.parseInt(OrqixModel.attribute(external, "retryDelaySeconds")));
    }

    @Override
    public void trigger(Context context) {
        DelegateExecution execution = context.getExecution();
        String variable = raiseVariable(execution.getCurrentActivityId());
        Object code = execution.getVariable(variable);
        if (!(code instanceof String error)) {
            return; // a completion: leave the step normally
        }
        execution.removeVariable(variable);
        if (!"ORQIX_STEP_FAILED".equals(error)) {
            // The section's error boundary cancels this step in the same command; it already shows as having raised
            // the error, not as interrupted.
            RuntimeProjectionListener.markInterrupted(execution.getProcessInstanceId(), execution.getCurrentActivityId());
        }
        throw new BpmnError(error);
    }

    // ---------------------------------------------------------------- cancellation of the open job

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() != FlowableEngineEventType.ACTIVITY_CANCELLED
                || !(event instanceof FlowableActivityCancelledEvent cancelled)
                || cancelled.getProcessDefinitionId() == null || cancelled.getActivityId() == null) {
            return;
        }
        FlowElement element = OrqixModel.model(cancelled.getProcessDefinitionId()).getFlowElement(cancelled.getActivityId());
        if (!(element instanceof ServiceTask) || OrqixModel.extension(element, "external").isEmpty()) {
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
