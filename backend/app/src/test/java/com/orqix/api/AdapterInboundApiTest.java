package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.integration.adapters.InboundService;
import com.orqix.integration.adapters.LocalStubTransport;
import com.orqix.support.OpenApiContract;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/** IN02: adapter definitions, local-stub delivery, signed inbound triggers, permission boundaries and user-started runs. */
class AdapterInboundApiTest extends RuntimeTestBase {

    private static final String SECRET = "in02-shared-secret-value";

    @Autowired
    LocalStubTransport stub;

    @BeforeEach
    void prepare() throws Exception {
        stub.reset();
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("in02-inbound"), SECRET);
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- outbound adapters

    @Test
    void adaptersAreCreatedUnderManagePermissionAndDeliverOnlyToTheLocalStub() throws Exception {
        String kafka = key("orders");
        Map<String, Object> body = Map.of("key", kafka, "kind", "kafka", "name", "Orders topic", "secretRef", "kafka-creds",
                "config", Map.of("allowedTopics", List.of("orders.created")));
        // Permission boundary: designers can read, not manage; requesters see nothing; outsiders see no workspace.
        assertThat(api("dana.designer").post(body, "/workspaces/{ws}/adapters", PURCHASING).status()).isEqualTo(403);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/adapters", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/adapters", PURCHASING).status()).isEqualTo(404);
        assertThat(api("otto.outsider").post(body, "/workspaces/{ws}/adapters", PURCHASING).status()).isEqualTo(404);

        Response created = api("wade.admin").post(body, "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(api("wade.admin").post(body, "/workspaces/{ws}/adapters", PURCHASING).status()).isEqualTo(409);

        JsonNode asDesigner = api("dana.designer").get("/workspaces/{ws}/adapters", PURCHASING).body();
        JsonNode asAdmin = api("wade.admin").get("/workspaces/{ws}/adapters", PURCHASING).body();
        assertThat(find(asDesigner, kafka).path("secretRef").isNull()).isTrue();
        assertThat(find(asAdmin, kafka).path("secretRef").asString()).isEqualTo("kafka-creds");

        Map<String, Object> send = Map.of("payload", Map.of("topic", "orders.created", "key", "o-1", "value", Map.of("id", 1)));
        assertThat(api("dana.designer").post(send, "/workspaces/{ws}/adapters/{k}/test-send", PURCHASING, kafka).status())
                .isEqualTo(403);
        Response sent = api("wade.admin").post(send, "/workspaces/{ws}/adapters/{k}/test-send", PURCHASING, kafka);
        assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
        assertThat(sent.body().path("outcome").asString()).isEqualTo("stubbed");
        assertThat(sent.body().path("detail").path("stub").asBoolean()).isTrue();
        assertThat(stub.sent()).hasSize(1);
        assertThat(stub.sent().get(0).adapterKey()).isEqualTo(kafka);

        // A refused request never reaches the transport.
        Response badTopic = api("wade.admin").post(Map.of("payload", Map.of("topic", "payroll", "value", Map.of())),
                "/workspaces/{ws}/adapters/{k}/test-send", PURCHASING, kafka);
        assertThat(badTopic.status()).isEqualTo(422);
        assertThat(badTopic.body().path("fieldErrors").get(0).path("pointer").asString()).isEqualTo("/payload/topic");
        assertThat(stub.sent()).hasSize(1);
        assertThat(api("wade.admin").post(send, "/workspaces/{ws}/adapters/{k}/test-send", PURCHASING, "missing").status())
                .isEqualTo(404);

        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id = :ws AND action = 'adapter.test-sent' "
                + "ORDER BY occurred_at DESC LIMIT 1").param("ws", PURCHASING).query(String.class).single();
        assertThat(json.parse(metadata).propertyNames()).containsExactlyInAnyOrder("kind", "key", "outcome");
        assertThat(metadata).doesNotContain("orders.created", "o-1");
    }

    @Test
    void unsafeConfigurationIsRefusedForEveryKindAndNoSecretIsEverStored() throws Exception {
        assertThat(create("email", Map.of("from", "noreply@example.com", "allowedRecipientDomains", List.of("*.example.com"))))
                .isEqualTo(422);
        assertThat(create("email", Map.of("from", "noreply@example.com", "allowedRecipientDomains", List.of("example.com"),
                "smtpPassword", "hunter2"))).isEqualTo(422);
        assertThat(create("teams", Map.of("baseUrl", "https://not-allowlisted.example.com"))).isEqualTo(422);
        assertThat(create("jdbc", Map.of("statement", "DELETE FROM orders WHERE id = :id", "parameters", List.of("id"))))
                .isEqualTo(422);
        assertThat(create("jdbc", Map.of("statement", "SELECT * FROM orders; DROP TABLE orders"))).isEqualTo(422);
        assertThat(create("kafka", Map.of("allowedTopics", List.of("orders.*")))).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM adapter_config WHERE config::text ILIKE '%hunter2%'").query(Long.class).single())
                .isZero();

        // Valid shapes for the other kinds, then delivery of each to the stub.
        String teams = key("teams");
        assertThat(createAs(teams, "teams", Map.of("baseUrl", com.orqix.support.Stub.baseUrl())).status()).isEqualTo(201);
        String email = key("mail");
        assertThat(createAs(email, "email", Map.of("from", "noreply@example.com", "allowedRecipientDomains", List.of("example.com"))).status())
                .isEqualTo(201);
        String db = key("db");
        assertThat(createAs(db, "jdbc", Map.of("statement", "SELECT id FROM orders WHERE customer_id = :customerId",
                "parameters", List.of("customerId"))).status()).isEqualTo(201);

        assertThat(sendAs(teams, Map.of("text", "Order approved")).status()).isEqualTo(200);
        assertThat(sendAs(email, Map.of("to", List.of("a@example.com"), "subject", "Hi", "body", "Hello")).status()).isEqualTo(200);
        assertThat(sendAs(email, Map.of("to", List.of("a@evil.example.org"), "subject", "Hi", "body", "Hello")).status()).isEqualTo(422);
        assertThat(sendAs(email, Map.of("to", List.of("a@example.com"), "subject", "Hi\r\nBcc: x@example.com", "body", "b")).status())
                .isEqualTo(422);
        Response rows = sendAs(db, Map.of("parameters", Map.of("customerId", 7)));
        assertThat(rows.status()).as(rows.toString()).isEqualTo(200);
        assertThat(rows.body().path("detail").path("boundParameters").get(0).asString()).isEqualTo("customerId");
        assertThat(sendAs(db, Map.of("parameters", Map.of("customerId", 7, "extra", 1))).status()).isEqualTo(422);
        assertThat(stub.sent()).extracting(s -> s.kind().value()).containsExactly("teams", "email", "jdbc");
    }

    // ---------------------------------------------------------------- inbound triggers

    @Test
    void signedEventsAreVerifiedDeduplicatedMappedAndNeverStartRunsOnTheirOwn() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        String trigger = key("orders-in");
        Map<String, Object> request = Map.of("key", trigger, "source", "kafka", "name", "Orders feed", "workflowId", workflowId,
                "secretRef", "in02-inbound", "filter", Map.of("allowedTopics", List.of("purchases")),
                "mapping", Map.of("title", "/value/title", "purpose", "/value/purpose", "costCenter", "/value/costCenter",
                        "amount", "/value/amount"));
        assertThat(api("dana.designer").post(request, "/workspaces/{ws}/inbound-triggers", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").post(request, "/workspaces/{ws}/inbound-triggers", PURCHASING).status()).isEqualTo(404);
        Map<String, Object> unknownWorkflow = new java.util.LinkedHashMap<>(request);
        unknownWorkflow.put("workflowId", UUID.randomUUID());
        assertThat(api("wade.admin").post(unknownWorkflow, "/workspaces/{ws}/inbound-triggers", PURCHASING).status()).isEqualTo(422);
        Map<String, Object> noTopics = new java.util.LinkedHashMap<>(request);
        noTopics.put("filter", Map.of());
        assertThat(api("wade.admin").post(noTopics, "/workspaces/{ws}/inbound-triggers", PURCHASING).status()).isEqualTo(422);

        Response created = api("wade.admin").post(request, "/workspaces/{ws}/inbound-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String triggerId = created.body().path("id").asString();
        assertThat(created.body().path("receivePath").asString()).endsWith("/inbound/" + triggerId);
        assertThat(api("dana.designer").get("/workspaces/{ws}/inbound-triggers", PURCHASING).body().path("items").get(0)
                .path("receivePath").isNull()).isTrue();

        String body = "{\"topic\":\"purchases\",\"value\":{\"title\":\"Replacement laptop\",\"purpose\":\"Device failed\","
                + "\"costCenter\":\"OPS\",\"amount\":1200,\"internalNote\":\"do-not-store-this\"}}";
        long runsBefore = runCount(workflowId);

        // Missing or bad signatures, stale timestamps and tampered bodies are all 401 and store nothing.
        assertThat(post(triggerId, "evt-1", now(), "sha256=" + "0".repeat(64), body).getResponse().getStatus()).isEqualTo(401);
        assertThat(post(triggerId, "evt-1", now(), null, body).getResponse().getStatus()).isEqualTo(401);
        String stale = String.valueOf(Instant.now().getEpochSecond() - 3600);
        assertThat(post(triggerId, "evt-1", stale, sign(stale, body), body).getResponse().getStatus()).isEqualTo(401);
        String ts = now();
        assertThat(post(triggerId, "evt-1", ts, sign(ts, body), body.replace("1200", "9999")).getResponse().getStatus()).isEqualTo(401);
        assertThat(eventCount(triggerId)).isZero();

        // Accepted once; the same event id is acknowledged as a duplicate and stored once.
        MvcResult accepted = post(triggerId, "evt-1", ts, sign(ts, body), body);
        assertThat(accepted.getResponse().getStatus()).as(accepted.getResponse().getContentAsString()).isEqualTo(202);
        OpenApiContract.validate(accepted);
        JsonNode first = json.parse(accepted.getResponse().getContentAsString());
        assertThat(first.path("duplicate").asBoolean()).isFalse();
        MvcResult again = post(triggerId, "evt-1", ts, sign(ts, body), body);
        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        OpenApiContract.validate(again);
        assertThat(json.parse(again.getResponse().getContentAsString()).path("duplicate").asBoolean()).isTrue();
        assertThat(json.parse(again.getResponse().getContentAsString()).path("eventId").asString())
                .isEqualTo(first.path("eventId").asString());
        assertThat(eventCount(triggerId)).isEqualTo(1);

        // Only the mapped scalars are kept: no raw payload and no unmapped field reaches the database.
        assertThat(jdbc.sql("SELECT mapped_input::text FROM inbound_event WHERE trigger_id = :t").param("t", UUID.fromString(triggerId))
                .query(String.class).single()).contains("Replacement laptop").doesNotContain("do-not-store-this");
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE mapped_input::text LIKE '%do-not-store%'").query(Long.class).single())
                .isZero();

        // Sender filter, shape and size rules answer with problems and store nothing.
        String otherTopic = body.replace("purchases", "payroll");
        String t2 = now();
        assertThat(post(triggerId, "evt-2", t2, sign(t2, otherTopic), otherTopic).getResponse().getStatus()).isEqualTo(422);
        String missingField = "{\"topic\":\"purchases\",\"value\":{\"title\":\"x\"}}";
        assertThat(post(triggerId, "evt-3", t2, sign(t2, missingField), missingField).getResponse().getStatus()).isEqualTo(422);
        String huge = "{\"topic\":\"purchases\",\"value\":{\"title\":\"" + "x".repeat(70_000) + "\"}}";
        assertThat(post(triggerId, "evt-4", t2, sign(t2, huge), huge).getResponse().getStatus()).isEqualTo(413);
        assertThat(post(triggerId, "bad id!", t2, sign(t2, body), body).getResponse().getStatus()).isEqualTo(400);
        assertThat(post(UUID.randomUUID().toString(), "evt-5", t2, sign(t2, body), body).getResponse().getStatus()).isEqualTo(404);
        assertThat(eventCount(triggerId)).isEqualTo(1);

        // No bearer token or session can use the new routes; machine tokens stay limited to runs and messages.
        var bearer = mvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + PURCHASING + "/adapters")
                .header("Authorization", "Bearer machine.in02")).andReturn();
        assertThat(bearer.getResponse().getStatus()).isEqualTo(403);

        // The event waits: nothing started a run.
        assertThat(runCount(workflowId)).isEqualTo(runsBefore);

        // Visibility: only people who can start the workflow see event data.
        assertThat(api("wade.admin").get("/workspaces/{ws}/inbound-events", PURCHASING).status()).isEqualTo(403);
        assertThat(api("olga.operator").get("/workspaces/{ws}/inbound-events", PURCHASING).status()).isEqualTo(403);
        // Other tests share the workspace, so only this trigger's events are considered.
        List<JsonNode> events = new java.util.ArrayList<>();
        api("rhea.requester").get("/workspaces/{ws}/inbound-events", PURCHASING).body().path("items").forEach(item -> {
            if (triggerId.equals(item.path("triggerId").asString())) {
                events.add(item);
            }
        });
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("input").path("amount").asInt()).isEqualTo(1200);
        String eventId = events.get(0).path("id").asString();

        // Start: people without start rights are refused; a starter becomes the initiator; repeats return the same run.
        assertThat(api("olga.operator").post(null, "/workspaces/{ws}/inbound-events/{e}/start", PURCHASING, eventId).status())
                .isEqualTo(403);
        assertThat(api("otto.outsider").post(null, "/workspaces/{ws}/inbound-events/{e}/start", PURCHASING, eventId).status())
                .isEqualTo(404);
        assertThat(api("rhea.requester").post(null, "/workspaces/{ws}/inbound-events/{e}/start", PURCHASING, UUID.randomUUID()).status())
                .isEqualTo(404);
        Response started = api("rhea.requester").post(null, "/workspaces/{ws}/inbound-events/{e}/start", PURCHASING, eventId);
        assertThat(started.status()).as(started.toString()).isEqualTo(200);
        assertThat(started.body().path("alreadyStarted").asBoolean()).isFalse();
        String runId = started.body().path("runId").asString();
        JsonNode run = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body();
        assertThat(run.path("initiator").path("displayName").asString()).containsIgnoringCase("rhea");
        Response repeat = api("mani.manager").post(null, "/workspaces/{ws}/inbound-events/{e}/start", PURCHASING, eventId);
        assertThat(repeat.status()).as(repeat.toString()).isEqualTo(200);
        assertThat(repeat.body().path("alreadyStarted").asBoolean()).isTrue();
        assertThat(repeat.body().path("runId").asString()).isEqualTo(runId);
        assertThat(runCount(workflowId)).isEqualTo(runsBefore + 1);
        assertThat(jdbc.sql("SELECT status FROM inbound_event WHERE id = :id").param("id", UUID.fromString(eventId)).query(String.class)
                .single()).isEqualTo("started");

        // A later duplicate delivery of the same event id is still just acknowledged.
        MvcResult late = post(triggerId, "evt-1", ts, sign(ts, body), body);
        assertThat(json.parse(late.getResponse().getContentAsString()).path("status").asString()).isEqualTo("started");
        assertThat(runCount(workflowId)).isEqualTo(runsBefore + 1);

        String audit = jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id = :ws AND action = 'inbound.received' "
                + "ORDER BY occurred_at DESC LIMIT 1").param("ws", PURCHASING).query(String.class).single();
        assertThat(json.parse(audit).propertyNames()).containsExactlyInAnyOrder("source", "key", "sizeBytes");
        assertThat(audit).doesNotContain("Replacement laptop", SECRET);
    }

    // ---------------------------------------------------------------- S73: edit, disable, cap and retention

    @Test
    void adaptersAndTriggersAreEditedAndDisabledUnderManageWithVersionChecks() throws Exception {
        String kafka = key("orders");
        assertThat(createAs(kafka, "kafka", Map.of("allowedTopics", List.of("orders.created", "orders.cancelled"))).status())
                .isEqualTo(201);
        Map<String, Object> edit = new java.util.LinkedHashMap<>(Map.of("name", "Orders (edited)", "secretRef", "kafka-creds-2",
                "config", Map.of("allowedTopics", List.of("orders.created")), "enabled", true, "expectedVersion", 1));
        assertThat(api("dana.designer").put(edit, null, "/workspaces/{ws}/adapters/{k}", PURCHASING, kafka).status()).isEqualTo(403);
        assertThat(api("otto.outsider").put(edit, null, "/workspaces/{ws}/adapters/{k}", PURCHASING, kafka).status()).isEqualTo(404);
        assertThat(api("wade.admin").put(edit, null, "/workspaces/{ws}/adapters/{k}", PURCHASING, "missing-key").status())
                .isEqualTo(404);
        Map<String, Object> secretInConfig = new java.util.LinkedHashMap<>(edit);
        secretInConfig.put("config", Map.of("allowedTopics", List.of("orders.created"), "saslPassword", "hunter2"));
        assertThat(api("wade.admin").put(secretInConfig, null, "/workspaces/{ws}/adapters/{k}", PURCHASING, kafka).status())
                .isEqualTo(422);

        Response updated = api("wade.admin").put(edit, null, "/workspaces/{ws}/adapters/{k}", PURCHASING, kafka);
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat(updated.body().path("version").asInt()).isEqualTo(2);
        assertThat(updated.body().path("kind").asString()).isEqualTo("kafka");
        // A stale editor is refused, so a concurrent change is never overwritten silently.
        Response stale = api("wade.admin").put(edit, null, "/workspaces/{ws}/adapters/{k}", PURCHASING, kafka);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("CONFIGURATION_VERSION_CONFLICT");
        // The narrowed allowlist applies at once.
        assertThat(sendAs(kafka, Map.of("topic", "orders.cancelled", "value", Map.of("id", 1))).status()).isEqualTo(422);
        edit.put("expectedVersion", 2);
        edit.put("enabled", false);
        assertThat(api("wade.admin").put(edit, null, "/workspaces/{ws}/adapters/{k}", PURCHASING, kafka).status()).isEqualTo(200);
        Response refused = sendAs(kafka, Map.of("topic", "orders.created", "value", Map.of("id", 1)));
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body().toString()).contains("disabled");
        assertThat(stub.sent()).isEmpty();
        assertThat(find(api("dana.designer").get("/workspaces/{ws}/adapters", PURCHASING).body(), kafka).path("enabled").asBoolean())
                .isFalse();
        String audit = jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id = :ws AND action = 'adapter.updated' "
                + "ORDER BY occurred_at DESC LIMIT 1").param("ws", PURCHASING).query(String.class).single();
        assertThat(json.parse(audit).propertyNames()).containsExactlyInAnyOrder("kind", "key", "version", "enabled");

        // Triggers: a disabled trigger answers senders like an unknown one; an edited mapping applies to new events.
        UUID workflowId = publishPurchase(PURCHASING, null);
        String triggerId = createTrigger(workflowId, "purchases");
        Map<String, Object> triggerEdit = new java.util.LinkedHashMap<>(Map.of("name", "Orders feed", "secretRef", "in02-inbound",
                "filter", Map.of("allowedTopics", List.of("purchases")), "mapping", Map.of("title", "/value/title"),
                "enabled", false, "expectedVersion", 1));
        assertThat(api("dana.designer").put(triggerEdit, null, "/workspaces/{ws}/inbound-triggers/{t}", PURCHASING, triggerId)
                .status()).isEqualTo(403);
        assertThat(api("wade.admin").put(triggerEdit, null, "/workspaces/{ws}/inbound-triggers/{t}", PURCHASING, triggerId)
                .status()).isEqualTo(200);
        String body = "{\"topic\":\"purchases\",\"value\":{\"title\":\"Edited mapping\",\"amount\":5}}";
        String ts = now();
        assertThat(post(triggerId, "edit-1", ts, sign(ts, body), body).getResponse().getStatus()).isEqualTo(404);
        triggerEdit.put("enabled", true);
        triggerEdit.put("expectedVersion", 2);
        Response reenabled = api("wade.admin").put(triggerEdit, null, "/workspaces/{ws}/inbound-triggers/{t}", PURCHASING, triggerId);
        assertThat(reenabled.status()).as(reenabled.toString()).isEqualTo(200);
        assertThat(reenabled.body().path("version").asInt()).isEqualTo(3);
        assertThat(api("wade.admin").put(triggerEdit, null, "/workspaces/{ws}/inbound-triggers/{t}", PURCHASING, triggerId).code())
                .isEqualTo("CONFIGURATION_VERSION_CONFLICT");
        assertThat(post(triggerId, "edit-1", ts, sign(ts, body), body).getResponse().getStatus()).isEqualTo(202);
        assertThat(jdbc.sql("SELECT mapped_input::text FROM inbound_event WHERE trigger_id = :t").param("t", UUID.fromString(triggerId))
                .query(String.class).single()).contains("Edited mapping").doesNotContain("amount");
    }

    @Test
    void aTriggerKeepsAtMostOneThousandUnstartedEvents() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        String triggerId = createTrigger(workflowId, "purchases");
        String other = createTrigger(workflowId, "purchases");
        // Every event goes through the real signed endpoint.
        for (int i = 1; i <= InboundService.MAX_PENDING_PER_TRIGGER; i++) {
            String body = "{\"topic\":\"purchases\",\"value\":{\"title\":\"Event " + i + "\",\"purpose\":\"p\",\"costCenter\":\"OPS\",\"amount\":" + i + "}}";
            String ts = now();
            int status = post(triggerId, "cap-" + i, ts, sign(ts, body), body).getResponse().getStatus();
            assertThat(status).as("event " + i).isEqualTo(202);
        }
        assertThat(eventCount(triggerId)).isEqualTo(1000);
        String body = "{\"topic\":\"purchases\",\"value\":{\"title\":\"One too many\",\"purpose\":\"p\",\"costCenter\":\"OPS\",\"amount\":1}}";
        String ts = now();
        MvcResult full = post(triggerId, "cap-1001", ts, sign(ts, body), body);
        assertThat(full.getResponse().getStatus()).isEqualTo(429);
        assertThat(full.getResponse().getHeader("Retry-After")).isEqualTo("60");
        OpenApiContract.validate(full);
        assertThat(eventCount(triggerId)).isEqualTo(1000);
        // A repeated delivery of a stored event is still acknowledged while the trigger is full.
        String first = "{\"topic\":\"purchases\",\"value\":{\"title\":\"Event 1\",\"purpose\":\"p\",\"costCenter\":\"OPS\",\"amount\":1}}";
        MvcResult duplicate = post(triggerId, "cap-1", ts, sign(ts, first), first);
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(202);
        assertThat(json.parse(duplicate.getResponse().getContentAsString()).path("duplicate").asBoolean()).isTrue();
        // The cap is per trigger.
        assertThat(post(other, "cap-1001", ts, sign(ts, body), body).getResponse().getStatus()).isEqualTo(202);
        // Starting one event frees one place.
        String eventId = jdbc.sql("SELECT id FROM inbound_event WHERE trigger_id = :t AND event_id = 'cap-1'")
                .param("t", UUID.fromString(triggerId)).query(String.class).single();
        Response started = api("rhea.requester").post(null, "/workspaces/{ws}/inbound-events/{e}/start", PURCHASING, eventId);
        assertThat(started.status()).as(started.toString()).isEqualTo(200);
        assertThat(post(triggerId, "cap-1001", ts, sign(ts, body), body).getResponse().getStatus()).isEqualTo(202);
        String ts2 = now();
        assertThat(post(triggerId, "cap-1002", ts2, sign(ts2, body), body).getResponse().getStatus()).isEqualTo(429);
        // Leave the shared database tidy for other tests.
        jdbc.sql("DELETE FROM inbound_event WHERE trigger_id IN (:a, :b) AND status = 'received'")
                .param("a", UUID.fromString(triggerId)).param("b", UUID.fromString(other)).update();
    }

    @Test
    void retentionPurgesOldStartedEventsAndUnstartedOnlyWhenAsked() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        String triggerId = createTrigger(workflowId, "purchases");
        for (String id : List.of("old-started", "old-waiting", "new-waiting")) {
            String body = "{\"topic\":\"purchases\",\"value\":{\"title\":\"" + id + "\",\"purpose\":\"p\",\"costCenter\":\"OPS\",\"amount\":3}}";
            String ts = now();
            assertThat(post(triggerId, id, ts, sign(ts, body), body).getResponse().getStatus()).isEqualTo(202);
        }
        String oldStarted = jdbc.sql("SELECT id FROM inbound_event WHERE trigger_id = :t AND event_id = 'old-started'")
                .param("t", UUID.fromString(triggerId)).query(String.class).single();
        assertThat(api("rhea.requester").post(null, "/workspaces/{ws}/inbound-events/{e}/start", PURCHASING, oldStarted).status())
                .isEqualTo(200);
        jdbc.sql("UPDATE inbound_event SET received_at = now() - interval '45 days' WHERE trigger_id = :t AND event_id LIKE 'old-%'")
                .param("t", UUID.fromString(triggerId)).update();

        Map<String, Object> request = Map.of("olderThanDays", 30);
        assertThat(api("dana.designer").post(request, "/workspaces/{ws}/inbound-events/purge", PURCHASING).status()).isEqualTo(403);
        assertThat(api("rhea.requester").post(request, "/workspaces/{ws}/inbound-events/purge", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").post(request, "/workspaces/{ws}/inbound-events/purge", PURCHASING).status()).isEqualTo(404);
        assertThat(api("wade.admin").post(Map.of("olderThanDays", 0), "/workspaces/{ws}/inbound-events/purge", PURCHASING).status())
                .isIn(400, 422);

        Response purged = api("wade.admin").post(request, "/workspaces/{ws}/inbound-events/purge", PURCHASING);
        assertThat(purged.status()).as(purged.toString()).isEqualTo(200);
        assertThat(purged.body().path("deleted").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(eventIds(triggerId)).containsExactlyInAnyOrder("old-waiting", "new-waiting");
        Response withUnstarted = api("wade.admin").post(Map.of("olderThanDays", 30, "includeUnstarted", true),
                "/workspaces/{ws}/inbound-events/purge", PURCHASING);
        assertThat(withUnstarted.status()).isEqualTo(200);
        assertThat(eventIds(triggerId)).containsExactly("new-waiting");
        // The run started from the purged event is untouched.
        assertThat(runCount(workflowId)).isEqualTo(1);
        String audit = jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id = :ws AND action = 'inbound.events-purged' "
                + "ORDER BY occurred_at DESC LIMIT 1").param("ws", PURCHASING).query(String.class).single();
        assertThat(json.parse(audit).propertyNames()).containsExactlyInAnyOrder("olderThanDays", "includeUnstarted", "deleted");
        // A purged event id is accepted again as a new event: deduplication lasts as long as the event is kept.
        String body = "{\"topic\":\"purchases\",\"value\":{\"title\":\"again\",\"purpose\":\"p\",\"costCenter\":\"OPS\",\"amount\":3}}";
        String ts = now();
        MvcResult again = post(triggerId, "old-waiting", ts, sign(ts, body), body);
        assertThat(json.parse(again.getResponse().getContentAsString()).path("duplicate").asBoolean()).isFalse();
    }

    private String createTrigger(UUID workflowId, String topic) throws Exception {
        Response created = api("wade.admin").post(Map.of("key", key("feed"), "source", "kafka", "name", "Feed", "workflowId", workflowId,
                "secretRef", "in02-inbound", "filter", Map.of("allowedTopics", List.of(topic)),
                "mapping", Map.of("title", "/value/title", "purpose", "/value/purpose", "costCenter", "/value/costCenter",
                        "amount", "/value/amount")), "/workspaces/{ws}/inbound-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body().path("id").asString();
    }

    private List<String> eventIds(String triggerId) {
        return jdbc.sql("SELECT event_id FROM inbound_event WHERE trigger_id = :t").param("t", UUID.fromString(triggerId))
                .query(String.class).list();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode find(JsonNode list, String key) {
        for (JsonNode item : list.path("items")) {
            if (key.equals(item.path("key").asString())) {
                return item;
            }
        }
        throw new AssertionError("adapter " + key + " not listed");
    }

    private int create(String kind, Map<String, Object> config) throws Exception {
        return createAs(key("x"), kind, config).status();
    }

    private Response createAs(String key, String kind, Map<String, Object> config) throws Exception {
        return api("wade.admin").post(Map.of("key", key, "kind", kind, "name", "Adapter " + key, "secretRef", "adapter-secret",
                "config", config), "/workspaces/{ws}/adapters", PURCHASING);
    }

    private Response sendAs(String key, Map<String, Object> payload) throws Exception {
        return api("wade.admin").post(Map.of("payload", payload), "/workspaces/{ws}/adapters/{k}/test-send", PURCHASING, key);
    }

    private long runCount(UUID workflowId) {
        return jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :wf").param("wf", workflowId).query(Long.class).single();
    }

    private long eventCount(String triggerId) {
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE trigger_id = :t").param("t", UUID.fromString(triggerId))
                .query(Long.class).single();
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String sign(String timestamp, String body) {
        return "sha256=" + InboundService.sign(SECRET, timestamp, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Posts without any session, CSRF token or bearer token: the signature is the only credential. */
    private MvcResult post(String triggerId, String eventId, String timestamp, String signature, String body) throws Exception {
        var builder = MockMvcRequestBuilders.post("/api/v1/workspaces/" + PURCHASING + "/inbound/" + triggerId)
                .contentType(MediaType.APPLICATION_JSON).content(body).header("X-Orqix-Event-Id", eventId)
                .header("X-Orqix-Timestamp", timestamp);
        if (signature != null) {
            builder.header("X-Orqix-Signature", signature);
        }
        return mvc.perform(builder).andReturn();
    }
}
