package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.ServiceNowStub;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connection definition v1 and lookup reconciliation (CG-C01, CG-C02, docs/build/S130) against PostgreSQL and
 * Flowable through the public API, with the ServiceNow-shaped WireMock stub: get by sys_id, list with rows and a
 * pagination cap, 404 mapped to a business code on the failed route, a path value that would change the address,
 * and a create whose answer is lost, reconciled by lookup (found: no second create; not found: one safe resend).
 * Version 1 is examples/connections/servicenow-table.connection.json unchanged; version 2 is a test variant with
 * small pagination caps, a short lookup settle time and free-text urgency.
 */
class ServiceNowConnectionTest extends RuntimeTestBase {

    private static final String KEY = "servicenow-table";
    private static final String GROUP = "0123456789abcdef0123456789abcdef";

    @BeforeEach
    void registerServiceNow() throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                "examples", "connections", "servicenow-table.connection.json")));
        Response v1 = api("wade.admin").post(Map.of("definition", example),
                "/workspaces/{ws}/connections/{key}/versions", PURCHASING, KEY);
        assertThat(v1.status()).as(v1.toString()).isIn(201, 409);
        ObjectNode variant = example.deepCopy();
        variant.put("version", 2);
        ObjectNode ops = (ObjectNode) variant.path("operations");
        ObjectNode paging = (ObjectNode) ops.path("listOpenIncidents").path("pagination");
        paging.put("pageSize", 2).put("maxPages", 2).put("maxItems", 3);
        ObjectNode create = (ObjectNode) ops.path("createIncident");
        ((ObjectNode) create.path("reconcile")).put("lookupSettleSeconds", 1);
        ObjectNode createFields = (ObjectNode) create.path("requestSchema");
        createFields.set("required", json.mapper().createArrayNode().add("shortDescription"));
        ((ObjectNode) createFields.path("properties").path("urgency")).remove("enum");
        ((ObjectNode) createFields.path("properties").path("impact")).remove("enum");
        Response v2 = api("wade.admin").post(Map.of("definition", variant),
                "/workspaces/{ws}/connections/{key}/versions", PURCHASING, KEY);
        assertThat(v2.status()).as(v2.toString()).isIn(201, 409);
        int existing = jdbc.sql("SELECT count(*) FROM connection_binding WHERE workspace_id = :ws AND connection_key = :key")
                .param("ws", PURCHASING).param("key", KEY).query(Integer.class).single();
        if (existing == 0) {
            Response bound = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/servicenow",
                    "auth", Map.of("type", "none"), "enabled", true), null,
                    "/workspaces/{ws}/connections/{key}/binding", PURCHASING, KEY);
            assertThat(bound.status()).as(bound.toString()).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ workflow and run helpers

    /**
     * The purchase template with its order step replaced by a ServiceNow call; outputs are the named step results (and
     * the failure facts when {@code failedRoute}).
     */
    private UUID workflow(int version, String operation, Map<String, String> inputs, Map<String, String> results,
            Map<String, String> outputTypes, boolean failedRoute) throws Exception {
        return publishPurchase(PURCHASING, definition -> {
            ((ObjectNode) definition.path("dependencies").path("connections").get(0)).put("key", KEY)
                    .put("definitionVersion", version);
            for (JsonNode node : definition.path("nodes")) {
                if ("procure".equals(node.path("id").asString())) {
                    ObjectNode config = (ObjectNode) node.path("config");
                    config.put("connectionRef", KEY).put("operation", operation).put("timeoutSeconds", 3);
                    config.set("inputMapping", json.toTree(inputs));
                    config.set("resultMapping", json.toTree(results));
                }
            }
            ObjectNode properties = json.object();
            ObjectNode mapping = json.object();
            Map<String, String> outputs = new LinkedHashMap<>(outputTypes);
            if (failedRoute) {
                outputs.put("failureCode", "string");
                outputs.put("failureMessage", "string");
                outputs.put("failureHttpStatus", "integer");
                ((ArrayNode) definition.path("nodes")).add(json.parse("{\"id\":\"handled\",\"type\":\"end\","
                        + "\"label\":\"Not found\",\"config\":{\"businessOutcome\":\"Not found\",\"outcomeKind\":\"failed\"}}"));
                ((ArrayNode) definition.path("transitions")).add(json.parse(
                        "{\"id\":\"lookupFailed\",\"from\":\"procure\",\"to\":\"handled\",\"on\":\"failed\"}"));
            }
            outputs.forEach((name, type) -> {
                ObjectNode field = json.object().put("type", type).put("title", name);
                if ("string".equals(type)) {
                    field.put("maxLength", 200);
                }
                properties.set(name, field);
                mapping.set(name, json.object().put("fieldRef", "/steps/procure/" + name).put("required", false));
            });
            definition.set("outputSchema", json.object().put("type", "object").put("additionalProperties", false)
                    .set("properties", properties));
            definition.set("outputMapping", mapping);
        });
    }

    /** Starts a run whose title is {@code title} and approves the manager step. */
    private UUID run(UUID workflow, String title) throws Exception {
        Map<String, Object> input = input(new BigDecimal("120"));
        input.put("title", title);
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID runId = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, runId, "manager");
        return runId;
    }

    private List<ServiceNowStub.Call> calls(String method, String path) {
        return ServiceNowStub.calls().stream().filter(c -> c.method().equals(method) && c.path().equals(path)).toList();
    }

    private JsonNode succeededEvent(UUID runId) throws Exception {
        for (JsonNode event : api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, runId).body()
                .path("items")) {
            if ("EFFECT_SUCCEEDED".equals(event.path("type").asString())) {
                return event.path("data");
            }
        }
        return json.object();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void getBySysIdFillsThePathTemplateAndConstantQuery() throws Exception {
        String sysId = ServiceNowStub.seed("INC0010001", "2", GROUP, "VPN is down");
        UUID workflow = workflow(1, "getIncident", Map.of("sysId", "/input/title"),
                Map.of("number", "/result/number", "state", "/result/state"),
                Map.of("number", "string", "state", "string"), false);
        UUID runId = run(workflow, sysId);
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(run.path("output").path("number").asString()).isEqualTo("INC0010001");
        assertThat(run.path("output").path("state").asString()).isEqualTo("2");
        List<ServiceNowStub.Call> gets = calls("GET", "/api/now/table/incident/" + sysId);
        assertThat(gets).hasSize(1);
        assertThat(gets.get(0).query()).containsEntry("sysparm_exclude_reference_link", "true")
                .containsEntry("sysparm_fields", "sys_id,number,state,short_description,close_code,close_notes,correlation_id");
    }

    @Test
    void notFoundIsMappedToABusinessCodeOnTheFailedRoute() throws Exception {
        UUID workflow = workflow(1, "getIncident", Map.of("sysId", "/input/title"),
                Map.of("number", "/result/number"), Map.of("number", "string"), true);
        UUID runId = run(workflow, "fedcba9876543210fedcba9876543210");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(run.path("outcome").asString()).isEqualTo("Not found");
        assertThat(run.path("output").path("failureCode").asString()).isEqualTo("INCIDENT_NOT_FOUND");
        assertThat(run.path("output").path("failureHttpStatus").asInt()).isEqualTo(404);
        assertThat(run.path("output").path("failureMessage").asString()).isEqualTo("No Record found");
        JsonNode effect = run.path("effects").get(0);
        assertThat(effect.path("state").asString()).isEqualTo("permanentFailure");
        assertThat(effect.path("lastErrorCode").asString()).isEqualTo("INCIDENT_NOT_FOUND");
        assertThat(calls("GET", "/api/now/table/incident/fedcba9876543210fedcba9876543210")).hasSize(1);
    }

    @Test
    void aPathValueThatWouldChangeTheAddressIsNeverSent() throws Exception {
        UUID workflow = workflow(1, "getIncident", Map.of("sysId", "/input/title"),
                Map.of("number", "/result/number"), Map.of("number", "string"), false);
        UUID runId = run(workflow, "../../sys_user/0123456789abcdef0123456789abcdef");
        awaitLifecycle(PURCHASING, runId, "needsAttention", "completed");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).isEqualTo("needsAttention");
        assertThat(run.path("effects").get(0).path("lastErrorCode").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(ServiceNowStub.calls()).isEmpty();
    }

    @Test
    void listReturnsRowsAndAHeaderAndStopsAtThePaginationCap() throws Exception {
        for (int i = 1; i <= 5; i++) {
            ServiceNowStub.seed("INC002000" + i, "1", GROUP, "Printer " + i);
        }
        ServiceNowStub.seed("INC0029999", "1", "ffffffffffffffffffffffffffffffff", "Other group");
        UUID workflow = workflow(2, "listOpenIncidents", Map.of("assignmentGroup", "/input/title"),
                Map.of("incidents", "/result/[*]", "numbers", "/result/[*]/number", "total", "/@headers/X-Total-Count",
                        "first", "/result/0/number"),
                Map.of("total", "integer", "first", "string"), false);
        UUID runId = run(workflow, GROUP);
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).as(run.toString()).isEqualTo("completed");
        assertThat(run.path("output").path("total").asInt()).isEqualTo(5);
        assertThat(run.path("output").path("first").asString()).isEqualTo("INC0020001");

        List<ServiceNowStub.Call> pages = calls("GET", "/api/now/table/incident");
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).query()).containsEntry("sysparm_offset", "0").containsEntry("sysparm_limit", "2")
                .containsEntry("sysparm_query", "active=true^assignment_group=" + GROUP + "^ORDERBYDESCsys_created_on");
        assertThat(pages.get(1).query()).containsEntry("sysparm_offset", "2");

        JsonNode result = json.parse(jdbc.sql("""
                SELECT result::text FROM effect_journal WHERE workspace_id = :ws AND run_id = :run AND node_id = 'procure'
                """).param("ws", PURCHASING).param("run", runId).query(String.class).single());
        assertThat(result.path("incidents")).hasSize(3);
        assertThat(result.path("incidents").get(2).path("number").asString()).isEqualTo("INC0020003");
        assertThat(result.path("incidents").get(0).has("short_description")).isTrue();
        assertThat(result.path("numbers")).hasSize(3);
        // Page 2 is its own journaled read.
        assertThat(jdbc.sql("""
                SELECT state || ':' || effect_class FROM effect_journal
                WHERE workspace_id = :ws AND run_id = :run AND node_id = 'procure#a1p2'
                """).param("ws", PURCHASING).param("run", runId).query(String.class).single())
                .isEqualTo("succeeded:readOnly");
        JsonNode event = succeededEvent(runId);
        assertThat(event.path("pages").asInt()).isEqualTo(2);
        assertThat(event.path("truncated").toString()).contains("incidents", "numbers");
    }

    @Test
    void createWhoseAnswerIsLostAfterCommitIsReconciledByLookupWithoutASecondCreate() throws Exception {
        ServiceNowStub.fault(ServiceNowStub.CreateFault.COMMIT_THEN_TIMEOUT, 6000);
        UUID workflow = workflow(2, "createIncident",
                Map.of("shortDescription", "/input/title", "description", "/input/purpose"),
                Map.of("number", "/result/number", "sysId", "/result/sys_id"),
                Map.of("number", "string", "sysId", "string"), false);
        UUID runId = run(workflow, "Laptop access for a new hire");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).as(run.toString()).isEqualTo("completed");
        assertThat(ServiceNowStub.creates()).as("no second create").isEqualTo(1);
        assertThat(ServiceNowStub.incidents()).hasSize(1);
        ObjectNode incident = ServiceNowStub.incidents().get(0);
        String reference = run.path("reference").asString();
        assertThat(incident.path("correlation_id").asString()).isEqualTo(reference + "-procure-1");
        assertThat(incident.path("correlation_display").asString()).isEqualTo("Orqix");
        assertThat(run.path("output").path("number").asString()).isEqualTo(incident.path("number").asString());
        assertThat(run.path("output").path("sysId").asString()).isEqualTo(incident.path("sys_id").asString());

        List<ServiceNowStub.Call> lookups = calls("GET", "/api/now/table/incident");
        assertThat(lookups).hasSize(1);
        assertThat(lookups.get(0).query()).containsEntry("sysparm_query", "correlation_id=" + reference + "-procure-1")
                .containsEntry("sysparm_limit", "1");
        JsonNode resolution = json.parse(jdbc.sql("""
                SELECT resolution::text FROM effect_journal WHERE workspace_id = :ws AND run_id = :run AND node_id = 'procure'
                """).param("ws", PURCHASING).param("run", runId).query(String.class).single());
        assertThat(resolution.path("kind").asString()).isEqualTo("lookup");
        assertThat(resolution.path("outcome").asString()).isEqualTo("found");
        assertThat(resolution.path("cause").asString()).isEqualTo("TIMEOUT");
        JsonNode event = succeededEvent(runId);
        assertThat(event.path("reconciled").asBoolean()).isTrue();
        assertThat(event.path("summary").asString()).startsWith("Reconciled automatically: found");
        assertThat(run.path("effects").get(0).path("effectClass").asString()).isEqualTo("idempotentByLookup");
    }

    @Test
    void createWhoseRequestWasLostIsSentAgainWithTheSameKeyAfterTheLookupFindsNothing() throws Exception {
        ServiceNowStub.fault(ServiceNowStub.CreateFault.LOST_THEN_TIMEOUT, 6000);
        UUID workflow = workflow(2, "createIncident", Map.of("shortDescription", "/input/title"),
                Map.of("number", "/result/number"), Map.of("number", "string"), false);
        UUID runId = run(workflow, "Badge for the new office");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).as(run.toString()).isEqualTo("completed");
        List<ServiceNowStub.Call> creates = calls("POST", "/api/now/table/incident");
        assertThat(creates).hasSize(2);
        assertThat(json.parse(creates.get(0).body()).path("correlation_id").asString())
                .isEqualTo(json.parse(creates.get(1).body()).path("correlation_id").asString())
                .isEqualTo(run.path("reference").asString() + "-procure-1");
        assertThat(ServiceNowStub.incidents()).hasSize(1);
        // Three lookups over the settle time before deciding "not found".
        assertThat(calls("GET", "/api/now/table/incident")).hasSize(3);
        JsonNode effect = run.path("effects").get(0);
        assertThat(effect.path("state").asString()).isEqualTo("succeeded");
        assertThat(effect.path("attempts").asInt()).isEqualTo(2);
        assertThat(run.path("output").path("number").asString()).isEqualTo(
                ServiceNowStub.incidents().get(0).path("number").asString());
    }
}
