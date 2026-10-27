package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.orqix.integration.adapters.InboundService;
import com.orqix.integration.webhooks.WebhookDispatcher;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/**
 * IN05 (b) (docs/build/S99): subscriptions under connection.manage with the egress allow-list, a transactional outbox
 * written with the run event, signed delivery with ids and states only, exponential retries, dead letters visible to
 * operators, replay, and SKIP LOCKED claiming. The receiver is the shared WireMock stub ({@code /webhooks/...}).
 */
class OutboundWebhookApiTest extends RuntimeTestBase {

    private static final String SECRET = "in05-webhook-secret-value";

    @Autowired
    private WebhookDispatcher dispatcher;

    @BeforeEach
    void prepare() throws Exception {
        Stub.resetWebhooks();
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("in05-webhook"), SECRET);
        // Other tests' subscriptions must not receive this class's events and vice versa.
        jdbc.sql("UPDATE webhook_subscription SET enabled = false WHERE workspace_id = :ws").param("ws", PURCHASING).update();
    }

    private JsonNode subscribe(String path, List<String> events) throws Exception {
        Response created = api("wade.admin").post(Map.of("name", "Ops feed", "url", Stub.baseUrl() + path,
                "secretRef", "in05-webhook", "events", events), "/workspaces/{ws}/webhook-subscriptions", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body();
    }

    private void dueNow() {
        jdbc.sql("UPDATE webhook_delivery SET next_attempt_at = now() - interval '1 second' WHERE workspace_id = :ws AND status = 'pending'")
                .param("ws", PURCHASING).update();
    }

    @Test
    void subscriptionsNeedConnectionManageAndAnAllowListedEndpoint() throws Exception {
        Map<String, Object> body = Map.of("name", "Feed", "url", "https://not-allowed.example.com/hook",
                "secretRef", "in05-webhook", "events", List.of("request.started"));
        assertThat(api("dana.designer").post(body, "/workspaces/{ws}/webhook-subscriptions", PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("otto.outsider").post(body, "/workspaces/{ws}/webhook-subscriptions", PURCHASING).status())
                .isEqualTo(404);
        Response refused = api("wade.admin").post(body, "/workspaces/{ws}/webhook-subscriptions", PURCHASING);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("EGRESS_NOT_ALLOWED");
        Response metadata = api("wade.admin").post(Map.of("name", "Feed", "url", "https://169.254.169.254:443/x",
                "secretRef", "in05-webhook", "events", List.of("request.started")), "/workspaces/{ws}/webhook-subscriptions",
                PURCHASING);
        assertThat(metadata.status()).isEqualTo(422);
        Response unknownEvent = api("wade.admin").post(Map.of("name", "Feed", "url", Stub.baseUrl() + "/webhooks/a",
                "secretRef", "in05-webhook", "events", List.of("request.deleted")), "/workspaces/{ws}/webhook-subscriptions",
                PURCHASING);
        assertThat(unknownEvent.status()).isIn(400, 422);

        JsonNode created = subscribe("/webhooks/perm", List.of("request.started"));
        // Readers see it without the secret reference; managers see it.
        JsonNode asDesigner = api("dana.designer").get("/workspaces/{ws}/webhook-subscriptions", PURCHASING).body();
        assertThat(asDesigner.toString()).doesNotContain("in05-webhook");
        Response stale = api("wade.admin").put(Map.of("name", "Feed 2", "url", Stub.baseUrl() + "/webhooks/perm",
                "secretRef", "in05-webhook", "events", List.of("request.started"), "enabled", false, "expectedVersion", 7),
                null, "/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, created.path("id").asString());
        assertThat(stale.status()).isEqualTo(409);
        Response updated = api("wade.admin").put(Map.of("name", "Feed 2", "url", Stub.baseUrl() + "/webhooks/perm",
                "secretRef", "in05-webhook", "events", List.of("request.started"), "enabled", false, "expectedVersion", 1),
                null, "/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, created.path("id").asString());
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat(updated.body().path("version").asInt()).isEqualTo(2);
        // Operators (run.operate) see dead letters; designers do not.
        assertThat(api("dana.designer").get("/workspaces/{ws}/webhook-deliveries", PURCHASING).status()).isEqualTo(403);
        assertThat(api("olga.operator").get("/workspaces/{ws}/webhook-deliveries", PURCHASING).status()).isEqualTo(200);
    }

    @Test
    void requestEventsAreDeliveredSignedWithIdsAndStatesOnly() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        subscribe("/webhooks/ok", List.of("request.started", "task.opened"));
        JsonNode started = start("rhea.requester", PURCHASING, workflowId, BigDecimal.valueOf(1234));
        UUID run = UUID.fromString(started.path("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE run_id = :r").param("r", run)
                .query(Long.class).single()).isGreaterThanOrEqualTo(1);
        dueNow();
        dispatcher.run();
        List<Stub.WebhookCall> calls = Stub.webhooks().stream().filter(c -> c.body().contains(run.toString())).toList();
        assertThat(calls).isNotEmpty();
        Stub.WebhookCall call = calls.get(0);
        String ts = call.headers().get("x-orqix-timestamp");
        assertThat(call.headers().get("x-orqix-signature"))
                .isEqualTo("sha256=" + InboundService.sign(SECRET, ts, call.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(call.headers().get("x-orqix-webhook-id")).isNotBlank();
        JsonNode payload = json.parse(call.body());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("id", "eventId", "type", "occurredAt",
                "workspaceId", "request", "step");
        assertThat(payload.path("request").propertyNames()).containsExactlyInAnyOrder("id", "reference", "workflowId", "state");
        // No request data: the amount, title and purpose never leave.
        assertThat(call.body()).doesNotContain("1234").doesNotContain(input(BigDecimal.ONE).get("title").toString());
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE run_id = :r AND status = 'delivered'")
                .param("r", run).query(Long.class).single()).isEqualTo(calls.size());
    }

    @Test
    void failuresRetryWithBackoffThenBecomeDeadLettersThatOperatorsReplay() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        subscribe("/webhooks/failing", List.of("request.started"));
        Stub.webhookStatuses(500, 503);
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN).path("id").asString());
        UUID delivery = jdbc.sql("SELECT id FROM webhook_delivery WHERE run_id = :r AND event_type = 'request.started'")
                .param("r", run).query(UUID.class).single();
        dueNow();
        dispatcher.run();
        record Row(String status, int attempts, Integer code, String error, java.sql.Timestamp next) {
        }
        java.util.function.Supplier<Row> row = () -> jdbc.sql(
                "SELECT status, attempts, last_status_code, last_error, next_attempt_at FROM webhook_delivery WHERE id = :id")
                .param("id", delivery).query((rs, n) -> new Row(rs.getString(1), rs.getInt(2), (Integer) rs.getObject(3),
                        rs.getString(4), rs.getTimestamp(5))).single();
        Row first = row.get();
        assertThat(first.status()).isEqualTo("pending");
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.code()).isEqualTo(500);
        // First backoff is about 30 s (30-33 s with jitter).
        long wait = first.next().toInstant().getEpochSecond() - java.time.Instant.now().getEpochSecond();
        assertThat(wait).isBetween(25L, 34L);
        // Not due yet: another pass does not send it.
        int before = Stub.webhooks().size();
        dispatcher.run();
        assertThat(Stub.webhooks().size()).isEqualTo(before);
        // Last attempt fails -> dead letter, audited without URL or payload.
        jdbc.sql("UPDATE webhook_delivery SET attempts = 7 WHERE id = :id").param("id", delivery).update();
        dueNow();
        dispatcher.run();
        Row dead = row.get();
        assertThat(dead.status()).isEqualTo("dead");
        assertThat(dead.attempts()).isEqualTo(8);
        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'webhook.delivery-dead' AND resource_id = :id")
                .param("id", delivery.toString()).query(String.class).single();
        assertThat(metadata).doesNotContain("webhooks/failing").doesNotContain(run.toString());

        JsonNode deadLetters = api("olga.operator").get("/workspaces/{ws}/webhook-deliveries?status=dead", PURCHASING).body();
        assertThat(deadLetters.path("items").findValuesAsString("id")).contains(delivery.toString());
        assertThat(api("dana.designer").post(null, "/workspaces/{ws}/webhook-deliveries/{id}/replay", PURCHASING, delivery)
                .status()).isEqualTo(403);
        Response replayed = api("olga.operator").post(null, "/workspaces/{ws}/webhook-deliveries/{id}/replay", PURCHASING,
                delivery);
        assertThat(replayed.status()).as(replayed.toString()).isEqualTo(200);
        assertThat(replayed.body().path("status").asString()).isEqualTo("pending");
        assertThat(replayed.body().path("replays").asInt()).isEqualTo(1);
        dispatcher.run();
        assertThat(row.get().status()).isEqualTo("delivered");
        // Only a dead letter can be replayed.
        assertThat(api("olga.operator").post(null, "/workspaces/{ws}/webhook-deliveries/{id}/replay", PURCHASING, delivery)
                .status()).isEqualTo(422);
    }

    @Test
    void anEndpointThatLeavesTheAllowListIsDeadLetteredWithoutSending() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        JsonNode sub = subscribe("/webhooks/moved", List.of("request.started"));
        // Simulate a configuration change behind the API: the stored URL is no longer allowed.
        jdbc.sql("UPDATE webhook_subscription SET url = 'https://not-allowed.example.com/x' WHERE id = :id")
                .param("id", UUID.fromString(sub.path("id").asString())).update();
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN).path("id").asString());
        dueNow();
        int before = Stub.webhooks().size();
        dispatcher.run();
        assertThat(Stub.webhooks().size()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT last_error FROM webhook_delivery WHERE run_id = :r").param("r", run)
                .query(String.class).single()).isEqualTo("egress_denied");
    }
}
