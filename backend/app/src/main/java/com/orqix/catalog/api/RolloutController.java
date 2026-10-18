package com.orqix.catalog.api;

import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.PromotionService;
import com.orqix.catalog.internal.RolloutService;
import com.orqix.identity.CallerResolver;

/** Canary, release analytics, environment stage and promotion (PL02, docs/build/S54). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class RolloutController {

    private final RolloutService rollouts;
    private final PromotionService promotions;
    private final CallerResolver callers;

    public RolloutController(RolloutService rollouts, PromotionService promotions, CallerResolver callers) {
        this.rollouts = rollouts;
        this.promotions = promotions;
        this.callers = callers;
    }

    @GetMapping("/workflows/{workflowId}/rollout")
    public RolloutService.Rollout rollout(@PathVariable UUID workspaceId, @PathVariable UUID workflowId) {
        return rollouts.get(callers.current(), workspaceId, workflowId);
    }

    @PutMapping("/workflows/{workflowId}/canary")
    public RolloutService.Rollout setCanary(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody RolloutService.SetCanaryRequest request) {
        return rollouts.setCanary(callers.current(), workspaceId, workflowId, request);
    }

    @DeleteMapping("/workflows/{workflowId}/canary")
    public RolloutService.Rollout clearCanary(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestParam int expectedVersion) {
        return rollouts.clearCanary(callers.current(), workspaceId, workflowId, expectedVersion);
    }

    @GetMapping("/workflows/{workflowId}/release-analytics")
    public RolloutService.Analytics analytics(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestParam(required = false) Integer days) {
        return rollouts.analytics(callers.current(), workspaceId, workflowId, days);
    }

    @GetMapping("/environment")
    public PromotionService.Environment environment(@PathVariable UUID workspaceId) {
        return promotions.environment(callers.current(), workspaceId);
    }

    @PutMapping("/environment")
    public PromotionService.Environment setEnvironment(@PathVariable UUID workspaceId,
            @Valid @RequestBody PromotionService.SetEnvironmentRequest request) {
        return promotions.setEnvironment(callers.current(), workspaceId, request);
    }

    @PostMapping("/workflows/{workflowId}/releases/{releaseId}/promotion-check")
    public PromotionService.Report check(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @PathVariable UUID releaseId, @Valid @RequestBody PromotionService.PromoteRequest request) {
        return promotions.check(callers.current(), workspaceId, workflowId, releaseId, request);
    }

    @PostMapping("/workflows/{workflowId}/releases/{releaseId}/promote")
    public ResponseEntity<PromotionService.Promotion> promote(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @PathVariable UUID releaseId,
            @Valid @RequestBody PromotionService.PromoteRequest request) {
        return ResponseEntity.status(201)
                .body(promotions.promote(callers.current(), workspaceId, workflowId, releaseId, request));
    }

    @GetMapping("/workflows/{workflowId}/promotions")
    public Map<String, List<PromotionService.PromotionRecord>> history(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId) {
        return Map.of("items", promotions.history(callers.current(), workspaceId, workflowId));
    }

    @PostMapping("/workflows/{workflowId}/releases/{releaseId}/promotion-requests")
    public ResponseEntity<PromotionService.PromotionRequestView> requestPromotion(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @PathVariable UUID releaseId,
            @Valid @RequestBody PromotionService.RequestPromotionBody request) {
        return ResponseEntity.status(201)
                .body(promotions.requestPromotion(callers.current(), workspaceId, workflowId, releaseId, request));
    }

    @GetMapping("/promotion-requests")
    public Map<String, List<PromotionService.PromotionRequestView>> promotionRequests(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String status) {
        return Map.of("items", promotions.requests(callers.current(), workspaceId, status));
    }

    @PostMapping("/promotion-requests/{requestId}/approve")
    public PromotionService.PromotionRequestView approvePromotion(@PathVariable UUID workspaceId,
            @PathVariable UUID requestId, @Valid @RequestBody(required = false) PromotionService.DecisionBody body) {
        return promotions.approve(callers.current(), workspaceId, requestId, body);
    }

    @PostMapping("/promotion-requests/{requestId}/reject")
    public PromotionService.PromotionRequestView rejectPromotion(@PathVariable UUID workspaceId,
            @PathVariable UUID requestId, @Valid @RequestBody PromotionService.DecisionBody body) {
        return promotions.reject(callers.current(), workspaceId, requestId, body);
    }

    @PostMapping("/promotion-requests/{requestId}/cancel")
    public PromotionService.PromotionRequestView cancelPromotion(@PathVariable UUID workspaceId,
            @PathVariable UUID requestId) {
        return promotions.cancel(callers.current(), workspaceId, requestId);
    }
}
