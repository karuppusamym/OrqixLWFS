package com.orqix.sdk;

import com.orqix.sdk.model.CallbackResumeRequest;
import com.orqix.sdk.model.CallbackResumed;
import com.orqix.sdk.model.CallbackWaitState;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Callback-token helper (docs/build/S98): an outside system that was given the one-time token of a wait polls the
 * wait and resumes it with a result. The token is the only credential and travels in the
 * {@code X-Orqix-Callback-Token} header, never in a URL; no bearer token is sent.
 *
 * <pre>{@code
 * CallbackTokenClient wait = CallbackTokenClient.create("https://orqix.example/api/v1", workspaceId, token);
 * CallbackPoll poll = wait.poll(null);
 * if (!poll.notModified() && "waiting".equals(poll.state().state())) {
 *     wait.resume("shipment-" + shipmentId, Map.of("trackingNumber", "1Z999"));
 * }
 * }</pre>
 */
public final class CallbackTokenClient {

    private static final String HEADER = "X-Orqix-Callback-Token";

    /** The answer of a poll: {@code notModified} after a 304 for the ETag passed, otherwise the current state. */
    public record CallbackPoll(boolean notModified, CallbackWaitState state, String etag) {
    }

    private final Transport transport;
    private final String token;

    CallbackTokenClient(Transport transport, String token) {
        if (token == null || token.isBlank() || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("A callback token is required (printable ASCII, as issued)");
        }
        this.transport = transport;
        this.token = token;
    }

    public static CallbackTokenClient create(String baseUrl, String workspaceId, String token) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new CallbackTokenClient(new Transport(baseUrl, workspaceId, http, Duration.ofSeconds(30), notice -> {
        }), token);
    }

    /** Polls the wait. Pass the previous {@link CallbackPoll#etag()} to get {@code notModified} while nothing changed. */
    public CallbackPoll poll(String etag) {
        Transport.Answer<CallbackWaitState> answer = transport.send(OrqixOperation.POLL_CALLBACK_WAIT, List.of(),
                Map.of(), Arrays.asList(HEADER, token, "If-None-Match", etag), null, CallbackWaitState.class, true);
        String newEtag = answer.headers().firstValue("ETag").orElse(etag);
        return new CallbackPoll(answer.status() == 304, answer.body(), newEtag);
    }

    /**
     * Resumes the wait once. A repeat with the same key and result returns the original answer; a different result
     * after a resume is refused with {@code CALLBACK_ALREADY_RESUMED}.
     */
    public CallbackResumed resume(String idempotencyKey, Map<String, ?> result) {
        Transport.checkIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(result, "result");
        return transport.send(OrqixOperation.RESUME_CALLBACK_WAIT, List.of(), Map.of(),
                Arrays.asList(HEADER, token, "Idempotency-Key", idempotencyKey),
                new CallbackResumeRequest(new LinkedHashMap<>(result)), CallbackResumed.class, false).body();
    }
}
