package com.orqix.integration;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLHandshakeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.orqix.integration.ConnectionDefinition.EffectClass;
import com.orqix.integration.ConnectionDefinition.Operation;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.CorrelationId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Outbound HTTP for connection operations (S5 section 6.1 step 3 and 6.2): JDK {@link HttpClient}, redirects never
 * followed, connect timeout {@code min(5 s, timeout)}, request timeout per node, response bodies capped at 1 MiB,
 * idempotency header = effect key for {@code idempotentWithKey}. Every outcome is classified by the effect class.
 */
@Component
public class HttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpExecutor.class);
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** Classified result of one attempt. */
    public enum Outcome {
        SUCCEEDED, RETRYABLE, PERMANENT, UNKNOWN
    }

    /** What was observed on the wire, before applying the effect class (S5 section 6.2 rows). */
    public enum Observation {
        NOT_SENT, MAYBE_APPLIED, DECLINED_429, SUCCESS, INVALID_RESPONSE, REDIRECT, CLIENT_ERROR
    }

    /**
     * @param attentionReason S5 section 6.3 reason when the outcome stops automatic progress (null on success)
     * @param errorCode       short code, e.g. CONNECT_FAILED, TIMEOUT, HTTP_500, RESPONSE_INVALID
     * @param detail          redacted: status and error class only, never bodies, headers or credentials
     */
    public record CallResult(Outcome outcome, Observation observation, Integer httpStatus, String errorCode,
            String detail, String attentionReason, ObjectNode result, String remoteReference, long durationMs,
            Page page, String failureMessage) {

        public CallResult(Outcome outcome, Observation observation, Integer httpStatus, String errorCode,
                String detail, String attentionReason, ObjectNode result, String remoteReference, long durationMs) {
            this(outcome, observation, httpStatus, errorCode, detail, attentionReason, result, remoteReference,
                    durationMs, null, null);
        }

        CallResult withPage(Page page) {
            return new CallResult(outcome, observation, httpStatus, errorCode, detail, attentionReason, result,
                    remoteReference, durationMs, page, failureMessage);
        }

        CallResult withFailureMessage(String message) {
            return new CallResult(outcome, observation, httpStatus, errorCode, detail, attentionReason, result,
                    remoteReference, durationMs, page, message);
        }

        public String classification() {
            return switch (outcome) {
                case SUCCEEDED -> "succeeded";
                case RETRYABLE -> "retryable";
                case PERMANENT -> "permanent";
                case UNKNOWN -> "unknown";
            };
        }
    }

    /**
     * What a v1 caller needs beyond the mapped result (S130): the parsed body for pagination and lookups, the next
     * page link and the result keys whose lists were cut at the row bound.
     */
    public record Page(JsonNode body, String nextLink, List<String> truncated) {
    }

    /** Credentials resolved for one call; built from the binding and the secret file. */
    public record Target(String baseUrl, String authType, String authHeaderName, String secretRef,
            java.util.UUID workspaceId) {
        public Target(String baseUrl, String authType, String authHeaderName, String secretRef) {
            this(baseUrl, authType, authHeaderName, secretRef, null);
        }
    }

    private final Json json;
    private final SecretStore secrets;
    private final MicrosoftGraphDocuments documents;
    private final Map<Integer, HttpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Schema> responseSchemas = new ConcurrentHashMap<>();
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public HttpExecutor(Json json, SecretStore secrets, MicrosoftGraphDocuments documents) {
        this.json = json;
        this.secrets = secrets;
        this.documents = documents;
    }

    /**
     * Performs the call. {@code secretMissing} outcomes are reported by {@link #resolveSecret}; this method assumes
     * the credential is available.
     *
     * @param resultMapping result key to JSON Pointer into the response body
     * @param effectKey     sent as the operation's idempotency header for {@code idempotentWithKey}
     */
    public CallResult call(Target target, ConnectionDefinition definition, Operation operation, JsonNode body,
            Map<String, String> resultMapping, String effectKey, int timeoutSeconds, String secretValue) {
        return call(target, definition, operation, body, resultMapping, effectKey, timeoutSeconds, secretValue,
                V1Requests.Context.NONE);
    }

    /** As above, with the request files and pagination parameters of a v1 operation. */
    CallResult call(Target target, ConnectionDefinition definition, Operation operation, JsonNode body,
            Map<String, String> resultMapping, String effectKey, int timeoutSeconds, String secretValue,
            V1Requests.Context context) {
        long started = System.nanoTime();
        if (definition.isMicrosoftGraph()) {
            try {
                ObjectNode response = documents.read(target, definition.key(), operation, body, secretValue, timeoutSeconds);
                return mapResponse(started, definition, operation, resultMapping, 200,
                        json.write(response).getBytes(StandardCharsets.UTF_8));
            } catch (MicrosoftGraphDocuments.Failure e) {
                if (Integer.valueOf(401).equals(e.status()) && ("oauth2ClientCredentials".equals(target.authType()) || "oauth2AuthorizationCode".equals(target.authType())) && secrets != null)
                    secrets.invalidateOAuth(target.workspaceId(), target.secretRef());
                return finish(started, e.observation(), operation.effectClass(), e.status(), e.code(),
                        "Microsoft document read failed (" + e.code() + ")", null, null);
            }
        }
        HttpRequest request;
        try {
            request = buildRequest(target, operation, body, effectKey, timeoutSeconds, secretValue, context);
        } catch (IllegalArgumentException e) {
            return finish(started, Observation.CLIENT_ERROR, operation.effectClass(), null, "INVALID_REQUEST",
                    "Request could not be built", null, null);
        }
        HttpClient client = clients.computeIfAbsent(Math.min(5, Math.max(1, timeoutSeconds)), seconds -> HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(seconds))
                .version(HttpClient.Version.HTTP_1_1)
                .build());
        HttpResponse<byte[]> response;
        LimitedSubscriber.Holder limited = new LimitedSubscriber.Holder();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, limited.handler());
        try {
            response = future.get(timeoutSeconds + 2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            future.cancel(true); // abort the exchange instead of leaving it running unobserved
            Thread.currentThread().interrupt();
            return finish(started, Observation.MAYBE_APPLIED, operation.effectClass(), null, "INTERRUPTED",
                    "Call interrupted", null, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return finish(started, Observation.MAYBE_APPLIED, operation.effectClass(), null, "TIMEOUT",
                    "No complete response within " + timeoutSeconds + " s", null, null);
        } catch (ExecutionException e) {
            // The body limit wins over whatever error the client reported after the subscriber cancelled.
            return classifyFailure(started, limited.exceeded() ? new ResponseTooLargeException() : e.getCause(),
                    operation.effectClass(), timeoutSeconds);
        }
        int status = response.statusCode();
        if (status == 401 && ("oauth2ClientCredentials".equals(target.authType()) || "oauth2AuthorizationCode".equals(target.authType())) && secrets != null)
            secrets.invalidateOAuth(target.workspaceId(), target.secretRef());
        if (status >= 300 && status < 400) {
            return finish(started, Observation.REDIRECT, operation.effectClass(), status, "UNEXPECTED_STATUS",
                    "HTTP " + status + " (redirects are not followed)", null, null);
        }
        if (status == 429) {
            return finish(started, Observation.DECLINED_429, operation.effectClass(), status, "RATE_LIMITED",
                    "HTTP 429", null, null);
        }
        if (status == 408 || status >= 500) {
            return finish(started, Observation.MAYBE_APPLIED, operation.effectClass(), status, "HTTP_" + status,
                    "HTTP " + status, null, null);
        }
        if (status >= 400) {
            if (operation.v1()) {
                CallResult mapped = mappedError(started, operation, status, response.body());
                if (mapped != null) {
                    return mapped;
                }
            }
            return finish(started, Observation.CLIENT_ERROR, operation.effectClass(), status, "HTTP_" + status,
                    "HTTP " + status, null, null);
        }
        // 2xx
        if (!operation.successStatuses().contains(status)) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "UNEXPECTED_STATUS",
                    "HTTP " + status + " is not a declared success status", null, null);
        }
        if (operation.v1()) {
            return mapV1Response(started, definition, operation, resultMapping, status, response.body(),
                    response.headers());
        }
        return mapResponse(started, definition, operation, resultMapping, status, response.body());
    }

    /** v1 error mapping: a declared client error becomes a known rejection with the business code. */
    private CallResult mappedError(long started, Operation operation, int status, byte[] bytes) {
        for (JsonNode error : operation.source().path("errors")) {
            if (error.path("status").asInt() != status) {
                continue;
            }
            String code = error.path("code").asString();
            String message = error.has("message") ? error.get("message").asString() : null;
            if (error.has("bodyPointer") && bytes != null && bytes.length > 0) {
                try {
                    JsonNode text = json.parse(bytes).at(error.get("bodyPointer").asString());
                    if (text.isString() && !text.asString().isBlank()) {
                        message = text.asString();
                    }
                } catch (IllegalArgumentException e) {
                    // Not JSON: keep the fixed message. The body is never logged.
                }
            }
            return finish(started, Observation.CLIENT_ERROR, operation.effectClass(), status, code,
                    "HTTP " + status + " mapped to " + code, null, null)
                    .withFailureMessage(message == null ? null : truncate(message, 200));
        }
        return null;
    }

    /** v1 results: JSON pointers with array indexes, /[*] list projections and /@headers/ response headers. */
    private CallResult mapV1Response(long started, ConnectionDefinition definition, Operation operation,
            Map<String, String> resultMapping, int status, byte[] bytes, java.net.http.HttpHeaders headers) {
        if (bytes == null) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_TOO_LARGE",
                    "Response body exceeds 1 MiB", null, null);
        }
        JsonNode responseBody;
        try {
            responseBody = bytes.length == 0 ? json.object() : json.parse(bytes);
        } catch (IllegalArgumentException e) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_INVALID",
                    "Response body is not JSON", null, null);
        }
        if (!validResponse(definition, operation, responseBody)) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_INVALID",
                    "Response body does not match the operation response schema", null, null);
        }
        V1Results.Mapped mapped;
        try {
            mapped = V1Results.map(operation, resultMapping, responseBody, headers, json);
        } catch (V1Results.Unresolved e) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_INVALID",
                    "Result pointer " + e.getMessage() + " did not resolve", null, null);
        }
        String remoteReference = remoteReference(responseBody, mapped.result());
        return finish(started, Observation.SUCCESS, operation.effectClass(), status, null, "HTTP " + status,
                mapped.result(), remoteReference)
                .withPage(new Page(responseBody, V1Results.nextLink(headers), mapped.truncated()));
    }

    /**
     * Maps a document that did not come from this call (a lookup's found record placed where this operation's
     * response keeps it, CG-C02) with the operation's result pointers.
     *
     * @return the result, or null when a pointer does not resolve
     */
    ObjectNode mapRecorded(Operation operation, Map<String, String> resultMapping, JsonNode document) {
        try {
            return V1Results.map(operation, resultMapping, document, null, json).result();
        } catch (V1Results.Unresolved e) {
            return null;
        }
    }

    private CallResult mapResponse(long started, ConnectionDefinition definition, Operation operation,
            Map<String, String> resultMapping, int status, byte[] bytes) {
        if (bytes == null) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_TOO_LARGE",
                    "Response body exceeds 1 MiB", null, null);
        }
        JsonNode responseBody;
        try {
            responseBody = bytes.length == 0 ? json.object() : json.parse(bytes);
        } catch (IllegalArgumentException e) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_INVALID",
                    "Response body is not JSON", null, null);
        }
        if (!validResponse(definition, operation, responseBody)) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_INVALID",
                    "Response body does not match the operation response schema", null, null);
        }
        ObjectNode result = json.object();
        for (Map.Entry<String, String> mapping : resultMapping.entrySet()) {
            JsonNode value = responseBody.at(mapping.getValue());
            if (value.isMissingNode() || value.isNull() || value.isContainer()) {
                return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "RESPONSE_INVALID",
                        "Result pointer " + mapping.getValue() + " did not resolve", null, null);
            }
            result.set(mapping.getKey(), value);
        }
        String remoteReference = remoteReference(responseBody, result);
        return finish(started, Observation.SUCCESS, operation.effectClass(), status, null, "HTTP " + status, result,
                remoteReference);
    }

    /** S5 section 6.2 classification table. */
    public static Outcome classify(Observation observation, EffectClass effectClass) {
        return switch (observation) {
            case NOT_SENT, DECLINED_429 -> Outcome.RETRYABLE;
            // idempotentByLookup is UNKNOWN here; EffectDispatcher then runs the lookup before recording (CG-C02).
            case MAYBE_APPLIED -> effectClass == EffectClass.NON_IDEMPOTENT
                    || effectClass == EffectClass.IDEMPOTENT_BY_LOOKUP ? Outcome.UNKNOWN : Outcome.RETRYABLE;
            case SUCCESS -> Outcome.SUCCEEDED;
            case INVALID_RESPONSE, REDIRECT -> effectClass == EffectClass.READ_ONLY ? Outcome.PERMANENT : Outcome.UNKNOWN;
            case CLIENT_ERROR -> Outcome.PERMANENT;
        };
    }

    static String attentionReason(Observation observation, Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> null;
            case RETRYABLE -> "RETRIES_EXHAUSTED";
            case UNKNOWN -> "EFFECT_UNKNOWN";
            case PERMANENT -> observation == Observation.CLIENT_ERROR ? "EFFECT_REJECTED" : "RESPONSE_INVALID";
        };
    }

    private CallResult classifyFailure(long started, Throwable error, EffectClass effectClass, int timeoutSeconds) {
        if (isNotSent(error)) {
            String code = error instanceof HttpConnectTimeoutException ? "CONNECT_TIMEOUT" : "CONNECT_FAILED";
            return finish(started, Observation.NOT_SENT, effectClass, null, code, simpleName(error), null, null);
        }
        if (error instanceof HttpTimeoutException) {
            return finish(started, Observation.MAYBE_APPLIED, effectClass, null, "TIMEOUT",
                    "No response within " + timeoutSeconds + " s", null, null);
        }
        if (error instanceof ResponseTooLargeException) {
            return finish(started, Observation.INVALID_RESPONSE, effectClass, null, "RESPONSE_TOO_LARGE",
                    "Response body exceeds 1 MiB", null, null);
        }
        if (error instanceof IOException) {
            return finish(started, Observation.MAYBE_APPLIED, effectClass, null, "CONNECTION_RESET",
                    simpleName(error), null, null);
        }
        log.warn("Unexpected HTTP client failure: {}", simpleName(error));
        return finish(started, Observation.MAYBE_APPLIED, effectClass, null, "CLIENT_FAILURE", simpleName(error), null,
                null);
    }

    private static boolean isNotSent(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof UnresolvedAddressException || t instanceof HttpConnectTimeoutException
                    || t instanceof SSLHandshakeException) {
                return true;
            }
        }
        return false;
    }

    private CallResult finish(long started, Observation observation, EffectClass effectClass, Integer status,
            String errorCode, String detail, ObjectNode result, String remoteReference) {
        Outcome outcome = classify(observation, effectClass);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new CallResult(outcome, observation, status, errorCode, truncate(detail),
                attentionReason(observation, outcome), result, remoteReference, durationMs);
    }

    private HttpRequest buildRequest(Target target, Operation operation, JsonNode body, String effectKey,
            int timeoutSeconds, String secretValue, V1Requests.Context context) {
        if (operation.v1()) {
            return buildV1Request(target, operation, body, effectKey, timeoutSeconds, secretValue, context);
        }
        String base = target.baseUrl().endsWith("/") ? target.baseUrl().substring(0, target.baseUrl().length() - 1)
                : target.baseUrl();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + operation.path()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header(CorrelationId.HEADER, CorrelationId.current());
        if ("bearerToken".equals(target.authType()) || ("oauth2ClientCredentials".equals(target.authType()) || "oauth2AuthorizationCode".equals(target.authType()))) {
            builder.header("Authorization", "Bearer " + secretValue);
        } else if ("apiKeyHeader".equals(target.authType())) {
            builder.header(target.authHeaderName(), secretValue);
        }
        if (operation.effectClass() == EffectClass.IDEMPOTENT_WITH_KEY && operation.idempotencyHeader() != null) {
            builder.header(operation.idempotencyHeader(), effectKey);
        }
        if ("GET".equals(operation.method())) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json");
            byte[] payload = json.write(body == null ? json.object() : body).getBytes(StandardCharsets.UTF_8);
            builder.method(operation.method(), HttpRequest.BodyPublishers.ofByteArray(payload));
        }
        return builder.build();
    }

    private HttpRequest buildV1Request(Target target, Operation operation, JsonNode inputs, String effectKey,
            int timeoutSeconds, String secretValue, V1Requests.Context context) {
        V1Requests.Built built = V1Requests.build(target.baseUrl(), operation, inputs, json, context);
        HttpRequest.Builder builder = HttpRequest.newBuilder(built.uri())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header(CorrelationId.HEADER, CorrelationId.current());
        boolean accept = false;
        for (Map.Entry<String, String> header : built.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
            accept |= "accept".equalsIgnoreCase(header.getKey());
        }
        if (!accept) {
            builder.header("Accept", "application/json");
        }
        if ("bearerToken".equals(target.authType()) || "oauth2ClientCredentials".equals(target.authType())
                || "oauth2AuthorizationCode".equals(target.authType())) {
            builder.header("Authorization", "Bearer " + secretValue);
        } else if ("apiKeyHeader".equals(target.authType())) {
            builder.header(target.authHeaderName(), secretValue);
        }
        if (operation.effectClass() == EffectClass.IDEMPOTENT_WITH_KEY && operation.idempotencyHeader() != null) {
            builder.header(operation.idempotencyHeader(), effectKey);
        }
        if (built.body() == null) {
            builder.method(operation.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", built.contentType());
            builder.method(operation.method(), HttpRequest.BodyPublishers.ofByteArray(built.body()));
        }
        return builder.build();
    }

    private boolean validResponse(ConnectionDefinition definition, Operation operation, JsonNode body) {
        String key = definition.key() + "|" + definition.version() + "|" + operation.name();
        Schema schema = responseSchemas.computeIfAbsent(key, k -> {
            ObjectNode schemaNode = (ObjectNode) operation.responseSchema().deepCopy();
            schemaNode.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            return schemaRegistry.getSchema(schemaNode);
        });
        return schema.validate(body).isEmpty();
    }

    static String remoteReference(JsonNode body, ObjectNode result) {
        JsonNode id = body.path("id");
        if (id.isString() || id.isNumber()) {
            return truncate(id.asString(), 200);
        }
        for (JsonNode value : result) {
            if (value.isString()) {
                return truncate(value.asString(), 200);
            }
        }
        return null;
    }

    private static String simpleName(Throwable error) {
        return error == null ? "Unknown error" : error.getClass().getSimpleName();
    }

    private static String truncate(String value) {
        return truncate(value, 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() {
            super("Response body exceeds the limit");
        }
    }

    /** Collects at most {@link #MAX_RESPONSE_BYTES}; larger bodies fail the call as an invalid response. */
    static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private Flow.Subscription subscription;
        private long size;
        private final int limit;
        /** Set before cancelling: the client may then fail the exchange with its own error, which hides this reason. */
        private volatile boolean exceeded;

        /** True when the body passed the limit, whatever error the client reported for the exchange. */
        boolean exceeded() { return exceeded; }

        /** A subscriber for {@code sendAsync} that the caller keeps, to ask {@link #exceeded()} after a failure. */
        static final class Holder {
            private volatile LimitedSubscriber subscriber;
            HttpResponse.BodyHandler<byte[]> handler(int limit) {
                return info -> subscriber = new LimitedSubscriber(limit);
            }
            HttpResponse.BodyHandler<byte[]> handler() { return handler(MAX_RESPONSE_BYTES); }
            boolean exceeded() { LimitedSubscriber s = subscriber; return s != null && s.exceeded(); }
        }

        LimitedSubscriber() { this(MAX_RESPONSE_BYTES); }
        LimitedSubscriber(int limit) { this.limit = limit; }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                size += item.remaining();
                if (size > limit) {
                    // Publish the classified failure before cancelling: JDK clients can synchronously deliver a
                    // generic cancellation error through onError, which must not replace the body-limit reason.
                    exceeded = true;
                    result.completeExceptionally(new ResponseTooLargeException());
                    buffers.clear();
                    subscription.cancel();
                    return;
                }
                buffers.add(item);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (result.isDone()) {
                return;
            }
            byte[] bytes = new byte[(int) size];
            int offset = 0;
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                buffer.get(bytes, offset, length);
                offset += length;
            }
            result.complete(bytes);
        }
    }
}
