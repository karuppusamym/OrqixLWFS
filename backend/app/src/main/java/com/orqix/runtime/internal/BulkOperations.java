package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.api.BulkDtos;
import com.orqix.runtime.api.RuntimeController;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.pagination.Limits;
import com.orqix.shared.pagination.Page;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-O01 (docs/build/S142): bulk operations by filter (retry, cancel, suspend, resume, migrate to a release).
 *
 * <ol>
 * <li><b>Dry run</b> ({@code run.operate} and the grant-only {@code run.bulk-operate}; field conditions also need
 * {@code run.read-data}): the filter (the run list's filters, which must narrow the runs) and the action's own state
 * rule select the runs. The exact matching runs are stored as items (at most the cap, default 1,000) and their count is
 * shown with a sample. A dry run that matches more than the cap stores no items and cannot be executed.</li>
 * <li><b>Execute</b>: only the person who ran the dry run, within 15 minutes, with the shown count confirmed, a reason
 * and an idempotency key. The items never grow: execution acts on the stored runs only, and each item checks the
 * filter again when it runs (a run that no longer matches is skipped), so the set can only shrink
 * (doc 29 section 4 row 12).</li>
 * <li><b>Background batch</b> ({@link BulkOperationWorker}): one item at a time at the operation's rate, through the
 * single-request operation with the confirmer's current permissions, its own idempotency key per item and the
 * single-request audit and run events. Pause, resume and cancel are version-checked.</li>
 * </ol>
 */
@Service
public class BulkOperations {

    public static final Duration PREVIEW_VALIDITY = Duration.ofMinutes(15);
    static final int SAMPLE = 10;

    public record Row(UUID id, UUID workspaceId, String action, JsonNode filter, UUID targetReleaseId, String state,
            int matchedCount, int cap, int ratePerMinute, String reason, String stopReason, UUID createdBy,
            Instant createdAt, Instant previewExpiresAt, Instant startedAt, Instant finishedAt, int succeeded,
            int failed, int skipped, int cancelledItems, int version, int items, Instant nextItemAt) {

        boolean exceedsCap() {
            return matchedCount > cap;
        }
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final RunQueries runQueries;
    private final RunSearchIndex searchIndex;
    private final ReleaseQueries releases;
    private final SubjectService subjects;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;
    private final CursorCodec cursors;
    private int maxItems;
    private final int defaultRate;

    public BulkOperations(JdbcClient jdbc, PolicyService policy, RunQueries runQueries, RunSearchIndex searchIndex,
            ReleaseQueries releases, SubjectService subjects, IdempotencyService idempotency, AuditWriter audit,
            Json json, Clock clock, CursorCodec cursors, @Value("${orqix.bulk.max-items:1000}") int maxItems,
            @Value("${orqix.bulk.default-rate-per-minute:60}") int defaultRate) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.runQueries = runQueries;
        this.searchIndex = searchIndex;
        this.releases = releases;
        this.subjects = subjects;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.cursors = cursors;
        this.maxItems = Math.max(1, Math.min(10_000, maxItems));
        this.defaultRate = Math.max(1, Math.min(600, defaultRate));
    }

    /** The cap of one operation (orqix.bulk.max-items, 1-10,000); tests lower it to reach the cap with few runs. */
    void maxItems(int value) {
        this.maxItems = Math.max(1, Math.min(10_000, value));
    }

    int maxItems() {
        return maxItems;
    }

    // ---------------------------------------------------------------- dry run

    @Transactional
    public BulkDtos.Operation preview(Caller caller, UUID workspaceId, BulkDtos.PreviewRequest request) {
        WorkspaceAccess access = operator(caller, workspaceId, "previewBulkOperation");
        BulkDtos.RunFilter f = request.filter();
        validateFilter(f);
        List<RunSearchIndex.Condition> conditions = List.of();
        if (f.fields() != null && !f.fields().isEmpty()) {
            policy.require(access, Permission.RUN_READ_DATA, "previewBulkOperation", true);
            conditions = searchIndex.parse(workspaceId, f.workflowId(), f.fields(), "/filter/fields");
        }
        UUID target = request.targetReleaseId();
        ReleaseQueries.ReleaseView release = null;
        if ("migrate".equals(request.action())) {
            if (target == null || f.workflowId() == null) {
                throw invalid("/targetReleaseId", "required", "Moving requests to a release needs the workflow in the"
                        + " filter and the target release.");
            }
            release = releases.find(workspaceId, target).orElse(null);
            if (release == null || !release.workflowId().equals(f.workflowId())) {
                throw invalid("/targetReleaseId", "enum", "Choose a release of the workflow in the filter.");
            }
        } else if (target != null) {
            throw invalid("/targetReleaseId", "type", "Only migrate takes a target release.");
        }
        int rate = request.ratePerMinute() == null ? defaultRate : request.ratePerMinute();
        ObjectNode stored = storedFilter(f, conditions);
        RunQueries.Filter filter = filter(stored);
        Map<String, Object> params = new HashMap<>();
        String where = runQueries.where(workspaceId, filter, params) + eligibility(request.action(), target, release,
                params);
        int matched = jdbc.sql("SELECT count(*) FROM workflow_run WHERE " + where).params(params)
                .query(Integer.class).single();
        UUID id = Ids.newId();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO bulk_operation (id, workspace_id, action, filter, target_release_id, state, matched_count,
                                            item_cap, rate_per_minute, created_by, created_at, preview_expires_at)
                VALUES (:id, :ws, :action, CAST(:filter AS jsonb), :target, 'previewed', :matched, :cap, :rate, :by,
                        :now, :expires)
                """)
                .param("id", id).param("ws", workspaceId).param("action", request.action())
                .param("filter", json.write(stored)).param("target", target).param("matched", matched)
                .param("cap", maxItems).param("rate", rate).param("by", caller.subjectId())
                .param("now", Sql.ts(now)).param("expires", Sql.ts(now.plus(PREVIEW_VALIDITY))).update();
        if (matched <= maxItems && matched > 0) {
            params.put("op", id);
            int inserted = jdbc.sql("""
                    INSERT INTO bulk_operation_item (operation_id, workspace_id, run_id, position, reference,
                                                     version_at_preview)
                    SELECT :op, workspace_id, id, row_number() OVER (ORDER BY created_at, id), reference, version
                    FROM workflow_run WHERE
                    """ + where).params(params).update();
            if (inserted != matched) {
                // Runs changed between the count and the copy: the stored items are the truth the person confirms.
                jdbc.sql("UPDATE bulk_operation SET matched_count = :n WHERE id = :id").param("n", inserted)
                        .param("id", id).update();
                matched = inserted;
                if (inserted > maxItems) {
                    jdbc.sql("DELETE FROM bulk_operation_item WHERE operation_id = :id").param("id", id).update();
                }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", request.action());
        metadata.put("matchedCount", matched);
        metadata.put("cap", maxItems);
        metadata.put("exceedsCap", matched > maxItems);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.bulk-previewed", "bulkOperation", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return view(find(workspaceId, id).orElseThrow(), true);
    }

    private static void validateFilter(BulkDtos.RunFilter f) {
        if (f.attentionReason() != null && !RuntimeController.ATTENTION_REASONS.contains(f.attentionReason())) {
            throw invalid("/filter/attentionReason", "enum", "Unknown attention reason.");
        }
        boolean narrows = f.workflowId() != null || f.businessKey() != null || f.createdFrom() != null
                || f.createdTo() != null || Boolean.TRUE.equals(f.overdue()) || f.attentionReason() != null
                || f.errorCode() != null || (f.fields() != null && !f.fields().isEmpty());
        if (!narrows) {
            throw invalid("/filter", "required", "Narrow the runs: choose a workflow, request reference, dates, the"
                    + " overdue filter, an attention reason, an error code or a field condition.");
        }
        if (f.fields() != null && !f.fields().isEmpty() && f.workflowId() == null) {
            throw invalid("/filter/workflowId", "required", "Choose a workflow to filter by its request fields.");
        }
    }

    /** The action's own state rule, as SQL on {@code workflow_run} (the single-request rules re-check each item). */
    private static String eligibility(String action, UUID target, ReleaseQueries.ReleaseView release,
            Map<String, Object> params) {
        return switch (action) {
            case "retry" -> " AND lifecycle = 'needsAttention'";
            case "cancel" -> " AND lifecycle IN ('running', 'waiting', 'needsAttention')";
            case "suspend" -> " AND lifecycle IN ('running', 'waiting', 'needsAttention')"
                    + " AND NOT EXISTS (SELECT 1 FROM run_suspension x WHERE x.run_id = workflow_run.id)";
            case "resume" -> " AND EXISTS (SELECT 1 FROM run_suspension x WHERE x.run_id = workflow_run.id)";
            case "migrate" -> {
                params.put("bulkTarget", target);
                params.put("bulkTargetWf", release.workflowId());
                yield " AND lifecycle IN ('running', 'waiting', 'needsAttention') AND workflow_id = :bulkTargetWf"
                        + " AND release_id <> :bulkTarget";
            }
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** True when the run still matches the operation's filter and state rule (checked again before each item). */
    @Transactional(readOnly = true)
    public boolean stillMatches(Row op, UUID runId) {
        Map<String, Object> params = new HashMap<>();
        ReleaseQueries.ReleaseView release = op.targetReleaseId() == null ? null
                : releases.find(op.workspaceId(), op.targetReleaseId()).orElse(null);
        if ("migrate".equals(op.action()) && release == null) {
            return false;
        }
        String where = runQueries.where(op.workspaceId(), filter(op.filter()), params)
                + eligibility(op.action(), op.targetReleaseId(), release, params) + " AND id = :bulkRun";
        params.put("bulkRun", runId);
        return jdbc.sql("SELECT count(*) FROM workflow_run WHERE " + where).params(params).query(Integer.class)
                .single() == 1;
    }

    private ObjectNode storedFilter(BulkDtos.RunFilter f, List<RunSearchIndex.Condition> conditions) {
        ObjectNode node = json.object();
        node.set("api", json.mapper().valueToTree(f));
        ArrayNode list = node.putArray("conditions");
        for (RunSearchIndex.Condition c : conditions) {
            ObjectNode o = list.addObject().put("key", c.key()).put("op", c.op());
            if (c.number() != null) {
                o.put("number", c.number());
            } else {
                o.put("text", c.text());
            }
        }
        return node;
    }

    RunQueries.Filter filter(JsonNode stored) {
        JsonNode api = stored.path("api");
        List<RunSearchIndex.Condition> conditions = new ArrayList<>();
        for (JsonNode c : stored.path("conditions")) {
            conditions.add(new RunSearchIndex.Condition(c.path("key").asString(), c.path("op").asString(),
                    c.has("text") ? c.path("text").asString() : null,
                    c.has("number") ? new BigDecimal(c.path("number").asString()) : null));
        }
        return new RunQueries.Filter(uuid(api.path("workflowId")), api.path("lifecycle").asString(null), null,
                api.path("businessKey").asString(null), instant(api.path("createdFrom")),
                instant(api.path("createdTo")), null, api.path("overdue").asBoolean(false), conditions,
                api.path("attentionReason").asString(null), api.path("errorCode").asString(null));
    }

    // ---------------------------------------------------------------- execute and control

    @Transactional
    public OperatorService.Result<BulkDtos.Operation> execute(Caller caller, UUID workspaceId, UUID id,
            String idempotencyKey, BulkDtos.ExecuteRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        WorkspaceAccess access = visible(caller, workspaceId);
        Row op = lock(workspaceId, id).orElseThrow(ApiException::notFound);
        require(access, "executeBulkOperation");
        String hash = idempotency.requestHash("executeBulkOperation", Map.of("workspaceId", workspaceId.toString(),
                "operationId", id.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "executeBulkOperation",
                key, hash);
        if (claim.isReplay()) {
            return new OperatorService.Result<>(view(op, false), true);
        }
        if (!op.createdBy().equals(caller.subjectId())) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Only the person who ran the dry run can confirm it; run"
                    + " your own dry run.");
        }
        if (request.reason().isBlank()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Give the reason for this bulk operation.");
        }
        if (!"previewed".equals(op.state())) {
            throw conflict(op, "This bulk operation was already confirmed or cancelled.");
        }
        Instant now = clock.instant();
        if (now.isAfter(op.previewExpiresAt())) {
            throw new ApiException(ProblemCode.BULK_PREVIEW_EXPIRED, "The dry run is older than 15 minutes. Run it"
                    + " again to see the current matching requests.");
        }
        if (op.exceedsCap()) {
            throw new ApiException(ProblemCode.BULK_LIMIT_EXCEEDED, "The filter matches " + op.matchedCount()
                    + " requests; one bulk operation changes at most " + op.cap() + ". Narrow the filter.")
                    .with("matchedCount", op.matchedCount()).with("cap", op.cap());
        }
        if (request.confirmedCount() != op.matchedCount()) {
            throw new ApiException(ProblemCode.BULK_COUNT_MISMATCH, "Confirm the number of requests the dry run"
                    + " showed (" + op.matchedCount() + ").").with("matchedCount", op.matchedCount());
        }
        if (op.matchedCount() == 0) {
            throw conflict(op, "No request matches this dry run, so there is nothing to do.");
        }
        jdbc.sql("""
                UPDATE bulk_operation SET state = 'running', reason = :reason, started_at = :now, next_item_at = :now,
                       version = version + 1
                WHERE id = :id
                """).param("reason", request.reason().strip()).param("now", Sql.ts(now)).param("id", id).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", op.action());
        metadata.put("matchedCount", op.matchedCount());
        metadata.put("ratePerMinute", op.ratePerMinute());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.bulk-started", "bulkOperation", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "bulkOperation", id.toString(), 200);
        return new OperatorService.Result<>(view(find(workspaceId, id).orElseThrow(), false), false);
    }

    @Transactional
    public BulkDtos.Operation pause(Caller caller, UUID workspaceId, UUID id, BulkDtos.ControlRequest request) {
        Row op = control(caller, workspaceId, id, request, "pauseBulkOperation");
        if (!"running".equals(op.state())) {
            throw conflict(op, "Only a running bulk operation can be paused.");
        }
        jdbc.sql("UPDATE bulk_operation SET state = 'paused', stop_reason = :why, lease_owner = NULL,"
                + " lease_until = NULL, version = version + 1 WHERE id = :id")
                .param("why", blankToNull(request.reason())).param("id", id).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.bulk-paused", "bulkOperation", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        return view(find(workspaceId, id).orElseThrow(), false);
    }

    @Transactional
    public BulkDtos.Operation resume(Caller caller, UUID workspaceId, UUID id, BulkDtos.ControlRequest request) {
        Row op = control(caller, workspaceId, id, request, "resumeBulkOperation");
        if (!"paused".equals(op.state())) {
            throw conflict(op, "Only a paused bulk operation can be resumed.");
        }
        if (!op.createdBy().equals(caller.subjectId())) {
            // The remaining items run with the confirmer's permissions, so only the confirmer continues them.
            throw new ApiException(ProblemCode.FORBIDDEN, "Only the person who confirmed this bulk operation can"
                    + " resume it; anyone with run.bulk-operate can cancel it.");
        }
        jdbc.sql("UPDATE bulk_operation SET state = 'running', stop_reason = NULL, next_item_at = :now,"
                + " version = version + 1 WHERE id = :id")
                .param("now", Sql.ts(clock.instant())).param("id", id).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.bulk-resumed", "bulkOperation", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        return view(find(workspaceId, id).orElseThrow(), false);
    }

    @Transactional
    public BulkDtos.Operation cancel(Caller caller, UUID workspaceId, UUID id, BulkDtos.ControlRequest request) {
        Row op = control(caller, workspaceId, id, request, "cancelBulkOperation");
        if ("completed".equals(op.state()) || "cancelled".equals(op.state())) {
            throw conflict(op, "This bulk operation has already ended.");
        }
        int cancelled = stopRemaining(op, "CANCELLED_BY_OPERATOR", "The bulk operation was cancelled.",
                blankToNull(request.reason()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("cancelledItems", cancelled);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.bulk-cancelled", "bulkOperation", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return view(find(workspaceId, id).orElseThrow(), false);
    }

    /** Cancels the pending items and ends the operation; returns how many items were cancelled. */
    @Transactional
    public int stopRemaining(Row op, String code, String message, String stopReason) {
        Instant now = clock.instant();
        int cancelled = jdbc.sql("""
                UPDATE bulk_operation_item SET state = 'cancelled', error_code = :code, message = :message,
                       finished_at = :now
                WHERE operation_id = :id AND state = 'pending'
                """).param("code", code).param("message", message).param("now", Sql.ts(now)).param("id", op.id())
                .update();
        jdbc.sql("""
                UPDATE bulk_operation SET state = 'cancelled', cancelled_items = cancelled_items + :n,
                       stop_reason = COALESCE(:why, stop_reason), finished_at = :now, lease_owner = NULL,
                       lease_until = NULL, next_item_at = NULL, version = version + 1
                WHERE id = :id
                """).param("n", cancelled).param("why", stopReason).param("now", Sql.ts(now)).param("id", op.id())
                .update();
        return cancelled;
    }

    private Row control(Caller caller, UUID workspaceId, UUID id, BulkDtos.ControlRequest request, String operation) {
        WorkspaceAccess access = visible(caller, workspaceId);
        Row op = lock(workspaceId, id).orElseThrow(ApiException::notFound);
        require(access, operation);
        if (op.version() != request.expectedVersion()) {
            throw conflict(op, "The bulk operation changed since you loaded it.");
        }
        return op;
    }

    // ---------------------------------------------------------------- reads (run.operate)

    @Transactional(readOnly = true)
    public BulkDtos.Operation get(Caller caller, UUID workspaceId, UUID id) {
        WorkspaceAccess access = visible(caller, workspaceId);
        Row op = find(workspaceId, id).orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "getBulkOperation", false);
        return view(op, true);
    }

    @Transactional(readOnly = true)
    public BulkDtos.OperationPage list(Caller caller, UUID workspaceId, String cursor, Integer limit) {
        WorkspaceAccess access = visible(caller, workspaceId);
        policy.require(access, Permission.RUN_OPERATE, "listBulkOperations", false);
        int size = Limits.of(limit);
        String filterKey = "listBulkOperations|" + workspaceId;
        CursorCodec.Position after = cursors.decode(cursor, filterKey);
        List<Row> rows = jdbc.sql("SELECT " + COLUMNS + """
                 FROM bulk_operation o WHERE o.workspace_id = :ws
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (o.created_at, o.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY o.created_at DESC, o.id DESC LIMIT :limit
                """)
                .param("ws", workspaceId).param("afterAt", after == null ? null : Sql.ts(after.at()))
                .param("afterId", after == null ? null : UUID.fromString(after.id())).param("limit", size + 1)
                .query(this::map).list();
        Page.Result<Row> page = Page.of(rows, size, r -> new CursorCodec.Position(r.createdAt(), r.id().toString()),
                cursors, filterKey);
        return new BulkDtos.OperationPage(page.items().stream().map(r -> view(r, false)).toList(), page.nextCursor());
    }

    @Transactional(readOnly = true)
    public BulkDtos.ItemPage items(Caller caller, UUID workspaceId, UUID id, String state, String cursor,
            Integer limit) {
        WorkspaceAccess access = visible(caller, workspaceId);
        find(workspaceId, id).orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "listBulkOperationItems", false);
        if (state != null && !List.of("pending", "succeeded", "failed", "skipped", "cancelled").contains(state)) {
            throw ApiException.malformed("Unknown item state.");
        }
        int size = Limits.of(limit);
        String filterKey = "listBulkOperationItems|" + id + "|" + state;
        CursorCodec.Position after = cursors.decode(cursor, filterKey);
        List<BulkDtos.Item> rows = items(id, state, after == null ? 0 : Integer.parseInt(after.id()), size + 1);
        Page.Result<BulkDtos.Item> page = Page.of(rows, size,
                i -> new CursorCodec.Position(Instant.EPOCH, Integer.toString(i.position())), cursors, filterKey);
        return new BulkDtos.ItemPage(page.items(), page.nextCursor());
    }

    private List<BulkDtos.Item> items(UUID operationId, String state, int afterPosition, int limit) {
        return jdbc.sql("""
                SELECT run_id, reference, position, state, error_code, message, finished_at FROM bulk_operation_item
                WHERE operation_id = :id AND (CAST(:state AS text) IS NULL OR state = :state) AND position > :after
                ORDER BY position LIMIT :limit
                """)
                .param("id", operationId).param("state", state).param("after", afterPosition).param("limit", limit)
                .query((rs, n) -> new BulkDtos.Item(Sql.uuid(rs, "run_id"), rs.getString("reference"),
                        rs.getInt("position"), rs.getString("state"), rs.getString("error_code"),
                        rs.getString("message"), Sql.instant(rs, "finished_at")))
                .list();
    }

    // ---------------------------------------------------------------- helpers

    private WorkspaceAccess visible(Caller caller, UUID workspaceId) {
        return policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
    }

    private WorkspaceAccess operator(Caller caller, UUID workspaceId, String operation) {
        WorkspaceAccess access = visible(caller, workspaceId);
        require(access, operation);
        return access;
    }

    private void require(WorkspaceAccess access, String operation) {
        policy.require(access, Permission.RUN_OPERATE, operation, true);
        policy.require(access, Permission.RUN_BULK_OPERATE, operation, true);
    }

    private static ApiException conflict(Row op, String message) {
        return new ApiException(ProblemCode.BULK_OPERATION_STATE_CONFLICT, message).with("currentVersion", op.version())
                .with("state", op.state());
    }

    private static ApiException invalid(String pointer, String code, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, code, message)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static UUID uuid(JsonNode node) {
        return node.isString() ? UUID.fromString(node.asString()) : null;
    }

    private static Instant instant(JsonNode node) {
        if (node.isString()) {
            return Instant.parse(node.asString());
        }
        if (node.isNumber()) {
            // Jackson may write instants as decimal epoch seconds.
            BigDecimal seconds = node.decimalValue();
            return Instant.ofEpochSecond(seconds.longValue(), seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        return null;
    }

    static final String COLUMNS = """
            o.id, o.workspace_id, o.action, o.filter::text AS filter, o.target_release_id, o.state, o.matched_count,
            o.item_cap, o.rate_per_minute, o.reason, o.stop_reason, o.created_by, o.created_at, o.preview_expires_at,
            o.started_at, o.finished_at, o.succeeded, o.failed, o.skipped, o.cancelled_items, o.version, o.next_item_at,
            (SELECT count(*) FROM bulk_operation_item i WHERE i.operation_id = o.id) AS items
            """;

    Row map(ResultSet rs, int n) throws SQLException {
        return new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("action"),
                json.parse(rs.getString("filter")), Sql.uuid(rs, "target_release_id"), rs.getString("state"),
                rs.getInt("matched_count"), rs.getInt("item_cap"), rs.getInt("rate_per_minute"), rs.getString("reason"),
                rs.getString("stop_reason"), Sql.uuid(rs, "created_by"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "preview_expires_at"), Sql.instant(rs, "started_at"), Sql.instant(rs, "finished_at"),
                rs.getInt("succeeded"), rs.getInt("failed"), rs.getInt("skipped"), rs.getInt("cancelled_items"),
                rs.getInt("version"), rs.getInt("items"), Sql.instant(rs, "next_item_at"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Row> find(UUID workspaceId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bulk_operation o WHERE o.workspace_id = :ws AND o.id = :id")
                .param("ws", workspaceId).param("id", id).query(this::map).optional();
    }

    @Transactional
    java.util.Optional<Row> lock(UUID workspaceId, UUID id) {
        jdbc.sql("SELECT id FROM bulk_operation WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", workspaceId).param("id", id).query(UUID.class).optional();
        return find(workspaceId, id);
    }

    BulkDtos.Operation view(Row op, boolean withSample) {
        BulkDtos.TargetRelease target = op.targetReleaseId() == null ? null
                : new BulkDtos.TargetRelease(op.targetReleaseId(), releases.find(op.workspaceId(), op.targetReleaseId())
                        .map(ReleaseQueries.ReleaseView::releaseNumber).orElse(0));
        int done = op.succeeded() + op.failed() + op.skipped() + op.cancelledItems();
        BulkDtos.Progress progress = new BulkDtos.Progress(op.items(), Math.max(0, op.items() - done), op.succeeded(),
                op.failed(), op.skipped(), op.cancelledItems());
        BulkDtos.RunFilter filter = json.mapper().treeToValue(op.filter().path("api"), BulkDtos.RunFilter.class);
        List<BulkDtos.Item> sample = withSample ? items(op.id(), null, 0, SAMPLE) : List.of();
        return new BulkDtos.Operation(op.id(), op.action(), op.state(), filter, target, op.matchedCount(), op.cap(),
                op.exceedsCap(), op.ratePerMinute(), op.reason(), op.stopReason(), subjects.ref(op.createdBy()),
                op.createdAt(), op.previewExpiresAt(), op.startedAt(), op.finishedAt(), progress, sample, op.version());
    }
}
