package com.orqix.catalog.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.ApplicationViews;
import com.orqix.identity.CallerResolver;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/views")
public class ApplicationViewsController {
    private final ApplicationViews views;
    private final CallerResolver callers;

    public ApplicationViewsController(ApplicationViews views, CallerResolver callers) {
        this.views = views;
        this.callers = callers;
    }

    @GetMapping
    public ApplicationViews.Page list(@PathVariable UUID workspaceId) {
        return views.list(callers.current(), workspaceId);
    }

    @PostMapping
    public ResponseEntity<ApplicationViews.Detail> create(@PathVariable UUID workspaceId,
            @Valid @RequestBody ApplicationViews.Create request) {
        return ResponseEntity.status(201).body(views.create(callers.current(), workspaceId, request));
    }

    @GetMapping("/{viewId}")
    public ApplicationViews.Detail get(@PathVariable UUID workspaceId, @PathVariable UUID viewId) {
        return views.get(callers.current(), workspaceId, viewId);
    }

    @PostMapping("/{viewId}/copy")
    public ResponseEntity<ApplicationViews.Detail> copy(@PathVariable UUID workspaceId, @PathVariable UUID viewId,
            @Valid @RequestBody ApplicationViews.Copy request) {
        return ResponseEntity.status(201).body(views.copy(callers.current(), workspaceId, viewId, request));
    }

    @PutMapping("/{viewId}/draft")
    public ApplicationViews.Detail save(@PathVariable UUID workspaceId, @PathVariable UUID viewId,
            @Valid @RequestBody ApplicationViews.Save request) {
        return views.save(callers.current(), workspaceId, viewId, request);
    }

    @PostMapping("/{viewId}/publish")
    public ApplicationViews.Detail publish(@PathVariable UUID workspaceId, @PathVariable UUID viewId,
            @Valid @RequestBody ApplicationViews.Publish request) {
        return views.publish(callers.current(), workspaceId, viewId, request);
    }

    @GetMapping("/{viewId}/versions/{version}")
    public ApplicationViews.PublishedVersion version(@PathVariable UUID workspaceId, @PathVariable UUID viewId,
            @PathVariable int version) {
        return views.version(callers.current(), workspaceId, viewId, version);
    }
}
