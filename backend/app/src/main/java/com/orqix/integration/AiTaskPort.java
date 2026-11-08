package com.orqix.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orqix.compiler.CompileContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the catalog and runtime modules need from the AI module for AI tasks (CG-A01/A03/A05, docs/build/S134). The
 * integration module owns the port because the allowed dependencies point from ai to integration (as for
 * {@link AgentModelPort}); the AI module implements it. Nothing here returns prompts or raw model output.
 */
public interface AiTaskPort {

    /** Compile facts about the workspace's AI skills: versions, approval, contract, publish gate, promotion. */
    Map<String, CompileContext.AiSkillInfo> skillFacts(UUID workspaceId);

    /**
     * Runs the guardrail pipeline of one AI task: admission, minimization, prompt, provider calls (samples and one
     * repair), validation, consistency, calibration and routing. Never throws for provider, policy or validation
     * problems: those end on the {@code aiUnavailable} route. Long-running; called outside any transaction. Completed
     * turns are kept as encrypted turn state, so a retry after a crash continues from the last completed turn.
     *
     * @param lease renewed by the pipeline between turns; when it reports the lease lost the pipeline stops
     */
    Outcome run(TaskRequest request, Lease lease);

    /**
     * The outcome of a task that cannot run any more (deadline reached, too many attempts, unreadable input): the
     * {@code aiUnavailable} route (shadow mode: {@code next}) with a decision record that says why.
     */
    Outcome giveUp(TaskRequest request, String reason);

    /** Writes the decision record in the caller's transaction (the step completion). @return the record id */
    UUID record(DecisionRecord record);

    /** Deletes the job's turn state (the step ended). Runs in the caller's transaction. */
    void endTurns(UUID workspaceId, UUID jobId);

    /** Seals the minimized-to-be input of a job for storage while it waits (AES-GCM, bound to the job). */
    String seal(UUID workspaceId, UUID jobId, ObjectNode input);

    /** @throws IllegalStateException when the sealed input cannot be opened (key rotated away, tampered) */
    ObjectNode open(UUID workspaceId, UUID jobId, String sealed);

    /** AI decision records of a run (run detail, evidence packs). {@code withValues} adds retained input values. */
    List<DecisionView> decisions(UUID workspaceId, UUID runId, boolean withValues);

    /**
     * Records the human outcome of an AI decision (accepted or overridden) and, for overrides, queues a candidate
     * evaluation case. Runs in the caller's transaction.
     *
     * @return false when the decision does not exist in this run
     */
    boolean recordOutcome(UUID workspaceId, UUID runId, UUID decisionId, HumanOutcome outcome);

    /**
     * Records the verification of a document extraction (CG-A06): the human outcome of the decision ({@code accepted}
     * when every field was confirmed, {@code overridden} when one was corrected) and, for corrections, a pending
     * evaluation candidate whose expected fields are the verified values. Runs in the caller's transaction.
     *
     * @param finalFields the verified values by field name
     * @param corrected   the names of the corrected fields
     * @return false when the decision does not exist in this run
     */
    boolean recordVerification(UUID workspaceId, UUID runId, UUID decisionId, ObjectNode finalFields,
            List<String> corrected, String reason, UUID decidedBy);

    /**
     * Control facts of an evaluation run for the release evidence pack (status, profile, suite, cases, accuracy,
     * finish time); null when the run does not exist in the workspace.
     */
    JsonNode evaluationEvidence(UUID workspaceId, UUID evaluationRunId);

    /** A lease on the job that is renewed while the pipeline works. */
    interface Lease {
        /** @return false when the lease was lost (another worker owns the job, the step was stopped) */
        boolean renew();

        /** Wall-clock deadline of the task (at most 10 minutes after it started). */
        Instant deadline();
    }

    /**
     * @param hasNeedsReview true when the step has a needsReview path (classify may then use it instead of
     *                       lowConfidence for suggest and policy downgrades)
     * @param input          mapped values by skill input name, before minimization
     */
    record TaskRequest(UUID workspaceId, UUID runId, UUID workflowId, UUID initiatorId, String nodeId, int occurrence,
            UUID jobId, String skillKey, int skillVersion, String contentHash, String mode, String autonomy,
            BigDecimal minConfidence, int reviewSamplePercent, Map<String, String> redaction, boolean hasNeedsReview,
            ObjectNode input) {
    }

    /**
     * @param route   {@code next}, {@code lowConfidence}, {@code needsReview}, {@code aiUnavailable} or
     *                {@code label:<key>}
     * @param outputs the step outputs ({@code /steps/<node>/...})
     */
    record Outcome(String route, ObjectNode outputs, DecisionRecord record, ObjectNode verification) {

        public Outcome(String route, ObjectNode outputs, DecisionRecord record) {
            this(route, outputs, record, null);
        }
    }

    /** Everything section 7.8 lists; written by {@link #record}. */
    record DecisionRecord(UUID workspaceId, UUID runId, String nodeId, int occurrence, String skillKey, int skillVersion,
            String skillHash, String mode, String autonomy, String effectiveAutonomy, String profile, String provider,
            String modelUsed, String promptHash, List<String> inputFields, String inputDigest, JsonNode inputValues,
            String inputRetention, int retentionDays, String status, JsonNode output, BigDecimal rawConfidence,
            BigDecimal confidence, BigDecimal threshold, String route, JsonNode guardrails, String unavailableReason,
            boolean reviewSampled, Long inputTokens, Long outputTokens, BigDecimal costUsd, long latencyMs) {
    }

    /** A decision record as shown in run detail and evidence packs. */
    record DecisionView(UUID id, String nodeId, int occurrence, String skillKey, int skillVersion, String skillHash,
            String mode, String autonomy, String effectiveAutonomy, String profile, String provider, String modelUsed,
            String promptHash, List<String> inputFields, String inputDigest, JsonNode inputValues,
            String inputRetention, String status, JsonNode output, BigDecimal rawConfidence, BigDecimal confidence,
            BigDecimal threshold, String route, JsonNode guardrails, String unavailableReason, boolean reviewSampled,
            String humanOutcome, String humanValue, String humanReason, UUID humanBy, Instant humanAt,
            Long inputTokens, Long outputTokens, BigDecimal costUsd, long latencyMs, Instant createdAt) {
    }

    /** @param outcome {@code accepted} or {@code overridden}; {@code value} is the final label or outcome */
    record HumanOutcome(String outcome, String value, String reason, UUID decidedBy) {
    }
}
