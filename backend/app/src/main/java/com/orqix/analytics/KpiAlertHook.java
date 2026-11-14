package com.orqix.analytics;

import java.time.Instant;
import java.util.UUID;

/**
 * Called when a workflow's KPI measure changes state (CG-N03, docs/build/S145), for example from on target to off
 * target. Implement it as a Spring bean to deliver alerts (alert rules, CG-O06); this module delivers nothing itself.
 * Calls happen after the new state is stored, on the evaluator's thread, once per change; a hook that throws is logged
 * and does not stop the others. Only aggregates are passed: no request data.
 */
public interface KpiAlertHook {

    void kpiStatusChanged(KpiStatusChange change);

    /**
     * @param measure {@code cycleP50}, {@code cycleP95}, {@code automationRate} or {@code slaAttainment}
     * @param previousStatus null the first time the measure is evaluated
     * @param status {@code onTarget}, {@code offTarget} or {@code insufficientData}
     * @param actual seconds for cycle times, a share between 0 and 1 for rates; null without data
     */
    record KpiStatusChange(UUID workspaceId, UUID workflowId, String workflowKey, String measure, String previousStatus,
            String status, Double actual, double target, int samples, int windowDays, Instant evaluatedAt) {
    }
}
