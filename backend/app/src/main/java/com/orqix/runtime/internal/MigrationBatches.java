package com.orqix.runtime.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.api.MigrationMoveDtos;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Batch migration with one step mapping (CG-O02, docs/build/S143). Self-contained: the bulk operations framework
 * (CG-O01) is not on this branch. The operator previews the exact list, confirms its count, and the batch runs each
 * request through the single-request migration (its own transaction, its own idempotency key), so every request is
 * checked, locked, audited and timelined exactly as if it were moved alone. With {@code suspendDuring} a running request
 * is suspended for the move and resumed afterwards, also when it is refused.
 */
@Service
public class MigrationBatches {

    /** Requests per batch: the batch runs inside the request that starts it. */
    static final int MAX_ITEMS = 100;

    private final PolicyService policy;
    private final RunAccess runAccess;
    private final RunStore runs;
    private final ReleaseQueries releases;
    private final MappedMigrations migrations;
    private final OperatorService operator;
    private final RunSuspensions suspensions;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final com.orqix.runtime.engine.EngineCommands engine;

    public MigrationBatches(PolicyService policy, RunAccess runAccess, RunStore runs, ReleaseQueries releases,
            MappedMigrations migrations, OperatorService operator, RunSuspensions suspensions,
            IdempotencyService idempotency, AuditWriter audit, JdbcClient jdbc, Json json, Clock clock,
            org.springframework.transaction.PlatformTransactionManager transactions,
            com.orqix.runtime.engine.EngineCommands engine) {
        this.engine = engine;
        this.policy = policy;
        this.runAccess = runAccess;
        this.runs = runs;
        this.releases = releases;
        this.migrations = migrations;
        this.operator = operator;
        this.suspensions = suspensions;
        this.idempotency = idempotency;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    private record Releases(ReleaseQueries.ReleaseView source, ReleaseQueries.ReleaseView target) {
    }

    private Releases releases(UUID workspaceId, UUID sourceId, UUID targetId) {
        ReleaseQueries.ReleaseView source = releases.find(workspaceId, sourceId).orElseThrow(ApiException::notFound);
        ReleaseQueries.ReleaseView target = releases.find(workspaceId, targetId)
                .filter(r -> r.workflowId().equals(source.workflowId())).orElseThrow(ApiException::notFound);
        if (source.id().equals(target.id())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a different target release.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/targetReleaseId", "different",
                            "The target must differ from the source release")));
        }
        return new Releases(source, target);
    }

    private WorkspaceAccess operatorAccess(Caller caller, UUID workspaceId, String operationId) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, operationId, true);
        return access;
    }

    /** Dry run: the plan of every listed request (or of the active requests on the source release, newest first). */
    public MigrationMoveDtos.MigrationBatchPreview preview(Caller caller, UUID workspaceId,
            MigrationMoveDtos.MigrationBatchPreviewRequest request) {
        operatorAccess(caller, workspaceId, "previewMigrationBatch");
        return tx.execute(status -> {
            Releases r = releases(workspaceId, request.sourceReleaseId(), request.targetReleaseId());
            boolean truncated = false;
            List<UUID> ids;
            if (request.runIds() != null) {
                ids = List.copyOf(new LinkedHashSet<>(request.runIds()));
            } else {
                ids = jdbc.sql("""
                        SELECT id FROM workflow_run WHERE workspace_id = :ws AND release_id = :release
                          AND lifecycle IN ('running', 'waiting', 'needsAttention') AND parent_run_id IS NULL
                        ORDER BY created_at DESC, id LIMIT :limit
                        """).param("ws", workspaceId).param("release", r.source().id()).param("limit", MAX_ITEMS + 1)
                        .query(UUID.class).list();
                truncated = ids.size() > MAX_ITEMS;
                ids = ids.subList(0, Math.min(ids.size(), MAX_ITEMS));
            }
            List<MigrationRules.Mapping> mappings = MappedMigrations.mappings(request.mappingsOrEmpty());
            boolean suspendDuring = Boolean.TRUE.equals(request.suspendDuring());
            List<MigrationMoveDtos.MigrationPlan> items = new ArrayList<>();
            for (UUID id : ids) {
                items.add(planItem(caller, workspaceId, id, r, mappings, suspendDuring));
            }
            int allowed = (int) items.stream().filter(MigrationMoveDtos.MigrationPlan::allowed).count();
            return new MigrationMoveDtos.MigrationBatchPreview(ref(r.source()), ref(r.target()), items.size(), allowed,
                    items.size() - allowed, truncated, items);
        });
    }

    private MigrationMoveDtos.MigrationPlan planItem(Caller caller, UUID workspaceId, UUID runId, Releases r,
            List<MigrationRules.Mapping> mappings, boolean suspendDuring) {
        RunStore.RunRow run;
        try {
            run = runAccess.require(caller, workspaceId, runId).run();
        } catch (ApiException e) {
            return refused(runId, null, r, "NOT_VISIBLE", "The request does not exist or is not visible to you.");
        }
        if (!run.releaseId().equals(r.source().id())) {
            return refused(runId, run.reference(), r, "NOT_ON_RELEASE", "The request does not follow the source release.");
        }
        return migrations.plan(run, r.source(), r.target(), mappings, migrations.readiness(run, suspendDuring));
    }

    private static MigrationMoveDtos.MigrationPlan refused(UUID runId, String reference, Releases r, String code,
            String message) {
        return new MigrationMoveDtos.MigrationPlan(runId, reference, ref(r.source()), ref(r.target()), false, message,
                null, false, 0, List.of(), List.of(), List.of(new RuntimeDtos.MigrationFinding(code, message, null)),
                List.of());
    }

    public OperatorService.Result<MigrationMoveDtos.MigrationBatch> create(Caller caller, UUID workspaceId,
            String idempotencyKey, MigrationMoveDtos.CreateMigrationBatchRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        operatorAccess(caller, workspaceId, "createMigrationBatch");
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(request.runIds()));
        if (ids.size() != request.expectedCount()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Confirm the number of requests to move: the list"
                    + " has " + ids.size() + ".").withFieldErrors(List.of(new ApiException.FieldError("/expectedCount",
                            "mismatch", "The list has " + ids.size() + " requests")));
        }
        UUID batchId = UUID.randomUUID();
        UUID[] created = new UUID[1];
        boolean replay = Boolean.TRUE.equals(tx.execute(status -> {
            String hash = idempotency.requestHash("createMigrationBatch", Map.of("workspaceId", workspaceId.toString()),
                    request);
            IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "createMigrationBatch",
                    key, hash);
            if (claim.isReplay()) {
                created[0] = UUID.fromString(claim.replay().resourceId());
                return true;
            }
            Releases r = releases(workspaceId, request.sourceReleaseId(), request.targetReleaseId());
            for (int i = 0; i < ids.size(); i++) {
                UUID id = ids.get(i);
                boolean listed;
                try {
                    listed = runAccess.require(caller, workspaceId, id).run().releaseId().equals(r.source().id());
                } catch (ApiException e) {
                    listed = false;
                }
                if (!listed) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Every listed request must exist and follow"
                            + " the source release. Preview the batch again.").withFieldErrors(List.of(
                                    new ApiException.FieldError("/runIds/" + i, "notOnRelease",
                                            "Not a request of the source release")));
                }
            }
            jdbc.sql("""
                    INSERT INTO run_migration_batch (id, workspace_id, workflow_id, source_release_id, target_release_id,
                                                     mappings, reason, suspend_during, state, item_count, created_by,
                                                     created_at)
                    VALUES (:id, :ws, :wf, :source, :target, CAST(:mappings AS jsonb), :reason, :suspend, 'running',
                            :count, :by, :now)
                    """).param("id", batchId).param("ws", workspaceId).param("wf", r.source().workflowId())
                    .param("source", r.source().id()).param("target", r.target().id())
                    .param("mappings", json.write(request.mappingsOrEmpty()))
                    .param("reason", request.reason()).param("suspend", Boolean.TRUE.equals(request.suspendDuring()))
                    .param("count", ids.size()).param("by", caller.subjectId()).param("now", Sql.ts(clock.instant()))
                    .update();
            int position = 0;
            for (UUID id : ids) {
                jdbc.sql("""
                        INSERT INTO run_migration_batch_item (batch_id, run_id, workspace_id, position, state)
                        VALUES (:batch, :run, :ws, :pos, 'pending')
                        """).param("batch", batchId).param("run", id).param("ws", workspaceId).param("pos", position++)
                        .update();
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("items", ids.size());
            metadata.put("fromRelease", r.source().releaseNumber());
            metadata.put("toRelease", r.target().releaseNumber());
            audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                    "run.migration-batch.started", "migrationBatch", batchId.toString(), AuditWriter.Outcome.SUCCESS,
                    metadata));
            idempotency.complete(claim, "migrationBatch", batchId.toString(), 200);
            created[0] = batchId;
            return false;
        }));
        if (replay) {
            return new OperatorService.Result<>(view(workspaceId, created[0]), true);
        }
        int[] counts = {0, 0, 0};
        for (UUID id : jdbc.sql("SELECT run_id FROM run_migration_batch_item WHERE batch_id = :b ORDER BY position")
                .param("b", batchId).query(UUID.class).list()) {
            Outcome outcome = migrateOne(caller, workspaceId, batchId, id, request);
            counts["migrated".equals(outcome.state()) ? 0 : "refused".equals(outcome.state()) ? 1 : 2]++;
            tx.executeWithoutResult(status -> jdbc.sql("""
                    UPDATE run_migration_batch_item SET state = :state, message = :message,
                           findings = CAST(:findings AS jsonb), finished_at = :now
                    WHERE batch_id = :b AND run_id = :run
                    """).param("state", outcome.state()).param("message", truncate(outcome.message()))
                    .param("findings", json.write(outcome.findings())).param("now", Sql.ts(clock.instant()))
                    .param("b", batchId).param("run", id).update());
        }
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    UPDATE run_migration_batch SET state = 'completed', migrated_count = :m, refused_count = :r,
                           failed_count = :f, finished_at = :now WHERE id = :b
                    """).param("m", counts[0]).param("r", counts[1]).param("f", counts[2])
                    .param("now", Sql.ts(clock.instant())).param("b", batchId).update();
            audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                    "run.migration-batch.completed", "migrationBatch", batchId.toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("migrated", counts[0], "refused", counts[1], "failed", counts[2])));
        });
        return new OperatorService.Result<>(view(workspaceId, batchId), false);
    }

    private record Outcome(String state, String message, List<RuntimeDtos.MigrationFinding> findings) {
    }

    private Outcome migrateOne(Caller caller, UUID workspaceId, UUID batchId, UUID runId,
            MigrationMoveDtos.CreateMigrationBatchRequest request) {
        boolean suspendedHere = false;
        try {
            RunStore.RunRow run = runAccess.require(caller, workspaceId, runId).run();
            if (!run.releaseId().equals(request.sourceReleaseId())) {
                return new Outcome("refused", "The request does not follow the source release any more.",
                        List.of(new RuntimeDtos.MigrationFinding("NOT_ON_RELEASE",
                                "The request does not follow the source release any more.", null)));
            }
            if (Boolean.TRUE.equals(request.suspendDuring()) && run.isActive()
                    && !suspensions.isSuspended(workspaceId, runId)
                    && !"childRequests".equals(tx.execute(s -> migrations.readiness(run, false).hold()))
                    && tx.execute(s -> OperatorService.suspendBlocker(run, runs, engine)) == null) {
                operator.suspend(caller, workspaceId, runId, itemKey(batchId, runId, "suspend"),
                        new RuntimeDtos.SuspendRunRequest(request.reason(), run.version()));
                suspendedHere = true;
            }
            int version = runs.find(workspaceId, runId).orElseThrow().version();
            migrations.migrate(caller, workspaceId, runId, itemKey(batchId, runId, "migrate"),
                    new MigrationMoveDtos.MappedMigrationRequest(request.targetReleaseId(), request.mappingsOrEmpty(),
                            request.reason(), version));
            return new Outcome("migrated", null, List.of());
        } catch (ApiException e) {
            @SuppressWarnings("unchecked")
            List<RuntimeDtos.MigrationFinding> findings = e.extensions().get("findings") instanceof List<?> list
                    ? (List<RuntimeDtos.MigrationFinding>) list : List.of();
            return new Outcome("refused", e.detail() != null ? e.detail() : e.code().name(), findings);
        } catch (RuntimeException e) {
            return new Outcome("failed", "The request could not be moved: " + e.getClass().getSimpleName(), List.of());
        } finally {
            if (suspendedHere) {
                resume(caller, workspaceId, batchId, runId, request.reason());
            }
        }
    }

    private void resume(Caller caller, UUID workspaceId, UUID batchId, UUID runId, String reason) {
        try {
            int version = runs.find(workspaceId, runId).orElseThrow().version();
            operator.resume(caller, workspaceId, runId, itemKey(batchId, runId, "resume"),
                    new RuntimeDtos.ResumeRunRequest(reason, version));
        } catch (RuntimeException e) {
            // Left suspended: the batch item shows the outcome and the operator resumes it from the request.
        }
    }

    private static String itemKey(UUID batchId, UUID runId, String step) {
        return "mb-" + UUID.nameUUIDFromBytes((batchId + ":" + runId + ":" + step).getBytes(StandardCharsets.UTF_8));
    }

    public MigrationMoveDtos.MigrationBatch get(Caller caller, UUID workspaceId, UUID batchId) {
        operatorAccess(caller, workspaceId, "getMigrationBatch");
        return tx.execute(status -> view(workspaceId, batchId));
    }

    private MigrationMoveDtos.MigrationBatch view(UUID workspaceId, UUID batchId) {
        return jdbc.sql("""
                SELECT b.*, b.mappings::text AS mappings_text, s.release_number AS source_number,
                       t.release_number AS target_number
                FROM run_migration_batch b
                JOIN workflow_release s ON s.id = b.source_release_id
                JOIN workflow_release t ON t.id = b.target_release_id
                WHERE b.workspace_id = :ws AND b.id = :id
                """).param("ws", workspaceId).param("id", batchId).query((rs, n) -> {
                    List<MigrationMoveDtos.MigrationBatchItem> items = jdbc.sql("""
                            SELECT i.run_id, r.reference, i.state, i.message, i.findings::text AS findings, i.finished_at
                            FROM run_migration_batch_item i JOIN workflow_run r ON r.workspace_id = i.workspace_id
                                 AND r.id = i.run_id
                            WHERE i.batch_id = :b ORDER BY i.position
                            """).param("b", batchId).query((irs, m) -> new MigrationMoveDtos.MigrationBatchItem(
                                    Sql.uuid(irs, "run_id"), irs.getString("reference"), irs.getString("state"),
                                    irs.getString("message"), parseFindings(irs.getString("findings")),
                                    Sql.instant(irs, "finished_at"))).list();
                    return new MigrationMoveDtos.MigrationBatch(Sql.uuid(rs, "id"), Sql.uuid(rs, "workflow_id"),
                            new RuntimeDtos.ReleaseRef(Sql.uuid(rs, "source_release_id"), rs.getInt("source_number")),
                            new RuntimeDtos.ReleaseRef(Sql.uuid(rs, "target_release_id"), rs.getInt("target_number")),
                            mappings(rs.getString("mappings_text")),
                            rs.getString("state"), rs.getBoolean("suspend_during"), rs.getInt("item_count"),
                            rs.getInt("migrated_count"), rs.getInt("refused_count"), rs.getInt("failed_count"),
                            Sql.uuid(rs, "created_by"), Sql.instant(rs, "created_at"), Sql.instant(rs, "finished_at"),
                            items);
                }).optional().orElseThrow(ApiException::notFound);
    }

    private List<MigrationMoveDtos.StepMapping> mappings(String text) {
        List<MigrationMoveDtos.StepMapping> result = new ArrayList<>();
        for (JsonNode m : json.parse(text)) {
            result.add(new MigrationMoveDtos.StepMapping(m.path("from").asString(""), m.path("to").asString("")));
        }
        return result;
    }

    private List<RuntimeDtos.MigrationFinding> parseFindings(String text) {
        List<RuntimeDtos.MigrationFinding> result = new ArrayList<>();
        for (JsonNode f : json.parse(text)) {
            result.add(new RuntimeDtos.MigrationFinding(f.path("code").asString(""), f.path("message").asString(""),
                    f.path("nodeId").asString(null)));
        }
        return result;
    }

    private static RuntimeDtos.ReleaseRef ref(ReleaseQueries.ReleaseView release) {
        return new RuntimeDtos.ReleaseRef(release.id(), release.releaseNumber());
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }
}
