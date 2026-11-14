package com.orqix.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Prepared MCP writes waiting for the person's confirmation (S136 section 5). A confirmation belongs to one subject and
 * one workspace; another subject cannot see, confirm or decline it (it does not exist for them). The arguments are
 * kept only while the confirmation is pending; the result is kept so that confirming again returns the same answer.
 */
@Component
public class McpConfirmations {

    private static final Logger LOG = LoggerFactory.getLogger(McpConfirmations.class);

    public enum Status {
        PENDING("pending"), CONFIRMED("confirmed"), DECLINED("declined"), EXPIRED("expired"), FAILED("failed");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        static Status of(String value) {
            for (Status s : values()) {
                if (s.value.equals(value)) {
                    return s;
                }
            }
            throw new IllegalStateException("Unknown confirmation status " + value);
        }
    }

    public record Confirmation(UUID id, UUID workspaceId, UUID subjectId, String tool, String action, String targetId,
            JsonNode arguments, String summary, Status status, JsonNode result, Instant createdAt, Instant expiresAt) {

        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final McpSettings settings;

    public McpConfirmations(JdbcClient jdbc, Json json, Clock clock, McpSettings settings) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.settings = settings;
    }

    public Confirmation create(UUID workspaceId, UUID subjectId, String tool, String action, String targetId,
            JsonNode arguments, String summary) {
        UUID id = Ids.newId();
        Instant now = clock.instant();
        Instant expires = now.plus(settings.confirmationTtl());
        jdbc.sql("""
                INSERT INTO mcp_confirmation (id, workspace_id, subject_id, tool, action, target_id, arguments, summary,
                                              status, created_at, expires_at)
                VALUES (:id, :ws, :subject, :tool, :action, :target, CAST(:arguments AS jsonb), :summary, 'pending',
                        :now, :expires)
                """).param("id", id).param("ws", workspaceId).param("subject", subjectId).param("tool", tool)
                .param("action", action).param("target", targetId).param("arguments", json.write(arguments))
                .param("summary", summary).param("now", Sql.ts(now)).param("expires", Sql.ts(expires)).update();
        return new Confirmation(id, workspaceId, subjectId, tool, action, targetId, arguments, summary, Status.PENDING,
                null, now, expires);
    }

    /** The subject's confirmation, locked for the rest of the transaction; empty for anybody else. */
    public Optional<Confirmation> lock(UUID id, UUID subjectId) {
        return jdbc.sql("""
                SELECT id, workspace_id, subject_id, tool, action, target_id, arguments::text AS arguments, summary, status,
                       result::text AS result, created_at, expires_at
                FROM mcp_confirmation WHERE id = :id AND subject_id = :subject FOR UPDATE
                """).param("id", id).param("subject", subjectId)
                .query((rs, n) -> new Confirmation(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"),
                        Sql.uuid(rs, "subject_id"), rs.getString("tool"), rs.getString("action"),
                        rs.getString("target_id"), parse(rs.getString("arguments")), rs.getString("summary"),
                        Status.of(rs.getString("status")), parse(rs.getString("result")),
                        Sql.instant(rs, "created_at"), Sql.instant(rs, "expires_at")))
                .optional();
    }

    /** Status of the subject's confirmation without a lock (the elicitation stream polls this). */
    public Optional<Status> status(UUID id, UUID subjectId) {
        return jdbc.sql("SELECT status FROM mcp_confirmation WHERE id = :id AND subject_id = :subject")
                .param("id", id).param("subject", subjectId).query(String.class).optional().map(Status::of);
    }

    /** Ends a pending confirmation: the arguments are dropped and the result kept for replays. */
    public void finish(UUID id, Status status, JsonNode result) {
        jdbc.sql("""
                UPDATE mcp_confirmation SET status = :status, result = CAST(:result AS jsonb), arguments = NULL,
                       decided_at = :now
                WHERE id = :id AND status = 'pending'
                """).param("id", id).param("status", status.value()).param("result", result == null ? null : json.write(result))
                .param("now", Sql.ts(clock.instant())).update();
    }

    /** Expires pending confirmations past their time (dropping their arguments) and deletes rows a day after expiry. */
    @Scheduled(fixedDelayString = "${orqix.mcp.sweep-delay:60s}", initialDelayString = "${orqix.mcp.sweep-delay:60s}")
    public void sweep() {
        try {
            sweep(clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("MCP confirmation sweep failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass; returns the number of rows deleted. */
    public int sweep(Instant now) {
        jdbc.sql("""
                UPDATE mcp_confirmation SET status = 'expired', arguments = NULL, decided_at = :now
                WHERE status = 'pending' AND expires_at <= :now
                """).param("now", Sql.ts(now)).update();
        return jdbc.sql("DELETE FROM mcp_confirmation WHERE expires_at < :before")
                .param("before", Sql.ts(now.minus(java.time.Duration.ofDays(1)))).update();
    }

    private JsonNode parse(String text) {
        return text == null ? null : json.parse(text);
    }
}
