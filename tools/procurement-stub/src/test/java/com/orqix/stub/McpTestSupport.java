package com.orqix.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import tools.jackson.databind.JsonNode;

/** JSON-RPC plumbing for the MCP endpoint tests. */
abstract class McpTestSupport extends StubTestSupport {

    /** POSTs one JSON-RPC message to /mcp with the given session header (null = none). */
    protected Response rpc(String body, String session) throws Exception {
        return rpc(body, session, null);
    }

    protected Response rpc(String body, String session, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url("/mcp")))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return send(builder.build());
    }

    protected String initialize() throws Exception {
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}", null);
        assertThat(response.status()).isEqualTo(200);
        return response.header("Mcp-Session-Id");
    }

    protected JsonNode call(String session, String tool, String arguments) throws Exception {
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}", session);
        assertThat(response.status()).isEqualTo(200);
        return response.json();
    }
}
