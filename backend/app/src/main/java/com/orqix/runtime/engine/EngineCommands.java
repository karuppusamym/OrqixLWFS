package com.orqix.runtime.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.job.api.Job;
import org.flowable.task.api.TaskInfo;
import org.springframework.stereotype.Component;

/**
 * The only door from Orqix application services into the engine for user commands (S5 section 1): engine ids come
 * from Orqix projections already resolved in the caller's workspace; the authenticated user is set around each call
 * and cleared in {@code finally}; engine optimistic-lock failures become {@link EngineConflictException} (TX-7).
 */
@Component
public class EngineCommands {

    /** The engine instance or task changed concurrently (a job holds it, or another command won). */
    public static class EngineConflictException extends RuntimeException {
        public EngineConflictException(Throwable cause) {
            super("Engine state changed concurrently", cause);
        }
    }

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final ManagementService managementService;
    private final ProcessMigrationService migrationService;
    private final RepositoryService repositoryService;

    public EngineCommands(RuntimeService runtimeService, TaskService taskService, ManagementService managementService,
            ProcessMigrationService migrationService, RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
        this.migrationService = migrationService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.managementService = managementService;
    }

    public String startProcess(String processDefinitionId, String tenantId, String businessKey,
            Map<String, Object> variables, UUID actor) {
        return as(actor, () -> {
            ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionId(processDefinitionId)
                    .tenantId(tenantId)
                    .businessKey(businessKey)
                    // CG-P06 (S150): the start's traceparent, so the run's engine jobs join the trace of its start.
                    // Set once at the start only: later updates from parallel branches would conflict on the row.
                    .variables(com.orqix.shared.tracing.TraceIds.withTraceparent(variables))
                    .start();
            return instance.getId();
        });
    }

    /** @return the executions of this process instance subscribed to the signal, oldest first (S59) */
    public List<String> signalSubscriptions(String processInstanceId, String signal) {
        return runtimeService.createEventSubscriptionQuery().processInstanceId(processInstanceId).eventType("signal")
                .eventName(signal).list().stream()
                .sorted(java.util.Comparator.comparing(org.flowable.eventsubscription.api.EventSubscription::getCreated))
                .map(org.flowable.eventsubscription.api.EventSubscription::getExecutionId).distinct().toList();
    }

    /**
     * Delivers a signal to the subscriptions of one process instance only (never a global broadcast), oldest first; a
     * subscription removed by an earlier delivery (an interrupting handler) is skipped.
     *
     * @return how many subscriptions received it
     */
    public int signal(String processInstanceId, String signal, UUID actor) {
        // The public signal API ignores process-instance scoped subscriptions (Orqix signals never leave a request), so
        // this delivers them the way a "Send a signal" step of the request itself does.
        return as(actor, () -> managementService.executeCommand(commandContext -> {
            var configuration = org.flowable.engine.impl.util.CommandContextUtil.getProcessEngineConfiguration(commandContext);
            var subscriptions = configuration.getEventSubscriptionServiceConfiguration().getEventSubscriptionService()
                    .findSignalEventSubscriptionsByProcessInstanceAndEventName(processInstanceId, signal);
            subscriptions.sort(java.util.Comparator.comparing(
                    org.flowable.eventsubscription.service.impl.persistence.entity.EventSubscriptionEntity::getCreated));
            int delivered = 0;
            for (var subscription : subscriptions) {
                if (subscription.isDeleted()) {
                    continue; // removed by an earlier delivery (an interrupting handler)
                }
                configuration.getEventDispatcher().dispatchEvent(
                        org.flowable.engine.delegate.event.impl.FlowableEventBuilder.createSignalEvent(
                                org.flowable.common.engine.api.delegate.event.FlowableEngineEventType.ACTIVITY_SIGNALED,
                                subscription.getActivityId(), signal, null, subscription.getExecutionId(),
                                subscription.getProcessInstanceId(), subscription.getProcessDefinitionId()),
                        configuration.getEngineCfgKey());
                org.flowable.engine.impl.util.EventSubscriptionUtil.eventReceived(subscription, null, false);
                delivered++;
            }
            return delivered;
        }));
    }

    public void claim(String taskId, UUID subject) {
        as(subject, () -> {
            taskService.claim(taskId, subject.toString());
            return null;
        });
    }

    /** Sets request data without retrying or triggering the paused execution. */
    public void correctInput(String processInstanceId,String inputJson,UUID actor) {
        as(actor,()-> { runtimeService.setVariable(processInstanceId,ProcessData.INPUT,inputJson); return null; });
    }

    public void unclaim(String taskId, UUID actor) {
        as(actor, () -> {
            taskService.unclaim(taskId);
            return null;
        });
    }

    public void setAssignee(String taskId, UUID assignee, UUID actor) {
        as(actor, () -> {
            taskService.setAssignee(taskId, assignee == null ? null : assignee.toString());
            return null;
        });
    }

    /** Completes the task with process-scope variables; the engine continues to the next wait state. */
    public void complete(String taskId, Map<String, Object> variables, UUID actor) {
        as(actor, () -> {
            taskService.complete(taskId, variables);
            return null;
        });
    }

    public boolean taskExists(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).count() == 1;
    }

    public String taskProcessDefinitionId(String taskId) {
        TaskInfo task = taskService.createTaskQuery().taskId(taskId).singleResult();
        return task == null ? null : task.getProcessDefinitionId();
    }

    public void deleteProcessInstance(String processInstanceId, String reason, UUID actor) {
        as(actor, () -> {
            runtimeService.deleteProcessInstance(processInstanceId, reason);
            return null;
        });
    }

    /** Resume a pinned child step in a durable job after the child's transaction commits. */
    public void resumeChild(String processInstanceId, String nodeId, Map<String, Object> variables) {
        var executions = runtimeService.createExecutionQuery().processInstanceId(processInstanceId)
                .activityId(nodeId).list().stream()
                // S138: a for-each step also has its multi-instance root (and batch) executions at the activity; only
                // the item execution waits for the trigger (items of workers and AI tasks run one at a time).
                .filter(e -> !(e instanceof org.flowable.engine.impl.persistence.entity.ExecutionEntity entity)
                        || !entity.isMultiInstanceRoot())
                .toList();
        if (executions.size() != 1) {
            throw new IllegalStateException("Expected one waiting child execution at " + nodeId);
        }
        runtimeService.triggerAsync(executions.getFirst().getId(), variables);
    }

    /**
     * Delivers a message to the execution waiting at a message catch event and sets the given process variables
     * (S16). The engine continues to the next wait state inside the caller's transaction; a competing timer or
     * message wins or loses by optimistic locking and surfaces as {@link EngineConflictException}.
     */
    public void deliverMessage(String executionId, String messageName, Map<String, Object> variables) {
        as(null, () -> {
            runtimeService.messageEventReceived(messageName, executionId, variables);
            return null;
        });
    }

    public Optional<String> deadLetterJob(String processInstanceId) {
        List<Job> jobs = managementService.createDeadLetterJobQuery().processInstanceId(processInstanceId).list();
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.get(0).getId());
    }

    public void moveDeadLetterToExecutable(String jobId, int retries) {
        // A step with its own retry policy (CG-W04, S131) gets its own attempts back, not the workflow's.
        int attempts = stepAttempts(jobId).orElse(retries);
        wrap(() -> managementService.moveDeadLetterJobToExecutableJob(jobId, attempts));
    }

    private Optional<Integer> stepAttempts(String jobId) {
        Job job = managementService.createDeadLetterJobQuery().jobId(jobId).singleResult();
        if (job == null || job.getProcessDefinitionId() == null || job.getElementId() == null) {
            return Optional.empty();
        }
        // Outside a Flowable command: read the deployed model through the repository service.
        FlowElement element = repositoryService.getBpmnModel(job.getProcessDefinitionId()).getFlowElement(job.getElementId());
        return element == null ? Optional.empty() : OrqixModel.extension(element, "retry")
                .map(x -> OrqixModel.attribute(x, "maxAttempts")).filter(v -> v != null).map(Integer::parseInt);
    }

    /**
     * CG-M03 (S135): makes the deferred job of a durable agent step due now (its wait was answered or its child request
     * ended). Joins the caller's transaction; the job runs after the commit. Returns false when no timer job waits.
     */
    public boolean wakeTimer(String processInstanceId, String elementId) {
        boolean moved = false;
        for (Job timer : managementService.createTimerJobQuery().processInstanceId(processInstanceId).list()) {
            if (elementId.equals(timer.getElementId())) {
                managementService.moveTimerToExecutableJob(timer.getId());
                moved = true;
            }
        }
        return moved;
    }

    public boolean hasExecutableOrTimerJob(String processInstanceId) {
        return managementService.createJobQuery().processInstanceId(processInstanceId).count() > 0
                || managementService.createTimerJobQuery().processInstanceId(processInstanceId).count() > 0;
    }

    /** Conservative watchdog snapshot: any engine activity or durable job counts as work. */
    public boolean hasOutstandingWork(String processInstanceId) {
        if (processInstanceId == null) {
            return false;
        }
        var instance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (instance != null && instance.isSuspended()) {
            return true;
        }
        return (instance != null && !runtimeService.getActiveActivityIds(processInstanceId).isEmpty())
                || taskService.createTaskQuery().processInstanceId(processInstanceId).count() > 0
                || runtimeService.createEventSubscriptionQuery().processInstanceId(processInstanceId).count() > 0
                || hasExecutableOrTimerJob(processInstanceId)
                || managementService.createDeadLetterJobQuery().processInstanceId(processInstanceId).count() > 0
                || managementService.createSuspendedJobQuery().processInstanceId(processInstanceId).count() > 0;
    }

    /** Freezes the instance: the engine moves its jobs and timers aside and rejects task and message commands. */
    public void suspendProcessInstance(String processInstanceId, UUID actor) {
        as(actor, () -> {
            runtimeService.suspendProcessInstanceById(processInstanceId);
            return null;
        });
    }

    /** Reactivates the instance; jobs and timers that fell due while suspended become executable again. */
    public void activateProcessInstance(String processInstanceId, UUID actor) {
        as(actor, () -> {
            runtimeService.activateProcessInstanceById(processInstanceId);
            return null;
        });
    }

    /** Engine-level verdict on moving the instance to another definition: empty when valid, else the engine's reasons. */
    public List<String> migrationProblems(String processInstanceId, String targetProcessDefinitionId) {
        var validation = migrationService.createProcessInstanceMigrationBuilder()
                .migrateToProcessDefinition(targetProcessDefinitionId).validateMigration(processInstanceId);
        return validation.isMigrationValid() ? List.of() : validation.getValidationMessages();
    }

    /** Re-points the instance at another definition, keeping its executions, tasks, timers and variables (S38). */
    public void migrateProcessInstance(String processInstanceId, String targetProcessDefinitionId, UUID actor) {
        as(actor, () -> {
            migrationService.createProcessInstanceMigrationBuilder()
                    .migrateToProcessDefinition(targetProcessDefinitionId).migrate(processInstanceId);
            return null;
        });
    }

    /**
     * S143 (CG-O02): the engine's verdict on a migration with explicit activity mappings ({@code from -> to}, only for
     * activities whose id changes; the others map to themselves) and process variables to set on the way.
     */
    public List<String> migrationProblems(String processInstanceId, String targetProcessDefinitionId,
            Map<String, String> activityMappings, Map<String, Object> variables) {
        var validation = mappedMigration(targetProcessDefinitionId, activityMappings, variables)
                .validateMigration(processInstanceId);
        return validation.isMigrationValid() ? List.of() : validation.getValidationMessages();
    }

    /** S143 (CG-O02): re-points the instance with explicit activity mappings and sets the given process variables. */
    public void migrateProcessInstance(String processInstanceId, String targetProcessDefinitionId,
            Map<String, String> activityMappings, Map<String, Object> variables, UUID actor) {
        as(actor, () -> {
            mappedMigration(targetProcessDefinitionId, activityMappings, variables).migrate(processInstanceId);
            return null;
        });
    }

    private org.flowable.engine.migration.ProcessInstanceMigrationBuilder mappedMigration(String targetProcessDefinitionId,
            Map<String, String> activityMappings, Map<String, Object> variables) {
        var builder = migrationService.createProcessInstanceMigrationBuilder()
                .migrateToProcessDefinition(targetProcessDefinitionId);
        for (Map.Entry<String, String> mapping : activityMappings.entrySet()) {
            builder.addActivityMigrationMapping(org.flowable.engine.migration.ActivityMigrationMapping
                    .createMappingFor(mapping.getKey(), mapping.getValue()));
        }
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            builder.withProcessInstanceVariable(variable.getKey(), variable.getValue());
        }
        return builder;
    }

    /**
     * S143 (CG-O03): moves the tokens of step {@code nodeId} (its element, or its generated elements such as the
     * catch events after an event-based gateway; attached boundary events go with their activity) to the element
     * {@code toActivityId}. The left activities are cancelled by the engine (their tasks, timers, subscriptions and jobs
     * go with them) and the target starts as if a token had just arrived. Joins the caller's transaction.
     *
     * @return the engine activities that were left; empty when the engine is not at the step (nothing was changed)
     */
    public List<String> moveStep(String processInstanceId, String nodeId, String toActivityId, UUID actor) {
        var instance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (instance == null) {
            return List.of();
        }
        var model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
        // Executions by element, active or not: a call parked on a dead-letter job is not an "active" activity.
        List<org.flowable.engine.runtime.Execution> executions = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list().stream()
                .filter(e -> e.getActivityId() != null && !e.getId().equals(processInstanceId))
                .filter(e -> e.getActivityId().equals(nodeId) || e.getActivityId().startsWith(nodeId + "__"))
                .filter(e -> !(model.getFlowElement(e.getActivityId()) instanceof org.flowable.bpmn.model.BoundaryEvent))
                .toList();
        if (executions.isEmpty()) {
            return List.of();
        }
        List<String> executionIds = executions.stream().map(org.flowable.engine.runtime.Execution::getId).toList();
        as(actor, () -> {
            runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                    .moveExecutionsToSingleActivityId(executionIds, toActivityId).changeState();
            return null;
        });
        return executions.stream().map(org.flowable.engine.runtime.Execution::getActivityId).distinct().sorted()
                .toList();
    }

    /** S143: the process-level variables of an instance whose names start with {@code prefix}, read-only. */
    public Map<String, Object> variables(String processInstanceId, String prefix) {
        var result = new java.util.TreeMap<String, Object>();
        if (!processExists(processInstanceId)) {
            return result;
        }
        runtimeService.getVariables(processInstanceId).forEach((name, value) -> {
            if (name.startsWith(prefix)) {
                result.put(name, value);
            }
        });
        return result;
    }

    /** S143: the ids of the activities active in the instance (tokens and waiting executions), sorted. */
    public List<String> activeActivityIds(String processInstanceId) {
        if (!processExists(processInstanceId)) {
            return List.of();
        }
        return List.copyOf(new java.util.TreeSet<>(runtimeService.getActiveActivityIds(processInstanceId)));
    }

    /** S143: whether the deployed definition has a flow node (activity, event or gateway) with this id. */
    public boolean hasFlowNode(String processDefinitionId, String elementId) {
        FlowElement element = repositoryService.getBpmnModel(processDefinitionId).getFlowElement(elementId);
        return element instanceof org.flowable.bpmn.model.FlowNode;
    }

    /** S143: executable, timer and dead-letter jobs of the instance, for "nothing can move it" checks. */
    public long jobCount(String processInstanceId) {
        return managementService.createJobQuery().processInstanceId(processInstanceId).count()
                + managementService.createTimerJobQuery().processInstanceId(processInstanceId).count()
                + managementService.createDeadLetterJobQuery().processInstanceId(processInstanceId).count();
    }

    public boolean isSuspended(String processInstanceId) {
        var instance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        return instance != null && instance.isSuspended();
    }

    /** What the engine itself holds for an instance, read-only; used to compare against the Orqix projection (EX09). */
    public record Snapshot(boolean exists, boolean suspended, List<String> activeActivityIds,
            Map<String, String> taskIdsByActivity, long eventSubscriptions, long jobs, long deadLetterJobs,
            long suspendedJobs) {
        public boolean hasDrivingWork() {
            return !taskIdsByActivity.isEmpty() || eventSubscriptions > 0 || jobs > 0 || deadLetterJobs > 0
                    || suspendedJobs > 0;
        }
    }

    public Snapshot snapshot(String processInstanceId) {
        if (!processExists(processInstanceId)) {
            return new Snapshot(false, false, List.of(), Map.of(), 0, 0, 0, 0);
        }
        var tasks = new java.util.TreeMap<String, String>();
        taskService.createTaskQuery().processInstanceId(processInstanceId).list()
                .forEach(t -> tasks.put(t.getTaskDefinitionKey(), t.getId()));
        long jobs = managementService.createJobQuery().processInstanceId(processInstanceId).count()
                + managementService.createTimerJobQuery().processInstanceId(processInstanceId).count();
        return new Snapshot(true, isSuspended(processInstanceId),
                List.copyOf(new java.util.TreeSet<>(runtimeService.getActiveActivityIds(processInstanceId))), tasks,
                runtimeService.createEventSubscriptionQuery().processInstanceId(processInstanceId).count(), jobs,
                managementService.createDeadLetterJobQuery().processInstanceId(processInstanceId).count(),
                managementService.createSuspendedJobQuery().processInstanceId(processInstanceId).count());
    }

    /**
     * One non-approval step the engine itself determines (EX09): a timer wait, a message wait or an external-worker
     * step. {@code executionId} is set for message waits only; {@code dueAt} is the engine timer's due date.
     */
    public record WaitStep(String kind, String nodeId, String label, String message, String topic, String executionId,
            java.time.Instant since, java.time.Instant dueAt) {
    }

    /**
     * Read-only interpretation of the engine's timers, subscriptions and waiting service tasks. {@code unexplained}
     * lists anything this check cannot interpret (it must then refuse); {@code explainedActivities} lists the engine
     * activity ids that are accounted for by {@code steps} or by an approval's own time limit.
     */
    public record WaitFacts(List<WaitStep> steps, java.util.Set<String> explainedActivities, List<String> unexplained,
            long executableJobs) {
    }

    public WaitFacts waitFacts(String processInstanceId, java.util.Set<String> approvalActivities) {
        var steps = new java.util.ArrayList<WaitStep>();
        var explained = new java.util.TreeSet<String>();
        var unexplained = new java.util.ArrayList<String>();
        var instance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (instance == null) {
            return new WaitFacts(List.of(), explained, unexplained, 0);
        }
        var model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
        java.util.Map<String, java.time.Instant> limits = new java.util.HashMap<>();
        for (Job timer : managementService.createTimerJobQuery().processInstanceId(processInstanceId).list()) {
            var element = timer.getElementId() == null ? null : model.getFlowElement(timer.getElementId());
            if (element == null) {
                unexplained.add("a timer that is not part of the workflow");
            } else if (OrqixModel.extension(element, "wait").isPresent()) {
                steps.add(new WaitStep("wait", element.getId(), element.getName() == null ? element.getId() : element.getName(),
                        null, null, null, timer.getCreateTime() == null ? null : timer.getCreateTime().toInstant(),
                        timer.getDuedate() == null ? null : timer.getDuedate().toInstant()));
                explained.add(element.getId());
            } else if (element instanceof org.flowable.bpmn.model.BoundaryEvent boundary
                    && OrqixModel.extension(element, "timeout").isPresent()
                    && approvalActivities.contains(boundary.getAttachedToRefId())) {
                explained.add(element.getId());
            } else if (OrqixModel.extension(element, "messageTimeout").isPresent()) {
                limits.put(OrqixModel.attribute(OrqixModel.extension(element, "messageTimeout").get(), "node"),
                        timer.getDuedate() == null ? null : timer.getDuedate().toInstant());
                explained.add(element.getId());
            } else {
                unexplained.add("a timer at " + element.getId());
            }
        }
        for (var subscription : runtimeService.createEventSubscriptionQuery().processInstanceId(processInstanceId).list()) {
            var element = subscription.getActivityId() == null ? null : model.getFlowElement(subscription.getActivityId());
            var message = element == null || !"message".equals(subscription.getEventType()) ? null
                    : OrqixModel.extension(element, "message").orElse(null);
            if (message == null) {
                unexplained.add("a " + subscription.getEventType() + " subscription at " + subscription.getActivityId());
                continue;
            }
            String node = OrqixModel.attribute(message, "node");
            var nodeElement = model.getFlowElement(node);
            String timeoutId = node + "__timeout";
            boolean limited = model.getFlowElement(timeoutId) != null;
            if (limited && limits.get(node) == null) {
                unexplained.add("the time limit of the message wait " + node);
                continue;
            }
            steps.add(new WaitStep("wait", node, nodeElement == null || nodeElement.getName() == null ? node
                    : nodeElement.getName(), OrqixModel.attribute(message, "name"), null, subscription.getExecutionId(),
                    null, limited ? limits.get(node) : null));
            explained.add(subscription.getActivityId());
            explained.add(node);
            explained.add(timeoutId);
        }
        for (String activity : runtimeService.getActiveActivityIds(processInstanceId)) {
            if (explained.contains(activity) || approvalActivities.contains(activity)) {
                continue;
            }
            var element = model.getFlowElement(activity);
            var external = element == null ? null : OrqixModel.extension(element, "external").orElse(null);
            if (external != null) {
                steps.add(new WaitStep("external", activity, element.getName() == null ? activity : element.getName(),
                        null, OrqixModel.attribute(external, "topic"), null, null, null));
                explained.add(activity);
            }
        }
        return new WaitFacts(steps, explained, unexplained,
                managementService.createJobQuery().processInstanceId(processInstanceId).count());
    }

    public boolean processExists(String processInstanceId) {
        return processInstanceId != null
                && runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count() > 0;
    }

    private <T> T as(UUID actor, Supplier<T> action) {
        Authentication.setAuthenticatedUserId(actor == null ? null : actor.toString());
        try {
            return wrap(action);
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
    }

    private static <T> T wrap(Supplier<T> action) {
        try {
            return action.get();
        } catch (FlowableOptimisticLockingException e) {
            throw new EngineConflictException(e);
        } catch (FlowableObjectNotFoundException e) {
            throw new EngineConflictException(e);
        }
    }

    private static void wrap(Runnable action) {
        wrap(() -> {
            action.run();
            return null;
        });
    }
}
