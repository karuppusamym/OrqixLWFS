package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IN05 (c) configuration and the record handler in stub mode (docs/build/S99 K2, K5, K7, K8, K11, K12): named clusters
 * and allow-listed topics only, secret references only, run-as rights checked at creation, the same start and message
 * services as a person, duplicate drop by key, dead letters without content, permissions and audit. No broker here; the
 * real consumer is {@code KafkaConsumerTest}.
 */
@TestPropertySource(properties = {
        "orqix.kafka.mode=stub",
        "orqix.kafka.clusters.main.bootstrap-servers=localhost:1",
        "orqix.kafka.clusters.main.security-protocol=PLAINTEXT",
        "orqix.kafka.clusters.main.allowed-topics=orders,payments"})
class KafkaTriggerApiTest extends RuntimeTestBase {

    /** The cap is 5 triggers per workspace and the database is shared, so each test starts without any (owner connection). */
    @org.junit.jupiter.api.BeforeEach
    void removeKafkaTriggers() {
        var owner = com.orqix.support.TestDatabase.legacyFixtureJdbc();
        owner.sql("DELETE FROM orqix.kafka_inbound_record WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger_status WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger WHERE workspace_id = :ws").param("ws", PURCHASING).update();
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void grant(String permission, UUID workflowId) throws Exception {
        UUID wade = UUID.fromString(api("wade.admin").get("/me").body().path("subject").path("id").asString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permission", permission);
        if (workflowId != null) {
            body.put("workflowId", workflowId);
        }
        body.put("principal", Map.of("type", "subject", "value", wade.toString()));
        Response granted = api("wade.admin").post(body, "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(granted.status()).as(granted.toString()).isIn(201, 409);
    }

    private Map<String, Object> startTrigger(String key, UUID workflowId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("name", "Orders feed");
        body.put("cluster", "main");
        body.put("topic", "orders");
        body.put("action", "start");
        body.put("workflowId", workflowId);
        body.put("mapping", Map.of("title", "/title", "purpose", "/purpose", "costCenter", "/costCenter", "amount", "/amount"));
        body.put("startFrom", "earliest");
        return body;
    }

    private Response test(String triggerId, String recordKey, Object value) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (recordKey != null) {
            body.put("key", recordKey);
        }
        body.put("value", value);
        return api("wade.admin").post(body, "/workspaces/{ws}/kafka-triggers/{id}/test-message", PURCHASING, triggerId);
    }

    @Test
    void configurationIsLimitedToNamedClustersAllowListedTopicsAndPeopleWhoMayDoWhatTheTriggerDoes() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Map<String, Object> body = startTrigger(key("orders"), workflowId);
        assertThat(api("dana.designer").post(body, "/workspaces/{ws}/kafka-triggers", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").post(body, "/workspaces/{ws}/kafka-triggers", PURCHASING).status()).isEqualTo(404);

        Map<String, Object> otherTopic = new LinkedHashMap<>(body);
        otherTopic.put("topic", "payroll");
        Response topic = api("wade.admin").post(otherTopic, "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(topic.status()).isEqualTo(422);
        assertThat(topic.body().path("fieldErrors").get(0).path("pointer").asString()).isEqualTo("/topic");
        Map<String, Object> otherCluster = new LinkedHashMap<>(body);
        otherCluster.put("cluster", "elsewhere");
        assertThat(api("wade.admin").post(otherCluster, "/workspaces/{ws}/kafka-triggers", PURCHASING).status()).isEqualTo(422);
        Map<String, Object> inlineSecret = new LinkedHashMap<>(body);
        inlineSecret.put("secretRef", "user:pass@broker");
        assertThat(api("wade.admin").post(inlineSecret, "/workspaces/{ws}/kafka-triggers", PURCHASING).status()).isIn(400, 422);

        // The creator becomes the run-as person, so they must be allowed to start the workflow themselves.
        Response withoutStart = api("wade.admin").post(body, "/workspaces/{ws}/kafka-triggers", PURCHASING);
        if (withoutStart.status() == 403) {
            grant("workflow.start", workflowId);
        } else {
            assertThat(withoutStart.status()).as(withoutStart.toString()).isEqualTo(201);
            body = startTrigger(key("orders"), workflowId);
        }
        Response created = api("wade.admin").post(body, "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.body().path("consumerGroup").asString()).isEqualTo("orqix-" + body.get("key"));
        assertThat(api("wade.admin").post(body, "/workspaces/{ws}/kafka-triggers", PURCHASING).status()).isEqualTo(409);

        JsonNode list = api("dana.designer").get("/workspaces/{ws}/kafka-triggers", PURCHASING).body();
        assertThat(list.path("mode").asString()).isEqualTo("stub");
        assertThat(list.path("clusters").get(0).path("allowedTopics").toString()).contains("orders", "payments");
        assertThat(list.toString()).doesNotContain("localhost:1");

        String id = created.body().path("id").asString();
        Response stale = api("wade.admin").put(Map.of("name", "Orders", "mapping", Map.of("title", "/title"),
                "batchSize", 10, "maxPerSecond", 5, "enabled", false, "expectedVersion", 9), null,
                "/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, id);
        assertThat(stale.status()).isEqualTo(409);
        Response disabled = api("wade.admin").put(Map.of("name", "Orders", "mapping", Map.of("title", "/title"),
                "batchSize", 10, "maxPerSecond", 5, "enabled", false, "expectedVersion", 1), null,
                "/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, id);
        assertThat(disabled.status()).as(disabled.toString()).isEqualTo(200);
        assertThat(disabled.body().path("version").asInt()).isEqualTo(2);
        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'kafka.trigger-created' AND resource_id = :id")
                .param("id", id).query(String.class).single();
        assertThat(json.parse(metadata).propertyNames()).containsExactlyInAnyOrder("key", "action", "cluster");
    }

    @Test
    void onlyTriggersThatAreSwitchedOnCountTowardTheLimitBecauseTriggersCannotBeDeleted() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        grant("workflow.start", workflowId);
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Response created = api("wade.admin").post(startTrigger(key("cap"), workflowId), "/workspaces/{ws}/kafka-triggers", PURCHASING);
            assertThat(created.status()).as(created.toString()).isEqualTo(201);
            ids.add(created.body().path("id").asString());
        }
        Response sixth = api("wade.admin").post(startTrigger(key("cap"), workflowId), "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(sixth.status()).isEqualTo(422);
        assertThat(sixth.body().path("detail").asString()).contains("switched on");

        // Switching one off makes room for another; switching it back on is refused while five are on.
        JsonNode first = api("wade.admin").get("/workspaces/{ws}/kafka-triggers", PURCHASING).body().path("items").get(0);
        String offId = first.path("id").asString();
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("name", first.path("name").asString());
        off.put("mapping", Map.of("title", "/title", "purpose", "/purpose", "costCenter", "/costCenter", "amount", "/amount"));
        off.put("batchSize", first.path("batchSize").asInt());
        off.put("maxPerSecond", first.path("maxPerSecond").asInt());
        off.put("enabled", false);
        off.put("expectedVersion", first.path("version").asInt());
        Response disabled = api("wade.admin").put(off, null, "/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, offId);
        assertThat(disabled.status()).as(disabled.toString()).isEqualTo(200);
        Response replacement = api("wade.admin").post(startTrigger(key("cap"), workflowId), "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(replacement.status()).as(replacement.toString()).isEqualTo(201);
        Map<String, Object> on = new LinkedHashMap<>(off);
        on.put("enabled", true);
        on.put("expectedVersion", disabled.body().path("version").asInt());
        Response reenabled = api("wade.admin").put(on, null, "/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, offId);
        assertThat(reenabled.status()).isEqualTo(422);
    }

    @Test
    void aTriggerCanBeDeletedOnlyOnceItIsSwitchedOff() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        grant("workflow.start", workflowId);
        Response created = api("wade.admin").post(startTrigger(key("gone"), workflowId), "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(api("dana.designer").delete("/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, id).status()).isEqualTo(403);
        Response on = api("wade.admin").delete("/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, id);
        assertThat(on.status()).isEqualTo(409);
        assertThat(on.code()).isEqualTo("RESOURCE_IN_USE");

        Map<String, Object> off = new LinkedHashMap<>();
        off.put("name", "Orders feed");
        off.put("mapping", Map.of("title", "/title", "purpose", "/purpose", "costCenter", "/costCenter", "amount", "/amount"));
        off.put("batchSize", created.body().path("batchSize").asInt());
        off.put("maxPerSecond", created.body().path("maxPerSecond").asInt());
        off.put("enabled", false);
        off.put("expectedVersion", created.body().path("version").asInt());
        assertThat(api("wade.admin").put(off, null, "/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api("wade.admin").delete("/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, id).status()).isEqualTo(204);
        assertThat(api("wade.admin").get("/workspaces/{ws}/kafka-triggers", PURCHASING).body().path("items").toString()).doesNotContain(id);
        assertThat(api("wade.admin").delete("/workspaces/{ws}/kafka-triggers/{id}", PURCHASING, id).status()).isEqualTo(404);
    }

    @Test
    void stubMessagesStartRequestsOnceDropDuplicatesAndDeadLetterBadRecordsWithoutContent() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        grant("workflow.start", workflowId);
        Response created = api("wade.admin").post(startTrigger(key("orders"), workflowId), "/workspaces/{ws}/kafka-triggers",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String id = created.body().path("id").asString();
        Map<String, Object> order = new LinkedHashMap<>(input(BigDecimal.valueOf(321)));

        assertThat(api("dana.designer").post(Map.of("value", order), "/workspaces/{ws}/kafka-triggers/{id}/test-message",
                PURCHASING, id).status()).isEqualTo(403);
        Response first = test(id, "order-1", order);
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        assertThat(first.body().path("outcome").asString()).isEqualTo("started");
        UUID run = UUID.fromString(first.body().path("runId").asString());
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("input").path("amount").decimalValue()).isEqualByComparingTo("321");
        assertThat(detail.path("initiator").path("displayName").asString()).isNotBlank();

        assertThat(test(id, "order-1", order).body().path("outcome").asString()).isEqualTo("duplicate");
        // Content never routes: a "workflowId" or "topic" in the value is just data the mapping ignores.
        Map<String, Object> sneaky = new LinkedHashMap<>(order);
        sneaky.put("workflowId", UUID.randomUUID().toString());
        sneaky.put("topic", "payroll");
        Response other = test(id, "order-2", sneaky);
        assertThat(other.body().path("outcome").asString()).isEqualTo("started");
        assertThat(run(PURCHASING, UUID.fromString(other.body().path("runId").asString())).path("workflow").path("id")
                .asString()).isEqualTo(workflowId.toString());

        Response unmapped = test(id, "order-3", Map.of("title", "Only a title"));
        assertThat(unmapped.body().path("outcome").asString()).isEqualTo("dead");
        assertThat(unmapped.body().path("code").asString()).isEqualTo("unmapped");
        Map<String, Object> invalid = new LinkedHashMap<>(order);
        invalid.put("amount", "not a number");
        Response refused = test(id, "order-4", invalid);
        assertThat(refused.body().path("outcome").asString()).isEqualTo("dead");
        assertThat(refused.body().path("code").asString()).isEqualTo("validation_failed");

        assertThat(api("dana.designer").get("/workspaces/{ws}/kafka-dead-letters", PURCHASING).status()).isEqualTo(403);
        JsonNode dead = api("olga.operator").get("/workspaces/{ws}/kafka-dead-letters", PURCHASING).body();
        assertThat(dead.path("items").findValuesAsString("errorCode")).contains("unmapped", "validation_failed");
        assertThat(dead.toString()).doesNotContain("Only a title").doesNotContain("not a number").doesNotContain("order-3");
        String audit = jdbc.sql("SELECT string_agg(metadata::text, ' ') FROM audit_event WHERE action = 'kafka.record-dead' AND resource_id = :id")
                .param("id", id).query(String.class).single();
        assertThat(audit).contains("unmapped").doesNotContain("Only a title").doesNotContain("order-3");
        JsonNode status = api("wade.admin").get("/workspaces/{ws}/kafka-triggers", PURCHASING).body().path("items");
        for (JsonNode t : status) {
            if (t.path("id").asString().equals(id)) {
                assertThat(t.path("status").path("dead").asLong()).isEqualTo(2);
            }
        }
    }

    @Test
    void aResumeTriggerSendsItsOwnMessageByCorrelationKey() throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/order-payment-wait.json")));
        UUID wf = publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
        grant("message.send", null);
        String orderNumber = "SO-" + UUID.randomUUID().toString().substring(0, 8);
        Response started = api("rhea.requester").post(Map.of("workflowId", wf, "input",
                Map.of("orderNumber", orderNumber, "customerName", "Acme Ltd")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key("payments"));
        body.put("name", "Payments");
        body.put("cluster", "main");
        body.put("topic", "payments");
        body.put("action", "resume");
        body.put("messageName", "payment-received");
        body.put("correlationPointer", "/order");
        body.put("mapping", Map.of("paymentReference", "/ref"));
        Response created = api("wade.admin").post(body, "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String id = created.body().path("id").asString();

        Response noKey = test(id, "pay-0", Map.of("ref", "PAY-0"));
        assertThat(noKey.body().path("code").asString()).isEqualTo("no_correlation_key");
        Response resumed = test(id, "pay-1", Map.of("order", orderNumber, "ref", "PAY-KAFKA"));
        assertThat(resumed.body().path("outcome").asString()).as(resumed.toString()).isEqualTo("resumed");
        assertThat(resumed.body().path("runId").asString()).isEqualTo(run.toString());
        assertThat(jdbc.sql("SELECT status FROM message_subscription WHERE run_id = :r").param("r", run)
                .query(String.class).single()).isEqualTo("received");
        assertThat(test(id, "pay-1", Map.of("order", orderNumber, "ref", "PAY-KAFKA")).body().path("outcome").asString())
                .isEqualTo("duplicate");
        Response buffered = test(id, "pay-2", Map.of("order", "SO-NOBODY-" + UUID.randomUUID(), "ref", "PAY-X"));
        assertThat(buffered.body().path("outcome").asString()).isEqualTo("buffered");
        assertThat(List.of("resumed", "buffered")).contains(buffered.body().path("outcome").asString());
    }
}
