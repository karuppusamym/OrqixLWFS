package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * "Explain this request" (CG-A07, doc 29 section 7.12, docs/build/S146-parts/C-operator-explain.md); schemas in
 * contracts/openapi.yaml under the Completion tag. Read-only: nothing here changes a request.
 */
public final class ExplainDtos {

    private ExplainDtos() {
    }

    /** Optional body. {@code preferRules} answers from the rules without calling a model. */
    public record ExplainRunRequest(Boolean preferRules) {
    }

    /**
     * One fact the explanation may cite. Text is written by Orqix from codes, times and labels; it never holds request
     * data values.
     *
     * @param kind     run, attention, effect, connection, circuit, binding, step, wait, check, task, event, model,
     *                 suspension or child
     * @param eventSeq the timeline entry the fact comes from (links the panel to the timeline), else null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Fact(String id, String kind, String text, String nodeId, Instant at, Long eventSeq) {
    }

    /**
     * An action the caller may take now, computed from the same permission checks as the action itself.
     *
     * @param id          stable id the explanation refers to, such as {@code retryRun} or {@code reassignTask:<taskId>}
     * @param operationId the API operation behind it (null for {@code askAdministrator})
     * @param kind        {@code operate} (an operator action with its usual dialog and confirmation), {@code open}
     *                    (a settings page the caller may change) or {@code askAdministrator} (someone else must act)
     * @param permission  the permission the action needs
     * @param targetId    effect, wait, task or connection the action applies to, else null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PermittedAction(String id, String operationId, String kind, String label, String description,
            String permission, String nodeId, String targetId) {
    }

    /** @param why a short reason; from the rules or the model */
    public record SuggestedAction(String actionId, String why) {
    }

    /** @param cached the answer was reused for the same request state within the cache lifetime */
    public record AiInfo(String profile, String model, String promptVersion, boolean cached) {
    }

    /**
     * @param source         {@code ai} or {@code rules}
     * @param fallbackReason why the rules answered although AI was wanted (AI off, unavailable, over budget, outside the
     *                       residency rule, invalid answer); null when AI answered or rules were asked for
     * @param droppedActions action ids the model suggested that are not in {@code permittedActions} (never shown as
     *                       actions)
     * @param stateDigest    digest of the facts and permitted actions the explanation was made from
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunExplanation(UUID runId, int runVersion, String lifecycle, String source, String summary,
            String cause, List<String> evidence, List<SuggestedAction> suggestedActions, List<Fact> facts,
            List<PermittedAction> permittedActions, List<String> droppedActions, String fallbackReason, AiInfo ai,
            boolean readOnly, String stateDigest, Instant generatedAt) {
    }
}
