package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

    public record ReassignRequest(@NotNull @Min(1) Integer expectedVersion, UUID assigneeSubjectId,
            @NotNull @Size(min = 1, max = 500) String reason) {
    }

    public record CancelRunRequest(@NotNull @Size(min = 1, max = 500) String reason,
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

    public record CurrentStep(String nodeId, String label, String kind, Instant since, String candidateGroup,
            SubjectRef assignee, Instant dueAt) {
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

    public record AllowedRunActions(ActionAvailability cancel, ActionAvailability retry, ActionAvailability reconcile) {
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
            List<ChildRun> children) {
    }

    public record RunSummary(UUID id, String reference, WorkflowRef workflow, ReleaseRef release, String businessKey,
            String requestTitle, SubjectRef initiator,
            String lifecycle, String outcome, String outcomeKind, List<CurrentStep> currentSteps, Attention attention,
            Instant createdAt, Instant updatedAt, Instant endedAt, boolean isTest, int version, ParentRef parentRun) {
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
            String status, Instant dueAt, Instant createdAt, int version, TaskDecision decision) {
    }

    public record TaskRequest(JsonNode input, JsonNode inputSchema,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode inputUi) {
    }

    public record TaskForm(boolean rejectReasonRequired) {
    }

    public record AllowedTaskActions(ActionAvailability claim, ActionAvailability release, ActionAvailability decide,
            ActionAvailability reassign) {
    }

    public record TaskDetail(String id, UUID runId, String runReference, String workflowName, String businessKey, String nodeId,
            String label, String requestTitle, SubjectRef requester, GroupRef candidateGroup, SubjectRef assignee,
            String status, Instant dueAt, Instant createdAt, int version, TaskDecision decision, TaskRequest request,
            List<TaskDecision> history, TaskForm form, AllowedTaskActions allowedActions, SubjectRef completedBy) {
    }

    public record TaskCounts(int assigned, int claimable) {
    }

    public record TaskPage(List<TaskSummary> items, String nextCursor, TaskCounts counts) {
    }

    // ------------------------------------------------------------------ notifications

    public record Notification(UUID id, UUID runId, String runReference, String requestTitle, String kind, String message, Instant createdAt, Instant readAt) {
    }

    public record NotificationPage(List<Notification> items, String nextCursor, int unreadCount) {
    }
}
