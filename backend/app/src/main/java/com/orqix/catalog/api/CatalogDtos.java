package com.orqix.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orqix.identity.SubjectService.SubjectRef;

import tools.jackson.databind.JsonNode;

/** Request and response bodies of the catalog operations (contracts/openapi.yaml, tags Workflows and Releases). */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public static final String KEY = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";

    public record Template(String id, String name, String description, String typicalOwner, List<String> requiredInputs,
            List<String> requiredGroups, List<String> requiredConnections, List<String> requiredWorkflows) {
    }

    public record TemplateList(List<Template> items) {
    }

    public record ActiveReleaseRef(UUID id, int releaseNumber, Instant publishedAt, int draftRevision) {
    }

    /** {@code Workflow} (and {@code WorkflowSummary} when version/createdAt/permissions are null). */
    public record WorkflowView(UUID id, String key, String name, String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) SubjectRef owner, int draftRevision, ActiveReleaseRef activeRelease,
            boolean canStart, Instant updatedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer version,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> permissions) {
    }

    public record WorkflowPage(List<WorkflowView> items, String nextCursor) {
    }

    public record CreateWorkflowRequest(
            @NotNull @Pattern(regexp = KEY) String key,
            @NotNull @Size(min = 1, max = 200) String name,
            @Size(max = 2000) String description,
            @Pattern(regexp = KEY) String templateId) {
    }

    public record WorkflowDraft(UUID workflowId, int revision, Instant savedAt, SubjectRef savedBy, JsonNode definition) {
    }

    public record SaveDraftRequest(@NotNull JsonNode definition) {
    }

    public record ValidateRequest(@NotNull @Min(1) Integer revision) {
    }

    public record Issue(String code, String severity, String message, String path, String nodeId, String transitionId) {
    }

    public record ValidationReport(int revision, String compilerVersion, boolean publishable, String semanticHash,
            List<Issue> issues) {
    }

    public record DecisionEvaluateRequest(@NotNull @Min(1) Integer revision, @NotNull Map<String, JsonNode> values) {
    }

    public record DecisionEvaluateResult(Integer matchedRow, boolean usedDefault, Map<String, Object> output) {
    }

    public record PublishRequest(@NotNull @Min(1) Integer draftRevision, Boolean acknowledgeWarnings, Boolean activate,
            @Size(max = 1000) String releaseNote) {
    }

    /** {@code Release} (and {@code ReleaseSummary} when the detail fields are null). */
    public record ReleaseView(UUID id, UUID workflowId, int releaseNumber, int draftRevision, Instant publishedAt,
            SubjectRef publishedBy, boolean isActive, String compilerVersion, String releaseNote,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sourceHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String semanticHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Issue> warnings,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode manifest,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode definition) {
    }

    public record ReleasePage(List<ReleaseView> items, String nextCursor) {
    }

    public record SetActiveReleaseRequest(@NotNull UUID releaseId, @NotNull @Min(1) Integer expectedVersion) {
    }

    public record StartForm(UUID workflowId, String workflowName, UUID releaseId, int releaseNumber, JsonNode inputSchema,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode inputUi, boolean isTest) {
    }

    /** Draft definition limits (S3 section 8). */
    public record Limits(@Min(1) @Max(Integer.MAX_VALUE) int maxBytes, int maxDepth) {
    }
}
