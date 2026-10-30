package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Step forms: a human step hides, shows, requires and captures fields (docs/build/S123). */
class StepFormRuntimeTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The manager sees no cost center, must have a purpose, and fills in a PO number (required) and a note (optional).
     * The route to finance reads the PO number the manager typed.
     */
    private static final Consumer<ObjectNode> STEP_FORM = definition -> {
        for (JsonNode n : definition.path("nodes")) {
            if ("manager".equals(n.path("id").asString())) {
                ObjectNode config = (ObjectNode) n.path("config");
                config.set("fieldAccess", MAPPER.readTree("{\"costCenter\":\"hidden\",\"purpose\":\"required\"}"));
                config.set("capture", MAPPER.readTree("{\"type\":\"object\",\"additionalProperties\":false,"
                        + "\"required\":[\"poNumber\"],\"properties\":{"
                        + "\"poNumber\":{\"type\":\"string\",\"title\":\"PO number\",\"minLength\":3,\"maxLength\":20},"
                        + "\"note\":{\"type\":\"string\",\"title\":\"Note\",\"maxLength\":200}}}"));
            }
        }
        for (JsonNode t : definition.path("transitions")) {
            if ("t5".equals(t.path("id").asString())) {
                ((ObjectNode) t).set("condition", MAPPER.readTree(
                        "{\"fieldRef\":\"/steps/manager/poNumber\",\"operator\":\"eq\",\"value\":\"PO-FIN\"}"));
            }
        }
    };

    private Response decideWith(String user, String taskId, Map<String, Object> body) throws Exception {
        return api(user).post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/tasks/{id}/decision", PURCHASING, taskId);
    }

    private JsonNode task(String user, String taskId) throws Exception {
        return api(user).get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
    }

    @Test
    void theApproverSeesTheStepFormAndTheCapturedValueRoutesTheRequest() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, STEP_FORM);
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        String taskId = openTask(PURCHASING, run, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);

        JsonNode held = task("mani.manager", taskId);
        assertThat(held.path("form").path("fieldAccess").path("costCenter").asString()).isEqualTo("hidden");
        assertThat(held.path("form").path("capture").path("required").toString()).isEqualTo("[\"poNumber\"]");
        // The hidden field is left out of the task view; the required one is editable.
        assertThat(held.path("request").path("input").has("costCenter")).isFalse();
        assertThat(held.path("request").path("input").has("title")).isTrue();
        assertThat(held.path("form").path("correctableFields").toString()).contains("purpose");
        int version = held.path("version").asInt();

        // Approving needs the PO number, valid by the step's schema.
        Response missing = decideWith("mani.manager", taskId, Map.of("expectedVersion", version, "action", "approve"));
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().toString()).contains("/fields/poNumber");
        Response tooShort = decideWith("mani.manager", taskId, Map.of("expectedVersion", version, "action", "approve",
                "fields", Map.of("poNumber", "P")));
        assertThat(tooShort.status()).isEqualTo(422);
        Response unknown = decideWith("mani.manager", taskId, Map.of("expectedVersion", version, "action", "approve",
                "fields", Map.of("poNumber", "PO-FIN", "other", "x")));
        assertThat(unknown.status()).isEqualTo(422);
        // A refused attempt does not change the task.
        assertThat(task("mani.manager", taskId).path("version").asInt()).isEqualTo(version);

        Response approved = decideWith("mani.manager", taskId, Map.of("expectedVersion", version, "action", "approve",
                "fields", Map.of("poNumber", "PO-FIN", "note", "Use the framework contract")));
        assertThat(approved.status()).as(approved.toString()).isEqualTo(201);
        // The route after the manager reads the captured PO number, so the request goes to finance.
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
        assertThat(jdbc.sql("SELECT (data->>'fieldsCaptured')::int FROM run_event WHERE run_id = :id AND type = 'TASK_DECIDED'")
                .param("id", run).query(Integer.class).single()).isEqualTo(2);
        // The audit record keeps counts, never the values.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND metadata::text LIKE '%PO-FIN%'")
                .param("id", taskId).query(Long.class).single()).isZero();
    }

    @Test
    void anotherPoNumberTakesTheOtherRouteAndARejectionTakesNoFields() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, STEP_FORM);
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        String taskId = openTask(PURCHASING, run, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        int version = task("mani.manager", taskId).path("version").asInt();

        Response rejectWithFields = decideWith("mani.manager", taskId, Map.of("expectedVersion", version, "action", "reject",
                "reason", "No budget", "fields", Map.of("poNumber", "PO-1")));
        assertThat(rejectWithFields.status()).isEqualTo(422);
        Response approved = decideWith("mani.manager", taskId, Map.of("expectedVersion", version, "action", "approve",
                "fields", Map.of("poNumber", "PO-123")));
        assertThat(approved.status()).as(approved.toString()).isEqualTo(201);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !"active".equals(run(PURCHASING, run).path("lifecycle").asString())
                || openTask(PURCHASING, run, "finance") != null);
        assertThat(openTask(PURCHASING, run, "finance")).isNull();
    }

    @Test
    void aStepWithoutAFormRefusesFields() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, d -> { });
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        String taskId = openTask(PURCHASING, run, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        JsonNode held = task("mani.manager", taskId);
        assertThat(held.path("form").has("fieldAccess")).isFalse();
        assertThat(held.path("form").has("capture")).isFalse();
        Response withFields = decideWith("mani.manager", taskId, Map.of("expectedVersion", held.path("version").asInt(),
                "action", "approve", "fields", Map.of("poNumber", "PO-1")));
        assertThat(withFields.status()).isEqualTo(422);
    }
}
