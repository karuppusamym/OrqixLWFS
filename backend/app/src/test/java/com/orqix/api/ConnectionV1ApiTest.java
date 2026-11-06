package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.IntegrationTestBase;
import com.orqix.support.ServiceNowStub;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Registration of connection definitions v1 (S130) through the API: a valid v1 definition registers next to v0 ones,
 * cross-field rules come back as field errors with their CONN_V1_* code and pointer, schema errors as schema
 * keywords, and a testable v1 read is tested with input through the real binding.
 */
class ConnectionV1ApiTest extends IntegrationTestBase {

    private ObjectNode example(String key) throws Exception {
        ObjectNode definition = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                "examples", "connections", "servicenow-table.connection.json")));
        definition.put("key", key);
        return definition;
    }

    private Response register(String key, JsonNode definition) throws Exception {
        return api("wade.admin").post(Map.of("definition", definition), "/workspaces/{ws}/connections/{key}/versions",
                SANDBOX, key);
    }

    @Test
    void validV1RegistersAndListsTheNewEffectClasses() throws Exception {
        String key = "sn-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = register(key, example(key));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.body().path("definition").path("schemaVersion").asString()).isEqualTo("1.0");
        JsonNode list = api("wade.admin").get("/workspaces/{ws}/connections", SANDBOX).body();
        String classes = "";
        for (JsonNode item : list.path("items")) {
            if (key.equals(item.path("key").asString())) {
                classes = item.path("operations").findValuesAsString("effectClass").toString();
            }
        }
        assertThat(classes).contains("idempotentByLookup", "idempotentNatural", "readOnly", "nonIdempotent");
    }

    @Test
    void ruleViolationsAreFieldErrorsWithCodesAndPointers() throws Exception {
        String key = "sn-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode definition = example(key);
        ((ObjectNode) definition.at("/operations/getIncident/headers")).set("Authorization",
                json.object().put("const", "Basic c2VjcmV0"));
        ((ObjectNode) definition.at("/operations/createIncident/reconcile")).put("operation", "addWorkNote");
        Response refused = register(key, definition);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        String errors = refused.body().path("fieldErrors").toString();
        assertThat(errors).contains("CONN_V1_HEADER_FORBIDDEN", "/definition/operations/getIncident/headers/Authorization",
                "CONN_V1_RECONCILE", "/definition/operations/createIncident/reconcile/operation");
        assertThat(errors).doesNotContain("c2VjcmV0");
    }

    @Test
    void schemaErrorsUseTheV1Schema() throws Exception {
        String key = "sn-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode definition = example(key);
        ((ObjectNode) definition.at("/operations/attachFile")).put("maxTimeoutSeconds", 500);
        Response refused = register(key, definition);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body().path("fieldErrors").toString()).contains("/definition/operations/attachFile");
        // The same timeout on schema 0.1 is refused by the v0 rules (at most 30).
        ObjectNode v0 = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                "examples", "connections", "procurement.connection.json")));
        v0.put("key", key);
        ((ObjectNode) v0.at("/operations/health")).put("maxTimeoutSeconds", 60);
        assertThat(register(key, v0).status()).isEqualTo(422);
    }

    @Test
    void testableV1ReadIsTestedWithInputThroughTheBinding() throws Exception {
        String key = "sn-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(register(key, example(key)).status()).isEqualTo(201);
        Response bound = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/servicenow",
                "auth", Map.of("type", "none"), "enabled", true), null,
                "/workspaces/{ws}/connections/{key}/binding", SANDBOX, key);
        assertThat(bound.status()).as(bound.toString()).isEqualTo(200);
        ServiceNowStub.reset();
        String sysId = ServiceNowStub.seed("INC0030001", "1", "0123456789abcdef0123456789abcdef", "Test");
        Response tested = api("wade.admin").post(Map.of("version", 1, "operation", "getIncident", "input",
                Map.of("sysId", sysId)), "/workspaces/{ws}/connections/{key}/test", SANDBOX, key);
        assertThat(tested.status()).as(tested.toString()).isEqualTo(200);
        assertThat(tested.body().path("outcome").asString()).isEqualTo("succeeded");
        Response missing = api("wade.admin").post(Map.of("version", 1, "operation", "getIncident", "input",
                Map.of("sysId", "fedcba9876543210fedcba9876543210")), "/workspaces/{ws}/connections/{key}/test",
                SANDBOX, key);
        assertThat(missing.body().path("outcome").asString()).isEqualTo("failed");
        assertThat(missing.body().path("errorCode").asString()).isEqualTo("INCIDENT_NOT_FOUND");
        Response refused = api("wade.admin").post(Map.of("version", 1, "operation", "createIncident"),
                "/workspaces/{ws}/connections/{key}/test", SANDBOX, key);
        assertThat(refused.code()).isEqualTo("OPERATION_NOT_TESTABLE");
    }
}
