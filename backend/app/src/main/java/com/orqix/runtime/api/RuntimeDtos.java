package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orqix.identity.SubjectService.SubjectRef;

import tools.jackson.databind.JsonNode;

/** Request and response bodies of the run, task and notification operations (contracts/openapi.yaml). */
public final class RuntimeDtos {

    private RuntimeDtos() {
    }

    // ------------------------------------------------------------------ requests

    public record StartRunRequest(@NotNull UUID workflowId, UUID expectedReleaseId,
            @Size(min = 1, max = 100) String businessKey, @NotNull JsonNode input) {
    }

    public record VersionedCommand(@NotNull @Min(1) Integer expectedVersion) {
    }

    public record DecisionRequest(@NotNull @Min(1) Integer expectedVersion,
            @NotNull @Pattern(regexp = "approve|reject") String action, @Size(max = 1000) String reason,
            @Size(max = 2000) String comment) {
    }

    public record PriorityRequest(@NotNull @Min(1) Integer expectedVersion,
            @NotNull @Min(0) @Max(100) Integer priority, @NotBlank @Size(max = 500) String reason) { }

    public record ReassignRequest(@NotNull @Min(1) Integer expectedVersion, UUID assigneeSubjectId,
            @NotNull @Size(min = 1, max = 500) String reason) {
    }

    public record CancelRunRequest(@NotNull @Size(min = 1, max = 500) String reason,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    public record SuspendRunRequest(@NotNull @Size(min = 1, max = 500) String reason,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** sendRunSignal (S59): the signal name follows the workflow schema's signalName pattern. */
    public record SendRunSignalRequest(
            @NotNull @Size(min = 2, max = 64) @jakarta.validation.constraints.Pattern(
                    regexp = "^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$") String signal,
            @NotNull @Size(min = 1, max = 500) String reason,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    public record RepairRunRequest(@NotNull @Size(min = 1, max = 500) String reason,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** What the engine holds compared with what the request shows; {@code repairable} only for rebuildable approvals. */
    public record RunDiagnosis(String state, boolean repairable, String message, List<String> projectedNodeIds,
            List<String> engineNodeIds, int version) {
    }

    /** What the periodic scan last found for one request (docs/build/S72). The scan only reports; it never repairs. */
    public record ConsistencyFinding(UUID runId, String reference, String state, boolean repairable, String message,
            int version, String firstSeenAt, String checkedAt) {
    }

    /** Open findings of the scan plus whether it is switched on and how much it has covered. */
    public record ConsistencyFindings(boolean enabled, int scannedRuns, String lastCheckedAt, int total,
            List<ConsistencyFinding> items) {
    }

    public record MigrateRunRequest(@NotNull UUID targetReleaseId, @NotNull @Size(min = 1, max = 500) String reason,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    public record ResumeRunRequest(@NotNull @Size(min = 1, max = 500) String reason,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    public record RetryRunRequest(UUID effectId, @NotNull @Size(min = 1, max = 500) String reason,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    public record Evidence(@NotNull @Size(min = 10, max = 1000) String note, @Size(max = 200) String remoteReference) {
    }

    public record ReconcileEffectRequest(@NotNull @Pattern(regexp = "confirmedApplied|confirmedNotApplied") String resolution,
            @NotNull @Valid Evidence evidence, Map<String, JsonNode> result) {
    }

    // ------------------------------------------------------------------ runs

    public record WorkflowRef(UUID id, String key, String name) {
    }

    public record ReleaseRef(UUID id, int releaseNumber) {
    }

    /** {@code messageName} is set while the step waits for a message (kind {@code wait}, docs/build/S16). */
    public record CurrentStep(String nodeId, String label, String kind, Instant since, String candidateGroup,
            SubjectRef assignee, Instant dueAt, String messageName) {

        public CurrentStep(String nodeId, String label, String kind, Instant since, String candidateGroup,
                SubjectRef assignee, Instant dueAt) {
            this(nodeId, label, kind, since, candidateGroup, assignee, dueAt, null);
        }
    }

    /** The run that started this one ("Start another workflow", S12). */
    public record ParentRef(UUID id, String reference, String nodeId, String nodeLabel) {
    }

    /** A run started by a step of this run: shown on the parent so a child that needs attention is visible. */
    public record ChildRun(UUID id, String reference, WorkflowRef workflow, ReleaseRef release, String nodeId,
            String nodeLabel, String lifecycle, String outcome, String outcomeKind, Attention attention,
            Instant startedAt, Instant endedAt, int iteration) {
    }

    public record Attention(String reason, String nodeId, UUID effectId, String message, Instant since) {
    }

    public record ActionAvailability(boolean allowed, String reasonCode) {
    }

    public record AllowedRunActions(ActionAvailability cancel, ActionAvailability retry, ActionAvailability reconcile,
            ActionAvailability suspend, ActionAvailability resume) {
    }

    public record MigrationFinding(String code, String message, String nodeId) {
    }

    public record MigrationOption(ReleaseRef release, boolean active, Instant publishedAt, boolean compatible,
            List<MigrationFinding> findings) {
    }

    /** Releases a request could move to, with the reasons it cannot. {@code ready} is false until it is suspended. */
    public record MigrationOptions(ReleaseRef current, boolean ready, String notReadyReason,
            List<MigrationOption> options) {
    }

    /** Present while an operator has suspended the request; {@code reason} is shown only to operators. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Suspension(Instant since, String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunDetail(UUID id, String reference, WorkflowRef workflow, ReleaseRef release,
            @JsonInclude(JsonInclude.Include.ALWAYS) String businessKey,
            @JsonInclude(JsonInclude.Include.ALWAYS) String requestTitle, SubjectRef initiator, String lifecycle,
            @JsonInclude(JsonInclude.Include.ALWAYS) String outcome,
            @JsonInclude(JsonInclude.Include.ALWAYS) String outcomeKind, List<CurrentStep> currentSteps,
            @JsonInclude(JsonInclude.Include.ALWAYS) Attention attention, Instant createdAt, Instant updatedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant endedAt, boolean isTest, int version,
            @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode input, Map<String, String> inputLabels,
            @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode output, List<TaskSummary> tasks, List<Effect> effects,
            List<Effect> completedEffectsNotReversed, AllowedRunActions allowedActions, ParentRef parentRun,
            List<ChildRun> children, StageView stage, List<StageView> stages, Suspension suspension) {
    }

    /**
     * A case stage of a run (docs/build/S17). {@code index} is 1-based; {@code state} is notStarted, active or
     * completed; {@code overdue} is true for an active stage past its due time.
     */
    public record StageView(String id, String label, int index, int total, String state, Instant enteredAt,
            Instant dueAt, Instant completedAt, boolean overdue, String sla) {
    }

    public record StageProgress(StageView current, List<StageView> stages) {
    }

    public record RunSummary(UUID id, String reference, WorkflowRef workflow, ReleaseRef release, String businessKey,
            String requestTitle, SubjectRef initiator,
            String lifecycle, String outcome, String outcomeKind, List<CurrentStep> currentSteps, Attention attention,
            Instant createdAt, Instant updatedAt, Instant endedAt, boolean isTest, int version, ParentRef parentRun,
            StageView stage) {
    }

    public record RunPage(List<RunSummary> items, String nextCursor) {
    }

    public record RunEvent(long seq, String type, String nodeId, String nodeLabel, SubjectRef actor, Instant occurredAt,
            JsonNode data) {
    }

    public record EventPage(List<RunEvent> items, long nextAfter, boolean hasMore, String lifecycle) {
    }

    public record Effect(UUID id, String nodeId, @JsonInclude(JsonInclude.Include.NON_NULL) String label,
            String connectionKey, String operation, String effectClass, String state, int attempts,
            Integer lastHttpStatus, String lastErrorCode, String lastErrorDetail, String remoteReference,
            JsonNode resolution, Instant updatedAt) {
    }

    // ------------------------------------------------------------------ tasks

    public record GroupRef(String key, String name) {
    }

    public record TaskDecision(UUID id, String taskId, UUID runId, String nodeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String label, SubjectRef actor, String action, String reason,
            String comment, Instant decidedAt, List<String> policyFlags) {
    }

    public record TaskSummary(String id, UUID runId, String runReference, String workflowName, String businessKey, String nodeId,
            String label, String requestTitle, SubjectRef requester, GroupRef candidateGroup, SubjectRef assignee,
            String status, Instant dueAt, Instant createdAt, int version, int priority, TaskDecision decision,
            StageView stage) {
    }

    public record TaskRequest(JsonNode input, JsonNode inputSchema,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode inputUi) {
    }

    /** {@code correctableFields} lists the request fields this caller may correct on this task now (S42); empty otherwise. */
    public record TaskForm(boolean rejectReasonRequired, java.util.List<String> correctableFields) {
        public TaskForm(boolean rejectReasonRequired) {
            this(rejectReasonRequired, java.util.List.of());
        }
    }

    public record CorrectTaskInputRequest(@NotNull @Min(1) Integer expectedVersion,
            @NotNull @Size(min = 1, max = 500) String reason, @NotNull Map<String, JsonNode> changes) {
    }

    public record AllowedTaskActions(ActionAvailability claim, ActionAvailability release, ActionAvailability decide,
            ActionAvailability reassign, ActionAvailability priority) {
    }

    public record TaskDetail(String id, UUID runId, String runReference, String workflowName, String businessKey, String nodeId,
            String label, String requestTitle, SubjectRef requester, GroupRef candidateGroup, SubjectRef assignee,
            String status, Instant dueAt, Instant createdAt, int version, int priority, TaskDecision decision, TaskRequest request,
            List<TaskDecision> history, TaskForm form, AllowedTaskActions allowedActions, SubjectRef completedBy, JsonNode approvalView) {
    }

    public record TaskCounts(int assigned, int claimable) {
    }

    public record TaskPage(List<TaskSummary> items, String nextCursor, TaskCounts counts) {
    }

    // ------------------------------------------------------------------ notifications

    /** {@code workflowId} and {@code elementId} are set for a discussion mention (CMP01, docs/build/S87). */
    public record Notification(UUID id, UUID runId, String runReference, String requestTitle, String kind, String message, Instant createdAt, Instant readAt,
            UUID workflowId, String elementId) {
    }

    public record NotificationsMarkedRead(int markedRead) {
    }

    public record NotificationPage(List<Notification> items, String nextCursor, int unreadCount) {
    }
}
