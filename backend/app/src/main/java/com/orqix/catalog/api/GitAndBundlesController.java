package com.orqix.catalog.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.GitSyncService;
import com.orqix.catalog.internal.ReleaseBundleService;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.idempotency.IdempotencyService;

/** Git sync (CG-S01) and release bundles (CG-S02), docs/build/S147. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class GitAndBundlesController {

    private final GitSyncService git;
    private final ReleaseBundleService bundles;
    private final CallerResolver callers;

    public GitAndBundlesController(GitSyncService git, ReleaseBundleService bundles, CallerResolver callers) {
        this.git = git;
        this.bundles = bundles;
        this.callers = callers;
    }

    // ------------------------------------------------------------------ Git connection

    @GetMapping("/git-connection")
    public GitSyncService.ConnectionState getGitConnection(@PathVariable UUID workspaceId) {
        return git.connection(callers.current(), workspaceId);
    }

    @PutMapping("/git-connection")
    public GitSyncService.ConnectionState saveGitConnection(@PathVariable UUID workspaceId,
            @Valid @RequestBody GitSyncService.SaveConnection request) {
        return git.save(callers.current(), workspaceId, request);
    }

    @DeleteMapping("/git-connection")
    public ResponseEntity<Void> deleteGitConnection(@PathVariable UUID workspaceId,
            @RequestParam int expectedVersion) {
        git.delete(callers.current(), workspaceId, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/git-connection/check")
    public GitSyncService.CheckResult checkGitConnection(@PathVariable UUID workspaceId) {
        return git.check(callers.current(), workspaceId);
    }

    // ------------------------------------------------------------------ push, pull, publish

    @PostMapping("/workflows/{workflowId}/git/push")
    public GitSyncService.PushResult pushWorkflowToGit(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody GitSyncService.PushRequest request) {
        return git.push(callers.current(), workspaceId, workflowId, request);
    }

    @GetMapping("/workflows/{workflowId}/git/import-preview")
    public GitSyncService.ImportPreview previewGitImport(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestParam String branch, @RequestParam(required = false) String commit) {
        return git.preview(callers.current(), workspaceId, workflowId, branch, commit);
    }

    @PostMapping("/workflows/{workflowId}/git/import")
    public GitSyncService.ImportResult importWorkflowFromGit(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @Valid @RequestBody GitSyncService.ImportRequest request) {
        return git.importDraft(callers.current(), workspaceId, workflowId, request);
    }

    @PostMapping("/workflows/{workflowId}/git/publish")
    public ResponseEntity<GitSyncService.PublishResult> publishFromGit(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody GitSyncService.PublishRequest request) {
        GitSyncService.PublishResult result = git.publish(callers.current(), workspaceId, workflowId, idempotencyKey,
                request);
        if (result.replayed()) {
            return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, "true").body(result);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/workflows/{workflowId}/git/events")
    public List<GitSyncService.SyncEvent> listGitSyncEvents(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId) {
        return git.events(callers.current(), workspaceId, workflowId);
    }

    // ------------------------------------------------------------------ bundles

    @PostMapping("/workflows/{workflowId}/bundles")
    public ResponseEntity<ReleaseBundleService.PublishResult> publishReleaseBundle(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CatalogDtos.PublishRequest request) {
        ReleaseBundleService.PublishResult result = bundles.publish(callers.current(), workspaceId, workflowId,
                idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, "true").body(result);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/workflows/{workflowId}/bundles")
    public ReleaseBundleService.BundleList listReleaseBundles(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId) {
        return bundles.list(callers.current(), workspaceId, workflowId);
    }

    @GetMapping("/workflows/{workflowId}/releases/{releaseId}/bundle")
    public ReleaseBundleService.BundleView getReleaseBundle(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @PathVariable UUID releaseId) {
        return bundles.forRelease(callers.current(), workspaceId, workflowId, releaseId);
    }

    @PostMapping("/bundles/{bundleId}/promotion-check")
    public ReleaseBundleService.PromotionPlan checkBundlePromotion(@PathVariable UUID workspaceId,
            @PathVariable UUID bundleId, @Valid @RequestBody ReleaseBundleService.PromoteRequest request) {
        return bundles.check(callers.current(), workspaceId, bundleId, request);
    }

    @PostMapping("/bundles/{bundleId}/promote")
    public ResponseEntity<ReleaseBundleService.BundlePromotion> promoteReleaseBundle(@PathVariable UUID workspaceId,
            @PathVariable UUID bundleId, @Valid @RequestBody ReleaseBundleService.PromoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bundles.promote(callers.current(), workspaceId, bundleId, request));
    }
}
