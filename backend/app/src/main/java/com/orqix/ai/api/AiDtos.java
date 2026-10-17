package com.orqix.ai.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orqix.shared.problem.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * AI API representations (contracts/openapi.yaml: AiStatus, AiDraftRequest, AiDraftResult, AiGeneration,
 * WorkflowExplanation). Request bodies are parsed after authorization, so a caller who cannot see the workspace gets
 * 404 whatever the body looks like.
 */
public final class AiDtos {

    private static final String PROFILE_PATTERN = "^[a-z][a-z0-9-]{0,31}$";

    private AiDtos() {
    }

    public record Profile(String id, String label, String model, boolean variablePrice) {
    }

    public record MaxPrice(BigDecimal promptPerMillion, BigDecimal completionPerMillion) {
    }

    public record Budget(BigDecimal monthlyUsd, BigDecimal spentUsd) {
    }

    /** A profile paused by the circuit breaker: its router chose a model the policy refuses (S10 section 1). */
    public record DisabledProfile(String id, String label, String model, String reason, Instant until) {
    }

    public record Status(boolean enabled, String reason, String defaultProfile, List<Profile> profiles,
            List<String> excludedModels, MaxPrice maxPrice, Budget budget, List<DisabledProfile> disabledProfiles) {
    }

    /** @param policyViolation why the routed model was refused by the policy, when it was (profile is then paused) */
    public record Generation(String profile, String model, String promptVersion, int attempts, boolean escalated,
            Long inputTokens, Long outputTokens, BigDecimal costUsd, long durationMs, String policyViolation) {

        /** Without a policy violation. */
        public Generation(String profile, String model, String promptVersion, int attempts, boolean escalated,
                Long inputTokens, Long outputTokens, BigDecimal costUsd, long durationMs) {
            this(profile, model, promptVersion, attempts, escalated, inputTokens, outputTokens, costUsd, durationMs,
                    null);
        }
    }

    public record MissingItem(String message, String nodeId, String code) {
    }

    public record DraftResult(JsonNode workflow, JsonNode draft, JsonNode report, List<String> assumptions,
            List<MissingItem> missingConfiguration, Generation generation) {
    }

    /**
     * {@code AiGenerationJob}: an accepted generation. {@code startedAt} is when it was accepted; {@code result} is set
     * when it succeeded and {@code problem} (an RFC 9457 body) when it failed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Job(UUID id, String status, Instant startedAt, Instant finishedAt, String profile, DraftResult result,
            JsonNode problem) {
    }

    public record Step(String nodeId, String text) {
    }

    public record Explanation(String summary, List<Step> steps, Generation generation, JsonNode validation) {
    }

    /** {@code AiDraftRequest}. */
    public record DraftRequest(String description, String name, String profile) {

        /** Validates against the contract schema: 400 MALFORMED_REQUEST with fieldErrors. */
        public static DraftRequest parse(JsonNode body) {
            List<ApiException.FieldError> errors = new ArrayList<>();
            JsonNode node = object(body, Set.of("description", "name", "profile"));
            String description = string(node, "description", true, 10, 4000, null, errors);
            String name = string(node, "name", false, 1, 200, null, errors);
            String profile = string(node, "profile", false, 1, 32, PROFILE_PATTERN, errors);
            if (name != null && (name.contains("${") || name.contains("#{"))) {
                errors.add(new ApiException.FieldError("/name", "pattern", "Invalid value"));
            }
            if (name != null && name.isBlank()) {
                errors.add(new ApiException.FieldError("/name", "minLength", "Invalid value"));
            }
            if (description != null && description.isBlank()) {
                errors.add(new ApiException.FieldError("/description", "minLength", "Invalid value"));
            }
            fail(errors);
            return new DraftRequest(description, name, profile);
        }
    }

    /** {@code explainWorkflow} request body. */
    public record ExplainRequest(Integer revision, String profile) {

        public static ExplainRequest parse(JsonNode body) {
            List<ApiException.FieldError> errors = new ArrayList<>();
            JsonNode node = object(body, Set.of("revision", "profile"));
            Integer revision = null;
            JsonNode value = node.path("revision");
            if (!value.isMissingNode()) {
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
                    errors.add(new ApiException.FieldError("/revision", value.isIntegralNumber() ? "minimum" : "type",
                            "Invalid value"));
                } else {
                    revision = value.asInt();
                }
            }
            String profile = string(node, "profile", false, 1, 32, PROFILE_PATTERN, errors);
            fail(errors);
            return new ExplainRequest(revision, profile);
        }
    }

    private static JsonNode object(JsonNode body, Set<String> allowed) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            throw ApiException.malformed("A request body is required.");
        }
        if (!body.isObject()) {
            throw ApiException.malformed("The request body must be a JSON object.");
        }
        for (String name : body.propertyNames()) {
            if (!allowed.contains(name)) {
                throw ApiException.malformed("Unknown property '" + (name.length() > 64 ? name.substring(0, 64) : name)
                        + "'.");
            }
        }
        return body;
    }

    private static String string(JsonNode node, String field, boolean required, int min, int max, String pattern,
            List<ApiException.FieldError> errors) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) {
            if (required) {
                errors.add(new ApiException.FieldError("/" + field, "required", "Invalid value"));
            }
            return null;
        }
        if (!value.isString()) {
            errors.add(new ApiException.FieldError("/" + field, "type", "Invalid value"));
            return null;
        }
        String text = value.stringValue();
        int length = text.codePointCount(0, text.length());
        if (length < min) {
            errors.add(new ApiException.FieldError("/" + field, "minLength", "Invalid value"));
            return null;
        }
        if (length > max) {
            errors.add(new ApiException.FieldError("/" + field, "maxLength", "Invalid value"));
            return null;
        }
        if (pattern != null && !text.matches(pattern)) {
            errors.add(new ApiException.FieldError("/" + field, "pattern", "Invalid value"));
            return null;
        }
        return text;
    }

    private static void fail(List<ApiException.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw ApiException.malformed("The request does not match the operation schema.").withFieldErrors(errors);
        }
    }
}
