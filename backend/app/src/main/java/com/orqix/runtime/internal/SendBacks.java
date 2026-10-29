package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.SubjectService;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Send back to the requester (UI02 / S116). The holder of an approval task returns the request to its requester for the
 * fields the step declares correctable (the same declaration as the holder's own correction, S42). While the send-back
 * is open the task is neither decided nor corrected by the holder. The requester answers by changing those fields; the
 * answer goes through the same correction path (form validation, engine update, before/after event, task version bump),
 * so a decision prepared on the old data is refused. The holder or an operator may withdraw an open send-back.
 */
@Service
public class SendBacks {
    private final TaskService taskService;
    private final TaskStore tasks;
    private final RunStore runs;
    private final RunAccess runAccess;
    private final ReleaseQueries releases;
    private final RunEvents events;
    private final RunSuspensions suspensions;
    private final SubjectService subjects;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;

    public SendBacks(TaskService taskService, TaskStore tasks, RunStore runs, RunAccess runAccess, ReleaseQueries releases,
            RunEvents events, RunSuspensions suspensions, SubjectService subjects, IdempotencyService idempotency,
            AuditWriter audit, JdbcClient jdbc, Json json) {
        this.taskService = taskService;
        this.tasks = tasks;
        this.runs = runs;
        this.runAccess = runAccess;
        this.releases = releases;
        this.events = events;
        this.suspensions = suspensions;
        this.subjects = subjects;
        this.idempotency = idempotency;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public RuntimeDtos.TaskDetail send(Caller caller, UUID workspaceId, String taskId, String idempotencyKey,
            RuntimeDtos.SendBackRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        TaskService.VisibleTask visible = taskService.visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        if (!caller.subjectId().equals(task.assignee())) {
            throw new ApiException(ProblemCode.TASK_NOT_CLAIMED_BY_CALLER, "Claim the task before sending it back.");
        }
        taskService.requireEligible(visible.access(), task, "sendTaskBack");
        String hash = idempotency.requestHash("sendTaskBack", Map.of("workspaceId", workspaceId.toString(), "taskId", taskId), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "sendTaskBack", key, hash);
        if (claim.isReplay()) return taskService.detail(taskService.visible(caller, workspaceId, taskId));
        if (!task.isOpen()) throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task is no longer open.");
        suspensions.requireNotSuspended(workspaceId, task.runId());
        TaskService.requireVersion(task, request.expectedVersion());
        taskService.requireNotSentBack(workspaceId, taskId);
        List<String> fields = taskService.declaredCorrectable(workspaceId, task);
        if (fields.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This step declares no request fields the requester may correct.");
        }
        String reason = reason(request.reason());
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO task_send_back (id, workspace_id, run_id, task_id, node_id, requester_id, fields, reason, status, sent_by)
                VALUES (:id, :ws, :run, :task, :node, :requester, CAST(:fields AS jsonb), :reason, 'open', :by)
                """).param("id", id).param("ws", workspaceId).param("run", task.runId()).param("task", taskId)
                .param("node", task.nodeId()).param("requester", task.initiator()).param("fields", json.write(fields))
                .param("reason", reason).param("by", caller.subjectId()).update();
        bump(workspaceId, taskId);
        events.append(workspaceId, task.runId(), "TASK_SENT_BACK", task.nodeId(), caller.subjectId(),
                Map.of("fields", fields.size(), "reason", reason));
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.sent-back",
                "task", taskId, AuditWriter.Outcome.SUCCESS, Map.of("taskId", taskId, "nodeId", task.nodeId(), "fields", fields.size())));
        idempotency.complete(claim, "taskSendBack", id.toString(), 200);
        return taskService.detail(taskService.visible(caller, workspaceId, taskId));
    }

    @Transactional
    public RuntimeDtos.TaskDetail withdraw(Caller caller, UUID workspaceId, String taskId, RuntimeDtos.WithdrawSendBackRequest request) {
        TaskService.VisibleTask visible = taskService.visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        boolean holder = caller.subjectId().equals(task.assignee());
        boolean operator = visible.access().has(Permission.RUN_OPERATE);
        if (!holder && !operator) throw ApiException.forbidden();
        if (holder) taskService.requireEligible(visible.access(), task, "withdrawTaskSendBack");
        TaskService.requireVersion(task, request.expectedVersion());
        String note = note(request.note());
        int closed = jdbc.sql("""
                UPDATE task_send_back SET status = 'withdrawn', closed_by = :by, closed_at = now(), note = :note
                WHERE workspace_id = :ws AND task_id = :task AND status = 'open'
                """).param("by", caller.subjectId()).param("note", note).param("ws", workspaceId).param("task", taskId).update();
        if (closed != 1) throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task has no open send-back.");
        bump(workspaceId, taskId);
        events.append(workspaceId, task.runId(), "TASK_SEND_BACK_WITHDRAWN", task.nodeId(), caller.subjectId(), Map.of());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.send-back.withdrawn",
                "task", taskId, AuditWriter.Outcome.SUCCESS, Map.of("taskId", taskId, "nodeId", task.nodeId())));
        return taskService.detail(taskService.visible(caller, workspaceId, taskId));
    }

    /** Send-backs of a request: its requester, its participants and run readers see them; values only with data access. */
    @Transactional(readOnly = true)
    public RuntimeDtos.SendBackList list(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        return new RuntimeDtos.SendBackList(rows(workspaceId, runId, null).stream().map(row -> view(visible, row)).toList());
    }

    @Transactional
    public RuntimeDtos.SendBack answer(Caller caller, UUID workspaceId, UUID runId, UUID sendBackId, String idempotencyKey,
            RuntimeDtos.AnswerSendBackRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        if (!visible.initiator()) throw new ApiException(ProblemCode.FORBIDDEN, "Only the requester answers a send-back.");
        String hash = idempotency.requestHash("answerSendBack", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString(), "sendBackId", sendBackId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "answerSendBack", key, hash);
        if (claim.isReplay()) return view(visible, rows(workspaceId, runId, sendBackId).getFirst());
        runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        Row row = rows(workspaceId, runId, sendBackId).stream().findFirst().orElseThrow(ApiException::notFound);
        if (!row.status().equals("open")) throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This send-back was already closed.");
        TaskStore.TaskRow task = tasks.lock(workspaceId, row.taskId()).orElseThrow(ApiException::notFound);
        if (!task.isOpen()) throw new ApiException(ProblemCode.TASK_NOT_OPEN, "The approval step is no longer open.");
        suspensions.requireNotSuspended(workspaceId, runId);
        TaskService.requireVersion(task, request.expectedVersion());
        String note = note(request.note());
        taskService.applyCorrection(caller, workspaceId, task, request.changes(), row.fields(),
                note == null ? "Corrected as requested: " + row.reason() : note, "sendBack");
        jdbc.sql("""
                UPDATE task_send_back SET status = 'answered', closed_by = :by, closed_at = now(), note = :note
                WHERE workspace_id = :ws AND id = :id AND status = 'open'
                """).param("by", caller.subjectId()).param("note", note).param("ws", workspaceId).param("id", sendBackId).update();
        events.append(workspaceId, runId, "TASK_SEND_BACK_ANSWERED", task.nodeId(), caller.subjectId(),
                Map.of("fields", request.changes().size()));
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.send-back.answered",
                "task", row.taskId(), AuditWriter.Outcome.SUCCESS, Map.of("taskId", row.taskId(), "fields", request.changes().size())));
        idempotency.complete(claim, "taskSendBackAnswer", sendBackId.toString(), 200);
        return view(runAccess.require(caller, workspaceId, runId), rows(workspaceId, runId, sendBackId).getFirst());
    }

    private record Row(UUID id, String taskId, String nodeId, String status, String reason, List<String> fields, UUID sentBy,
            java.time.Instant sentAt, java.time.Instant closedAt, String note) { }

    private List<Row> rows(UUID workspaceId, UUID runId, UUID only) {
        return jdbc.sql("""
                SELECT id, task_id, node_id, status, reason, fields::text AS fields, sent_by, sent_at, closed_at, note
                FROM task_send_back WHERE workspace_id = :ws AND run_id = :run AND (CAST(:only AS uuid) IS NULL OR id = :only)
                ORDER BY sent_at DESC, id LIMIT 50
                """).param("ws", workspaceId).param("run", runId).param("only", only)
                .query((rs, n) -> {
                    List<String> fields = new ArrayList<>();
                    json.parse(rs.getString("fields")).forEach(f -> fields.add(f.asString()));
                    return new Row(Sql.uuid(rs, "id"), rs.getString("task_id"), rs.getString("node_id"), rs.getString("status"),
                            rs.getString("reason"), fields, Sql.uuid(rs, "sent_by"), Sql.instant(rs, "sent_at"),
                            Sql.instant(rs, "closed_at"), rs.getString("note"));
                }).list();
    }

    private RuntimeDtos.SendBack view(RunAccess.Visible visible, Row row) {
        RunStore.RunRow run = runs.find(visible.run().workspaceId(), visible.run().id()).orElseThrow();
        ReleaseQueries.ReleaseView release = releases.require(run.workspaceId(), run.releaseId());
        JsonNode properties = release.inputSchema().path("properties");
        boolean data = visible.dataVisible();
        List<RuntimeDtos.SendBackField> fields = new ArrayList<>();
        for (String key : row.fields()) {
            JsonNode schema = properties.path(key);
            fields.add(new RuntimeDtos.SendBackField(key, schema.path("title").asString(key), schema.isMissingNode() ? null : schema,
                    data ? run.input().path(key).isMissingNode() ? null : run.input().get(key) : null));
        }
        TaskStore.TaskRow task = tasks.find(run.workspaceId(), row.taskId()).orElse(null);
        JsonNode node = release.node(row.nodeId());
        boolean canAnswer = visible.initiator() && row.status().equals("open") && task != null && task.isOpen()
                && !suspensions.isSuspended(run.workspaceId(), run.id());
        String name = subjects.find(row.sentBy()).map(SubjectService.Subject::displayName).orElse(null);
        return new RuntimeDtos.SendBack(row.id(), row.taskId(), row.nodeId(),
                node == null ? row.nodeId() : node.path("label").asString(row.nodeId()), row.status(), row.reason(), fields,
                new SubjectService.SubjectRef(row.sentBy(), name), row.sentAt(), row.closedAt(), row.note(),
                task == null ? 0 : task.version(), canAnswer);
    }

    private void bump(UUID workspaceId, String taskId) {
        TaskStore.TaskRow current = tasks.find(workspaceId, taskId).orElseThrow();
        tasks.bumpVersion(workspaceId, taskId, current.version());
    }

    private static String reason(String value) {
        String reason = value == null ? "" : value.strip();
        if (reason.isEmpty() || reason.length() > 500) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give a reason of 1 to 500 characters.");
        return reason;
    }

    private static String note(String value) {
        if (value == null || value.isBlank()) return null;
        String note = value.strip();
        if (note.length() > 500) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Keep the note to 500 characters.");
        return note;
    }
}
