package com.orqix.catalog.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.DocumentTemplates;
import com.orqix.identity.CallerResolver;

import jakarta.validation.Valid;

/** The document template registry (docs/build/S52, contracts/openapi.yaml tag Documents). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/document-templates")
public class DocumentTemplatesController {

    private final CallerResolver callers;
    private final DocumentTemplates templates;

    public DocumentTemplatesController(CallerResolver callers, DocumentTemplates templates) {
        this.callers = callers;
        this.templates = templates;
    }

    @GetMapping
    public DocumentTemplates.Page list(@PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return templates.list(callers.current(), workspaceId, includeArchived);
    }

    @PostMapping
    public ResponseEntity<DocumentTemplates.Detail> create(@PathVariable UUID workspaceId,
            @Valid @RequestBody DocumentTemplates.CreateRequest request) {
        return ResponseEntity.status(201).body(templates.create(callers.current(), workspaceId, request));
    }

    @GetMapping("/{templateId}")
    public DocumentTemplates.Detail get(@PathVariable UUID workspaceId, @PathVariable UUID templateId) {
        return templates.get(callers.current(), workspaceId, templateId);
    }

    @PostMapping("/{templateId}/versions")
    public ResponseEntity<DocumentTemplates.Detail> addVersion(@PathVariable UUID workspaceId,
            @PathVariable UUID templateId, @Valid @RequestBody DocumentTemplates.VersionRequest request) {
        return ResponseEntity.status(201).body(templates.addVersion(callers.current(), workspaceId, templateId, request));
    }

    @PutMapping("/{templateId}/archived")
    public DocumentTemplates.Detail setArchived(@PathVariable UUID workspaceId, @PathVariable UUID templateId,
            @RequestBody DocumentTemplates.ArchiveRequest request) {
        return templates.setArchived(callers.current(), workspaceId, templateId, request);
    }
}
