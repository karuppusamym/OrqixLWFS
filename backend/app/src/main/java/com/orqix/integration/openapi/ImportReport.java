package com.orqix.integration.openapi;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The review report of an OpenAPI import (S36 section 4). Plain records so the same shape is stored, returned by the
 * API and tested. Never contains the source text; {@code servers} is provenance only and is never copied into a
 * connection definition.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportReport(Source source, List<Server> servers, Auth auth, Summary summary, List<Operation> operations,
        List<Issue> issues, String suggestedKey, String suggestedName) {

    public static final String SUPPORTED = "supported";
    public static final String SIMPLIFIED = "simplified";
    public static final String BLOCKED = "blocked";

    public record Source(String title, String version, String openapiVersion, String description) {
    }

    /** @param url display only, credentials and query removed; the binding owns the real address */
    public record Server(String url, String description) {
    }

    /**
     * @param status {@code suggested}, {@code none} (nothing declared) or {@code needsPlatformDecision}
     * @param suggestedType binding auth type: none, bearerToken or apiKeyHeader; null when a decision is needed
     */
    public record Auth(String status, String suggestedType, String headerName, String message, List<Scheme> schemes) {
    }

    public record Scheme(String name, String type, boolean supported, String note) {
    }

    public record Summary(int operations, int supported, int simplified, int blocked, int selectableLimit,
            int analysisLimit) {
    }

    /**
     * One operation of the source. {@code key} is the stable operation name used in the definition.
     *
     * @param blockers why the operation is excluded (disposition blocked)
     * @param notes what was simplified or dropped (disposition simplified)
     * @param suggestedEffectClass always a suggestion that a person must confirm; null for blocked operations
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(String key, String method, String path, String summary, String disposition,
            List<String> blockers, List<String> notes, String suggestedEffectClass, String effectReason,
            String idempotencyHeader, boolean testable, boolean deprecated, boolean selectedByDefault,
            List<String> inputs, List<String> outputs) {
    }

    /** @param severity info, warning or blocker; blocker issues apply to the whole document */
    public record Issue(String severity, String code, String message) {
    }
}
