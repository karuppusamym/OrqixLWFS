package com.orqix.catalog.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.OrganizationService;
import com.orqix.identity.CallerResolver;

/** Workflow folders and owners (PL01, docs/build/S46). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class OrganizationController {

    private final OrganizationService service;
    private final CallerResolver callers;

    public OrganizationController(OrganizationService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping("/workflow-organization")
    public OrganizationService.Organization organization(@PathVariable UUID workspaceId) {
        return service.organization(callers.current(), workspaceId);
    }

    @PostMapping("/workflows/{workflowId}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody OrganizationService.ArchiveRequest request) {
        service.archive(callers.current(), workspaceId, workflowId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/workflow-owner-candidates")
    public java.util.Map<String, java.util.List<com.orqix.identity.SubjectService.SubjectRef>> ownerCandidates(
            @PathVariable UUID workspaceId) {
        return java.util.Map.of("items", service.ownerCandidates(callers.current(), workspaceId));
    }

    @PostMapping("/workflow-folders")
    public ResponseEntity<OrganizationService.Folder> create(@PathVariable UUID workspaceId,
            @Valid @RequestBody OrganizationService.NameRequest request) {
        OrganizationService.Folder folder = service.create(callers.current(), workspaceId, request);
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/workflow-folders/" + folder.id()))
                .body(folder);
    }

    @PutMapping("/workflow-folders/{folderId}")
    public OrganizationService.Folder rename(@PathVariable UUID workspaceId, @PathVariable UUID folderId,
            @Valid @RequestBody OrganizationService.RenameRequest request) {
        return service.rename(callers.current(), workspaceId, folderId, request);
    }

    @DeleteMapping("/workflow-folders/{folderId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID folderId) {
        service.delete(callers.current(), workspaceId, folderId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/workflows/{workflowId}/folder")
    public CatalogDtos.WorkflowView assign(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody OrganizationService.AssignRequest request) {
        return service.assign(callers.current(), workspaceId, workflowId, request);
    }

    @PutMapping("/workflows/{workflowId}/owner")
    public CatalogDtos.WorkflowView changeOwner(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody OrganizationService.OwnerRequest request) {
        return service.changeOwner(callers.current(), workspaceId, workflowId, request);
    }
}
