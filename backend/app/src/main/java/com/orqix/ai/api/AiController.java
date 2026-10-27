package com.orqix.ai.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.ai.internal.AiService;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.idempotency.IdempotencyService;

import tools.jackson.databind.JsonNode;

/** AI assistance (contracts/openapi.yaml tag AI; docs/build/S10-ai-assistance.md). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AiController {

    private final CallerResolver callers;
    private final AiService ai;

    public AiController(CallerResolver callers, AiService ai) {
        this.callers = callers;
        this.ai = ai;
    }

    @GetMapping("/ai/status")
    public AiDtos.Status getAiStatus(@PathVariable UUID workspaceId) {
        return ai.status(callers.current(), workspaceId);
    }

    @PostMapping("/ai/drafts")
    public ResponseEntity<AiDtos.Job> generateAiDraft(@PathVariable UUID workspaceId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body) {
        AiService.Accepted accepted = ai.generateDraft(callers.current(), workspaceId, idempotencyKey, body);
        if (accepted.replayed()) {
            return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, "true").body(accepted.job());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/workspaces/" + workspaceId + "/ai/generations/" + accepted.job().id()))
                .body(accepted.job());
    }

    @GetMapping("/ai/generations/{generationId}")
    public AiDtos.Job getAiGeneration(@PathVariable UUID workspaceId, @PathVariable UUID generationId) {
        return ai.getGeneration(callers.current(), workspaceId, generationId);
    }

    @PostMapping("/workflows/{workflowId}/ai/form-suggestions")
    public AiDtos.FormSuggestion suggestFormChanges(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestBody(required = false) JsonNode body) {
        return ai.suggestForm(callers.current(), workspaceId, workflowId, body);
    }

    @PostMapping("/workflows/{workflowId}/ai/draft-edits")
    public AiDtos.DraftEditProposal proposeDraftEdits(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestBody(required = false) JsonNode body) {
        return ai.proposeDraftEdits(callers.current(), workspaceId, workflowId, body);
    }

    @PostMapping("/workflows/{workflowId}/explain")
    public AiDtos.Explanation explainWorkflow(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestBody(required = false) JsonNode body) {
        return ai.explain(callers.current(), workspaceId, workflowId, body);
    }
}
