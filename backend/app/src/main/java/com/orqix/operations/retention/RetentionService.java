package com.orqix.operations.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Run retention policies, legal holds, sweeps and archives (CG-O07, docs/build/S144). Workspace administrators
 * ({@code workspace.manage-access}) set policies, place and release holds and start sweeps; they and auditors
 * ({@code audit.read}) read them and download archives. Every change is audited; a download is audited too.
 */
@Service
public class RetentionService {

    public record SetPolicy(@NotNull @Min(1) @Max(3650) Integer keepDays, Boolean includeRunData,
            Boolean includeFiles, Boolean enabled, @NotNull @Min(0) Integer expectedVersion) {
    }

    /** {@code eligibleNow}: top-level requests the next sweep would look at; {@code held}: active hold on the workflow. */
    public record PolicyView(UUID workflowId, String workflowKey, String workflowName, int keepDays,
            boolean includeRunData, boolean includeFiles, boolean enabled, int version, Instant updatedAt,
            int eligibleNow, boolean workflowHeld) {
    }

    public record PolicyList(List<PolicyView> items) {
    }

    public record PlaceHold(UUID workflowId, UUID runId, @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record ReleaseHold(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record HoldView(UUID id, UUID workflowId, String workflowName, UUID runId, String runReference,
            String reason, UUID placedBy, Instant placedAt, UUID releasedBy, Instant releasedAt, String releaseReason,
            boolean active) {
    }

    public record HoldList(List<HoldView> items) {
    }

    public record StartSweep(@NotNull Boolean dryRun, Boolean confirm) {
    }

    public record SweepView(UUID id, String mode, String trigger, UUID requestedBy, String status, Instant startedAt,
            Instant finishedAt, int eligible, int archived, int purged, int held, int skipped, int failed,
            JsonNode detail) {
    }

    public record SweepList(List<SweepView> items) {
    }

    public record ArchiveView(UUID id, UUID runId, String runReference, UUID workflowId, UUID rootRunId,
            UUID sweepId, String lifecycle, Instant endedAt, String store, String objectKey, String sha256, long bytes,
            String status, String historyStatus, Instant archivedAt, Instant purgedAt, Instant historyPurgedAt,
            JsonNode purgeCounts) {
    }

    public record ArchiveList(List<ArchiveView> items) {
    }

    public record Download(String fileName, byte[] bytes) {
    }

    private final PolicyService policy;
    private final RetentionSweeper sweeper;
    private final ArchiveStore store;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public RetentionService(PolicyService policy, RetentionSweeper sweeper, ArchiveStore store, AuditWriter audit,
            JdbcClient jdbc, Json json, Clock clock) {
        this.policy = policy;
        this.sweeper = sweeper;
        this.store = store;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- policies

    @Transactional(readOnly = true)
    public PolicyList listPolicies(Caller caller, UUID ws) {
        read(caller, ws, "listRetentionPolicies");
        return new PolicyList(jdbc.sql(POLICY + " WHERE p.workspace_id = :ws ORDER BY w.name, w.id").param("ws", ws)
                .param("now", Sql.ts(clock.instant())).query((rs, n) -> policy(rs)).list());
    }

    @Transactional
    public PolicyView setPolicy(Caller caller, UUID ws, UUID workflowId, SetPolicy request) {
        UUID actor = write(caller, ws, "setRetentionPolicy");
        if (jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id = :ws AND id = :id").param("ws", ws)
                .param("id", workflowId).query(Integer.class).single() == 0) {
            throw ApiException.notFound();
        }
        Integer version = jdbc.sql("""
                SELECT version FROM retention_policy WHERE workspace_id = :ws AND workflow_id = :wf FOR UPDATE
                """).param("ws", ws).param("wf", workflowId).query(Integer.class).optional().orElse(0);
        if (!version.equals(request.expectedVersion())) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The retention policy changed since you loaded it (now version " + version + ").");
        }
        boolean data = !Boolean.FALSE.equals(request.includeRunData());
        boolean files = !Boolean.FALSE.equals(request.includeFiles());
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        jdbc.sql("""
                INSERT INTO retention_policy (workspace_id, workflow_id, keep_days, include_run_data, include_files,
                                              enabled, version, updated_by, updated_at)
                VALUES (:ws, :wf, :days, :data, :files, :enabled, 1, :by, :now)
                ON CONFLICT (workspace_id, workflow_id) DO UPDATE SET keep_days = :days, include_run_data = :data,
                    include_files = :files, enabled = :enabled, version = retention_policy.version + 1,
                    updated_by = :by, updated_at = :now
                """).param("ws", ws).param("wf", workflowId).param("days", request.keepDays()).param("data", data)
                .param("files", files).param("enabled", enabled).param("by", actor)
                .param("now", Sql.ts(clock.instant())).update();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workflowId", workflowId.toString());
        meta.put("keepDays", request.keepDays());
        meta.put("includeRunData", data);
        meta.put("includeFiles", files);
        meta.put("enabled", enabled);
        meta.put("version", version + 1);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, actor, "retention.policy-set", "workflow",
                workflowId.toString(), AuditWriter.Outcome.SUCCESS, meta));
        return jdbc.sql(POLICY + " WHERE p.workspace_id = :ws AND p.workflow_id = :wf").param("ws", ws)
                .param("wf", workflowId).param("now", Sql.ts(clock.instant())).query((rs, n) -> policy(rs)).single();
    }

    @Transactional
    public void removePolicy(Caller caller, UUID ws, UUID workflowId) {
        UUID actor = write(caller, ws, "removeRetentionPolicy");
        int removed = jdbc.sql("DELETE FROM retention_policy WHERE workspace_id = :ws AND workflow_id = :wf")
                .param("ws", ws).param("wf", workflowId).update();
        if (removed == 0) {
            throw ApiException.notFound();
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, actor, "retention.policy-removed",
                "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("workflowId", workflowId.toString())));
    }

    // ---------------------------------------------------------------- legal holds

    @Transactional(readOnly = true)
    public HoldList listHolds(Caller caller, UUID ws, Boolean active) {
        read(caller, ws, "listLegalHolds");
        return new HoldList(jdbc.sql(HOLD + """
                 WHERE h.workspace_id = :ws AND (CAST(:active AS boolean) IS NULL
                       OR (h.released_at IS NULL) = CAST(:active AS boolean))
                ORDER BY h.placed_at DESC, h.id DESC LIMIT 200""").param("ws", ws).param("active", active)
                .query((rs, n) -> hold(rs)).list());
    }

    @Transactional
    public HoldView placeHold(Caller caller, UUID ws, PlaceHold request) {
        UUID actor = write(caller, ws, "placeLegalHold");
        if ((request.workflowId() == null) == (request.runId() == null)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Hold either one workflow or one request.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/runId", "oneOf",
                            "Give workflowId or runId, not both.")));
        }
        String table = request.workflowId() != null ? "workflow" : "workflow_run";
        UUID target = request.workflowId() != null ? request.workflowId() : request.runId();
        if (jdbc.sql("SELECT count(*) FROM " + table + " WHERE workspace_id = :ws AND id = :id").param("ws", ws)
                .param("id", target).query(Integer.class).single() == 0) {
            throw ApiException.notFound();
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO legal_hold (id, workspace_id, workflow_id, run_id, reason, placed_by, placed_at)
                    VALUES (:id, :ws, :wf, :run, :reason, :by, :now)
                    """).param("id", id).param("ws", ws).param("wf", request.workflowId()).param("run", request.runId())
                    .param("reason", request.reason().strip()).param("by", actor)
                    .param("now", Sql.ts(clock.instant())).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "An active legal hold already covers this.");
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, actor, "retention.hold-placed", "legalHold",
                id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("target", request.workflowId() != null ? "workflow"
                        : "run", "targetId", target.toString())));
        return findHold(ws, id);
    }

    @Transactional
    public HoldView releaseHold(Caller caller, UUID ws, UUID holdId, ReleaseHold request) {
        UUID actor = write(caller, ws, "releaseLegalHold");
        Instant releasedAt = jdbc.sql("SELECT released_at FROM legal_hold WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", ws).param("id", holdId).query((rs, n) -> java.util.Optional.ofNullable(
                        Sql.instant(rs, "released_at")))
                .optional().orElseThrow(ApiException::notFound).orElse(null);
        if (releasedAt != null) {
            throw new ApiException(ProblemCode.RESOURCE_IN_USE, "This legal hold was already released.");
        }
        jdbc.sql("""
                UPDATE legal_hold SET released_by = :by, released_at = :now, release_reason = :reason
                WHERE workspace_id = :ws AND id = :id
                """).param("by", actor).param("now", Sql.ts(clock.instant()))
                .param("reason", request.reason().strip()).param("ws", ws).param("id", holdId).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, actor, "retention.hold-released", "legalHold",
                holdId.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        return findHold(ws, holdId);
    }

    // ---------------------------------------------------------------- sweeps and archives

    /** Runs a sweep now (synchronously, bounded by the per-sweep limit). A purge sweep needs {@code confirm: true}. */
    public SweepView startSweep(Caller caller, UUID ws, StartSweep request) {
        UUID actor = write(caller, ws, "startRetentionSweep");
        if (!request.dryRun() && !Boolean.TRUE.equals(request.confirm())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "Archiving and purging cannot be undone in Orqix: run a dry run first, then confirm.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/confirm", "required",
                            "Set confirm to true to archive and purge.")));
        }
        UUID id = sweeper.sweep(ws, request.dryRun(), actor, clock.instant());
        return findSweep(ws, id);
    }

    @Transactional(readOnly = true)
    public SweepList listSweeps(Caller caller, UUID ws) {
        read(caller, ws, "listRetentionSweeps");
        return new SweepList(jdbc.sql(SWEEP + " WHERE workspace_id = :ws ORDER BY started_at DESC, id DESC LIMIT 50")
                .param("ws", ws).query((rs, n) -> sweep(rs)).list());
    }

    @Transactional(readOnly = true)
    public ArchiveList listArchives(Caller caller, UUID ws, String reference) {
        read(caller, ws, "listRunArchives");
        return new ArchiveList(jdbc.sql(ARCHIVE + """
                 WHERE workspace_id = :ws AND (CAST(:ref AS text) IS NULL OR run_reference = CAST(:ref AS text))
                ORDER BY archived_at DESC, id DESC LIMIT 200""").param("ws", ws).param("ref", reference)
                .query((rs, n) -> archive(rs)).list());
    }

    /** Reads the archive back from the store and checks its checksum before handing it out. */
    @Transactional
    public Download download(Caller caller, UUID ws, UUID archiveId) {
        WorkspaceAccess access = policy.freshAccess(caller, ws).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        if (!access.has(Permission.WORKSPACE_MANAGE_ACCESS)) {
            policy.require(access, Permission.AUDIT_READ, "downloadRunArchive", true);
        }
        ArchiveView a = jdbc.sql(ARCHIVE + " WHERE workspace_id = :ws AND id = :id").param("ws", ws)
                .param("id", archiveId).query((rs, n) -> archive(rs)).optional().orElseThrow(ApiException::notFound);
        if (!a.store().equals(store.kind())) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE,
                    "This archive is in the " + a.store() + " store, which is not configured now.");
        }
        byte[] bytes;
        try {
            bytes = store.get(a.objectKey()).orElseThrow(() -> new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE,
                    "The archive is missing from the store."));
        } catch (ArchiveStore.ArchiveStoreException e) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The archive store cannot be reached.");
        }
        if (!RunArchiver.sha256(bytes).equals(a.sha256())) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE,
                    "The stored archive does not match its recorded checksum.");
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "retention.archive-downloaded",
                "runArchive", archiveId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("runId", a.runId().toString(), "reference", a.runReference(), "bytes", bytes.length)));
        return new Download(a.runReference() + "-archive.zip", bytes);
    }

    // ---------------------------------------------------------------- helpers

    private void read(Caller caller, UUID ws, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        if (!access.has(Permission.WORKSPACE_MANAGE_ACCESS)) {
            policy.require(access, Permission.AUDIT_READ, operationId, false);
        }
    }

    private UUID write(Caller caller, UUID ws, String operationId) {
        WorkspaceAccess access = policy.freshAccess(caller, ws).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, operationId, true);
        return caller.subjectId();
    }

    private HoldView findHold(UUID ws, UUID id) {
        return jdbc.sql(HOLD + " WHERE h.workspace_id = :ws AND h.id = :id").param("ws", ws).param("id", id)
                .query((rs, n) -> hold(rs)).single();
    }

    private SweepView findSweep(UUID ws, UUID id) {
        return jdbc.sql(SWEEP + " WHERE workspace_id = :ws AND id = :id").param("ws", ws).param("id", id)
                .query((rs, n) -> sweep(rs)).single();
    }

    private static final String POLICY = """
            SELECT p.workflow_id, w.key, w.name, p.keep_days, p.include_run_data, p.include_files, p.enabled, p.version,
                   p.updated_at,
                   (SELECT count(*) FROM workflow_run r WHERE r.workspace_id = p.workspace_id
                      AND r.workflow_id = p.workflow_id AND r.parent_run_id IS NULL
                      AND r.lifecycle IN ('completed', 'cancelled')
                      AND r.ended_at < CAST(:now AS timestamptz) - make_interval(days => p.keep_days)) AS eligible,
                   EXISTS (SELECT 1 FROM legal_hold h WHERE h.workspace_id = p.workspace_id
                      AND h.workflow_id = p.workflow_id AND h.released_at IS NULL) AS held
            FROM retention_policy p JOIN workflow w ON w.workspace_id = p.workspace_id AND w.id = p.workflow_id""";

    private static PolicyView policy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PolicyView(Sql.uuid(rs, "workflow_id"), rs.getString("key"), rs.getString("name"),
                rs.getInt("keep_days"), rs.getBoolean("include_run_data"), rs.getBoolean("include_files"),
                rs.getBoolean("enabled"), rs.getInt("version"), Sql.instant(rs, "updated_at"), rs.getInt("eligible"),
                rs.getBoolean("held"));
    }

    private static final String HOLD = """
            SELECT h.id, h.workflow_id, w.name AS workflow_name, h.run_id,
                   coalesce(r.reference, (SELECT a.run_reference FROM run_archive a
                                          WHERE a.workspace_id = h.workspace_id AND a.run_id = h.run_id)) AS reference,
                   h.reason, h.placed_by, h.placed_at, h.released_by, h.released_at, h.release_reason
            FROM legal_hold h
            LEFT JOIN workflow w ON w.workspace_id = h.workspace_id AND w.id = h.workflow_id
            LEFT JOIN workflow_run r ON r.workspace_id = h.workspace_id AND r.id = h.run_id""";

    private static HoldView hold(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant released = Sql.instant(rs, "released_at");
        return new HoldView(Sql.uuid(rs, "id"), Sql.uuid(rs, "workflow_id"), rs.getString("workflow_name"),
                Sql.uuid(rs, "run_id"), rs.getString("reference"), rs.getString("reason"), Sql.uuid(rs, "placed_by"),
                Sql.instant(rs, "placed_at"), Sql.uuid(rs, "released_by"), released, rs.getString("release_reason"),
                released == null);
    }

    private static final String SWEEP = """
            SELECT id, mode, trigger, requested_by, status, started_at, finished_at, eligible, archived, purged, held,
                   skipped, failed, detail::text AS detail FROM retention_sweep""";

    private SweepView sweep(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SweepView(Sql.uuid(rs, "id"), rs.getString("mode"), rs.getString("trigger"),
                Sql.uuid(rs, "requested_by"), rs.getString("status"), Sql.instant(rs, "started_at"),
                Sql.instant(rs, "finished_at"), rs.getInt("eligible"), rs.getInt("archived"), rs.getInt("purged"),
                rs.getInt("held"), rs.getInt("skipped"), rs.getInt("failed"), json.parse(rs.getString("detail")));
    }

    private static final String ARCHIVE = """
            SELECT id, run_id, run_reference, workflow_id, root_run_id, sweep_id, lifecycle, ended_at, store,
                   object_key, sha256, bytes, status, history_status, archived_at, purged_at, history_purged_at,
                   purge_counts::text AS purge_counts FROM run_archive""";

    private ArchiveView archive(java.sql.ResultSet rs) throws java.sql.SQLException {
        String counts = rs.getString("purge_counts");
        return new ArchiveView(Sql.uuid(rs, "id"), Sql.uuid(rs, "run_id"), rs.getString("run_reference"),
                Sql.uuid(rs, "workflow_id"), Sql.uuid(rs, "root_run_id"), Sql.uuid(rs, "sweep_id"),
                rs.getString("lifecycle"), Sql.instant(rs, "ended_at"), rs.getString("store"),
                rs.getString("object_key"), rs.getString("sha256"), rs.getLong("bytes"), rs.getString("status"),
                rs.getString("history_status"), Sql.instant(rs, "archived_at"), Sql.instant(rs, "purged_at"),
                Sql.instant(rs, "history_purged_at"), counts == null ? null : json.parse(counts));
    }
}
