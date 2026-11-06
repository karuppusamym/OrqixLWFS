package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.AiDecisions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** AI decision records of a run (contracts/openapi.yaml tag AiSkills; docs/build/S134 section 6). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/runs/{runId}/ai-decisions")
public class AiDecisionsController {

    private final CallerResolver callers;
    private final AiDecisions decisions;

    public AiDecisionsController(CallerResolver callers, AiDecisions decisions) {
        this.callers = callers;
        this.decisions = decisions;
    }

    @GetMapping
    public ObjectNode listRunAiDecisions(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return decisions.list(callers.current(), workspaceId, runId);
    }

    @PostMapping("/{decisionId}/outcome")
    public ObjectNode recordAiDecisionOutcome(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID decisionId, @RequestBody(required = false) JsonNode body) {
        return decisions.recordOutcome(callers.current(), workspaceId, runId, decisionId, body);
    }
}
