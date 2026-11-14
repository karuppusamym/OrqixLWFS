package com.orqix.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orqix.sdk.model.EventPage;
import com.orqix.sdk.model.Lifecycle;
import com.orqix.sdk.model.MessageAccepted;
import com.orqix.sdk.model.Run;
import com.orqix.sdk.model.RunDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrqixClientTest {

    static final String WS = "00000000-0000-7000-8000-000000000201";
    static final String RUN_ID = "0193a0c4-1111-7000-8000-000000000001";
    static final String RUN_JSON = """
            {"id":"%s","reference":"ORQ-1043","workflow":{"id":"0193a0c4-2222-7000-8000-000000000002",
             "key":"purchase-approval","name":"Purchase approval"},"release":{"id":"0193a0c4-3333-7000-8000-000000000003",
             "releaseNumber":4},"businessKey":"PO-7","requestTitle":null,"initiator":{"id":"0193a0c4-4444-7000-8000-000000000004",
             "displayName":"Order bot"},"lifecycle":"waiting","outcome":null,"outcomeKind":null,"currentSteps":[
             {"nodeId":"approve","label":"Approve","kind":"approval","since":"2026-11-09T10:15:30+05:30"}],
             "attention":null,"createdAt":"2026-11-09T04:45:30Z","updatedAt":"2026-11-09T04:45:30Z","endedAt":null,
             "isTest":false,"version":2,"addedByALaterRelease":{"nested":true}}
            """.formatted(RUN_ID);

    private ScriptedServer server;
    private final List<DeprecationNotice> notices = new ArrayList<>();
    private OrqixClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void start() throws Exception {
        server = new ScriptedServer();
        client = OrqixClient.builder().baseUrl(server.baseUrl()).workspaceId(WS).token(() -> "machine-token")
                .onDeprecation(notices::add).build();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void startRunSendsTheIdempotencyKeyAndReadsATypedRunIgnoringUnknownFields() {
        server.once("POST", "/workspaces/" + WS + "/runs", 201, RUN_JSON);

        Run run = client.startRun("start-PO-7-0001", "0193a0c4-2222-7000-8000-000000000002", Map.of("amount", 1200));

        assertEquals("ORQ-1043", run.reference());
        assertEquals(Lifecycle.WAITING, run.lifecycle());
        assertEquals(4L, run.release().releaseNumber());
        // The offset in the answer is kept as an instant; the machine time zone plays no part.
        assertEquals(Instant.parse("2026-11-09T04:45:30Z"), run.currentSteps().get(0).since().toInstant());
        ScriptedServer.Received request = server.received().get(0);
        assertEquals("/api/v1/workspaces/" + WS + "/runs", request.path());
        assertEquals("Bearer machine-token", request.header("Authorization"));
        assertEquals("start-PO-7-0001", request.header("Idempotency-Key"));
        JsonNode body = json.readTree(request.body());
        assertEquals(1200, body.path("input").path("amount").asInt());
        assertFalse(body.has("businessKey"), "null fields are not sent");
    }

    @Test
    void startRunRefusesAMalformedIdempotencyKeyBeforeCallingTheServer() {
        assertThrows(IllegalArgumentException.class, () -> client.startRun("short", "w", Map.of()));
        assertTrue(server.received().isEmpty());
    }

    @Test
    void getRunAndEventsUseTheirPathsAndQuery() {
        String detail = RUN_JSON.trim();
        detail = detail.substring(0, detail.length() - 1) + ",\"tasks\":[],\"allowedActions\":{\"cancel\":{\"allowed\":true},"
                + "\"retry\":{\"allowed\":false,\"reasonCode\":\"RUN_NOT_RECOVERABLE\"},\"reconcile\":{\"allowed\":false},"
                + "\"suspend\":{\"allowed\":true},\"resume\":{\"allowed\":false}}}";
        server.once("GET", "/runs/" + RUN_ID, 200, detail);
        server.once("GET", "/runs/" + RUN_ID + "/events", 200,
                "{\"items\":[{\"seq\":5,\"type\":\"RUN_STARTED\",\"occurredAt\":\"2026-11-09T04:45:30Z\",\"data\":{}}],"
                        + "\"nextAfter\":5,\"hasMore\":false,\"lifecycle\":\"waiting\"}");

        RunDetail run = client.getRun(RUN_ID);
        EventPage events = client.listRunEvents(RUN_ID, 4, 50);

        assertTrue(run.allowedActions().cancel().allowed());
        assertEquals("RUN_NOT_RECOVERABLE", run.allowedActions().retry().reasonCode());
        assertEquals(5L, events.nextAfter());
        assertEquals("after=4&limit=50", server.received().get(1).query());
        assertNull(server.received().get(0).header("Idempotency-Key"));
    }

    @Test
    void sendMessageHelperBuildsTheRequest() {
        server.once("POST", "/workspaces/" + WS + "/messages", 202,
                "{\"id\":\"0193a0c4-5555-7000-8000-000000000005\",\"status\":\"buffered\",\"matched\":0,\"delivered\":[],"
                        + "\"bufferedUntil\":\"2026-11-10T04:45:30Z\"}");

        MessageAccepted accepted = client.sendMessage("payment-PO-7", "payment-received", "PO-7", Map.of("paid", true));

        assertEquals("buffered", accepted.status());
        JsonNode body = json.readTree(server.received().get(0).body());
        assertEquals("payment-received", body.path("name").asString());
        assertEquals("PO-7", body.path("correlationKey").asString());
        assertTrue(body.path("payload").path("paid").asBoolean());
        assertEquals("payment-PO-7", server.received().get(0).header("Idempotency-Key"));
    }

    @Test
    void problemAnswersBecomeApiExceptionsWithCodeAndCorrelation() {
        server.once("POST", "/runs", 429,
                "{\"type\":\"https://orqix.example/problems/rate-limited\",\"title\":\"Too many\",\"status\":429,"
                        + "\"code\":\"RATE_LIMITED\",\"detail\":\"Slow down\",\"correlationId\":\"c-42\"}",
                Map.of("Retry-After", "7"));

        OrqixApiException e = assertThrows(OrqixApiException.class,
                () -> client.startRun("start-PO-7-0002", "w", Map.of()));

        assertEquals(429, e.status());
        assertEquals("RATE_LIMITED", e.code());
        assertEquals("c-42", e.correlationId().orElseThrow());
        assertEquals(7L, e.retryAfterSeconds().orElseThrow());
        assertTrue(e.isTransient());
    }

    @Test
    void anAnswerWithoutProblemDocumentKeepsTheStatus() {
        server.once("GET", "/runs/" + RUN_ID, 502, "<html>bad gateway</html>");
        OrqixApiException e = assertThrows(OrqixApiException.class, () -> client.getRun(RUN_ID));
        assertEquals("HTTP_ERROR", e.code());
        assertEquals(502, e.status());
    }

    @Test
    void anUnreachableServerIsATransientTransportError() {
        server.close();
        OrqixApiException e = assertThrows(OrqixApiException.class, () -> client.getRun(RUN_ID));
        assertEquals("TRANSPORT_ERROR", e.code());
        assertTrue(e.isTransient());
    }

    @Test
    void aDeprecatedOperationIsReportedOnce() {
        Map<String, String> headers = Map.of("Deprecation", "@1767225600", "Sunset", "Wed, 30 Jun 2027 00:00:00 GMT",
                "Link", "<https://orqix.example/docs/api-compatibility>; rel=\"deprecation\"");
        server.once("GET", "/runs/" + RUN_ID + "/events", 200,
                "{\"items\":[],\"nextAfter\":0,\"hasMore\":false,\"lifecycle\":\"running\"}", headers);
        server.once("GET", "/runs/" + RUN_ID + "/events", 200,
                "{\"items\":[],\"nextAfter\":0,\"hasMore\":false,\"lifecycle\":\"running\"}", headers);

        client.listRunEvents(RUN_ID, 0, null);
        client.listRunEvents(RUN_ID, 0, null);

        assertEquals(1, notices.size());
        assertEquals(OrqixOperation.LIST_RUN_EVENTS, notices.get(0).operation());
        assertEquals("Wed, 30 Jun 2027 00:00:00 GMT", notices.get(0).sunset());
    }

    @Test
    void theTokenSupplierIsAskedForEveryCall() {
        int[] calls = {0};
        OrqixClient rotating = OrqixClient.builder().baseUrl(server.baseUrl()).workspaceId(WS)
                .token(() -> "token-" + (++calls[0])).build();
        server.always("GET", "/runs/" + RUN_ID + "/events", 200,
                "{\"items\":[],\"nextAfter\":0,\"hasMore\":false,\"lifecycle\":\"running\"}");

        rotating.listRunEvents(RUN_ID, 0, null);
        rotating.listRunEvents(RUN_ID, 0, null);

        assertEquals("Bearer token-1", server.received().get(0).header("Authorization"));
        assertEquals("Bearer token-2", server.received().get(1).header("Authorization"));
    }

    @Test
    void operationPathsEncodeTheirParameters() {
        assertEquals("/workspaces/a%20b/runs/x%2Fy", OrqixOperation.GET_RUN.path("a b", "x/y"));
        assertThrows(IllegalArgumentException.class, () -> OrqixOperation.GET_RUN.path("only-one"));
        assertEquals(OrqixOperation.Credential.CALLBACK_TOKEN, OrqixOperation.RESUME_CALLBACK_WAIT.credential());
        assertTrue(OrqixOperation.COMPLETE_EXTERNAL_JOB.idempotencyKeyRequired());
        assertFalse(OrqixOperation.FETCH_EXTERNAL_JOBS.idempotencyKeyRequired());
    }
}
