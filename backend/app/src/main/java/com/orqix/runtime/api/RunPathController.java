package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.RunPathBuilder;
import com.orqix.runtime.internal.RunPaths;

/** getRunPath (contracts/openapi.yaml, tag Runs): the diagram view of a run. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/runs/{runId}/path")
public class RunPathController {

    private final CallerResolver callers;
    private final RunPaths paths;

    public RunPathController(CallerResolver callers, RunPaths paths) {
        this.callers = callers;
        this.paths = paths;
    }

    @GetMapping
    public RunPathBuilder.Result path(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return paths.path(callers.current(), workspaceId, runId);
    }
}
