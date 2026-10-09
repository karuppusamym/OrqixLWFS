package com.orqix.catalog.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.ReleaseService;
import com.orqix.catalog.internal.WorkflowService;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.idempotency.IdempotencyService;

/** Workflows, drafts, templates, releases, export and start form (contracts/openapi.yaml). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class CatalogController {

    private final CallerResolver callers;
    private final WorkflowService workflows;
    private final ReleaseService releases;

    public CatalogController(CallerResolver callers, WorkflowService workflows, ReleaseService releases) {
        this.callers = callers;
        this.workflows = workflows;
        this.releases = releases;
    }

    @GetMapping("/templates")
    public CatalogDtos.TemplateList listTemplates(@PathVariable UUID workspaceId) {
        return workflows.listTemplates(callers.current(), workspaceId);
    }

    @GetMapping("/workflows")
    public CatalogDtos.WorkflowPage listWorkflows(@PathVariable UUID workspaceId, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean startable, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return workflows.listWorkflows(callers.current(), workspaceId, q, startable, cursor, limit);
    }

    @PostMapping("/workflows")
    public ResponseEntity<CatalogDtos.WorkflowView> createWorkflow(@PathVariable UUID workspaceId,
            @Valid @RequestBody CatalogDtos.CreateWorkflowRequest request) {
        CatalogDtos.WorkflowView workflow = workflows.createWorkflow(callers.current(), workspaceId, request);
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/workflows/" + workflow.id()))
                .eTag(WorkflowService.etag(workflow.draftRevision())).body(workflow);
    }

    @GetMapping("/workflows/{workflowId}")
    public CatalogDtos.WorkflowView getWorkflow(@PathVariable UUID workspaceId, @PathVariable UUID workflowId) {
        return workflows.getWorkflow(callers.current(), workspaceId, workflowId);
    }

    @GetMapping("/workflows/{workflowId}/draft")
    public ResponseEntity<CatalogDtos.WorkflowDraft> getDraft(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId) {
        CatalogDtos.WorkflowDraft draft = workflows.getDraft(callers.current(), workspaceId, workflowId);
        return ResponseEntity.ok().eTag(WorkflowService.etag(draft.revision())).body(draft);
    }

    @PutMapping("/workflows/{workflowId}/draft")
    public ResponseEntity<CatalogDtos.WorkflowDraft> saveDraft(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CatalogDtos.SaveDraftRequest request) {
        CatalogDtos.WorkflowDraft draft = workflows.saveDraft(callers.current(), workspaceId, workflowId, ifMatch, request);
        return ResponseEntity.ok().eTag(WorkflowService.etag(draft.revision())).body(draft);
    }

    @PostMapping("/workflows/{workflowId}/validate")
    public CatalogDtos.ValidationReport validateDraft(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody CatalogDtos.ValidateRequest request) {
        return workflows.validateDraft(callers.current(), workspaceId, workflowId, request.revision());
    }

    @PostMapping("/workflows/{workflowId}/decisions/{decisionKey}/evaluate")
    public CatalogDtos.DecisionEvaluateResult evaluateDecision(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @PathVariable String decisionKey,
            @Valid @RequestBody CatalogDtos.DecisionEvaluateRequest request) {
        return workflows.evaluateDecision(callers.current(), workspaceId, workflowId, decisionKey, request);
    }

    @PostMapping("/workflows/{workflowId}/publish")
    public ResponseEntity<CatalogDtos.ReleaseView> publishDraft(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CatalogDtos.PublishRequest request) {
        ReleaseService.Published published = releases.publish(callers.current(), workspaceId, workflowId,
                idempotencyKey, request);
        if (published.replayed()) {
            return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, "true").body(published.release());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(published.release());
    }

    @GetMapping("/workflows/{workflowId}/releases")
    public CatalogDtos.ReleasePage listReleases(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return releases.listReleases(callers.current(), workspaceId, workflowId, cursor, limit);
    }

    @GetMapping("/workflows/{workflowId}/releases/{releaseId}")
    public CatalogDtos.ReleaseView getRelease(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @PathVariable UUID releaseId) {
        return releases.getRelease(callers.current(), workspaceId, workflowId, releaseId);
    }

    @GetMapping("/workflows/{workflowId}/releases/{releaseId}/export")
    public ResponseEntity<byte[]> exportRelease(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @PathVariable UUID releaseId) {
        ReleaseService.Bundle bundle = releases.export(callers.current(), workspaceId, workflowId, releaseId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(bundle.fileName()).build().toString())
                .body(bundle.zip());
    }

    @PostMapping("/workflows/{workflowId}/active-release")
    public CatalogDtos.WorkflowView setActiveRelease(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody CatalogDtos.SetActiveReleaseRequest request) {
        return releases.setActiveRelease(callers.current(), workspaceId, workflowId, request);
    }

    @GetMapping("/workflows/{workflowId}/start-form")
    public CatalogDtos.StartForm getStartForm(@PathVariable UUID workspaceId, @PathVariable UUID workflowId) {
        return releases.startForm(callers.current(), workspaceId, workflowId);
    }
}
