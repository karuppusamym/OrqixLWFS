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

    /** Acyclic R0 graphs: every node runs at most once per run (S2 section 6). */
    static final int OCCURRENCE = 1;

    private final Json json;
    private final EffectDispatcher dispatcher;
    private final RunStore runs;
    private final RunEvents events;
    private final RuntimeMetrics metrics;
    private final JdbcClient jdbc;
    private final OrqixProperties properties;

    public HttpTaskDelegate(Json json, EffectDispatcher dispatcher, RunStore runs, RunEvents events,
            RuntimeMetrics metrics, JdbcClient jdbc, OrqixProperties properties) {
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

        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
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
        EffectDispatcher.Failed recorded = recordedFailure(workspaceId, runId, element.getId(), connection,
                connectionVersion, operation, json.canonicalSha256(dispatcher.body(values)));
        if (recorded != null && routeKnownFailure(execution, element, recorded, workspaceId, runId, connection, operation))
            throw new BpmnError("ORQIX_STEP_FAILED");
        EffectDispatcher.DispatchResult result = dispatcher.dispatch(new EffectDispatcher.DispatchRequest(workspaceId,
                runId, element.getId(), OCCURRENCE, connection, connectionVersion, operation, dispatcher.body(values),
                results, timeoutSeconds, properties.runtime().lockOwner(), ProcessData.initiator(execution)));

        if (result instanceof EffectDispatcher.Succeeded success) {
            if (!success.reused()) {
                metrics.effectAttempt(success.effectClass(), "succeeded");
            }
            execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(success.result()));
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
            if (success.summary() != null) {
                data.put("summary", success.summary());
            }
            if (success.meta() != null) {
                data.put("agent", success.meta());
            }
            events.append(workspaceId, runId, "EFFECT_SUCCEEDED", element.getId(), null, data);
            return;
        }
        EffectDispatcher.Failed failed = (EffectDispatcher.Failed) result;
        metrics.effectAttempt(failed.effectClass(), failed.classification());
        if (routeKnownFailure(execution, element, failed, workspaceId, runId, connection, operation)) {
            // The engine catches this business error at the generated interrupting boundary in the same transaction.
            // Journal completion is already durable; no failed-job retry or dead-letter transition is requested.
            throw new BpmnError("ORQIX_STEP_FAILED");
        }
        throw new JobFailure(failed.retryable(), failed.attentionReason(), failed.errorCode(), failed.classification(),
                failed.httpStatus(), failed.attempt(), failed.effectId(), failed.businessMessage());
    }

    private boolean routeKnownFailure(DelegateExecution execution, FlowElement element, EffectDispatcher.Failed failed,
            UUID workspaceId, UUID runId, String connection, String operation) {
        if (failed.retryable() || !"permanent".equals(failed.classification()) || failed.effectId() == null) return false;
        boolean rejected = "EFFECT_REJECTED".equals(failed.attentionReason()) && knownRejection(failed.httpStatus(), failed.errorCode());
        boolean invalidRead = "RESPONSE_INVALID".equals(failed.attentionReason()) && knownInvalidRead(failed.effectClass(), failed.errorCode());
        if (!rejected && !invalidRead) return false;
        var candidate = OrqixModel.model(execution.getProcessDefinitionId()).getFlowElement(element.getId() + "__failed");
        if (!(candidate instanceof BoundaryEvent boundary) || !element.getId().equals(boundary.getAttachedToRefId())
                || boundary.getEventDefinitions().stream().noneMatch(definition -> definition instanceof ErrorEventDefinition error
                        && ("error__stepFailed".equals(error.getErrorCode()) || "ORQIX_STEP_FAILED".equals(error.getErrorCode())))) return false;
        // Never route an uncertain effect or a policy/configuration failure merely because its result was classified.
        // The stable effect id must identify this exact run/node/occurrence and a completed permanent journal result.
        boolean recorded = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM effect_journal WHERE id=:id AND workspace_id=:ws AND run_id=:run
                  AND node_id=:node AND occurrence=:occurrence AND state='permanentFailure')
                """).param("id", failed.effectId()).param("ws", workspaceId).param("run", runId)
                .param("node", element.getId()).param("occurrence", OCCURRENCE).query(Boolean.class).single();
        if (!recorded) return false;
        String kind = rejected ? "rejected" : "invalidResponse";
        String message = rejected ? "The external system rejected this request." : "The read-only system returned an invalid response.";
        execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(json.object()
                .put("failureKind", kind).put("failureHttpStatus", failed.httpStatus() == null ? 0 : failed.httpStatus())
                .put("failureMessage", message)));
        runs.mutate(workspaceId, runId, m -> { m.removeStep(element.getId()); m.settle(); });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connectionKey", connection); data.put("operation", operation); data.put("attempt", failed.attempt());
        data.put("httpStatus", failed.httpStatus()); data.put("classification", failed.classification());
        data.put("errorCode", failed.errorCode()); data.put("failureKind", kind); data.put("routed", true);
        events.append(workspaceId, runId, "EFFECT_FAILED", element.getId(), null, data);
        events.append(workspaceId, runId, "STEP_FAILED", element.getId(), null,
                Map.of("outcome", "failed", "failureKind", kind, "effectId", failed.effectId().toString()));
        return true;
    }

    private static boolean knownRejection(Integer status, String code) {
        return status != null && status >= 400 && status < 500 && status != 401 && status != 403 && status != 408
                && status != 429 && ("HTTP_" + status).equals(code);
    }

    private static boolean knownInvalidRead(String effectClass, String code) {
        return "readOnly".equals(effectClass) && code != null
                && java.util.Set.of("RESPONSE_INVALID", "RESPONSE_TOO_LARGE", "UNEXPECTED_STATUS").contains(code);
    }

    private EffectDispatcher.Failed recordedFailure(UUID workspaceId, UUID runId, String node, String connection,
            int connectionVersion, String operation, String requestHash) {
        return jdbc.sql("""
                SELECT id, attempts, last_http_status, last_error_code, effect_class
                FROM effect_journal WHERE workspace_id=:ws AND run_id=:run AND node_id=:node AND occurrence=:occurrence
                  AND connection_key=:connection AND connection_version=:version AND operation=:operation
                  AND request_hash=:hash AND state='permanentFailure'
                """).param("ws", workspaceId).param("run", runId).param("node", node).param("occurrence", OCCURRENCE)
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
