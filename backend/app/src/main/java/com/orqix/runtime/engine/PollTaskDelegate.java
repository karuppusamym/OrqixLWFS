package com.orqix.runtime.engine;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.PollWaits;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delegate {@code orqixPollTask} of a "Call a system" step that keeps checking until done (docs/build/S129, CG-W01).
 * The service task is asynchronous and triggerable: this delegate resolves the request values once, registers the
 * {@code poll_wait} row in the job transaction and returns, so the engine waits at the step. The poll scheduler does
 * the checks and triggers the execution with the outcome ({@code met}, {@code failed} or {@code timedOut}).
 */
@Component("orqixPollTask")
public class PollTaskDelegate implements JavaDelegate {

    private final PollWaits polls;
    private final Json json;

    public PollTaskDelegate(@org.springframework.context.annotation.Lazy PollWaits polls, Json json) {
        this.polls = polls;
        this.json = json;
    }

    @Override
    public void execute(DelegateExecution execution) {
        var http = OrqixModel.requireExtension(execution.getCurrentFlowElement(), "http");
        String nodeId = execution.getCurrentActivityId();
        ObjectNode request = json.object();
        OrqixModel.childMap(http, "input", "fieldRef").forEach((name, ref) -> {
            JsonNode value = ProcessData.resolve(execution, json, ref);
            if (value.isMissingNode() || value.isNull()) {
                throw new IllegalStateException("Input " + ref + " is not available; the compiler guarantees it");
            }
            request.set(name, value);
        });
        polls.startCheck(ProcessData.workspaceId(execution), ProcessData.runId(execution), nodeId,
                OrqixModel.attribute(http, "connection"), Integer.parseInt(OrqixModel.attribute(http, "connectionVersion")),
                OrqixModel.attribute(http, "operation"), Integer.parseInt(OrqixModel.attribute(http, "timeoutSeconds")),
                request, ref -> ProcessData.resolve(execution, json, ref));
    }
}
