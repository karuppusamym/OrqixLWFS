package com.orqix.integration;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.orqix.compiler.ConnectionContracts;

import com.orqix.integration.ConnectionDefinition.Operation;
import com.orqix.integration.EffectJournal.BeginResult;
import com.orqix.integration.health.CallGuard;
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
            String effectClass, boolean reused, String summary, ObjectNode meta, ObjectNode facts)
            implements DispatchResult {

        public Succeeded(UUID effectId, int attempt, ObjectNode result, Integer httpStatus, String remoteReference,
                String effectClass, boolean reused, String summary, ObjectNode meta) {
            this(effectId, attempt, result, httpStatus, remoteReference, effectClass, reused, summary, meta, null);
        }
    }

    /**
     * @param attentionReason S5 section 6.3 reason applied when this failure stops automatic progress
     * @param classification  retryable, permanent or unknown
     * @param retryAfter      the server's {@code Retry-After} of a 429 or 503 (CG-W04, S131), else null
     */
    public record Failed(UUID effectId, int attempt, boolean retryable, String attentionReason, String errorCode,
            String classification, Integer httpStatus, String effectClass, String businessMessage, String failureCode,
            String failureMessage, Duration retryAfter) implements DispatchResult {

        public Failed(UUID effectId, int attempt, boolean retryable, String attentionReason, String errorCode,
                String classification, Integer httpStatus, String effectClass, String businessMessage) {
            this(effectId, attempt, retryable, attentionReason, errorCode, classification, httpStatus, effectClass,
                    businessMessage, null, null, null);
        }

        /** A mapped business failure of a v1 operation (S130). */
        public Failed(UUID effectId, int attempt, boolean retryable, String attentionReason, String errorCode,
                String classification, Integer httpStatus, String effectClass, String businessMessage,
                String failureCode, String failureMessage) {
            this(effectId, attempt, retryable, attentionReason, errorCode, classification, httpStatus, effectClass,
                    businessMessage, failureCode, failureMessage, null);
        }

        /** A retryable failure carrying the server's {@code Retry-After} (S131). */
        public Failed(UUID effectId, int attempt, boolean retryable, String attentionReason, String errorCode,
                String classification, Integer httpStatus, String effectClass, String businessMessage,
                Duration retryAfter) {
            this(effectId, attempt, retryable, attentionReason, errorCode, classification, httpStatus, effectClass,
                    businessMessage, null, null, retryAfter);
        }
    }

    private final ConnectionService connections;
    private final EgressPolicy egress;
    private final SecretStore secrets;
    private final HttpExecutor executor;
    private final AgentExecutor agents;
    private final EffectJournal journal;
    private final Json json;
    private final JdbcClient jdbc;
    private final ObjectProvider<RequestFiles> files;
    private final CallGuard guard;

    public EffectDispatcher(ConnectionService connections, EgressPolicy egress, SecretStore secrets,
            HttpExecutor executor, AgentExecutor agents, EffectJournal journal, Json json, JdbcClient jdbc,
            ObjectProvider<RequestFiles> files, CallGuard guard) {
        this.jdbc = jdbc;
        this.files = files;
        this.guard = guard;
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
                secretValue = secrets.credential(binding.get().authType(), binding.get().secretRef(), request.workspaceId()).orElse(null);
                if (secretValue == null) {
                    policyDenial = "The credential for the connection is not available";
                    if (binding.get().authType().startsWith("oauth2")) {
                        guard.tokenFailure(request.workspaceId(), request.connectionKey(), request.operation());
                    }
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

        // CG-W04 (S131): an open circuit or a used-up request rate defers the call before anything is registered.
        CallGuard.Ticket ticket = policyDenial == null && !definition.isAgent()
                ? guard.before(request.workspaceId(), request.connectionKey(), request.operation(),
                        request.timeoutSeconds())
                : new CallGuard.Ticket(false);
        EffectJournal.EffectRequest effectRequest = new EffectJournal.EffectRequest(request.workspaceId(),
                request.runId(), request.nodeId(), request.occurrence(), effectKey, request.connectionKey(),
                request.connectionVersion(), request.operation(), effectClass, requestHash);
        Duration lease = Duration.ofSeconds(leaseSeconds(operation, request.timeoutSeconds()));
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
            case PROCEED, RECONCILE_FIRST -> {
                // continue below
            }
        }
        int attempt = effect.attempts();
        if (dnsFailure) {
            guard.notResolved(request.workspaceId(), request.connectionKey(), request.operation(), ticket);
            journal.finish(request.workspaceId(), effect.id(), EffectJournal.RETRYABLE_FAILURE, null, "DNS_FAILURE",
                    "Host name did not resolve", null, null);
            return new Failed(effect.id(), attempt, true, "RETRIES_EXHAUSTED", "DNS_FAILURE", "retryable", null,
                    effectClass, retriesMessage(name));
        }
        ConnectionService.Binding target = binding.orElseThrow();
        if (definition.isAgent()) {
            return dispatchAgent(request, record, operation, target, secretValue, effect, attempt, effectClass);
        }
        HttpExecutor.Target http = new HttpExecutor.Target(target.baseUrl(), target.authType(),
                target.authHeaderName(), target.secretRef(), request.workspaceId());
        ObjectNode inputs = request.body();
        String reconcileKey = null;
        if (operation.reconcile() != null) {
            // CG-C02: the stable key is written into keyField on every send, so a lookup can find the record.
            reconcileKey = reconcileKey(operation.reconcile(), request, effectKey);
            inputs = inputs.deepCopy();
            inputs.put(operation.reconcile().path("keyField").asString(), reconcileKey);
        }
        V1Requests.Context context = new V1Requests.Context(request.workspaceId(), request.runId(), files.getIfAvailable(),
                Map.of(), null);
        if (begin.action() == EffectJournal.BeginAction.RECONCILE_FIRST) {
            // An earlier attempt ended without an outcome: never resend before the lookup says it is safe.
            Lookup found = lookup(definition, operation, http, secretValue, reconcileKey, request, context);
            if (found.state() == Lookup.State.FOUND) {
                return reconciled(request, effect, attempt, effectClass, found, name, "LEASE_EXPIRED");
            }
            if (found.state() == Lookup.State.FAILED) {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.UNKNOWN, null, "LOOKUP_FAILED",
                        "A previous attempt ended without an outcome and the lookup failed", null, null);
                return new Failed(effect.id(), attempt, false, "EFFECT_UNKNOWN", "LOOKUP_FAILED", "unknown", null,
                        effectClass, unknownMessage(name));
            }
        }
        HttpExecutor.CallResult call = operation.pagination() != null
                ? paginate(request, definition, operation, http, inputs, effectKey, secretValue, context, attempt)
                : executor.call(http, definition, operation, inputs, request.resultMapping(), effectKey,
                        request.timeoutSeconds(), secretValue, context);
        if (call.outcome() == HttpExecutor.Outcome.UNKNOWN && call.observation() == HttpExecutor.Observation.MAYBE_APPLIED
                && operation.reconcile() != null) {
            Lookup found = lookup(definition, operation, http, secretValue, reconcileKey, request, context);
            switch (found.state()) {
                case FOUND -> {
                    return reconciled(request, effect, attempt, effectClass, found, name, call.errorCode());
                }
                case NOT_FOUND -> {
                    // Not applied: safe to send again with the same key on the next attempt.
                    journal.finish(request.workspaceId(), effect.id(), EffectJournal.RETRYABLE_FAILURE,
                            call.httpStatus(), call.errorCode(), call.detail() + "; lookup found no record", null, null);
                    return new Failed(effect.id(), attempt, true, "RETRIES_EXHAUSTED", call.errorCode(), "retryable",
                            call.httpStatus(), effectClass, retriesMessage(name));
                }
                case FAILED -> {
                    // Fall through to unknown, as without a lookup.
                }
            }
        }
        guard.after(request.workspaceId(), request.connectionKey(), request.operation(), ticket, call);
        switch (call.outcome()) {
            case SUCCEEDED -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.SUCCEEDED, call.httpStatus(), null,
                        call.detail(), call.result(), call.remoteReference());
                return new Succeeded(effect.id(), attempt, call.result(), call.httpStatus(), call.remoteReference(),
                        effectClass, false, null, null, pageFacts(call));
            }
            case RETRYABLE -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.RETRYABLE_FAILURE, call.httpStatus(),
                        call.errorCode(), call.detail(), null, null);
                return new Failed(effect.id(), attempt, true, "RETRIES_EXHAUSTED", call.errorCode(), "retryable",
                        call.httpStatus(), effectClass, retriesMessage(name), call.retryAfter());
            }
            case PERMANENT -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.PERMANENT_FAILURE, call.httpStatus(),
                        call.errorCode(), call.detail(), null, null);
                String message = "EFFECT_REJECTED".equals(call.attentionReason())
                        ? name + " rejected the request" + (call.httpStatus() == null ? "" : " (HTTP " + call.httpStatus() + ")")
                                + ". An operator must fix the cause, then retry."
                        : name + " returned a response Orqix could not use. An operator must check the connection "
                                + "contract, then retry.";
                boolean mapped = operation.v1() && call.errorCode() != null && !call.errorCode().startsWith("HTTP_")
                        && "EFFECT_REJECTED".equals(call.attentionReason()) && call.httpStatus() != null;
                return new Failed(effect.id(), attempt, false, call.attentionReason(), call.errorCode(), "permanent",
                        call.httpStatus(), effectClass, message, mapped ? call.errorCode() : null,
                        mapped ? call.failureMessage() : null);
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
                ("bearerToken".equals(target.authType()) || ("oauth2ClientCredentials".equals(target.authType()) || "oauth2AuthorizationCode".equals(target.authType()))) ? secretValue : null, request.timeoutSeconds()));
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

    /** Outcome of a reconciliation lookup (CG-C02). */
    record Lookup(State state, ObjectNode result, String remoteReference, int tries, String lookupOperation) {
        enum State { FOUND, NOT_FOUND, FAILED }
    }

    /** Lookup tries before deciding "not found"; spread over lookupSettleSeconds. */
    static final int LOOKUP_TRIES = 3;

    /** The key Orqix writes into keyField: request reference, step and occurrence, or the effect key. */
    private String reconcileKey(JsonNode reconcile, DispatchRequest request, String effectKey) {
        if ("effectKey".equals(reconcile.path("keyFrom").asString())) {
            return effectKey;
        }
        String reference = jdbc.sql("SELECT reference FROM workflow_run WHERE workspace_id = :ws AND id = :id")
                .param("ws", request.workspaceId()).param("id", request.runId()).query(String.class).optional()
                .orElse("ORQ-" + request.runId());
        return reference + "-" + request.nodeId() + "-" + request.occurrence();
    }

    private Lookup lookup(ConnectionDefinition definition, Operation operation, HttpExecutor.Target http,
            String secretValue, String key, DispatchRequest request, V1Requests.Context context) {
        JsonNode reconcile = operation.reconcile();
        String lookupName = reconcile.path("operation").asString();
        Operation lookup = definition.operation(lookupName);
        ObjectNode inputs = json.object();
        inputs.put(reconcile.path("lookupField").asString(reconcile.path("keyField").asString()), key);
        int settle = reconcile.path("lookupSettleSeconds").asInt(30);
        int timeout = Math.min(request.timeoutSeconds(), lookup.maxTimeoutSeconds());
        for (int tryNumber = 1; tryNumber <= LOOKUP_TRIES; tryNumber++) {
            if (tryNumber > 1 && settle > 0) {
                try {
                    Thread.sleep(settle * 1000L / (LOOKUP_TRIES - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Lookup(Lookup.State.FAILED, null, null, tryNumber, lookupName);
                }
            }
            HttpExecutor.CallResult call = executor.call(http, definition, lookup, inputs, Map.of(),
                    "lookup-" + key, timeout, secretValue, context);
            if (call.outcome() != HttpExecutor.Outcome.SUCCEEDED || call.page() == null) {
                return new Lookup(Lookup.State.FAILED, null, null, tryNumber, lookupName);
            }
            JsonNode found = call.page().body().at(reconcile.path("found").asString());
            if (found.isMissingNode() || found.isNull()) {
                continue;
            }
            JsonNode document = placed(found, reconcile.path("foundAs").asString(""));
            ObjectNode result = executor.mapRecorded(operation, request.resultMapping(), document);
            if (result == null) {
                // Found, but it cannot give the step's results: an operator must decide.
                return new Lookup(Lookup.State.FAILED, null, null, tryNumber, lookupName);
            }
            return new Lookup(Lookup.State.FOUND, result, HttpExecutor.remoteReference(document, result), tryNumber,
                    lookupName);
        }
        return new Lookup(Lookup.State.NOT_FOUND, null, null, LOOKUP_TRIES, lookupName);
    }

    /** @return a document with {@code found} at the pointer (the whole document for the empty pointer) */
    private JsonNode placed(JsonNode found, String pointer) {
        if (pointer.isEmpty()) {
            return found;
        }
        ObjectNode root = json.object();
        ObjectNode current = root;
        String[] tokens = pointer.substring(1).split("/", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].replace("~1", "/").replace("~0", "~");
            if (i == tokens.length - 1) {
                current.set(token, found);
            } else {
                current = current.putObject(token);
            }
        }
        return root;
    }

    private Succeeded reconciled(DispatchRequest request, EffectJournal.Effect effect, int attempt, String effectClass,
            Lookup found, String name, String cause) {
        ObjectNode resolution = json.object();
        resolution.put("kind", "lookup");
        resolution.put("outcome", "found");
        resolution.put("lookupOperation", found.lookupOperation());
        resolution.put("lookups", found.tries());
        resolution.put("cause", cause);
        if (found.remoteReference() != null) {
            resolution.put("remoteReference", found.remoteReference());
        }
        journal.finishReconciled(request.workspaceId(), effect.id(), null,
                "Reconciled automatically by " + found.lookupOperation(), found.result(), found.remoteReference(),
                resolution);
        String summary = "Reconciled automatically: found " + (found.remoteReference() == null ? "the record"
                : found.remoteReference()) + " created by an earlier attempt, so nothing was sent again.";
        ObjectNode facts = json.object();
        facts.put("reconciled", true);
        facts.put("lookupOperation", found.lookupOperation());
        return new Succeeded(effect.id(), attempt, found.result(), null, found.remoteReference(), effectClass, false,
                summary, null, facts);
    }

    /**
     * Bounded pagination (S130): page 1 is this effect's call; every further page is its own journaled read with node
     * id {@code <node>#a<attempt>p<page>}. Lists projected with {@code /[*]} collect items from every page up to
     * maxItems; other results come from page 1.
     */
    private HttpExecutor.CallResult paginate(DispatchRequest request, ConnectionDefinition definition,
            Operation operation, HttpExecutor.Target http, ObjectNode inputs, String effectKey, String secretValue,
            V1Requests.Context context, int attempt) {
        JsonNode page = operation.pagination();
        String style = page.path("style").asString();
        int maxPages = page.path("maxPages").asInt(1);
        int maxItems = page.path("maxItems").asInt(ConnectionContracts.MAX_ROWS);
        int pageSize = page.path("pageSize").asInt(0);
        String itemsPointer = page.path("itemsPointer").asString();
        Map<String, String> extra = new LinkedHashMap<>();
        if ("offset".equals(style)) {
            extra.put(page.path("offsetParam").asString(), "0");
            extra.put(page.path("limitParam").asString(), Integer.toString(pageSize));
        }
        HttpExecutor.CallResult first = executor.call(http, definition, operation, inputs, request.resultMapping(),
                effectKey, request.timeoutSeconds(), secretValue, withQuery(context, extra, null));
        if (first.outcome() != HttpExecutor.Outcome.SUCCEEDED || first.page() == null) {
            return first;
        }
        ObjectNode result = first.result().deepCopy();
        List<String> truncated = new ArrayList<>(first.page().truncated());
        trimLists(result, request.resultMapping(), maxItems, truncated);
        JsonNode body = first.page().body();
        String nextLink = first.page().nextLink();
        int items = body.at(itemsPointer).size();
        int offset = 0;
        int pages = 1;
        while (true) {
            String continuation;
            URI absolute = null;
            Map<String, String> query = new LinkedHashMap<>();
            if ("offset".equals(style)) {
                int onPage = body.at(itemsPointer).size();
                if (onPage < pageSize) {
                    break;
                }
                offset += pageSize;
                continuation = Integer.toString(offset);
                query.put(page.path("offsetParam").asString(), continuation);
                query.put(page.path("limitParam").asString(), Integer.toString(pageSize));
            } else if ("linkHeader".equals(style)) {
                if (nextLink == null) {
                    break;
                }
                absolute = insideBinding(http.baseUrl(), nextLink);
                if (absolute == null) {
                    return new HttpExecutor.CallResult(HttpExecutor.Outcome.PERMANENT,
                            HttpExecutor.Observation.INVALID_RESPONSE, first.httpStatus(), "PAGE_LINK_OUTSIDE_BINDING",
                            "The next page link leaves the binding", "RESPONSE_INVALID", null, null, first.durationMs());
                }
                continuation = "link";
            } else {
                JsonNode cursor = body.at(page.path("cursorPointer").asString());
                if (cursor.isMissingNode() || cursor.isNull() || cursor.isContainer() || cursor.asString().isEmpty()) {
                    break;
                }
                continuation = cursor.asString();
                query.put(page.path("cursorParam").asString(), continuation);
            }
            if (pages >= maxPages || items >= maxItems) {
                // More pages exist than the hard caps allow: the lists are cut, visibly.
                for (Map.Entry<String, String> e : request.resultMapping().entrySet()) {
                    ConnectionContracts.ResultPointer p = ConnectionContracts.parseResult(e.getValue());
                    if (p != null && p.projection() && !truncated.contains(e.getKey())) {
                        truncated.add(e.getKey());
                    }
                }
                break;
            }
            pages++;
            String pageNode = request.nodeId() + "#a" + attempt + "p" + pages;
            String pageKey = effectKey(request.workspaceId(), request.runId(), pageNode, request.occurrence());
            EffectJournal.BeginResult begun = journal.begin(new EffectJournal.EffectRequest(request.workspaceId(),
                    request.runId(), pageNode, request.occurrence(), pageKey, request.connectionKey(),
                    request.connectionVersion(), request.operation(), "readOnly",
                    Json.sha256Hex(effectKey + "|" + pages + "|" + continuation)), request.leaseOwner(),
                    Duration.ofSeconds(request.timeoutSeconds()).plus(LEASE_MARGIN), null);
            if (begun.action() != EffectJournal.BeginAction.PROCEED) {
                return new HttpExecutor.CallResult(HttpExecutor.Outcome.RETRYABLE, HttpExecutor.Observation.NOT_SENT,
                        null, "PAGE_BUSY", "Page " + pages + " is already being read", "RETRIES_EXHAUSTED", null, null,
                        first.durationMs());
            }
            HttpExecutor.CallResult next = executor.call(http, definition, operation, inputs, request.resultMapping(),
                    pageKey, request.timeoutSeconds(), secretValue, withQuery(context, query, absolute));
            if (next.outcome() != HttpExecutor.Outcome.SUCCEEDED || next.page() == null) {
                journal.finish(request.workspaceId(), begun.effect().id(),
                        next.outcome() == HttpExecutor.Outcome.RETRYABLE ? EffectJournal.RETRYABLE_FAILURE
                                : EffectJournal.PERMANENT_FAILURE, next.httpStatus(), next.errorCode(), next.detail(),
                        null, null);
                return next;
            }
            body = next.page().body();
            nextLink = next.page().nextLink();
            int onPage = body.at(itemsPointer).size();
            items += onPage;
            ObjectNode evidence = json.object();
            evidence.put("page", pages);
            evidence.put("items", onPage);
            journal.finish(request.workspaceId(), begun.effect().id(), EffectJournal.SUCCEEDED, next.httpStatus(), null,
                    next.detail(), evidence, null);
            V1Results.append(result, new V1Results.Mapped(next.result(), next.page().truncated()),
                    request.resultMapping(), truncated, maxItems);
            for (String key : next.page().truncated()) {
                if (!truncated.contains(key)) {
                    truncated.add(key);
                }
            }
        }
        ObjectNode facts = json.object();
        facts.put("pages", pages);
        if (!truncated.isEmpty()) {
            facts.set("truncated", json.toTree(truncated));
        }
        return new HttpExecutor.CallResult(HttpExecutor.Outcome.SUCCEEDED, HttpExecutor.Observation.SUCCESS,
                first.httpStatus(), null, first.detail() + "; " + pages + " page(s)", null, result,
                first.remoteReference(), first.durationMs(), new HttpExecutor.Page(facts, null, List.copyOf(truncated)),
                null);
    }

    private static void trimLists(ObjectNode result, Map<String, String> mapping, int maxItems, List<String> truncated) {
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            JsonNode list = result.get(e.getKey());
            ConnectionContracts.ResultPointer p = ConnectionContracts.parseResult(e.getValue());
            if (p != null && p.projection() && list != null && list.isArray() && list.size() > maxItems) {
                tools.jackson.databind.node.ArrayNode array = (tools.jackson.databind.node.ArrayNode) list;
                while (array.size() > maxItems) {
                    array.remove(array.size() - 1);
                }
                if (!truncated.contains(e.getKey())) {
                    truncated.add(e.getKey());
                }
            }
        }
    }

    private static V1Requests.Context withQuery(V1Requests.Context context, Map<String, String> query, URI absolute) {
        return new V1Requests.Context(context.workspaceId(), context.runId(), context.files(), Map.copyOf(query),
                absolute);
    }

    /** @return the absolute next-page URI when it stays under the binding base URL, else null */
    static URI insideBinding(String baseUrl, String link) {
        try {
            URI base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
            URI target = base.resolve(link.trim()).normalize();
            boolean sameOrigin = base.getScheme().equalsIgnoreCase(target.getScheme())
                    && base.getHost().equalsIgnoreCase(target.getHost()) && base.getPort() == target.getPort();
            String basePath = base.getPath() == null ? "/" : base.getPath();
            String path = target.getPath() == null ? "/" : target.getPath();
            return sameOrigin && target.getUserInfo() == null && target.getFragment() == null
                    && path.startsWith(basePath) && !path.contains("/../") ? target : null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Pages and cut lists of a paginated read, for the run timeline (else null). */
    private ObjectNode pageFacts(HttpExecutor.CallResult call) {
        if (call.page() == null) {
            return null;
        }
        if (call.page().body() != null && call.page().body().has("pages")) {
            return (ObjectNode) call.page().body();
        }
        if (call.page().truncated().isEmpty()) {
            return null;
        }
        ObjectNode facts = json.object();
        facts.set("truncated", json.toTree(call.page().truncated()));
        return facts;
    }

    /**
     * Seconds the effect lease (and the job lock) must cover: the timeout plus margin, times the pages of a paginated
     * read, plus the lookups and their settling time for idempotentByLookup.
     */
    public static long leaseSeconds(Operation operation, int timeoutSeconds) {
        long seconds = timeoutSeconds;
        JsonNode page = operation.pagination();
        if (page != null) {
            seconds = (long) timeoutSeconds * page.path("maxPages").asInt(1);
        }
        JsonNode reconcile = operation.reconcile();
        if (reconcile != null) {
            seconds += (long) timeoutSeconds * LOOKUP_TRIES + reconcile.path("lookupSettleSeconds").asInt(30);
        }
        return seconds + LEASE_MARGIN.toSeconds();
    }

    /** @return lease seconds for a registered operation, or the plain timeout plus margin when it is unknown */
    public long leaseSeconds(UUID workspaceId, String connectionKey, int version, String operation, int timeoutSeconds) {
        return connections.definition(workspaceId, connectionKey, version)
                .map(r -> r.definition().operations().get(operation))
                .map(op -> leaseSeconds(op, timeoutSeconds))
                .orElse(timeoutSeconds + LEASE_MARGIN.toSeconds());
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
