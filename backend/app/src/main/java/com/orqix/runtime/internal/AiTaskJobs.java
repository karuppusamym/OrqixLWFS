package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.integration.AiTaskPort;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI task jobs (CG-A05, docs/build/S134 section 5). A step queues one job with its input sealed; a worker claims it
 * with a lease and a fence, renews the lease between turns while the pipeline runs (up to the 10-minute deadline), and
 * completes it: decision record, step outputs, route variable, removal of the step and the engine trigger in one
 * transaction. A lease that expires (a pod died) returns the job to the queue, and the next worker continues from the
 * sealed turn state; after {@code max-attempts} claims or past the deadline the job ends on {@code aiUnavailable}.
 */
@Service
public class AiTaskJobs {

    /** Process variable prefix carrying the route an AI task took (read by {@code orqixRoute.ai}). */
    public static final String ROUTE_PREFIX = "orqix_ai_route_";

    /** What the step queues. */
    public record Spec(UUID workspaceId, UUID runId, String nodeId, String skillKey, int skillVersion,
            String contentHash, String mode, String autonomy, BigDecimal minConfidence, int reviewSamplePercent,
            Map<String, String> redaction, boolean hasNeedsReview, ObjectNode input) {
    }

    /** A claimed job. */
    public record Claimed(UUID id, UUID workspaceId, UUID runId, String nodeId, int occurrence, int attempt, int fence,
            Instant deadline, String sealed) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final AiTaskPort ai;
    private final AiTaskQueueSettings settings;

    private PlatformMetrics platformMetrics;

    /** CG-P06 (S150): feature counters. */
    @org.springframework.beans.factory.annotation.Autowired
    void setPlatformMetrics(PlatformMetrics platformMetrics) {
        this.platformMetrics = platformMetrics;
    }

    public AiTaskJobs(JdbcClient jdbc, Json json, Clock clock, RunStore runs, RunEvents events, ReleaseQueries releases,
            EngineCommands engine, AiTaskPort ai, AiTaskQueueSettings settings) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.runs = runs;
        this.events = events;
        this.releases = releases;
        this.engine = engine;
        this.ai = ai;
        this.settings = settings;
    }

    public static String routeVariable(String nodeId) {
        return ROUTE_PREFIX + nodeId;
    }

    // ---------------------------------------------------------------- queue (engine transaction)

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Spec spec) {
        boolean open = jdbc.sql("""
                SELECT count(*) FROM ai_task_job WHERE run_id = :run AND node_id = :node AND status IN ('available', 'running')
                """).param("run", spec.runId()).param("node", spec.nodeId()).query(Integer.class).single() > 0;
        if (open) {
            return;
        }
        int occurrence = jdbc.sql("SELECT count(*) + 1 FROM ai_task_job WHERE run_id = :run AND node_id = :node")
                .param("run", spec.runId()).param("node", spec.nodeId()).query(Integer.class).single();
        UUID id = Ids.newId();
        Instant now = clock.instant();
        ObjectNode payload = json.object();
        payload.put("contentHash", spec.contentHash()).put("mode", spec.mode()).put("autonomy", spec.autonomy())
                .put("minConfidence", spec.minConfidence()).put("reviewSamplePercent", spec.reviewSamplePercent())
                .put("hasNeedsReview", spec.hasNeedsReview());
        ObjectNode redaction = payload.putObject("redaction");
        spec.redaction().forEach(redaction::put);
        payload.set("input", spec.input());
        String sealed = ai.seal(spec.workspaceId(), id, payload);
        jdbc.sql("""
                INSERT INTO ai_task_job (id, workspace_id, run_id, node_id, occurrence, skill_key, skill_version, mode,
                    autonomy, status, deadline_at, input_key_id, input_cipher, available_at, created_at)
                VALUES (:id, :ws, :run, :node, :occ, :skill, :version, :mode, :autonomy, 'available', :deadline, :key,
                        :cipher, :now, :now)
                """).param("id", id).param("mode", spec.mode()).param("autonomy", spec.autonomy()).param("ws", spec.workspaceId()).param("run", spec.runId()).param("node", spec.nodeId())
                .param("occ", occurrence).param("skill", spec.skillKey()).param("version", spec.skillVersion())
                .param("deadline", Sql.ts(now.plus(settings.maxTimeout())))
                .param("key", sealed.substring(0, sealed.indexOf(':'))).param("cipher", sealed)
                .param("now", Sql.ts(now)).update();
        RunStore.RunRow run = runs.find(spec.workspaceId(), spec.runId()).orElseThrow();
        String label = releases.require(spec.workspaceId(), run.releaseId()).nodeLabel(spec.nodeId());
        runs.mutate(spec.workspaceId(), spec.runId(), m -> {
            ObjectNode step = json.object();
            step.put("nodeId", spec.nodeId()).put("label", label == null ? spec.nodeId() : label).put("kind", "ai")
                    .put("since", now.toString());
            step.putNull("candidateGroup").putNull("assigneeId").putNull("dueAt");
            m.addStep(step);
            m.settle();
        });
        events.append(spec.workspaceId(), spec.runId(), "AI_TASK_QUEUED", spec.nodeId(), null,
                Map.of("skill", spec.skillKey(), "skillVersion", spec.skillVersion(), "autonomy", spec.autonomy()));
    }

    /** The engine cancelled the step (section, error, request ended): the job ends and its turn state goes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOpen(UUID workspaceId, UUID runId, String nodeId) {
        List<UUID> ids = jdbc.sql("""
                UPDATE ai_task_job SET status = 'cancelled', lease_owner = NULL, lease_expires_at = NULL,
                       input_cipher = NULL, last_error = 'The step was stopped before AI finished.', ended_at = :now
                WHERE workspace_id = :ws AND run_id = :run AND node_id = :node AND status IN ('available', 'running')
                RETURNING id
                """).param("now", Sql.ts(clock.instant())).param("ws", workspaceId).param("run", runId)
                .param("node", nodeId).query(UUID.class).list();
        ids.forEach(id -> ai.endTurns(workspaceId, id));
        // CG-A06: an open verification of the step ends with it.
        jdbc.sql("""
                UPDATE ai_document_verification SET status = 'cancelled'
                WHERE workspace_id = :ws AND run_id = :run AND node_id = :node AND status = 'open'
                """).param("ws", workspaceId).param("run", runId).param("node", nodeId).update();
    }

    // ---------------------------------------------------------------- worker protocol

    /** Claims the oldest available job with a fresh lease and fence. */
    @Transactional
    public Optional<Claimed> claim(String owner) {
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE ai_task_job SET status = 'running', lease_owner = :owner, lease_expires_at = :lease,
                       fence = fence + 1, attempt = attempt + 1
                WHERE id = (SELECT id FROM ai_task_job WHERE status = 'available' AND available_at <= :now
                            ORDER BY available_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, workspace_id, run_id, node_id, occurrence, attempt, fence, deadline_at, input_cipher
                """).param("owner", owner).param("lease", Sql.ts(now.plusSeconds(settings.leaseSeconds())))
                .param("now", Sql.ts(now)).query((rs, n) -> new Claimed(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"),
                        Sql.uuid(rs, "run_id"), rs.getString("node_id"), rs.getInt("occurrence"), rs.getInt("attempt"),
                        rs.getInt("fence"), Sql.instant(rs, "deadline_at"), rs.getString("input_cipher"))).optional();
    }

    /** @return false when the lease is lost (fence moved, job ended) */
    @Transactional
    public boolean renew(Claimed job) {
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE ai_task_job SET lease_expires_at = :lease
                WHERE id = :id AND fence = :fence AND status = 'running'
                """).param("lease", Sql.ts(now.plusSeconds(settings.leaseSeconds()))).param("id", job.id())
                .param("fence", job.fence()).update() == 1;
    }

    /** The task request of a claimed job (sealed input opened). */
    public AiTaskPort.TaskRequest request(Claimed job) {
        ObjectNode payload = ai.open(job.workspaceId(), job.id(), job.sealed());
        RunStore.RunRow run = runs.find(job.workspaceId(), job.runId()).orElseThrow();
        var row = jdbc.sql("SELECT skill_key, skill_version FROM ai_task_job WHERE id = :id").param("id", job.id())
                .query((rs, n) -> Map.entry(rs.getString("skill_key"), rs.getInt("skill_version"))).single();
        Map<String, String> redaction = new LinkedHashMap<>();
        payload.path("redaction").properties().forEach(e -> redaction.put(e.getKey(), e.getValue().asString()));
        JsonNode input = payload.path("input");
        return new AiTaskPort.TaskRequest(job.workspaceId(), job.runId(), run.workflowId(), run.initiator(), job.nodeId(),
                job.occurrence(), job.id(), row.getKey(), row.getValue(), payload.path("contentHash").asString(null),
                payload.path("mode").asString(), payload.path("autonomy").asString(),
                payload.path("minConfidence").decimalValue(), payload.path("reviewSamplePercent").asInt(10), redaction,
                payload.path("hasNeedsReview").asBoolean(false),
                input.isObject() ? (ObjectNode) input : json.object());
    }

    /** A request without the sealed input (it could not be opened): enough to end the task on aiUnavailable. */
    public AiTaskPort.TaskRequest requestWithoutInput(Claimed job) {
        RunStore.RunRow run = runs.find(job.workspaceId(), job.runId()).orElseThrow();
        return jdbc.sql("SELECT skill_key, skill_version, mode, autonomy FROM ai_task_job WHERE id = :id")
                .param("id", job.id()).query((rs, n) -> new AiTaskPort.TaskRequest(job.workspaceId(), job.runId(),
                        run.workflowId(), run.initiator(), job.nodeId(), job.occurrence(), job.id(),
                        rs.getString("skill_key"), rs.getInt("skill_version"), null, rs.getString("mode"),
                        rs.getString("autonomy"), new BigDecimal("0.8"), 10, Map.of(), false, json.object())).single();
    }

    /**
     * Completes a job with the pipeline's outcome: decision record, outputs and route, engine trigger, in one
     * transaction. A job whose fence moved (another worker took it) changes nothing.
     *
     * @return false when this worker no longer holds the job or the request ended
     * @throws EngineCommands.EngineConflictException when the engine is busy (the caller retries)
     */
    @Transactional
    public boolean complete(Claimed job, AiTaskPort.Outcome outcome) {
        boolean held = jdbc.sql("""
                SELECT count(*) FROM ai_task_job WHERE id = :id AND fence = :fence AND status = 'running'
                """).param("id", job.id()).param("fence", job.fence()).query(Integer.class).single() == 1;
        if (!held) {
            return false;
        }
        jdbc.sql("SELECT id FROM ai_task_job WHERE id = :id FOR UPDATE").param("id", job.id()).query(UUID.class).single();
        RunStore.RunRow run = runs.lock(job.workspaceId(), job.runId()).orElseThrow();
        Instant now = clock.instant();
        if (!run.isActive()) {
            end(job, "cancelled", "The request ended before AI finished.", now);
            return false;
        }
        UUID decisionId = ai.record(outcome.record());
        end(job, "completed", null, now);
        if (outcome.verification() != null) {
            // CG-A06: the extraction waits for a person to confirm or correct its fields; the engine is triggered
            // when the verification is submitted (AiVerifications).
            openVerification(job, decisionId, outcome, now);
            return true;
        }
        runs.mutate(job.workspaceId(), job.runId(), m -> {
            m.removeStep(job.nodeId());
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("route", outcome.route());
        data.put("status", outcome.outputs().path("status").asString());
        data.put("attempt", job.attempt());
        if (outcome.outputs().has("confidence")) {
            data.put("confidence", outcome.outputs().path("confidence").decimalValue());
        }
        events.append(job.workspaceId(), job.runId(), "AI_TASK_COMPLETED", job.nodeId(), null, data);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(ProcessData.stepVariable(job.nodeId()), json.write(outcome.outputs()));
        variables.put(routeVariable(job.nodeId()), outcome.route());
        engine.resumeChild(run.engineProcessInstanceId(), job.nodeId(), variables);
        return true;
    }

    private void openVerification(Claimed job, UUID decisionId, AiTaskPort.Outcome outcome, Instant now) {
        ObjectNode payload = outcome.verification();
        var skill = jdbc.sql("SELECT skill_key, skill_version FROM ai_task_job WHERE id = :id").param("id", job.id())
                .query((rs, n) -> Map.entry(rs.getString("skill_key"), rs.getInt("skill_version"))).single();
        UUID id = com.orqix.shared.Ids.newId();
        jdbc.sql("""
                INSERT INTO ai_document_verification (id, workspace_id, run_id, node_id, occurrence, job_id, decision_id,
                    attachment_id, skill_key, skill_version, document_type, provider, threshold, payload, ai_outputs,
                    status, created_at)
                VALUES (:id, :ws, :run, :node, :occ, :job, :decision, :attachment, :skill, :version, :type, :provider,
                        :threshold, CAST(:payload AS jsonb), CAST(:outputs AS jsonb), 'open', :now)
                """).param("id", id).param("ws", job.workspaceId()).param("run", job.runId()).param("node", job.nodeId())
                .param("occ", job.occurrence()).param("job", job.id()).param("decision", decisionId)
                .param("attachment", UUID.fromString(payload.path("attachmentId").asString()))
                .param("skill", skill.getKey()).param("version", skill.getValue())
                .param("type", payload.path("documentType").asString()).param("provider", payload.path("provider").asString())
                .param("threshold", payload.path("threshold").decimalValue()).param("payload", json.write(payload))
                .param("outputs", json.write(outcome.outputs())).param("now", Sql.ts(now)).update();
        RunStore.RunRow run = runs.find(job.workspaceId(), job.runId()).orElseThrow();
        String label = releases.require(job.workspaceId(), run.releaseId()).nodeLabel(job.nodeId());
        runs.mutate(job.workspaceId(), job.runId(), m -> {
            m.removeStep(job.nodeId());
            ObjectNode step = json.object();
            step.put("nodeId", job.nodeId()).put("label", label == null ? job.nodeId() : label).put("kind", "verify")
                    .put("since", now.toString()).put("verificationId", id.toString());
            step.putNull("candidateGroup").putNull("assigneeId").putNull("dueAt");
            m.addStep(step);
            m.settle();
        });
        int attention = 0;
        for (JsonNode f : payload.path("fields")) {
            attention += f.path("needsAttention").asBoolean(false) ? 1 : 0;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("route", outcome.route());
        data.put("status", outcome.outputs().path("status").asString());
        data.put("attempt", job.attempt());
        if (outcome.outputs().has("confidence")) {
            data.put("confidence", outcome.outputs().path("confidence").decimalValue());
        }
        events.append(job.workspaceId(), job.runId(), "AI_TASK_COMPLETED", job.nodeId(), null, data);
        events.append(job.workspaceId(), job.runId(), "AI_VERIFICATION_OPENED", job.nodeId(), null,
                Map.of("verificationId", id.toString(), "fields", payload.path("fields").size(), "needsAttention",
                        attention));
    }

    private void end(Claimed job, String status, String error, Instant now) {
        jdbc.sql("""
                UPDATE ai_task_job SET status = :status, lease_owner = NULL, lease_expires_at = NULL, input_cipher = NULL,
                       last_error = :error, ended_at = :now
                WHERE id = :id
                """).param("status", status).param("error", error).param("now", Sql.ts(now)).param("id", job.id())
                .update();
        ai.endTurns(job.workspaceId(), job.id());
        if (platformMetrics != null) {
            platformMetrics.aiTaskEnded(status);
        }
    }

    /**
     * Expired leases: back to the queue (the next worker resumes from the turn state), or, after the last attempt or
     * past the deadline, a fresh claim that the worker completes as unavailable.
     *
     * @return jobs whose time is up, claimed by {@code owner} for completion on aiUnavailable
     */
    @Transactional
    public List<Claimed> recoverExpired(String owner) {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE ai_task_job SET status = 'available', lease_owner = NULL, lease_expires_at = NULL, available_at = :now,
                       last_error = 'The AI worker stopped; the task continues from its last completed turn.'
                WHERE status = 'running' AND lease_expires_at < :now AND attempt < :max AND deadline_at > :now
                """).param("now", Sql.ts(now)).param("max", settings.maxAttempts()).update();
        return jdbc.sql("""
                UPDATE ai_task_job SET lease_owner = :owner, lease_expires_at = :lease, fence = fence + 1
                WHERE status IN ('running', 'available') AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                  AND (attempt >= :max OR deadline_at <= :now)
                RETURNING id, workspace_id, run_id, node_id, occurrence, attempt, fence, deadline_at, input_cipher
                """).param("owner", owner).param("lease", Sql.ts(now.plusSeconds(settings.leaseSeconds())))
                .param("now", Sql.ts(now)).param("max", settings.maxAttempts())
                .query((rs, n) -> new Claimed(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                        rs.getString("node_id"), rs.getInt("occurrence"), rs.getInt("attempt"), rs.getInt("fence"),
                        Sql.instant(rs, "deadline_at"), rs.getString("input_cipher"))).list().stream()
                .map(c -> {
                    jdbc.sql("UPDATE ai_task_job SET status = 'running' WHERE id = :id").param("id", c.id()).update();
                    return c;
                }).toList();
    }
}
