package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bulk operations, data search and the job dashboard (contracts/openapi.yaml, tag BulkAndSearch; docs/build/S142). */
public final class BulkDtos {

    private BulkDtos() {
    }

    /** The run filter of a bulk operation: the same filters as listRuns. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunFilter(UUID workflowId,
            @Pattern(regexp = "running|waiting|needsAttention|completed|cancelled") String lifecycle,
            @Size(min = 1, max = 100) String businessKey, Instant createdFrom, Instant createdTo, Boolean overdue,
            @Size(max = 5) List<@NotNull @Size(min = 3, max = 270) String> fields,
            String attentionReason,
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String errorCode) {
    }

    public record PreviewRequest(
            @NotNull @Pattern(regexp = "retry|cancel|suspend|resume|migrate") String action,
            @NotNull @Valid RunFilter filter, UUID targetReleaseId,
            @Min(1) @Max(600) Integer ratePerMinute) {
    }

    public record ExecuteRequest(@NotNull @Min(0) Integer confirmedCount,
            @NotNull @Size(min = 1, max = 450) String reason) {
    }

    public record ControlRequest(@NotNull @Min(1) Integer expectedVersion, @Size(max = 500) String reason) {
    }

    public record Progress(int total, int pending, int succeeded, int failed, int skipped, int cancelled) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetRelease(UUID id, int releaseNumber) {
    }

    public record Item(UUID runId, String reference, int position, String state, String errorCode, String message,
            Instant finishedAt) {
    }

    public record Operation(UUID id, String action, String state, RunFilter filter, TargetRelease targetRelease,
            int matchedCount, int cap, boolean exceedsCap, int ratePerMinute, String reason, String stopReason,
            com.orqix.identity.SubjectService.SubjectRef createdBy, Instant createdAt, Instant previewExpiresAt, Instant startedAt,
            Instant finishedAt, Progress progress, List<Item> sample, int version) {
    }

    public record OperationPage(List<Operation> items, String nextCursor) {
    }

    public record ItemPage(List<Item> items, String nextCursor) {
    }

    public record SearchableField(String key, String title, String kind, List<String> options) {
    }

    public record SearchableFields(List<SearchableField> items) {
    }

    // ---------------------------------------------------------------- job dashboard (CG-O05)

    public record JobQueue(String topic, int available, int locked, int failedLastDay, Instant oldestAvailableAt,
            Long oldestAgeSeconds, Instant workerLastSeenAt, int workersSeenLastHour) {
    }

    /** Open checks of one connection operation (S129 poll waits). {@code lateChecks}: due more than a minute ago. */
    public record PollCheckSummary(String connectionKey, String operation, int waiting, int checking,
            int needsAttention, int lateChecks, Instant nextCheckAt, Instant lastCheckedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobDashboard(List<JobQueue> jobQueues, List<PollCheckSummary> pollChecks,
            List<com.orqix.integration.IntegrationStatus.TriggerLag> packageTriggers,
            List<com.orqix.integration.IntegrationStatus.ConnectionVolume> connections, Instant asOf) {
    }
}
