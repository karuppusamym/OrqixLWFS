package com.orqix.runtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.orqix.identity.Caller;
import com.orqix.runtime.api.MessageDtos;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.internal.MessageService;
import com.orqix.runtime.internal.RunQueries;
import com.orqix.runtime.internal.RunService;
import com.orqix.runtime.internal.TaskService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Request, task and message operations for the MCP server (CG-M04, docs/build/S136). Each method delegates to the
 * same service as the public operation with the person as caller, so membership, permissions, eligibility, separation
 * of duties, versions, idempotency and audit are those of the REST API. Results are the REST response bodies as JSON
 * trees: exactly the fields the person could see in the UI, nothing more.
 */
@Service
public class AssistantRuntime {

    /** A write result: the REST body and whether it replayed an earlier call with the same key. */
    public record Written(JsonNode body, boolean replayed) {
    }

    private final RunService runs;
    private final TaskService tasks;
    private final MessageService messages;
    private final Json json;

    public AssistantRuntime(RunService runs, TaskService tasks, MessageService messages, Json json) {
        this.runs = runs;
        this.tasks = tasks;
        this.messages = messages;
        this.json = json;
    }

    /** getRun. */
    public JsonNode request(Caller caller, UUID workspaceId, UUID runId) {
        return json.toTree(runs.getRun(caller, workspaceId, runId));
    }

    /** listRunEvents (first page of the timeline). */
    public JsonNode timeline(Caller caller, UUID workspaceId, UUID runId, int limit) {
        return json.toTree(runs.events(caller, workspaceId, runId, null, limit));
    }

    /** listRuns (run.read) with the filters of the Requests page. */
    public JsonNode searchRequests(Caller caller, UUID workspaceId, UUID workflowId, String lifecycle, String outcomeKind,
            String businessKey, Instant createdFrom, Instant createdTo, boolean overdue, String cursor, int limit) {
        RunQueries.Filter filter = new RunQueries.Filter(workflowId, lifecycle, outcomeKind, businessKey, createdFrom,
                createdTo, null, overdue);
        return json.toTree(runs.listRuns(caller, workspaceId, filter, cursor, limit));
    }

    /** listMyRequests: the caller's own requests (any member). */
    public JsonNode myRequests(Caller caller, UUID workspaceId, String lifecycle, String cursor, int limit) {
        return json.toTree(runs.listMyRequests(caller, workspaceId, lifecycle, cursor, limit));
    }

    /** listTasks (task.act): assigned, claimable or completed. */
    public JsonNode tasks(Caller caller, UUID workspaceId, String view, String q, String cursor, int limit) {
        return json.toTree(tasks.listTasks(caller, workspaceId, view, cursor, limit, null, null, q, null, null));
    }

    /** getTask. */
    public JsonNode task(Caller caller, UUID workspaceId, String taskId) {
        return json.toTree(tasks.getTask(caller, workspaceId, taskId));
    }

    /** The checks of startRun that write nothing (S136: a summary is only prepared for input that would be accepted). */
    public List<ApiException.FieldError> checkStart(Caller caller, UUID workspaceId, UUID workflowId, JsonNode input) {
        return runs.checkStart(caller, workspaceId, workflowId, input);
    }

    /** startRun with the given idempotency key. */
    public Written start(Caller caller, UUID workspaceId, String idempotencyKey, UUID workflowId, String businessKey,
            JsonNode input) {
        RunService.Started started = runs.start(caller, workspaceId, idempotencyKey,
                new RuntimeDtos.StartRunRequest(workflowId, null, businessKey, input));
        return new Written(json.toTree(started.run()), started.replayed());
    }

    /**
     * Decides a task the way the task page does: claims it first when it is still in the caller's queue (claimTask,
     * with the version the summary showed), then decideTask with the version after the claim. A task that changed
     * since the summary was prepared is a version conflict, never a decision on something the person did not see.
     */
    public Written decide(Caller caller, UUID workspaceId, String taskId, String idempotencyKey, int preparedVersion,
            String action, String reason, String comment) {
        RuntimeDtos.TaskDetail task = tasks.getTask(caller, workspaceId, taskId);
        boolean mine = task.assignee() != null && caller.subjectId().equals(task.assignee().id());
        int version = preparedVersion;
        if (!mine && "open".equals(task.status())) {
            // claimTask checks the version, eligibility and separation of duties exactly as on the task page.
            version = tasks.claim(caller, workspaceId, taskId, preparedVersion).version();
        }
        TaskService.Decided decided = tasks.decide(caller, workspaceId, taskId, idempotencyKey,
                new RuntimeDtos.DecisionRequest(version, action, reason, comment));
        return new Written(json.toTree(decided.decision()), decided.replayed());
    }

    /** sendMessage (message.send). */
    public Written sendMessage(Caller caller, UUID workspaceId, String idempotencyKey, String name, String correlationKey,
            JsonNode payload) {
        MessageService.Sent sent = messages.send(caller, workspaceId, idempotencyKey,
                new MessageDtos.SendMessageRequest(name, correlationKey, payload));
        return new Written(json.toTree(sent.body()), sent.replayed());
    }
}
