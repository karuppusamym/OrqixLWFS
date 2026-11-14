package com.orqix.analytics.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response and request shapes of contracts/openapi.yaml, tag Analytics. Aggregates only: no titles, input or people. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record Range(LocalDate from, LocalDate to, int days, Instant generatedAt) {
    }

    public record WorkflowRef(UUID id, String key, String name) {
    }

    /** How current the read model is: the newest projection in the workspace and the updates still waiting. */
    public record Freshness(Instant lastProjectedAt, int pendingUpdates) {
    }

    /** CG-N01: one step of the release, over finished visits of runs started in the range. */
    public record HeatStep(String nodeId, String kind, int visits, int runs, Long averageSeconds, Long p95Seconds,
            Long maxSeconds) {
    }

    /** CG-N01: one connection, by how many runs started in the range used it. */
    public record HeatRoute(String transitionId, int runs, double share) {
    }

    public record Heatmap(Range range, UUID workflowId, UUID releaseId, int runs, int finishedRuns, List<HeatStep> steps,
            List<HeatRoute> routes, Freshness freshness) {
    }

    /** CG-N05: one human step and group. Times are seconds; attainment is null without tasks that had a due time. */
    public record HumanStep(WorkflowRef workflow, String nodeId, String label, String groupKey, String groupName,
            int finished, Long averageWaitingSeconds, Long p95WaitingSeconds, Long averageWorkingSeconds,
            Long p95WorkingSeconds, int slaMet, int slaMissed, Double slaAttainment, int reassignments, int approximate) {
    }

    public record HumanGroup(String groupKey, String groupName, int openNow, int openUnassigned, Long oldestOpenSeconds,
            int finished, Long averageWaitingSeconds, Long averageWorkingSeconds, int slaMet, int slaMissed,
            Double slaAttainment, int reassignments) {
    }

    public record HumanWork(Range range, List<HumanStep> steps, List<HumanGroup> groups, boolean truncated,
            Freshness freshness) {
    }

    /** CG-N03: targets of one workflow; every target is optional. Version 0 means no targets were set yet. */
    public record KpiTargets(UUID workflowId, Long cycleP50Seconds, Long cycleP95Seconds, BigDecimal automationRate,
            BigDecimal slaAttainment, BigDecimal delayCostPerHour, String currency, int version, Instant updatedAt) {
    }

    public record SetKpiTargets(Long cycleP50Seconds, Long cycleP95Seconds, BigDecimal automationRate,
            BigDecimal slaAttainment, BigDecimal delayCostPerHour, String currency, Integer expectedVersion) {
    }

    /** {@code gap} is how far the actual figure is on the wrong side of the target (0 when on target). */
    public record KpiMeasure(String measure, double target, Double actual, int samples, String status, Double gap) {
    }

    public record KpiWorkflow(WorkflowRef workflow, KpiTargets targets, List<KpiMeasure> measures, int offTarget,
            int completedRuns, double excessHours, BigDecimal delayCost, String currency) {
    }

    public record KpiOverview(Range range, List<KpiWorkflow> items, Freshness freshness) {
    }

    public record Status(int pending, int failed, Instant oldestPendingAt, Instant lastProjectedAt, int projectedRuns) {
    }

    public record BackfillRequest(LocalDate since) {
    }

    public record Backfill(int enqueued) {
    }
}
