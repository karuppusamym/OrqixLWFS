package com.orqix.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.IntegrationTestBase;
import com.orqix.support.McpTestTokens;
import com.orqix.support.TestUsers;

/**
 * CG-K01 (S148): the command line tool's bearer tokens (audience orqix-cli). The person acts with their own
 * permissions on the routes the tool uses; other routes, other audiences, expired or foreign-signed tokens are refused.
 * Machine tokens keep their own chain.
 */
class CliTokenAccessTest extends IntegrationTestBase {

    private static String cli(String user) {
        return McpTestTokens.token(user, "orqix-cli", Instant.now().plusSeconds(300), TestUsers.ISSUER, false);
    }

    private Response call(MockHttpServletRequestBuilder builder, String token) throws Exception {
        return api(null).send(builder.header("Authorization", "Bearer " + token));
    }

    private Response get(String token, String path, Object... vars) throws Exception {
        return call(MockMvcRequestBuilders.get("/api/v1" + path, vars), token);
    }

    private Response post(String token, Object body, String path, Object... vars) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/v1" + path, vars).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(body)), token);
    }

    @Test
    void aCliTokenActsAsThePersonOnTheToolsRoutesWithoutCsrf() throws Exception {
        Response me = get(cli("dana.designer"), "/me");
        assertThat(me.status()).as(me.toString()).isEqualTo(200);
        assertThat(me.body().toString()).contains("Dana Designer");
        // Create a workflow (POST without a CSRF token: bearer only, no cookie).
        String key = "cli-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = post(cli("dana.designer"), Map.of("key", key, "name", "From the CLI", "templateId",
                "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(get(cli("dana.designer"), "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id).status()).isEqualTo(200);
        // The person's own permissions: a viewer cannot publish, a foreign person sees nothing.
        Response publish = call(MockMvcRequestBuilders.post("/api/v1/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id)
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content(json.write(Map.of("draftRevision", 1))), cli("vic.viewer"));
        assertThat(publish.status()).isEqualTo(403);
        assertThat(get(cli("fred.facilities"), "/workspaces/{ws}/workflows/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(get(cli("olga.operator"), "/workspaces/{ws}/runs?limit=5", PURCHASING).status()).isEqualTo(200);
    }

    @Test
    void routesOutsideTheToolAndUnusableTokensAreRefused() throws Exception {
        // A route the tool does not use: refused even for an administrator.
        assertThat(get(cli("wade.admin"), "/workspaces/{ws}/access/grants", PURCHASING).status()).isEqualTo(403);
        // Expired, foreign-signed, wrong issuer.
        assertThat(get(McpTestTokens.token("dana.designer", "orqix-cli", Instant.now().minusSeconds(120), TestUsers.ISSUER,
                false), "/me").status()).isEqualTo(401);
        assertThat(get(McpTestTokens.token("dana.designer", "orqix-cli", Instant.now().plusSeconds(300), TestUsers.ISSUER,
                true), "/me").status()).isEqualTo(401);
        assertThat(get(McpTestTokens.token("dana.designer", "orqix-cli", Instant.now().plusSeconds(300),
                "https://idp.other.example/realms/x", false), "/me").status()).isEqualTo(401);
        // An MCP token is not a CLI token: it falls to the machine chain, which does not serve this route.
        Response mcp = get(McpTestTokens.token("dana.designer"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(mcp.status()).isIn(401, 403);
        // Machine clients keep working on their own routes.
        Response machine = get("machine.cli-test", "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(machine.status()).isIn(401, 403);
    }
}
