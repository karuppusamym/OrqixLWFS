package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.ServiceNowStub;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * v1 bodies from request files (S130) against PostgreSQL: a file uploaded to a request is sent as a binary body or a
 * multipart part, only for that request; form bodies and the reviewed encoders. The HTTP calls go to the
 * ServiceNow-shaped WireMock stub through the real executor.
 */
class V1RequestBodiesTest extends RuntimeTestBase {

    @Autowired
    HttpExecutor executor;
    @Autowired
    RequestFiles files;

    private ObjectNode example;

    @BeforeEach
    void load() throws Exception {
        example = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"), "examples",
                "connections", "servicenow-table.connection.json")));
    }

    private UUID waitingRun() throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, publishPurchase(PURCHASING, null), BigDecimal.TEN)
                .path("id").asString());
    }

    private UUID upload(UUID runId, String name, String type, byte[] content) throws Exception {
        Response added = api("rhea.requester").upload(UUID.randomUUID().toString(), name, type, content, Map.of(),
                "/workspaces/{ws}/runs/{run}/attachments", PURCHASING, runId);
        assertThat(added.status()).as(added.toString()).isEqualTo(201);
        return UUID.fromString(added.body().path("attachment").path("id").asString(
                added.body().path("id").asString()));
    }

    private HttpExecutor.CallResult call(ObjectNode definition, String operation, ObjectNode inputs, UUID runId,
            Map<String, String> results) {
        ConnectionDefinition parsed = ConnectionDefinition.parse(definition, "test");
        return executor.call(new HttpExecutor.Target(Stub.baseUrl() + "/servicenow", "none", null, null, PURCHASING),
                parsed, parsed.operation(operation), inputs, results, "test-key", 10, null,
                new V1Requests.Context(PURCHASING, runId, files, Map.of(), null));
    }

    @Test
    void binaryBodyIsTheRequestFileWithItsContentType() throws Exception {
        UUID runId = waitingRun();
        byte[] pdf = "%PDF-1.4 access approval".getBytes(StandardCharsets.UTF_8);
        UUID file = upload(runId, "approval.pdf", "application/pdf", pdf);
        ObjectNode inputs = json.object().put("sysId", "0123456789abcdef0123456789abcdef")
                .put("fileName", "approval.pdf").put("document", file.toString());
        HttpExecutor.CallResult result = call(example, "attachFile", inputs, runId, Map.of("id", "/result/sys_id"));
        assertThat(result.outcome()).as(result.toString()).isEqualTo(HttpExecutor.Outcome.SUCCEEDED);
        ServiceNowStub.Call sent = ServiceNowStub.calls().get(0);
        assertThat(sent.bytes()).isEqualTo(pdf);
        assertThat(sent.contentType()).isEqualTo("application/pdf");
        assertThat(sent.query()).containsEntry("table_name", "incident").containsEntry("file_name", "approval.pdf")
                .containsEntry("table_sys_id", "0123456789abcdef0123456789abcdef");
        assertThat(result.result().path("id").asString()).hasSize(32);
    }

    @Test
    void aFileOfAnotherRequestIsNeverSent() throws Exception {
        UUID owner = waitingRun();
        UUID other = waitingRun();
        UUID file = upload(owner, "secret.pdf", "application/pdf", "%PDF-1.4 other".getBytes(StandardCharsets.UTF_8));
        ObjectNode inputs = json.object().put("sysId", "0123456789abcdef0123456789abcdef")
                .put("fileName", "secret.pdf").put("document", file.toString());
        HttpExecutor.CallResult result = call(example, "attachFile", inputs, other, Map.of());
        assertThat(result.outcome()).isEqualTo(HttpExecutor.Outcome.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(ServiceNowStub.calls()).isEmpty();
        assertThat(files.read(PURCHASING, owner, file)).isPresent();
        assertThat(files.read(PURCHASING, other, file)).isEmpty();
    }

    @Test
    void multipartPartsCarryTheFileAndTextFields() throws Exception {
        UUID runId = waitingRun();
        UUID file = upload(runId, "notes.txt", "text/plain", "line one".getBytes(StandardCharsets.UTF_8));
        ObjectNode op = (ObjectNode) example.path("operations").path("attachFile");
        op.put("contentType", "multipart");
        op.remove("binaryInput");
        op.set("body", json.parse("{\"file\":{\"input\":\"document\"},\"table_name\":{\"const\":\"incident\"}}"));
        ObjectNode inputs = json.object().put("sysId", "0123456789abcdef0123456789abcdef")
                .put("fileName", "notes.txt").put("document", file.toString());
        HttpExecutor.CallResult result = call(example, "attachFile", inputs, runId, Map.of());
        assertThat(result.outcome()).as(result.toString()).isEqualTo(HttpExecutor.Outcome.SUCCEEDED);
        ServiceNowStub.Call sent = ServiceNowStub.calls().get(0);
        assertThat(sent.contentType()).startsWith("multipart/form-data; boundary=orqix-");
        assertThat(sent.body()).contains("name=\"file\"; filename=\"notes.txt\"", "Content-Type: text/plain",
                "line one", "name=\"table_name\"", "incident");
    }

    @Test
    void formBodyAndNestedJsonBodyAreBuiltFromTheTemplate() throws Exception {
        ObjectNode op = (ObjectNode) example.path("operations").path("updateIncidentFields");
        op.put("contentType", "form");
        ObjectNode inputs = json.object().put("sysId", "0123456789abcdef0123456789abcdef").put("state", "6")
                .put("urgency", "2");
        call(example, "updateIncidentFields", inputs, null, Map.of());
        ServiceNowStub.Call sent = ServiceNowStub.calls().get(0);
        assertThat(sent.method()).isEqualTo("PATCH");
        assertThat(sent.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(sent.body()).isEqualTo("state=6&urgency=2");

        ObjectNode create = (ObjectNode) example.path("operations").path("createIncident");
        ((ObjectNode) create.path("body")).set("variables", json.parse(
                "{\"object\":{\"group\":{\"input\":\"assignmentGroup\"},\"tags\":{\"array\":[{\"const\":\"orqix\"},{\"input\":\"urgency\"}]}}}"));
        ObjectNode createInputs = json.object().put("shortDescription", "Laptop").put("urgency", "1").put("impact", "2")
                .put("correlationId", "ORQ-1001-raise-1");
        call(example, "createIncident", createInputs, null, Map.of());
        JsonNode body = json.parse(ServiceNowStub.calls().get(1).body());
        assertThat(body.path("short_description").asString()).isEqualTo("Laptop");
        assertThat(body.has("description")).as("absent optional input is left out").isFalse();
        assertThat(body.path("variables").path("tags").toString()).isEqualTo("[\"orqix\",\"1\"]");
        assertThat(body.path("variables").has("group")).isFalse();
        assertThat(ServiceNowStub.calls().get(1).contentType()).isEqualTo("application/json");
    }

    @Test
    void reviewedEncodersKeepValuesLiteral() {
        assertThat(V1Requests.rfc3986("a b/c?d=e&f")).isEqualTo("a%20b%2Fc%3Fd%3De%26f");
        assertThat(V1Requests.encode("servicenowEncodedQuery", "ORQ-1001-raise-1")).isEqualTo("ORQ-1001-raise-1");
        assertThatThrownBy(() -> V1Requests.encode("servicenowEncodedQuery", "x^ORactive=false"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V1Requests.encode("servicenowEncodedQuery", "x\nactive=false"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(V1Requests.text(json.mapper().getNodeFactory().numberNode(new BigDecimal("2.50")))).isEqualTo("2.50");
        assertThat(V1Requests.text(json.mapper().getNodeFactory().numberNode(new BigDecimal("3.0")))).isEqualTo("3");
    }

    @Test
    void nextPageLinksMustStayInsideTheBinding() {
        String base = "https://acme.service-now.com/api";
        assertThat(EffectDispatcher.insideBinding(base, "/api/now/table/incident?sysparm_offset=25")).isNotNull();
        assertThat(EffectDispatcher.insideBinding(base, "https://evil.example/api/now")).isNull();
        assertThat(EffectDispatcher.insideBinding(base, "https://acme.service-now.com/other")).isNull();
        assertThat(EffectDispatcher.insideBinding(base, "https://user@acme.service-now.com/api/x")).isNull();
        assertThat(List.of(V1Results.nextLink(java.net.http.HttpHeaders.of(Map.of("Link", List.of(
                "<https://acme.service-now.com/api/now/table/incident?sysparm_offset=0>;rel=\"first\","
                        + "<https://acme.service-now.com/api/now/table/incident?sysparm_offset=25>;rel=\"next\"")),
                (a, b) -> true)))).containsExactly("https://acme.service-now.com/api/now/table/incident?sysparm_offset=25");
    }
}
