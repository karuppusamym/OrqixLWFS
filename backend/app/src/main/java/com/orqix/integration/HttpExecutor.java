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
            String detail, String attentionReason, ObjectNode result, String remoteReference, long durationMs) {

        public String classification() {
            return switch (outcome) {
                case SUCCEEDED -> "succeeded";
                case RETRYABLE -> "retryable";
                case PERMANENT -> "permanent";
                case UNKNOWN -> "unknown";
            };
        }
    }

    /** Credentials resolved for one call; built from the binding and the secret file. */
    public record Target(String baseUrl, String authType, String authHeaderName, String secretRef) {
    }

    private final Json json;
    private final SecretStore secrets;
    private final Map<Integer, HttpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Schema> responseSchemas = new ConcurrentHashMap<>();
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public HttpExecutor(Json json, SecretStore secrets) {
        this.json = json;
        this.secrets = secrets;
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
        long started = System.nanoTime();
        HttpRequest request;
        try {
            request = buildRequest(target, operation, body, effectKey, timeoutSeconds, secretValue);
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
        try {
            CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new LimitedSubscriber());
            response = future.get(timeoutSeconds + 2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(started, Observation.MAYBE_APPLIED, operation.effectClass(), null, "INTERRUPTED",
                    "Call interrupted", null, null);
        } catch (TimeoutException e) {
            return finish(started, Observation.MAYBE_APPLIED, operation.effectClass(), null, "TIMEOUT",
                    "No complete response within " + timeoutSeconds + " s", null, null);
        } catch (ExecutionException e) {
            return classifyFailure(started, e.getCause(), operation.effectClass(), timeoutSeconds);
        }
        int status = response.statusCode();
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
            return finish(started, Observation.CLIENT_ERROR, operation.effectClass(), status, "HTTP_" + status,
                    "HTTP " + status, null, null);
        }
        // 2xx
        if (!operation.successStatuses().contains(status)) {
            return finish(started, Observation.INVALID_RESPONSE, operation.effectClass(), status, "UNEXPECTED_STATUS",
                    "HTTP " + status + " is not a declared success status", null, null);
        }
        byte[] bytes = response.body();
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
            case MAYBE_APPLIED -> effectClass == EffectClass.NON_IDEMPOTENT ? Outcome.UNKNOWN : Outcome.RETRYABLE;
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
            int timeoutSeconds, String secretValue) {
        String base = target.baseUrl().endsWith("/") ? target.baseUrl().substring(0, target.baseUrl().length() - 1)
                : target.baseUrl();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + operation.path()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header(CorrelationId.HEADER, CorrelationId.current());
        if ("bearerToken".equals(target.authType())) {
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

    private boolean validResponse(ConnectionDefinition definition, Operation operation, JsonNode body) {
        String key = definition.key() + "|" + definition.version() + "|" + operation.name();
        Schema schema = responseSchemas.computeIfAbsent(key, k -> {
            ObjectNode schemaNode = (ObjectNode) operation.responseSchema().deepCopy();
            schemaNode.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            return schemaRegistry.getSchema(schemaNode);
        });
        return schema.validate(body).isEmpty();
    }

    private static String remoteReference(JsonNode body, ObjectNode result) {
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
            for (ByteBuffer item : items) {
                size += item.remaining();
                if (size > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
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
