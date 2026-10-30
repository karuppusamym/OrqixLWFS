package com.orqix.identity.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.identity.WorkspaceFeatures;

/** Workspace feature switches (docs/build/S127; contracts/openapi.yaml, tag Access). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/features")
public class WorkspaceFeatureController {

    private final CallerResolver callers;
    private final WorkspaceFeatures features;

    public WorkspaceFeatureController(CallerResolver callers, WorkspaceFeatures features) {
        this.callers = callers;
        this.features = features;
    }

    @GetMapping
    public WorkspaceFeatures.StateList getWorkspaceFeatures(@PathVariable UUID workspaceId) {
        return features.list(callers.current(), workspaceId);
    }

    @PutMapping("/{feature}")
    public WorkspaceFeatures.StateList setWorkspaceFeature(@PathVariable UUID workspaceId, @PathVariable String feature,
            @Valid @RequestBody WorkspaceFeatures.SetRequest request) {
        return features.set(callers.current(), workspaceId, feature, request.enabled());
    }
}
