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
import org.flowable.bpmn.model.IntermediateCatchEvent;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.ThrowEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.delegate.event.FlowableActivityCancelledEvent;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.delegate.event.FlowableErrorEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.eventsubscription.service.impl.persistence.entity.EventSubscriptionEntity;
import org.flowable.job.service.impl.persistence.entity.AbstractRuntimeJobEntity;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.flowable.job.service.impl.persistence.entity.TimerJobEntity;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.stereotype.Component;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.runtime.internal.ChildRuns;
import com.orqix.runtime.internal.MessageWaits;
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
    /** Command attribute: deadline of the time limit of a message wait, keyed by process instance and step (S16). */
    private static final String MESSAGE_TIMEOUT_ATTRIBUTE = "orqix.messageTimeout.";

    private final Json json;
    private final RunStore runs;
    private final TaskStore tasks;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final RuntimeMetrics metrics;
    private final ChildRuns children;
    private final MessageWaits messageWaits;
    private final Clock clock;
    private final com.orqix.runtime.internal.RunStages stages;
    private final com.orqix.runtime.internal.TaskDispatcher dispatcher;

    public RuntimeProjectionListener(Json json, RunStore runs, TaskStore tasks, RunEvents events,
            ReleaseQueries releases, RuntimeMetrics metrics, @org.springframework.context.annotation.Lazy ChildRuns children,
            @org.springframework.context.annotation.Lazy MessageWaits messageWaits, Clock clock,
            @org.springframework.context.annotation.Lazy com.orqix.runtime.internal.RunStages stages,
            @org.springframework.context.annotation.Lazy com.orqix.runtime.internal.TaskDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.stages = stages;
        this.json = json;
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.releases = releases;
        this.metrics = metrics;
        this.children = children;
        this.messageWaits = messageWaits;
        this.clock = clock;
    }

    @Override
    public Set<? extends FlowableEventType> getTypes() {
        return Set.of(FlowableEngineEventType.TASK_CREATED, FlowableEngineEventType.ENTITY_CREATED,
                FlowableEngineEventType.ACTIVITY_STARTED, FlowableEngineEventType.PROCESS_COMPLETED,
                FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT, FlowableEngineEventType.TIMER_FIRED,
                FlowableEngineEventType.ACTIVITY_COMPLETED, FlowableEngineEventType.ACTIVITY_CANCELLED,
                FlowableEngineEventType.ACTIVITY_ERROR_RECEIVED);
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event.getType() instanceof FlowableEngineEventType type)) {
            return;
        }
        boolean selfChecked = type == FlowableEngineEventType.ACTIVITY_COMPLETED
                || type == FlowableEngineEventType.ACTIVITY_CANCELLED || type == FlowableEngineEventType.ACTIVITY_ERROR_RECEIVED;
        // The S55 handlers filter on the element first and check the run themselves (they fire for every activity).
        if (!selfChecked && !isOrqixProcess(event)) {
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
                    } else if (entityEvent.getEntity() instanceof EventSubscriptionEntity subscription) {
                        onSubscriptionCreated(subscription);
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
            case ACTIVITY_COMPLETED -> {
                if (event instanceof FlowableActivityEvent activityEvent) {
                    onActivityCompleted(activityEvent);
                }
            }
            case ACTIVITY_CANCELLED -> {
                if (event instanceof FlowableActivityCancelledEvent cancelled) {
                    onActivityCancelled(cancelled);
                }
            }
            case ACTIVITY_ERROR_RECEIVED -> {
                if (event instanceof FlowableErrorEvent error) {
                    onErrorReceived(error);
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
                .filter(approval -> OrqixModel.attribute(approval,"dueAfterHours") != null || OrqixModel.attribute(approval,"dueAfterMinutes") != null)
                .map(approval -> {
                    String minuteValue = OrqixModel.attribute(approval,"dueAfterMinutes");
                    int minutes = minuteValue != null ? Integer.parseInt(minuteValue) : Integer.parseInt(OrqixModel.attribute(approval,"dueAfterHours")) * 60;
                    String calendar = OrqixModel.attribute(approval,"calendar");
                    if (calendar == null) return now.plus(Duration.ofMinutes(minutes));
                    JsonNode configured = json.parse(calendar);
                    var working = com.orqix.compiler.BusinessCalendar.read(configured);
                    Instant deadline = working.deadlineMinutes(now, minutes);
                    if (configured.has("validFrom")) {
                        var from = java.time.LocalDate.parse(configured.path("validFrom").asString());
                        var today = now.atZone(working.zone()).toLocalDate();
                        if (today.isBefore(from) || !deadline.atZone(working.zone()).toLocalDate().isBefore(from.plusYears(10)))
                            throw new IllegalArgumentException("The task exceeds its pinned calendar's ten-year scheduling horizon.");
                    }
                    return deadline;
                })
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
        dispatch(task, element.getId(), workspaceId, runId);
    }

    /** Optional routing of the new task to one eligible person when the approval step declares a dispatch policy (S39). */
    private void dispatch(TaskEntity task, String nodeId, UUID workspaceId, UUID runId) {
        var run = runs.find(workspaceId, runId).orElse(null);
        if (run == null) {
            return;
        }
        var node = releases.require(workspaceId, run.releaseId()).node(nodeId);
        String policy = node == null ? null : node.path("config").path("dispatch").path("policy").asString(null);
        if (policy == null) {
            return;
        }
        dispatcher.choose(workspaceId, task.getId(), policy).ifPresent(choice -> {
            if (dispatcher.assign(workspaceId, task.getId(), choice)) {
                task.setAssignee(choice.subjectId().toString());
                runs.mutate(workspaceId, runId, m -> m.updateStep(nodeId,
                        step -> step.put("assigneeId", choice.subjectId().toString())));
                events.append(workspaceId, runId, "TASK_DISPATCHED", nodeId, null, choice.eventData());
            }
        });
    }

    private void onJobCreated(JobEntity job) {
        if (job.getProcessDefinitionId() == null || job.getElementId() == null || job.getExecutionId() == null) {
            return;
        }
        FlowElement element = OrqixModel.flowElement(job.getProcessDefinitionId(), job.getElementId());
        if (OrqixModel.extension(element, "http").isEmpty() && OrqixModel.extension(element, "generateDocument").isEmpty()
                && OrqixModel.extension(element, "compensation").isEmpty() && OrqixModel.extension(element, "adapter").isEmpty()) {
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
            step.put("kind", OrqixModel.extension(element, "generateDocument").isPresent() ? "generateDocument"
                    : OrqixModel.extension(element, "compensation").isPresent() ? "compensate"
                    : OrqixModel.extension(element, "adapter").isPresent() ? "adapter" : "http");
            step.put("since", now.toString());
            step.putNull("candidateGroup");
            step.putNull("assigneeId");
            step.putNull("dueAt");
            m.addStep(step);
            m.settle();
        });
    }

    // ---------------------------------------------------------------- sections, handlers, errors, signals (S55)

    /** Command attribute: the handler started by an error in this process instance (its interruption has no cause). */
    private static final String ERROR_HANDLER_ATTRIBUTE = "orqix.errorHandler.";
    /** Command attribute: a step already shown as interrupted in this command. */
    private static final String INTERRUPTED_ATTRIBUTE = "orqix.interrupted.";

    private static String label(FlowElement element) {
        return element.getName() == null ? element.getId() : element.getName();
    }

    /** A section is entered, or a signal is sent. */
    private void scopeStarted(FlowableActivityEvent event, FlowElement element) {
        ExtensionElement scope = OrqixModel.extension(element, "scope").orElse(null);
        ExtensionElement signal = OrqixModel.extension(element, "signal").orElse(null);
        boolean section = scope != null && "embedded".equals(OrqixModel.attribute(scope, "kind"));
        boolean send = signal != null && "send".equals(OrqixModel.attribute(signal, "action"));
        if (!section && !send) {
            return;
        }
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager().findById(event.getExecutionId());
        if (execution == null || execution.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label(element));
        if (section) {
            events.append(ProcessData.workspaceId(execution), ProcessData.runId(execution), "SECTION_ENTERED",
                    element.getId(), null, data);
        } else {
            data.put("signal", OrqixModel.attribute(signal, "name"));
            events.append(ProcessData.workspaceId(execution), ProcessData.runId(execution), "SIGNAL_SENT",
                    element.getId(), null, data);
        }
    }

    /** A handler started (its start event was left), a signal wait ended or a section finished normally. */
    private void onActivityCompleted(FlowableActivityEvent event) {
        if (event.getProcessDefinitionId() == null || event.getActivityId() == null) {
            return;
        }
        FlowElement element = OrqixModel.model(event.getProcessDefinitionId()).getFlowElement(event.getActivityId());
        if (element == null) {
            return;
        }
        ExtensionElement handler = OrqixModel.extension(element, "scopeEvent").orElse(null);
        ExtensionElement signal = OrqixModel.extension(element, "signal").orElse(null);
        ExtensionElement scope = OrqixModel.extension(element, "scope").orElse(null);
        boolean waited = signal != null && "wait".equals(OrqixModel.attribute(signal, "action"));
        if (handler == null && !waited && scope == null) {
            return;
        }
        ExecutionEntity process = CommandContextUtil.getExecutionEntityManager().findById(event.getProcessInstanceId());
        if (process == null || process.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        UUID workspaceId = ProcessData.workspaceId(process);
        UUID runId = ProcessData.runId(process);
        Map<String, Object> data = new LinkedHashMap<>();
        if (handler != null) {
            String scopeId = OrqixModel.attribute(handler, "scope");
            FlowElement subprocess = OrqixModel.flowElement(event.getProcessDefinitionId(), scopeId);
            data.put("label", label(subprocess));
            data.put("trigger", OrqixModel.attribute(handler, "trigger"));
            data.put("interrupting", Boolean.parseBoolean(OrqixModel.attribute(handler, "interrupting")));
            events.append(workspaceId, runId, "HANDLER_STARTED", scopeId, null, data);
        } else if (waited) {
            runs.mutate(workspaceId, runId, m -> {
                m.removeStep(element.getId());
                m.settle();
            });
            data.put("label", label(element));
            data.put("signal", OrqixModel.attribute(signal, "name"));
            events.append(workspaceId, runId, "SIGNAL_RECEIVED", element.getId(), null, data);
        } else if ("embedded".equals(OrqixModel.attribute(scope, "kind"))) {
            data.put("label", label(element));
            data.put("how", "completed");
            Object exit = process.getVariable(OrqixRoute.EXIT_VARIABLE + element.getId());
            if (exit != null) {
                data.put("exit", exit.toString());
            }
            events.append(workspaceId, runId, "SECTION_LEFT", element.getId(), null, data);
        }
    }

    /** An error was caught: remember an error handler, whose interruption carries no cause of its own. */
    private void onErrorReceived(FlowableErrorEvent event) {
        if (event.getProcessDefinitionId() == null || event.getActivityId() == null) {
            return;
        }
        FlowElement element = OrqixModel.model(event.getProcessDefinitionId()).getFlowElement(event.getActivityId());
        ExtensionElement handler = OrqixModel.extension(element, "scopeEvent").orElse(null);
        ExtensionElement section = OrqixModel.extension(element, "scopeError").orElse(null);
        if (handler == null && section == null) {
            return;
        }
        String scopeId = OrqixModel.attribute(handler != null ? handler : section, "scope");
        if (handler != null) {
            Context.getCommandContext().addAttribute(ERROR_HANDLER_ATTRIBUTE + event.getProcessInstanceId(), scopeId);
        }
        ExecutionEntity process = CommandContextUtil.getExecutionEntityManager().findById(event.getProcessInstanceId());
        if (process == null || process.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label(OrqixModel.flowElement(event.getProcessDefinitionId(), scopeId)));
        data.put("errorCode", event.getErrorCode());
        events.append(ProcessData.workspaceId(process), ProcessData.runId(process), "ERROR_CAUGHT", scopeId, null, data);
    }

    /**
     * Work cancelled because a section's time limit passed or an error left it, or because a handler interrupted the
     * request (S55): its task is cancelled and it no longer shows as a current step. Other cancellations (a decision
     * time limit, a failure route, a rejection that ends the request, an operator) keep their own handling.
     */
    private void onActivityCancelled(FlowableActivityCancelledEvent event) {
        if (event.getProcessDefinitionId() == null || event.getActivityId() == null) {
            return;
        }
        String scopeId = null;
        String reason = null;
        if (event.getCause() instanceof FlowElement cause) {
            ExtensionElement timeout = OrqixModel.extension(cause, "scopeTimeout").orElse(null);
            ExtensionElement error = OrqixModel.extension(cause, "scopeError").orElse(null);
            ExtensionElement handler = OrqixModel.extension(cause, "scopeEvent").orElse(null);
            if (timeout != null) {
                scopeId = OrqixModel.attribute(timeout, "scope");
                reason = "timedOut";
            } else if (error != null) {
                scopeId = OrqixModel.attribute(error, "scope");
                reason = "error";
            } else if (handler != null) {
                scopeId = OrqixModel.attribute(handler, "scope");
                reason = OrqixModel.attribute(handler, "trigger");
            }
        }
        if (scopeId == null) {
            Object handler = Context.getCommandContext().getAttribute(ERROR_HANDLER_ATTRIBUTE + event.getProcessInstanceId());
            if (handler instanceof String id) {
                scopeId = id;
                reason = "error";
            }
        }
        if (scopeId == null) {
            return;
        }
        FlowElement element = OrqixModel.model(event.getProcessDefinitionId()).getFlowElement(event.getActivityId());
        if (element == null) {
            return;
        }
        ExecutionEntity process = CommandContextUtil.getExecutionEntityManager().findById(event.getProcessInstanceId());
        if (process == null || process.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        UUID workspaceId = ProcessData.workspaceId(process);
        UUID runId = ProcessData.runId(process);
        CommandContext context = Context.getCommandContext();
        String key = INTERRUPTED_ATTRIBUTE + event.getProcessInstanceId() + "." + element.getId();
        if (context.getAttribute(key) != null) {
            return; // the other approvers of a several-approver step
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label(element));
        data.put("by", scopeId);
        data.put("reason", reason);
        ExtensionElement scope = OrqixModel.extension(element, "scope").orElse(null);
        if (element instanceof SubProcess && scope != null) {
            if ("embedded".equals(OrqixModel.attribute(scope, "kind"))) {
                context.addAttribute(key, true);
                data.put("how", reason);
                events.append(workspaceId, runId, "SECTION_LEFT", element.getId(), null, data);
            }
            return;
        }
        // Only steps of the workflow (generated helpers carry a double underscore) that can be open work.
        if (element.getId().contains("__") || !(element instanceof UserTask || element instanceof ServiceTask
                || element instanceof IntermediateCatchEvent)) {
            return;
        }
        context.addAttribute(key, true);
        Instant now = clock.instant();
        tasks.cancelOpenForNode(workspaceId, runId, element.getId(), now);
        runs.mutate(workspaceId, runId, m -> {
            m.removeStep(element.getId());
            m.settle();
        });
        events.append(workspaceId, runId, "STEP_INTERRUPTED", element.getId(), null, data);
    }

    // ---------------------------------------------------------------- messages (S16)

    /** A token reached a "Wait for a signal" step (S55); handler start subscriptions are not waiting steps. */
    private void onSignalWait(EventSubscriptionEntity subscription) {
        if (subscription.getProcessDefinitionId() == null || subscription.getActivityId() == null
                || subscription.getExecutionId() == null) {
            return;
        }
        FlowElement element = OrqixModel.flowElement(subscription.getProcessDefinitionId(), subscription.getActivityId());
        ExtensionElement signal = OrqixModel.extension(element, "signal").orElse(null);
        if (signal == null || !"wait".equals(OrqixModel.attribute(signal, "action"))) {
            return;
        }
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager().findById(subscription.getExecutionId());
        if (execution == null || execution.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        String label = label(element);
        Instant now = clock.instant();
        runs.mutate(workspaceId, runId, m -> {
            ObjectNode step = json.object();
            step.put("nodeId", element.getId());
            step.put("label", label);
            step.put("kind", "waitForSignal");
            step.put("since", now.toString());
            step.putNull("candidateGroup");
            step.putNull("assigneeId");
            step.putNull("dueAt");
            m.addStep(step);
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("signal", OrqixModel.attribute(signal, "name"));
        events.append(workspaceId, runId, "SIGNAL_WAIT_STARTED", element.getId(), null, data);
    }

    /** A token reached a message catch event: the engine now waits for the message. */
    private void onSubscriptionCreated(EventSubscriptionEntity subscription) {
        if ("signal".equals(subscription.getEventType())) {
            onSignalWait(subscription);
            return;
        }
        if (!"message".equals(subscription.getEventType()) || subscription.getProcessDefinitionId() == null
                || subscription.getActivityId() == null || subscription.getExecutionId() == null) {
            return;
        }
        FlowElement element = OrqixModel.flowElement(subscription.getProcessDefinitionId(), subscription.getActivityId());
        ExtensionElement message = OrqixModel.extension(element, "message").orElse(null);
        if (message == null) {
            return;
        }
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager().findById(subscription.getExecutionId());
        if (execution == null || execution.getVariable(ProcessData.RUN_ID) == null) {
            return;
        }
        String nodeId = OrqixModel.attribute(message, "node");
        String field = OrqixModel.attribute(message, "correlationField");
        JsonNode value = ProcessData.resolve(execution, json, field);
        String correlationKey = value.isString() ? value.stringValue()
                : value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0
                    ? value.decimalValue().stripTrailingZeros().toPlainString() : null;
        if (correlationKey == null || correlationKey.isEmpty() || correlationKey.length() > 200) {
            throw new IllegalStateException("WF_MESSAGE_CORRELATION_INVALID: " + field + " does not hold a usable key");
        }
        FlowElement node = OrqixModel.flowElement(subscription.getProcessDefinitionId(), nodeId);
        Object deadline = Context.getCommandContext().getAttribute(MESSAGE_TIMEOUT_ATTRIBUTE
                + subscription.getProcessInstanceId() + "." + nodeId);
        messageWaits.started(ProcessData.workspaceId(execution), ProcessData.runId(execution), nodeId,
                node.getName() == null ? nodeId : node.getName(), OrqixModel.attribute(message, "name"),
                correlationKey, subscription.getExecutionId(), deadline instanceof Instant at ? at : null);
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
        ExtensionElement messageTimeout = OrqixModel.extension(element, "messageTimeout").orElse(null);
        if (messageTimeout != null && due != null) {
            // The time limit of a message wait: remembered for the wait (created before or after this timer).
            String nodeId = OrqixModel.attribute(messageTimeout, "node");
            Context.getCommandContext().addAttribute(MESSAGE_TIMEOUT_ATTRIBUTE + timer.getProcessInstanceId() + "."
                    + nodeId, due);
            ExecutionEntity waiting = CommandContextUtil.getExecutionEntityManager().findById(timer.getExecutionId());
            if (waiting != null && waiting.getVariable(ProcessData.RUN_ID) != null) {
                messageWaits.deadline(ProcessData.workspaceId(waiting), ProcessData.runId(waiting), nodeId, due);
            }
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
        } else if (OrqixModel.extension(element, "messageTimeout").isPresent()) {
            ExtensionElement timeout = OrqixModel.requireExtension(element, "messageTimeout");
            String nodeId = OrqixModel.attribute(timeout, "node");
            FlowElement node = OrqixModel.flowElement(job.getProcessDefinitionId(), nodeId);
            String messageName = OrqixModel.attribute(OrqixModel.requireExtension(OrqixModel.flowElement(
                    job.getProcessDefinitionId(), nodeId + "__message"), "message"), "name");
            messageWaits.timedOut(workspaceId, runId, nodeId, node.getName() == null ? nodeId : node.getName(),
                    messageName, OrqixModel.attribute(timeout, "after"));
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
        if (element instanceof SubProcess || element instanceof ThrowEvent) {
            scopeStarted(event, element);
            return;
        }
        if (!(element instanceof EndEvent)) {
            return;
        }
        ExtensionElement outcome = OrqixModel.extension(element, "outcome").orElse(null);
        if (outcome == null) {
            // A raised error or the generated end of a section or handler (S55): the request does not finish here.
            OrqixModel.extension(element, "raise").ifPresent(raise -> {
                ExecutionEntity raising = CommandContextUtil.getExecutionEntityManager().findById(event.getExecutionId());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("label", label(element));
                data.put("errorCode", OrqixModel.attribute(raise, "errorCode"));
                events.append(ProcessData.workspaceId(raising), ProcessData.runId(raising), "ERROR_RAISED",
                        element.getId(), null, data);
            });
            return;
        }
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
        // Stages close before the run's completion event, so a timeline reads stage done, then request done (S17).
        stages.reconcile(end.workspaceId(), end.runId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", end.label());
        data.put("outcomeKind", end.kind());
        events.append(end.workspaceId(), end.runId(), "RUN_COMPLETED", end.nodeId(), null, data);
        metrics.runCompleted(end.kind());
        // Child runs still running when their parent ends (a rejection in another branch) end with it; a child that
        // ends tells its parent through an asynchronous trigger job (S12 section 4).
        messageWaits.ended(end.workspaceId(), end.runId());
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
            } else if (entity instanceof EventSubscriptionEntity subscription) {
                processInstanceId = subscription.getProcessInstanceId();
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
