package com.orqix.runtime.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.integration.AiTaskPort;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI decision records of a run (CG-A03, doc 29 section 7.8): shown in run detail and evidence packs. Everyone who may
 * see the run sees the record; retained input values are shown only to callers who may see the run's data
 * ({@link RunAccess.Visible#dataVisible()}). The human outcome is recorded by a participant of the run, an operator or
 * an evaluator.
 */
@Service
public class AiDecisions {

    private final RunAccess runAccess;
    private final AiTaskPort ai;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Json json;

    public AiDecisions(RunAccess runAccess, AiTaskPort ai, PolicyService policy, AuditWriter audit, Json json) {
        this.runAccess = runAccess;
        this.ai = ai;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ObjectNode list(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        ObjectNode result = json.object();
        result.put("valuesVisible", visible.dataVisible());
        ArrayNode items = result.putArray("items");
        ai.decisions(workspaceId, runId, visible.dataVisible()).forEach(d -> items.add(view(json, d)));
        return result;
    }

    @Transactional
    public ObjectNode recordOutcome(Caller caller, UUID workspaceId, UUID runId, UUID decisionId, JsonNode body) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        if (!visible.participant() && !visible.access().has(Permission.RUN_OPERATE)
                && !visible.access().has(Permission.AI_EVALUATE)) {
            policy.require(visible.access(), Permission.RUN_OPERATE, "recordAiDecisionOutcome", true);
        }
        String outcome = body == null ? "" : body.path("outcome").asString("");
        if (!List.of("accepted", "overridden").contains(outcome)) {
            throw invalid("/outcome", "Use accepted or overridden.");
        }
        String value = body.path("value").isString() ? body.path("value").asString().strip() : null;
        String reason = body.path("reason").isString() ? body.path("reason").asString().strip() : null;
        if ("overridden".equals(outcome)) {
            if (value == null || !value.matches("^[a-z][A-Za-z0-9]{0,39}$")) {
                throw invalid("/value", "Name the label or outcome the person chose instead.");
            }
            if (reason == null || reason.isEmpty()) {
                throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why the AI suggestion was overridden.");
            }
        }
        if (!ai.recordOutcome(workspaceId, runId, decisionId, new AiTaskPort.HumanOutcome(outcome, value, reason,
                caller.subjectId()))) {
            throw ApiException.notFound();
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.decision.outcome-recorded", "aiDecision", decisionId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("runId", runId.toString(), "outcome", outcome)));
        return ai.decisions(workspaceId, runId, visible.dataVisible()).stream()
                .filter(d -> d.id().equals(decisionId)).findFirst().map(d -> view(json, d))
                .orElseThrow(ApiException::notFound);
    }

    /** The API and evidence-pack form of one record. */
    public static ObjectNode view(Json json, AiTaskPort.DecisionView d) {
        ObjectNode o = json.object();
        o.put("id", d.id().toString()).put("nodeId", d.nodeId()).put("occurrence", d.occurrence())
                .put("skillKey", d.skillKey()).put("skillVersion", d.skillVersion());
        text(o, "skillHash", d.skillHash());
        o.put("mode", d.mode()).put("autonomy", d.autonomy()).put("effectiveAutonomy", d.effectiveAutonomy());
        text(o, "profile", d.profile());
        text(o, "provider", d.provider());
        text(o, "modelUsed", d.modelUsed());
        text(o, "promptHash", d.promptHash());
        ArrayNode fields = o.putArray("inputFields");
        d.inputFields().forEach(fields::add);
        o.put("inputDigest", d.inputDigest()).put("inputRetention", d.inputRetention());
        if (d.inputValues() == null) {
            o.putNull("inputValues");
        } else {
            o.set("inputValues", d.inputValues());
        }
        o.put("status", d.status());
        if (d.output() == null) {
            o.putNull("output");
        } else {
            o.set("output", d.output());
        }
        number(o, "rawConfidence", d.rawConfidence());
        number(o, "confidence", d.confidence());
        number(o, "threshold", d.threshold());
        o.put("route", d.route());
        o.set("guardrails", d.guardrails());
        text(o, "unavailableReason", d.unavailableReason());
        o.put("reviewSampled", d.reviewSampled());
        ObjectNode human = o.putObject("human");
        text(human, "outcome", d.humanOutcome());
        text(human, "value", d.humanValue());
        text(human, "reason", d.humanReason());
        text(human, "decidedBy", d.humanBy() == null ? null : d.humanBy().toString());
        text(human, "decidedAt", d.humanAt() == null ? null : d.humanAt().toString());
        ObjectNode cost = o.putObject("cost");
        if (d.inputTokens() == null) {
            cost.putNull("inputTokens");
        } else {
            cost.put("inputTokens", d.inputTokens());
        }
        if (d.outputTokens() == null) {
            cost.putNull("outputTokens");
        } else {
            cost.put("outputTokens", d.outputTokens());
        }
        number(cost, "costUsd", d.costUsd());
        cost.put("latencyMs", d.latencyMs());
        o.put("createdAt", d.createdAt().toString());
        return o;
    }

    private static void text(ObjectNode o, String name, String value) {
        if (value == null) {
            o.putNull(name);
        } else {
            o.put(name, value);
        }
    }

    private static void number(ObjectNode o, String name, java.math.BigDecimal value) {
        if (value == null) {
            o.putNull(name);
        } else {
            o.put(name, value);
        }
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }
}
