package com.orqix.analytics.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.analytics.internal.AnalyticsService;
import com.orqix.identity.CallerResolver;

/** Analytics read model (contracts/openapi.yaml, tag Analytics; docs/build/S145). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AnalyticsController {

    private final CallerResolver callers;
    private final AnalyticsService analytics;

    public AnalyticsController(CallerResolver callers, AnalyticsService analytics) {
        this.callers = callers;
        this.analytics = analytics;
    }

    @GetMapping("/analytics/heatmap")
    public AnalyticsDtos.Heatmap heatmap(@PathVariable UUID workspaceId, @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) UUID releaseId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analytics.heatmap(callers.current(), workspaceId, workflowId, releaseId, from, to);
    }

    @GetMapping("/analytics/human-work")
    public AnalyticsDtos.HumanWork humanWork(@PathVariable UUID workspaceId, @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analytics.humanWork(callers.current(), workspaceId, workflowId, from, to);
    }

    @GetMapping("/analytics/kpis")
    public AnalyticsDtos.KpiOverview kpis(@PathVariable UUID workspaceId, @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analytics.kpiOverview(callers.current(), workspaceId, workflowId, from, to);
    }

    @GetMapping("/analytics/status")
    public AnalyticsDtos.Status status(@PathVariable UUID workspaceId) {
        return analytics.status(callers.current(), workspaceId);
    }

    @PostMapping("/analytics/backfill")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalyticsDtos.Backfill backfill(@PathVariable UUID workspaceId,
            @RequestBody(required = false) AnalyticsDtos.BackfillRequest body) {
        return analytics.backfill(callers.current(), workspaceId, body);
    }

    @GetMapping("/workflows/{workflowId}/kpi-targets")
    public AnalyticsDtos.KpiTargets targets(@PathVariable UUID workspaceId, @PathVariable UUID workflowId) {
        return analytics.targets(callers.current(), workspaceId, workflowId);
    }

    @PutMapping("/workflows/{workflowId}/kpi-targets")
    public AnalyticsDtos.KpiTargets setTargets(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @RequestBody AnalyticsDtos.SetKpiTargets body) {
        return analytics.setTargets(callers.current(), workspaceId, workflowId, body);
    }
}
