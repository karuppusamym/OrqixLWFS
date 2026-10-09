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

    public EngineCommands(RuntimeService runtimeService, TaskService taskService, ManagementService managementService) {
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

    public void claim(String taskId, UUID subject) {
        as(subject, () -> {
            taskService.claim(taskId, subject.toString());
            return null;
        });
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
