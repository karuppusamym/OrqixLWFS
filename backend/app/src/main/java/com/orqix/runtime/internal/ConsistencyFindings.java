package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.web.Sql;

/** {@code run_consistency_scan} (EX09, docs/build/S67): what the periodic scan last found for a request. Scoped by workspace. */
@Repository
public class ConsistencyFindings {
    private final JdbcClient jdbc;

    public ConsistencyFindings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the latest result; the first-seen time is kept while the finding stays the same. */
    public void record(UUID workspaceId, UUID runId, String state, boolean repairable, String message, int version,
            Instant now) {
        String text = message.length() > 1000 ? message.substring(0, 1000) : message;
        jdbc.sql("""
                INSERT INTO run_consistency_scan (workspace_id, run_id, state, repairable, message, run_version,
                                                  first_seen_at, checked_at)
                VALUES (:ws, :run, :state, :repairable, :message, :version, :now, :now)
                ON CONFLICT (workspace_id, run_id) DO UPDATE SET
                    first_seen_at = CASE WHEN run_consistency_scan.state = EXCLUDED.state
                                         THEN run_consistency_scan.first_seen_at ELSE EXCLUDED.first_seen_at END,
                    state = EXCLUDED.state, repairable = EXCLUDED.repairable, message = EXCLUDED.message,
                    run_version = EXCLUDED.run_version, checked_at = EXCLUDED.checked_at
                """).param("ws", workspaceId).param("run", runId).param("state", state).param("repairable", repairable)
                .param("message", text).param("version", version).param("now", Sql.ts(now)).update();
    }

    /** A repair made the last finding obsolete. */
    public void forget(UUID workspaceId, UUID runId) {
        jdbc.sql("DELETE FROM run_consistency_scan WHERE workspace_id=:ws AND run_id=:run")
                .param("ws", workspaceId).param("run", runId).update();
    }

    /** Drops rows of requests that have ended; bounded so one pass stays short. */
    public int purgeEnded(int limit) {
        return jdbc.sql("""
                DELETE FROM run_consistency_scan WHERE (workspace_id, run_id) IN (
                    SELECT s.workspace_id, s.run_id FROM run_consistency_scan s
                    JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.run_id
                    WHERE r.lifecycle IN ('completed', 'cancelled') LIMIT :limit)
                """).param("limit", limit).update();
    }

    /** Active requests due for a look, never-scanned first, then the longest-unchecked. Suspended requests are skipped. */
    public List<UUID[]> due(Instant settledBefore, Instant healthyBefore, Instant findingBefore, int limit) {
        return jdbc.sql("""
                SELECT r.workspace_id, r.id FROM workflow_run r
                LEFT JOIN run_consistency_scan s ON s.workspace_id = r.workspace_id AND s.run_id = r.id
                WHERE r.lifecycle IN ('running', 'waiting') AND r.updated_at < :settled
                  AND (s.run_id IS NULL
                       OR (s.state = 'inSync' AND s.checked_at < :healthy)
                       OR (s.state <> 'inSync' AND s.checked_at < :finding))
                  AND NOT EXISTS (SELECT 1 FROM run_suspension x WHERE x.run_id = r.id)
                ORDER BY s.checked_at NULLS FIRST, r.created_at, r.id LIMIT :limit
                """).param("settled", Sql.ts(settledBefore)).param("healthy", Sql.ts(healthyBefore))
                .param("finding", Sql.ts(findingBefore)).param("limit", limit)
                .query((rs, n) -> new UUID[] {Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "id")}).list();
    }

    public RuntimeDtos.ConsistencyFindings list(UUID workspaceId, boolean enabled, int limit) {
        var items = jdbc.sql("""
                SELECT s.run_id, r.reference, s.state, s.repairable, s.message, s.run_version, s.first_seen_at, s.checked_at
                FROM run_consistency_scan s
                JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.run_id
                WHERE s.workspace_id = :ws AND s.state <> 'inSync' AND r.lifecycle IN ('running', 'waiting')
                ORDER BY s.first_seen_at DESC, s.run_id LIMIT :limit
                """).param("ws", workspaceId).param("limit", limit)
                .query((rs, n) -> new RuntimeDtos.ConsistencyFinding(Sql.uuid(rs, "run_id"), rs.getString("reference"),
                        rs.getString("state"), rs.getBoolean("repairable"), rs.getString("message"),
                        rs.getInt("run_version"), Sql.instant(rs, "first_seen_at").toString(),
                        Sql.instant(rs, "checked_at").toString())).list();
        long total = jdbc.sql("""
                SELECT count(*) FROM run_consistency_scan s
                JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.run_id
                WHERE s.workspace_id = :ws AND s.state <> 'inSync' AND r.lifecycle IN ('running', 'waiting')
                """).param("ws", workspaceId).query(Long.class).single();
        long scanned = jdbc.sql("SELECT count(*) FROM run_consistency_scan WHERE workspace_id = :ws")
                .param("ws", workspaceId).query(Long.class).single();
        String last = jdbc.sql("SELECT max(checked_at) AS last FROM run_consistency_scan WHERE workspace_id = :ws")
                .param("ws", workspaceId)
                .query((rs, n) -> rs.getTimestamp("last") == null ? null : Sql.instant(rs, "last").toString())
                .single();
        return new RuntimeDtos.ConsistencyFindings(enabled, (int) scanned, last, (int) total, items);
    }
}
