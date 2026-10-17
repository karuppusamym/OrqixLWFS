package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.MessageSweeper;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Wait for a message" end to end (S16): publish the example, start requests, send messages through the public API
 * (validated against the OpenAPI contract) and watch the request, its timeline, the subscription rows and the audit log
 * on PostgreSQL and Flowable. The buffer, exactly-once delivery, the time limit, restart and concurrent senders.
 */
class MessageEventsTest extends RuntimeTestBase {

    @Autowired
    private org.flowable.engine.ProcessEngine processEngine;

    @Autowired
    private MessageSweeper sweeper;

    private UUID publishOrderWait() throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/order-payment-wait.json")));
        return publishPurchase(PURCHASING, d -> {
            example.properties().forEach(e -> {
                if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                    d.set(e.getKey(), e.getValue());
                }
            });
        });
    }

    private UUID startOrder(UUID workflow, String orderNumber) throws Exception {
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow,
                "input", Map.of("orderNumber", orderNumber, "customerName", "Acme Ltd")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private static String order() {
        return "SO-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Response send(String user, UUID workspace, String name, String key, Object payload, String idempotencyKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("correlationKey", key);
        if (payload != null) {
            body.put("payload", payload);
        }
        return api(user).post(body, idempotencyKey, "/workspaces/{ws}/messages", workspace);
    }

    private Response pay(String key, Object payload) throws Exception {
        return send("olga.operator", PURCHASING, "payment-received", key, payload, UUID.randomUUID().toString());
    }

    private static Map<String, Object> paid() {
        return Map.of("paymentReference", "PAY-77", "amountPaid", 125.50);
    }

    private List<String> eventTypes(UUID runId) {
        return jdbc.sql("SELECT type FROM run_event WHERE workspace_id = :ws AND run_id = :id ORDER BY seq")
                .param("ws", PURCHASING).param("id", runId).query(String.class).list();
    }

    private void awaitCompleted(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(300)).until(() -> {
            sweeper.run();
            return "completed".equals(lifecycle(PURCHASING, run));
        });
    }

    private String subscriptionStatus(UUID run) {
        return jdbc.sql("SELECT status FROM message_subscription WHERE run_id = :run").param("run", run)
                .query(String.class).single();
    }

    @Test
    void bufferedReplayRemainsTheOriginalAcceptanceAfterConsumptionAndExpiry() throws Exception {
        UUID wf=publishOrderWait();
        String key=order(), idem=UUID.randomUUID().toString();
        Response first=send("olga.operator",PURCHASING,"payment-received",key,paid(),idem);
        UUID run=startOrder(wf,key); awaitCompleted(run);
        Response replay=send("olga.operator",PURCHASING,"payment-received",key,paid(),idem);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(first.body());
        String expiredKey=order(), expiredIdem=UUID.randomUUID().toString();
        Response buffered=send("olga.operator",PURCHASING,"payment-received",expiredKey,paid(),expiredIdem);
        jdbc.sql("UPDATE inbound_message SET expires_at=now()-interval '1 minute' WHERE id=:id")
                .param("id",UUID.fromString(buffered.body().path("id").asString())).update();
        sweeper.run();
        assertThat(send("olga.operator",PURCHASING,"payment-received",expiredKey,paid(),expiredIdem).body()).isEqualTo(buffered.body());
    }

    @Test
    void concurrentBufferSendsCannotExceedThePerKeyQuota() throws Exception {
        String key=order();
        for(int i=0;i<9;i++) assertThat(pay(key,paid()).status()).isEqualTo(202);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs=List.of(() -> pay(key,paid()).status(),() -> pay(key,paid()).status());
            List<Integer> statuses=new ArrayList<>();
            for(Future<Integer> result:pool.invokeAll(jobs)) statuses.add(result.get());
            assertThat(statuses).containsExactlyInAnyOrder(202,429);
            assertThat(jdbc.sql("SELECT count(*) FROM inbound_message WHERE workspace_id=:ws AND name='payment-received' AND correlation_key=:key AND status='buffered'")
                    .param("ws",PURCHASING).param("key",key).query(Integer.class).single()).isEqualTo(10);
        } finally {
            pool.shutdownNow();
            jdbc.sql("DELETE FROM inbound_message WHERE workspace_id=:ws AND correlation_key=:key").param("ws",PURCHASING).param("key",key).update();
        }
    }

    @Test
    void aMessageThatArrivesAfterTheWaitStartsEndsTheWait() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);

        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).isEqualTo("waiting");
        JsonNode step = detail.path("currentSteps").get(0);
        assertThat(step.path("kind").asString()).isEqualTo("wait");
        assertThat(step.path("messageName").asString()).isEqualTo("payment-received");
        assertThat(step.path("dueAt").isString()).as("the two day limit is shown").isTrue();
        assertThat(subscriptionStatus(run)).isEqualTo("waiting");

        Response sent = pay(key, paid());
        assertThat(sent.status()).as(sent.toString()).isEqualTo(202);
        assertThat(sent.body().path("status").asString()).isEqualTo("delivered");
        assertThat(sent.body().path("matched").asInt()).isEqualTo(1);
        assertThat(sent.body().path("delivered").get(0).path("runId").asString()).isEqualTo(run.toString());

        awaitCompleted(run);
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("outcome").asString()).isEqualTo("Order paid");
        assertThat(done.path("output").path("paymentReference").asString()).isEqualTo("PAY-77");
        assertThat(eventTypes(run)).containsOnlyOnce("WAIT_STARTED").containsOnlyOnce("WAIT_ENDED")
                .containsOnlyOnce("RUN_COMPLETED");
        JsonNode events = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body();
        assertThat(events.path("items").toString()).contains("payment-received").doesNotContain(key)
                .doesNotContain("PAY-77");
        assertThat(subscriptionStatus(run)).isEqualTo("received");
        assertThat(jdbc.sql("SELECT status FROM inbound_message WHERE id = :id")
                .param("id", UUID.fromString(sent.body().path("id").asString())).query(String.class).single())
                .isEqualTo("delivered");
        assertThat(jdbc.sql("SELECT payload::text FROM inbound_message WHERE id = :id")
                .param("id", UUID.fromString(sent.body().path("id").asString())).query(String.class).single())
                .isEqualTo("{}");
    }

    @Test
    void theAuditRecordHoldsTheNameAndCountsOnly() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        startOrder(wf, key);
        Response sent = pay(key, paid());
        assertThat(sent.status()).isEqualTo(202);
        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'message.received'"
                + " AND resource_id = :id").param("id", sent.body().path("id").asString()).query(String.class).single();
        assertThat(json.parse(metadata).propertyNames()).containsExactlyInAnyOrder("name", "matched", "buffered");
        assertThat(metadata).doesNotContain(key).doesNotContain("PAY-77");
    }

    @Test
    void aMessageForAnotherKeyIsKeptAndDoesNotTouchTheRequest() throws Exception {
        UUID wf = publishOrderWait();
        UUID run = startOrder(wf, order());
        Response sent = pay("SO-OTHER-" + UUID.randomUUID(), paid());
        assertThat(sent.status()).isEqualTo(202);
        assertThat(sent.body().path("status").asString()).isEqualTo("buffered");
        assertThat(sent.body().path("matched").asInt()).isZero();
        assertThat(sent.body().path("bufferedUntil").isString()).isTrue();
        sweeper.run();
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(subscriptionStatus(run)).isEqualTo("waiting");
    }

    @Test
    void aMessageThatArrivesBeforeTheWaitIsKeptAndUsedWhenTheWaitStarts() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        Response early = pay(key, paid());
        assertThat(early.status()).isEqualTo(202);
        assertThat(early.body().path("status").asString()).isEqualTo("buffered");

        UUID run = startOrder(wf, key);
        awaitCompleted(run);
        assertThat(run(PURCHASING, run).path("output").path("paymentReference").asString()).isEqualTo("PAY-77");
        assertThat(jdbc.sql("SELECT status FROM inbound_message WHERE id = :id")
                .param("id", UUID.fromString(early.body().path("id").asString())).query(String.class).single())
                .isEqualTo("delivered");
        JsonNode events = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body();
        assertThat(events.path("items").toString()).contains("\"buffered\":true");
    }

    @Test
    void aBufferedMessageIsUsedOnceAndDropsAfterItsWindow() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        Response early = pay(key, paid());
        UUID first = startOrder(wf, key);
        awaitCompleted(first);
        // The second request with the same key finds nothing left to consume.
        UUID second = startOrder(wf, key);
        sweeper.run();
        assertThat(lifecycle(PURCHASING, second)).isEqualTo("waiting");
        // An expired message is not consumed either.
        String other = order();
        Response old = pay(other, paid());
        jdbc.sql("UPDATE inbound_message SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(old.body().path("id").asString())).update();
        UUID third = startOrder(wf, other);
        sweeper.run();
        assertThat(lifecycle(PURCHASING, third)).isEqualTo("waiting");
        assertThat(jdbc.sql("SELECT status FROM inbound_message WHERE id = :id")
                .param("id", UUID.fromString(old.body().path("id").asString())).query(String.class).single())
                .isEqualTo("expired");
        assertThat(early.status()).isEqualTo(202);
    }

    @Test
    void aBufferedMessageWithTheWrongPayloadIsDiscardedAndTheWaitGoesOn() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        // Buffered messages are only checked against the wait when it starts: a missing required value is discarded.
        Response early = pay(key, Map.of("amountPaid", 1));
        assertThat(early.status()).isEqualTo(202);
        UUID run = startOrder(wf, key);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            sweeper.run();
            return eventTypes(run).contains("MESSAGE_DISCARDED");
        });
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(jdbc.sql("SELECT status FROM inbound_message WHERE id = :id")
                .param("id", UUID.fromString(early.body().path("id").asString())).query(String.class).single())
                .isEqualTo("discarded");
        // A good message still ends the wait.
        assertThat(pay(key, paid()).status()).isEqualTo(202);
        awaitCompleted(run);
    }

    @Test
    void theSameIdempotencyKeyDeliversOnceAndReplays() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        String idem = "msg-" + UUID.randomUUID();
        Response first = send("olga.operator", PURCHASING, "payment-received", key, paid(), idem);
        Response replay = send("olga.operator", PURCHASING, "payment-received", key, paid(), idem);
        assertThat(first.status()).isEqualTo(202);
        assertThat(replay.status()).isEqualTo(202);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("id").asString()).isEqualTo(first.body().path("id").asString());
        assertThat(replay.body().path("matched").asInt()).isEqualTo(1);
        // The same key with another body is refused.
        Response reused = send("olga.operator", PURCHASING, "payment-received", key, Map.of("paymentReference", "X"), idem);
        assertThat(reused.status()).isEqualTo(409);
        awaitCompleted(run);
        assertThat(eventTypes(run)).containsOnlyOnce("WAIT_ENDED");
        // A second message with a new key finds the wait closed: it is kept, not delivered again.
        Response late = pay(key, paid());
        assertThat(late.body().path("status").asString()).isEqualTo("buffered");
        assertThat(eventTypes(run)).containsOnlyOnce("WAIT_ENDED").containsOnlyOnce("RUN_COMPLETED");
    }

    @Test
    void theTimeLimitSendsTheRequestDownTheTimedOutPath() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        awaitCompletedByTimer(run);
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("outcome").asString()).isEqualTo("Cancelled no payment");
        assertThat(done.path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(subscriptionStatus(run)).isEqualTo("timed_out");
        assertThat(eventTypes(run)).containsOnlyOnce("WAIT_STARTED").containsOnlyOnce("WAIT_ENDED");
        JsonNode events = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body();
        assertThat(events.path("items").toString()).contains("\"outcome\":\"timedOut\"");
        // A message after the limit is not delivered; it is kept like any message nobody waits for.
        assertThat(pay(key, paid()).body().path("status").asString()).isEqualTo("buffered");
    }

    private void awaitCompletedByTimer(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).until(() -> {
            fastForward(PURCHASING, run);
            return "completed".equals(lifecycle(PURCHASING, run));
        });
    }

    @Test
    void thePayloadIsCheckedAgainstTheDeclaredValues() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        Response missing = pay(key, Map.of("amountPaid", 3));
        assertThat(missing.status()).as(missing.toString()).isEqualTo(422);
        assertThat(missing.body().toString()).contains("/payload/paymentReference").contains("required");
        Response wrongType = pay(key, Map.of("paymentReference", "P", "amountPaid", "lots"));
        assertThat(wrongType.status()).isEqualTo(422);
        assertThat(wrongType.body().toString()).contains("/payload/amountPaid");
        Response unknown = pay(key, Map.of("paymentReference", "P", "surprise", true));
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.body().toString()).contains("/payload/surprise");
        Response nested = pay(key, Map.of("paymentReference", "P", "extra", Map.of("a", 1)));
        assertThat(nested.status()).isEqualTo(422);
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            many.put("v" + i, i);
        }
        assertThat(pay(key, many).status()).isEqualTo(422);
        // Nothing changed: the request still waits, and nothing was kept.
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_message WHERE correlation_key = :k").param("k", key)
                .query(Integer.class).single()).isZero();
        assertThat(pay(key, paid()).status()).isEqualTo(202);
    }

    @Test
    void aLargePayloadIsRefused() throws Exception {
        Map<String, Object> big = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            big.put("v" + i, "x".repeat(990));
        }
        Response r = pay(order(), big);
        assertThat(r.status()).as(r.toString()).isEqualTo(202);
        Map<String, Object> bigger = new LinkedHashMap<>();
        bigger.put("text", "y".repeat(2000));
        Response tooLong = pay(order(), bigger);
        assertThat(tooLong.status()).isEqualTo(422);
        Response badName = send("olga.operator", PURCHASING, "Bad Name", order(), null, UUID.randomUUID().toString());
        assertThat(badName.status()).isIn(400, 422);
    }

    @Test
    void permissionAndWorkspaceBoundariesHold() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        // A member without message.send: 403.
        Response denied = send("rhea.requester", PURCHASING, "payment-received", key, paid(), UUID.randomUUID().toString());
        assertThat(denied.status()).as(denied.toString()).isEqualTo(403);
        // A person of another workspace cannot see this one: 404, and the wait is untouched.
        Response foreign = send("fred.facilities", PURCHASING, "payment-received", key, paid(), UUID.randomUUID().toString());
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_message WHERE correlation_key = :k").param("k", key)
                .query(Integer.class).single()).isZero();
        // The key is missing: 400.
        Response noKey = send("olga.operator", PURCHASING, "payment-received", key, paid(), null);
        assertThat(noKey.status()).isEqualTo(400);
    }

    @Test
    void aMessageInAnotherWorkspaceNeverReachesThisWait() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        // The same name and key sent in the Facilities workspace is only buffered there.
        Response other = send("wade.admin", FACILITIES, "payment-received", key, paid(), UUID.randomUUID().toString());
        // wade.admin may lack message.send there; either way nothing reaches PURCHASING.
        assertThat(other.status()).isIn(202, 403, 404);
        sweeper.run();
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(subscriptionStatus(run)).isEqualTo("waiting");
    }

    @Test
    void aCancelledRequestCannotReceiveAMessage() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        int version = run(PURCHASING, run).path("version").asInt();
        Response cancelled = api("olga.operator").post(Map.of("reason", "Customer withdrew", "expectedVersion", version),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        Response sent = pay(key, paid());
        assertThat(sent.body().path("status").asString()).isEqualTo("buffered");
        sweeper.run();
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("cancelled");
        assertThat(subscriptionStatus(run)).isEqualTo("cancelled");
    }

    @Test
    void theWaitSurvivesAnExecutorRestart() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().shutdown();
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().start();
        Thread.sleep(1500);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(pay(key, paid()).status()).isEqualTo(202);
        awaitCompleted(run);
        assertThat(eventTypes(run)).containsOnlyOnce("WAIT_ENDED");
    }

    @Test
    void manySendersAtOnceDeliverExactlyOnce() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID run = startOrder(wf, key);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            List<String> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 8; i++) {
                calls.add(() -> {
                    Response r = pay(key, paid());
                    statuses.add(r.status() + ":" + r.body().path("status").asString());
                    return r.body().path("matched").asInt();
                });
            }
            int matched = 0;
            for (Future<Integer> f : pool.invokeAll(calls)) {
                matched += f.get();
            }
            assertThat(matched).as(statuses.toString()).isEqualTo(1);
            assertThat(statuses).allMatch(s -> s.startsWith("202:") || s.startsWith("409:"));
        } finally {
            pool.shutdownNow();
        }
        awaitCompleted(run);
        assertThat(eventTypes(run)).containsOnlyOnce("WAIT_ENDED").containsOnlyOnce("RUN_COMPLETED");
    }

    @Test
    void severalRequestsWithTheSameKeyAllReceiveTheMessage() throws Exception {
        UUID wf = publishOrderWait();
        String key = order();
        UUID a = startOrder(wf, key);
        UUID b = startOrder(wf, key);
        Response sent = pay(key, paid());
        assertThat(sent.body().path("matched").asInt()).isEqualTo(2);
        awaitCompleted(a);
        awaitCompleted(b);
    }
}
