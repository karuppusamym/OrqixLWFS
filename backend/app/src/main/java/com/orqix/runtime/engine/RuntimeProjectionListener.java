package com.orqix.runtime.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.job.service.impl.persistence.entity.AbstractRuntimeJobEntity;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.flowable.job.service.impl.persistence.entity.TimerJobEntity;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.stereotype.Component;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.runtime.internal.ChildRuns;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunStore;
import com.orqix.runtime.internal.RuntimeMetrics;
import com.orqix.runtime.internal.TaskStore;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Engine-wide projection listener (S5 section 2, BD-22). Runs synchronously inside the engine command and therefore
 * inside the caller's Spring transaction; a failed write fails the command ({@link #isFailOnException()}).
 */
@Component
public class RuntimeProjectionListener implements FlowableEventListener {

    private static final String END_ATTRIBUTE = "orqix.reachedEnd.";
    /** Command attribute: deadline of the time limit of an approval, keyed by process instance and approval step. */
    private static final String TIMEOUT_ATTRIBUTE = "orqix.timeout.";

    private final Json json;
    private final RunStore runs;
    private final TaskStore tasks;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final RuntimeMetrics metrics;
    private final ChildRuns children;
    private final Clock clock;

    public RuntimeProjectionListener(Json json, RunStore runs, TaskStore tasks, RunEvents events,
            ReleaseQueries releases, RuntimeMetrics metrics, @org.springframework.context.annotation.Lazy ChildRuns children,
            Clock clock) {
        this.json = json;
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.releases = releases;
        this.metrics = metrics;
        this.children = children;
        this.clock = clock;
    }

    @Override
    public Set<? extends FlowableEventType> getTypes() {
        return Set.of(FlowableEngineEventType.TASK_CREATED, FlowableEngineEventType.ENTITY_CREATED,
                FlowableEngineEventType.ACTIVITY_STARTED, FlowableEngineEventType.PROCESS_COMPLETED,
                FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT, FlowableEngineEventType.TIMER_FIRED);
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event.getType() instanceof FlowableEngineEventType type)) {
            return;
        }
        if (!isOrqixProcess(event)) {
            // Every process deployed by Orqix carries orqix_runId; anything else (engine spikes) is not projected.
            return;
        }
        switch (type) {
            case TASK_CREATED -> {
                if (event instanceof FlowableEntityEvent entityEvent && entityEvent.getEntity() instanceof TaskEntity task) {
                    onTaskCreated(task);
                }
            }
            case ENTITY_CREATED -> {
                if (event instanceof FlowableEntityEvent entityEvent) {
                    if (entityEvent.getEntity() instanceof JobEntity job) {
                        onJobCreated(job);
                    } else if (entityEvent.getEntity() instanceof TimerJobEntity timer) {
                        onTimerCreated(timer);
                    }
                }
            }
            case TIMER_FIRED -> {
                if (event instanceof FlowableEntityEvent entityEvent && entityEvent.getEntity() instanceof JobEntity job) {
                    onTimerFired(job);
                }
            }
            case ACTIVITY_STARTED -> {
                if (event instanceof FlowableActivityEvent activityEvent) {
                    onActivityStarted(activityEvent);
                }
            }
            case PROCESS_COMPLETED, PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT -> {
                if (event instanceof FlowableEntityEvent entityEvent
                        && entityEvent.getEntity() instanceof ExecutionEntity processInstance) {
                    onProcessCompleted(processInstance);
                }
            }
            default -> {
                // not projected
            }
        }
    }

    private void onTaskCreated(TaskEntity task) {
        FlowElement element = OrqixModel.flowElement(task.getProcessDefinitionId(), task.getTaskDefinitionKey());
        if (!(element instanceof UserTask userTask)) {
            return;
        }
        UUID workspaceId = ProcessData.workspaceId(task);
        UUID runId = ProcessData.runId(task);
        List<String> groups = userTask.getCandidateGroups();
        if (groups == null || groups.size() != 1) {
            throw new IllegalStateException("Approval " + element.getId() + " must have exactly one candidate group");
        }
        String group = groups.get(0);
        Instant now = clock.instant();
        Instant dueAt = OrqixModel.extension(element, "approval")
                .map(approval -> OrqixModel.attribute(approval, "dueAfterHours"))
                .filter(v -> v != null && !v.isBlank())
                .map(hours -> now.plus(Duration.ofHours(Long.parseLong(hours))))
                .orElse(null);
        String label = element.getName() == null ? element.getId() : element.getName();
        // The timer of an approval time limit was created just before the task (same command): S12 section 3.
        Object timesOut = Context.getCommandContext().getAttribute(TIMEOUT_ATTRIBUTE + task.getProcessInstanceId() + "."
                + element.getId());

        tasks.insert(task.getId(), workspaceId, runId, element.getId(), label, group, dueAt, now);
        runs.mutate(workspaceId, runId, m -> {
            ObjectNode step = json.object();
            step.put("nodeId", element.getId());
            step.put("label", label);
            step.put("kind", "approval");
            step.put("since", now.toString());
            step.put("candidateGroup", group);
            step.putNull("assigneeId");
            if (dueAt == null) {
                step.putNull("dueAt");
            } else {
                step.put("dueAt", dueAt.toString());
            }
            step.put("taskId", task.getId());
            if (timesOut instanceof Instant at) {
                step.put("timesOutAt", at.toString());
            }
            m.addStep(step);
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("candidateGroup", group);
        data.put("dueAt", dueAt == null ? null : dueAt.toString());
        events.append(workspaceId, runId, "TASK_CREATED", element.getId(), null, data);
    }

    private void onJobCreated(JobEntity job) {
        if (job.getProcessDefinitionId() == null || job.getElementId() == null || job.getExecutionId() == null) {
            return;
        }
        FlowElement element = OrqixModel.flowElement(job.getProcessDefinitionId(), job.getElementId());
        if (OrqixModel.extension(element, "http").isEmpty()) {
            return;
        }
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager().findById(job.getExecutionId());
        if (execution == null) {
            return;
        }
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        String label = element.getName() == null ? element.getId() : element.getName();
        Instant now = clock.instant();
        runs.mutate(workspaceId, runId, m -> {
            ObjectNode step = json.object();
            step.put("nodeId", element.getId());
            step.put("label", label);
            step.put("kind", "http");
            step.put("since", now.toString());
            step.putNull("candidateGroup");
            step.putNull("assigneeId");
            step.putNull("dueAt");
            m.addStep(step);
            m.settle();
        });
    }

    // ---------------------------------------------------------------- timers (S12 section 3)

    /** A timer job was created: the wait of a "Wait" step, or the time limit of an approval. */
    private void onTimerCreated(TimerJobEntity timer) {
        if (timer.getProcessDefinitionId() == null || timer.getElementId() == null || timer.getProcessInstanceId() == null) {
            return;
        }
        FlowElement element = OrqixModel.flowElement(timer.getProcessDefinitionId(), timer.getElementId());
        Instant due = timer.getDuedate() == null ? null : timer.getDuedate().toInstant();
        if (element instanceof BoundaryEvent boundary && OrqixModel.extension(element, "timeout").isPresent()) {
            // The task is created next; it picks the deadline up (or, if it already exists, is updated here).
            Context.getCommandContext().addAttribute(TIMEOUT_ATTRIBUTE + timer.getProcessInstanceId() + "."
                    + boundary.getAttachedToRefId(), due);
            return;
        }
        if (OrqixModel.extension(element, "wait").isEmpty()) {
            return;
        }
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager().findById(timer.getExecutionId());
        if (execution == null || execution.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        String label = element.getName() == null ? element.getId() : element.getName();
        Instant now = clock.instant();
        boolean[] started = {false};
        runs.mutate(workspaceId, runId, m -> {
            started[0] = !m.hasStep(element.getId());
            ObjectNode step = json.object();
            step.put("nodeId", element.getId());
            step.put("label", label);
            step.put("kind", "wait");
            step.put("since", now.toString());
            step.putNull("candidateGroup");
            step.putNull("assigneeId");
            if (due == null) {
                step.putNull("dueAt");
            } else {
                step.put("dueAt", due.toString());
            }
            m.addStep(step);
            m.settle();
        });
        if (!started[0]) {
            return; // a retry timer of the same wait: already shown
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("duration", OrqixModel.attribute(OrqixModel.requireExtension(element, "wait"), "duration"));
        data.put("until", due == null ? null : due.toString());
        events.append(workspaceId, runId, "WAIT_STARTED", element.getId(), null, data);
    }

    /**
     * A timer job runs (inside its own job transaction, before the engine continues past the timer): the wait ended,
     * or nobody decided an approval in time and its task is cancelled.
     */
    private void onTimerFired(JobEntity job) {
        if (job.getProcessDefinitionId() == null || job.getElementId() == null || job.getExecutionId() == null) {
            return;
        }
        FlowElement element = OrqixModel.flowElement(job.getProcessDefinitionId(), job.getElementId());
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager().findById(job.getExecutionId());
        if (execution == null || execution.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        Instant now = clock.instant();
        String label = element.getName() == null ? element.getId() : element.getName();
        if (element instanceof BoundaryEvent boundary && OrqixModel.extension(element, "timeout").isPresent()) {
            String approvalId = boundary.getAttachedToRefId();
            FlowElement approval = OrqixModel.flowElement(job.getProcessDefinitionId(), approvalId);
            String approvalLabel = approval.getName() == null ? approvalId : approval.getName();
            tasks.cancelOpenForNode(workspaceId, runId, approvalId, now);
            runs.mutate(workspaceId, runId, m -> {
                m.removeStep(approvalId);
                m.settle();
            });
            ObjectNode output = json.object();
            output.put("decision", "timedOut");
            output.put("decidedAt", now.toString());
            execution.setVariable(ProcessData.stepVariable(approvalId), json.write(output));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("label", approvalLabel);
            data.put("after", OrqixModel.attribute(OrqixModel.requireExtension(element, "timeout"), "after"));
            events.append(workspaceId, runId, "TASK_TIMED_OUT", approvalId, null, data);
        } else if (OrqixModel.extension(element, "wait").isPresent()) {
            runs.mutate(workspaceId, runId, m -> {
                m.removeStep(element.getId());
                m.settle();
            });
            events.append(workspaceId, runId, "WAIT_ENDED", element.getId(), null, Map.of("label", label));
        }
    }

    private void onActivityStarted(FlowableActivityEvent event) {
        if (event.getProcessDefinitionId() == null || event.getActivityId() == null) {
            return;
        }
        FlowElement element = OrqixModel.flowElement(event.getProcessDefinitionId(), event.getActivityId());
        if (!(element instanceof EndEvent)) {
            return;
        }
        ExtensionElement outcome = OrqixModel.requireExtension(element, "outcome");
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager().findById(event.getExecutionId());
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        RunStore.RunRow run = runs.find(workspaceId, runId).orElseThrow();
        JsonNode mapping = releases.require(workspaceId, run.releaseId()).definition().path("outputMapping");
        ObjectNode output = json.object();
        for (Map.Entry<String, JsonNode> entry : mapping.properties()) {
            JsonNode value = ProcessData.resolve(execution, json, entry.getValue().path("fieldRef").asString());
            if (!value.isMissingNode() && !value.isNull()) {
                output.set(entry.getKey(), value);
            } else if (entry.getValue().path("required").asBoolean(false)) {
                throw new IllegalStateException("Required output " + entry.getKey() + " is missing");
            }
        }
        CommandContext context = Context.getCommandContext();
        context.addAttribute(END_ATTRIBUTE + event.getProcessInstanceId(), new ReachedEnd(workspaceId, runId,
                element.getId(), OrqixModel.attribute(outcome, "label"), OrqixModel.attribute(outcome, "kind"), output));
    }

    private void onProcessCompleted(ExecutionEntity processInstance) {
        CommandContext context = Context.getCommandContext();
        Object attribute = context.getAttribute(END_ATTRIBUTE + processInstance.getId());
        if (!(attribute instanceof ReachedEnd end)) {
            throw new IllegalStateException("Process " + processInstance.getId() + " completed without an Orqix end");
        }
        Instant now = clock.instant();
        RunStore.RunRow before = runs.find(end.workspaceId(), end.runId()).orElseThrow();
        // A rejection ends the whole request: tasks of branches that were still open are cancelled with it (S12).
        tasks.cancelOpenForRun(end.workspaceId(), end.runId(), now);
        runs.mutate(end.workspaceId(), end.runId(), m -> {
            m.lifecycle = RunStore.COMPLETED;
            m.outcome = end.label();
            m.outcomeKind = end.kind();
            m.output = end.output();
            m.attention = null;
            m.endedAt = now;
            m.steps.removeAll();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", end.label());
        data.put("outcomeKind", end.kind());
        events.append(end.workspaceId(), end.runId(), "RUN_COMPLETED", end.nodeId(), null, data);
        metrics.runCompleted(end.kind());
        // Child runs still running when their parent ends (a rejection in another branch) end with it; a child that
        // ends tells its parent through an asynchronous trigger job (S12 section 4).
        children.cancelActiveChildren(before, "The request that started this one has ended.");
        children.onRunEnded(runs.find(end.workspaceId(), end.runId()).orElseThrow());
    }

    private static boolean isOrqixProcess(FlowableEvent event) {
        String processInstanceId = null;
        if (event instanceof FlowableEntityEvent entityEvent) {
            Object entity = entityEvent.getEntity();
            if (entity instanceof TaskEntity task) {
                processInstanceId = task.getProcessInstanceId();
            } else if (entity instanceof AbstractRuntimeJobEntity job) {
                processInstanceId = job.getProcessInstanceId();
            } else if (entity instanceof ExecutionEntity execution) {
                // PROCESS_COMPLETED: variables are already deleted; the reached Orqix end was recorded at its start.
                return Context.getCommandContext().getAttribute(END_ATTRIBUTE + execution.getProcessInstanceId()) != null;
            }
        } else if (event instanceof FlowableActivityEvent activityEvent) {
            processInstanceId = activityEvent.getProcessInstanceId();
        }
        if (processInstanceId == null) {
            return false;
        }
        ExecutionEntity processInstance = CommandContextUtil.getExecutionEntityManager().findById(processInstanceId);
        return processInstance != null && processInstance.getVariable(ProcessData.RUN_ID) != null;
    }

    private record ReachedEnd(UUID workspaceId, UUID runId, String nodeId, String label, String kind, ObjectNode output) {
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
