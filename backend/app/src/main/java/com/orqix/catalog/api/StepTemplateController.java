package com.orqix.catalog.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.StepTemplates;
import com.orqix.identity.CallerResolver;

/** Workspace step templates (CG-S03, contracts/openapi.yaml tag TaskActions block of S141). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/step-templates")
public class StepTemplateController {

    private final CallerResolver callers;
    private final StepTemplates templates;

    public StepTemplateController(CallerResolver callers, StepTemplates templates) {
        this.callers = callers;
        this.templates = templates;
    }

    @GetMapping
    public StepTemplates.TemplateList listStepTemplates(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
        return templates.list(callers.current(), workspaceId, q, includeArchived);
    }

    @PostMapping
    public ResponseEntity<StepTemplates.TemplateView> createStepTemplate(@PathVariable UUID workspaceId,
            @Valid @RequestBody StepTemplates.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templates.create(callers.current(), workspaceId, request));
    }

    @GetMapping("/{key}")
    public StepTemplates.TemplateDetail getStepTemplate(@PathVariable UUID workspaceId, @PathVariable String key) {
        return templates.get(callers.current(), workspaceId, key);
    }

    @PutMapping("/{key}")
    public StepTemplates.TemplateView updateStepTemplate(@PathVariable UUID workspaceId, @PathVariable String key,
            @Valid @RequestBody StepTemplates.UpdateRequest request) {
        return templates.update(callers.current(), workspaceId, key, request);
    }

    @PostMapping("/{key}/versions")
    public ResponseEntity<StepTemplates.TemplateView> addStepTemplateVersion(@PathVariable UUID workspaceId,
            @PathVariable String key, @Valid @RequestBody StepTemplates.VersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(templates.addVersion(callers.current(), workspaceId, key, request));
    }
}
