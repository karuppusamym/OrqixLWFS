package com.orqix.ai.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.ai.api.AiDtos;
import com.orqix.shared.Ids;
import com.orqix.shared.web.Sql;

/**
 * {@code ai_generation} metering rows (V8) and the monthly budget (S10 sections 1 and 3): spend is the sum of
 * provider-reported {@code usage.cost} in the current UTC calendar month, across the deployment (one provider key).
 * Rows hold metering facts only, never descriptions, prompts or model output.
 */
@Component
public class AiLedger {

    /** Stored outcome values (CHECK constraint in V8). */
    public enum Outcome {
        SUCCESS("success"), INVALID_OUTPUT("invalidOutput"), NOT_A_WORKFLOW("notAWorkflow"),
        PROVIDER_ERROR("providerError"), CREDENTIALS_REJECTED("credentialsRejected"),
        MODEL_NOT_ALLOWED("modelNotAllowed");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** One generation that reached the provider. */
    public record Entry(UUID workspaceId, UUID subjectId, String kind, String profile, String model,
            String requestedModel, String promptVersion, int attempts, boolean escalated, Long inputTokens,
            Long outputTokens, BigDecimal costUsd, long durationMs, Outcome outcome, UUID workflowId, Integer revision,
            Integer releaseNumber) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AiLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Inserts in the caller's transaction (the success path commits it with the created workflow). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Entry entry) {
        jdbc.sql("""
                INSERT INTO ai_generation (id, workspace_id, subject_id, kind, profile, model, requested_model,
                    prompt_version, attempts, escalated, input_tokens, output_tokens, cost_usd, duration_ms, outcome,
                    workflow_id, revision, release_number, created_at)
                VALUES (:id, :ws, :subject, :kind, :profile, :model, :requested, :prompt, :attempts, :escalated,
                    :input, :output, :cost, :duration, :outcome, :workflow, :revision, :release, :at)
                """)
                .param("id", Ids.newId()).param("ws", entry.workspaceId()).param("subject", entry.subjectId())
                .param("kind", entry.kind()).param("profile", entry.profile())
                .param("model", limit(entry.model() == null ? entry.requestedModel() : entry.model()))
                .param("requested", limit(entry.requestedModel())).param("prompt", entry.promptVersion())
                .param("attempts", entry.attempts()).param("escalated", entry.escalated())
                .param("input", entry.inputTokens()).param("output", entry.outputTokens())
                .param("cost", entry.costUsd()).param("duration", entry.durationMs())
                .param("outcome", entry.outcome().value()).param("workflow", entry.workflowId())
                .param("revision", entry.revision()).param("release", entry.releaseNumber())
                .param("at", Sql.ts(clock.instant()))
                .update();
    }

    /** Generation metadata of the successful draft generation that created a workflow (idempotent replays). */
    @Transactional(readOnly = true)
    public Optional<AiDtos.Generation> draftGeneration(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("""
                SELECT profile, model, prompt_version, attempts, escalated, input_tokens, output_tokens, cost_usd,
                       duration_ms
                FROM ai_generation
                WHERE workspace_id = :ws AND workflow_id = :workflow AND kind = 'draft' AND outcome = 'success'
                ORDER BY created_at LIMIT 1
                """)
                .param("ws", workspaceId).param("workflow", workflowId)
                .query((rs, n) -> new AiDtos.Generation(rs.getString("profile"), rs.getString("model"),
                        rs.getString("prompt_version"), rs.getInt("attempts"), rs.getBoolean("escalated"),
                        (Long) rs.getObject("input_tokens", Long.class), (Long) rs.getObject("output_tokens", Long.class),
                        AiService.usd(rs.getBigDecimal("cost_usd")), rs.getLong("duration_ms")))
                .optional();
    }

    /** Provider-reported spend of the current UTC calendar month (all workspaces). */
    @Transactional(readOnly = true)
    public BigDecimal monthToDate() {
        BigDecimal spent = jdbc.sql("SELECT COALESCE(SUM(cost_usd), 0) FROM ai_generation WHERE created_at >= :start")
                .param("start", Sql.ts(monthStart())).query(BigDecimal.class).single();
        return spent == null ? BigDecimal.ZERO : spent;
    }

    /** Start of the current month (UTC). */
    public Instant monthStart() {
        ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        return now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Seconds until the next month starts (Retry-After of AI_BUDGET_EXCEEDED). */
    public long secondsUntilNextMonth() {
        Instant next = monthStart().atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        return Math.max(1, next.getEpochSecond() - clock.instant().getEpochSecond());
    }

    private static String limit(String model) {
        String value = model == null || model.isBlank() ? "unknown" : model;
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
