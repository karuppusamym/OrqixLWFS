package com.orqix.runtime.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.web.Sql;

/**
 * Signed, expiring action links of CG-T01 (docs/build/S141). A token is {@code base64url(id, expiry) "."
 * base64url(HMAC-SHA256(key, id | expiry | workspace | task | person))}; the key is derived from the deployment's
 * cursor secret with its own label, so it never equals another key. The row ({@code task_action_link}) binds the id to
 * one task and one person, so a token for another task or person fails the signature, and it can be revoked. The token
 * itself is never stored or logged.
 */
@Component
public class TaskActionLinks {

    /** The facts a valid token stands for. */
    public record Link(UUID id, UUID workspaceId, String taskId, UUID subjectId, String channel, Instant expiresAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final byte[] key;

    public TaskActionLinks(JdbcClient jdbc, Clock clock, OrqixProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.key = ("orqix-task-action-link|" + properties.pagination().cursorSecret()).getBytes(StandardCharsets.UTF_8);
    }

    /** Issues a link for one person and task; returns the token. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Issued issue(UUID workspaceId, String taskId, UUID subjectId, String channel, Duration validity) {
        UUID id = Ids.newId();
        Instant now = clock.instant();
        Instant expires = now.plus(validity).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.sql("""
                INSERT INTO task_action_link (id, workspace_id, task_id, subject_id, channel, issued_at, expires_at)
                VALUES (:id, :ws, :task, :subject, :channel, :now, :expires)
                """).param("id", id).param("ws", workspaceId).param("task", taskId).param("subject", subjectId)
                .param("channel", channel).param("now", Sql.ts(now)).param("expires", Sql.ts(expires)).update();
        return new Issued(id, token(id, expires.getEpochSecond(), workspaceId, taskId, subjectId), expires);
    }

    public record Issued(UUID id, String token, Instant expiresAt) {
    }

    /**
     * @return the link a token stands for, when the signature matches its row, it has not expired and was not revoked;
     *         otherwise empty (the caller answers 404 whatever the reason)
     */
    @Transactional(readOnly = true)
    public Optional<Link> verify(String token) {
        if (token == null || token.length() > 200) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        ByteBuffer head;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token.substring(0, dot));
            if (raw.length != 24) {
                return Optional.empty();
            }
            head = ByteBuffer.wrap(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        UUID id = new UUID(head.getLong(), head.getLong());
        long expiry = head.getLong();
        Link link = jdbc.sql("""
                SELECT id, workspace_id, task_id, subject_id, channel, expires_at FROM task_action_link
                WHERE id = :id AND revoked_at IS NULL
                """).param("id", id)
                .query((rs, n) -> new Link(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("task_id"),
                        Sql.uuid(rs, "subject_id"), rs.getString("channel"), Sql.instant(rs, "expires_at")))
                .optional().orElse(null);
        if (link == null || link.expiresAt().getEpochSecond() != expiry) {
            return Optional.empty();
        }
        String expected = token(id, expiry, link.workspaceId(), link.taskId(), link.subjectId());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(link.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(link);
    }

    /** Counts a use of the link (opening the confirmation or acting). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void used(UUID id) {
        jdbc.sql("UPDATE task_action_link SET used_count = used_count + 1, last_used_at = :now WHERE id = :id")
                .param("now", Sql.ts(clock.instant())).param("id", id).update();
    }

    /** Revokes every link of a task (it ended); returns how many. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeForTask(UUID workspaceId, String taskId) {
        return jdbc.sql("""
                UPDATE task_action_link SET revoked_at = :now
                WHERE workspace_id = :ws AND task_id = :task AND revoked_at IS NULL
                """).param("now", Sql.ts(clock.instant())).param("ws", workspaceId).param("task", taskId).update();
    }

    private String token(UUID id, long expiry, UUID workspaceId, String taskId, UUID subjectId) {
        ByteBuffer head = ByteBuffer.allocate(24);
        head.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putLong(expiry);
        String headText = Base64.getUrlEncoder().withoutPadding().encodeToString(head.array());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal((headText + "|" + workspaceId + "|" + taskId + "|" + subjectId)
                    .getBytes(StandardCharsets.UTF_8));
            return headText + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
