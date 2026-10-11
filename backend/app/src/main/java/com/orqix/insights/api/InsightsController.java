package com.orqix.insights.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.insights.internal.InsightsService;

/** Workflow analytics for people with run.read (contracts/openapi.yaml, tag Insights). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/insights")
public class InsightsController {

    private final CallerResolver callers;
    private final InsightsService insights;

    public InsightsController(CallerResolver callers, InsightsService insights) {
        this.callers = callers;
        this.insights = insights;
    }

    @GetMapping("/summary")
    public InsightsDtos.Summary summary(@PathVariable UUID workspaceId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) UUID workflowId) {
        return insights.summary(callers.current(), workspaceId, from, to, workflowId);
    }

    @GetMapping("/throughput")
    public InsightsDtos.Throughput throughput(@PathVariable UUID workspaceId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) UUID workflowId) {
        return insights.throughput(callers.current(), workspaceId, from, to, workflowId);
    }

    @GetMapping("/steps")
    public InsightsDtos.Steps steps(@PathVariable UUID workspaceId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) UUID workflowId) {
        return insights.steps(callers.current(), workspaceId, from, to, workflowId);
    }
}
