package com.orqix.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.orqix.identity.Caller;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.internal.RunService;
import com.orqix.runtime.internal.TaskService;

import tools.jackson.databind.JsonNode;

/**
 * Narrow request and task commands for application page chains (S111). Each method delegates to the same service as
 * the public operation, so membership, permissions, eligibility, versions, idempotency and audit are unchanged.
 */
@Service
public class PageActions {
    private final TaskService tasks;
    private final RunService runs;
    private final com.orqix.runtime.internal.SendBacks sendBacks;
    private final com.orqix.runtime.internal.OperatorService operator;

    public PageActions(TaskService tasks, RunService runs, com.orqix.runtime.internal.SendBacks sendBacks,
            com.orqix.runtime.internal.OperatorService operator) {
        this.tasks = tasks;
        this.runs = runs;
        this.sendBacks = sendBacks;
        this.operator = operator;
    }

    /** S116: returns the request to its requester for the step's declared fields; returns the new task version. */
    public int sendBack(Caller caller, UUID workspaceId, String taskId, String idempotencyKey, int expectedVersion, String reason) {
        return sendBacks.send(caller, workspaceId, taskId, idempotencyKey, new RuntimeDtos.SendBackRequest(expectedVersion, reason)).version();
    }

    /** Compensation of a send-back: withdraws it again (holder or operator). */
    public void withdrawSendBack(Caller caller, UUID workspaceId, String taskId, String note) {
        int version = tasks.getTask(caller, workspaceId, taskId).version();
        sendBacks.withdraw(caller, workspaceId, taskId, new RuntimeDtos.WithdrawSendBackRequest(version, note));
    }

    /** Compensation of a claim: returns the task to its group (assignee only). */
    public void release(Caller caller, UUID workspaceId, String taskId) {
        int version = tasks.getTask(caller, workspaceId, taskId).version();
        tasks.release(caller, workspaceId, taskId, version);
    }

    /** Compensation of a started request: closes it through the operator's cancel (run.operate), with a reason. */
    public void cancel(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey, String reason) {
        int version = runs.getRun(caller, workspaceId, runId).version();
        operator.cancel(caller, workspaceId, runId, idempotencyKey, new RuntimeDtos.CancelRunRequest(reason, version));
    }

    /** A task as the caller may see it; summary keys are the page's task binding names. */
    public record Task(String id, int version, String status, Map<String, Object> summary, JsonNode requestInput) { }

    public Task task(Caller caller, UUID workspaceId, String taskId) {
        return task(tasks.getTask(caller, workspaceId, taskId));
    }

    /** Returns the task version after the claim. */
    public int claim(Caller caller, UUID workspaceId, String taskId, int expectedVersion) {
        return tasks.claim(caller, workspaceId, taskId, expectedVersion).version();
    }

    public void decide(Caller caller, UUID workspaceId, String taskId, String idempotencyKey, int expectedVersion,
            String action, String reason, String comment) {
        tasks.decide(caller, workspaceId, taskId, idempotencyKey,
                new RuntimeDtos.DecisionRequest(expectedVersion, action, reason, comment));
    }

    /**
     * Corrects request fields the step lets the task holder correct (S42); returns the task version after the
     * correction, which a following decision must carry.
     */
    public int correct(Caller caller, UUID workspaceId, String taskId, String idempotencyKey, int expectedVersion,
            String reason, Map<String, JsonNode> changes) {
        return tasks.correctInput(caller, workspaceId, taskId, idempotencyKey,
                new RuntimeDtos.CorrectTaskInputRequest(expectedVersion, reason, changes)).version();
    }

    public UUID start(Caller caller, UUID workspaceId, String idempotencyKey, UUID workflowId, JsonNode input) {
        return runs.start(caller, workspaceId, idempotencyKey,
                new RuntimeDtos.StartRunRequest(workflowId, null, null, input)).run().id();
    }

    private static Task task(RuntimeDtos.TaskDetail detail) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requestTitle", detail.requestTitle());
        summary.put("reference", detail.runReference());
        summary.put("label", detail.label());
        summary.put("workflowName", detail.workflowName());
        summary.put("requester", detail.requester() == null ? null : detail.requester().displayName());
        summary.put("createdAt", text(detail.createdAt()));
        summary.put("dueAt", text(detail.dueAt()));
        summary.put("status", detail.status());
        summary.put("priority", detail.priority());
        return new Task(detail.id(), detail.version(), detail.status(), summary,
                detail.request() == null ? null : detail.request().input());
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }
}
