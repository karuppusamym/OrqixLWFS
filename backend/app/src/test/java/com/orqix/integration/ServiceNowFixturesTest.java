package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The ServiceNow example connection (S130) against its WireMock fixtures
 * (examples/fixtures/servicenow-table.wiremock.json), through the real HTTP executor: the fixtures and the definition agree on paths,
 * query parameters, headers, bodies, rows, response headers and the 404 mapping.
 */
class ServiceNowFixturesTest {

    private static final Path ROOT = Path.of(System.getProperty("orqix.repoRoot", "../.."));
    private static final Json JSON = new Json(JsonMapper.builder().build());
    private static WireMockServer server;
    private static ConnectionDefinition definition;
    private static HttpExecutor executor;

    @BeforeAll
    static void start() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        JsonNode fixtures = JSON.parse(Files.readString(ROOT.resolve("examples/fixtures/servicenow-table.wiremock.json")));
        for (JsonNode mapping : fixtures.path("mappings")) {
            server.addStubMapping(StubMapping.buildFrom(mapping.toString()));
        }
        definition = ConnectionDefinition.parse(JSON.parse(Files.readString(
                ROOT.resolve("examples/connections/servicenow-table.connection.json"))), "fixture");
        executor = new HttpExecutor(JSON, null, null);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    private HttpExecutor.CallResult call(String operation, ObjectNode inputs, Map<String, String> results,
            Map<String, String> extraQuery) {
        return executor.call(new HttpExecutor.Target("http://localhost:" + server.port() + "/servicenow", "none", null,
                null), definition, definition.operation(operation), inputs, results, "fixture-key", 5, null,
                new V1Requests.Context(null, null, null, extraQuery, null));
    }

    @Test
    void getIncidentReadsTheRecord() {
        HttpExecutor.CallResult result = call("getIncident",
                JSON.object().put("sysId", "a1b2c3d4e5f60718293a4b5c6d7e8f90"),
                Map.of("number", "/result/number", "state", "/result/state"), Map.of());
        assertThat(result.outcome()).as(result.toString()).isEqualTo(HttpExecutor.Outcome.SUCCEEDED);
        assertThat(result.result().path("number").asString()).isEqualTo("INC0012345");
        assertThat(result.result().path("state").asString()).isEqualTo("6");
    }

    @Test
    void unknownIncidentIsTheMappedBusinessError() {
        HttpExecutor.CallResult result = call("getIncident",
                JSON.object().put("sysId", "00000000000000000000000000000000"), Map.of("n", "/result/number"), Map.of());
        assertThat(result.outcome()).isEqualTo(HttpExecutor.Outcome.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("INCIDENT_NOT_FOUND");
        assertThat(result.attentionReason()).isEqualTo("EFFECT_REJECTED");
        assertThat(result.failureMessage()).isEqualTo("No Record found");
    }

    @Test
    void listMapsRowsAndTheTotalHeader() {
        HttpExecutor.CallResult result = call("listOpenIncidents",
                JSON.object().put("assignmentGroup", "0123456789abcdef0123456789abcdef"),
                Map.of("rows", "/result/[*]", "total", "/@headers/X-Total-Count"),
                Map.of("sysparm_offset", "0", "sysparm_limit", "25"));
        assertThat(result.outcome()).as(result.toString()).isEqualTo(HttpExecutor.Outcome.SUCCEEDED);
        assertThat(result.result().path("total").asInt()).isEqualTo(2);
        assertThat(result.result().path("rows")).hasSize(2);
        assertThat(result.result().path("rows").get(1).path("short_description").asString()).isEqualTo("VPN slow");
        assertThat(result.page().truncated()).isEmpty();
    }

    @Test
    void lookupFindsByCorrelationAndCreateSendsTheBodyTemplate() {
        HttpExecutor.CallResult found = call("findIncidentByCorrelation",
                JSON.object().put("correlationId", "ORQ-1042-raise-1"), Map.of("n", "/result/0/number"), Map.of());
        assertThat(found.result().path("n").asString()).isEqualTo("INC0012345");
        HttpExecutor.CallResult none = call("findIncidentByCorrelation",
                JSON.object().put("correlationId", "ORQ-1043-raise-1"), Map.of(), Map.of());
        assertThat(none.page().body().path("result")).isEmpty();
        HttpExecutor.CallResult created = call("createIncident", JSON.object().put("shortDescription", "Access")
                .put("urgency", "2").put("impact", "2").put("correlationId", "ORQ-1042-raise-1"),
                Map.of("number", "/result/number"), Map.of());
        assertThat(created.outcome()).as(created.toString()).isEqualTo(HttpExecutor.Outcome.SUCCEEDED);
        assertThat(created.result().path("number").asString()).isEqualTo("INC0012345");
        HttpExecutor.CallResult invalid = call("createIncident", JSON.object().put("shortDescription", "No key")
                .put("urgency", "2").put("impact", "2"), Map.of(), Map.of());
        assertThat(invalid.errorCode()).isEqualTo("INCIDENT_INVALID");
        assertThat(invalid.failureMessage()).isEqualTo("short_description is mandatory");
    }
}
