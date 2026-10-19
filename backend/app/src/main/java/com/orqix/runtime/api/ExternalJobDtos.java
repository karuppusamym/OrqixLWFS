package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/** Worker protocol of docs/build/S43. */
public final class ExternalJobDtos {

    private ExternalJobDtos() {
    }

    public static final String TOPIC = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";

    public record FetchRequest(@NotBlank @Size(max = 100) String workerId,
            @NotNull @Size(min = 1, max = 10) List<@Pattern(regexp = TOPIC) String> topics,
            @Min(1) @Max(10) Integer maxJobs, @Min(30) @Max(3600) Integer leaseSeconds) {
    }

    public record Job(UUID id, String topic, JsonNode input, int fence, Instant leaseExpiresAt, int attempt,
            int maxAttempts, String runReference, String nodeId) {
    }

    public record JobBatch(List<Job> items) {
    }

    public record ExtendRequest(@NotBlank @Size(max = 100) String workerId, @NotNull @Min(1) Integer fence,
            @Min(30) @Max(3600) Integer leaseSeconds) {
    }

    public record ExtendResult(Instant leaseExpiresAt) {
    }

    public record AwaitCallbackRequest(@NotBlank @Size(max = 100) String workerId,
            @NotNull @Min(1) Integer fence, @NotNull @Min(30) @Max(86400) Integer timeoutSeconds) {
    }

    public record CallbackRegistration(UUID callbackId, Instant deadline) {
    }

    public record CallbackResult(@NotNull @Valid Map<String, JsonNode> result) {
    }

    public record CallbackFailure(@NotBlank @Size(max = 500) String message) {
    }

    public record CompleteRequest(@NotBlank @Size(max = 100) String workerId, @NotNull @Min(1) Integer fence,
            @NotNull @Valid Map<String, JsonNode> result) {
    }

    public record FailRequest(@NotBlank @Size(max = 100) String workerId, @NotNull @Min(1) Integer fence,
            @NotBlank @Size(max = 500) String message, @NotNull Boolean retryable,
            @Min(0) @Max(86400) Integer retryAfterSeconds) {
    }
}
