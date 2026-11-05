package com.orqix.support;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;

/**
 * Order tracking status endpoint on the shared WireMock origin ({@link Stub}) for "Keep checking until..." tests
 * (docs/build/S129): {@code POST /tracking/status} answers the queued responses in order, then repeats the last one
 * (or {@code {"state":"pending"}} when nothing was queued). Every call is recorded with its time and body.
 */
public final class TrackingStub {

    /** One request received. */
    public record Call(Instant at, String body) {
    }

    private record Answer(int status, String body) {
    }

    private static final Deque<Answer> QUEUE = new ArrayDeque<>();
    private static final List<Call> CALLS = new ArrayList<>();
    private static Answer last = new Answer(200, "{\"state\":\"pending\",\"trackingRef\":\"\"}");

    private TrackingStub() {
    }

    public static synchronized void reset() {
        QUEUE.clear();
        CALLS.clear();
        last = new Answer(200, "{\"state\":\"pending\",\"trackingRef\":\"\"}");
    }

    /** Queues answers with these states (HTTP 200), for example pending, pending, shipped. */
    public static synchronized void states(String... states) {
        for (String state : states) {
            // Every mapped field is in every answer: a missing mapped field makes the answer unusable (S129 limits).
            QUEUE.add(new Answer(200, "{\"state\":\"" + state + "\",\"trackingRef\":\""
                    + ("shipped".equals(state) ? "TRK-1" : "") + "\"}"));
        }
    }

    /** Queues {@code times} answers with this HTTP status (no usable body). */
    public static synchronized void fail(int status, int times) {
        for (int i = 0; i < times; i++) {
            QUEUE.add(new Answer(status, "{\"error\":\"unavailable\"}"));
        }
    }

    public static synchronized int calls() {
        return CALLS.size();
    }

    public static synchronized List<Call> received() {
        return List.copyOf(CALLS);
    }

    static synchronized ResponseDefinition handle(Request request) {
        if (!"POST".equals(request.getMethod().getName()) || !request.getUrl().startsWith("/tracking/status")) {
            return new ResponseDefinitionBuilder().withStatus(404).build();
        }
        CALLS.add(new Call(Instant.now(), request.getBodyAsString()));
        Answer answer = QUEUE.isEmpty() ? last : QUEUE.pollFirst();
        if (answer.status() == 200) {
            last = answer;
        }
        return new ResponseDefinitionBuilder().withStatus(answer.status()).withHeader("Content-Type", "application/json")
                .withBody(answer.body()).build();
    }
}
