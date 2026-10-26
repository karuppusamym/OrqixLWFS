package com.orqix.catalog.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.DiscussionService;
import com.orqix.identity.CallerResolver;

/** Threaded discussion on workflow steps and connections (UI03, docs/build/S53). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/workflows/{workflowId}/discussion")
public class DiscussionController {

    private final DiscussionService service;
    private final CallerResolver callers;

    public DiscussionController(DiscussionService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping
    public DiscussionService.Discussion list(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestParam(required = false) String elementId) {
        return service.list(callers.current(), workspaceId, workflowId, elementId);
    }

    /** CMP01 (docs/build/S87): people who can be mentioned on this workflow (they can read it in this workspace). */
    @GetMapping("/mention-candidates")
    public DiscussionService.Candidates mentionCandidates(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestParam(required = false) String q) {
        return service.mentionCandidates(callers.current(), workspaceId, workflowId, q);
    }

    @PostMapping
    public ResponseEntity<DiscussionService.Comment> post(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody DiscussionService.PostRequest request) {
        DiscussionService.Comment created = service.post(callers.current(), workspaceId, workflowId, request);
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/workflows/" + workflowId
                + "/discussion/" + created.id())).body(created);
    }

    @PutMapping("/{commentId}/resolution")
    public DiscussionService.Comment resolve(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @PathVariable UUID commentId, @Valid @RequestBody DiscussionService.ResolveRequest request) {
        return service.resolve(callers.current(), workspaceId, workflowId, commentId, request);
    }
}
