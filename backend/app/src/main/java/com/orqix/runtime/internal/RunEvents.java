package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Run timeline (S5 section 8). The per-run sequence is allocated with
 * {@code UPDATE workflow_run SET event_seq = event_seq + 1 ... RETURNING event_seq} inside the writing transaction; the
 * row lock orders commits, so a poller reading {@code seq > after} never skips a late-committing event (S3 section 7).
 */
@Repository
public class RunEvents {

    public record EventRow(long seq, String type, String nodeId, UUID actorSubjectId, Instant occurredAt, JsonNode data) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    public record Appended(UUID workspaceId, UUID runId, long sequence, String type, String nodeId, Map<String, ?> data) {}
    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final Clock clock;

    public RunEvents(JdbcClient jdbc, Json json, Clock clock, org.springframework.context.ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long append(UUID workspaceId, UUID runId, String type, String nodeId, UUID actor, Map<String, ?> data) {
        Instant now = clock.instant();
        Long seq = jdbc.sql("""
                UPDATE workflow_run SET event_seq = event_seq + 1, updated_at = :now
                WHERE workspace_id = :ws AND id = :run
                RETURNING event_seq
                """)
                .param("now", Sql.ts(now)).param("ws", workspaceId).param("run", runId)
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("Run " + runId + " not found for event " + type));
        jdbc.sql("""
                INSERT INTO run_event (run_id, seq, workspace_id, type, node_id, actor_subject_id, occurred_at, data)
                VALUES (:run, :seq, :ws, :type, :node, :actor, :at, CAST(:data AS jsonb))
                """)
                .param("run", runId).param("seq", seq).param("ws", workspaceId).param("type", type)
                .param("node", nodeId).param("actor", actor).param("at", Sql.ts(now))
                .param("data", json.write(data == null ? Map.of() : data))
                .update();
        publisher.publishEvent(new Appended(workspaceId, runId, seq, type, nodeId, data == null ? Map.of() : data));
        return seq;
    }

    @Transactional(readOnly = true)
    public List<EventRow> list(UUID workspaceId, UUID runId, long after, int limitPlusOne) {
        return jdbc.sql("""
                SELECT seq, type, node_id, actor_subject_id, occurred_at, data::text AS data FROM run_event
                WHERE workspace_id = :ws AND run_id = :run AND seq > :after
                ORDER BY seq
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("run", runId).param("after", after).param("limit", limitPlusOne)
                .query((rs, n) -> new EventRow(rs.getLong("seq"), rs.getString("type"), rs.getString("node_id"),
                        Sql.uuid(rs, "actor_subject_id"), Sql.instant(rs, "occurred_at"), json.parse(rs.getString("data"))))
                .list();
    }

    @Transactional(readOnly = true)
    public List<String> types(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT type FROM run_event WHERE workspace_id = :ws AND run_id = :run ORDER BY seq")
                .param("ws", workspaceId).param("run", runId).query(String.class).list();
    }
}
