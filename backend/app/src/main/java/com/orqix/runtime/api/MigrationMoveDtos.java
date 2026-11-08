package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Mapped release migration and guarded moves (contracts/openapi.yaml, tag MigrationAndMove; docs/build/S143). */
public final class MigrationMoveDtos {

    private MigrationMoveDtos() {
    }

    // ---------------------------------------------------------------- CG-O02 migration with a step mapping

    public record StepMapping(@NotBlank @Size(max = 200) String from, @NotBlank @Size(max = 200) String to) {
    }

    public record MigrationPlanRequest(@NotNull UUID targetReleaseId, @Valid @Size(max = 50) List<StepMapping> mappings) {
        public List<StepMapping> mappingsOrEmpty() {
            return mappings == null ? List.of() : mappings;
        }
    }

    public record MigrationPlanStep(String from, String to, String kind, String action, String label) {
    }

    public record MigrationDataCopy(String from, String to) {
    }

    public record MigrationPlan(UUID runId, String reference, RuntimeDtos.ReleaseRef current,
            RuntimeDtos.ReleaseRef target, boolean ready, String notReadyReason, String hold, boolean allowed,
            int runVersion, List<MigrationPlanStep> steps, List<MigrationDataCopy> dataCopies,
            List<RuntimeDtos.MigrationFinding> findings, List<StepMapping> suggestedMappings) {
    }

    public record MappedMigrationRequest(@NotNull UUID targetReleaseId,
            @Valid @Size(max = 50) List<StepMapping> mappings,
            @NotNull @Size(min = 1, max = 500) String reason, @Min(1) int expectedVersion) {
        public List<StepMapping> mappingsOrEmpty() {
            return mappings == null ? List.of() : mappings;
        }
    }

    public record MigrationBatchPreviewRequest(@NotNull UUID sourceReleaseId, @NotNull UUID targetReleaseId,
            @Valid @Size(max = 50) List<StepMapping> mappings, @Size(min = 1, max = 100) List<UUID> runIds,
            Boolean suspendDuring) {
        public List<StepMapping> mappingsOrEmpty() {
            return mappings == null ? List.of() : mappings;
        }
    }

    public record MigrationBatchPreview(RuntimeDtos.ReleaseRef source, RuntimeDtos.ReleaseRef target, int total,
            int allowed, int refused, boolean truncated, List<MigrationPlan> items) {
    }

    public record CreateMigrationBatchRequest(@NotNull UUID sourceReleaseId, @NotNull UUID targetReleaseId,
            @Valid @Size(max = 50) List<StepMapping> mappings, @NotNull @Size(min = 1, max = 100) List<UUID> runIds,
            @NotNull @Size(min = 1, max = 500) String reason, Boolean suspendDuring, @Min(1) int expectedCount) {
        public List<StepMapping> mappingsOrEmpty() {
            return mappings == null ? List.of() : mappings;
        }
    }

    public record MigrationBatchItem(UUID runId, String reference, String state, String message,
            List<RuntimeDtos.MigrationFinding> findings, Instant finishedAt) {
    }

    public record MigrationBatch(UUID id, UUID workflowId, RuntimeDtos.ReleaseRef source, RuntimeDtos.ReleaseRef target,
            List<StepMapping> mappings, String state, boolean suspendDuring, int itemCount, int migrated, int refused,
            int failed, UUID createdBy, Instant createdAt, Instant finishedAt, List<MigrationBatchItem> items) {
    }

    // ---------------------------------------------------------------- CG-O03 guarded move

    public record MovePlanRequest(@NotNull @Pattern(regexp = "move|skip|rerun") String kind,
            @NotBlank @Size(max = 200) String fromStepId, @NotBlank @Size(max = 200) String toStepId) {
    }

    public record MoveAffected(String nodeId, String label, String kind, String handling) {
    }

    public record MovePlan(String kind, String fromStepId, String fromLabel, String toStepId, String toLabel,
            boolean allowed, int runVersion, boolean approvalRequired, String approvalReason,
            List<MoveAffected> skipped, List<MoveAffected> rerun, List<RuntimeDtos.MigrationFinding> findings) {
    }

    public record MoveRunRequest(@NotNull @Pattern(regexp = "move|skip|rerun") String kind,
            @NotBlank @Size(max = 200) String fromStepId, @NotBlank @Size(max = 200) String toStepId,
            @NotNull @Size(min = 1, max = 500) String reason, @Min(1) int expectedVersion) {
    }

    public record DecideMoveRequest(@Size(max = 500) String note) {
    }

    public record RunMove(UUID id, UUID runId, String kind, String fromStepId, String fromLabel, String toStepId,
            String toLabel, String reason, String state, boolean approvalRequired, int expectedVersion,
            UUID requestedBy, Instant requestedAt, Instant expiresAt, UUID decidedBy, Instant decidedAt,
            String decisionNote, Instant executedAt, List<MoveAffected> skipped, List<MoveAffected> rerun) {
    }

    public record RunMoveList(List<RunMove> items) {
    }
}
