package com.orqix.runtime.engine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.compiler.AdapterMessages;
import com.orqix.integration.AdapterSteps;
import com.orqix.integration.EffectDispatcher;
import com.orqix.runtime.internal.JobFailure;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunStore;
import com.orqix.runtime.internal.RuntimeMetrics;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delegate {@code orqixAdapterTask} (S68): "Send through an adapter". Builds the adapter request only from the step's fixed
 * recipients or topic and its mapped values, then sends it through the effect journal ({@link AdapterSteps}). Success
 * stores {@code outcome} (and {@code rowCount} for a database read) as the step's outputs; failures become classified
 * {@link JobFailure}s, so an unknown outcome or a refused message needs an operator and is never resent blindly.
 */
@Component("orqixAdapterTask")
public class AdapterTaskDelegate implements JavaDelegate {

    /** Acyclic graphs: every node runs at most once per run (S2 section 6). */
    static final int OCCURRENCE = 1;

    private final Json json;
    private final AdapterSteps adapters;
    private final RunStore runs;
    private final RunEvents events;
    private final RuntimeMetrics metrics;
    private final JdbcClient jdbc;
    private final OrqixProperties properties;

    public AdapterTaskDelegate(Json json, AdapterSteps adapters, RunStore runs, RunEvents events, RuntimeMetrics metrics,
            JdbcClient jdbc, OrqixProperties properties) {
        this.json = json;
        this.adapters = adapters;
        this.runs = runs;
        this.events = events;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void execute(DelegateExecution execution) {
        jdbc.sql("SET LOCAL statement_timeout = '60s'").update();
        FlowElement element = execution.getCurrentFlowElement();
        ExtensionElement config = OrqixModel.requireExtension(element, "adapter");
        String adapterKey = OrqixModel.attribute(config, "adapter");
        String kind = OrqixModel.attribute(config, "kind");
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);

        Map<String, JsonNode> values = new LinkedHashMap<>();
        OrqixModel.childMap(config, "input", "fieldRef").forEach((name, ref) -> {
            JsonNode value = ProcessData.resolve(execution, json, ref);
            if (value.isMissingNode() || value.isNull()) {
                throw new IllegalStateException("Input " + ref + " is not available; the compiler guarantees it");
            }
            values.put(name, value);
        });
        ObjectNode payload = payload(config, kind, values);

        EffectDispatcher.DispatchResult result = adapters.send(new AdapterSteps.Send(workspaceId, runId, element.getId(),
                OCCURRENCE, adapterKey, kind, payload, properties.runtime().lockOwner()));
        if (result instanceof EffectDispatcher.Succeeded success) {
            if (!success.reused()) {
                metrics.effectAttempt(success.effectClass(), "succeeded");
            }
            JsonNode stored = success.result() == null ? json.object() : success.result();
            ObjectNode outputs = json.object();
            // An operator who confirmed an unknown send records no adapter outcome of its own.
            outputs.put("outcome", stored.path("outcome").asString("confirmed"));
            if ("jdbc".equals(kind)) {
                outputs.put("rowCount", stored.path("detail").path("rowCount").asInt(0));
            }
            execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(outputs));
            runs.mutate(workspaceId, runId, m -> {
                m.removeStep(element.getId());
                m.settle();
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("adapterKey", adapterKey);
            data.put("adapterKind", kind);
            data.put("attempt", success.attempt());
            data.put("outcome", outputs.path("outcome").asString());
            if (success.reused()) {
                data.put("reusedRecordedResult", true);
            }
            events.append(workspaceId, runId, "EFFECT_SUCCEEDED", element.getId(), null, data);
            return;
        }
        EffectDispatcher.Failed failed = (EffectDispatcher.Failed) result;
        metrics.effectAttempt(failed.effectClass(), failed.classification());
        throw new JobFailure(failed.retryable(), failed.attentionReason(), failed.errorCode(), failed.classification(),
                null, failed.attempt(), failed.effectId(), failed.businessMessage());
    }

    /** The S47 request shape of the kind. Line breaks in mapped values never reach a single-line member. */
    ObjectNode payload(ExtensionElement config, String kind, Map<String, JsonNode> values) {
        Map<String, String> text = new LinkedHashMap<>();
        Map<String, String> line = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            String plain = value.isString() ? value.asString() : value.toString();
            text.put(name, plain);
            line.put(name, plain.replace('\r', ' ').replace('\n', ' '));
        });
        ObjectNode payload = json.object();
        switch (kind) {
            case "email" -> {
                var to = payload.putArray("to");
                OrqixModel.children(config, "to").forEach(child -> to.add(OrqixModel.attribute(child, "address")));
                payload.put("subject", AdapterMessages.render(decoded(config, "subject"), line));
                payload.put("body", AdapterMessages.render(decoded(config, "body"), text));
            }
            case "teams" -> {
                String title = decoded(config, "title");
                if (title != null) {
                    payload.put("title", AdapterMessages.render(title, line));
                }
                payload.put("text", AdapterMessages.render(decoded(config, "text"), text));
            }
            case "kafka" -> {
                payload.put("topic", OrqixModel.attribute(config, "topic"));
                String key = decoded(config, "key");
                if (key != null) {
                    payload.put("key", AdapterMessages.render(key, line));
                }
                ObjectNode value = payload.putObject("value");
                values.forEach(value::set);
            }
            case "jdbc" -> {
                ObjectNode parameters = payload.putObject("parameters");
                values.forEach(parameters::set);
            }
            default -> throw new IllegalStateException("Unknown adapter kind " + kind);
        }
        return payload;
    }

    private static String decoded(ExtensionElement config, String member) {
        String encoded = OrqixModel.attribute(config, member + "Base64");
        return encoded == null ? null : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
