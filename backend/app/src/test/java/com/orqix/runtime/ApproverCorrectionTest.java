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
import tools.jackson.databind.node.ObjectNode;

/** WM07: an approver corrects the fields the workflow declared correctable (docs/build/S42). */
class ApproverCorrectionTest extends RuntimeTestBase {

    private static Consumer<ObjectNode> correctable(String node, String... fields) {
        return definition -> {
            for (JsonNode n : definition.path("nodes")) {
                if (node.equals(n.path("id").asString())) {
                    var list = ((ObjectNode) n.path("config")).putArray("correctable");
                    for (String field : fields) {
                        list.add(field);
                    }
                }
            }
        };
    }

    private Response correct(String user, String taskId, int version, Map<String, Object> changes, String key) throws Exception {
        return api(user).post(Map.of("expectedVersion", version, "reason", "The requester mistyped the amount",
                "changes", changes), key, "/workspaces/{ws}/tasks/{id}/correct-input", PURCHASING, taskId);
    }

    private JsonNode task(String user, String taskId) throws Exception {
        return api(user).get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
    }

    @Test
    void theTaskHolderCorrectsADeclaredFieldAndLaterStepsUseTheCorrectedValue() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, correctable("manager", "amount", "purpose"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        String taskId = openTask(PURCHASING, run, "manager").path("id").asString();

        // Nobody may correct before holding the task, so the field list is empty for them.
        assertThat(task("mani.manager", taskId).path("form").path("correctableFields")).isEmpty();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        JsonNode held = task("mani.manager", taskId);
        assertThat(held.path("form").path("correctableFields").toString()).isEqualTo("[\"amount\",\"purpose\"]");
        int version = held.path("version").asInt();

        String key = UUID.randomUUID().toString();
        Response corrected = correct("mani.manager", taskId, version, Map.of("amount", 6000), key);
        assertThat(corrected.status()).as(corrected.toString()).isEqualTo(200);
        assertThat(corrected.body().path("request").path("input").path("amount").decimalValue()).isEqualByComparingTo("6000");
        assertThat(corrected.body().path("version").asInt()).isGreaterThan(version);
        Response replay = correct("mani.manager", taskId, version, Map.of("amount", 6000), key);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :id AND type = 'INPUT_CORRECTED'").param("id", run)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action = 'task.input-corrected'")
                .param("id", taskId).query(Long.class).single()).isEqualTo(1);

        // A decision prepared on the old data is refused; a fresh one goes through and the corrected amount routes to finance.
        Response stale = api("mani.manager").post(Map.of("expectedVersion", version, "action", "approve"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/tasks/{id}/decision", PURCHASING, taskId);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().path("code").asString()).isEqualTo("TASK_VERSION_CONFLICT");
        assertThat(decide("mani.manager", PURCHASING, taskId, "approve", null).status()).isEqualTo(201);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
        assertThat(run(PURCHASING, run).path("input").path("amount").decimalValue()).isEqualByComparingTo("6000");

        // The task is closed now.
        assertThat(correct("mani.manager", taskId, 99, Map.of("amount", 1), UUID.randomUUID().toString()).status()).isEqualTo(409);
    }

    @Test
    void onlyDeclaredFieldsOnlyTheHolderAndOnlyValidValues() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, correctable("manager", "amount"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        String taskId = openTask(PURCHASING, run, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        int version = task("mani.manager", taskId).path("version").asInt();

        Response notDeclared = correct("mani.manager", taskId, version, Map.of("title", "Changed"), UUID.randomUUID().toString());
        assertThat(notDeclared.status()).isEqualTo(422);
        Response invalid = correct("mani.manager", taskId, version, Map.of("amount", -5), UUID.randomUUID().toString());
        assertThat(invalid.status()).isEqualTo(422);
        // Another manager who does not hold the task, and the requester, cannot correct.
        assertThat(correct("max.manager", taskId, version, Map.of("amount", 200), UUID.randomUUID().toString()).status()).isEqualTo(403);
        assertThat(correct("rhea.requester", taskId, version, Map.of("amount", 200), UUID.randomUUID().toString()).status())
                .isIn(403, 404);
        // An operator who can read the data may.
        Response byOperator = correct("olga.operator", taskId, version, Map.of("amount", 200), UUID.randomUUID().toString());
        assertThat(byOperator.status()).as(byOperator.toString()).isEqualTo(200);
        assertThat(run(PURCHASING, run).path("input").path("amount").decimalValue()).isEqualByComparingTo("200");
        assertThat(jdbc.sql("SELECT data->>'reason' FROM run_event WHERE run_id = :id AND type = 'INPUT_CORRECTED'")
                .param("id", run).query(String.class).single()).isEqualTo("The requester mistyped the amount");
    }

    @Test
    void publishingIsBlockedWhenAnEarlierStepAlreadyUsedTheField() throws Exception {
        Response created = api("dana.designer").post(Map.of("key", "wm7-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Bad correction", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        UUID id = UUID.fromString(created.body().path("id").asString());
        JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id).body();
        ObjectNode definition = (ObjectNode) draft.path("definition");
        // The finance approval comes after the amount decision, so the amount was already used.
        correctable("finance", "amount", "nothere").accept(definition);
        Response saved = api("dana.designer").put(Map.of("definition", definition),
                "\"r" + draft.path("revision").asInt() + "\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        Response validation = api("dana.designer").post(Map.of("revision", saved.body().path("revision").asInt()),
                "/workspaces/{ws}/workflows/{id}/validate", PURCHASING, id);
        assertThat(validation.body().toString()).contains("WF_CORRECTION_INVALID", "already uses it", "not a request field");
    }
}
