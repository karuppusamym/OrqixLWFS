package com.orqix.stub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** With {@code stub.mcp-token} set, the MCP endpoint requires that bearer token (the control surface does not). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "stub.commit-then-timeout-ms=1500", "stub.mcp-token=demo-mcp-token" })
class McpAuthTest extends McpTestSupport {

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";

    @Test
    void aRequestWithoutTheTokenIsRefused() throws Exception {
        Response response = rpc(INITIALIZE, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.json().get("error").get("message").stringValue()).isEqualTo("Unauthorized");
    }

    @Test
    void aWrongTokenIsRefusedAndTheRightOneAccepted() throws Exception {
        assertThat(rpc(INITIALIZE, null, "wrong").status()).isEqualTo(401);
        assertThat(rpc(INITIALIZE, null, "demo-mcp-token").status()).isEqualTo(200);
    }

    @Test
    void theControlSurfaceNeedsNoToken() throws Exception {
        assertThat(get("/__mcp/calls").status()).isEqualTo(200);
        assertThat(get("/health").status()).isEqualTo(200);
    }
}
