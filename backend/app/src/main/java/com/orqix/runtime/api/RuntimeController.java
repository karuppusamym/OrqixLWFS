package com.orqix.runtime.api;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.notification.NotificationService;
import com.orqix.runtime.internal.OperatorService;
import com.orqix.runtime.internal.RunQueries;
import com.orqix.runtime.internal.RunService;
import com.orqix.runtime.internal.TaskService;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.pagination.Limits;
import com.orqix.shared.pagination.Page;
import com.orqix.shared.problem.ApiException;

/** Runs, tasks and notifications (contracts/openapi.yaml, tags Runs, Tasks, Notifications). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class RuntimeController {

    private static final Set<String> LIFECYCLES = Set.of("running", "waiting", "needsAttention", "completed", "cancelled");

    private final CallerResolver callers;
    private final RunService runs;
    private final TaskService tasks;
    private final OperatorService operator;
    private final NotificationService notifications;
    private final PolicyService policy;
    private final CursorCodec cursors;

    public RuntimeController(CallerResolver callers, RunService runs, TaskService tasks, OperatorService operator,
            NotificationService notifications, PolicyService policy, CursorCodec cursors) {
        this.callers = callers;
        this.runs = runs;
        this.tasks = tasks;
        this.operator = operator;
        this.notifications = notifications;
        this.policy = policy;
        this.cursors = cursors;
    }

    // ---------------------------------------------------------------- runs

    @PostMapping("/runs")
    public ResponseEntity<RuntimeDtos.RunSummary> startRun(@PathVariable UUID workspaceId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RuntimeDtos.StartRunRequest request) {
        RunService.Started started = runs.start(callers.current(), workspaceId, idempotencyKey, request);
        if (started.replayed()) {
            return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, "true").body(started.run());
        }
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/runs/" + started.run().id()))
                .body(started.run());
    }

    @GetMapping("/runs")
    public RuntimeDtos.RunPage listRuns(@PathVariable UUID workspaceId, @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) String lifecycle, @RequestParam(required = false) String outcomeKind,
            @RequestParam(required = false) String businessKey, @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (lifecycle != null && !LIFECYCLES.contains(lifecycle)) {
            throw ApiException.malformed("Unknown lifecycle.");
        }
        if (outcomeKind != null && !Set.of("success", "rejected").contains(outcomeKind)) {
            throw ApiException.malformed("Unknown outcomeKind.");
        }
        if (businessKey != null && businessKey.length() > 100) {
            throw ApiException.malformed("businessKey is too long.");
        }
        RunQueries.Filter filter = new RunQueries.Filter(workflowId, lifecycle, outcomeKind, businessKey,
                instant(createdFrom), instant(createdTo), null);
        return runs.listRuns(callers.current(), workspaceId, filter, cursor, limit);
    }

    @GetMapping("/runs/{runId}")
    public RuntimeDtos.RunDetail getRun(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return runs.getRun(callers.current(), workspaceId, runId);
    }

    @GetMapping("/runs/{runId}/events")
    public RuntimeDtos.EventPage listRunEvents(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestParam(required = false) Long after, @RequestParam(required = false) Integer limit) {
        return runs.events(callers.current(), workspaceId, runId, after, limit);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<RuntimeDtos.RunDetail> cancelRun(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RuntimeDtos.CancelRunRequest request) {
        return replayAware(operator.cancel(callers.current(), workspaceId, runId, idempotencyKey, request));
    }

    @PostMapping("/runs/{runId}/retry")
    public ResponseEntity<RuntimeDtos.RunDetail> retryRun(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RuntimeDtos.RetryRunRequest request) {
        return replayAware(operator.retry(callers.current(), workspaceId, runId, idempotencyKey, request));
    }

    @PostMapping("/runs/{runId}/effects/{effectId}/reconcile")
    public ResponseEntity<RuntimeDtos.Effect> reconcileEffect(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID effectId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RuntimeDtos.ReconcileEffectRequest request) {
        return replayAware(operator.reconcile(callers.current(), workspaceId, runId, effectId, idempotencyKey, request));
    }

    @GetMapping("/me/requests")
    public RuntimeDtos.RunPage listMyRequests(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String lifecycle, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (lifecycle != null && !LIFECYCLES.contains(lifecycle)) {
            throw ApiException.malformed("Unknown lifecycle.");
        }
        return runs.listMyRequests(callers.current(), workspaceId, lifecycle, cursor, limit);
    }

    // ---------------------------------------------------------------- tasks

    @GetMapping("/tasks")
    public RuntimeDtos.TaskPage listTasks(@PathVariable UUID workspaceId, @RequestParam(required = false) String view,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return tasks.listTasks(callers.current(), workspaceId, view, cursor, limit);
    }

    @GetMapping("/tasks/{taskId}")
    public RuntimeDtos.TaskDetail getTask(@PathVariable UUID workspaceId, @PathVariable String taskId) {
        return tasks.getTask(callers.current(), workspaceId, taskId(taskId));
    }

    @PostMapping("/tasks/{taskId}/claim")
    public RuntimeDtos.TaskDetail claimTask(@PathVariable UUID workspaceId, @PathVariable String taskId,
            @Valid @RequestBody RuntimeDtos.VersionedCommand request) {
        return tasks.claim(callers.current(), workspaceId, taskId(taskId), request.expectedVersion());
    }

    @PostMapping("/tasks/{taskId}/release")
    public RuntimeDtos.TaskDetail releaseTask(@PathVariable UUID workspaceId, @PathVariable String taskId,
            @Valid @RequestBody RuntimeDtos.VersionedCommand request) {
        return tasks.release(callers.current(), workspaceId, taskId(taskId), request.expectedVersion());
    }

    @PostMapping("/tasks/{taskId}/decision")
    public ResponseEntity<RuntimeDtos.TaskDecision> decideTask(@PathVariable UUID workspaceId,
            @PathVariable String taskId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RuntimeDtos.DecisionRequest request) {
        TaskService.Decided decided = tasks.decide(callers.current(), workspaceId, taskId(taskId), idempotencyKey, request);
        if (decided.replayed()) {
            return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, "true").body(decided.decision());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(decided.decision());
    }

    @PostMapping("/tasks/{taskId}/reassign")
    public RuntimeDtos.TaskDetail reassignTask(@PathVariable UUID workspaceId, @PathVariable String taskId,
            @Valid @RequestBody RuntimeDtos.ReassignRequest request) {
        return tasks.reassign(callers.current(), workspaceId, taskId(taskId), request);
    }

    @GetMapping("/tasks/{taskId}/delegates")
    public java.util.List<com.orqix.identity.SubjectService.SubjectRef> listTaskDelegates(@PathVariable UUID workspaceId,
            @PathVariable String taskId) {
        return tasks.delegates(callers.current(), workspaceId, taskId(taskId));
    }

    @PostMapping("/tasks/{taskId}/delegate")
    public RuntimeDtos.TaskDetail delegateTask(@PathVariable UUID workspaceId, @PathVariable String taskId,
            @Valid @RequestBody RuntimeDtos.ReassignRequest request) {
        return tasks.delegate(callers.current(), workspaceId, taskId(taskId), request);
    }

    // ---------------------------------------------------------------- notifications

    @GetMapping("/me/notifications")
    public RuntimeDtos.NotificationPage listMyNotifications(@PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "false") boolean unread, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        Caller caller = callers.current();
        WorkspaceAccess access = policy.requireMember(caller, workspaceId);
        int size = Limits.of(limit);
        String filterKey = "listMyNotifications|" + workspaceId + "|" + caller.subjectId() + "|" + unread;
        List<NotificationService.NotificationRow> rows = notifications.list(workspaceId, caller.subjectId(), unread,
                cursors.decode(cursor, filterKey), size + 1);
        Page.Result<NotificationService.NotificationRow> page = Page.of(rows, size,
                n -> new CursorCodec.Position(n.createdAt(), n.id().toString()), cursors, filterKey);
        Map<UUID, String> titles = runs.requestTitles(access, page.items().stream()
                .map(NotificationService.NotificationRow::runId).filter(java.util.Objects::nonNull).distinct().toList());
        return new RuntimeDtos.NotificationPage(page.items().stream()
                .map(n -> new RuntimeDtos.Notification(n.id(), n.runId(), n.runReference(), titles.get(n.runId()), n.kind(), n.message(),
                        n.createdAt(), n.readAt()))
                .toList(), page.nextCursor(), notifications.unreadCount(workspaceId, caller.subjectId()));
    }

    @PostMapping("/notifications/{notificationId}/read")
    public ResponseEntity<Void> markNotificationRead(@PathVariable UUID workspaceId, @PathVariable UUID notificationId) {
        Caller caller = callers.current();
        policy.requireVisible(caller, workspaceId);
        if (!notifications.markRead(workspaceId, caller.subjectId(), notificationId)) {
            throw ApiException.notFound();
        }
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- helpers

    private static <T> ResponseEntity<T> replayAware(OperatorService.Result<T> result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.replayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(result.body());
    }

    private static String taskId(String taskId) {
        if (!taskId.matches("^[A-Za-z0-9-]{1,64}$")) {
            throw ApiException.notFound();
        }
        return taskId;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.malformed("Timestamps must be RFC 3339 UTC.");
        }
    }
}
