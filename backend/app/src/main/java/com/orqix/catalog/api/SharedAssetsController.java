package com.orqix.catalog.api;

import java.util.List;
import java.util.Map;
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

import com.orqix.catalog.internal.SharedAssets;
import com.orqix.identity.CallerResolver;
import com.orqix.identity.SubjectService.SubjectRef;

import jakarta.validation.Valid;

/** Shared reusable assets: search, detail, where-used, shared decisions and forms, owners and archive (docs/build/S61, S128). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class SharedAssetsController {

    private final CallerResolver callers;
    private final SharedAssets assets;

    public SharedAssetsController(CallerResolver callers, SharedAssets assets) {
        this.callers = callers;
        this.assets = assets;
    }

    @GetMapping("/shared-assets")
    public SharedAssets.Page search(@PathVariable UUID workspaceId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return assets.search(callers.current(), workspaceId, q, kind, owner, includeArchived);
    }

    @GetMapping("/shared-asset-owner-candidates")
    public Map<String, List<SubjectRef>> ownerCandidates(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String kind) {
        return Map.of("items", assets.ownerCandidates(callers.current(), workspaceId, kind));
    }

    @PostMapping("/shared-assets/decision")
    public ResponseEntity<SharedAssets.Detail> createDecision(@PathVariable UUID workspaceId,
            @Valid @RequestBody SharedAssets.CreateDecisionRequest request) {
        return ResponseEntity.status(201).body(assets.createDecision(callers.current(), workspaceId, request));
    }

    @PostMapping("/shared-assets/decision/{assetKey}/versions")
    public ResponseEntity<SharedAssets.Detail> addDecisionVersion(@PathVariable UUID workspaceId,
            @PathVariable String assetKey, @Valid @RequestBody SharedAssets.DecisionVersionRequest request) {
        return ResponseEntity.status(201).body(assets.addDecisionVersion(callers.current(), workspaceId, assetKey, request));
    }

    @PostMapping("/shared-assets/form")
    public ResponseEntity<SharedAssets.Detail> createForm(@PathVariable UUID workspaceId,
            @Valid @RequestBody SharedAssets.CreateFormRequest request) {
        return ResponseEntity.status(201).body(assets.createForm(callers.current(), workspaceId, request));
    }

    @PostMapping("/shared-assets/form/{assetKey}/versions")
    public ResponseEntity<SharedAssets.Detail> addFormVersion(@PathVariable UUID workspaceId,
            @PathVariable String assetKey, @Valid @RequestBody SharedAssets.FormVersionRequest request) {
        return ResponseEntity.status(201).body(assets.addFormVersion(callers.current(), workspaceId, assetKey, request));
    }

    @GetMapping("/shared-assets/{assetKind}/{assetKey}")
    public SharedAssets.Detail get(@PathVariable UUID workspaceId, @PathVariable String assetKind,
            @PathVariable String assetKey) {
        return assets.get(callers.current(), workspaceId, assetKind, assetKey);
    }

    @GetMapping("/shared-assets/{assetKind}/{assetKey}/usage")
    public SharedAssets.Usage usage(@PathVariable UUID workspaceId, @PathVariable String assetKind,
            @PathVariable String assetKey) {
        return assets.usage(callers.current(), workspaceId, assetKind, assetKey);
    }

    @PutMapping("/shared-assets/{assetKind}/{assetKey}/owner")
    public SharedAssets.Detail setOwner(@PathVariable UUID workspaceId, @PathVariable String assetKind,
            @PathVariable String assetKey, @Valid @RequestBody SharedAssets.OwnerRequest request) {
        return assets.setOwner(callers.current(), workspaceId, assetKind, assetKey, request);
    }

    @PutMapping("/shared-assets/{assetKind}/{assetKey}/archived")
    public SharedAssets.Detail setArchived(@PathVariable UUID workspaceId, @PathVariable String assetKind,
            @PathVariable String assetKey, @Valid @RequestBody SharedAssets.ArchiveRequest request) {
        return assets.setArchived(callers.current(), workspaceId, assetKind, assetKey, request);
    }
}
