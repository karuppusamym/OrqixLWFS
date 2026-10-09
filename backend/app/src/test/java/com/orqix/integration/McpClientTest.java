package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orqix.integration.McpClient.Deadline;
import com.orqix.integration.McpClient.Endpoint;
import com.orqix.integration.McpClient.FailureKind;
import com.orqix.integration.McpClient.McpException;
import com.orqix.integration.McpClient.Session;
import com.orqix.integration.McpClient.Tool;
import com.orqix.integration.McpClient.ToolResult;
import com.orqix.shared.json.Json;
import com.orqix.support.McpStub;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The MCP client against the WireMock MCP server: handshake, sessions, reply shapes and every failure class. */
class McpClientTest {

    private final Json json = new Json(JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build());
    private final McpClient client = new McpClient(json);

    @BeforeEach
    void reset() {
        McpStub.reset();
    }

    private Session connect() {
        return client.connect(new Endpoint(McpStub.url(), null), new Deadline(Duration.ofSeconds(10)));
    }

    private ToolResult lookup(Session session, String vendor) {
        return session.callTool("vendor_risk_lookup", json.object().put("vendorName", vendor));
    }

    @Test
    void handshakeListAndCallUseOneSessionAndTheNegotiatedVersion() {
        try (Session session = connect()) {
            List<Tool> tools = session.listTools();
            ToolResult result = lookup(session, "Acme Ltd");

            assertThat(tools).extracting(Tool::name).containsExactly("vendor_risk_lookup", "vendor_financials");
            assertThat(tools.get(0).inputSchema().path("required").get(0).asString()).isEqualTo("vendorName");
            assertThat(result.isError()).isFalse();
            assertThat(result.structured().path("riskScore").asInt()).isEqualTo(72);
            assertThat(result.text()).contains("\"riskBand\":\"high\"");
        }
        assertThat(McpStub.calls()).extracting(McpStub.Call::method).containsExactly("initialize",
                "notifications/initialized", "tools/list", "tools/call", "session/delete");
        String session = McpStub.calls().get(2).session();
        assertThat(session).startsWith("sess-");
        assertThat(McpStub.calls().get(3).session()).isEqualTo(session);
        assertThat(McpStub.calls().get(4).session()).isEqualTo(session);
    }

    @Test
    void aBearerTokenIsSentOnEveryRequestAndAWrongOneIsRejected() {
        McpStub.requireToken("mcp-secret");
        try (Session session = client.connect(new Endpoint(McpStub.url(), "mcp-secret"),
                new Deadline(Duration.ofSeconds(10)))) {
            session.listTools();
        }
        assertThat(McpStub.calls()).filteredOn(c -> !c.method().equals("session/delete"))
                .allMatch(c -> "Bearer mcp-secret".equals(c.authorization()));

        assertThatThrownBy(() -> client.connect(new Endpoint(McpStub.url(), "wrong"), new Deadline(Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(McpException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.REJECTED);
                    assertThat(e.httpStatus()).isEqualTo(401);
                });
    }

    @Test
    void anEventStreamReplyIsParsedLikeAJsonReply() {
        try (Session session = connect()) {
            McpStub.fault(McpStub.Mode.SSE, 1, 0);

            ToolResult result = lookup(session, "Globex Corporation");

            assertThat(result.structured().path("riskScore").asInt()).isEqualTo(18);
        }
    }

    @Test
    void aToolFailureIsAResultNotAnException() {
        try (Session session = connect()) {
            McpStub.fault(McpStub.Mode.TOOL_ERROR, 1, 0);

            ToolResult failed = lookup(session, "Acme Ltd");
            ToolResult protocolError = session.callTool("vendor_risk_lookup", json.object().put("wrong", "x"));

            assertThat(failed.isError()).isTrue();
            assertThat(failed.structured()).isNull();
            assertThat(protocolError.isError()).isTrue();
        }
    }

    @Test
    void serverFailuresAreClassifiedForRetry() {
        try (Session session = connect()) {
            McpStub.fault(McpStub.Mode.STATUS500, 1, 0);
            assertThatThrownBy(() -> lookup(session, "Acme Ltd")).isInstanceOfSatisfying(McpException.class,
                    e -> assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE));

            McpStub.fault(McpStub.Mode.STATUS429, 1, 0);
            assertThatThrownBy(() -> lookup(session, "Acme Ltd")).isInstanceOfSatisfying(McpException.class,
                    e -> assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE));

            McpStub.fault(McpStub.Mode.STATUS401, 1, 0);
            assertThatThrownBy(() -> lookup(session, "Acme Ltd")).isInstanceOfSatisfying(McpException.class, e -> {
                assertThat(e.kind()).isEqualTo(FailureKind.REJECTED);
                assertThat(e.httpStatus()).isEqualTo(401);
            });
        }
    }

    @Test
    void anUnreachableServerIsUnavailable() {
        assertThatThrownBy(() -> client.connect(new Endpoint("http://127.0.0.1:1/mcp", null),
                new Deadline(Duration.ofSeconds(5)))).isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE));
    }

    @Test
    void unreadableOrOversizedRepliesAreInvalid() {
        try (Session session = connect()) {
            McpStub.fault(McpStub.Mode.INVALID_JSON, 1, 0);
            assertThatThrownBy(() -> lookup(session, "Acme Ltd")).isInstanceOfSatisfying(McpException.class,
                    e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID));

            McpStub.fault(McpStub.Mode.TOO_LARGE, 1, 0);
            assertThatThrownBy(() -> lookup(session, "Acme Ltd")).isInstanceOfSatisfying(McpException.class,
                    e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID));
        }
    }

    @Test
    void redirectsAreNeverFollowed() {
        try (Session session = connect()) {
            McpStub.fault(McpStub.Mode.REDIRECT, 1, 0);

            assertThatThrownBy(() -> lookup(session, "Acme Ltd")).isInstanceOfSatisfying(McpException.class, e -> {
                assertThat(e.kind()).isEqualTo(FailureKind.REJECTED);
                assertThat(e.httpStatus()).isEqualTo(302);
            });
        }
        assertThat(McpStub.toolCalls()).hasSize(1);
    }

    @Test
    void theDeadlineBoundsASlowServer() {
        try (Session session = client.connect(new Endpoint(McpStub.url(), null), new Deadline(Duration.ofSeconds(2)))) {
            McpStub.fault(McpStub.Mode.DELAY, 1, 5000);
            long started = System.nanoTime();

            assertThatThrownBy(() -> lookup(session, "Acme Ltd")).isInstanceOfSatisfying(McpException.class,
                    e -> assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        }
    }

    @Test
    void anExpiredDeadlineFailsBeforeAnyRequest() {
        Deadline expired = new Deadline(Duration.ZERO);

        assertThatThrownBy(() -> client.connect(new Endpoint(McpStub.url(), null), expired))
                .isInstanceOfSatisfying(McpException.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE));
        assertThat(McpStub.calls()).isEmpty();
    }

    @Test
    void aRefusedInitializeIsAProtocolFailure() {
        McpStub.faultAny(McpStub.Mode.INVALID_JSON, 1, 0);

        assertThatThrownBy(this::connect).isInstanceOfSatisfying(McpException.class,
                e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID));
    }

    @Test
    void eventStreamsAreParsedByEventAndPrimingEventsAreIgnored() {
        List<JsonNode> messages = client.parseEvents("id: 1\ndata:\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\n"
                + "data: \"id\":5,\"result\":{}}\n\n: comment\ndata: {\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{}}\r\n\r\n");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("id").asInt()).isEqualTo(5);
        assertThat(messages.get(1).path("id").asInt()).isEqualTo(6);
    }
}
