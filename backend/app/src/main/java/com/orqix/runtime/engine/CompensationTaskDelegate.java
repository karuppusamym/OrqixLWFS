package com.orqix.runtime.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.integration.EffectDispatcher;
import com.orqix.integration.EffectJournal;
import com.orqix.runtime.internal.JobFailure;
import com.orqix.runtime.internal.RunCompensations;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunStore;
import com.orqix.runtime.internal.RuntimeMetrics;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Delegate {@code orqixCompensationTask} of an "Undo completed calls" step (docs/build/S55), executed by the async
 * executor inside a job transaction.
 * <ol>
 * <li>The first time, it fixes the plan: every call named by an {@code orqix:undo} entry whose effect journal entry in
 * this request succeeded, most recently completed first. Calls that failed, were never reached or whose outcome is
 * unknown are not undone.</li>
 * <li>It then runs the automatic undo call of each planned call, one after the other, through the same journaled
 * dispatcher as any call step (so an undo call that already succeeded is reused, never sent again).</li>
 * <li>A failing undo call becomes a normal job failure: retries, then the request needs attention at this step. An
 * operator retry continues the same plan.</li>
 * </ol>
 * Flowable's own compensation events are not used: in Flowable 8 a compensation throw does not wait for its handlers
 * (the request could finish before the undo calls ran) and orders them by subscription time only.
 */
@Component("orqixCompensationTask")
public class CompensationTaskDelegate implements JavaDelegate {

    private final Json json;
    private final EffectDispatcher dispatcher;
    private final EffectJournal journal;
    private final RunCompensations compensations;
    private final RunStore runs;
    private final RunEvents events;
    private final RuntimeMetrics metrics;
    private final JdbcClient jdbc;
    private final OrqixProperties properties;

    public CompensationTaskDelegate(Json json, EffectDispatcher dispatcher, EffectJournal journal,
            RunCompensations compensations, RunStore runs, RunEvents events, RuntimeMetrics metrics, JdbcClient jdbc,
            OrqixProperties properties) {
        this.json = json;
        this.dispatcher = dispatcher;
        this.journal = journal;
        this.compensations = compensations;
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
        ExtensionElement compensation = OrqixModel.requireExtension(element, "compensation");
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        String stepId = element.getId();

        Map<String, String> handlerOf = new LinkedHashMap<>();
        for (ExtensionElement undo : OrqixModel.children(compensation, "undo")) {
            handlerOf.put(OrqixModel.attribute(undo, "target"), OrqixModel.attribute(undo, "handler"));
        }
        List<RunCompensations.Entry> plan = compensations.plan(workspaceId, runId, stepId);
        if (plan.isEmpty()) {
            List<EffectJournal.Effect> completed = new ArrayList<>();
            for (EffectJournal.Effect effect : journal.forRun(workspaceId, runId)) {
                if (handlerOf.containsKey(effect.nodeId()) && effect.occurrence() == 1
                        && EffectJournal.SUCCEEDED.equals(effect.state())) {
                    completed.add(effect);
                }
            }
            completed.sort(Comparator.comparing(EffectJournal.Effect::updatedAt)
                    .thenComparing(EffectJournal.Effect::createdAt).reversed());
            List<RunCompensations.Planned> calls = completed.stream()
                    .map(e -> new RunCompensations.Planned(e.nodeId(), handlerOf.get(e.nodeId()))).toList();
            plan = compensations.fix(workspaceId, runId, stepId, calls).entries();
        }

        for (RunCompensations.Entry entry : plan) {
            if (entry.undone()) {
                continue;
            }
            FlowElement handler = OrqixModel.flowElement(execution.getProcessDefinitionId(), entry.handlerNode());
            ExtensionElement http = OrqixModel.requireExtension(handler, "http");
            String connection = OrqixModel.attribute(http, "connection");
            Map<String, JsonNode> values = new LinkedHashMap<>();
            OrqixModel.childMap(http, "input", "fieldRef").forEach((name, fieldRef) -> {
                JsonNode value = ProcessData.resolve(execution, json, fieldRef);
                if (value.isMissingNode() || value.isNull()) {
                    throw new IllegalStateException("Input " + fieldRef + " of the undo call " + entry.handlerNode()
                            + " is not available; the compiler guarantees it");
                }
                values.put(name, value);
            });
            EffectDispatcher.DispatchResult result = dispatcher.dispatch(new EffectDispatcher.DispatchRequest(
                    workspaceId, runId, entry.handlerNode(), HttpTaskDelegate.OCCURRENCE, connection,
                    Integer.parseInt(OrqixModel.attribute(http, "connectionVersion")),
                    OrqixModel.attribute(http, "operation"), dispatcher.body(values),
                    OrqixModel.childMap(http, "result", "pointer"),
                    Integer.parseInt(OrqixModel.attribute(http, "timeoutSeconds")), properties.runtime().lockOwner(),
                    ProcessData.initiator(execution)));
            if (result instanceof EffectDispatcher.Succeeded success) {
                if (!success.reused()) {
                    metrics.effectAttempt(success.effectClass(), "succeeded");
                }
                execution.setVariable(ProcessData.stepVariable(entry.handlerNode()), json.write(success.result()));
                compensations.undone(workspaceId, runId, stepId, entry.targetNode(), success.effectId());
                continue;
            }
            EffectDispatcher.Failed failed = (EffectDispatcher.Failed) result;
            metrics.effectAttempt(failed.effectClass(), failed.classification());
            throw new JobFailure(failed.retryable(), failed.attentionReason(), failed.errorCode(),
                    failed.classification(), failed.httpStatus(), failed.attempt(), failed.effectId(),
                    failed.businessMessage());
        }

        // Everything planned is undone: record it and continue.
        List<RunCompensations.Entry> done = compensations.plan(workspaceId, runId, stepId);
        List<String> undone = new ArrayList<>();
        for (RunCompensations.Entry entry : done) {
            FlowElement handler = OrqixModel.flowElement(execution.getProcessDefinitionId(), entry.handlerNode());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("label", handler.getName() == null ? entry.handlerNode() : handler.getName());
            data.put("undoes", entry.targetNode());
            data.put("position", entry.position());
            data.put("effectId", entry.effectId() == null ? null : entry.effectId().toString());
            events.append(workspaceId, runId, "UNDO_SUCCEEDED", entry.handlerNode(), null, data);
            undone.add(entry.targetNode());
        }
        runs.mutate(workspaceId, runId, m -> {
            m.removeStep(stepId);
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", element.getName() == null ? stepId : element.getName());
        data.put("undone", undone);
        events.append(workspaceId, runId, "COMPENSATION_COMPLETED", stepId, null, data);
        execution.setVariable(ProcessData.stepVariable(stepId), json.write(json.object().put("undoneCount", undone.size())));
    }
}
