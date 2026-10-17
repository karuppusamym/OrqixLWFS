package com.orqix.runtime.engine;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.ExternalJobService;
import com.orqix.shared.json.Json;

/**
 * Publishes the job of a "Work by an external worker" step (docs/build/S43). The service task is triggerable, so the
 * engine waits at the step until a worker's completion triggers it.
 */
@Component("orqixExternalTask")
public class ExternalTaskDelegate implements JavaDelegate {
    private final ExternalJobService jobs;
    private final Json json;

    public ExternalTaskDelegate(@org.springframework.context.annotation.Lazy ExternalJobService jobs, Json json) {
        this.jobs = jobs;
        this.json = json;
    }

    @Override
    public void execute(DelegateExecution execution) {
        var external = OrqixModel.requireExtension(execution.getCurrentFlowElement(), "external");
        var input = json.object();
        OrqixModel.childMap(external, "input", "fieldRef").forEach((name, ref) -> {
            var value = ProcessData.resolve(execution, json, ref);
            if (value.isMissingNode() || value.isNull()) {
                throw new IllegalStateException("A value for the worker is unavailable: " + ref);
            }
            input.set(name, value);
        });
        jobs.publish(ProcessData.workspaceId(execution), ProcessData.runId(execution), execution.getCurrentActivityId(),
                OrqixModel.attribute(external, "topic"), input,
                Integer.parseInt(OrqixModel.attribute(external, "leaseSeconds")),
                Integer.parseInt(OrqixModel.attribute(external, "maxAttempts")),
                Integer.parseInt(OrqixModel.attribute(external, "retryDelaySeconds")));
    }
}
