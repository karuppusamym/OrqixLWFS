package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.orqix.integration.EgressPolicy;
import com.orqix.support.Fixtures;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** B05.2: connection definitions, bindings with egress policy (T-SEC-07), connection test, secret-free responses. */
class ConnectionsApiTest extends IntegrationTestBase {

    @Autowired
    EgressPolicy egress;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void resetResolver() {
        egress.setResolver(null);
    }

    private Map<String, Object> binding(String url, String authType, String secretRef) {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("type", authType);
        if (secretRef != null) {
            auth.put("secretRef", secretRef);
        }
        return Map.of("baseUrl", url, "auth", auth, "enabled", true);
    }

    private String etag() throws Exception {
        JsonNode detail = api("wade.admin").get("/workspaces/{ws}/connections/{key}", SANDBOX, "procurement").body();
        return "\"r" + detail.path("binding").path("revision").asInt() + "\"";
    }

    @Test
    void listAndDetailNeverExposeSecretsAndHideBindingFromReaders() throws Exception {
        Response list = api("dana.designer").get("/workspaces/{ws}/connections", PURCHASING);
        assertThat(list.status()).isEqualTo(200);
        JsonNode procurement = list.body().path("items").get(0);
        assertThat(procurement.path("key").asString()).isEqualTo("procurement");
        assertThat(procurement.path("operations").findValues("name").toString()).contains("createOrder", "health");
        Response asDesigner = api("dana.designer").get("/workspaces/{ws}/connections/{key}", PURCHASING, "procurement");
        assertThat(asDesigner.body().path("binding").isNull()).isTrue();
        Response asAdmin = api("wade.admin").get("/workspaces/{ws}/connections/{key}", PURCHASING, "procurement");
        assertThat(asAdmin.body().path("binding").path("baseUrl").asString()).isNotBlank();
        assertThat(api("rhea.requester").get("/workspaces/{ws}/connections", PURCHASING).status()).isEqualTo(403);
        assertThat(api("fred.facilities").get("/workspaces/{ws}/connections/{key}", PURCHASING, "procurement").status())
                .isEqualTo(404);
    }

    @Test
    void egressPolicyRejectsUnsafeTargets_T_SEC_07() throws Exception {
        egress.setResolver(host -> switch (host) {
            case "public.test.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})};
            case "internal.test.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})};
            default -> InetAddress.getAllByName(host);
        });
        String[] rejected = {
                "https://not-allowlisted.example.com",
                "https://127.0.0.1",
                "https://169.254.169.254",
                "https://10.1.2.3",
                "https://internal.test.example",
                "https://user:secret@public.test.example",
                "http://public.test.example:443",
                "ftp://public.test.example",
        };
        for (String url : rejected) {
            Response response = api("wade.admin").put(binding(url, "none", null), etag(),
                    "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
            assertThat(response.status()).as(url + " " + response).isEqualTo(422);
            assertThat(response.code()).isEqualTo("EGRESS_NOT_ALLOWED");
        }
        Response ok = api("wade.admin").put(binding("https://public.test.example/api", "bearerToken", "procurement-token"),
                etag(), "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(ok.body().path("auth").path("secretRef").asString()).isEqualTo("procurement-token");
        assertThat(ok.toString()).doesNotContain("Bearer");
        // Restore the stub binding for other tests.
        Response restored = api("wade.admin").put(binding(Stub.baseUrl(), "none", null), etag(),
                "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
        assertThat(restored.status()).isEqualTo(200);
    }

    @Test
    void bindingRequiresIfMatchOnceItExists() throws Exception {
        Response missing = api("wade.admin").put(binding(Stub.baseUrl(), "none", null), null,
                "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
        assertThat(missing.status()).isEqualTo(428);
        Response stale = api("wade.admin").put(binding(Stub.baseUrl(), "none", null), "\"r0\"",
                "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().has("currentRevision")).isTrue();
        Response designer = api("dana.designer").put(binding(Stub.baseUrl(), "none", null), etag(),
                "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
        assertThat(designer.status()).isEqualTo(403);
    }

    @Test
    void registerNextVersionAndRejectInvalidDefinitions() throws Exception {
        ObjectNode definition = (ObjectNode) Fixtures.procurementConnection();
        definition.put("key", "procurement-copy");
        Response first = api("wade.admin").post(Map.of("definition", definition),
                "/workspaces/{ws}/connections/{key}/versions", SANDBOX, "procurement-copy");
        assertThat(first.status()).as(first.toString()).isEqualTo(201);
        assertThat(first.body().path("definitionHash").asString()).hasSize(64);
        Response duplicate = api("wade.admin").post(Map.of("definition", definition),
                "/workspaces/{ws}/connections/{key}/versions", SANDBOX, "procurement-copy");
        assertThat(duplicate.status()).isEqualTo(409);
        ObjectNode invalid = definition.deepCopy();
        invalid.put("version", 2);
        ((ObjectNode) invalid.path("operations").path("health")).put("testable", "yes");
        Response bad = api("wade.admin").post(Map.of("definition", invalid),
                "/workspaces/{ws}/connections/{key}/versions", SANDBOX, "procurement-copy");
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void testConnectionOnlyForTestableOperations() throws Exception {
        jdbc.sql("UPDATE connection_binding SET base_url = :url, enabled = true, auth_type = 'none', secret_ref = NULL WHERE workspace_id = :ws AND connection_key = 'procurement'")
                .param("url", Stub.baseUrl()).param("ws", SANDBOX).update();
        Response refused = api("wade.admin").post(Map.of("version", 1, "operation", "createOrder"),
                "/workspaces/{ws}/connections/{key}/test", SANDBOX, "procurement");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("OPERATION_NOT_TESTABLE");
        Response ok = api("wade.admin").post(Map.of("version", 1, "operation", "health"),
                "/workspaces/{ws}/connections/{key}/test", SANDBOX, "procurement");
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(ok.body().path("outcome").asString()).isEqualTo("succeeded");
        assertThat(ok.body().path("httpStatus").asInt()).isEqualTo(200);
    }
}
