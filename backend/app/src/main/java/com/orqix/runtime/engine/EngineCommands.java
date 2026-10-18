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
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
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

    public EngineCommands(RuntimeService runtimeService, TaskService taskService, ManagementService managementService,
            ProcessMigrationService migrationService) {
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
                    .variables(variables)
                    .start();
            return instance.getId();
        });
    }

    /** @return the executions of this process instance subscribed to the signal, oldest first (S55) */
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
                .activityId(nodeId).list();
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
        wrap(() -> managementService.moveDeadLetterJobToExecutableJob(jobId, retries));
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
