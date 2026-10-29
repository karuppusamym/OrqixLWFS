package com.orqix.runtime.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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

/**
 * {@code callback_wait} (S98) joined with its subscription and request, so one indexed read gives everything the poll
 * needs. Every query is scoped by workspace except the token lookup, which is by hash and checked against the
 * workspace by the caller.
 */
@Repository
public class CallbackStore {

    /** One callback wait with the state of its subscription and request. */
    public record Row(UUID id, UUID workspaceId, UUID runId, String nodeId, UUID subscriptionId, Duration validFor,
            String tokenSha256, Instant tokenIssuedAt, Instant tokenExpiresAt, int tokensIssued, Instant resumedAt,
            String resumeIdemKey, String resumeRequestHash, JsonNode resumeResult, int version, Instant createdAt,
            String subscriptionStatus, String messageName, String lifecycle, String runReference, UUID releaseId,
            UUID workflowId) {
    }

    private static final String SELECT = """
            SELECT c.id, c.workspace_id, c.run_id, c.node_id, c.subscription_id,
                   EXTRACT(EPOCH FROM c.token_valid_for)::bigint AS valid_seconds, c.token_sha256, c.token_issued_at,
                   c.token_expires_at, c.tokens_issued, c.resumed_at, c.resume_idem_key, c.resume_request_hash,
                   c.resume_result::text AS resume_result, c.version, c.created_at, s.status AS subscription_status,
                   s.message_name, r.lifecycle, r.reference, r.release_id, r.workflow_id
            FROM callback_wait c
            JOIN message_subscription s ON s.id = c.subscription_id
            JOIN workflow_run r ON r.workspace_id = c.workspace_id AND r.id = c.run_id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public CallbackStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return false when the wait already has a row (a repeated engine event) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(UUID id, UUID workspaceId, UUID runId, String nodeId, UUID subscriptionId, Duration validFor,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO callback_wait (id, workspace_id, run_id, node_id, subscription_id, token_valid_for, created_at)
                VALUES (:id, :ws, :run, :node, :sub, make_interval(secs => :seconds), :now)
                ON CONFLICT DO NOTHING
                """).param("id", id).param("ws", workspaceId).param("run", runId).param("node", nodeId)
                .param("sub", subscriptionId).param("seconds", validFor.toSeconds()).param("now", Sql.ts(now))
                .update() == 1;
    }

    @Transactional(readOnly = true)
    public List<Row> list(UUID workspaceId, UUID runId, int limit) {
        return jdbc.sql(SELECT + " WHERE c.workspace_id = :ws AND (CAST(:run AS uuid) IS NULL OR c.run_id = :run)"
                + " ORDER BY c.created_at DESC, c.id LIMIT :limit")
                .param("ws", workspaceId).param("run", runId).param("limit", limit).query(this::row).list();
    }

    @Transactional(readOnly = true)
    public Optional<Row> find(UUID workspaceId, UUID id) {
        return jdbc.sql(SELECT + " WHERE c.workspace_id = :ws AND c.id = :id")
                .param("ws", workspaceId).param("id", id).query(this::row).optional();
    }

    /** Locks the callback row only (always the first lock a callback command takes). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Row> lock(UUID workspaceId, UUID id) {
        return jdbc.sql(SELECT + " WHERE c.workspace_id = :ws AND c.id = :id FOR UPDATE OF c")
                .param("ws", workspaceId).param("id", id).query(this::row).optional();
    }

    @Transactional(readOnly = true)
    public Optional<Row> findByToken(String sha256) {
        return jdbc.sql(SELECT + " WHERE c.token_sha256 = :sha").param("sha", sha256).query(this::row).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Row> lockByToken(String sha256) {
        return jdbc.sql(SELECT + " WHERE c.token_sha256 = :sha FOR UPDATE OF c").param("sha", sha256)
                .query(this::row).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UUID id, String sha256, Instant issuedAt, Instant expiresAt, UUID issuedBy) {
        jdbc.sql("""
                UPDATE callback_wait SET token_sha256 = :sha, token_issued_at = :at, token_expires_at = :expires,
                       token_issued_by = :by, tokens_issued = tokens_issued + 1, expiry_audited = false,
                       version = version + 1
                WHERE id = :id
                """).param("sha", sha256).param("at", Sql.ts(issuedAt)).param("expires", Sql.ts(expiresAt))
                .param("by", issuedBy).param("id", id).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(UUID id) {
        jdbc.sql("""
                UPDATE callback_wait SET token_sha256 = NULL, token_issued_at = NULL, token_expires_at = NULL,
                       version = version + 1
                WHERE id = :id
                """).param("id", id).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resumed(UUID id, Instant at, String idemKey, String requestHash, JsonNode result) {
        jdbc.sql("""
                UPDATE callback_wait SET resumed_at = :at, resume_idem_key = :key, resume_request_hash = :hash,
                       resume_result = CAST(:result AS jsonb), version = version + 1
                WHERE id = :id AND resumed_at IS NULL
                """).param("at", Sql.ts(at)).param("key", idemKey).param("hash", requestHash)
                .param("result", json.write(result)).param("id", id).update();
    }

    /** Live tokens of a workspace (issued, not resumed, not expired): the issue cap. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long liveTokens(UUID workspaceId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM callback_wait WHERE workspace_id = :ws AND token_sha256 IS NOT NULL
                  AND resumed_at IS NULL AND token_expires_at > :now
                """).param("ws", workspaceId).param("now", Sql.ts(now)).query(Long.class).single();
    }

    /** Expired, unresumed, not yet audited tokens; SKIP LOCKED so every pod may sweep. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID[]> lockExpired(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id, workspace_id, run_id FROM callback_wait
                WHERE token_sha256 IS NOT NULL AND resumed_at IS NULL AND NOT expiry_audited AND token_expires_at <= :now
                ORDER BY token_expires_at LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("now", Sql.ts(now)).param("limit", limit)
                .query((rs, n) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("run_id", UUID.class)})
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expiryAudited(UUID id) {
        jdbc.sql("UPDATE callback_wait SET expiry_audited = true, version = version + 1 WHERE id = :id")
                .param("id", id).update();
    }

    /**
     * Deletes up to {@code limit} closed waits whose closing time (resume, subscription close or request end) is before
     * {@code before} (S104). SKIP LOCKED so every pod may purge. @return the workspace id of each deleted row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> purgeClosed(Instant before, int limit) {
        return jdbc.sql("""
                WITH doomed AS (
                    SELECT c.id FROM callback_wait c
                    JOIN message_subscription s ON s.id = c.subscription_id
                    JOIN workflow_run r ON r.workspace_id = c.workspace_id AND r.id = c.run_id
                    -- A wait closed before the cut-off was created before it: the index on created_at keeps this to old rows.
                    WHERE c.created_at < :before
                      AND coalesce(c.resumed_at,
                                   CASE WHEN s.status <> 'waiting' THEN coalesce(s.closed_at, r.ended_at) END,
                                   r.ended_at) < :before
                    ORDER BY c.created_at LIMIT :limit
                    FOR UPDATE OF c SKIP LOCKED)
                DELETE FROM callback_wait c USING doomed d WHERE c.id = d.id RETURNING c.workspace_id
                """).param("before", Sql.ts(before)).param("limit", limit)
                .query((rs, n) -> rs.getObject("workspace_id", UUID.class)).list();
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        String result = rs.getString("resume_result");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("node_id"), rs.getObject("subscription_id", UUID.class),
                Duration.ofSeconds(rs.getLong("valid_seconds")), rs.getString("token_sha256"),
                Sql.instant(rs, "token_issued_at"), Sql.instant(rs, "token_expires_at"), rs.getInt("tokens_issued"),
                Sql.instant(rs, "resumed_at"), rs.getString("resume_idem_key"), rs.getString("resume_request_hash"),
                result == null ? null : json.parse(result), rs.getInt("version"), Sql.instant(rs, "created_at"),
                rs.getString("subscription_status"), rs.getString("message_name"), rs.getString("lifecycle"),
                rs.getString("reference"), rs.getObject("release_id", UUID.class),
                rs.getObject("workflow_id", UUID.class));
    }
}
