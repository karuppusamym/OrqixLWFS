package com.orqix.stub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** Fault injection of the MCP endpoint: each mode, how many requests it affects, and which requests. */
class McpFaultModesTest extends McpTestSupport {

    private static final String LOOKUP = "{\"vendorName\":\"Acme Ltd\"}";

    @Test
    void aFaultAffectsOnlyToolCallsByDefaultAndExpiresAfterTimes() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"status500\",\"times\":2}");
        String session = initialize();                                   // initialize is not a tools/call: unaffected

        Response first = rpc(toolCall(), session);
        Response second = rpc(toolCall(), session);
        Response third = rpc(toolCall(), session);

        assertThat(first.status()).isEqualTo(500);
        assertThat(second.status()).isEqualTo(500);
        assertThat(third.status()).isEqualTo(200);
        assertThat(get("/__mcp/faults").json().get("mode").stringValue()).isEqualTo("none");
        assertThat(get("/__mcp/faults").json().get("applied").intValue()).isEqualTo(2);
    }

    @Test
    void onAnyAffectsEveryRequestIncludingInitialize() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"status500\",\"times\":1,\"on\":\"any\"}");

        assertThat(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).status())
                .isEqualTo(500);
        assertThat(initialize()).isNotBlank();
    }

    @Test
    void status429CarriesRetryAfter() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"status429\",\"times\":1}");

        Response response = rpc(toolCall(), initialize());

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.header("Retry-After")).isEqualTo("1");
    }

    @Test
    void status401AsksForABearerToken() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"status401\",\"times\":1}");

        Response response = rpc(toolCall(), initialize());

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void toolErrorAnswersWithAnIsErrorResult() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"toolError\",\"times\":1}");
        String session = initialize();

        JsonNode failed = rpc(toolCall(), session).json().get("result");
        JsonNode recovered = rpc(toolCall(), session).json().get("result");

        assertThat(failed.get("isError").booleanValue()).isTrue();
        assertThat(recovered.get("isError").booleanValue()).isFalse();
    }

    @Test
    void invalidJsonAnswersWithAnUnparsableBody() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"invalidJson\",\"times\":1}");

        Response response = rpc(toolCall(), initialize());

        assertThat(response.status()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThatThrownBy(response::json).isInstanceOf(RuntimeException.class);
    }

    @Test
    void sseAnswersWithAnEventStreamCarryingTheSameMessage() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"sse\",\"times\":1}");

        Response response = rpc(toolCall(), initialize());

        assertThat(response.header("Content-Type")).startsWith("text/event-stream");
        assertThat(response.body()).startsWith("event: message\ndata: ").endsWith("\n\n");
        String data = response.body().lines().filter(line -> line.startsWith("data: ")).findFirst().orElseThrow()
                .substring(6);
        assertThat(JSON.readTree(data).get("result").get("structuredContent").get("riskScore").intValue()).isEqualTo(72);
    }

    @Test
    void delayHoldsTheReply() throws Exception {
        post("/__mcp/faults", null, "{\"mode\":\"delay\",\"delayMs\":600,\"times\":1}");
        String session = initialize();

        long started = System.nanoTime();
        Response response = rpc(toolCall(), session);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.status()).isEqualTo(200);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(550);
    }

    @Test
    void faultRequestsAreValidated() throws Exception {
        assertThat(post("/__mcp/faults", null, "{\"mode\":\"melt\"}").status()).isEqualTo(400);
        assertThat(post("/__mcp/faults", null, "{\"mode\":\"status500\",\"times\":0}").status()).isEqualTo(400);
        assertThat(post("/__mcp/faults", null, "{\"mode\":\"status500\",\"delayMs\":5}").status()).isEqualTo(400);
        assertThat(post("/__mcp/faults", null, "{\"mode\":\"status500\",\"on\":\"sometimes\"}").status()).isEqualTo(400);
        assertThat(post("/__mcp/faults", null, "{\"mode\":\"status500\",\"extra\":1}").status()).isEqualTo(400);
        assertThat(post("/__mcp/faults", null, "[]").status()).isEqualTo(400);
        assertThat(post("/__mcp/faults", null, "{\"mode\":\"none\"}").status()).isEqualTo(200);
    }

    @Test
    void theOrdersFaultsAreIndependentOfTheMcpFaults() throws Exception {
        arm("{\"mode\":\"status500\",\"times\":1}");

        assertThat(rpc(toolCall(), initialize()).status()).isEqualTo(200);
        assertThat(createOrder("k1", VALID_BODY).status()).isEqualTo(500);
    }

    private static String toolCall() {
        return "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"vendor_risk_lookup\","
                + "\"arguments\":" + LOOKUP + "}}";
    }
}
