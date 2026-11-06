package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * The uniform "Waiting for" projection (docs/build/S129, CG-W08). Every wait kind already shows itself as a current step
 * of the request (approval, timer, message, callback, poll, external job, child request, signal); this projection is
 * derived from those steps in the same transaction as every change of them ({@link RunStore#mutate}), so it can never
 * disagree with the request page and needs no extra write in each wait's own code. A step that is not a wait (a call
 * that is executing) has no row.
 */
@Service
public class WaitStates {

    /** One open wait. */
    public record WaitRow(UUID runId, String nodeId, String kind, String label, String subject, Instant since,
            Instant nextCheckAt, Instant dueAt, Instant deadlineAt, int attempts, String correlationRef,
            String connectionKey, UUID pollWaitId, String runReference) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public WaitStates(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Re-derives the waits of a request from its current steps. Called by {@link RunStore#mutate} only. */
    @Transactional(propagation = Propagation.MANDATORY)
    void sync(UUID workspaceId, UUID runId, JsonNode before, JsonNode steps) {
        if (before != null && before.equals(steps)) {
            return;
        }
        Map<String, WaitRow> waits = new LinkedHashMap<>();
        for (JsonNode step : steps) {
            WaitRow row = fromStep(runId, step);
            if (row != null) {
                waits.putIfAbsent(row.nodeId(), row);
            }
        }
        if (waits.isEmpty()) {
            jdbc.sql("DELETE FROM wait_state WHERE workspace_id = :ws AND run_id = :run").param("ws", workspaceId)
                    .param("run", runId).update();
            return;
        }
        jdbc.sql("DELETE FROM wait_state WHERE workspace_id = :ws AND run_id = :run AND NOT (node_id = ANY (:nodes))")
                .param("ws", workspaceId).param("run", runId)
                .param("nodes", waits.keySet().toArray(new String[0])).update();
        Instant now = clock.instant();
        for (WaitRow w : waits.values()) {
            jdbc.sql("""
                    INSERT INTO wait_state (workspace_id, run_id, node_id, kind, label, subject, since, next_check_at, due_at,
                                            deadline_at, attempts, correlation_ref, connection_key, poll_wait_id, updated_at)
                    VALUES (:ws, :run, :node, :kind, :label, :subject, :since, :next, :due, :deadline, :attempts, :ref,
                            :conn, :poll, :now)
                    ON CONFLICT (workspace_id, run_id, node_id) DO UPDATE SET kind = EXCLUDED.kind, label = EXCLUDED.label,
                        subject = EXCLUDED.subject, since = EXCLUDED.since, next_check_at = EXCLUDED.next_check_at,
                        due_at = EXCLUDED.due_at, deadline_at = EXCLUDED.deadline_at, attempts = EXCLUDED.attempts,
                        correlation_ref = EXCLUDED.correlation_ref, connection_key = EXCLUDED.connection_key,
                        poll_wait_id = EXCLUDED.poll_wait_id, updated_at = EXCLUDED.updated_at
                    """).param("ws", workspaceId).param("run", runId).param("node", w.nodeId()).param("kind", w.kind())
                    .param("label", cut(w.label(), 200)).param("subject", cut(w.subject(), 300))
                    .param("since", Sql.ts(w.since())).param("next", Sql.ts(w.nextCheckAt()))
                    .param("due", Sql.ts(w.dueAt())).param("deadline", Sql.ts(w.deadlineAt())).param("attempts", w.attempts())
                    .param("ref", w.correlationRef() == null ? null : cut(w.correlationRef(), 200))
                    .param("conn", w.connectionKey()).param("poll", w.pollWaitId()).param("now", Sql.ts(now)).update();
        }
    }

    /** @return the wait a current step stands for, or null when the step is not waiting */
    static WaitRow fromStep(UUID runId, JsonNode step) {
        String kind = step.path("kind").asString("");
        String nodeId = step.path("nodeId").asString(null);
        if (nodeId == null) {
            return null;
        }
        String label = step.path("label").asString(nodeId);
        Instant since = instant(step.path("since"));
        if (since == null) {
            since = Instant.EPOCH;
        }
        Instant due = instant(step.path("dueAt"));
        Instant next = instant(step.path("nextCheckAt"));
        UUID poll = step.hasNonNull("pollWaitId") ? UUID.fromString(step.path("pollWaitId").asString()) : null;
        String connection = step.path("connectionKey").asString(null);
        int attempts = step.path("checks").asInt(0);
        return switch (kind) {
            case "approval" -> new WaitRow(runId, nodeId, "approval", label, "A decision on " + q(label)
                    + (step.hasNonNull("candidateGroup") ? " by " + step.path("candidateGroup").asString() : ""),
                    since, null, due, null, 0, null, null, null, null);
            case "wait" -> {
                String message = step.path("message").asString(step.path("messageName").asString(null));
                if (message == null) {
                    yield new WaitRow(runId, nodeId, "timer", label, "The time set on " + q(label), since, null, due,
                            null, 0, null, null, null, null);
                }
                boolean callback = step.path("callback").asBoolean(false);
                yield new WaitRow(runId, nodeId, callback ? "callback" : "message", label, (callback
                        ? "An answer from an external system for " : "The message " + q(message) + " for ") + q(label)
                        + (poll == null ? "" : ", with a safety-net check"), since, next, null, due, attempts, message,
                        connection, poll, null);
            }
            case "waitForSignal" -> new WaitRow(runId, nodeId, "signal", label, "A signal for " + q(label), since, null,
                    null, null, 0, step.path("signal").asString(null), null, null, null);
            case "external" -> new WaitRow(runId, nodeId, "externalJob", label, "An outside worker to finish " + q(label)
                    + (step.hasNonNull("topic") ? " (" + step.path("topic").asString() + ")" : ""), since, null, null,
                    null, 0, step.path("topic").asString(null), null, null, null);
            case "child" -> new WaitRow(runId, nodeId, "childRequest", label, "Another request started by " + q(label)
                    + " to finish", since, null, null, null, 0, null, null, null, null);
            case "http" -> step.path("polling").asBoolean(false) ? new WaitRow(runId, nodeId, "poll", label,
                    step.path("subject").asString("The other system to report that " + q(label) + " is done"), since,
                    next, null, due, attempts, null, connection, poll, null) : null;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<WaitRow> forRun(UUID workspaceId, UUID runId) {
        return jdbc.sql("""
                SELECT w.*, r.reference FROM wait_state w JOIN workflow_run r ON r.workspace_id = w.workspace_id AND r.id = w.run_id
                WHERE w.workspace_id = :ws AND w.run_id = :run ORDER BY w.since, w.node_id
                """).param("ws", workspaceId).param("run", runId).query(WaitStates::map).list();
    }

    /**
     * Runs filter "waiting on connection X for longer than Y" (and any kind): oldest first.
     *
     * @param longerThan null for any age
     */
    @Transactional(readOnly = true)
    public List<WaitRow> search(UUID workspaceId, String kind, String connectionKey, Duration longerThan, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT w.*, r.reference FROM wait_state w JOIN workflow_run r ON r.workspace_id = w.workspace_id AND r.id = w.run_id
                WHERE w.workspace_id = :ws
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ws", workspaceId);
        if (kind != null) {
            sql.append(" AND w.kind = :kind");
            params.put("kind", kind);
        }
        if (connectionKey != null) {
            sql.append(" AND w.connection_key = :conn");
            params.put("conn", connectionKey);
        }
        if (longerThan != null) {
            sql.append(" AND w.since <= :before");
            params.put("before", Sql.ts(clock.instant().minus(longerThan)));
        }
        sql.append(" ORDER BY w.since, w.run_id, w.node_id LIMIT :limit");
        params.put("limit", limit);
        var statement = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            statement = statement.param(e.getKey(), e.getValue());
        }
        return new ArrayList<>(statement.query(WaitStates::map).list());
    }

    private static WaitRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new WaitRow(Sql.uuid(rs, "run_id"), rs.getString("node_id"), rs.getString("kind"), rs.getString("label"),
                rs.getString("subject"), Sql.instant(rs, "since"), Sql.instant(rs, "next_check_at"),
                Sql.instant(rs, "due_at"), Sql.instant(rs, "deadline_at"), rs.getInt("attempts"),
                rs.getString("correlation_ref"), rs.getString("connection_key"),
                (UUID) rs.getObject("poll_wait_id"), rs.getString("reference"));
    }

    private static Instant instant(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String q(String text) {
        return "“" + text + "”";
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
