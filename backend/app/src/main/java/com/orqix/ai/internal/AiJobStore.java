package com.orqix.ai.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Persistent state of async draft generations ({@code ai_generation_job}, V11). Every pod reads and updates the same
 * rows, so a poll can be answered by any pod. Only state, review notes and the Problem body of a failure are stored,
 * never the description, prompt or raw model output.
 */
@Component
public class AiJobStore {

    /** @param startedAt when the request was accepted (API field {@code startedAt}) */
    public record Job(UUID id, UUID workspaceId, UUID subjectId, String status, String profile, String correlationId,
            Instant startedAt, Instant finishedAt, Instant deadlineAt, UUID workflowId, JsonNode review,
            JsonNode problem) {

        public boolean terminal() {
            return "succeeded".equals(status) || "failed".equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final java.time.Duration retention;

    public AiJobStore(JdbcClient jdbc, Json json, Clock clock, OrqixProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.retention = properties.idempotency().retention();
    }

    /** Serialize a person's admission checks across pods and concurrent Idempotency-Keys. */
    public void lockSubject(UUID subjectId) {
        // Idempotency inserts already hold FK KEY SHARE locks; avoid an incompatible lock upgrade.
        jdbc.sql("SELECT id FROM subject WHERE id = :id FOR NO KEY UPDATE").param("id", subjectId)
                .query(UUID.class).single();
    }

    /** Terminal jobs use the idempotency retention window; live replay records always keep their job. */
    public int purgeExpired() {
        return jdbc.sql("""
                DELETE FROM ai_generation_job j
                WHERE j.status IN ('succeeded', 'failed') AND j.finished_at < :cutoff
                  AND NOT EXISTS (
                    SELECT 1 FROM idempotency_record i WHERE i.workspace_id = j.workspace_id
                      AND i.resource_type = 'aiGenerationJob' AND i.resource_id = j.id::text AND i.expires_at > :now)
                """).param("cutoff", Sql.ts(clock.instant().minus(retention)))
                .param("now", Sql.ts(clock.instant())).update();
    }

    /** Inserts a queued job (in the caller's transaction, together with the idempotency completion). */
    public void create(UUID id, UUID workspaceId, UUID subjectId, String profile, String correlationId,
            Instant deadlineAt) {
        jdbc.sql("""
                INSERT INTO ai_generation_job (id, workspace_id, subject_id, status, profile, correlation_id, created_at,
                                               deadline_at)
                VALUES (:id, :ws, :subject, 'queued', :profile, :correlation, :now, :deadline)
                """)
                .param("id", id).param("ws", workspaceId).param("subject", subjectId).param("profile", profile)
                .param("correlation", correlationId).param("now", Sql.ts(clock.instant()))
                .param("deadline", Sql.ts(deadlineAt)).update();
    }

    public Optional<Job> find(UUID workspaceId, UUID id) {
        return jdbc.sql(SELECT + " WHERE workspace_id = :ws AND id = :id").param("ws", workspaceId).param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    /** Locks the row and returns it (final persistence step of a running job). */
    public Optional<Job> lock(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    /** Queued or running jobs of one person in one workspace. */
    public long countActive(UUID workspaceId, UUID subjectId) {
        return jdbc.sql("""
                SELECT count(*) FROM ai_generation_job
                WHERE workspace_id = :ws AND subject_id = :subject AND status IN ('queued', 'running')
                """).param("ws", workspaceId).param("subject", subjectId).query(Long.class).single();
    }

    /** queued to running; false when the job already ended (for example reaped while it waited in the queue). */
    public boolean markRunning(UUID id, Instant deadlineAt) {
        return jdbc.sql("""
                UPDATE ai_generation_job SET status = 'running', started_at = :now, deadline_at = :deadline
                WHERE id = :id AND status = 'queued'
                """).param("id", id).param("now", Sql.ts(clock.instant())).param("deadline", Sql.ts(deadlineAt))
                .update() == 1;
    }

    public boolean markSucceeded(UUID id, UUID workflowId, JsonNode review) {
        return jdbc.sql("""
                UPDATE ai_generation_job SET status = 'succeeded', finished_at = :now, workflow_id = :workflow,
                       review = CAST(:review AS jsonb)
                WHERE id = :id AND status IN ('queued', 'running')
                """).param("id", id).param("now", Sql.ts(clock.instant())).param("workflow", workflowId)
                .param("review", json.write(review)).update() == 1;
    }

    public boolean markFailed(UUID id, JsonNode problem) {
        return jdbc.sql("""
                UPDATE ai_generation_job SET status = 'failed', finished_at = :now, problem = CAST(:problem AS jsonb)
                WHERE id = :id AND status IN ('queued', 'running')
                """).param("id", id).param("now", Sql.ts(clock.instant())).param("problem", json.write(problem))
                .update() == 1;
    }

    /**
     * Fails jobs that outlived their deadline (the pod running them died or was restarted): they would otherwise
     * poll as "running" forever. Idempotent and safe to run on every pod.
     */
    public int expireOrphans() {
        return jdbc.sql("""
                UPDATE ai_generation_job SET status = 'failed', finished_at = :now,
                       problem = jsonb_build_object(
                           'type', 'https://orqix.example/problems/ai-provider-unavailable',
                           'title', 'The AI provider is unavailable; nothing was created',
                           'status', 503, 'code', 'AI_PROVIDER_UNAVAILABLE',
                           'detail', 'The generation was interrupted before it finished (the service restarted or the '
                                     || 'time limit passed). Nothing was created; try again.',
                           'correlationId', correlation_id)
                WHERE status IN ('queued', 'running') AND deadline_at < :now
                """).param("now", Sql.ts(clock.instant())).update();
    }

    private static final String SELECT = """
            SELECT id, workspace_id, subject_id, status, profile, correlation_id, created_at, finished_at, deadline_at,
                   workflow_id, review::text AS review, problem::text AS problem
            FROM ai_generation_job
            """;

    private Job map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String review = rs.getString("review");
        String problem = rs.getString("problem");
        return new Job(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "subject_id"),
                rs.getString("status"), rs.getString("profile"), rs.getString("correlation_id"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "finished_at"),
                Sql.instant(rs, "deadline_at"), Sql.uuid(rs, "workflow_id"), review == null ? null : json.parse(review),
                problem == null ? null : json.parse(problem));
    }
}
