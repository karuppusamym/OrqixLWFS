package com.orqix.insights.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes of contracts/openapi.yaml, tag Insights. Aggregates only: no titles, no input data. */
public final class InsightsDtos {

    private InsightsDtos() {
    }

    public record Range(LocalDate from, LocalDate to, int days, Instant generatedAt) {
    }

    public record WorkflowRef(UUID id, String key, String name) {
    }

    public record Totals(int started, int completed, int fulfilled, int rejected, int cancelled, int open,
            int needsAttention) {
    }

    public record CycleTime(WorkflowRef workflow, int completed, long medianSeconds, long p90Seconds) {
    }

    public record OpenRun(UUID runId, String reference, WorkflowRef workflow, String lifecycle, Instant startedAt,
            long ageSeconds, String stepLabel) {
    }

    public record AttentionRun(UUID runId, String reference, WorkflowRef workflow, String reason, Instant since,
            String stepLabel) {
    }

    public record Timeout(WorkflowRef workflow, String nodeId, String label, int timeouts, int decided, double share) {
    }

    public record Overdue(UUID runId, String reference, WorkflowRef workflow, String kind, String stepLabel,
            Instant dueAt, long overdueSeconds) {
    }

    public record Sla(List<Timeout> timeouts, List<Overdue> overdue, int overdueCount) {
    }

    public record Summary(Range range, Totals totals, List<CycleTime> cycleTimes, List<OpenRun> oldestOpen,
            List<AttentionRun> needsAttention, Sla sla) {
    }

    public record Day(LocalDate date, int started, int completed, int fulfilled, int rejected) {
    }

    public record Throughput(Range range, List<Day> days) {
    }

    public record Step(WorkflowRef workflow, String nodeId, String label, String kind, int count, int openNow,
            Long averageWaitSeconds, Long p90WaitSeconds, int timeouts, double timeoutShare) {
    }

    public record Steps(Range range, List<Step> items, boolean truncated) {
    }
}
