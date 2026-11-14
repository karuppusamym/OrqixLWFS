package com.orqix.sdk;

import com.orqix.sdk.model.CallbackTokenIssued;
import com.orqix.sdk.model.EventPage;
import com.orqix.sdk.model.ExternalJobAwaitCallbackRequest;
import com.orqix.sdk.model.ExternalJobBatch;
import com.orqix.sdk.model.ExternalJobCallbackFailure;
import com.orqix.sdk.model.ExternalJobCallbackRegistration;
import com.orqix.sdk.model.ExternalJobCallbackResult;
import com.orqix.sdk.model.ExternalJobCompleteRequest;
import com.orqix.sdk.model.ExternalJobExtendRequest;
import com.orqix.sdk.model.ExternalJobExtendResult;
import com.orqix.sdk.model.ExternalJobFailRequest;
import com.orqix.sdk.model.ExternalJobFetchRequest;
import com.orqix.sdk.model.MessageAccepted;
import com.orqix.sdk.model.Run;
import com.orqix.sdk.model.RunDetail;
import com.orqix.sdk.model.SendMessageRequest;
import com.orqix.sdk.model.StartRunRequest;
import java.lang.System.Logger.Level;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Typed client for the Orqix machine API ({@code /api/v1}): start and read requests, read their events, send
 * messages, the external-worker protocol and callback tokens. Method names are the contract's operationIds.
 *
 * <pre>{@code
 * OrqixClient orqix = OrqixClient.builder()
 *         .baseUrl("https://orqix.example/api/v1")
 *         .workspaceId(workspaceId)
 *         .token(tokens::currentAccessToken)   // asked for every call, so refreshed tokens are picked up
 *         .build();
 * Run run = orqix.startRun(UUID.randomUUID().toString(), workflowId, Map.of("amount", 1200));
 * }</pre>
 *
 * <p>Thread-safe. The token supplier is called for every request; the SDK never stores or logs the token.
 */
public final class OrqixClient {

    private static final System.Logger LOG = System.getLogger(OrqixClient.class.getName());

    private final Transport transport;
    private final Supplier<String> token;
    private final HttpClient http;
    private final Duration timeout;
    private final Consumer<DeprecationNotice> deprecations;
    private final String baseUrl;

    private OrqixClient(Builder builder) {
        this.token = Objects.requireNonNull(builder.token, "token supplier is required");
        this.http = builder.http != null ? builder.http
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build();
        this.timeout = builder.timeout;
        this.deprecations = builder.deprecations;
        this.baseUrl = builder.baseUrl;
        this.transport = new Transport(builder.baseUrl, builder.workspaceId, http, timeout, deprecations);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String workspaceId() {
        return transport.workspaceId();
    }

    // ------------------------------------------------------------------------------------------------ requests (runs)

    /** Starts a request of the workflow's active release. Repeating the key with the same body replays the answer. */
    public Run startRun(String idempotencyKey, StartRunRequest request) {
        return bearer(OrqixOperation.START_RUN, List.of(), Map.of(), idempotencyKey, request, Run.class);
    }

    /** Shorthand for {@link #startRun(String, StartRunRequest)} without business key or release check. */
    public Run startRun(String idempotencyKey, String workflowId, Map<String, ?> input) {
        return startRun(idempotencyKey, new StartRunRequest(workflowId, null, null, copy(input)));
    }

    public RunDetail getRun(String runId) {
        return bearer(OrqixOperation.GET_RUN, List.of(runId), Map.of(), null, null, RunDetail.class);
    }

    /** Events after sequence number {@code after} (0 for the first page); pass {@code nextAfter} to poll on. */
    public EventPage listRunEvents(String runId, long after, Integer limit) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("after", after);
        query.put("limit", limit);
        return bearer(OrqixOperation.LIST_RUN_EVENTS, List.of(runId), query, null, null, EventPage.class);
    }

    // ------------------------------------------------------------------------------------------------ messages

    /** Tells waiting requests that something happened (a "Wait for a message" step with this name and key). */
    public MessageAccepted sendMessage(String idempotencyKey, SendMessageRequest request) {
        return bearer(OrqixOperation.SEND_MESSAGE, List.of(), Map.of(), idempotencyKey, request, MessageAccepted.class);
    }

    /** Message helper: name such as {@code payment-received}, the correlation key and a flat payload of scalars. */
    public MessageAccepted sendMessage(String idempotencyKey, String name, String correlationKey, Map<String, ?> payload) {
        return sendMessage(idempotencyKey, new SendMessageRequest(name, correlationKey, payload == null ? null : copy(payload)));
    }

    // ------------------------------------------------------------------------------------------------ external jobs

    public ExternalJobBatch fetchExternalJobs(ExternalJobFetchRequest request) {
        return bearer(OrqixOperation.FETCH_EXTERNAL_JOBS, List.of(), Map.of(), null, request, ExternalJobBatch.class);
    }

    public ExternalJobExtendResult extendExternalJobLock(String jobId, ExternalJobExtendRequest request) {
        return bearer(OrqixOperation.EXTEND_EXTERNAL_JOB_LOCK, List.of(jobId), Map.of(), null, request,
                ExternalJobExtendResult.class);
    }

    public void completeExternalJob(String jobId, String idempotencyKey, ExternalJobCompleteRequest request) {
        bearer(OrqixOperation.COMPLETE_EXTERNAL_JOB, List.of(jobId), Map.of(), idempotencyKey, request, Void.class);
    }

    public void failExternalJob(String jobId, String idempotencyKey, ExternalJobFailRequest request) {
        bearer(OrqixOperation.FAIL_EXTERNAL_JOB, List.of(jobId), Map.of(), idempotencyKey, request, Void.class);
    }

    public ExternalJobCallbackRegistration awaitExternalJobCallback(String jobId, String idempotencyKey,
            ExternalJobAwaitCallbackRequest request) {
        return bearer(OrqixOperation.AWAIT_EXTERNAL_JOB_CALLBACK, List.of(jobId), Map.of(), idempotencyKey, request,
                ExternalJobCallbackRegistration.class);
    }

    public void completeExternalJobCallback(String jobId, String callbackId, String idempotencyKey,
            ExternalJobCallbackResult request) {
        bearer(OrqixOperation.COMPLETE_EXTERNAL_JOB_CALLBACK, List.of(jobId, callbackId), Map.of(), idempotencyKey,
                request, Void.class);
    }

    public void failExternalJobCallback(String jobId, String callbackId, String idempotencyKey,
            ExternalJobCallbackFailure request) {
        bearer(OrqixOperation.FAIL_EXTERNAL_JOB_CALLBACK, List.of(jobId, callbackId), Map.of(), idempotencyKey,
                request, Void.class);
    }

    // ------------------------------------------------------------------------------------------------ callback tokens

    /** Issues the one-time callback token of an open wait (needs a run.operate grant). Store it like a password. */
    public CallbackTokenIssued issueCallbackToken(String waitId) {
        return bearer(OrqixOperation.ISSUE_CALLBACK_TOKEN, List.of(waitId), Map.of(), null, null,
                CallbackTokenIssued.class);
    }

    /** A client that polls and resumes the wait of {@code callbackToken}; it sends no bearer token. */
    public CallbackTokenClient callbackTokenClient(String callbackToken) {
        return new CallbackTokenClient(new Transport(baseUrl, transport.workspaceId(), http, timeout, deprecations),
                callbackToken);
    }

    // ------------------------------------------------------------------------------------------------ internals

    private <T> T bearer(OrqixOperation operation, List<String> path, Map<String, ?> query, String idempotencyKey,
            Object body, Class<T> type) {
        if (operation.idempotencyKeyRequired()) {
            Transport.checkIdempotencyKey(idempotencyKey);
        }
        String accessToken = token.get();
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("The token supplier returned no access token");
        }
        return transport.send(operation, path, query, Arrays.asList("Authorization", "Bearer " + accessToken,
                "Idempotency-Key", operation.idempotencyKeyRequired() ? idempotencyKey : null),
                body, type, false).body();
    }

    private static Map<String, Object> copy(Map<String, ?> values) {
        return values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    /** Builds an {@link OrqixClient}. {@code baseUrl}, {@code workspaceId} and {@code token} are required. */
    public static final class Builder {
        private String baseUrl;
        private String workspaceId;
        private Supplier<String> token;
        private HttpClient http;
        private Duration timeout = Duration.ofSeconds(30);
        private Consumer<DeprecationNotice> deprecations = notice -> LOG.log(Level.WARNING,
                "Orqix operation {0} is deprecated (Deprecation: {1}, Sunset: {2}); see docs/api-compatibility.md",
                notice.operation().operationId(), notice.deprecation(), notice.sunset());

        private Builder() {
        }

        /** The absolute URL of the API, ending in {@code /api/v1}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        /** Returns the current access token of the machine identity; called for every request. */
        public Builder token(Supplier<String> token) {
            this.token = token;
            return this;
        }

        /** A configured {@link HttpClient} (proxy, TLS). The default never follows redirects. */
        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        /** Request timeout; default 30 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** Called once per operation when the server marks it deprecated. The default logs a warning. */
        public Builder onDeprecation(Consumer<DeprecationNotice> listener) {
            this.deprecations = Objects.requireNonNull(listener);
            return this;
        }

        public OrqixClient build() {
            return new OrqixClient(this);
        }
    }
}
