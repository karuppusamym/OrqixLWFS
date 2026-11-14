package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.FormVisibility;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.FormLookupCalls;
import com.orqix.runtime.api.LookupDtos;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;

/**
 * Lookup fields of request forms (CG-F02, docs/build/S140). A search runs the field's pinned read-only operation for a
 * person who may start the workflow; the same answers are shared by everyone who may start it (the binding is the
 * workspace's), so they are cached in memory for a short time. Nothing is stored in the database or logged: the search
 * text and the rows exist only in these caches. When the request is submitted every lookup answer is checked again: a
 * value offered by a search in the last {@code verified-ttl} counts as checked, otherwise the operation is asked for
 * that value and must return a row with exactly it.
 */
@Service
public class FormLookups {

    static final int MAX_OPTIONS = 20;
    static final int MAX_SEARCH = 100;
    static final int MAX_ENTRIES = 5_000;

    /** A lookup field of a release, read from its form schema. */
    record Field(String key, String title, String connection, int version, String operation, String searchInput,
            String valueInput, String rows, String valueColumn, String labelColumn, String detailColumn,
            int minSearchLength) {
    }

    private record Cached(LookupDtos.LookupOptions options, Instant until) {
    }

    private final PolicyService policy;
    private final ReleaseQueries releases;
    private final FormLookupCalls calls;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration verifiedTtl;
    private final int requestsPerMinute;
    private final Map<String, Cached> searches = lru();
    private final Map<String, Instant> verified = lru();
    private final Map<String, Deque<Long>> recent = new HashMap<>();

    public FormLookups(PolicyService policy, ReleaseQueries releases, FormLookupCalls calls, Clock clock,
            @Value("${orqix.lookups.cache-ttl:60s}") Duration cacheTtl,
            @Value("${orqix.lookups.verified-ttl:120s}") Duration verifiedTtl,
            @Value("${orqix.lookups.requests-per-minute:60}") int requestsPerMinute) {
        this.policy = policy;
        this.releases = releases;
        this.calls = calls;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
        this.verifiedTtl = verifiedTtl;
        this.requestsPerMinute = requestsPerMinute;
    }

    // ---------------------------------------------------------------- search

    public LookupDtos.LookupOptions search(Caller caller, UUID workspaceId, UUID workflowId, String fieldKey,
            LookupDtos.LookupSearch request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        ReleaseQueries.WorkflowRef workflow = releases.workflow(workspaceId, workflowId).orElseThrow(ApiException::notFound);
        if (!access.canStart(workflow.id())) {
            if (!access.has(Permission.WORKFLOW_READ)) {
                throw ApiException.notFound();
            }
            throw policy.deny(workspaceId, caller.subjectId(), "workflow.start", "searchLookupOptions", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        UUID releaseId = startableRelease(workspaceId, workflow, request == null ? null : request.releaseId());
        Field field = field(releases.require(workspaceId, releaseId), fieldKey);
        if (field == null) {
            throw ApiException.notFound();
        }
        String text = request == null || request.search() == null ? "" : request.search().strip();
        if (text.length() > MAX_SEARCH || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The search text is not valid.").withFieldErrors(
                    List.of(new ApiException.FieldError("/search", "maxLength",
                            "Enter at most " + MAX_SEARCH + " characters of plain text.")));
        }
        throttle(caller.subjectId(), workflowId);
        if (text.codePointCount(0, text.length()) < field.minSearchLength()) {
            return new LookupDtos.LookupOptions(List.of(), false, false, field.minSearchLength());
        }
        String cacheKey = workspaceId + "|" + releaseId + "|" + field.key() + "|" + text.toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        synchronized (searches) {
            Cached cached = searches.get(cacheKey);
            if (cached != null && cached.until().isAfter(now)) {
                LookupDtos.LookupOptions o = cached.options();
                return new LookupDtos.LookupOptions(o.options(), o.truncated(), true, field.minSearchLength());
            }
        }
        FormLookupCalls.Answer answer = calls.read(workspaceId, field.connection(), field.version(), field.operation(),
                field.searchInput(), text, field.rows());
        LookupDtos.LookupOptions options = switch (answer.status()) {
            case OK -> options(workspaceId, releaseId, field, answer, now);
            case NOT_APPLICABLE -> new LookupDtos.LookupOptions(List.of(), false, false, field.minSearchLength());
            case DEFERRED -> throw new ApiException(ProblemCode.RATE_LIMITED,
                    "The connected system is busy; search again in a moment.")
                    .withHeader("Retry-After", retryAfter(answer.retryAt(), now));
            case UNAVAILABLE -> throw unavailable(field);
        };
        synchronized (searches) {
            searches.put(cacheKey, new Cached(options, now.plus(cacheTtl)));
        }
        return options;
    }

    private LookupDtos.LookupOptions options(UUID workspaceId, UUID releaseId, Field field,
            FormLookupCalls.Answer answer, Instant now) {
        List<LookupDtos.LookupOption> options = new ArrayList<>();
        boolean truncated = answer.truncated();
        for (Map<String, String> row : answer.rows()) {
            String value = row.get(field.valueColumn());
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (options.size() >= MAX_OPTIONS) {
                truncated = true;
                break;
            }
            String label = row.getOrDefault(field.labelColumn(), value);
            String detail = field.detailColumn() == null ? null : row.get(field.detailColumn());
            options.add(new LookupDtos.LookupOption(value, label, detail));
            remember(workspaceId, releaseId, field.key(), value, now);
        }
        return new LookupDtos.LookupOptions(List.copyOf(options), truncated, false, field.minSearchLength());
    }

    // ---------------------------------------------------------------- re-validation at submit

    /**
     * Checks every visible lookup answer of a request input against the release's lookup operations.
     *
     * @return field errors ({@code /<key>} pointers, code {@code lookup}); empty when every value is a current option
     * @throws ApiException 503 or 429 when the connected system cannot answer now (nothing is accepted unchecked)
     */
    public List<ApiException.FieldError> verify(UUID workspaceId, ReleaseQueries.ReleaseView release, JsonNode input) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        JsonNode schema = release.inputSchema();
        JsonNode ui = release.inputUi();
        for (Map.Entry<String, JsonNode> e : schema.path("properties").properties()) {
            if (!e.getValue().has("lookup")) {
                continue;
            }
            JsonNode value = input == null ? null : input.get(e.getKey());
            if (value == null || !value.isString() || !FormVisibility.visible(ui, input, e.getKey())) {
                continue; // absent, hidden or mistyped answers are reported by the form validator
            }
            Field field = field(release, e.getKey());
            if (field != null && !check(workspaceId, release.id(), field, value.stringValue())) {
                errors.add(new ApiException.FieldError("/" + e.getKey(), "lookup",
                        "Choose " + field.title() + " from the list; this value is not one of its options."));
            }
        }
        return errors;
    }

    /** @return true when the release has at least one lookup field */
    public static boolean hasLookups(ReleaseQueries.ReleaseView release) {
        for (Map.Entry<String, JsonNode> e : release.inputSchema().path("properties").properties()) {
            if (e.getValue().has("lookup")) {
                return true;
            }
        }
        return false;
    }

    private boolean check(UUID workspaceId, UUID releaseId, Field field, String value) {
        Instant now = clock.instant();
        String key = workspaceId + "|" + releaseId + "|" + field.key() + "|" + value;
        synchronized (verified) {
            Instant until = verified.get(key);
            if (until != null && until.isAfter(now)) {
                return true;
            }
        }
        FormLookupCalls.Answer answer = calls.read(workspaceId, field.connection(), field.version(), field.operation(),
                field.valueInput(), value, field.rows());
        switch (answer.status()) {
            case NOT_APPLICABLE -> {
                return false;
            }
            case DEFERRED -> throw new ApiException(ProblemCode.RATE_LIMITED, "We could not check " + field.title()
                    + " with the connected system right now. Send the same request again in a moment.")
                    .withHeader("Retry-After", retryAfter(answer.retryAt(), now));
            case UNAVAILABLE -> throw unavailable(field);
            case OK -> {
            }
        }
        for (Map<String, String> row : answer.rows()) {
            if (value.equals(row.get(field.valueColumn()))) {
                remember(workspaceId, releaseId, field.key(), value, now);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    /** The active release, or the canary that takes a share of new starts; a request for another release is refused. */
    private UUID startableRelease(UUID workspaceId, ReleaseQueries.WorkflowRef workflow, UUID requested) {
        if (workflow.activeReleaseId() == null) {
            throw new ApiException(ProblemCode.NO_ACTIVE_RELEASE, "This workflow has not been published yet.");
        }
        if (requested == null || requested.equals(workflow.activeReleaseId())) {
            return workflow.activeReleaseId();
        }
        return releases.canary(workspaceId, workflow.id()).map(ReleaseQueries.Canary::releaseId)
                .filter(requested::equals)
                .orElseThrow(() -> new ApiException(ProblemCode.RELEASE_NOT_ACTIVE,
                        "A newer release of this workflow is active; reload the form."));
    }

    /** The lookup of a request field and the connection version the release pins, or null. */
    static Field field(ReleaseQueries.ReleaseView release, String key) {
        JsonNode schema = release.inputSchema().path("properties").get(key);
        if (schema == null || !schema.has("lookup")) {
            return null;
        }
        JsonNode l = schema.get("lookup");
        String connection = l.path("connection").asString("");
        int version = -1;
        for (JsonNode dep : release.definition().path("dependencies").path("connections")) {
            if (connection.equals(dep.path("key").asString(null))) {
                version = dep.path("definitionVersion").asInt(-1);
                break;
            }
        }
        if (version < 1) {
            return null;
        }
        String search = l.path("searchInput").asString("");
        return new Field(key, schema.path("title").asString(key), connection, version, l.path("operation").asString(""),
                search, l.path("valueInput").asString(search), l.path("rows").asString(""),
                l.path("valueColumn").asString(""), l.path("labelColumn").asString(""),
                l.path("detailColumn").asString(null), l.path("minSearchLength").asInt(2));
    }

    private void remember(UUID workspaceId, UUID releaseId, String fieldKey, String value, Instant now) {
        synchronized (verified) {
            verified.put(workspaceId + "|" + releaseId + "|" + fieldKey + "|" + value, now.plus(verifiedTtl));
        }
    }

    /** A sliding minute of searches per person and workflow, on this pod: protection for the connected system. */
    private void throttle(UUID subject, UUID workflowId) {
        long now = clock.millis();
        String key = subject + "|" + workflowId;
        synchronized (recent) {
            if (recent.size() > MAX_ENTRIES) {
                recent.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() >= 60_000);
            }
            Deque<Long> times = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!times.isEmpty() && now - times.peekFirst() >= 60_000) {
                times.pollFirst();
            }
            if (times.size() >= requestsPerMinute) {
                long wait = Math.max(1, (60_000 - (now - times.peekFirst()) + 999) / 1000);
                throw new ApiException(ProblemCode.RATE_LIMITED, "Too many searches. Type a little more, then wait a"
                        + " moment.").withHeader("Retry-After", String.valueOf(wait));
            }
            times.addLast(now);
        }
    }

    private static ApiException unavailable(Field field) {
        return new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The options for " + field.title()
                + " cannot be loaded from the connected system right now.").withHeader("Retry-After", "10");
    }

    private static String retryAfter(Instant at, Instant now) {
        long seconds = at == null ? 5 : Math.max(1, Duration.between(now, at).toSeconds() + 1);
        return String.valueOf(Math.min(seconds, 900));
    }

    private static <V> Map<String, V> lru() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }
}
