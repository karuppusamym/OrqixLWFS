package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/** {@code message_subscription} and {@code inbound_message} (S16): every query is scoped by workspace. */
@Repository
public class MessageStore {

    public static final String WAITING = "waiting";
    public static final String RECEIVED = "received";
    public static final String TIMED_OUT = "timed_out";
    public static final String CANCELLED = "cancelled";

    public static final String BUFFERED = "buffered";
    public static final String DELIVERED = "delivered";
    public static final String EXPIRED = "expired";
    public static final String DISCARDED = "discarded";

    /** A request waiting (or that waited) for a message. */
    public record Subscription(UUID id, UUID workspaceId, UUID runId, String nodeId, String messageName,
            String correlationKey, String engineExecutionId, String status, UUID messageId, Instant createdAt,
            Instant expiresAt) {
    }

    /** A message received through the API. */
    public record Inbound(UUID id, UUID workspaceId, String name, String correlationKey, JsonNode payload,
            String status, int matched, UUID senderSubjectId, Instant receivedAt, Instant expiresAt) {
    }

    private static final String SUBSCRIPTION_COLUMNS = """
            id, workspace_id, run_id, node_id, message_name, correlation_key, engine_execution_id, status, message_id,
            created_at, expires_at
            """;
    private static final String INBOUND_COLUMNS = """
            id, workspace_id, name, correlation_key, payload::text AS payload, status, matched, sender_subject_id,
            received_at, expires_at
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public MessageStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Only senders take this workspace lock; wait registration keeps run-first locking. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void serializeSend(UUID workspaceId) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .param("scope", "orqix-message:" + workspaceId).query(Integer.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockRun(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id=:ws AND id=:run AND lifecycle IN ('running','waiting','needsAttention') FOR UPDATE SKIP LOCKED")
                .param("ws",workspaceId).param("run",runId).query(UUID.class).optional().isPresent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accepted(UUID workspaceId, UUID messageId, JsonNode response) {
        jdbc.sql("UPDATE inbound_message SET accepted_response=CAST(:response AS jsonb) WHERE workspace_id=:ws AND id=:id")
                .param("response",json.write(response)).param("ws",workspaceId).param("id",messageId).update();
    }

    @Transactional(readOnly = true)
    public Optional<JsonNode> acceptance(UUID workspaceId, UUID messageId) {
        return jdbc.sql("SELECT accepted_response::text FROM inbound_message WHERE workspace_id=:ws AND id=:id AND accepted_response IS NOT NULL")
                .param("ws",workspaceId).param("id",messageId).query(String.class).optional().map(json::parse);
    }

    // ---------------------------------------------------------------- subscriptions

    /** @return the new subscription ID, or empty when this run already has one for the step (a repeated event) */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> insertSubscription(UUID id, UUID workspaceId, UUID runId, String nodeId, String messageName,
            String correlationKey, String engineExecutionId, Instant createdAt, Instant expiresAt) {
        int inserted = jdbc.sql("""
                INSERT INTO message_subscription (id, workspace_id, run_id, node_id, message_name, correlation_key,
                                                  engine_execution_id, status, created_at, expires_at)
                VALUES (:id, :ws, :run, :node, :name, :key, :execution, 'waiting', :now, :expires)
                ON CONFLICT (run_id, node_id) DO NOTHING
                """)
                .param("id", id).param("ws", workspaceId).param("run", runId).param("node", nodeId)
                .param("name", messageName).param("key", correlationKey).param("execution", engineExecutionId)
                .param("now", Sql.ts(createdAt)).param("expires", Sql.ts(expiresAt)).update();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> find(UUID workspaceId, UUID id) {
        return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS + " FROM message_subscription WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", id).query(this::subscription).optional();
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> findForStep(UUID workspaceId, UUID runId, String nodeId) {
        return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS
                + " FROM message_subscription WHERE workspace_id = :ws AND run_id = :run AND node_id = :node")
                .param("ws", workspaceId).param("run", runId).param("node", nodeId).query(this::subscription).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Subscription> lock(UUID workspaceId, UUID id) {
        return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS
                + " FROM message_subscription WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", workspaceId).param("id", id).query(this::subscription).optional();
    }

    /**
     * Subscriptions of active requests waiting for this message about this key, oldest first. The run join is what
     * makes a cancelled or finished request unreachable at once.
     */
    @Transactional(readOnly = true)
    public List<Subscription> waiting(UUID workspaceId, String name, String correlationKey, int limit) {
        return jdbc.sql("SELECT s.id, s.workspace_id, s.run_id, s.node_id, s.message_name, s.correlation_key,"
                + " s.engine_execution_id, s.status, s.message_id, s.created_at, s.expires_at"
                + """
                 FROM message_subscription s
                 JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.run_id
                 WHERE s.workspace_id = :ws AND s.message_name = :name AND s.correlation_key = :key
                   AND s.status = 'waiting' AND r.lifecycle IN ('running', 'waiting', 'needsAttention')
                 ORDER BY s.run_id, s.created_at, s.id
                 LIMIT :limit
                """)
                .param("ws", workspaceId).param("name", name).param("key", correlationKey).param("limit", limit)
                .query(this::subscription).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void close(UUID workspaceId, UUID id, String status, UUID messageId, Instant now) {
        jdbc.sql("""
                UPDATE message_subscription SET status = :status, message_id = :message, closed_at = :now
                WHERE workspace_id = :ws AND id = :id AND status = 'waiting'
                """)
                .param("status", status).param("message", messageId).param("now", Sql.ts(now))
                .param("ws", workspaceId).param("id", id).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void closeAllForRun(UUID workspaceId, UUID runId, Instant now) {
        jdbc.sql("""
                UPDATE message_subscription SET status = 'cancelled', closed_at = :now
                WHERE workspace_id = :ws AND run_id = :run AND status = 'waiting'
                """)
                .param("now", Sql.ts(now)).param("ws", workspaceId).param("run", runId).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setDeadline(UUID workspaceId, UUID runId, String nodeId, Instant deadline) {
        jdbc.sql("""
                UPDATE message_subscription SET expires_at = :deadline
                WHERE workspace_id = :ws AND run_id = :run AND node_id = :node AND status = 'waiting'
                """)
                .param("deadline", Sql.ts(deadline)).param("ws", workspaceId).param("run", runId)
                .param("node", nodeId).update();
    }

    /** Subscriptions that wait for a message that a buffered message could satisfy, oldest first. */
    @Transactional(readOnly = true)
    public List<UUID> waitingWithBufferedMessage(Instant now, int limit) {
        return jdbc.sql("""
                SELECT s.id FROM message_subscription s
                WHERE s.status = 'waiting' AND EXISTS (
                    SELECT 1 FROM inbound_message m
                    WHERE m.workspace_id = s.workspace_id AND m.name = s.message_name
                      AND m.correlation_key = s.correlation_key AND m.status = 'buffered' AND m.expires_at > :now)
                ORDER BY s.created_at, s.id
                LIMIT :limit
                """)
                .param("now", Sql.ts(now)).param("limit", limit).query(UUID.class).list();
    }

    /** Waiting subscriptions of runs that ended without closing them (a terminate end, a cancel). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelOrphans(Instant now) {
        return jdbc.sql("""
                UPDATE message_subscription s SET status = 'cancelled', closed_at = :now
                WHERE s.status = 'waiting' AND EXISTS (
                    SELECT 1 FROM workflow_run r WHERE r.workspace_id = s.workspace_id AND r.id = s.run_id
                      AND r.lifecycle IN ('completed', 'cancelled'))
                """)
                .param("now", Sql.ts(now)).update();
    }

    // ---------------------------------------------------------------- inbound messages

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertInbound(UUID id, UUID workspaceId, String name, String correlationKey, JsonNode payload,
            UUID sender, Instant receivedAt, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO inbound_message (id, workspace_id, name, correlation_key, payload, status, matched,
                                             sender_subject_id, received_at, expires_at)
                VALUES (:id, :ws, :name, :key, CAST(:payload AS jsonb), 'buffered', 0, :sender, :now, :expires)
                """)
                .param("id", id).param("ws", workspaceId).param("name", name).param("key", correlationKey)
                .param("payload", json.write(payload)).param("sender", sender).param("now", Sql.ts(receivedAt))
                .param("expires", Sql.ts(expiresAt)).update();
    }

    @Transactional(readOnly = true)
    public Optional<Inbound> findInbound(UUID workspaceId, UUID id) {
        return jdbc.sql("SELECT " + INBOUND_COLUMNS + " FROM inbound_message WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", id).query(this::inbound).optional();
    }

    /** Oldest unexpired buffered message for the key, locked so that two pods never take the same one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Inbound> lockOldestBuffered(UUID workspaceId, String name, String correlationKey, Instant now) {
        return jdbc.sql("SELECT " + INBOUND_COLUMNS + """
                 FROM inbound_message
                 WHERE workspace_id = :ws AND name = :name AND correlation_key = :key AND status = 'buffered'
                   AND expires_at > :now
                 ORDER BY received_at, id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """)
                .param("ws", workspaceId).param("name", name).param("key", correlationKey).param("now", Sql.ts(now))
                .query(this::inbound).optional();
    }

    /** Closes a message and drops its payload; {@code matched} is the number of waits it reached. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeInbound(UUID workspaceId, UUID id, String status, int matched, Instant now) {
        jdbc.sql("""
                UPDATE inbound_message SET status = :status, matched = :matched, payload = '{}'::jsonb, closed_at = :now
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("status", status).param("matched", matched).param("now", Sql.ts(now))
                .param("ws", workspaceId).param("id", id).update();
    }

    @Transactional(readOnly = true)
    public int bufferedInWorkspace(UUID workspaceId, Instant now) {
        return jdbc.sql("SELECT count(*) FROM inbound_message WHERE workspace_id = :ws AND status = 'buffered'"
                + " AND expires_at > :now").param("ws", workspaceId).param("now", Sql.ts(now))
                .query(Integer.class).single();
    }

    @Transactional(readOnly = true)
    public int bufferedForKey(UUID workspaceId, String name, String correlationKey, Instant now) {
        return jdbc.sql("SELECT count(*) FROM inbound_message WHERE workspace_id = :ws AND name = :name"
                + " AND correlation_key = :key AND status = 'buffered' AND expires_at > :now")
                .param("ws", workspaceId).param("name", name).param("key", correlationKey).param("now", Sql.ts(now))
                .query(Integer.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int expireBuffered(Instant now) {
        return jdbc.sql("""
                UPDATE inbound_message SET status = 'expired', payload = '{}'::jsonb, closed_at = :now
                WHERE status = 'buffered' AND expires_at <= :now
                """).param("now", Sql.ts(now)).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeClosed(Instant before, Instant now) {
        return jdbc.sql("DELETE FROM inbound_message m WHERE m.status <> 'buffered' AND m.closed_at < :before AND NOT EXISTS (SELECT 1 FROM idempotency_record i WHERE i.resource_type='message' AND i.resource_id=m.id::text AND i.expires_at>:now)")
                .param("before", Sql.ts(before)).param("now",Sql.ts(now)).update();
    }

    /** Requests (reference and step) a message was delivered to, oldest first. */
    @Transactional(readOnly = true)
    public List<Delivered> deliveredTo(UUID workspaceId, UUID messageId) {
        return jdbc.sql("""
                SELECT s.run_id, r.reference, s.node_id
                FROM message_subscription s
                JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.run_id
                WHERE s.workspace_id = :ws AND s.message_id = :message AND s.status = 'received'
                ORDER BY s.closed_at, s.id
                """)
                .param("ws", workspaceId).param("message", messageId)
                .query((rs, n) -> new Delivered(Sql.uuid(rs, "run_id"), rs.getString("reference"), rs.getString("node_id")))
                .list();
    }

    public record Delivered(UUID runId, String runReference, String nodeId) {
    }

    private Subscription subscription(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Subscription(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                rs.getString("node_id"), rs.getString("message_name"), rs.getString("correlation_key"),
                rs.getString("engine_execution_id"), rs.getString("status"), Sql.uuid(rs, "message_id"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "expires_at"));
    }

    private Inbound inbound(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Inbound(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("name"),
                rs.getString("correlation_key"), json.parse(rs.getString("payload")), rs.getString("status"),
                rs.getInt("matched"), Sql.uuid(rs, "sender_subject_id"), Sql.instant(rs, "received_at"),
                Sql.instant(rs, "expires_at"));
    }
}
