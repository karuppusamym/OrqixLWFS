package com.orqix.integration.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orqix.identity.SubjectService.SubjectRef;

import tools.jackson.databind.JsonNode;

/** Connection API bodies (contracts/openapi.yaml, tag Connections). Secrets are never part of any body. */
public final class ConnectionDtos {

    private ConnectionDtos() {
    }

    public record OperationSummary(String name, String label, String effectClass, boolean testable) {
    }

    public record BindingState(boolean configured, boolean enabled, String host) {
    }

    public record ConnectionSummary(String key, String name, String kind, int latestVersion, List<Integer> versions,
            BindingState binding, List<OperationSummary> operations) {
    }

    public record ConnectionList(List<ConnectionSummary> items) {
    }

    public record DefinitionRecord(int version, JsonNode definition, String definitionHash, Instant createdAt,
            SubjectRef createdBy) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Auth(@NotNull @Pattern(regexp = "none|bearerToken|apiKeyHeader|oauth2ClientCredentials") String type,
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9-]{0,63}$") String headerName,
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef) {
    }

    public record Binding(String connectionKey, String baseUrl, Auth auth, boolean enabled, int revision,
            Instant updatedAt, SubjectRef updatedBy) {
    }

    public record ConnectionDetail(String key, List<DefinitionRecord> definitions, Binding binding) {
    }

    public record RegisterRequest(@NotNull JsonNode definition) {
    }

    public record PutBindingRequest(@NotNull @Size(max = 500) String baseUrl, @NotNull @Valid Auth auth,
            @NotNull Boolean enabled) {
    }

    /** @param input sample input of an agent operation; ignored for HTTP operations */
    public record TestRequest(@NotNull @Min(1) Integer version,
            @NotNull @Pattern(regexp = "^[a-z][A-Za-z0-9]{0,63}$") String operation, JsonNode input) {
    }

    /** @param output agent tests only: the structured result; @param summary agent tests only: the timeline sentence */
    public record TestResult(String outcome, Integer httpStatus, long durationMs, String errorCode, String detail,
            JsonNode output, String summary) {

        public TestResult(String outcome, Integer httpStatus, long durationMs, String errorCode, String detail) {
            this(outcome, httpStatus, durationMs, errorCode, detail, null, null);
        }
    }

    @SuppressWarnings("unused")
    private static UUID unused() {
        return null;
    }
}
