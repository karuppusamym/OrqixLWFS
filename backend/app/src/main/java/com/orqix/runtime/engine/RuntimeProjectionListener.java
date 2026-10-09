package com.orqix.runtime.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.stereotype.Component;

import com.orqix.catalog.ReleaseQueries;
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

    private final Json json;
    private final RunStore runs;
    private final TaskStore tasks;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final RuntimeMetrics metrics;
    private final Clock clock;

    public RuntimeProjectionListener(Json json, RunStore runs, TaskStore tasks, RunEvents events,
            ReleaseQueries releases, RuntimeMetrics metrics, Clock clock) {
        this.json = json;
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.releases = releases;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Set<? extends FlowableEventType> getTypes() {
        return Set.of(FlowableEngineEventType.TASK_CREATED, FlowableEngineEventType.ENTITY_CREATED,
                FlowableEngineEventType.ACTIVITY_STARTED, FlowableEngineEventType.PROCESS_COMPLETED);
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
                if (event instanceof FlowableEntityEvent entityEvent && entityEvent.getEntity() instanceof JobEntity job) {
                    onJobCreated(job);
                }
            }
            case ACTIVITY_STARTED -> {
                if (event instanceof FlowableActivityEvent activityEvent) {
                    onActivityStarted(activityEvent);
                }
            }
            case PROCESS_COMPLETED -> {
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
            m.addStep(step);
            if ((RunStore.RUNNING.equals(m.lifecycle) || RunStore.WAITING.equals(m.lifecycle)) && !m.hasStepOfKind("http")) {
                m.lifecycle = RunStore.WAITING;
            }
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
            if (RunStore.RUNNING.equals(m.lifecycle) || RunStore.WAITING.equals(m.lifecycle)) {
                m.lifecycle = RunStore.RUNNING;
            }
        });
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
    }

    private static boolean isOrqixProcess(FlowableEvent event) {
        String processInstanceId = null;
        if (event instanceof FlowableEntityEvent entityEvent) {
            Object entity = entityEvent.getEntity();
            if (entity instanceof TaskEntity task) {
                processInstanceId = task.getProcessInstanceId();
            } else if (entity instanceof JobEntity job) {
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
