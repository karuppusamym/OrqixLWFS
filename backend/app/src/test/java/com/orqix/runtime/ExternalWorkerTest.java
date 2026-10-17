package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.ExternalJobService;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** EX01 external workers: leases, fencing, recovery, validation and authorization (docs/build/S43). */
class ExternalWorkerTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Fresh topics per test: the database is shared, so another test's open jobs must never be offered here. */
    private String topic;
    private String scoring;

    @Autowired
    private ExternalJobService jobs;

    @BeforeEach
    void grantWork() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        topic = "address-" + suffix;
        scoring = "scoring-" + suffix;
        jdbc.sql("""
                INSERT INTO permission_grant (id, workspace_id, permission, principal_type, principal_value, created_at)
                VALUES (:id, :ws, 'job.work', 'idpGroup', 'orqix-purchasing-workers', now()) ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("ws", PURCHASING).update();
    }

    @AfterEach
    void revokeWork() {
        jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND permission = 'job.work'")
                .param("ws", PURCHASING).update();
    }

    /** start -> check (worker) -> second (worker, fed by check's score) -> manager approval ... */
    private Consumer<ObjectNode> twoWorkerSteps() {
        return definition -> {
            try {
                ArrayNode nodes = (ArrayNode) definition.path("nodes");
                nodes.add(MAPPER.readTree("{\"id\":\"check\",\"type\":\"externalTask\",\"label\":\"Check the address\",\"config\":{"
                        + "\"topic\":\"" + topic + "\",\"inputMapping\":{\"purpose\":\"/input/purpose\"},"
                        + "\"result\":[{\"name\":\"score\",\"type\":\"number\",\"required\":true},{\"name\":\"note\",\"type\":\"string\"}]}}"));
                nodes.add(MAPPER.readTree("{\"id\":\"second\",\"type\":\"externalTask\",\"label\":\"Score the result\",\"config\":{"
                        + "\"topic\":\"" + scoring + "\",\"inputMapping\":{\"score\":\"/steps/check/score\"},"
                        + "\"result\":[{\"name\":\"ok\",\"type\":\"boolean\",\"required\":true}]}}"));
                ArrayNode transitions = (ArrayNode) definition.path("transitions");
                for (JsonNode t : transitions) {
                    if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                        ((ObjectNode) t).put("to", "check");
                    }
                }
                transitions.add(MAPPER.readTree("{\"id\":\"x1\",\"from\":\"check\",\"to\":\"second\",\"on\":\"next\"}"));
                transitions.add(MAPPER.readTree("{\"id\":\"x2\",\"from\":\"second\",\"to\":\"manager\",\"on\":\"next\"}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private UUID startRun() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, twoWorkerSteps());
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
    }

    private Response fetch(String user, String worker, String... topics) throws Exception {
        return api(user).post(Map.of("workerId", worker, "topics", List.of(topics), "maxJobs", 5, "leaseSeconds", 60),
                "/workspaces/{ws}/external-jobs/fetch-and-lock", PURCHASING);
    }

    private JsonNode firstJob(Response fetched) {
        assertThat(fetched.status()).as(fetched.toString()).isEqualTo(200);
        JsonNode items = fetched.body().path("items");
        assertThat(items).as("leased jobs").hasSize(1);
        return items.get(0);
    }

    private Response complete(String worker, JsonNode job, int fence, Map<String, Object> result, String key) throws Exception {
        return api("wally.worker").post(Map.of("workerId", worker, "fence", fence, "result", result), key,
                "/workspaces/{ws}/external-jobs/{id}/complete", PURCHASING, job.path("id").asString());
    }

    private Response fail(String worker, JsonNode job, boolean retryable) throws Exception {
        return api("wally.worker").post(Map.of("workerId", worker, "fence", job.path("fence").asInt(),
                "message", "Address service said no", "retryable", retryable, "retryAfterSeconds", 0),
                UUID.randomUUID().toString(), "/workspaces/{ws}/external-jobs/{id}/fail", PURCHASING, job.path("id").asString());
    }

    private void awaitJobs(UUID run, String status, long count) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM external_job WHERE run_id = :run AND status = :status")
                .param("run", run).param("status", status).query(Long.class).single() == count);
    }

    @Test
    void aWorkerLeasesCompletesAndTheNextStepReceivesTheTypedResult() throws Exception {
        UUID run = startRun();
        awaitJobs(run, "available", 1);
        assertThat(run(PURCHASING, run).path("currentSteps").get(0).path("kind").asString()).isEqualTo("external");

        JsonNode job = firstJob(fetch("wally.worker", "w1", topic));
        assertThat(job.path("input").path("purpose").asString()).isNotBlank();
        assertThat(job.path("input").size()).as("only mapped values are sent").isEqualTo(1);
        assertThat(job.path("fence").asInt()).isEqualTo(1);
        assertThat(job.path("attempt").asInt()).isEqualTo(1);
        // Nothing else is available for this topic, and other topics are not served.
        assertThat(fetch("wally.worker", "w2", topic).body().path("items")).isEmpty();
        assertThat(fetch("wally.worker", "w2", scoring).body().path("items")).isEmpty();

        Response extended = api("wally.worker").post(Map.of("workerId", "w1", "fence", 1, "leaseSeconds", 120),
                "/workspaces/{ws}/external-jobs/{id}/extend-lock", PURCHASING, job.path("id").asString());
        assertThat(extended.status()).isEqualTo(200);

        String key = UUID.randomUUID().toString();
        Response done = complete("w1", job, 1, Map.of("score", 7.5, "note", "ok"), key);
        assertThat(done.status()).as(done.toString()).isEqualTo(204);
        assertThat(complete("w1", job, 1, Map.of("score", 7.5, "note", "ok"), key).status()).as("replay").isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :id AND type = 'EXTERNAL_JOB_COMPLETED'")
                .param("id", run).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT input::text FROM external_job WHERE id = :id").param("id", UUID.fromString(job.path("id").asString()))
                .query(String.class).single()).as("request data is not kept once the job ends").isEqualTo("{}");

        // The second worker step receives check's score, typed as a number.
        awaitJobs(run, "available", 1);
        JsonNode second = firstJob(fetch("wally.worker", "w1", scoring));
        assertThat(second.path("input").path("score").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(complete("w1", second, 1, Map.of("ok", true), UUID.randomUUID().toString()).status()).isEqualTo(204);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "manager") != null);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'externaljob.completed' AND metadata->>'topic' = :t")
                .param("t", topic).query(Long.class).single()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aLateWorkerCannotOverwriteTheWorkerThatTookOver() throws Exception {
        UUID run = startRun();
        awaitJobs(run, "available", 1);
        JsonNode first = firstJob(fetch("wally.worker", "slow", topic));

        // The lease runs out and the sweeper offers the job again.
        jdbc.sql("UPDATE external_job SET lease_expires_at = now() - interval '1 minute' WHERE run_id = :run AND status = 'locked'")
                .param("run", run).update();
        jobs.recoverExpired();
        awaitJobs(run, "available", 1);
        JsonNode second = firstJob(fetch("wally.worker", "fast", topic));
        assertThat(second.path("fence").asInt()).isEqualTo(2);
        assertThat(second.path("attempt").asInt()).isEqualTo(2);

        Response late = complete("slow", first, first.path("fence").asInt(), Map.of("score", 1), UUID.randomUUID().toString());
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().path("code").asString()).isEqualTo("JOB_LEASE_LOST");
        // The same worker id with the old fence is refused too: the fence, not the name, decides.
        assertThat(complete("fast", first, first.path("fence").asInt(), Map.of("score", 1), UUID.randomUUID().toString())
                .body().path("code").asString()).isEqualTo("JOB_LEASE_LOST");
        assertThat(api("wally.worker").post(Map.of("workerId", "slow", "fence", 1),
                "/workspaces/{ws}/external-jobs/{id}/extend-lock", PURCHASING, first.path("id").asString()).status()).isEqualTo(409);

        assertThat(complete("fast", second, 2, Map.of("score", 2), UUID.randomUUID().toString()).status()).isEqualTo(204);
        assertThat(complete("fast", second, 2, Map.of("score", 2), UUID.randomUUID().toString()).body().path("code").asString())
                .as("an ended job").isEqualTo("JOB_NOT_OPEN");
    }

    @Test
    void theResultMustMatchTheDeclaredFieldsAndTypes() throws Exception {
        UUID run = startRun();
        awaitJobs(run, "available", 1);
        JsonNode job = firstJob(fetch("wally.worker", "w1", topic));
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("note", "no score"),
                Map.<String, Object>of("score", "high"), Map.<String, Object>of("score", 1, "extra", "x"))) {
            Response refused = complete("w1", job, 1, bad, UUID.randomUUID().toString());
            assertThat(refused.status()).as(bad.toString()).isEqualTo(422);
        }
        assertThat(jdbc.sql("SELECT status FROM external_job WHERE run_id = :run").param("run", run).query(String.class).single())
                .as("a refused result leaves the lease in place").isEqualTo("locked");
        assertThat(complete("w1", job, 1, Map.of("score", 3), UUID.randomUUID().toString()).status()).isEqualTo(204);
    }

    @Test
    void failuresRetryThenNeedAttentionAndAnOperatorCanOfferTheJobAgain() throws Exception {
        UUID run = startRun();
        awaitJobs(run, "available", 1);
        JsonNode first = firstJob(fetch("wally.worker", "w1", topic));
        assertThat(fail("w1", first, true).status()).isEqualTo(204);
        JsonNode again = firstJob(fetch("wally.worker", "w1", topic));
        assertThat(again.path("attempt").asInt()).isEqualTo(2);

        // A final failure ends the attempts: the request needs attention instead of looping forever.
        assertThat(fail("w1", again, false).status()).isEqualTo(204);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("needsAttention");
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("attention").path("reason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(detail.path("allowedActions").path("retry").path("allowed").asBoolean()).isTrue();
        assertThat(fetch("wally.worker", "w1", topic).body().path("items")).as("nothing is offered while it needs attention").isEmpty();

        Response retried = api("olga.operator").post(Map.of("reason", "The address service is back", "expectedVersion",
                detail.path("version").asInt()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/retry", PURCHASING, run);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        JsonNode third = firstJob(fetch("wally.worker", "w1", topic));
        assertThat(third.path("attempt").asInt()).as("attempts start again after an operator retry").isEqualTo(1);
        assertThat(complete("w1", third, third.path("fence").asInt(), Map.of("score", 5), UUID.randomUUID().toString()).status())
                .isEqualTo(204);
        // Readers without request-data access never see the worker's own words; the operator does.
        assertThat(api("vic.viewer").get("/workspaces/{ws}/runs/{id}/events?limit=100", PURCHASING, run).body().toString())
                .contains("EXTERNAL_JOB_FAILED").doesNotContain("Address service said no");
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}/events?limit=100", PURCHASING, run).body().toString())
                .contains("Address service said no");
    }

    @Test
    void anExhaustedLeaseFailsTheJobAfterTheAttemptLimit() throws Exception {
        UUID run = startRun();
        awaitJobs(run, "available", 1);
        int max = jdbc.sql("SELECT max_attempts FROM external_job WHERE run_id = :run").param("run", run).query(Integer.class).single();
        for (int i = 1; i <= max; i++) {
            firstJob(fetch("wally.worker", "w" + i, topic));
            jdbc.sql("UPDATE external_job SET lease_expires_at = now() - interval '1 minute' WHERE run_id = :run AND status = 'locked'")
                    .param("run", run).update();
            jobs.recoverExpired();
        }
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("needsAttention");
        assertThat(run(PURCHASING, run).path("attention").path("reason").asString()).isEqualTo("RETRIES_EXHAUSTED");
    }

    @Test
    void onlyHoldersOfTheJobPermissionMayWorkAndSuspendedRequestsHoldTheirJobs() throws Exception {
        UUID run = startRun();
        awaitJobs(run, "available", 1);
        assertThat(fetch("rhea.requester", "w1", topic).status()).isEqualTo(403);
        assertThat(fetch("olga.operator", "w1", topic).status()).as("an operator is not a worker").isEqualTo(403);

        JsonNode detail = run(PURCHASING, run);
        Response suspended = api("olga.operator").post(Map.of("reason", "Hold", "expectedVersion", detail.path("version").asInt()),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, run);
        assertThat(suspended.status()).as(suspended.toString()).isEqualTo(200);
        assertThat(fetch("wally.worker", "w1", topic).body().path("items")).as("suspended requests offer no jobs").isEmpty();
        JsonNode resume = run(PURCHASING, run);
        assertThat(api("olga.operator").post(Map.of("reason", "Go", "expectedVersion", resume.path("version").asInt()),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/resume", PURCHASING, run).status()).isEqualTo(200);
        JsonNode job = firstJob(fetch("wally.worker", "w1", topic));

        // Suspending after the lease: the worker cannot complete until the request resumes.
        JsonNode again = run(PURCHASING, run);
        api("olga.operator").post(Map.of("reason", "Hold again", "expectedVersion", again.path("version").asInt()),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, run);
        Response blocked = complete("w1", job, 1, Map.of("score", 1), UUID.randomUUID().toString());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body().path("code").asString()).isEqualTo("RUN_SUSPENDED");
    }
}
