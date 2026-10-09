package com.orqix.runtime.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
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

        EffectDispatcher.DispatchResult result = dispatcher.dispatch(new EffectDispatcher.DispatchRequest(workspaceId,
                runId, element.getId(), OCCURRENCE, connection, connectionVersion, operation, dispatcher.body(values),
                results, timeoutSeconds, properties.runtime().lockOwner()));

        if (result instanceof EffectDispatcher.Succeeded success) {
            if (!success.reused()) {
                metrics.effectAttempt(success.effectClass(), "succeeded");
            }
            execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(success.result()));
            runs.mutate(workspaceId, runId, m -> m.removeStep(element.getId()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connectionKey", connection);
            data.put("operation", operation);
            data.put("attempt", success.attempt());
            data.put("httpStatus", success.httpStatus());
            data.put("remoteReference", success.remoteReference());
            if (success.reused()) {
                data.put("reusedRecordedResult", true);
            }
            events.append(workspaceId, runId, "EFFECT_SUCCEEDED", element.getId(), null, data);
            return;
        }
        EffectDispatcher.Failed failed = (EffectDispatcher.Failed) result;
        metrics.effectAttempt(failed.effectClass(), failed.classification());
        throw new JobFailure(failed.retryable(), failed.attentionReason(), failed.errorCode(), failed.classification(),
                failed.httpStatus(), failed.attempt(), failed.effectId(), failed.businessMessage());
    }
}
