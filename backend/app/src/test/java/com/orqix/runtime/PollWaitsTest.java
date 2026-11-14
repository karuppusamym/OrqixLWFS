package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.PollWaits;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;
import com.orqix.support.TrackingStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Keep checking until..." (CG-W01), the safety net of a message wait (CG-W03) and the "Waiting for" API (CG-W08),
 * docs/build/S129, on PostgreSQL and Flowable with a WireMock status endpoint. Time is moved by making rows due in the
 * database; checks run through {@link PollWaits#runDue()} (the scheduler body) as well as the live scheduler, which
 * the assertions tolerate: every claim is fenced, so a check is never made twice.
 */
class PollWaitsTest extends RuntimeTestBase {

    @Autowired
    PollWaits polls;

    @BeforeEach
    void connection() throws Exception {
        JsonNode definition = json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                "examples", "connections", "order-status.connection.json")));
        var registered = api("wade.admin").post(Map.of("definition", definition),
                "/workspaces/{ws}/connections/{key}/versions", PURCHASING, "order-status");
        assertThat(registered.status()).as(registered.toString()).isIn(201, 409);
        boolean exists = jdbc.sql("SELECT count(*) FROM connection_binding WHERE workspace_id = :ws AND connection_key = 'order-status'")
                .param("ws", PURCHASING).query(Long.class).single() > 0;
        if (!exists) {
            var bound = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/tracking", "auth", Map.of("type", "none"),
                    "enabled", true), null, "/workspaces/{ws}/connections/{key}/binding", PURCHASING, "order-status");
            assertThat(bound.status()).as(bound.toString()).isEqualTo(200);
        }
        // Later tests (or one that disabled it) get the same, enabled binding back.
        jdbc.sql("UPDATE connection_binding SET base_url = :url, enabled = true WHERE workspace_id = :ws AND connection_key = 'order-status'")
                .param("url", Stub.baseUrl() + "/tracking").param("ws", PURCHASING).update();
    }

    // ------------------------------------------------------------------ workflows

    /** procure -> track (keeps checking order-status) -> notify; failed and timedOut -> notifyRejected. */
    private UUID polling(String pollJson) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ((ArrayNode) d.path("dependencies").path("connections"))
                    .add(json.parse("{\"key\":\"order-status\",\"definitionVersion\":1}"));
            ((ArrayNode) d.path("nodes")).add(json.parse("""
                    {"id":"track","type":"http","label":"Track the order","config":{"connectionRef":"order-status",
                     "operation":"getStatus","inputMapping":{"orderId":"/steps/procure/procurementId"},
                     "resultMapping":{"state":"/state","trackingRef":"/trackingRef"},"timeoutSeconds":3,"poll":%s}}"""
                    .formatted(pollJson)));
            for (JsonNode t : d.path("transitions")) {
                if ("procure".equals(t.path("from").asString())) {
                    ((ObjectNode) t).put("to", "track");
                }
            }
            ArrayNode transitions = (ArrayNode) d.path("transitions");
            transitions.add(json.parse("{\"id\":\"p1\",\"from\":\"track\",\"to\":\"notify\",\"on\":\"next\"}"));
            transitions.add(json.parse("{\"id\":\"p2\",\"from\":\"track\",\"to\":\"notifyRejected\",\"on\":\"failed\"}"));
            transitions.add(json.parse("{\"id\":\"p3\",\"from\":\"track\",\"to\":\"notifyRejected\",\"on\":\"timedOut\"}"));
            ((ObjectNode) d.path("outputSchema").path("properties")).set("trackingRef",
                    json.parse("{\"type\":\"string\",\"title\":\"Tracking reference\"}"));
            ((ObjectNode) d.path("outputMapping")).set("trackingRef",
                    json.parse("{\"fieldRef\":\"/steps/track/trackingRef\",\"required\":false}"));
            d.remove("uiLayout");
        });
    }

    private static String poll(String extra) {
        return """
                {"every":"PT10S","jitterPercent":0,"giveUpAfter":"PT1H",
                 "until":{"fieldRef":"/steps/track/state","operator":"eq","value":"shipped"},
                 "failWhen":{"fieldRef":"/steps/track/state","operator":"eq","value":"cancelled"},
                 "showWhileWaiting":["state"]%s}""".formatted(extra);
    }

    private UUID startAndApprove(UUID workflow) throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> row(run) != null);
        return run;
    }

    private record Poll(UUID id, String state, int checks, String endReason, boolean finalDone) {
    }

    private Poll row(UUID run) {
        return jdbc.sql("SELECT id, state, checks_done, end_reason, final_check_done FROM poll_wait WHERE run_id = :run")
                .param("run", run).query((rs, n) -> new Poll(rs.getObject("id", UUID.class), rs.getString("state"),
                        rs.getInt("checks_done"), rs.getString("end_reason"), rs.getBoolean("final_check_done")))
                .optional().orElse(null);
    }

    /** Waits until the row has made this many checks (the first check is immediate). */
    private void awaitChecks(UUID run, int checks) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            polls.runDue();
            Poll p = row(run);
            return p != null && (p.checks() >= checks || !List.of("waiting", "checking").contains(p.state()));
        });
    }

    /** Makes the next check due now, as if its interval had passed. */
    private void due(UUID run) {
        jdbc.sql("UPDATE poll_wait SET next_check_at = now() - interval '1 second' WHERE run_id = :run AND state = 'waiting'")
                .param("run", run).update();
    }

    /** Leftover waits of this class must not keep calling while other test classes snapshot row counts. */
    @org.junit.jupiter.api.AfterEach
    void stopLeftoverChecks() {
        jdbc.sql("UPDATE poll_wait SET state = 'cancelled', end_reason = 'testEnded', next_check_at = NULL, lease_owner = NULL,"
                + " lease_until = NULL WHERE state IN ('waiting', 'checking', 'attention')").update();
        jdbc.sql("DELETE FROM connection_circuit WHERE connection_key = 'order-status'").update();
    }

    // ------------------------------------------------------------------ follow-ups after the Wave A merge

    @Autowired
    com.orqix.runtime.internal.RunRepair repair;

    @Autowired
    com.orqix.runtime.internal.RunStore runStore;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    private com.orqix.runtime.api.RuntimeDtos.RunDiagnosis diagnose(UUID run) {
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
                repair.diagnose(runStore.find(PURCHASING, run).orElseThrow()).diagnosis());
    }

    private long secondsToNextCheck(UUID run) {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM (next_check_at - now()))::bigint FROM poll_wait WHERE run_id = :run")
                .param("run", run).query(Long.class).single();
    }

    @Test
    void aCheckDeferredByAnOpenCircuitWaitsWithoutCountingACheckOrAnError() throws Exception {
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        int calls = TrackingStub.calls();
        jdbc.sql("""
                INSERT INTO connection_circuit (workspace_id, connection_key, state, consecutive_failures, opened_at,
                                                open_until, reason, updated_at)
                VALUES (:ws, 'order-status', 'open', 10, now(), now() + interval '10 minutes', 'test', now())
                ON CONFLICT (workspace_id, connection_key) DO UPDATE SET state = 'open',
                    open_until = now() + interval '10 minutes'
                """).param("ws", PURCHASING).update();
        due(run);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
            polls.runDue();
            return events(run, "POLL_CHECK_DEFERRED") == 1;
        });
        assertThat(TrackingStub.calls()).isEqualTo(calls);
        Poll p = row(run);
        assertThat(p.checks()).isEqualTo(1);
        assertThat(p.state()).isEqualTo("waiting");
        assertThat(jdbc.sql("SELECT consecutive_errors FROM poll_wait WHERE run_id = :run").param("run", run)
                .query(Integer.class).single()).isZero();
        // The next check is at the end of the cool-down (about 10 minutes), not at the 10-second interval.
        assertThat(secondsToNextCheck(run)).isBetween(560L, 610L);
        // A deferral past the deadline gives up instead of spinning.
        jdbc.sql("UPDATE poll_wait SET started_at = now() - interval '2 hours', authored_deadline_at = now() - interval '1 second',"
                + " deadline_at = now() - interval '1 second', next_check_at = now() - interval '1 second' WHERE run_id = :run")
                .param("run", run).update();
        awaitEnded(run);
        assertThat(row(run).state()).isEqualTo("timedOut");
        assertThat(TrackingStub.calls()).isEqualTo(calls);
    }

    @Test
    void retryAfterOfTheServerSetsTheNextCheckCappedAtTheLongestInterval() throws Exception {
        TrackingStub.states("pending");
        TrackingStub.failWithRetryAfter(503, 120);
        UUID run = startAndApprove(polling(poll(",\"backoff\":{\"multiplier\":1,\"maxInterval\":\"PT10M\"}")));
        awaitChecks(run, 1);
        due(run);
        awaitChecks(run, 2);
        assertThat(secondsToNextCheck(run)).isBetween(100L, 125L);
        TrackingStub.failWithRetryAfter(429, 7200);
        due(run);
        awaitChecks(run, 3);
        // Two hours asked, but never later than the longest interval (10 minutes).
        assertThat(secondsToNextCheck(run)).isBetween(580L, 605L);
    }

    @Test
    void anOptionalResultMissingInPendingAnswersDoesNotMakeThemUnusable() throws Exception {
        TrackingStub.raw("{\"state\":\"pending\"}");
        TrackingStub.raw("{\"state\":\"shipped\",\"trackingRef\":\"TRK-9\"}");
        UUID run = startAndApprove(polling(poll(",\"optionalResults\":[\"trackingRef\"]")));
        awaitChecks(run, 1);
        assertThat(row(run).state()).isEqualTo("waiting");
        due(run);
        awaitChecks(run, 2);
        awaitEnded(run);
        assertThat(run(PURCHASING, run).path("output").path("trackingRef").asString()).isEqualTo("TRK-9");
        assertThat(row(run).state()).isEqualTo("met");
    }

    @Test
    void endedChecksArePurgedAfterTheRetentionWithAnAuditedCount() throws Exception {
        TrackingStub.states("shipped");
        UUID run = startAndApprove(polling(poll("")));
        awaitEnded(run);
        UUID id = row(run).id();
        assertThat(polls.purge(java.time.Instant.now(), Duration.ofDays(30))).isZero();
        jdbc.sql("UPDATE poll_wait SET ended_at = now() - interval '31 days' WHERE id = :id").param("id", id).update();
        assertThat(polls.purge(java.time.Instant.now(), Duration.ofDays(30))).isGreaterThanOrEqualTo(1);
        assertThat(row(run)).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'poll.waits-purged' AND workspace_id = :ws")
                .param("ws", PURCHASING).query(Long.class).single()).isPositive();
    }

    @Test
    void theRepairScanUnderstandsChecksAndRebuildsTheWaitingForList() throws Exception {
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        assertThat(diagnose(run).state()).isEqualTo("inSync");
        // The derived list lost its row: reported as repairable drift; repair derives it again.
        jdbc.sql("DELETE FROM wait_state WHERE run_id = :run").param("run", run).update();
        var drift = diagnose(run);
        assertThat(drift.state()).isEqualTo("projectionDrift");
        assertThat(drift.repairable()).isTrue();
        int version = run(PURCHASING, run).path("version").asInt();
        var repaired = api("olga.operator").post(Map.of("reason", "Rebuild the waiting list", "expectedVersion", version),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/repair", PURCHASING, run);
        assertThat(repaired.status()).as(repaired.toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM wait_state WHERE run_id = :run").param("run", run)
                .query(Long.class).single()).isEqualTo(1);
        // A step that shows checks, but no check is scheduled: reported, never rebuilt.
        jdbc.sql("UPDATE poll_wait SET state = 'cancelled', next_check_at = NULL WHERE run_id = :run").param("run", run).update();
        var orphan = diagnose(run);
        assertThat(orphan.state()).isEqualTo("unsupported");
        assertThat(orphan.message()).contains("no check is scheduled");
    }

    @Test
    void aRequestWaitingInAPollingStepMovesOnlyWithIdenticalCheckSettings() throws Exception {
        JsonNode source = json.parse("""
                {"nodes":[{"id":"start","type":"start","label":"Start","config":{}},
                  {"id":"track","type":"http","label":"Track","config":{"connectionRef":"order-status","operation":"getStatus",
                   "inputMapping":{},"resultMapping":{"state":"/state"},"timeoutSeconds":5,
                   "poll":{"every":"PT10S","giveUpAfter":"PT1H","until":{"fieldRef":"/steps/track/state","operator":"eq","value":"shipped"}}}},
                  {"id":"end","type":"end","label":"End","config":{"businessOutcome":"Done","outcomeKind":"success"}}],
                 "transitions":[{"id":"a","from":"start","to":"track","on":"next"},{"id":"b","from":"track","to":"end","on":"next"},
                  {"id":"c","from":"track","to":"end","on":"timedOut"}],
                 "dependencies":{"connections":[{"key":"order-status","definitionVersion":1}]}}""");
        assertThat(com.orqix.runtime.internal.ReleaseCompatibility.check(source, source, List.of("track"))).isEmpty();
        ObjectNode changed = (ObjectNode) source.deepCopy();
        ((ObjectNode) changed.path("nodes").get(1).path("config").path("poll")).put("every", "PT1M");
        assertThat(com.orqix.runtime.internal.ReleaseCompatibility.check(source, changed, List.of("track")))
                .extracting(com.orqix.runtime.internal.ReleaseCompatibility.Finding::code).containsExactly("POLL_CHANGED");
        ObjectNode repinned = (ObjectNode) source.deepCopy();
        ((ObjectNode) repinned.path("dependencies").path("connections").get(0)).put("definitionVersion", 2);
        assertThat(com.orqix.runtime.internal.ReleaseCompatibility.check(source, repinned, List.of("track")))
                .extracting(com.orqix.runtime.internal.ReleaseCompatibility.Finding::code).containsExactly("POLL_CHANGED");
    }

    @Test
    void insightsReportTimeSpentWaitingOnOutsideSystems() throws Exception {
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        JsonNode body = api("olga.operator").get("/workspaces/{ws}/insights/outside-waits", PURCHASING).body();
        JsonNode item = null;
        for (JsonNode i : body.path("items")) {
            if ("poll".equals(i.path("kind").asString()) && "order-status".equals(i.path("source").asString())) {
                item = i;
            }
        }
        assertThat(item).as(body.toString()).isNotNull();
        assertThat(item.path("openNow").asInt()).isPositive();
        assertThat(body.path("totalOpenSeconds").asLong()).isNotNegative();
    }

    private long events(UUID run, String type) {
        return jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = :type").param("run", run)
                .param("type", type).query(Long.class).single();
    }

    private void awaitEnded(UUID run) {
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention", "cancelled");
    }

    // ------------------------------------------------------------------ CG-W01

    @Test
    void pendingThreeTimesThenDoneContinuesWithTheMappedOutputs() throws Exception {
        TrackingStub.states("pending", "pending", "pending", "shipped");
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        // While waiting: the uniform projection and the panel API.
        JsonNode waits = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).body();
        JsonNode wait = waits.path("items").get(0);
        assertThat(wait.path("kind").asString()).isEqualTo("poll");
        assertThat(wait.path("connectionKey").asString()).isEqualTo("order-status");
        assertThat(wait.path("subject").asString()).contains("Order tracking", "Track the order", "state: pending");
        assertThat(wait.path("check").path("checksDone").asInt()).isEqualTo(1);
        assertThat(wait.path("check").path("canOperate").asBoolean()).isFalse();
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).body()
                .path("items").get(0).path("check").path("canOperate").asBoolean()).isTrue();
        JsonNode search = api("olga.operator").get("/workspaces/{ws}/waits?connectionKey=order-status&kind=poll",
                PURCHASING).body();
        assertThat(search.path("items").findValuesAsString("runId")).contains(run.toString());
        for (int i = 2; i <= 4; i++) {
            due(run);
            awaitChecks(run, i);
        }
        awaitEnded(run);
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(done.path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(done.path("output").path("trackingRef").asString()).isEqualTo("TRK-1");
        assertThat(TrackingStub.calls()).isEqualTo(4);
        assertThat(TrackingStub.received().get(0).body()).contains("orderId");
        Poll p = row(run);
        assertThat(p.state()).isEqualTo("met");
        assertThat(p.checks()).isEqualTo(4);
        // One effect journal entry per check, read-only, each made once.
        assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :run AND node_id = 'track' AND attempts = 1"
                + " AND effect_class = 'readOnly'").param("run", run).query(Long.class).single()).isEqualTo(4);
        // Timeline noise control: a start, one change per shown-value change, an end; unchanged checks write nothing.
        assertThat(events(run, "POLL_STARTED")).isEqualTo(1);
        assertThat(events(run, "POLL_VALUE_CHANGED")).isEqualTo(2);
        assertThat(events(run, "POLL_ENDED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT request::text FROM poll_wait WHERE run_id = :run").param("run", run)
                .query(String.class).single()).isEqualTo("{}");
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).body()
                .path("items").size()).isZero();
    }

    @Test
    void theDeadlineGetsOneFinalCheckThenTheTimedOutRoute() throws Exception {
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        // The deadline has passed (as after a long wait): one final check, then timedOut.
        jdbc.sql("UPDATE poll_wait SET started_at = now() - interval '1 hour', authored_deadline_at = now() - interval '1 second',"
                + " deadline_at = now() - interval '1 second', next_check_at = now() - interval '1 second' WHERE run_id = :run")
                .param("run", run).update();
        awaitChecks(run, 2);
        awaitEnded(run);
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Rejected");
        Poll p = row(run);
        assertThat(p.state()).isEqualTo("timedOut");
        assertThat(p.endReason()).isEqualTo("deadline");
        assertThat(p.finalDone()).isTrue();
        assertThat(TrackingStub.calls()).isEqualTo(2);
    }

    @Test
    void failWhenTakesTheFailedRoute() throws Exception {
        TrackingStub.states("pending", "cancelled");
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        due(run);
        awaitChecks(run, 2);
        awaitEnded(run);
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Rejected");
        assertThat(row(run).state()).isEqualTo("failed");
        assertThat(TrackingStub.calls()).isEqualTo(2);
    }

    @Test
    void consecutiveCheckErrorsNeedAttentionAndCheckNowResumes() throws Exception {
        TrackingStub.fail(503, 2);
        UUID run = startAndApprove(polling(poll(",\"onCheckError\":{\"maxConsecutive\":2}")));
        awaitChecks(run, 1);
        due(run);
        awaitLifecycle(PURCHASING, run, "needsAttention");
        assertThat(run(PURCHASING, run).path("attention").path("reason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        Poll p = row(run);
        assertThat(p.state()).isEqualTo("attention");
        assertThat(events(run, "POLL_CHECK_FAILED")).isEqualTo(2);
        // The requester can see the wait but not operate it; the operator needs a reason.
        String path = "/workspaces/{ws}/runs/{run}/waits/{wait}/check-now";
        assertThat(api("rhea.requester").post(Map.of("reason", "x"), UUID.randomUUID().toString(), path, PURCHASING, run,
                p.id()).status()).isEqualTo(403);
        assertThat(api("olga.operator").post(Map.of("reason", " "), UUID.randomUUID().toString(), path, PURCHASING, run,
                p.id()).status()).isEqualTo(422);
        TrackingStub.states("shipped");
        var checked = api("olga.operator").post(Map.of("reason", "Vendor API is back"), UUID.randomUUID().toString(), path,
                PURCHASING, run, p.id());
        assertThat(checked.status()).as(checked.toString()).isEqualTo(200);
        // At most once a minute.
        assertThat(api("olga.operator").post(Map.of("reason", "Again"), UUID.randomUUID().toString(), path, PURCHASING,
                run, p.id()).status()).isEqualTo(429);
        awaitChecks(run, 3);
        awaitEnded(run);
        assertThat(run(PURCHASING, run).path("output").path("trackingRef").asString()).isEqualTo("TRK-1");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'poll.checked-now' AND resource_id = :id")
                .param("id", p.id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void afterDowntimeADueWaitGetsExactlyOneCatchUpCheck() throws Exception {
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        int before = TrackingStub.calls();
        // An outage: the next check is an hour overdue (360 intervals missed).
        jdbc.sql("UPDATE poll_wait SET next_check_at = now() - interval '1 hour' WHERE run_id = :run").param("run", run).update();
        awaitChecks(run, 2);
        for (int i = 0; i < 5; i++) {
            polls.runDue();
        }
        assertThat(TrackingStub.calls()).isEqualTo(before + 1);
        assertThat(row(run).checks()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT next_check_at > now() FROM poll_wait WHERE run_id = :run").param("run", run)
                .query(Boolean.class).single()).isTrue();
        // A pod that died while checking: its lease runs out and the check is made once by another pod.
        jdbc.sql("UPDATE poll_wait SET state = 'checking', lease_owner = 'dead-pod', lease_until = now() - interval '1 second',"
                + " next_check_at = now() - interval '1 minute' WHERE run_id = :run").param("run", run).update();
        awaitChecks(run, 3);
        assertThat(TrackingStub.calls()).isEqualTo(before + 2);
        assertThat(row(run).state()).isEqualTo("waiting");
    }

    @Test
    void twoSchedulersNeverCheckTheSameRowAtOnce() throws Exception {
        UUID workflow = polling(poll(""));
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            runs.add(startAndApprove(workflow));
        }
        for (UUID run : runs) {
            awaitChecks(run, 1);
        }
        int before = TrackingStub.calls();
        for (UUID run : runs) {
            due(run);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return polls.runDue();
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        for (UUID run : runs) {
            awaitChecks(run, 2);
        }
        assertThat(TrackingStub.calls()).isEqualTo(before + runs.size());
        for (UUID run : runs) {
            assertThat(row(run).checks()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT max(attempts) FROM effect_journal WHERE run_id = :run AND node_id = 'track'")
                    .param("run", run).query(Integer.class).single()).isEqualTo(1);
        }
    }

    @Test
    void operatorsExtendTheDeadlineAndStopWaiting() throws Exception {
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        Poll p = row(run);
        String extend = "/workspaces/{ws}/runs/{run}/waits/{wait}/extend-deadline";
        // Up to twice the authored hour: 1 more hour is allowed, 2 more are not.
        assertThat(api("olga.operator").post(Map.of("reason", "Vendor asked", "extendBy", "PT2H"),
                UUID.randomUUID().toString(), extend, PURCHASING, run, p.id()).status()).isEqualTo(422);
        String key = UUID.randomUUID().toString();
        var extended = api("olga.operator").post(Map.of("reason", "Vendor asked", "extendBy", "PT1H"), key, extend,
                PURCHASING, run, p.id());
        assertThat(extended.status()).as(extended.toString()).isEqualTo(200);
        assertThat(extended.body().path("deadlineExtensions").asInt()).isEqualTo(1);
        // The same request again is a replay, not a second extension.
        var replay = api("olga.operator").post(Map.of("reason", "Vendor asked", "extendBy", "PT1H"), key, extend,
                PURCHASING, run, p.id());
        assertThat(replay.body().path("deadlineExtensions").asInt()).isEqualTo(1);
        var stopped = api("olga.operator").post(Map.of("reason", "Handled by phone"), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{run}/waits/{wait}/stop", PURCHASING, run, p.id());
        assertThat(stopped.status()).as(stopped.toString()).isEqualTo(200);
        awaitEnded(run);
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Rejected");
        assertThat(row(run).endReason()).isEqualTo("stoppedByOperator");
        assertThat(events(run, "POLL_STOPPED")).isEqualTo(1);
    }

    @Test
    void cancellingTheRequestClosesItsChecksInTheSameTransaction() throws Exception {
        UUID run = startAndApprove(polling(poll("")));
        awaitChecks(run, 1);
        int version = run(PURCHASING, run).path("version").asInt();
        var cancelled = api("olga.operator").post(Map.of("reason", "No longer needed", "expectedVersion", version),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(row(run).state()).isEqualTo("cancelled");
        int calls = TrackingStub.calls();
        due(run);
        polls.runDue();
        assertThat(TrackingStub.calls()).isEqualTo(calls);
        assertThat(jdbc.sql("SELECT count(*) FROM wait_state WHERE run_id = :run").param("run", run)
                .query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ CG-W08 for other kinds

    @Test
    void anApprovalIsListedAsWhatTheRequestWaitsFor() throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, polling(poll("")), BigDecimal.TEN)
                .path("id").asString());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> api("rhea.requester")
                .get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).body().path("items").size() == 1);
        JsonNode wait = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).body()
                .path("items").get(0);
        assertThat(wait.path("kind").asString()).isEqualTo("approval");
        assertThat(wait.path("subject").asString()).startsWith("A decision on");
        assertThat(wait.path("check").isNull()).isTrue();
        // Another workspace's people see nothing.
        assertThat(api("otto.outsider").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ CG-W03

    private UUID safetyNet() throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ((ArrayNode) d.path("dependencies").path("connections"))
                    .add(json.parse("{\"key\":\"order-status\",\"definitionVersion\":1}"));
            ((ArrayNode) d.path("nodes")).add(json.parse("""
                    {"id":"shipped","type":"waitForMessage","label":"Wait for shipping","config":{"message":"order-shipped",
                     "correlationField":"/steps/procure/procurementId","timeoutAfter":"P5D",
                     "payload":[{"name":"state","type":"string","required":true}],
                     "fallbackCheck":{"connectionRef":"order-status","operation":"getStatus",
                      "inputMapping":{"orderId":"/steps/procure/procurementId"},"resultMapping":{"state":"/state"},
                      "timeoutSeconds":3,"every":"PT10M","jitterPercent":0,
                      "until":{"fieldRef":"/steps/shipped/state","operator":"eq","value":"shipped"}}}}"""));
            for (JsonNode t : d.path("transitions")) {
                if ("procure".equals(t.path("from").asString())) {
                    ((ObjectNode) t).put("to", "shipped");
                }
            }
            ArrayNode transitions = (ArrayNode) d.path("transitions");
            transitions.add(json.parse("{\"id\":\"m1\",\"from\":\"shipped\",\"to\":\"notify\",\"on\":\"next\"}"));
            transitions.add(json.parse("{\"id\":\"m2\",\"from\":\"shipped\",\"to\":\"notifyRejected\",\"on\":\"timedOut\"}"));
            d.remove("uiLayout");
        });
    }

    private String orderId(UUID run) throws Exception {
        return run(PURCHASING, run).path("output").path("procurementId").asString(
                jdbc.sql("SELECT correlation_key FROM message_subscription WHERE run_id = :run").param("run", run)
                        .query(String.class).single());
    }

    @Test
    void theSafetyNetCheckEndsTheWaitWhenTheMessageIsLostAndALateMessageNeverResumesTwice() throws Exception {
        UUID run = startAndApprove(safetyNet());
        Poll p = row(run);
        assertThat(p.state()).isEqualTo("waiting");
        assertThat(TrackingStub.calls()).isZero(); // the first safety-net check waits one interval
        JsonNode wait = api("olga.operator").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).body()
                .path("items").get(0);
        assertThat(wait.path("kind").asString()).isEqualTo("message");
        assertThat(wait.path("subject").asString()).contains("safety-net check");
        assertThat(wait.path("check").path("kind").asString()).isEqualTo("fallback");
        TrackingStub.states("pending", "shipped");
        String order = orderId(run);
        due(run);
        awaitChecks(run, 1);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        due(run);
        awaitChecks(run, 2);
        awaitEnded(run);
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(row(run).state()).isEqualTo("met");
        // The webhook arrives late: it is kept (buffered), never delivered to the ended wait.
        var late = api("olga.operator").post(Map.of("name", "order-shipped", "correlationKey", order,
                "payload", Map.of("state", "shipped")), UUID.randomUUID().toString(), "/workspaces/{ws}/messages", PURCHASING);
        assertThat(late.status()).as(late.toString()).isLessThan(300);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = 'WAIT_ENDED'").param("run", run)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aMessageThatArrivesFirstClosesTheSafetyNetInTheSameTransaction() throws Exception {
        UUID run = startAndApprove(safetyNet());
        String order = orderId(run);
        var sent = api("olga.operator").post(Map.of("name", "order-shipped", "correlationKey", order,
                "payload", Map.of("state", "shipped")), UUID.randomUUID().toString(), "/workspaces/{ws}/messages", PURCHASING);
        assertThat(sent.status()).as(sent.toString()).isLessThan(300);
        awaitEnded(run);
        Poll p = row(run);
        assertThat(p.state()).isEqualTo("cancelled");
        assertThat(p.endReason()).isEqualTo("messageReceived");
        due(run);
        polls.runDue();
        assertThat(TrackingStub.calls()).isZero();
    }
}
