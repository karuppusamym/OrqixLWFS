package com.orqix.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** The demo MCP endpoint over real HTTP: protocol handshake, the two tools, validation and the session rules. */
class McpEndpointTest extends McpTestSupport {

    @Test
    void initializeNegotiatesTheVersionAndIssuesASession() throws Exception {
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.header("Mcp-Session-Id")).isNotBlank();
        JsonNode result = response.json().get("result");
        assertThat(response.json().get("id").intValue()).isEqualTo(1);
        assertThat(result.get("protocolVersion").stringValue()).isEqualTo("2025-03-26");
        assertThat(result.get("capabilities").has("tools")).isTrue();
        assertThat(result.get("serverInfo").get("name").stringValue()).isEqualTo("orqix-procurement-stub");
    }

    @Test
    void anUnknownProtocolVersionIsAnsweredWithTheLatestOne() throws Exception {
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"1999-01-01\"}}", null);

        assertThat(response.json().get("result").get("protocolVersion").stringValue()).isEqualTo("2025-06-18");
    }

    @Test
    void aNotificationIsAcceptedWithoutABody() throws Exception {
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", initialize());

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void toolsListOffersBothToolsWithStrictInputSchemas() throws Exception {
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", initialize());

        JsonNode tools = response.json().get("result").get("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).get("name").stringValue()).isEqualTo("vendor_risk_lookup");
        assertThat(tools.get(1).get("name").stringValue()).isEqualTo("vendor_financials");
        JsonNode schema = tools.get(0).get("inputSchema");
        assertThat(schema.get("required").get(0).stringValue()).isEqualTo("vendorName");
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
        assertThat(tools.get(0).get("description").stringValue()).isNotBlank();
    }

    @Test
    void vendorRiskLookupReturnsTheFixtureAsStructuredAndTextContent() throws Exception {
        String session = initialize();

        JsonNode result = call(session, "vendor_risk_lookup", "{\"vendorName\":\"Acme Ltd\"}").get("result");

        assertThat(result.get("isError").booleanValue()).isFalse();
        JsonNode structured = result.get("structuredContent");
        assertThat(structured.get("vendorName").stringValue()).isEqualTo("Acme Ltd");
        assertThat(structured.get("riskScore").intValue()).isEqualTo(72);
        assertThat(structured.get("riskBand").stringValue()).isEqualTo("high");
        assertThat(structured.get("sanctionsHit").booleanValue()).isFalse();
        assertThat(structured.get("registryId").stringValue()).matches("VR-[0-9A-F]{6}");
        assertThat(JSON.readTree(result.get("content").get(0).get("text").stringValue())).isEqualTo(structured);
        assertThat(call(session, "vendor_risk_lookup", "{\"vendorName\":\"Acme Ltd\"}").get("result")
                .get("structuredContent")).isEqualTo(structured);
    }

    @Test
    void theDemoVendorsCoverEveryRoute() throws Exception {
        String session = initialize();

        assertThat(risk(session, "Globex Corporation")).containsExactly(18, "low", false);
        assertThat(risk(session, "Initech")).containsExactly(45, "medium", false);
        assertThat(risk(session, "Umbrella Trading")).containsExactly(91, "high", true);
        assertThat(risk(session, "Totally Sanctioned Holdings")).containsExactly(95, "high", true);
        assertThat(risk(session, "  acme ltd ")).containsExactly(72, "high", false);
    }

    @Test
    void anUnknownVendorGetsADeterministicScoreInRange() throws Exception {
        String session = initialize();

        Object[] first = risk(session, "Zeta Industries");
        Object[] second = risk(session, "zeta industries");

        assertThat(second).isEqualTo(first);
        int score = (Integer) first[0];
        assertThat(score).isBetween(5, 95);
        assertThat(first[1]).isEqualTo(VendorRegistry.band(score));
    }

    @Test
    void vendorFinancialsIsDeterministic() throws Exception {
        String session = initialize();

        JsonNode first = call(session, "vendor_financials", "{\"vendorName\":\"Acme Ltd\"}").get("result")
                .get("structuredContent");
        JsonNode second = call(session, "vendor_financials", "{\"vendorName\":\"Acme Ltd\"}").get("result")
                .get("structuredContent");

        assertThat(second).isEqualTo(first);
        assertThat(first.get("annualRevenueUsd").longValue()).isBetween(10_000_000L, 100_000_000L);
        assertThat(first.get("creditRating").stringValue()).isEqualTo("BB");
    }

    @Test
    void invalidToolArgumentsAreAProtocolError() throws Exception {
        String session = initialize();

        for (String arguments : new String[] { "{}", "{\"vendorName\":42}", "{\"vendorName\":\"A\",\"extra\":1}",
                "{\"vendorName\":\"\"}" }) {
            JsonNode response = call(session, "vendor_risk_lookup", arguments);
            assertThat(response.get("error").get("code").intValue()).as(arguments).isEqualTo(-32602);
            assertThat(response.has("result")).isFalse();
        }
    }

    @Test
    void aBlankVendorNameIsAToolErrorNotAProtocolError() throws Exception {
        JsonNode result = call(initialize(), "vendor_risk_lookup", "{\"vendorName\":\"   \"}").get("result");

        assertThat(result.get("isError").booleanValue()).isTrue();
        assertThat(result.has("structuredContent")).isFalse();
    }

    @Test
    void anUnknownToolAndAnUnknownMethodAreProtocolErrors() throws Exception {
        String session = initialize();

        assertThat(call(session, "delete_everything", "{}").get("error").get("code").intValue()).isEqualTo(-32602);
        Response unknown = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}", session);
        assertThat(unknown.json().get("error").get("code").intValue()).isEqualTo(-32601);
    }

    @Test
    void requestsNeedAKnownSession() throws Exception {
        Response missing = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", null);
        Response unknown = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "not-a-session");

        assertThat(missing.status()).isEqualTo(400);
        assertThat(unknown.status()).isEqualTo(404);
    }

    @Test
    void deleteEndsTheSession() throws Exception {
        String session = initialize();

        Response deleted = send(HttpRequest.newBuilder(URI.create(url("/mcp"))).header("Mcp-Session-Id", session)
                .DELETE().build());
        Response after = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(after.status()).isEqualTo(404);
    }

    @Test
    void malformedBodiesAreRejectedAndGetIsNotAllowed() throws Exception {
        Response garbage = rpc("{not json", null);
        Response notAnObject = rpc("[1,2]", null);
        Response get = get("/mcp");

        assertThat(garbage.status()).isEqualTo(400);
        assertThat(garbage.json().get("error").get("code").intValue()).isEqualTo(-32700);
        assertThat(notAnObject.status()).isEqualTo(400);
        assertThat(get.status()).isEqualTo(405);
    }

    @Test
    void theCallLogRecordsToolCallsAndTheirArguments() throws Exception {
        String session = initialize();
        call(session, "vendor_risk_lookup", "{\"vendorName\":\"Acme Ltd\"}");

        JsonNode calls = get("/__mcp/calls").json();

        assertThat(calls.get("toolCalls").intValue()).isEqualTo(1);
        JsonNode last = calls.get("calls").get(calls.get("calls").size() - 1);
        assertThat(last.get("method").stringValue()).isEqualTo("tools/call");
        assertThat(last.get("tool").stringValue()).isEqualTo("vendor_risk_lookup");
        assertThat(last.get("arguments").get("vendorName").stringValue()).isEqualTo("Acme Ltd");
    }

    @Test
    void resetClearsSessionsFaultsAndTheCallLog() throws Exception {
        String session = initialize();
        call(session, "vendor_risk_lookup", "{\"vendorName\":\"Acme Ltd\"}");
        post("/__mcp/faults", null, "{\"mode\":\"status500\"}");

        post("/__reset", null, null);

        assertThat(get("/__mcp/calls").json().get("count").intValue()).isZero();
        assertThat(get("/__mcp/faults").json().get("mode").stringValue()).isEqualTo("none");
        assertThat(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session).status()).isEqualTo(404);
    }

    private Object[] risk(String session, String vendor) throws Exception {
        JsonNode structured = call(session, "vendor_risk_lookup", "{\"vendorName\":\"" + vendor + "\"}").get("result")
                .get("structuredContent");
        return new Object[] { structured.get("riskScore").intValue(), structured.get("riskBand").stringValue(),
                structured.get("sanctionsHit").booleanValue() };
    }
}
