package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** "Waiting for" panel and API (docs/build/S129, CG-W08); schemas in openapi.yaml under the Waits tag. */
public final class WaitDtos {

    private WaitDtos() {
    }

    /**
     * One open wait of a request.
     *
     * @param kind approval, timer, message, callback, poll, externalJob, childRequest or signal
     * @param check the checks of a "Keep checking until..." step or a safety-net check, else null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunWait(String nodeId, String kind, String label, String subject, Instant since, Instant nextCheckAt,
            Instant dueAt, Instant deadlineAt, int attempts, String correlationRef, String connectionKey,
            PollCheck check) {
    }

    /**
     * @param shown          up to three values the step shows while waiting; null when the caller may not read request data
     * @param extensionLimit the latest deadline an operator may set (twice the authored time, at most 90 days)
     * @param canOperate     the caller may use Check now, Extend deadline and Stop waiting
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PollCheck(UUID waitId, String kind, String state, int checksDone, int maxChecks, int consecutiveErrors,
            Instant startedAt, Instant lastCheckedAt, Integer lastHttpStatus, String lastOutcome, Instant deadlineAt,
            Instant authoredDeadlineAt, Instant extensionLimit, int deadlineExtensions, JsonNode shown, boolean canOperate,
            Instant checkNowAvailableAt, Instant endedAt, String endReason) {
    }

    public record RunWaitList(List<RunWait> items, List<PollCheck> endedChecks) {
    }

    /** A wait found by the workspace search (runs filter "waiting on connection X for longer than Y"). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WaitSearchItem(UUID runId, String runReference, String nodeId, String kind, String label,
            String subject, Instant since, Instant nextCheckAt, Instant deadlineAt, int attempts, String connectionKey) {
    }

    public record WaitSearchResult(List<WaitSearchItem> items) {
    }

    /** Body of Check now and Stop waiting. */
    public record WaitActionRequest(String reason) {
    }

    /** Body of Extend deadline: {@code extendBy} is an ISO-8601 duration such as PT6H or P1D. */
    public record ExtendWaitRequest(String reason, String extendBy) {
    }
}
