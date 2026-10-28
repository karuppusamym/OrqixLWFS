package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.internal.SecretStore;
import com.orqix.integration.webhooks.WebhookDispatcher;
import com.orqix.integration.webhooks.WebhookSettings;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/**
 * IN05 (b) (docs/build/S99): subscriptions under connection.manage with the egress allow-list and a webhook-only secret
 * name space, a transactional outbox, domain-separated signatures with ids and states only, exponential retries, dead
 * letters, pause and replay limits, per-row claims with a fenced finish (two dispatchers, hanging receivers, expired
 * leases), per-subscription time budgets, a dedicated scheduler, and connections pinned to the vetted address. The
 * receiver is the shared WireMock stub ({@code /webhooks/...}).
 */
class OutboundWebhookApiTest extends RuntimeTestBase {

    private static final String SECRET = "in05-webhook-secret-value";
    private static final String REF = "webhook-in05";

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private EgressPolicy egress;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private SecretStore secrets;

    @Autowired
    private WebhookSettings settings;

    @Autowired
    private AuditWriter audit;

    @Autowired
    private java.time.Clock clock;

    @BeforeEach
    void prepare() throws Exception {
        Stub.resetWebhooks();
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(REF), SECRET);
        // Each test starts without subscriptions in the workspace (the cap is 10, and other tests' events must not
        // reach them). The application role cannot delete subscriptions, so the owner connection does it.
        var owner = com.orqix.support.TestDatabase.legacyFixtureJdbc();
        owner.sql("DELETE FROM orqix.webhook_delivery WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.webhook_subscription WHERE workspace_id = :ws").param("ws", PURCHASING).update();
    }

    @AfterEach
    void restoreResolver() {
        egress.setResolver(null);
        Stub.resetWebhooks();
    }

    private JsonNode subscribe(String path, List<String> events) throws Exception {
        Response created = api("wade.admin").post(Map.of("name", "Ops feed", "url", Stub.baseUrl() + path,
                "secretRef", REF, "events", events), "/workspaces/{ws}/webhook-subscriptions", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body();
    }

    private void dueNow() {
        jdbc.sql("UPDATE webhook_delivery SET next_attempt_at = now() - interval '1 second' WHERE workspace_id = :ws AND status = 'pending'")
                .param("ws", PURCHASING).update();
    }

    /** A pending delivery written directly, due {@code secondsAgo} seconds ago (ids-only payload). */
    private UUID delivery(UUID subscription, int secondsAgo) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, workspace_id, subscription_id, event_key, event_type, payload, status,
                                              next_attempt_at, created_at)
                VALUES (:id, :ws, :s, :key, 'request.started', CAST(:payload AS jsonb), 'pending', now() - make_interval(secs => :ago), now())
                """).param("id", id).param("ws", PURCHASING).param("s", subscription).param("key", "test:" + id)
                .param("payload", "{\"id\":\"" + id + "\",\"type\":\"request.started\"}").param("ago", secondsAgo).update();
        return id;
    }

    private String status(UUID delivery) {
        return jdbc.sql("SELECT status FROM webhook_delivery WHERE id = :id").param("id", delivery).query(String.class).single();
    }

    private long received(UUID delivery) {
        return Stub.webhooks().stream().filter(c -> delivery.toString().equals(c.headers().get("x-orqix-webhook-id"))).count();
    }

    private WebhookDispatcher secondPod() {
        return new WebhookDispatcher(jdbc, transactions, egress, secrets, settings, audit, clock);
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void aSubscriptionCanBeDeletedOnlyOnceItIsSwitchedOffAndTakesItsDeliveriesWithIt() throws Exception {
        JsonNode sub = subscribe("/hooks/gone", List.of("request.started"));
        String id = sub.path("id").asString();
        delivery(UUID.fromString(id), 0);
        Response on = api("wade.admin").delete("/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, id);
        assertThat(on.status()).isEqualTo(409);
        assertThat(on.code()).isEqualTo("RESOURCE_IN_USE");
        Response off = api("wade.admin").put(Map.of("name", "Ops feed", "url", Stub.baseUrl() + "/hooks/gone", "secretRef", REF,
                "events", List.of("request.started"), "enabled", false, "expectedVersion", sub.path("version").asInt()), null,
                "/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, id);
        assertThat(off.status()).as(off.toString()).isEqualTo(200);
        assertThat(api("dana.designer").delete("/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, id).status()).isEqualTo(403);
        assertThat(api("wade.admin").delete("/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, id).status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = :s").param("s", UUID.fromString(id))
                .query(Long.class).single()).isZero();
        assertThat(api("wade.admin").delete("/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, id).status()).isEqualTo(404);
    }

    @Test
    void subscriptionsNeedConnectionManageAnAllowListedEndpointAndAWebhookOnlySecret() throws Exception {
        Map<String, Object> body = Map.of("name", "Feed", "url", "https://not-allowed.example.com/hook",
                "secretRef", REF, "events", List.of("request.started"));
        assertThat(api("dana.designer").post(body, "/workspaces/{ws}/webhook-subscriptions", PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("otto.outsider").post(body, "/workspaces/{ws}/webhook-subscriptions", PURCHASING).status())
                .isEqualTo(404);
        Response refused = api("wade.admin").post(body, "/workspaces/{ws}/webhook-subscriptions", PURCHASING);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("EGRESS_NOT_ALLOWED");
        for (String url : List.of("https://169.254.169.254:443/x", "https://user:pw@public.test.example/x",
                "https://[::1]/x", "https://public.test.example/x?token=1")) {
            assertThat(api("wade.admin").post(Map.of("name", "Feed", "url", url, "secretRef", REF, "events",
                    List.of("request.started")), "/workspaces/{ws}/webhook-subscriptions", PURCHASING).status()).as(url)
                    .isEqualTo(422);
        }
        Response unknownEvent = api("wade.admin").post(Map.of("name", "Feed", "url", Stub.baseUrl() + "/webhooks/a",
                "secretRef", REF, "events", List.of("request.deleted")), "/workspaces/{ws}/webhook-subscriptions", PURCHASING);
        assertThat(unknownEvent.status()).isIn(400, 422);

        JsonNode created = subscribe("/webhooks/perm/secret-path-token", List.of("request.started"));
        // Readers see neither the secret reference nor the path (it often carries a credential).
        JsonNode asDesigner = api("dana.designer").get("/workspaces/{ws}/webhook-subscriptions", PURCHASING).body();
        assertThat(asDesigner.toString()).doesNotContain(REF).doesNotContain("secret-path-token").contains("/…");
        assertThat(api("wade.admin").get("/workspaces/{ws}/webhook-subscriptions", PURCHASING).body().toString())
                .contains("secret-path-token");
        Response stale = api("wade.admin").put(Map.of("name", "Feed 2", "url", Stub.baseUrl() + "/webhooks/perm",
                "secretRef", REF, "events", List.of("request.started"), "enabled", false, "expectedVersion", 7),
                null, "/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, created.path("id").asString());
        assertThat(stale.status()).isEqualTo(409);
        Response updated = api("wade.admin").put(Map.of("name", "Feed 2", "url", Stub.baseUrl() + "/webhooks/perm",
                "secretRef", REF, "events", List.of("request.started"), "enabled", false, "expectedVersion", 1),
                null, "/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, created.path("id").asString());
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat(updated.body().path("version").asInt()).isEqualTo(2);
        assertThat(api("dana.designer").get("/workspaces/{ws}/webhook-deliveries", PURCHASING).status()).isEqualTo(403);
        assertThat(api("olga.operator").get("/workspaces/{ws}/webhook-deliveries", PURCHASING).status()).isEqualTo(200);
    }

    @Test
    void aWebhookCannotBorrowAnInboundTriggerSecretAndItsSignatureNeverVerifiesInbound() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
        Files.writeString(dir.resolve("in05-sig"), SECRET);
        Response trigger = api("wade.admin").post(Map.of("key", "sig-" + UUID.randomUUID().toString().substring(0, 8),
                "source", "kafka", "name", "Sig", "workflowId", workflowId, "secretRef", "in05-sig",
                "filter", Map.of("allowedTopics", List.of("t")), "mapping", Map.of("title", "/value/title")),
                "/workspaces/{ws}/inbound-triggers", PURCHASING);
        assertThat(trigger.status()).as(trigger.toString()).isEqualTo(201);
        String triggerId = trigger.body().path("id").asString();

        // The inbound trigger's own reference (outside the webhook name space) is refused for a webhook...
        Response borrowed = api("wade.admin").post(Map.of("name", "Borrow", "url", Stub.baseUrl() + "/webhooks/x",
                "secretRef", "in05-sig", "events", List.of("request.started")), "/workspaces/{ws}/webhook-subscriptions",
                PURCHASING);
        assertThat(borrowed.status()).isEqualTo(422);
        // ...an inbound trigger may not take a webhook- reference...
        assertThat(api("wade.admin").post(Map.of("key", "sig-" + UUID.randomUUID().toString().substring(0, 8),
                "source", "kafka", "name", "Sig", "workflowId", workflowId, "secretRef", REF,
                "filter", Map.of("allowedTopics", List.of("t")), "mapping", Map.of("title", "/value/title")),
                "/workspaces/{ws}/inbound-triggers", PURCHASING).status()).isEqualTo(422);
        // ...and a webhook- reference already used by any configuration (of any workspace) is refused too.
        jdbc.sql("UPDATE inbound_trigger SET secret_ref = 'webhook-legacy-shared' WHERE id = :id")
                .param("id", UUID.fromString(triggerId)).update();
        Response shared = api("wade.admin").post(Map.of("name", "Shared", "url", Stub.baseUrl() + "/webhooks/x",
                "secretRef", "webhook-legacy-shared", "events", List.of("request.started")),
                "/workspaces/{ws}/webhook-subscriptions", PURCHASING);
        assertThat(shared.status()).isEqualTo(422);
        assertThat(shared.body().path("fieldErrors").get(0).path("code").asString()).isEqualTo("inUse");
        jdbc.sql("UPDATE inbound_trigger SET secret_ref = 'in05-sig' WHERE id = :id").param("id", UUID.fromString(triggerId))
                .update();

        // Even with the very same secret value in both files, an outbound signature is no inbound signature.
        subscribe("/webhooks/sig", List.of("request.started"));
        start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN);
        dueNow();
        dispatcher.run();
        Stub.WebhookCall call = Stub.webhooks().getLast();
        String ts = call.headers().get("x-orqix-timestamp");
        String signature = call.headers().get("x-orqix-signature");
        assertThat(signature).startsWith(WebhookDispatcher.SIGNATURE_PREFIX)
                .isEqualTo("v1=" + WebhookDispatcher.sign(SECRET, call.headers().get("x-orqix-webhook-id"), ts,
                        call.body().getBytes(StandardCharsets.UTF_8)));
        for (String presented : List.of(signature, "sha256=" + signature.substring(3))) {
            int status = api(null).send(MockMvcRequestBuilders.post("/api/v1/workspaces/{ws}/inbound/{t}", PURCHASING, triggerId)
                    .contentType(MediaType.APPLICATION_JSON).content(call.body()).header("X-Orqix-Event-Id", "evt-sig")
                    .header("X-Orqix-Timestamp", ts).header("X-Orqix-Signature", presented)).status();
            assertThat(status).as(presented.substring(0, 3)).isEqualTo(401);
        }
    }

    // ---------------------------------------------------------------- delivery

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
        assertThat(call.headers().get("x-orqix-signature")).isEqualTo("v1=" + WebhookDispatcher.sign(SECRET,
                call.headers().get("x-orqix-webhook-id"), ts, call.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(call.headers().get("host")).isEqualTo("localhost:" + Stub.port());
        JsonNode payload = json.parse(call.body());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("id", "eventId", "type", "occurredAt",
                "workspaceId", "request", "step");
        assertThat(payload.path("request").propertyNames()).containsExactlyInAnyOrder("id", "reference", "workflowId", "state");
        assertThat(call.body()).doesNotContain("1234").doesNotContain("Replacement laptop");
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
        assertThat(status(delivery)).isEqualTo("pending");
        assertThat(jdbc.sql("SELECT last_status_code FROM webhook_delivery WHERE id = :id").param("id", delivery)
                .query(Integer.class).single()).isEqualTo(500);
        long wait = jdbc.sql("SELECT EXTRACT(EPOCH FROM next_attempt_at - now())::bigint FROM webhook_delivery WHERE id = :id")
                .param("id", delivery).query(Long.class).single();
        assertThat(wait).isBetween(25L, 34L);
        int before = Stub.webhooks().size();
        dispatcher.run();
        assertThat(Stub.webhooks().size()).isEqualTo(before);
        jdbc.sql("UPDATE webhook_delivery SET attempts = 7 WHERE id = :id").param("id", delivery).update();
        dueNow();
        dispatcher.run();
        assertThat(status(delivery)).isEqualTo("dead");
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
        assertThat(replayed.body().path("replays").asInt()).isEqualTo(1);
        dispatcher.run();
        assertThat(status(delivery)).isEqualTo("delivered");
        assertThat(api("olga.operator").post(null, "/workspaces/{ws}/webhook-deliveries/{id}/replay", PURCHASING, delivery)
                .status()).isEqualTo(422);
    }

    @Test
    void pausingDeadLettersTheBacklogAndReplayRespectsPauseAndThePendingCap() throws Exception {
        JsonNode sub = subscribe("/webhooks/paused", List.of("request.started"));
        UUID subscription = UUID.fromString(sub.path("id").asString());
        UUID waiting = delivery(subscription, 1);
        Response paused = api("wade.admin").put(Map.of("name", "Ops feed", "url", Stub.baseUrl() + "/webhooks/paused",
                "secretRef", REF, "events", List.of("request.started"), "enabled", false, "expectedVersion", 1), null,
                "/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, subscription);
        assertThat(paused.status()).isEqualTo(200);
        assertThat(status(waiting)).isEqualTo("dead");
        assertThat(jdbc.sql("SELECT last_error FROM webhook_delivery WHERE id = :id").param("id", waiting)
                .query(String.class).single()).isEqualTo("subscription_paused");
        // Replay is refused while paused, then capped by the pending limit after resuming.
        assertThat(api("olga.operator").post(null, "/workspaces/{ws}/webhook-deliveries/{id}/replay", PURCHASING, waiting)
                .status()).isEqualTo(422);
        assertThat(api("wade.admin").put(Map.of("name", "Ops feed", "url", Stub.baseUrl() + "/webhooks/paused",
                "secretRef", REF, "events", List.of("request.started"), "enabled", true, "expectedVersion", 2), null,
                "/workspaces/{ws}/webhook-subscriptions/{id}", PURCHASING, subscription).status()).isEqualTo(200);
        assertThat(received(waiting)).isZero();
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, workspace_id, subscription_id, event_key, event_type, payload, status,
                                              next_attempt_at, created_at)
                SELECT gen_random_uuid(), :ws, :s, 'cap:' || g, 'request.started', '{}'::jsonb, 'pending',
                       now() + interval '1 day', now()
                FROM generate_series(1, :n) g
                """).param("ws", PURCHASING).param("s", subscription).param("n", settings.maxPending()).update();
        try {
            Response capped = api("olga.operator").post(null, "/workspaces/{ws}/webhook-deliveries/{id}/replay", PURCHASING,
                    waiting);
            assertThat(capped.status()).isEqualTo(429);
        } finally {
            jdbc.sql("DELETE FROM webhook_delivery WHERE subscription_id = :s AND event_key LIKE 'cap:%'")
                    .param("s", subscription).update();
        }
        assertThat(api("olga.operator").post(null, "/workspaces/{ws}/webhook-deliveries/{id}/replay", PURCHASING, waiting)
                .status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- claims, fencing, budgets, scheduler

    @Test
    void twoDispatchersWithAHangingReceiverDeliverEveryRowExactlyOnce() throws Exception {
        UUID subscription = UUID.fromString(subscribe("/webhooks/slow", List.of("request.started")).path("id").asString());
        List<UUID> rows = List.of(delivery(subscription, 10), delivery(subscription, 9), delivery(subscription, 8),
                delivery(subscription, 7));
        Stub.webhookDelays(1200, 1200, 1200, 1200, 1200, 1200);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            WebhookDispatcher other = secondPod();
            for (int round = 0; round < 4 && rows.stream().anyMatch(r -> !"delivered".equals(status(r))); round++) {
                Future<Integer> a = pool.submit(dispatcher::run);
                Future<Integer> b = pool.submit(other::run);
                a.get(60, TimeUnit.SECONDS);
                b.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID row : rows) {
            assertThat(status(row)).as(row.toString()).isEqualTo("delivered");
            assertThat(received(row)).as(row.toString()).isEqualTo(1);
        }
    }

    @Test
    void aLateFinishOfAnExpiredClaimCannotOverwriteTheNewerClaim() throws Exception {
        UUID subscription = UUID.fromString(subscribe("/webhooks/fence", List.of("request.started")).path("id").asString());
        UUID row = delivery(subscription, 5);
        // First claim: answers 500 after 3 s. Second claim (after the first lease expired): 204 after 5 s.
        Stub.webhookStatuses(500, 204);
        Stub.webhookDelays(3000, 5000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(dispatcher::run);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> received(row) == 1);
            jdbc.sql("UPDATE webhook_delivery SET lease_until = now() - interval '1 second' WHERE id = :id").param("id", row).update();
            Future<Integer> second = pool.submit(secondPod()::run);
            first.get(30, TimeUnit.SECONDS);
            // The first pod's 500 arrived after the second pod claimed: it must not set the row back to pending.
            assertThat(status(row)).isEqualTo("sending");
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(row)).isEqualTo("delivered");
        assertThat(jdbc.sql("SELECT attempts FROM webhook_delivery WHERE id = :id").param("id", row).query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void aSlowSubscriptionUsesOnlyItsBudgetSoOthersAreServedInTheSamePass() throws Exception {
        UUID slow = UUID.fromString(subscribe("/webhooks/budget-slow", List.of("request.started")).path("id").asString());
        UUID fast = UUID.fromString(subscribe("/webhooks/budget-fast", List.of("request.started")).path("id").asString());
        // Eight slow rows: even with the scheduled dispatcher as a second pod, each pod rests the slow subscription after
        // about three sends, so at least two stay pending while the fast row is served.
        List<UUID> slowRows = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            slowRows.add(delivery(slow, 60 - i));
        }
        UUID fastRow = delivery(fast, 1);
        Stub.webhookDelays(2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000);
        dispatcher.run();
        assertThat(status(fastRow)).isEqualTo("delivered");
        assertThat(slowRows.stream().filter(r -> "pending".equals(status(r))).count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theDispatcherRunsOnItsOwnScheduler() throws Exception {
        Scheduled scheduled = WebhookDispatcher.class.getMethod("tick").getAnnotation(Scheduled.class);
        assertThat(scheduled.scheduler()).isEqualTo("webhookScheduler");
    }

    // ---------------------------------------------------------------- egress at send time

    @Test
    void theConnectionGoesToTheVettedAddressAndARebindingOrRedirectIsNeverFollowed() throws Exception {
        UUID subscription = UUID.fromString(subscribe("/webhooks/pinned", List.of("request.started")).path("id").asString());
        // localhost resolves once to the stub; any later lookup would point at a black-hole address.
        AtomicInteger lookups = new AtomicInteger();
        egress.setResolver(host -> "localhost".equals(host)
                ? new InetAddress[] {lookups.getAndIncrement() == 0 ? InetAddress.getByName("127.0.0.1")
                        : InetAddress.getByName("10.255.255.1")}
                : InetAddress.getAllByName(host));
        UUID pinned = delivery(subscription, 1);
        dispatcher.run();
        assertThat(status(pinned)).isEqualTo("delivered");
        assertThat(lookups.get()).isEqualTo(1);

        // A redirect to the metadata address is an ordinary failure: nothing follows it.
        egress.setResolver(null);
        Stub.webhookStatuses(302);
        int before = Stub.webhooks().size();
        UUID redirected = delivery(subscription, 1);
        dispatcher.run();
        assertThat(Stub.webhooks().size()).isEqualTo(before + 1);
        assertThat(jdbc.sql("SELECT last_status_code FROM webhook_delivery WHERE id = :id").param("id", redirected)
                .query(Integer.class).single()).isEqualTo(302);
        assertThat(status(redirected)).isEqualTo("pending");
    }

    @Test
    void aHostThatResolvesPublicAtSaveAndPrivateAtSendIsRefusedBeforeConnecting() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        Function<Integer, String> answers = n -> n == 0 ? "93.184.216.34" : "10.0.0.5";
        egress.setResolver(host -> "public.test.example".equals(host)
                ? new InetAddress[] {InetAddress.getByName(answers.apply(lookups.getAndIncrement()))}
                : InetAddress.getAllByName(host));
        Response created = api("wade.admin").post(Map.of("name", "Rebind", "url", "https://public.test.example/hook",
                "secretRef", REF, "events", List.of("request.started")), "/workspaces/{ws}/webhook-subscriptions", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID row = delivery(UUID.fromString(created.body().path("id").asString()), 1);
        dispatcher.run();
        assertThat(status(row)).isEqualTo("dead");
        assertThat(jdbc.sql("SELECT last_error FROM webhook_delivery WHERE id = :id").param("id", row).query(String.class)
                .single()).isEqualTo("egress_denied");

        // IPv4-mapped, NAT64 and 6to4 answers are private too, at save time.
        for (String address : List.of("::ffff:10.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1")) {
            egress.setResolver(host -> "public.test.example".equals(host)
                    ? new InetAddress[] {InetAddress.getByName(address)} : InetAddress.getAllByName(host));
            assertThat(api("wade.admin").post(Map.of("name", "V6", "url", "https://public.test.example/hook",
                    "secretRef", REF, "events", List.of("request.started")), "/workspaces/{ws}/webhook-subscriptions",
                    PURCHASING).status()).as(address).isEqualTo(422);
        }
        // A stored URL that gained userinfo is refused at send time as well.
        egress.setResolver(null);
        jdbc.sql("UPDATE webhook_subscription SET url = :u WHERE id = :id").param("u", "http://user:pw@localhost:" + Stub.port() + "/webhooks/x")
                .param("id", UUID.fromString(created.body().path("id").asString())).update();
        int before = Stub.webhooks().size();
        UUID userinfo = delivery(UUID.fromString(created.body().path("id").asString()), 1);
        dispatcher.run();
        assertThat(Stub.webhooks().size()).isEqualTo(before);
        assertThat(status(userinfo)).isEqualTo("dead");
    }
}
