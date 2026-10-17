package com.orqix.runtime.engine;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.ChildRuns;
import com.orqix.shared.json.Json;

/** Starts the pinned child once; Flowable's triggerable service task waits for its durable completion job. */
@Component("orqixChildTask")
public class ChildTaskDelegate implements JavaDelegate {
    private final ChildRuns children;
    private final Json json;

    public ChildTaskDelegate(@org.springframework.context.annotation.Lazy ChildRuns children, Json json) {
        this.children = children;
        this.json = json;
    }

    @Override
    public void execute(DelegateExecution execution) {
        var child = OrqixModel.requireExtension(execution.getCurrentFlowElement(), "child");
        var input = json.object();
        OrqixModel.childMap(child, "input", "fieldRef").forEach((name, ref) -> {
            var value = ProcessData.resolve(execution, json, ref);
            if (value.isMissingNode() || value.isNull()) throw new IllegalStateException("Child input is unavailable: " + ref);
            input.set(name, value);
        });
        children.start(ProcessData.workspaceId(execution), ProcessData.runId(execution),
                execution.getCurrentActivityId(), input);
    }
}
