package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.integration.AiTaskPort;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The AI reviewer of an approval step (docs/build/S152). When a task of a step with {@code config.aiReviewer} is
 * created, a review job is queued on the AI task queue; the AI task worker runs the guardrail pipeline of the pinned
 * skill (admission, minimization, calibration, autonomy policy). Its outcome is settled here: a confident
 * {@code approve} or {@code reject} within the effective autonomy decides the task as the AI reviewer
 * ({@link TaskService#decideAsAgent}); anything else leaves the task with people and the suggestion attached. A person
 * who decides first ends the job, and their decision is recorded as the human outcome of the AI suggestion.
 */
@Service
public class AiReviews {

    /** What a review job sends: the step's reviewer configuration resolved for one task. */
    public record Spec(String skillKey, int skillVersion, String mode, String autonomy, BigDecimal minConfidence,
            int reviewSamplePercent, Map<String, String> redaction, Map<String, String> inputs) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final RunEvents events;
    private final AiTaskPort ai;
    private final TaskService tasks;
    private final AiTaskJobs jobs;

    public AiReviews(JdbcClient jdbc, Json json, RunEvents events, AiTaskPort ai, @Lazy TaskService tasks,
            @Lazy AiTaskJobs jobs) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.ai = ai;
        this.tasks = tasks;
        this.jobs = jobs;
    }

    /** The reviewer of an approval step as written in the pinned definition, or null. */
    public Spec spec(UUID workspaceId, JsonNode node) {
        JsonNode r = node == null ? null : node.path("config").path("aiReviewer");
        if (r == null || !r.isObject()) {
            return null;
        }
        String key = r.path("skill").path("key").asString();
        int version = r.path("skill").path("version").asInt();
        // The mode of the pinned version (publishing checked it is classify or recommend; a missing version ends the
        // review on unavailable at admission).
        String mode = jdbc.sql("""
                SELECT v.mode FROM ai_skill_version v JOIN ai_skill s ON s.id = v.skill_id
                WHERE s.workspace_id = :ws AND s.key = :key AND v.version = :version
                """).param("ws", workspaceId).param("key", key).param("version", version).query(String.class)
                .optional().filter(m -> m.equals("recommend")).orElse("classify");
        Map<String, String> inputs = new LinkedHashMap<>();
        r.path("inputs").properties().forEach(e -> inputs.put(e.getKey(), e.getValue().asString()));
        Map<String, String> redaction = new LinkedHashMap<>();
        r.path("redaction").properties().forEach(e -> redaction.put(e.getKey(), e.getValue().asString()));
        return new Spec(key, version, mode, r.path("autonomy").asString("suggest"),
                r.has("minConfidence") ? r.path("minConfidence").decimalValue() : new BigDecimal("0.85"),
                r.path("reviewSamplePercent").asInt(10), redaction, inputs);
    }

    /** Queues the review of a new task (the transaction that created it). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(UUID workspaceId, UUID runId, String nodeId, String taskId, int taskVersion, Spec spec,
            ObjectNode input) {
        jobs.publishReview(new AiTaskJobs.Spec(workspaceId, runId, nodeId, spec.skillKey(), spec.skillVersion(), null,
                spec.mode(), spec.autonomy(), spec.minConfidence(), spec.reviewSamplePercent(), spec.redaction(), true,
                input), taskId, taskVersion);
    }

    /**
     * Settles a completed review (the job's completion transaction, request locked): a confident approve or reject
     * decides the task; otherwise the suggestion stays with the task for people.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(UUID workspaceId, UUID runId, String nodeId, String taskId, int taskVersion, UUID decisionId,
            AiTaskPort.Outcome outcome) {
        AiTaskPort.DecisionRecord record = outcome.record();
        String route = outcome.route();
        if (route != null && route.startsWith("label:")) {
            String action = route.substring("label:".length());
            TaskService.AgentDecision decision = new TaskService.AgentDecision(action, rationale(record.output(), action),
                    decisionId, record.skillKey(), record.skillVersion(), record.confidence(), record.threshold(),
                    record.reviewSampled());
            if (tasks.decideAsAgent(workspaceId, taskId, taskVersion, decision)) {
                return;
            }
            if (!tasks.isOpen(workspaceId, taskId)) {
                return;
            }
            suggested(workspaceId, runId, nodeId, taskId, record, "personActed");
            return;
        }
        if (!tasks.isOpen(workspaceId, taskId)) {
            return;
        }
        if ("unavailable".equals(record.status())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            data.put("skill", record.skillKey());
            data.put("reason", record.unavailableReason());
            events.append(workspaceId, runId, "AI_REVIEW_UNAVAILABLE", nodeId, null, data);
            return;
        }
        suggested(workspaceId, runId, nodeId, taskId, record, heldFor(record.status(), record.effectiveAutonomy(),
                record.guardrails()));
    }

    private void suggested(UUID workspaceId, UUID runId, String nodeId, String taskId, AiTaskPort.DecisionRecord record,
            String heldFor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("skill", record.skillKey());
        data.put("recommendation", value(record.output()));
        data.put("confidence", record.confidence());
        data.put("heldFor", heldFor);
        events.append(workspaceId, runId, "AI_REVIEW_SUGGESTED", nodeId, null, data);
    }

    /**
     * A person decided the task (their decision transaction): an open review job ends, and an AI answer becomes
     * {@code accepted} (same action) or {@code overridden} (the other action, with the person's reason).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void personDecided(UUID workspaceId, UUID runId, String taskId, String action, String reason,
            UUID subjectId) {
        jobs.cancelReview(workspaceId, taskId);
        var found = jdbc.sql("""
                SELECT id, output::text AS output FROM ai_decision_record
                WHERE workspace_id = :ws AND task_id = :task AND human_outcome IS NULL AND output IS NOT NULL
                ORDER BY created_at DESC LIMIT 1
                """).param("ws", workspaceId).param("task", taskId)
                .query((rs, n) -> Map.entry(Sql.uuid(rs, "id"), rs.getString("output"))).optional();
        if (found.isEmpty()) {
            return;
        }
        String suggestion = value(json.parse(found.get().getValue()));
        if (suggestion == null) {
            return;
        }
        boolean accepted = suggestion.equals(action);
        ai.recordOutcome(workspaceId, runId, found.get().getKey(), new AiTaskPort.HumanOutcome(
                accepted ? "accepted" : "overridden", accepted ? null : action,
                accepted || reason == null || reason.isBlank() ? null : reason, subjectId));
    }

    /** The AI review of a task for the task view; {@code withValues} adds the recommendation, rationale and reasons. */
    @Transactional(readOnly = true)
    public Optional<RuntimeDtos.AiReview> view(UUID workspaceId, String taskId, TaskQueries.DecisionRow decided,
            boolean withValues) {
        var job = jdbc.sql("""
                SELECT status, skill_key, skill_version, autonomy FROM ai_task_job
                WHERE workspace_id = :ws AND task_id = :task AND purpose = 'review' ORDER BY created_at DESC LIMIT 1
                """).param("ws", workspaceId).param("task", taskId)
                .query((rs, n) -> new String[] {rs.getString("status"), rs.getString("skill_key"),
                        Integer.toString(rs.getInt("skill_version")), rs.getString("autonomy")}).optional();
        if (job.isEmpty()) {
            return Optional.empty();
        }
        String[] j = job.get();
        var record = jdbc.sql("""
                SELECT id, status, output::text AS output, confidence, threshold, autonomy, effective_autonomy,
                       guardrails::text AS guardrails, unavailable_reason, human_outcome
                FROM ai_decision_record WHERE workspace_id = :ws AND task_id = :task ORDER BY created_at DESC LIMIT 1
                """).param("ws", workspaceId).param("task", taskId).query((rs, n) -> {
            JsonNode output = rs.getString("output") == null ? null : json.parse(rs.getString("output"));
            String status = rs.getString("status");
            boolean byAi = decided != null && decided.byAi();
            String effective = rs.getString("effective_autonomy");
            String recordStatus = "unavailable".equals(status) ? "unavailable" : byAi ? "decided" : "suggested";
            String held = byAi || "unavailable".equals(status) ? null
                    : "decided".equals(status) ? "personActed"
                    : heldFor(status, effective, json.parse(rs.getString("guardrails")));
            List<String> reasons = new ArrayList<>();
            if (withValues && output != null) {
                output.path("reasons").forEach(r -> reasons.add(r.asString()));
            }
            return new RuntimeDtos.AiReview(recordStatus, j[1], Integer.parseInt(j[2]), rs.getString("autonomy"),
                    effective, withValues ? value(output) : null, rs.getBigDecimal("confidence"),
                    rs.getBigDecimal("threshold"), withValues && output != null && output.has("rationale")
                    ? output.path("rationale").asString(null) : null, reasons, byAi, held,
                    rs.getString("unavailable_reason"), Sql.uuid(rs, "id"), rs.getString("human_outcome"));
        }).optional();
        if (record.isPresent()) {
            return record;
        }
        String status = switch (j[0]) {
            case "available", "running" -> "pending";
            case "cancelled" -> "cancelled";
            default -> "unavailable";
        };
        return Optional.of(new RuntimeDtos.AiReview(status, j[1], Integer.parseInt(j[2]), j[3], null, null, null, null,
                null, List.of(), false, null, null, null, null));
    }

    /** Why an answer did not decide the task: the record's status and the policy's downgrades. */
    static String heldFor(String status, String effectiveAutonomy, JsonNode guardrails) {
        if ("lowConfidence".equals(status)) {
            return "lowConfidence";
        }
        List<String> downgrades = new ArrayList<>();
        if (guardrails != null) {
            guardrails.path("downgrades").forEach(d -> downgrades.add(d.asString()));
        }
        for (String reason : List.of("notADecision", "adverseRegulated", "notLowRisk", "drift", "policy",
                "notPromoted")) {
            if (downgrades.contains(reason)) {
                return reason;
            }
        }
        return "suggest".equals(effectiveAutonomy) ? "suggest" : null;
    }

    /** The answer of a review record: the label (classify) or the recommendation (recommend). */
    static String value(JsonNode output) {
        if (output == null) {
            return null;
        }
        if (output.hasNonNull("label")) {
            return output.path("label").asString();
        }
        return output.hasNonNull("recommendation") ? output.path("recommendation").asString() : null;
    }

    /** The reason of an AI decision: the rationale (recommend) or the reasons (classify), never blank. */
    static String rationale(JsonNode output, String action) {
        String text = null;
        if (output != null && output.hasNonNull("rationale")) {
            text = output.path("rationale").asString();
        } else if (output != null && output.path("reasons").isArray() && !output.path("reasons").isEmpty()) {
            List<String> reasons = new ArrayList<>();
            output.path("reasons").forEach(r -> reasons.add(r.asString()));
            text = String.join("; ", reasons);
        }
        if (text == null || text.isBlank()) {
            text = "The AI reviewer recommended to " + action + " this request.";
        }
        text = text.strip();
        return text.length() > 1000 ? text.substring(0, 999) + "…" : text;
    }
}
