package com.orqix.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orqix.sdk.CallbackTokenClient.CallbackPoll;
import com.orqix.sdk.model.CallbackResumed;
import com.orqix.sdk.model.CallbackTokenIssued;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallbackTokenClientTest {

    private static final String WS = OrqixClientTest.WS;
    private static final String TOKEN = "ocb_" + "A".repeat(43);
    private static final String STATE = """
            {"waitId":"0193a0c4-6666-7000-8000-000000000006","runId":"%s","runReference":"ORQ-1043","nodeId":"ship",
             "state":"waiting","reason":null,"suspended":false,"expiresAt":"2026-11-10T04:45:30Z","resumedAt":null,
             "fields":[{"name":"trackingNumber","type":"text","required":true}],"result":null}
            """.formatted(OrqixClientTest.RUN_ID);

    private ScriptedServer server;

    @BeforeEach
    void start() throws Exception {
        server = new ScriptedServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void pollSendsOnlyTheCallbackTokenAndHonoursTheEtag() {
        server.once("GET", "/workspaces/" + WS + "/callbacks/wait", 200, STATE, Map.of("ETag", "\"v1\""));
        server.once("GET", "/workspaces/" + WS + "/callbacks/wait", 304, null, Map.of("ETag", "\"v1\""));
        CallbackTokenClient wait = CallbackTokenClient.create(server.baseUrl(), WS, TOKEN);

        CallbackPoll first = wait.poll(null);
        CallbackPoll second = wait.poll(first.etag());

        assertFalse(first.notModified());
        assertEquals("waiting", first.state().state());
        assertEquals("trackingNumber", first.state().fields().get(0).name());
        assertTrue(second.notModified());
        assertNull(second.state());
        ScriptedServer.Received request = server.received().get(1);
        assertEquals(TOKEN, request.header("X-Orqix-Callback-Token"));
        assertEquals("\"v1\"", request.header("If-None-Match"));
        assertNull(request.header("Authorization"));
        assertNull(request.query(), "the token never travels in the URL");
    }

    @Test
    void resumeSendsTheResultWithItsKey() {
        server.once("POST", "/workspaces/" + WS + "/callbacks/wait/resume", 200,
                "{\"waitId\":\"0193a0c4-6666-7000-8000-000000000006\",\"runId\":\"" + OrqixClientTest.RUN_ID
                        + "\",\"runReference\":\"ORQ-1043\",\"nodeId\":\"ship\",\"state\":\"resumed\","
                        + "\"resumedAt\":\"2026-11-09T05:00:00Z\",\"acceptedFields\":[\"trackingNumber\"]}");
        CallbackTokenClient wait = CallbackTokenClient.create(server.baseUrl(), WS, TOKEN);

        CallbackResumed resumed = wait.resume("shipment-0001", Map.of("trackingNumber", "1Z999"));

        assertEquals("resumed", resumed.state());
        ScriptedServer.Received request = server.received().get(0);
        assertEquals("shipment-0001", request.header("Idempotency-Key"));
        assertEquals("{\"result\":{\"trackingNumber\":\"1Z999\"}}", request.body());
    }

    @Test
    void alreadyResumedIsAnApiError() {
        server.once("POST", "/callbacks/wait/resume", 409,
                "{\"status\":409,\"code\":\"CALLBACK_ALREADY_RESUMED\",\"correlationId\":\"c-1\"}");
        CallbackTokenClient wait = CallbackTokenClient.create(server.baseUrl(), WS, TOKEN);
        OrqixApiException e = assertThrows(OrqixApiException.class, () -> wait.resume("shipment-0002", Map.of()));
        assertEquals("CALLBACK_ALREADY_RESUMED", e.code());
    }

    @Test
    void issueTokenUsesTheBearerClientAndTheTokenClientReusesItsServer() {
        server.once("POST", "/callback-waits/0193a0c4-6666-7000-8000-000000000006/token", 201,
                "{\"waitId\":\"0193a0c4-6666-7000-8000-000000000006\",\"token\":\"" + TOKEN + "\","
                        + "\"expiresAt\":\"2026-11-10T04:45:30Z\",\"pollPath\":\"/api/v1/workspaces/" + WS
                        + "/callbacks/wait\",\"resumePath\":\"/api/v1/workspaces/" + WS + "/callbacks/wait/resume\","
                        + "\"header\":\"X-Orqix-Callback-Token\"}");
        server.once("GET", "/callbacks/wait", 200, STATE);
        OrqixClient client = OrqixClient.builder().baseUrl(server.baseUrl()).workspaceId(WS).token(() -> "t").build();

        CallbackTokenIssued issued = client.issueCallbackToken("0193a0c4-6666-7000-8000-000000000006");
        CallbackPoll poll = client.callbackTokenClient(issued.token()).poll(null);

        assertEquals("waiting", poll.state().state());
        assertEquals("Bearer t", server.received().get(0).header("Authorization"));
        assertNull(server.received().get(1).header("Authorization"));
    }

    @Test
    void aTokenWithControlCharactersIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CallbackTokenClient.create(server.baseUrl(), WS, "ocb_x\r\nX-Evil: 1"));
    }
}
