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

    /** One reviewed change of a form suggestion (S90): {@code field} is null for remove. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FormChange(String op, String key, JsonNode field, boolean required, String reason, JsonNode before,
            Boolean wasRequired) {
    }

    public record FormRejected(String op, String key, List<String> reasons) {
    }

    /** {@code AiFormSuggestion}: never applied by the server; the designer applies accepted changes in the studio. */
    public record FormSuggestion(List<FormChange> changes, List<FormRejected> rejected, List<String> notes,
            String notAForm, Generation generation) {
    }

    /** {@code suggestFormChanges} request body: the description and the request form as the editor shows it now. */
    public record FormSuggestRequest(String description, JsonNode inputSchema, String profile) {

        private static final int MAX_FORM_CHARS = 64_000;

        public static FormSuggestRequest parse(JsonNode body, java.util.function.Function<JsonNode, String> write) {
            List<ApiException.FieldError> errors = new ArrayList<>();
            JsonNode node = object(body, Set.of("description", "inputSchema", "profile"));
            String description = string(node, "description", true, 10, 2000, null, errors);
            if (description != null && description.isBlank()) {
                errors.add(new ApiException.FieldError("/description", "minLength", "Invalid value"));
            }
            String profile = string(node, "profile", false, 1, 32, PROFILE_PATTERN, errors);
            JsonNode form = node.path("inputSchema");
            if (form.isMissingNode()) {
                errors.add(new ApiException.FieldError("/inputSchema", "required", "Invalid value"));
            } else if (!form.isObject() || !form.path("properties").isObject()) {
                errors.add(new ApiException.FieldError("/inputSchema", "type", "Invalid value"));
            } else if (form.path("properties").size() > 30) {
                errors.add(new ApiException.FieldError("/inputSchema/properties", "maxProperties", "Invalid value"));
            } else if (write.apply(form).length() > MAX_FORM_CHARS) {
                errors.add(new ApiException.FieldError("/inputSchema", "maxLength", "Invalid value"));
            }
            fail(errors);
            return new FormSuggestRequest(description, form, profile);
        }
    }

    /** One reviewable change of a conversational edit (STU02): normalized operations, applied together. */
    public record DraftEditChange(String id, String summary, List<JsonNode> operations, List<String> dependsOn) {
    }

    public record DraftEditRejected(String summary, List<String> reasons) {
    }

    /**
     * {@code AiDraftEditProposal}: never applied by the server. {@code definition} is the complete proposed draft of a
     * regeneration (absent for edits).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DraftEditProposal(int revision, String mode, String message, List<DraftEditChange> changes,
            List<DraftEditRejected> rejected, List<String> notes, String notAnEdit, JsonNode definition,
            Generation generation, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> questions) {
        public DraftEditProposal(int revision, String mode, String message, List<DraftEditChange> changes,
                List<DraftEditRejected> rejected, List<String> notes, String notAnEdit, JsonNode definition,
                Generation generation) {
            this(revision, mode, message, changes, rejected, notes, notAnEdit, definition, generation, List.of());
        }
    }

    /** The Ask AI conversation saved with a draft (S124); {@code version} 0 when nothing is saved. */
    public record Conversation(JsonNode turns, @JsonInclude(JsonInclude.Include.ALWAYS) Instant updatedAt, int version) {
    }

    /** One earlier conversation turn sent with a draft-edit request. */
    public record Turn(String role, String text) {
    }

    /** {@code proposeDraftEdits} request body (STU02). */
    /** {@code noQuestions}: the author asked for a draft now, so the assistant must not ask clarifying questions (S124). */
    public record DraftEditRequest(String instruction, int revision, String mode, List<Turn> history, String profile,
            boolean noQuestions) {

        public static final int MAX_TURNS = 8;
        public static final int MAX_TEXT = 2000;

        public static DraftEditRequest parse(JsonNode body) {
            List<ApiException.FieldError> errors = new ArrayList<>();
            JsonNode node = object(body, Set.of("instruction", "revision", "mode", "history", "profile", "noQuestions"));
            boolean noQuestions = false;
            if (node.has("noQuestions")) {
                if (!node.get("noQuestions").isBoolean()) {
                    errors.add(new ApiException.FieldError("/noQuestions", "type", "Invalid value"));
                } else {
                    noQuestions = node.get("noQuestions").booleanValue();
                }
            }
            String instruction = string(node, "instruction", true, 2, MAX_TEXT, null, errors);
            if (instruction != null && instruction.isBlank()) {
                errors.add(new ApiException.FieldError("/instruction", "minLength", "Invalid value"));
            }
            int revision = 0;
            JsonNode value = node.path("revision");
            if (value.isMissingNode()) {
                errors.add(new ApiException.FieldError("/revision", "required", "Invalid value"));
            } else if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
                errors.add(new ApiException.FieldError("/revision", value.isIntegralNumber() ? "minimum" : "type",
                        "Invalid value"));
            } else {
                revision = value.asInt();
            }
            String mode = string(node, "mode", false, 1, 16, "^(edit|regenerate)$", errors);
            if (mode != null && !mode.matches("^(edit|regenerate)$")) {
                mode = null;
            }
            String profile = string(node, "profile", false, 1, 32, PROFILE_PATTERN, errors);
            List<Turn> history = new ArrayList<>();
            JsonNode turns = node.path("history");
            if (!turns.isMissingNode()) {
                if (!turns.isArray()) {
                    errors.add(new ApiException.FieldError("/history", "type", "Invalid value"));
                } else if (turns.size() > MAX_TURNS) {
                    errors.add(new ApiException.FieldError("/history", "maxItems", "Invalid value"));
                } else {
                    for (int i = 0; i < turns.size(); i++) {
                        JsonNode turn = turns.get(i);
                        String pointer = "/history/" + i;
                        if (!turn.isObject()) {
                            errors.add(new ApiException.FieldError(pointer, "type", "Invalid value"));
                            continue;
                        }
                        for (String name : turn.propertyNames()) {
                            if (!"role".equals(name) && !"text".equals(name)) {
                                errors.add(new ApiException.FieldError(pointer, "additionalProperties",
                                        "Invalid value"));
                            }
                        }
                        String role = turn.path("role").isString() ? turn.path("role").stringValue() : null;
                        if (!"author".equals(role) && !"assistant".equals(role)) {
                            errors.add(new ApiException.FieldError(pointer + "/role", "enum", "Invalid value"));
                        }
                        String text = turn.path("text").isString() ? turn.path("text").stringValue() : null;
                        int length = text == null ? 0 : text.codePointCount(0, text.length());
                        if (text == null) {
                            errors.add(new ApiException.FieldError(pointer + "/text", "required", "Invalid value"));
                        } else if (length < 1 || length > MAX_TEXT) {
                            errors.add(new ApiException.FieldError(pointer + "/text",
                                    length < 1 ? "minLength" : "maxLength", "Invalid value"));
                        } else {
                            history.add(new Turn(role, text));
                        }
                    }
                }
            }
            fail(errors);
            return new DraftEditRequest(instruction, revision, mode == null ? "edit" : mode, List.copyOf(history),
                    profile, noQuestions);
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
