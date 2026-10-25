package com.orqix.integration.agentwrite;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Retention of agent write records (IN04 hardening, docs/build/S85), following the DOC08 file retention pattern
 * ({@code AttachmentLifecycle}): workspace administrators ({@code workspace.manage-access}) set a number of days after a
 * request ended; an hourly sweep deletes the {@code agent_write_call} rows (argument digest and size, approver, requester,
 * reconciliation note) of requests that ended longer ago than that.
 *
 * <p>Never removed: a record of a request that has not ended, and every record of an ended request that still has a
 * write without a final outcome ({@code unknown}, in flight, pending, retryable, or never registered). Final means the
 * effect journal says {@code succeeded}, {@code permanentFailure} or {@code notApplied}. The effect journal entries and
 * audit events are not touched. Each removal is audited ({@code agent.write.purged}, system actor, ids only).
 */
@Service
public class AgentWriteRetention {

    public static final int BATCH = 200;

    public record RetentionRequest(@Min(1) @Max(3650) Integer retentionDays, @Min(0) int expectedVersion) {
    }

    public record Retention(Integer retentionDays, int version, int eligibleNow, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Clock clock;

    public AgentWriteRetention(JdbcClient jdbc, PolicyService policy, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Retention retention(Caller caller, UUID ws) {
        admin(caller, ws, "getAgentWriteRetention");
        return read(ws);
    }

    @Transactional
    public Retention setRetention(Caller caller, UUID ws, RetentionRequest request) {
        admin(caller, ws, "setAgentWriteRetention");
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).optional();
        int current = jdbc.sql("SELECT version FROM workspace_agent_write_policy WHERE workspace_id = :ws")
                .param("ws", ws).query(Integer.class).optional().orElse(0);
        if (current != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,
                    "The retention setting changed. Reload before saving.").with("currentVersion", current);
        }
        jdbc.sql("""
                INSERT INTO workspace_agent_write_policy (workspace_id, retention_days, version, updated_by, updated_at)
                VALUES (:ws, :days, 1, :by, :now)
                ON CONFLICT (workspace_id) DO UPDATE
                   SET retention_days = :days, version = workspace_agent_write_policy.version + 1,
                       updated_by = :by, updated_at = :now
                """).param("ws", ws).param("days", request.retentionDays()).param("by", caller.subjectId())
                .param("now", Sql.ts(clock.instant())).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retentionDays", request.retentionDays());
        metadata.put("version", current + 1);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "agent.write.retention.updated", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return read(ws);
    }

    private Retention read(UUID ws) {
        var row = jdbc.sql("""
                SELECT retention_days, version, updated_at FROM workspace_agent_write_policy WHERE workspace_id = :ws
                """).param("ws", ws).query((rs, n) -> new Object[] {(Integer) rs.getObject("retention_days"),
                        rs.getInt("version"), Sql.instant(rs, "updated_at")}).optional();
        Integer days = row.map(r -> (Integer) r[0]).orElse(null);
        int eligible = days == null ? 0
                : jdbc.sql("SELECT count(*) FROM (" + CANDIDATES + " AND c.workspace_id = :ws) x")
                        .param("now", Sql.ts(clock.instant())).param("ws", ws).query(Integer.class).single();
        return new Retention(days, row.map(r -> (Integer) r[1]).orElse(0), eligible,
                row.map(r -> (Instant) r[2]).orElse(null));
    }

    /**
     * Records of ended requests past retention whose request has no write without a final outcome. A write row whose
     * effect was never registered counts as not final (it may be an attempt that ended without an outcome).
     */
    private static final String CANDIDATES = """
            SELECT c.id, c.workspace_id, c.run_id, p.retention_days
            FROM agent_write_call c
            JOIN workflow_run r ON r.workspace_id = c.workspace_id AND r.id = c.run_id
            JOIN workspace_agent_write_policy p ON p.workspace_id = c.workspace_id
            WHERE p.retention_days IS NOT NULL
              AND r.lifecycle IN ('completed', 'cancelled') AND r.ended_at IS NOT NULL
              AND r.ended_at < CAST(:now AS timestamptz) - (p.retention_days * interval '1 day')
              AND NOT EXISTS (
                  SELECT 1 FROM agent_write_call o
                  LEFT JOIN effect_journal e ON e.workspace_id = o.workspace_id AND e.effect_key = o.effect_key
                  WHERE o.workspace_id = c.workspace_id AND o.run_id = c.run_id
                    AND (e.state IS NULL OR e.state NOT IN ('succeeded', 'permanentFailure', 'notApplied')))
            """;

    @Scheduled(fixedDelayString = "${orqix.agent-writes.retention-delay:1h}")
    @Transactional
    public void sweep() {
        purgeExpired();
    }

    /** Deletes up to {@value #BATCH} write records past retention. Safe on several pods. @return records removed */
    @Transactional
    public int purgeExpired() {
        record Candidate(UUID id, UUID ws, UUID run, int days) {
        }
        List<Candidate> due = jdbc.sql(CANDIDATES + " ORDER BY r.ended_at, c.id LIMIT " + BATCH
                + " FOR UPDATE OF c SKIP LOCKED")
                .param("now", Sql.ts(clock.instant()))
                .query((rs, n) -> new Candidate(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                        rs.getInt("retention_days")))
                .list();
        for (Candidate c : due) {
            jdbc.sql("DELETE FROM agent_write_call WHERE workspace_id = :ws AND id = :id").param("ws", c.ws())
                    .param("id", c.id()).update();
            audit.write(new AuditWriter.Entry(c.ws(), AuditWriter.ActorType.SYSTEM, null, "agent.write.purged",
                    "agentWriteCall", c.id().toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("runId", c.run().toString(), "writeId", c.id().toString(), "retentionDays", c.days())));
        }
        return due.size();
    }

    private void admin(Caller caller, UUID ws, String operation) {
        WorkspaceAccess workspace = policy.requireVisible(caller, ws);
        policy.require(workspace, Permission.WORKSPACE_MANAGE_ACCESS, operation, true);
    }
}
