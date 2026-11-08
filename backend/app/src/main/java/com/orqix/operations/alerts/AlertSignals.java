package com.orqix.operations.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.shared.web.Sql;

/**
 * The operational signals an alert rule can watch (CG-O06, docs/build/S144). Gauges count what is true now; windowed
 * signals count events in the rule's window. Each value is a single bounded count query on indexed state; no request
 * value is read.
 */
@Component
public class AlertSignals {

    /** Signals that count events in the rule's window (the others are current counts). */
    public static final Set<String> WINDOWED = Set.of("slaBreaches", "tokenRefreshFailing", "deadLetters");

    /** Which scope each signal accepts: workflow, connection or none. */
    public static final Map<String, Set<String>> SCOPES = Map.of(
            "needsAttention", Set.of("workflow"),
            "slaBreaches", Set.of("workflow"),
            "circuitOpen", Set.of("connection"),
            "tokenRefreshFailing", Set.of("connection"),
            "deadLetters", Set.of("workflow"),
            "pollChecksFailing", Set.of("workflow", "connection"),
            "aiDriftFallback", Set.of());

    private final JdbcClient jdbc;

    public AlertSignals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int value(UUID workspaceId, String signal, int windowMinutes, UUID workflowId, String connectionKey,
            Instant now) {
        Instant since = now.minus(Duration.ofMinutes(windowMinutes));
        String sql = switch (signal) {
            case "needsAttention" -> """
                    SELECT count(*) FROM workflow_run WHERE workspace_id = :ws AND lifecycle = 'needsAttention'
                      AND (CAST(:wf AS uuid) IS NULL OR workflow_id = CAST(:wf AS uuid))""";
            case "slaBreaches" -> """
                    SELECT count(*) FROM task_sla_breach b
                    JOIN workflow_run r ON r.workspace_id = b.workspace_id AND r.id = b.run_id
                    WHERE b.workspace_id = :ws AND b.detected_at > :since AND b.detected_at <= :now
                      AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = CAST(:wf AS uuid))""";
            case "circuitOpen" -> """
                    SELECT count(*) FROM connection_circuit WHERE workspace_id = :ws AND state = 'open'
                      AND (CAST(:conn AS text) IS NULL OR connection_key = CAST(:conn AS text))""";
            case "tokenRefreshFailing" -> """
                    SELECT count(*) FROM connection_call WHERE workspace_id = :ws AND outcome = 'tokenFailure'
                      AND at > :since AND at <= :now
                      AND (CAST(:conn AS text) IS NULL OR connection_key = CAST(:conn AS text))""";
            case "deadLetters" -> """
                    SELECT (SELECT count(*) FROM webhook_delivery WHERE workspace_id = :ws AND status = 'dead'
                              AND dead_at > :since AND dead_at <= :now AND CAST(:wf AS uuid) IS NULL)
                         + (SELECT count(*) FROM external_job j JOIN workflow_run r ON r.id = j.run_id
                            WHERE j.workspace_id = :ws AND j.status = 'failed' AND j.ended_at > :since
                              AND j.ended_at <= :now
                              AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = CAST(:wf AS uuid)))""";
            case "pollChecksFailing" -> """
                    SELECT count(*) FROM poll_wait p
                    JOIN workflow_run r ON r.workspace_id = p.workspace_id AND r.id = p.run_id
                    WHERE p.workspace_id = :ws
                      AND (p.state = 'attention' OR (p.state IN ('waiting', 'checking') AND p.consecutive_errors > 0))
                      AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = CAST(:wf AS uuid))
                      AND (CAST(:conn AS text) IS NULL OR p.connection_key = CAST(:conn AS text))""";
            case "aiDriftFallback" -> """
                    SELECT count(*) FROM ai_skill_version WHERE workspace_id = :ws AND drift_fallback""";
            default -> throw new IllegalArgumentException("Unknown signal " + signal);
        };
        Long value = jdbc.sql(sql).param("ws", workspaceId).param("wf", workflowId).param("conn", connectionKey)
                .param("since", Sql.ts(since)).param("now", Sql.ts(now)).query(Long.class).single();
        return (int) Math.min(Integer.MAX_VALUE, value == null ? 0 : value);
    }
}
