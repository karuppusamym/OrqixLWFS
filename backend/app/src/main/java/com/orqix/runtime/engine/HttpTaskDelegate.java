package com.orqix.runtime.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.ErrorEventDefinition;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.BpmnError;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.integration.EffectDispatcher;
import com.orqix.runtime.internal.JobFailure;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunStore;
import com.orqix.runtime.internal.RuntimeMetrics;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Delegate {@code orqixHttpTask} (S5 section 6), executed by the async executor inside a job transaction. The effect
 * journal protocol and the call itself live in {@link EffectDispatcher}; this delegate maps inputs, stores the result
 * on success and turns failures into classified {@link JobFailure}s for the failed-job command.
 */
@Component("orqixHttpTask")
public class HttpTaskDelegate implements JavaDelegate {

    /**
     * Occurrence of steps outside bounded loops (S2 section 6). Inside a loop (S138) the delegate uses the visit number
     * of the step; a for-each item adds its position to the journal step key.
     */
    static final int OCCURRENCE = 1;

    private final Json json;
    private final EffectDispatcher dispatcher;
    private final RunStore runs;
    private final RunEvents events;
    private final RuntimeMetrics metrics;
    private final JdbcClient jdbc;
    private final OrqixProperties properties;
    private final com.orqix.runtime.internal.AgentLoops agentLoops;
    private final OrqixForEach forEach;

    public HttpTaskDelegate(Json json, EffectDispatcher dispatcher, RunStore runs, RunEvents events,
            RuntimeMetrics metrics, JdbcClient jdbc, OrqixProperties properties,
            @org.springframework.context.annotation.Lazy com.orqix.runtime.internal.AgentLoops agentLoops,
            @org.springframework.context.annotation.Lazy OrqixForEach forEach) {
        this.agentLoops = agentLoops;
        this.forEach = forEach;
        this.json = json;
        this.dispatcher = dispatcher;
        this.runs = runs;
        this.events = events;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void execute(DelegateExecution execution) {
        // TX-6: job transactions allow 60 s statements (the HTTP call itself is not a statement).
        jdbc.sql("SET LOCAL statement_timeout = '60s'").update();

        FlowElement element = execution.getCurrentFlowElement();
        ExtensionElement http = OrqixModel.requireExtension(element, "http");
        String connection = OrqixModel.attribute(http, "connection");
        int connectionVersion = Integer.parseInt(OrqixModel.attribute(http, "connectionVersion"));
        String operation = OrqixModel.attribute(http, "operation");
        int timeoutSeconds = Integer.parseInt(OrqixModel.attribute(http, "timeoutSeconds"));
        Map<String, String> inputs = OrqixModel.childMap(http, "input", "fieldRef");
        Map<String, String> results = OrqixModel.childMap(http, "result", "pointer");
        // Opt-in (S71): a known failure becomes this business error for the error route of the call's section.
        String raise = OrqixModel.attribute(http, "raiseOnFailure");

        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        // S138: the visit of the step inside a bounded loop, and the item of a for-each step, give each call its own
        // effect key, so a later visit or another item is a new call while a retry reuses the recorded one.
        int occurrence = ProcessData.occurrence(execution, element.getId());
        if (execution.getVariable(ProcessData.FOR_EACH_BATCH) != null
                && OrqixForEach.isForEach(execution.getProcessDefinitionId(), element.getId())) {
            batch(execution, element, connection, connectionVersion, operation, timeoutSeconds, inputs, results, raise,
                    workspaceId, runId, occurrence);
            return;
        }
        String effectNode = ProcessData.effectNode(execution, json, element.getId());
        var item = ProcessData.item(execution, json);
        Map<String, JsonNode> values = new LinkedHashMap<>();
        inputs.forEach((name, fieldRef) -> {
            JsonNode value = ProcessData.resolve(execution, json, fieldRef);
            if (value.isMissingNode() || value.isNull()) {
                throw new IllegalStateException("Input " + fieldRef + " is not available; the compiler guarantees it");
            }
            values.put(name, value);
        });

        // A previous job can crash after the independent journal transaction committed, but before the engine
        // committed its failure route. Reuse that exact known result rather than resending a rejected call.
        EffectDispatcher.Failed recorded = recordedFailure(workspaceId, runId, effectNode, occurrence, connection,
                connectionVersion, operation, json.canonicalSha256(dispatcher.body(values)));
        String recordedError = recorded == null ? null
                : routeKnownFailure(execution, element, recorded, workspaceId, runId, connection, operation, raise,
                        effectNode, occurrence);
        if (recordedError != null)
            throw new BpmnError(recordedError);
        extendJobLock(execution, dispatcher.leaseSeconds(workspaceId, connection, connectionVersion, operation,
                timeoutSeconds));
        EffectDispatcher.DispatchRequest dispatchRequest = new EffectDispatcher.DispatchRequest(workspaceId,
                runId, effectNode, occurrence, connection, connectionVersion, operation, dispatcher.body(values),
                results, timeoutSeconds, properties.runtime().lockOwner(), ProcessData.initiator(execution));
        // CG-M03 (S135): a durable agent runs one turn per job execution; between turns the job is deferred.
        EffectDispatcher.DispatchResult result = agentLoops.durable(workspaceId, connection, connectionVersion, operation)
                ? agentLoops.turn(dispatchRequest) : dispatcher.dispatch(dispatchRequest);

        if (result instanceof EffectDispatcher.Succeeded success) {
            if (!success.reused()) {
                metrics.effectAttempt(success.effectClass(), "succeeded");
            }
            if (!forEach.record(execution, element.getId(), success.result(), true)) {
                execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(success.result()));
            }
            runs.mutate(workspaceId, runId, m -> {
                m.removeStep(element.getId());
                m.settle();
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connectionKey", connection);
            data.put("operation", operation);
            data.put("attempt", success.attempt());
            data.put("httpStatus", success.httpStatus());
            data.put("remoteReference", success.remoteReference());
            if (success.reused()) {
                data.put("reusedRecordedResult", true);
            }
            if (occurrence > 1) {
                data.put("occurrence", occurrence);
            }
            item.ifPresent(i -> data.put("itemIndex", i.index()));
            if (success.summary() != null) {
                data.put("summary", success.summary());
            }
            if (success.meta() != null) {
                data.put("agent", success.meta());
            }
            if (success.facts() != null) {
                // v1 (S130): pages read, lists cut at the bound, automatic lookup reconciliation.
                success.facts().properties().forEach(e -> data.put(e.getKey(), e.getValue()));
            }
            events.append(workspaceId, runId, "EFFECT_SUCCEEDED", element.getId(), null, data);
            return;
        }
        EffectDispatcher.Failed failed = (EffectDispatcher.Failed) result;
        metrics.effectAttempt(failed.effectClass(), failed.classification());
        String error = routeKnownFailure(execution, element, failed, workspaceId, runId, connection, operation, raise,
                effectNode, occurrence);
        if (error != null) {
            // The engine catches this business error at the generated interrupting boundary (the step's failure path,
            // or with the S71 opt-in its section's error route) in the same transaction. Journal completion is already
            // durable; no failed-job retry or dead-letter transition is requested.
            throw new BpmnError(error);
        }
        throw new JobFailure(failed.retryable(), failed.attentionReason(), failed.errorCode(), failed.classification(),
                failed.httpStatus(), failed.attempt(), failed.effectId(), failed.businessMessage())
                .withRetryAfter(failed.retryAfter());
    }

    /**
     * One batch of a parallel for-each call (CG-E06, S138): the items of the batch are sent at the same time, each
     * through the effect journal under its own key ({@code <step>#i<position>}, this visit's occurrence), and their
     * results are recorded in this job's transaction. When an item fails, the first failure (in item order) decides
     * the job: a routed known failure takes the step's failed path, anything else fails the job, and its retry reuses
     * every recorded success, so no item is ever sent twice.
     */
    private void batch(DelegateExecution execution, FlowElement element, String connection, int connectionVersion,
            String operation, int timeoutSeconds, Map<String, String> inputs, Map<String, String> results, String raise,
            UUID workspaceId, UUID runId, int occurrence) {
        if (agentLoops.durable(workspaceId, connection, connectionVersion, operation)) {
            throw new IllegalStateException("A durable agent operation cannot run for several items in parallel");
        }
        String nodeId = element.getId();
        UUID initiator = ProcessData.initiator(execution);
        java.util.List<ProcessData.Item> items = forEach.batchItems(execution, nodeId);
        record Prepared(ProcessData.Item item, String effectNode, Map<String, JsonNode> values, String hash) {
        }
        java.util.List<Prepared> prepared = new java.util.ArrayList<>();
        for (ProcessData.Item item : items) {
            Map<String, JsonNode> values = new LinkedHashMap<>();
            inputs.forEach((name, fieldRef) -> {
                JsonNode value = ProcessData.resolve(execution, json, fieldRef, item);
                if (value.isMissingNode() || value.isNull()) {
                    throw new IllegalStateException("Input " + fieldRef + " is not available; the compiler guarantees it");
                }
                values.put(name, value);
            });
            prepared.add(new Prepared(item, nodeId + "#i" + item.index(), values,
                    json.canonicalSha256(dispatcher.body(values))));
        }
        extendJobLock(execution, dispatcher.leaseSeconds(workspaceId, connection, connectionVersion, operation,
                timeoutSeconds) * 2L);
        java.util.List<java.util.concurrent.Callable<EffectDispatcher.DispatchResult>> calls = new java.util.ArrayList<>();
        for (Prepared p : prepared) {
            EffectDispatcher.Failed recorded = recordedFailure(workspaceId, runId, p.effectNode(), occurrence, connection,
                    connectionVersion, operation, p.hash());
            if (recorded != null) {
                calls.add(() -> recorded);
                continue;
            }
            EffectDispatcher.DispatchRequest request = new EffectDispatcher.DispatchRequest(workspaceId, runId,
                    p.effectNode(), occurrence, connection, connectionVersion, operation, dispatcher.body(p.values()),
                    results, timeoutSeconds, properties.runtime().lockOwner(), initiator);
            calls.add(() -> dispatcher.dispatch(request));
        }
        java.util.List<EffectDispatcher.DispatchResult> outcomes = forEach.concurrently(calls);
        Prepared failedItem = null;
        EffectDispatcher.Failed failure = null;
        for (int i = 0; i < prepared.size(); i++) {
            Prepared p = prepared.get(i);
            if (outcomes.get(i) instanceof EffectDispatcher.Succeeded success) {
                if (!success.reused()) {
                    metrics.effectAttempt(success.effectClass(), "succeeded");
                }
                forEach.recordItem(execution, nodeId, occurrence, p.item().index(), success.result(), true);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("connectionKey", connection);
                data.put("operation", operation);
                data.put("attempt", success.attempt());
                data.put("httpStatus", success.httpStatus());
                data.put("remoteReference", success.remoteReference());
                data.put("itemIndex", p.item().index());
                if (occurrence > 1) {
                    data.put("occurrence", occurrence);
                }
                if (success.reused()) {
                    data.put("reusedRecordedResult", true);
                }
                events.append(workspaceId, runId, "EFFECT_SUCCEEDED", nodeId, null, data);
            } else if (failure == null) {
                failure = (EffectDispatcher.Failed) outcomes.get(i);
                failedItem = p;
            }
        }
        if (failure == null) {
            runs.mutate(workspaceId, runId, m -> m.settle());
            return;
        }
        metrics.effectAttempt(failure.effectClass(), failure.classification());
        String error = routeKnownFailure(execution, element, failure, workspaceId, runId, connection, operation, raise,
                failedItem.effectNode(), occurrence);
        if (error != null) {
            throw new BpmnError(error);
        }
        throw new JobFailure(failure.retryable(), failure.attentionReason(), failure.errorCode(),
                failure.classification(), failure.httpStatus(), failure.attempt(), failure.effectId(),
                failure.businessMessage()).withRetryAfter(failure.retryAfter());
    }

    /**
     * @return the BPMN error code to throw for a known permanent failure the workflow routes ({@code ORQIX_STEP_FAILED}
     *         for the step's own failure path, or the S71 opt-in code for its section's error route), or null when the
     *         failure is not routed and becomes an ordinary job failure
     */
    private String routeKnownFailure(DelegateExecution execution, FlowElement element, EffectDispatcher.Failed failed,
            UUID workspaceId, UUID runId, String connection, String operation, String raise, String effectNode,
            int occurrence) {
        if (failed.retryable() || !"permanent".equals(failed.classification()) || failed.effectId() == null) return null;
        boolean rejected = "EFFECT_REJECTED".equals(failed.attentionReason()) && knownRejection(failed.httpStatus(), failed.errorCode());
        boolean invalidRead = "RESPONSE_INVALID".equals(failed.attentionReason()) && knownInvalidRead(failed.effectClass(), failed.errorCode());
        if (!rejected && !invalidRead) return null;
        var candidate = OrqixModel.model(execution.getProcessDefinitionId()).getFlowElement(element.getId() + "__failed");
        // S138: a parallel for-each in batches has its failure boundary on the batches subprocess.
        boolean failurePath = candidate instanceof BoundaryEvent boundary && (element.getId().equals(boundary.getAttachedToRefId())
                        || (element.getId() + "__batches").equals(boundary.getAttachedToRefId()))
                && boundary.getEventDefinitions().stream().anyMatch(definition -> definition instanceof ErrorEventDefinition error
                        && ("error__stepFailed".equals(error.getErrorCode()) || "ORQIX_STEP_FAILED".equals(error.getErrorCode())));
        // The compiler never lets a call have both; the step's own failure path wins if a hand-made model did.
        String code = failurePath ? "ORQIX_STEP_FAILED" : raise;
        if (code == null) return null;
        // Never route an uncertain effect or a policy/configuration failure merely because its result was classified.
        // The stable effect id must identify this exact run/node/occurrence and a completed permanent journal result.
        boolean recorded = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM effect_journal WHERE id=:id AND workspace_id=:ws AND run_id=:run
                  AND node_id=:node AND occurrence=:occurrence AND state='permanentFailure')
                """).param("id", failed.effectId()).param("ws", workspaceId).param("run", runId)
                .param("node", effectNode).param("occurrence", occurrence).query(Boolean.class).single();
        if (!recorded) return null;
        String kind = rejected ? "rejected" : "invalidResponse";
        // A v1 connection maps known client errors to business codes (S130); the code is a failure fact.
        String failureCode = rejected && failed.errorCode() != null && !failed.errorCode().startsWith("HTTP_")
                ? failed.errorCode() : "";
        String message = failed.failureMessage() != null ? failed.failureMessage()
                : rejected ? "The external system rejected this request." : "The read-only system returned an invalid response.";
        execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(json.object()
                .put("failureKind", kind).put("failureHttpStatus", failed.httpStatus() == null ? 0 : failed.httpStatus())
                .put("failureMessage", message).put("failureCode", failureCode)));
        runs.mutate(workspaceId, runId, m -> { m.removeStep(element.getId()); m.settle(); });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connectionKey", connection); data.put("operation", operation); data.put("attempt", failed.attempt());
        data.put("httpStatus", failed.httpStatus()); data.put("classification", failed.classification());
        data.put("errorCode", failed.errorCode()); data.put("failureKind", kind); data.put("routed", true);
        if (!failureCode.isEmpty()) {
            data.put("failureCode", failureCode);
        }
        if (!failurePath) {
            data.put("raisedError", code);
        }
        events.append(workspaceId, runId, "EFFECT_FAILED", element.getId(), null, data);
        if (failurePath) {
            events.append(workspaceId, runId, "STEP_FAILED", element.getId(), null,
                    Map.of("outcome", "failed", "failureKind", kind, "effectId", failed.effectId().toString()));
            return code;
        }
        // The section's error boundary cancels this call's activity in the same command; it already shows as having
        // raised the error, not as interrupted.
        RuntimeProjectionListener.markInterrupted(execution.getProcessInstanceId(), element.getId());
        events.append(workspaceId, runId, "ERROR_RAISED", element.getId(), null, Map.of("label",
                element.getName() == null ? element.getId() : element.getName(), "errorCode", code, "source",
                "callFailure", "failureKind", kind, "effectId", failed.effectId().toString()));
        return code;
    }

    private static boolean knownRejection(Integer status, String code) {
        return status != null && status >= 400 && status < 500 && status != 401 && status != 403 && status != 408
                && status != 429 && (("HTTP_" + status).equals(code) || mappedCode(code));
    }

    /** A business code from a v1 connection's error mapping (S130); platform codes never look like this on a 4xx. */
    private static boolean mappedCode(String code) {
        return code != null && code.matches("^[A-Z][A-Z0-9_]{1,63}$") && !code.startsWith("HTTP_")
                && !java.util.Set.of("UNEXPECTED_STATUS", "RESPONSE_INVALID", "RESPONSE_TOO_LARGE", "INVALID_REQUEST")
                        .contains(code);
    }

    /**
     * Long operations (up to 120 s), paginated reads and lookup reconciliation can need more time than the async job
     * lock. The job row is re-locked inside this job transaction, so no other executor takes the job while the call
     * runs (S130). Calls that fit in the lock change nothing.
     */
    private void extendJobLock(DelegateExecution execution, long seconds) {
        long lock = properties.runtime().asyncJobLockTime().toSeconds();
        if (seconds + 15 <= lock) {
            return;
        }
        jdbc.sql("""
                UPDATE act_ru_job SET lock_exp_time_ = now() + make_interval(secs => :seconds)
                WHERE execution_id_ = :execution AND lock_owner_ = :owner
                """).param("seconds", seconds + 30).param("execution", execution.getId())
                .param("owner", properties.runtime().lockOwner()).update();
    }

    private static boolean knownInvalidRead(String effectClass, String code) {
        return "readOnly".equals(effectClass) && code != null
                && java.util.Set.of("RESPONSE_INVALID", "RESPONSE_TOO_LARGE", "UNEXPECTED_STATUS").contains(code);
    }

    private EffectDispatcher.Failed recordedFailure(UUID workspaceId, UUID runId, String node, int occurrence, String connection,
            int connectionVersion, String operation, String requestHash) {
        return jdbc.sql("""
                SELECT id, attempts, last_http_status, last_error_code, effect_class
                FROM effect_journal WHERE workspace_id=:ws AND run_id=:run AND node_id=:node AND occurrence=:occurrence
                  AND connection_key=:connection AND connection_version=:version AND operation=:operation
                  AND request_hash=:hash AND state='permanentFailure'
                """).param("ws", workspaceId).param("run", runId).param("node", node).param("occurrence", occurrence)
                .param("connection", connection).param("version", connectionVersion).param("operation", operation).param("hash", requestHash)
                .query((rs, index) -> {
                    Integer status = rs.getObject("last_http_status", Integer.class); String code = rs.getString("last_error_code");
                    String effectClass = rs.getString("effect_class");
                    String reason = knownRejection(status, code) ? "EFFECT_REJECTED"
                            : knownInvalidRead(effectClass, code)
                            ? "RESPONSE_INVALID" : null;
                    return reason == null ? null : new EffectDispatcher.Failed(rs.getObject("id", UUID.class), rs.getInt("attempts"), false,
                            reason, code, "permanent", status, effectClass, "Recorded known failure");
                }).optional().orElse(null);
    }
}
