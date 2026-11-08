package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.explain.RunExplainService;

/**
 * "Explain this request" (CG-A07, tag Completion). A POST because a model call is metered and audited, but it never
 * changes the request: no idempotency key, no expected version. The body is optional so that access is decided first.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class RunExplainController {

    private final CallerResolver callers;
    private final RunExplainService explainer;

    public RunExplainController(CallerResolver callers, RunExplainService explainer) {
        this.callers = callers;
        this.explainer = explainer;
    }

    @PostMapping("/runs/{runId}/explain")
    public ExplainDtos.RunExplanation explainRun(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestBody(required = false) ExplainDtos.ExplainRunRequest request) {
        return explainer.explain(callers.current(), workspaceId, runId, request);
    }
}
