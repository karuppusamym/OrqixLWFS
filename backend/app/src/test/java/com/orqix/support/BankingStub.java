package com.orqix.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;

/**
 * Stubbed external banking effects on the shared, approved WireMock origin (see {@link Stub}): party screening,
 * loan booking and chargebacks. Every POST needs an {@code Idempotency-Key}; the same key with the same body returns
 * the original record (200), the same key with another body is refused (422), as the connection definitions in
 * examples/connections promise. Nothing here contacts a real service.
 */
public final class BankingStub {

    /** One HTTP request received, including replays. */
    public record Call(String path, String idempotencyKey, String body, boolean replay) {
    }

    private record Fault(String path, int status) {
    }

    private static final Map<String, String[]> RECORDS = new LinkedHashMap<>();
    private static final List<Call> CALLS = new ArrayList<>();
    private static final List<Fault> FAULTS = new ArrayList<>();
    private static int sequence;

    private BankingStub() {
    }

    public static synchronized void reset() {
        RECORDS.clear();
        CALLS.clear();
        FAULTS.clear();
        sequence = 0;
    }

    /** The next {@code times} requests to {@code path} (for example {@code /banking/bookings}) fail with {@code status}. */
    public static synchronized void fail(String path, int times, int status) {
        for (int i = 0; i < times; i++) {
            FAULTS.add(new Fault(path, status));
        }
    }

    /** Distinct records created at {@code path}: retries and replays of one key count once. */
    public static synchronized long created(String path) {
        return RECORDS.keySet().stream().filter(k -> k.startsWith(path + "|")).count();
    }

    /** Every request received at {@code path}, including failed attempts and replays. */
    public static synchronized List<Call> calls(String path) {
        return CALLS.stream().filter(c -> c.path().equals(path)).toList();
    }

    /** Request bodies of the records created at {@code path}, in creation order. */
    public static synchronized List<String> createdBodies(String path) {
        return RECORDS.entrySet().stream().filter(e -> e.getKey().startsWith(path + "|")).map(e -> e.getValue()[1]).toList();
    }

    public static ResponseDefinition handle(Request request) {
        return handle0(request);
    }

    private static synchronized ResponseDefinition handle0(Request request) {
        String path = request.getUrl();
        String body = request.getBodyAsString();
        String key = request.getHeader("Idempotency-Key");
        if (!"POST".equals(request.getMethod().getName())) {
            return response(404, "{\"error\":\"not found\"}");
        }
        if (!path.equals("/banking/sanctions/screen") && !path.equals("/banking/bookings") && !path.equals("/banking/chargebacks")) {
            return response(404, "{\"error\":\"not found\"}");
        }
        Fault fault = FAULTS.stream().filter(f -> f.path().equals(path)).findFirst().orElse(null);
        if (fault != null) {
            FAULTS.remove(fault);
            CALLS.add(new Call(path, key, body, false));
            return response(fault.status(), "{\"error\":\"injected\"}");
        }
        if (key == null) {
            return response(400, "{\"error\":\"Idempotency-Key required\"}");
        }
        String recordKey = path + "|" + key;
        String[] existing = RECORDS.get(recordKey);
        if (existing != null) {
            CALLS.add(new Call(path, key, body, true));
            if (!existing[1].equals(body)) {
                return response(422, "{\"error\":\"different body for key\"}");
            }
            return response(200, existing[0]);
        }
        CALLS.add(new Call(path, key, body, false));
        sequence++;
        String answer = switch (path) {
            case "/banking/sanctions/screen" -> {
                // A possible match when the party name contains "sanction" (any case); deterministic for tests.
                boolean hit = field(body, "partyName").toLowerCase().contains("sanction");
                yield "{\"hit\":" + hit + ",\"referenceId\":\"SCR-" + sequence + "\",\"listVersion\":\"stub-1\"}";
            }
            case "/banking/bookings" -> "{\"bookingId\":\"BK-" + sequence + "\",\"status\":\"BOOKED\"}";
            default -> "{\"chargebackId\":\"CB-" + sequence + "\",\"status\":\"OPEN\"}";
        };
        RECORDS.put(recordKey, new String[] {answer, body});
        return response(201, answer);
    }

    /** Raw text of a number member of a request body, exactly as the platform sent it (no parsing, no rounding). */
    public static String rawNumber(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?[0-9][0-9.eE+-]*)").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private static ResponseDefinition response(int status, String body) {
        return new ResponseDefinitionBuilder().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(body).build();
    }
}
