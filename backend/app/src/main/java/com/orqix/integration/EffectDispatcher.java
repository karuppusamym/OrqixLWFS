package com.orqix.integration;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.orqix.integration.ConnectionDefinition.Operation;
import com.orqix.integration.EffectJournal.BeginResult;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes one external effect occurrence with the journal protocol of S5 section 6.1: transaction A (register, lock,
 * decide, lease), the call outside any database lock, classification, transaction B (record outcome). Never throws for
 * remote failures; the caller turns a {@link Failed} result into a retryable or non-retryable job failure.
 */
@Service
public class EffectDispatcher {

    /** Extra lease beyond the call timeout for journal writes (S5 section 6.1). */
    static final Duration LEASE_MARGIN = Duration.ofSeconds(15);

    /** @param initiatorSubjectId run initiator; agent usage is metered for this person */
    public record DispatchRequest(UUID workspaceId, UUID runId, String nodeId, int occurrence, String connectionKey,
            int connectionVersion, String operation, ObjectNode body, Map<String, String> resultMapping,
            int timeoutSeconds, String leaseOwner, UUID initiatorSubjectId) {
    }

    public sealed interface DispatchResult permits Succeeded, Failed {
        UUID effectId();
    }

    /**
     * @param reused  true when an earlier attempt had already succeeded (no call was made)
     * @param summary business sentence for the run timeline (agent operations only, else null)
     * @param meta    model, tool names and timings, tokens and cost of an agent call (else null); never payloads
     */
    public record Succeeded(UUID effectId, int attempt, ObjectNode result, Integer httpStatus, String remoteReference,
            String effectClass, boolean reused, String summary, ObjectNode meta) implements DispatchResult {
    }

    /**
     * @param attentionReason S5 section 6.3 reason applied when this failure stops automatic progress
     * @param classification  retryable, permanent or unknown
     */
    public record Failed(UUID effectId, int attempt, boolean retryable, String attentionReason, String errorCode,
            String classification, Integer httpStatus, String effectClass, String businessMessage)
            implements DispatchResult {
    }

    private final ConnectionService connections;
    private final EgressPolicy egress;
    private final SecretStore secrets;
    private final HttpExecutor executor;
    private final AgentExecutor agents;
    private final EffectJournal journal;
    private final Json json;

    public EffectDispatcher(ConnectionService connections, EgressPolicy egress, SecretStore secrets,
            HttpExecutor executor, AgentExecutor agents, EffectJournal journal, Json json) {
        this.connections = connections;
        this.egress = egress;
        this.secrets = secrets;
        this.executor = executor;
        this.agents = agents;
        this.journal = journal;
        this.json = json;
    }

    /** {@code sha256(workspaceId|runId|nodeId|occurrence)} hex (S2 section 6). */
    public static String effectKey(UUID workspaceId, UUID runId, String nodeId, int occurrence) {
        return Json.sha256Hex(workspaceId + "|" + runId + "|" + nodeId + "|" + occurrence);
    }

    public DispatchResult dispatch(DispatchRequest request) {
        ConnectionService.DefinitionRecord record = connections
                .definition(request.workspaceId(), request.connectionKey(), request.connectionVersion())
                .orElseThrow(() -> new IllegalStateException("Connection " + request.connectionKey() + " v"
                        + request.connectionVersion() + " is not registered"));
        ConnectionDefinition definition = record.definition();
        Operation operation = definition.operation(request.operation());
        String effectClass = operation.effectClass().value();
        String effectKey = effectKey(request.workspaceId(), request.runId(), request.nodeId(), request.occurrence());
        String requestHash = json.canonicalSha256(request.body());
        String name = definition.name();

        // Pre-dispatch policy check with current data (binding enabled, egress, credential present).
        String policyDenial = null;
        String denialMessage = null;
        boolean dnsFailure = false;
        Optional<ConnectionService.Binding> binding = connections.binding(request.workspaceId(), request.connectionKey());
        String secretValue = null;
        if (binding.isEmpty() || !binding.get().enabled()) {
            policyDenial = "The connection binding is missing or disabled";
        } else {
            try {
                egress.check(binding.get().baseUrl());
            } catch (EgressPolicy.EgressDeniedException e) {
                if (e.denial() == EgressPolicy.Denial.DNS_FAILURE) {
                    dnsFailure = true;
                } else {
                    policyDenial = "The endpoint is not allowed by the egress policy (" + e.denial() + ")";
                }
            }
            if (policyDenial == null && !"none".equals(binding.get().authType())) {
                secretValue = secrets.read(binding.get().secretRef()).orElse(null);
                if (secretValue == null) {
                    policyDenial = "The credential for the connection is not available";
                }
            }
            if (policyDenial == null && definition.isAgent() && agents.unavailableReason() != null) {
                // Agents need AI: a clear failure that an operator can retry after the fix (S13 section 7).
                String reason = agents.unavailableReason();
                policyDenial = "AI is not available: " + reason;
                denialMessage = name + " needs AI, which is not available: " + reason
                        + " An administrator must fix it, then an operator can retry.";
            }
        }

        EffectJournal.EffectRequest effectRequest = new EffectJournal.EffectRequest(request.workspaceId(),
                request.runId(), request.nodeId(), request.occurrence(), effectKey, request.connectionKey(),
                request.connectionVersion(), request.operation(), effectClass, requestHash);
        Duration lease = Duration.ofSeconds(request.timeoutSeconds()).plus(LEASE_MARGIN);
        BeginResult begin = journal.begin(effectRequest, request.leaseOwner(), lease, policyDenial);
        EffectJournal.Effect effect = begin.effect();
        switch (begin.action()) {
            case ALREADY_SUCCEEDED -> {
                return new Succeeded(effect.id(), effect.attempts(), (ObjectNode) effect.result(),
                        effect.lastHttpStatus(), effect.remoteReference(), effectClass, true, null, null);
            }
            case UNKNOWN -> {
                return new Failed(effect.id(), effect.attempts(), false, "EFFECT_UNKNOWN", "EFFECT_UNKNOWN", "unknown",
                        effect.lastHttpStatus(), effectClass, unknownMessage(name));
            }
            case BUSY -> {
                return new Failed(effect.id(), effect.attempts(), true, "RETRIES_EXHAUSTED", "BUSY", "retryable", null,
                        effectClass, "Another attempt for this step is still in progress.");
            }
            case HASH_MISMATCH -> {
                return new Failed(effect.id(), effect.attempts(), false, "INTERNAL_ERROR", "REQUEST_HASH_MISMATCH",
                        "permanent", null, effectClass, "The request for this step changed unexpectedly.");
            }
            case POLICY_DENIED -> {
                return new Failed(effect.id(), effect.attempts(), false, "CONNECTION_UNAVAILABLE",
                        "CONNECTION_UNAVAILABLE", "permanent", null, effectClass,
                        denialMessage != null ? denialMessage : "The connection to " + name + " is not available. "
                                + "An administrator must fix the connection binding, then an operator can retry.");
            }
            case PROCEED -> {
                // continue below
            }
        }
        int attempt = effect.attempts();
        if (dnsFailure) {
            journal.finish(request.workspaceId(), effect.id(), EffectJournal.RETRYABLE_FAILURE, null, "DNS_FAILURE",
                    "Host name did not resolve", null, null);
            return new Failed(effect.id(), attempt, true, "RETRIES_EXHAUSTED", "DNS_FAILURE", "retryable", null,
                    effectClass, retriesMessage(name));
        }
        ConnectionService.Binding target = binding.orElseThrow();
        if (definition.isAgent()) {
            return dispatchAgent(request, record, operation, target, secretValue, effect, attempt, effectClass);
        }
        HttpExecutor.CallResult call = executor.call(new HttpExecutor.Target(target.baseUrl(), target.authType(),
                target.authHeaderName(), target.secretRef(), request.workspaceId()), definition, operation, request.body(),
                request.resultMapping(), effectKey, request.timeoutSeconds(), secretValue);
        switch (call.outcome()) {
            case SUCCEEDED -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.SUCCEEDED, call.httpStatus(), null,
                        call.detail(), call.result(), call.remoteReference());
                return new Succeeded(effect.id(), attempt, call.result(), call.httpStatus(), call.remoteReference(),
                        effectClass, false, null, null);
            }
            case RETRYABLE -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.RETRYABLE_FAILURE, call.httpStatus(),
                        call.errorCode(), call.detail(), null, null);
                return new Failed(effect.id(), attempt, true, "RETRIES_EXHAUSTED", call.errorCode(), "retryable",
                        call.httpStatus(), effectClass, retriesMessage(name));
            }
            case PERMANENT -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.PERMANENT_FAILURE, call.httpStatus(),
                        call.errorCode(), call.detail(), null, null);
                String message = "EFFECT_REJECTED".equals(call.attentionReason())
                        ? name + " rejected the request" + (call.httpStatus() == null ? "" : " (HTTP " + call.httpStatus() + ")")
                                + ". An operator must fix the cause, then retry."
                        : name + " returned a response Orqix could not use. An operator must check the connection "
                                + "contract, then retry.";
                return new Failed(effect.id(), attempt, false, call.attentionReason(), call.errorCode(), "permanent",
                        call.httpStatus(), effectClass, message);
            }
            case UNKNOWN -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.UNKNOWN, call.httpStatus(),
                        call.errorCode(), call.detail(), null, null);
                return new Failed(effect.id(), attempt, false, "EFFECT_UNKNOWN", call.errorCode(), "unknown",
                        call.httpStatus(), effectClass, unknownMessage(name));
            }
            default -> throw new IllegalStateException();
        }
    }

    /** Runs an agent operation (S13): same journal protocol, the model and tool loop instead of one HTTP call. */
    private DispatchResult dispatchAgent(DispatchRequest request, ConnectionService.DefinitionRecord record,
            Operation operation, ConnectionService.Binding target, String secretValue, EffectJournal.Effect effect,
            int attempt, String effectClass) {
        AgentExecutor.Result run = agents.execute(new AgentExecutor.Request(request.workspaceId(), request.runId(),
                effect.id(), request.initiatorSubjectId(), request.nodeId(), attempt, record.definition(), operation,
                request.body(), request.resultMapping(), target.baseUrl(),
                "bearerToken".equals(target.authType()) ? secretValue : null, request.timeoutSeconds()));
        switch (run.outcome()) {
            case SUCCEEDED -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.SUCCEEDED, null, null, run.detail(),
                        run.result(), null);
                return new Succeeded(effect.id(), attempt, run.result(), null, null, effectClass, false, run.summary(),
                        run.meta());
            }
            case RETRYABLE -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.RETRYABLE_FAILURE, null,
                        run.errorCode(), run.detail(), null, null);
                return new Failed(effect.id(), attempt, true, "RETRIES_EXHAUSTED", run.errorCode(), "retryable", null,
                        effectClass, run.businessMessage());
            }
            default -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.PERMANENT_FAILURE, null,
                        run.errorCode(), run.detail(), null, null);
                return new Failed(effect.id(), attempt, false, run.attentionReason(), run.errorCode(), "permanent", null,
                        effectClass, run.businessMessage());
            }
        }
    }

    private static String unknownMessage(String name) {
        return "We could not confirm whether " + name + " completed this step. An operator must check " + name
                + " and reconcile before the request continues.";
    }

    private static String retriesMessage(String name) {
        return name + " could not be reached. Automatic retries are used up; an operator can retry once it is available.";
    }

    /** Builds the request body from resolved input values (numbers stay BigDecimal). */
    public ObjectNode body(Map<String, JsonNode> values) {
        ObjectNode body = json.object();
        values.forEach(body::set);
        return body;
    }
}
