package com.orqix.runtime.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.engine.OrqixForEach;
import com.orqix.runtime.internal.RunPaths;

/** getRunStepItems (contracts/openapi.yaml, tag ControlFlow, S138): the item results of a for-each step. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/runs/{runId}/steps/{nodeId}/items")
public class StepItemsController {

    private final CallerResolver callers;
    private final RunPaths paths;
    private final OrqixForEach forEach;

    public StepItemsController(CallerResolver callers, RunPaths paths, OrqixForEach forEach) {
        this.callers = callers;
        this.paths = paths;
        this.forEach = forEach;
    }

    @GetMapping
    public Map<String, Object> items(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable String nodeId) {
        return paths.items(callers.current(), workspaceId, runId, nodeId, forEach);
    }
}
