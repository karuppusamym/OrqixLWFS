package com.orqix.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * PL02 (docs/build/S54): a bounded canary and per-release analytics. One non-active release of a workflow may receive
 * 1 to 50 percent of new starts. The canary must accept exactly the same request form as the active release, so a
 * person never sees a different form from the one they filled in. Selecting a different active release clears the
 * canary. Runs never move between releases.
 */
@Service
public class RolloutService {

    public static final int MAX_DAYS = 365;
    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_RELEASES = 20;

    public record Canary(UUID releaseId, int releaseNumber, int percent, Instant setAt) {
    }

    public record Rollout(int version, Integer activeReleaseNumber, Canary canary) {
    }

    public record SetCanaryRequest(@NotNull UUID releaseId, @Min(1) @Max(50) int percent, @Min(1) int expectedVersion) {
    }

    public record ReleaseStats(UUID releaseId, int releaseNumber, boolean active, boolean canary, long started,
            long open, long needsAttention, long succeeded, long rejected, long cancelled, Double averageCompletionSeconds,
            Double successShare) {
    }

    public record Analytics(int days, Instant from, Instant to, List<ReleaseStats> releases) {
    }

    private final JdbcClient jdbc;
    private final CatalogStore store;
    private final WorkflowService workflows;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;

    public RolloutService(JdbcClient jdbc, CatalogStore store, WorkflowService workflows, PolicyService policy,
            AuditWriter audit, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.workflows = workflows;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    private Canary canary(UUID ws, UUID wf) {
        return jdbc.sql("""
                SELECT c.release_id, r.release_number, c.percent, c.set_at
                FROM workflow_canary c JOIN workflow_release r ON r.workflow_id = c.workflow_id AND r.id = c.release_id
                WHERE c.workspace_id = :ws AND c.workflow_id = :wf
                """).param("ws", ws).param("wf", wf)
                .query((rs, n) -> new Canary(rs.getObject("release_id", UUID.class), rs.getInt("release_number"),
                        rs.getInt("percent"), rs.getTimestamp("set_at").toInstant()))
                .optional().orElse(null);
    }

    private Rollout rollout(CatalogStore.WorkflowRow workflow) {
        return new Rollout(workflow.version(), workflow.activeReleaseNumber(), canary(workflow.workspaceId(), workflow.id()));
    }

    @Transactional(readOnly = true)
    public Rollout get(Caller caller, UUID ws, UUID wf) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "getWorkflowRollout");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "getWorkflowRollout", false);
        return rollout(visible.workflow());
    }

    @Transactional
    public Rollout setCanary(Caller caller, UUID ws, UUID wf, SetCanaryRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "setWorkflowCanary");
        policy.require(visible.access(), Permission.WORKFLOW_PUBLISH, "setWorkflowCanary", true);
        CatalogStore.WorkflowRow workflow = store.lockWorkflow(ws, wf).orElseThrow(ApiException::notFound);
        CatalogStore.ReleaseRow target = store.release(ws, wf, request.releaseId()).orElseThrow(ApiException::notFound);
        if (workflow.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The workflow changed since you loaded it.")
                    .with("currentVersion", workflow.version());
        }
        if (workflow.activeReleaseId() == null) {
            throw new ApiException(ProblemCode.NO_ACTIVE_RELEASE, "Activate a release before starting a canary.");
        }
        if (target.id().equals(workflow.activeReleaseId())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The canary must be a different release from the active one.");
        }
        CatalogStore.ReleaseRow active = store.release(ws, wf, workflow.activeReleaseId()).orElseThrow();
        String incompatible = formDifference(active, target);
        if (incompatible != null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This release cannot run next to the active release: "
                    + incompatible);
        }
        jdbc.sql("DELETE FROM workflow_canary WHERE workspace_id = :ws AND workflow_id = :wf").param("ws", ws).param("wf", wf)
                .update();
        jdbc.sql("""
                INSERT INTO workflow_canary (workspace_id, workflow_id, release_id, percent, set_by)
                VALUES (:ws, :wf, :rel, :percent, :by)
                """).param("ws", ws).param("wf", wf).param("rel", target.id()).param("percent", request.percent())
                .param("by", caller.subjectId()).update();
        bumpVersion(ws, wf);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("activeReleaseNumber", active.releaseNumber());
        metadata.put("canaryReleaseNumber", target.releaseNumber());
        metadata.put("percent", request.percent());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.canary.set",
                "workflow", wf.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return rollout(store.workflow(ws, wf).orElseThrow());
    }

    @Transactional
    public Rollout clearCanary(Caller caller, UUID ws, UUID wf, int expectedVersion) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "clearWorkflowCanary");
        policy.require(visible.access(), Permission.WORKFLOW_PUBLISH, "clearWorkflowCanary", true);
        CatalogStore.WorkflowRow workflow = store.lockWorkflow(ws, wf).orElseThrow(ApiException::notFound);
        if (workflow.version() != expectedVersion) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The workflow changed since you loaded it.")
                    .with("currentVersion", workflow.version());
        }
        Canary existing = canary(ws, wf);
        if (existing != null) {
            jdbc.sql("DELETE FROM workflow_canary WHERE workspace_id = :ws AND workflow_id = :wf").param("ws", ws)
                    .param("wf", wf).update();
            bumpVersion(ws, wf);
            audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.canary.cleared",
                    "workflow", wf.toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("canaryReleaseNumber", existing.releaseNumber())));
        }
        return rollout(store.workflow(ws, wf).orElseThrow());
    }

    private void bumpVersion(UUID ws, UUID wf) {
        jdbc.sql("UPDATE workflow SET version = version + 1, updated_at = :now WHERE workspace_id = :ws AND id = :wf")
                .param("now", Timestamp.from(clock.instant())).param("ws", ws).param("wf", wf).update();
    }

    /** Null when both releases present the same request form; otherwise a plain reason. */
    String formDifference(CatalogStore.ReleaseRow a, CatalogStore.ReleaseRow b) {
        var x = json.parse(a.definitionJson());
        var y = json.parse(b.definitionJson());
        if (!x.path("inputSchema").equals(y.path("inputSchema"))) {
            return "its request fields differ from release " + a.releaseNumber() + ".";
        }
        if (!x.path("inputUi").equals(y.path("inputUi"))) {
            return "its form layout or conditions differ from release " + a.releaseNumber() + ".";
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Analytics analytics(Caller caller, UUID ws, UUID wf, Integer daysParam) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "getReleaseAnalytics");
        policy.require(visible.access(), Permission.RUN_READ, "getReleaseAnalytics", false);
        int days = daysParam == null ? DEFAULT_DAYS : daysParam;
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.malformed("days must be between 1 and " + MAX_DAYS + ".");
        }
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(days));
        Canary canary = canary(ws, wf);
        UUID canaryId = canary == null ? null : canary.releaseId();
        UUID activeId = visible.workflow().activeReleaseId();
        List<ReleaseStats> releases = jdbc.sql("""
                SELECT r.id, r.release_number,
                       count(u.id) AS started,
                       count(u.id) FILTER (WHERE u.lifecycle IN ('running', 'waiting')) AS open,
                       count(u.id) FILTER (WHERE u.lifecycle = 'needsAttention') AS attention,
                       count(u.id) FILTER (WHERE u.lifecycle = 'completed' AND u.outcome_kind = 'success') AS succeeded,
                       count(u.id) FILTER (WHERE u.lifecycle = 'completed' AND u.outcome_kind = 'rejected') AS rejected,
                       count(u.id) FILTER (WHERE u.lifecycle = 'cancelled') AS cancelled,
                       avg(extract(epoch FROM (u.ended_at - u.created_at))) FILTER (WHERE u.lifecycle = 'completed') AS avg_seconds
                FROM (SELECT id, release_number FROM workflow_release WHERE workspace_id = :ws AND workflow_id = :wf
                      ORDER BY release_number DESC LIMIT :max) r
                LEFT JOIN workflow_run u ON u.workflow_id = :wf AND u.workspace_id = :ws AND u.release_id = r.id
                     AND u.created_at >= :from AND u.created_at <= :to
                GROUP BY r.id, r.release_number ORDER BY r.release_number DESC
                """).param("ws", ws).param("wf", wf).param("max", MAX_RELEASES)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> stats(rs, activeId, canaryId)).list();
        return new Analytics(days, from, to, releases);
    }

    private static ReleaseStats stats(ResultSet rs, UUID activeId, UUID canaryId) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        long succeeded = rs.getLong("succeeded");
        long rejected = rs.getLong("rejected");
        long cancelled = rs.getLong("cancelled");
        long finished = succeeded + rejected + cancelled;
        Double avg = rs.getObject("avg_seconds") == null ? null : rs.getDouble("avg_seconds");
        return new ReleaseStats(id, rs.getInt("release_number"), id.equals(activeId), id.equals(canaryId),
                rs.getLong("started"), rs.getLong("open"), rs.getLong("attention"), succeeded, rejected, cancelled, avg,
                finished == 0 ? null : (double) succeeded / finished);
    }
}
