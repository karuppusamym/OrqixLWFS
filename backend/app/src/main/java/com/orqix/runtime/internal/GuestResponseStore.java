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
 * {@code guest_response}, {@code guest_response_file} and {@code guest_domain} (S140). Rows are read joined with their
 * subscription and request, like {@link CallbackStore}. Every query is scoped by workspace except the token lookup,
 * which is by hash and checked against the workspace by the caller.
 */
@Repository
public class GuestResponseStore {

    /** The uploader of guest files on the request (V330). */
    public static final UUID GUEST_SUBJECT = UUID.fromString("00000000-0000-4000-8000-00000000f140");

    public record Row(UUID id, UUID workspaceId, UUID runId, String nodeId, UUID subscriptionId, Duration validFor,
            String sendState, int sendAttempts, String sentDomain, String tokenSha256, Instant tokenIssuedAt,
            Instant tokenExpiresAt, int tokensIssued, Instant revokedAt, Instant firstViewedAt, Instant submittedAt, String submitIdemKey, String submitRequestHash, JsonNode submitResult, int version,
            Instant createdAt, String subscriptionStatus, String lifecycle, String runReference, UUID releaseId,
            JsonNode input) {
    }

    public record File(UUID id, String name, String contentType, long sizeBytes, String sha256, String scanStatus) {
    }

    public record Content(File file, byte[] bytes) {
    }

    private static final String SELECT = """
            SELECT g.id, g.workspace_id, g.run_id, g.node_id, g.subscription_id,
                   EXTRACT(EPOCH FROM g.link_valid_for)::bigint AS valid_seconds, g.send_state, g.send_attempts,
                   g.sent_domain, g.token_sha256, g.token_issued_at, g.token_expires_at, g.tokens_issued, g.revoked_at,
                   g.first_viewed_at, g.submitted_at, g.submit_idem_key, g.submit_request_hash,
                   g.submit_result::text AS submit_result, g.version, g.created_at, s.status AS subscription_status,
                   r.lifecycle, r.reference, r.release_id, r.input::text AS input
            FROM guest_response g
            JOIN message_subscription s ON s.id = g.subscription_id
            JOIN workflow_run r ON r.workspace_id = g.workspace_id AND r.id = g.run_id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public GuestResponseStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(UUID id, UUID workspaceId, UUID runId, String nodeId, UUID subscriptionId, Duration validFor,
            String sendState, Instant now) {
        return jdbc.sql("""
                INSERT INTO guest_response (id, workspace_id, run_id, node_id, subscription_id, link_valid_for,
                                            send_state, created_at)
                VALUES (:id, :ws, :run, :node, :sub, make_interval(secs => :seconds), :state, :now)
                ON CONFLICT DO NOTHING
                """).param("id", id).param("ws", workspaceId).param("run", runId).param("node", nodeId)
                .param("sub", subscriptionId).param("seconds", validFor.toSeconds()).param("state", sendState)
                .param("now", Sql.ts(now)).update() == 1;
    }

    @Transactional(readOnly = true)
    public List<Row> list(UUID workspaceId, UUID runId, int limit) {
        return jdbc.sql(SELECT + " WHERE g.workspace_id = :ws AND (CAST(:run AS uuid) IS NULL OR g.run_id = :run)"
                + " ORDER BY g.created_at DESC, g.id LIMIT :limit")
                .param("ws", workspaceId).param("run", runId).param("limit", limit).query(this::row).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Row> lock(UUID workspaceId, UUID id) {
        return jdbc.sql(SELECT + " WHERE g.workspace_id = :ws AND g.id = :id FOR UPDATE OF g")
                .param("ws", workspaceId).param("id", id).query(this::row).optional();
    }

    @Transactional(readOnly = true)
    public Optional<Row> findByToken(String sha256) {
        return jdbc.sql(SELECT + " WHERE g.token_sha256 = :sha").param("sha", sha256).query(this::row).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Row> lockByToken(String sha256) {
        return jdbc.sql(SELECT + " WHERE g.token_sha256 = :sha FOR UPDATE OF g").param("sha", sha256)
                .query(this::row).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UUID id, String sha256, Instant issuedAt, Instant expiresAt, UUID issuedBy, String sendState,
            String sentDomain) {
        jdbc.sql("""
                UPDATE guest_response SET token_sha256 = :sha, token_issued_at = :at, token_expires_at = :expires,
                       token_issued_by = :by, tokens_issued = tokens_issued + 1, revoked_at = NULL,
                       expiry_audited = false, send_state = :state, sent_domain = :domain, version = version + 1
                WHERE id = :id
                """).param("sha", sha256).param("at", Sql.ts(issuedAt)).param("expires", Sql.ts(expiresAt))
                .param("by", issuedBy).param("state", sendState).param("domain", sentDomain).param("id", id).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendState(UUID id, String state, String domain, boolean attempt) {
        jdbc.sql("""
                UPDATE guest_response SET send_state = :state, sent_domain = coalesce(:domain, sent_domain),
                       send_attempts = least(10, send_attempts + :inc), version = version + 1
                WHERE id = :id
                """).param("state", state).param("domain", domain).param("inc", attempt ? 1 : 0).param("id", id)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(UUID id, Instant at) {
        jdbc.sql("""
                UPDATE guest_response SET token_sha256 = NULL, token_issued_at = NULL, token_expires_at = NULL,
                       revoked_at = :at, version = version + 1
                WHERE id = :id
                """).param("at", Sql.ts(at)).param("id", id).update();
        deleteFiles(id);
    }

    /** @return true for the first view of this link (audited once) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean viewed(UUID id, Instant at) {
        return jdbc.sql("UPDATE guest_response SET first_viewed_at = :at WHERE id = :id AND first_viewed_at IS NULL")
                .param("at", Sql.ts(at)).param("id", id).update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void submitted(UUID id, Instant at, String idemKey, String requestHash, JsonNode result) {
        jdbc.sql("""
                UPDATE guest_response SET submitted_at = :at, submit_idem_key = :key, submit_request_hash = :hash,
                       submit_result = CAST(:result AS jsonb), version = version + 1
                WHERE id = :id AND submitted_at IS NULL
                """).param("at", Sql.ts(at)).param("key", idemKey).param("hash", requestHash)
                .param("result", json.write(result)).param("id", id).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long liveTokens(UUID workspaceId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM guest_response WHERE workspace_id = :ws AND token_sha256 IS NOT NULL
                  AND submitted_at IS NULL AND token_expires_at > :now
                """).param("ws", workspaceId).param("now", Sql.ts(now)).query(Long.class).single();
    }

    /** Links waiting to be emailed; SKIP LOCKED so every pod may send. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Row> lockPending(int limit) {
        return jdbc.sql(SELECT + " WHERE g.send_state = 'pending' ORDER BY g.created_at LIMIT :limit"
                + " FOR UPDATE OF g SKIP LOCKED").param("limit", limit).query(this::row).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID[]> lockExpired(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id, workspace_id, run_id FROM guest_response
                WHERE token_sha256 IS NOT NULL AND submitted_at IS NULL AND NOT expiry_audited AND token_expires_at <= :now
                ORDER BY token_expires_at LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("now", Sql.ts(now)).param("limit", limit)
                .query((rs, n) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("run_id", UUID.class)})
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expiryAudited(UUID id) {
        jdbc.sql("UPDATE guest_response SET expiry_audited = true, version = version + 1 WHERE id = :id")
                .param("id", id).update();
        deleteFiles(id);
    }

    /** Staged files of links whose wait closed (answered elsewhere, timed out, request ended). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteFilesOfClosed() {
        return jdbc.sql("""
                DELETE FROM guest_response_file f USING guest_response g, message_subscription s
                WHERE f.guest_response_id = g.id AND s.id = g.subscription_id AND s.status <> 'waiting'
                """).update();
    }

    /** Deletes closed links older than {@code before} with their files. @return the workspace of each deleted link */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> purgeClosed(Instant before, int limit) {
        List<UUID> ids = jdbc.sql("""
                SELECT g.id FROM guest_response g
                JOIN message_subscription s ON s.id = g.subscription_id
                JOIN workflow_run r ON r.workspace_id = g.workspace_id AND r.id = g.run_id
                WHERE g.created_at < :before
                  AND coalesce(g.submitted_at, CASE WHEN s.status <> 'waiting' THEN coalesce(s.closed_at, r.ended_at) END,
                               r.ended_at) < :before
                ORDER BY g.created_at LIMIT :limit
                FOR UPDATE OF g SKIP LOCKED
                """).param("before", Sql.ts(before)).param("limit", limit).query(UUID.class).list();
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.sql("DELETE FROM guest_response_file WHERE guest_response_id IN (:ids)").param("ids", ids).update();
        return jdbc.sql("DELETE FROM guest_response WHERE id IN (:ids) RETURNING workspace_id").param("ids", ids)
                .query((rs, n) -> rs.getObject("workspace_id", UUID.class)).list();
    }

    // ---------------------------------------------------------------- files

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertFile(UUID id, UUID responseId, String tokenSha256, String name, String contentType, byte[] bytes,
            String sha256, String scanStatus, Instant now) {
        jdbc.sql("""
                INSERT INTO guest_response_file (id, guest_response_id, token_sha256, file_name, content_type,
                                                 size_bytes, sha256, scan_status, content, created_at)
                VALUES (:id, :g, :token, :name, :type, :size, :sha, :scan, :content, :now)
                """).param("id", id).param("g", responseId).param("token", tokenSha256).param("name", name)
                .param("type", contentType).param("size", (long) bytes.length).param("sha", sha256)
                .param("scan", scanStatus).param("content", bytes).param("now", Sql.ts(now)).update();
    }

    /** Files uploaded through the current token of the link, oldest first. */
    @Transactional(readOnly = true)
    public List<File> files(UUID responseId, String tokenSha256) {
        return jdbc.sql("""
                SELECT id, file_name, content_type, size_bytes, sha256, scan_status FROM guest_response_file
                WHERE guest_response_id = :g AND token_sha256 = :token ORDER BY created_at, id
                """).param("g", responseId).param("token", tokenSha256)
                .query((rs, n) -> new File(rs.getObject("id", UUID.class), rs.getString("file_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("scan_status")))
                .list();
    }

    /** Uploads through this link in the last minute (any token): the upload rate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recentUploads(UUID responseId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM guest_response_file WHERE guest_response_id = :g AND created_at > :since")
                .param("g", responseId).param("since", Sql.ts(since)).query(Integer.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Content> content(UUID responseId, UUID fileId) {
        return jdbc.sql("""
                SELECT id, file_name, content_type, size_bytes, sha256, scan_status, content FROM guest_response_file
                WHERE guest_response_id = :g AND id = :id
                """).param("g", responseId).param("id", fileId)
                .query((rs, n) -> new Content(new File(rs.getObject("id", UUID.class), rs.getString("file_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("scan_status")), rs.getBytes("content")))
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteFiles(UUID responseId) {
        jdbc.sql("DELETE FROM guest_response_file WHERE guest_response_id = :g").param("g", responseId).update();
    }

    /** The guest uploader subject (V330); recreated if a test or an administrator removed it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureGuestSubject() {
        jdbc.sql("""
                INSERT INTO subject (id, issuer, external_subject, display_name, email, first_seen_at)
                VALUES (:id, 'orqix:guest', 'guest-response', 'Guest (response link)', NULL, now())
                ON CONFLICT DO NOTHING
                """).param("id", GUEST_SUBJECT).update();
    }

    // ---------------------------------------------------------------- approved domains

    @Transactional(readOnly = true)
    public List<String> domains(UUID workspaceId) {
        return jdbc.sql("SELECT domain FROM guest_domain WHERE workspace_id = :ws ORDER BY domain")
                .param("ws", workspaceId).query(String.class).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceDomains(UUID workspaceId, List<String> domains, UUID by, Instant now) {
        jdbc.sql("DELETE FROM guest_domain WHERE workspace_id = :ws").param("ws", workspaceId).update();
        for (String domain : domains) {
            jdbc.sql("INSERT INTO guest_domain (workspace_id, domain, added_by, added_at) VALUES (:ws, :d, :by, :now)")
                    .param("ws", workspaceId).param("d", domain).param("by", by).param("now", Sql.ts(now)).update();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean domainApproved(UUID workspaceId, String domain) {
        return jdbc.sql("SELECT count(*) FROM guest_domain WHERE workspace_id = :ws AND domain = :d")
                .param("ws", workspaceId).param("d", domain).query(Long.class).single() > 0;
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        String result = rs.getString("submit_result");
        String input = rs.getString("input");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("node_id"), rs.getObject("subscription_id", UUID.class),
                Duration.ofSeconds(rs.getLong("valid_seconds")), rs.getString("send_state"), rs.getInt("send_attempts"),
                rs.getString("sent_domain"), rs.getString("token_sha256"), Sql.instant(rs, "token_issued_at"),
                Sql.instant(rs, "token_expires_at"), rs.getInt("tokens_issued"), Sql.instant(rs, "revoked_at"),
                Sql.instant(rs, "first_viewed_at"), Sql.instant(rs, "submitted_at"),
                rs.getString("submit_idem_key"), rs.getString("submit_request_hash"),
                result == null ? null : json.parse(result), rs.getInt("version"), Sql.instant(rs, "created_at"),
                rs.getString("subscription_status"), rs.getString("lifecycle"), rs.getString("reference"),
                rs.getObject("release_id", UUID.class), input == null ? null : json.parse(input));
    }
}
