package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.orqix.identity.PolicyService;
import com.orqix.notification.NotificationService;
import com.orqix.runtime.api.MigrationMoveDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Guarded move of a request between the steps of its release (CG-O03, docs/build/S143): move, skip or re-run, each
 * planned by {@link MoveRules} and refused with reasons when it could duplicate an effect or leave a step without its
 * inputs. Every move needs run.operate, an idempotency key, a reason and the version the operator looked at. In a
 * production or regulated workspace a second operator approves it; the version must still hold at that moment.
 */
@Service
public class RunMoves {

    /** How long a move waits for its second operator before it lapses. */
    static final Duration APPROVAL_WINDOW = Duration.ofHours(24);

    private final PolicyService policy;
    private final RunAccess runAccess;
    private final RunStore runs;
    private final TaskStore tasks;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final NotificationService notifications;
    private final RunSuspensions suspensions;
    private final RunFacts facts;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public RunMoves(PolicyService policy, RunAccess runAccess, RunStore runs, TaskStore tasks, RunEvents events,
            ReleaseQueries releases, EngineCommands engine, IdempotencyService idempotency, AuditWriter audit,
            NotificationService notifications, RunSuspensions suspensions, RunFacts facts,
            JdbcClient jdbc, Json json,
            Clock clock) {
        this.policy = policy;
        this.runAccess = runAccess;
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.releases = releases;
        this.engine = engine;
        this.idempotency = idempotency;
        this.audit = audit;
        this.notifications = notifications;
        this.suspensions = suspensions;
        this.facts = facts;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- plan (dry run)

    @Transactional
    public MigrationMoveDtos.MovePlan plan(Caller caller, UUID workspaceId, UUID runId,
            MigrationMoveDtos.MovePlanRequest request) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "planRunMove", true);
        RunStore.RunRow run = visible.run();
        MoveRules.Plan plan = rules(run, request.kind(), request.fromStepId(), request.toStepId());
        String fourEyes = facts.fourEyesReason(workspaceId);
        return new MigrationMoveDtos.MovePlan(plan.kind(), plan.from(), plan.fromLabel(), plan.to(), plan.toLabel(),
                plan.allowed(), run.version(), fourEyes != null, fourEyes, affected(plan.skipped()),
                affected(plan.rerun()), MappedMigrations.findings(plan.findings()));
    }

    MoveRules.Plan rules(RunStore.RunRow run, String kind, String from, String to) {
        ReleaseQueries.ReleaseView release = releases.require(run.workspaceId(), run.releaseId());
        List<ReleaseCompatibility.Finding> extra = new ArrayList<>();
        if (!run.isActive()) {
            extra.add(new ReleaseCompatibility.Finding("RUN_ENDED", "The request has ended.", null));
            return new MoveRules.Plan(kind, from, release.nodeLabel(from), to, release.nodeLabel(to), List.of(),
                    List.of(), extra);
        }
        if (suspensions.isSuspended(run.workspaceId(), run.id())) {
            extra.add(new ReleaseCompatibility.Finding("RUN_SUSPENDED", "Resume the request first: a move lets the"
                    + " workflow continue from the new step at once.", null));
        }
        if (runs.children(run.workspaceId(), run.id()).stream().anyMatch(RunStore.RunRow::isActive)) {
            extra.add(new ReleaseCompatibility.Finding("CHILD_RUNNING", "The request has running child requests;"
                    + " they would be left without a parent step.", null));
        }
        MoveRules.Plan plan = MoveRules.plan(release.definition(), facts.moveFacts(run), kind, from, to);
        if (plan.findings().isEmpty() && !engine.hasFlowNode(release.processDefinitionId(), to)) {
            extra.add(new ReleaseCompatibility.Finding("MOVE_TARGET_KIND", "The step '" + plan.toLabel()
                    + "' cannot be entered directly in the workflow engine.", to));
        }
        List<ReleaseCompatibility.Finding> all = new ArrayList<>(extra);
        all.addAll(plan.findings());
        return new MoveRules.Plan(plan.kind(), plan.from(), plan.fromLabel(), plan.to(), plan.toLabel(),
                plan.skipped(), plan.rerun(), all);
    }

    // ---------------------------------------------------------------- request (executes at once without four eyes)

    @Transactional
    public OperatorService.Result<MigrationMoveDtos.RunMove> request(Caller caller, UUID workspaceId, UUID runId,
            String idempotencyKey, MigrationMoveDtos.MoveRunRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "moveRun", true);
        String hash = idempotency.requestHash("moveRun", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "moveRun", key, hash);
        if (claim.isReplay()) {
            return new OperatorService.Result<>(view(workspaceId, runId, UUID.fromString(claim.replay().resourceId())),
                    true);
        }
        RunStore.RunRow run = lockAndCheck(workspaceId, runId, request.expectedVersion());
        MoveRules.Plan plan = rules(run, request.kind(), request.fromStepId(), request.toStepId());
        refuseIfNotAllowed(plan, run);
        expirePending(workspaceId, runId);
        String fourEyes = facts.fourEyesReason(workspaceId);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO run_move (id, workspace_id, run_id, kind, from_node_id, to_node_id, reason, expected_version,
                                      plan, approval_required, state, requested_by, requested_at, expires_at)
                VALUES (:id, :ws, :run, :kind, :from, :to, :reason, :version, CAST(:plan AS jsonb), :required,
                        'pendingApproval', :by, :now, :expires)
                """).param("id", id).param("ws", workspaceId).param("run", runId).param("kind", plan.kind())
                .param("from", plan.from()).param("to", plan.to()).param("reason", request.reason())
                .param("version", request.expectedVersion()).param("plan", json.write(planJson(plan)))
                .param("required", fourEyes != null).param("by", caller.subjectId()).param("now", Sql.ts(now))
                .param("expires", fourEyes == null ? null : Sql.ts(now.plus(APPROVAL_WINDOW))).update();
        if (fourEyes != null) {
            // The request itself is not changed: its version must still match when the second operator approves.
            events.append(workspaceId, runId, "RUN_MOVE_REQUESTED", plan.from(), caller.subjectId(),
                    eventData(plan, request.reason(), id, null));
            audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                    "run.move-requested", "run", runId.toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("kind", plan.kind())));
        } else {
            execute(run, plan, request.reason(), caller.subjectId(), null, id);
        }
        idempotency.complete(claim, "runMove", id.toString(), 200);
        return new OperatorService.Result<>(view(workspaceId, runId, id), false);
    }

    // ---------------------------------------------------------------- second operator

    @Transactional
    public OperatorService.Result<MigrationMoveDtos.RunMove> approve(Caller caller, UUID workspaceId, UUID runId,
            UUID moveId, String idempotencyKey, MigrationMoveDtos.DecideMoveRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "approveRunMove", true);
        String hash = idempotency.requestHash("approveRunMove", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString(), "moveId", moveId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "approveRunMove", key, hash);
        if (claim.isReplay()) {
            return new OperatorService.Result<>(view(workspaceId, runId, moveId), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        Pending move = pending(workspaceId, runId, moveId);
        if (move.requestedBy().equals(caller.subjectId())) {
            throw new ApiException(ProblemCode.MOVE_SAME_OPERATOR, "Another operator must approve this move; you"
                    + " requested it.");
        }
        Instant now = clock.instant();
        if (move.expiresAt() != null && !now.isBefore(move.expiresAt())) {
            // Answered with the lapsed move (state expired) rather than an error, so the state change is kept.
            close(workspaceId, moveId, "expired", null, null);
            idempotency.complete(claim, "runMove", moveId.toString(), 200);
            return new OperatorService.Result<>(view(workspaceId, runId, moveId), false);
        }
        String staleReason = !run.isActive() ? "The request has ended."
                : run.version() != move.expectedVersion() ? "The request changed since the move was planned." : null;
        MoveRules.Plan plan = staleReason == null ? rules(run, move.kind(), move.from(), move.to()) : null;
        if (staleReason == null && !plan.allowed()) {
            staleReason = plan.findings().getFirst().message();
        }
        if (staleReason != null) {
            close(workspaceId, moveId, "stale", caller.subjectId(), request.note());
            events.append(workspaceId, runId, "RUN_MOVE_LAPSED", move.from(), caller.subjectId(),
                    Map.of("moveId", moveId.toString(), "why", staleReason));
            // Answered with the lapsed move (state stale): nothing ran, and the lapse stays recorded.
            idempotency.complete(claim, "runMove", moveId.toString(), 200);
            return new OperatorService.Result<>(view(workspaceId, runId, moveId), false);
        }
        jdbc.sql("""
                UPDATE run_move SET decided_by = :by, decided_at = :now, decision_note = :note, version = version + 1
                WHERE workspace_id = :ws AND id = :id
                """).param("by", caller.subjectId()).param("now", Sql.ts(now)).param("note", request.note())
                .param("ws", workspaceId).param("id", moveId).update();
        execute(run, plan, move.reason(), move.requestedBy(), caller.subjectId(), moveId);
        idempotency.complete(claim, "runMove", moveId.toString(), 200);
        return new OperatorService.Result<>(view(workspaceId, runId, moveId), false);
    }

    /** Reject (another operator) or withdraw (the requester) a move that waits for approval. */
    @Transactional
    public OperatorService.Result<MigrationMoveDtos.RunMove> reject(Caller caller, UUID workspaceId, UUID runId,
            UUID moveId, String idempotencyKey, MigrationMoveDtos.DecideMoveRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "rejectRunMove", true);
        String hash = idempotency.requestHash("rejectRunMove", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString(), "moveId", moveId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "rejectRunMove", key, hash);
        if (claim.isReplay()) {
            return new OperatorService.Result<>(view(workspaceId, runId, moveId), true);
        }
        runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        Pending move = pending(workspaceId, runId, moveId);
        boolean withdrawn = move.requestedBy().equals(caller.subjectId());
        String state = withdrawn ? "withdrawn" : "rejected";
        close(workspaceId, moveId, state, caller.subjectId(), request.note());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moveId", moveId.toString());
        data.put("state", state);
        if (request.note() != null) {
            data.put("note", request.note());
        }
        events.append(workspaceId, runId, "RUN_MOVE_REJECTED", move.from(), caller.subjectId(), data);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.move-rejected", "run", runId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("kind", move.kind(), "state", state)));
        idempotency.complete(claim, "runMove", moveId.toString(), 200);
        return new OperatorService.Result<>(view(workspaceId, runId, moveId), false);
    }

    @Transactional(readOnly = true)
    public MigrationMoveDtos.RunMoveList list(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "listRunMoves", true);
        return new MigrationMoveDtos.RunMoveList(jdbc.sql("SELECT " + COLUMNS
                + " FROM run_move WHERE workspace_id = :ws AND run_id = :run ORDER BY requested_at DESC, id LIMIT 50")
                .param("ws", workspaceId).param("run", runId).query((rs, n) -> map(rs)).list());
    }

    // ---------------------------------------------------------------- execution

    /**
     * Moves the engine token and closes what Orqix kept open for the step it leaves; the engine listeners project the
     * new step. Runs under the run lock taken by the caller.
     */
    private void execute(RunStore.RunRow run, MoveRules.Plan plan, String reason, UUID requestedBy, UUID approvedBy,
            UUID moveId) {
        UUID ws = run.workspaceId();
        Instant now = clock.instant();
        // Orqix closes what it kept open for the step first: when the step runs again (a re-run of the same step) the
        // engine listeners open its new task while the token arrives.
        tasks.cancelOpenForNode(ws, run.id(), plan.from(), now);
        runs.mutate(ws, run.id(), m -> {
            m.removeStep(plan.from());
            if (RunStore.NEEDS_ATTENTION.equals(m.lifecycle)) {
                m.lifecycle = RunStore.RUNNING;
                m.attention = null;
            }
            m.settle();
        });
        List<String> left;
        try {
            left = engine.moveStep(run.engineProcessInstanceId(), plan.from(), plan.to(),
                    approvedBy != null ? approvedBy : requestedBy);
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "A step of this run is executing; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        if (left.isEmpty()) {
            throw new ApiException(ProblemCode.RUN_NOT_MOVABLE, "The workflow engine is not at the step '"
                    + plan.fromLabel() + "'. Check the request's consistency first.");
        }
        jdbc.sql("UPDATE run_move SET state = 'executed', executed_at = :now, version = version + 1"
                + " WHERE workspace_id = :ws AND id = :id")
                .param("now", Sql.ts(now)).param("ws", ws).param("id", moveId).update();
        Map<String, Object> data = eventData(plan, reason, moveId, approvedBy);
        data.put("requestedBy", requestedBy.toString());
        events.append(ws, run.id(), "RUN_MOVED", plan.to(), approvedBy != null ? approvedBy : requestedBy, data);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", plan.kind());
        metadata.put("fourEyes", approvedBy != null);
        metadata.put("rerun", plan.rerun().size());
        metadata.put("skipped", plan.skipped().size());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, approvedBy != null ? approvedBy : requestedBy,
                "run.moved", "run", run.id().toString(), AuditWriter.Outcome.SUCCESS, metadata));
        String what = switch (plan.kind()) {
            case "skip" -> "skipped the step '" + plan.fromLabel() + "'";
            case "rerun" -> "sent it back to '" + plan.toLabel() + "'";
            default -> "moved it to '" + plan.toLabel() + "'";
        };
        if (notifications.create(ws, run.initiator(), run.id(), NotificationService.Kind.WORKFLOW_MESSAGE,
                run.id() + ":moved:" + moveId, "An operator " + what + " in your request.")) {
            events.append(ws, run.id(), "NOTIFICATION_CREATED", null, null,
                    Map.of("kind", NotificationService.Kind.WORKFLOW_MESSAGE.value(), "recipient", "initiator"));
        }
    }

    private Map<String, Object> eventData(MoveRules.Plan plan, String reason, UUID moveId, UUID approvedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moveId", moveId.toString());
        data.put("kind", plan.kind());
        data.put("from", plan.from());
        data.put("fromLabel", plan.fromLabel());
        data.put("to", plan.to());
        data.put("toLabel", plan.toLabel());
        data.put("reason", reason);
        data.put("rerun", plan.rerun().stream().map(MoveRules.Affected::nodeId).toList());
        data.put("reusedResults", plan.rerun().stream().filter(a -> "reuseRecorded".equals(a.handling()))
                .map(MoveRules.Affected::nodeId).toList());
        data.put("skipped", plan.skipped().stream().map(MoveRules.Affected::nodeId).toList());
        if (approvedBy != null) {
            data.put("approvedBy", approvedBy.toString());
        }
        return data;
    }

    private RunStore.RunRow lockAndCheck(UUID workspaceId, UUID runId, int expectedVersion) {
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        if (run.version() != expectedVersion) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run changed since you loaded it.")
                    .with("currentVersion", run.version());
        }
        return run;
    }

    private static void refuseIfNotAllowed(MoveRules.Plan plan, RunStore.RunRow run) {
        if (!plan.allowed()) {
            throw new ApiException(ProblemCode.RUN_NOT_MOVABLE, plan.findings().getFirst().message())
                    .with("findings", MappedMigrations.findings(plan.findings())).with("currentVersion", run.version());
        }
    }

    private void expirePending(UUID workspaceId, UUID runId) {
        Instant now = clock.instant();
        int pending = jdbc.sql("""
                UPDATE run_move SET state = 'expired', version = version + 1
                WHERE workspace_id = :ws AND run_id = :run AND state = 'pendingApproval' AND expires_at <= :now
                """).param("ws", workspaceId).param("run", runId).param("now", Sql.ts(now)).update();
        if (jdbc.sql("SELECT count(*) FROM run_move WHERE workspace_id = :ws AND run_id = :run AND state = 'pendingApproval'")
                .param("ws", workspaceId).param("run", runId).query(Integer.class).single() > 0) {
            throw new ApiException(ProblemCode.RUN_NOT_MOVABLE, "Another move of this request waits for approval."
                    + " Approve, reject or withdraw it first.");
        }
        assert pending >= 0;
    }

    private void close(UUID workspaceId, UUID moveId, String state, UUID by, String note) {
        jdbc.sql("""
                UPDATE run_move SET state = :state, decided_by = COALESCE(:by, decided_by),
                       decided_at = CASE WHEN CAST(:by AS uuid) IS NULL THEN decided_at ELSE :now END,
                       decision_note = COALESCE(:note, decision_note), version = version + 1
                WHERE workspace_id = :ws AND id = :id
                """).param("state", state).param("by", by).param("now", Sql.ts(clock.instant())).param("note", note)
                .param("ws", workspaceId).param("id", moveId).update();
    }

    private record Pending(UUID id, String kind, String from, String to, String reason, int expectedVersion,
            UUID requestedBy, Instant expiresAt) {
    }

    private Pending pending(UUID workspaceId, UUID runId, UUID moveId) {
        var row = jdbc.sql("""
                SELECT id, kind, from_node_id, to_node_id, reason, expected_version, requested_by, expires_at, state
                FROM run_move WHERE workspace_id = :ws AND run_id = :run AND id = :id FOR UPDATE
                """).param("ws", workspaceId).param("run", runId).param("id", moveId)
                .query((rs, n) -> Map.entry(rs.getString("state"), new Pending(Sql.uuid(rs, "id"), rs.getString("kind"),
                        rs.getString("from_node_id"), rs.getString("to_node_id"), rs.getString("reason"),
                        rs.getInt("expected_version"), Sql.uuid(rs, "requested_by"), Sql.instant(rs, "expires_at"))))
                .optional().orElseThrow(ApiException::notFound);
        if (!"pendingApproval".equals(row.getKey())) {
            throw new ApiException(ProblemCode.MOVE_NOT_PENDING, "The move is " + row.getKey() + "; nothing is waiting"
                    + " for a decision.");
        }
        return row.getValue();
    }

    // ---------------------------------------------------------------- views

    private static final String COLUMNS = """
            id, run_id, kind, from_node_id, to_node_id, reason, state, approval_required, expected_version,
            requested_by, requested_at, expires_at, decided_by, decided_at, decision_note, executed_at, plan::text AS plan
            """;

    private MigrationMoveDtos.RunMove view(UUID workspaceId, UUID runId, UUID moveId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run_move WHERE workspace_id = :ws AND run_id = :run AND id = :id")
                .param("ws", workspaceId).param("run", runId).param("id", moveId).query((rs, n) -> map(rs))
                .optional().orElseThrow(ApiException::notFound);
    }

    private MigrationMoveDtos.RunMove map(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode plan = json.parse(rs.getString("plan"));
        return new MigrationMoveDtos.RunMove(Sql.uuid(rs, "id"), Sql.uuid(rs, "run_id"), rs.getString("kind"),
                rs.getString("from_node_id"), plan.path("fromLabel").asString(null), rs.getString("to_node_id"),
                plan.path("toLabel").asString(null), rs.getString("reason"), rs.getString("state"),
                rs.getBoolean("approval_required"), rs.getInt("expected_version"), Sql.uuid(rs, "requested_by"),
                Sql.instant(rs, "requested_at"), Sql.instant(rs, "expires_at"), Sql.uuid(rs, "decided_by"),
                Sql.instant(rs, "decided_at"), rs.getString("decision_note"), Sql.instant(rs, "executed_at"),
                affectedJson(plan.path("skipped")), affectedJson(plan.path("rerun")));
    }

    private ObjectNode planJson(MoveRules.Plan plan) {
        ObjectNode node = json.object();
        node.put("fromLabel", plan.fromLabel());
        node.put("toLabel", plan.toLabel());
        for (String key : List.of("skipped", "rerun")) {
            ArrayNode list = node.putArray(key);
            for (MoveRules.Affected a : "skipped".equals(key) ? plan.skipped() : plan.rerun()) {
                list.addObject().put("nodeId", a.nodeId()).put("label", a.label()).put("kind", a.kind())
                        .put("handling", a.handling());
            }
        }
        return node;
    }

    private static List<MigrationMoveDtos.MoveAffected> affectedJson(JsonNode list) {
        List<MigrationMoveDtos.MoveAffected> result = new ArrayList<>();
        for (JsonNode a : list) {
            result.add(new MigrationMoveDtos.MoveAffected(a.path("nodeId").asString(""), a.path("label").asString(""),
                    a.path("kind").asString(""), a.path("handling").asString("")));
        }
        return result;
    }

    private static List<MigrationMoveDtos.MoveAffected> affected(List<MoveRules.Affected> list) {
        return list.stream().map(a -> new MigrationMoveDtos.MoveAffected(a.nodeId(), a.label(), a.kind(), a.handling()))
                .toList();
    }
}
