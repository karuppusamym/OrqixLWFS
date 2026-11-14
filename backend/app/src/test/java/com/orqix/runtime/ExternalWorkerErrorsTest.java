package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-E10 worker business errors (docs/build/S132) on the real engine and PostgreSQL: a worker's {@code errorCode}
 * takes the step's failed route with the failure facts, or raises the section error its step opts in to; without a
 * route the request needs attention; retryable failures behave exactly as before; a step the engine stops (section
 * time limit) cancels its open job.
 */
class ExternalWorkerErrorsTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private String topic;

    @BeforeEach
    void grantWork() {
        topic = "verify-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** start -> check (worker) -> manager ...; {@code extra} adds a failed route, a section or both. */
    private Consumer<ObjectNode> worker(Consumer<ObjectNode> extra) {
        return d -> {
            ArrayNode nodes = (ArrayNode) d.path("nodes");
            nodes.add(MAPPER.readTree("{\"id\":\"check\",\"type\":\"externalTask\",\"label\":\"Verify the supplier\",\"config\":{"
                    + "\"topic\":\"" + topic + "\",\"inputMapping\":{\"purpose\":\"/input/purpose\"},"
                    + "\"result\":[{\"name\":\"score\",\"type\":\"number\",\"required\":true}]}}"));
            ArrayNode transitions = (ArrayNode) d.path("transitions");
            for (JsonNode t : transitions) {
                if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                    ((ObjectNode) t).put("to", "check");
                }
            }
            transitions.add(MAPPER.readTree("{\"id\":\"c1\",\"from\":\"check\",\"to\":\"manager\",\"on\":\"next\"}"));
            extra.accept(d);
        };
    }

    private static Consumer<ObjectNode> failedRoute() {
        return d -> ((ArrayNode) d.path("transitions"))
                .add(MAPPER.readTree("{\"id\":\"c2\",\"from\":\"check\",\"to\":\"notifyRejected\",\"on\":\"failed\"}"));
    }

    private static Consumer<ObjectNode> section(String extra) {
        return d -> {
            d.putArray("subprocesses").add(MAPPER.readTree("{\"id\":\"verify\",\"label\":\"Verify\",\"type\":\"embedded\","
                    + "\"nodes\":[\"check\"],\"onError\":{\"to\":\"notifyRejected\",\"errorCode\":\"SUPPLIER_BLOCKED\"}" + extra + "}"));
            for (JsonNode n : d.path("nodes")) {
                if ("check".equals(n.path("id").asString())) {
                    ((ObjectNode) n.path("config")).put("raiseErrorOnFailure", "SUPPLIER_BLOCKED");
                }
            }
        };
    }

    private UUID startRun(Consumer<ObjectNode> patch) throws Exception {
        UUID workflow = publishPurchase(PURCHASING, worker(patch));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        awaitJob(run, "available");
        return run;
    }

    private void awaitJob(UUID run, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> jdbc.sql(
                "SELECT count(*) FROM external_job WHERE run_id = :run AND status = :status")
                .param("run", run).param("status", status).query(Long.class).single() == 1);
    }

    private JsonNode lease() throws Exception {
        Response fetched = api("wally.worker").post(Map.of("workerId", "w1", "topics", List.of(topic), "maxJobs", 1,
                "leaseSeconds", 60), "/workspaces/{ws}/external-jobs/fetch-and-lock", PURCHASING);
        assertThat(fetched.status()).as(fetched.toString()).isEqualTo(200);
        assertThat(fetched.body().path("items")).hasSize(1);
        return fetched.body().path("items").get(0);
    }

    private Response fail(JsonNode job, boolean retryable, String errorCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", "w1");
        body.put("fence", job.path("fence").asInt());
        body.put("message", "The supplier is on the sanctions list");
        body.put("retryable", retryable);
        body.put("retryAfterSeconds", 0);
        if (errorCode != null) {
            body.put("errorCode", errorCode);
        }
        return api("wally.worker").post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/external-jobs/{id}/fail",
                PURCHASING, job.path("id").asString());
    }

    private List<String> events(UUID run) {
        return jdbc.sql("SELECT type || COALESCE(':' || node_id, '') FROM run_event WHERE run_id = :run ORDER BY seq")
                .param("run", run).query(String.class).list();
    }

    private String jobColumn(UUID run, String column) {
        List<String> values = jdbc.sql("SELECT " + column + " FROM external_job WHERE run_id = :run").param("run", run)
                .query(String.class).list();
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    @Test
    void aBusinessErrorTakesTheFailedRouteWithTheFailureFacts() throws Exception {
        UUID run = startRun(failedRoute());
        JsonNode job = lease();
        Response failed = fail(job, false, "SUPPLIER_BLOCKED");
        assertThat(failed.status()).as(failed.toString()).isEqualTo(204);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(events(run)).contains("EXTERNAL_JOB_FAILED:check", "STEP_FAILED:check", "RUN_COMPLETED:rejected")
                .doesNotContain("RUN_NEEDS_ATTENTION:check", "EXTERNAL_JOB_RETRY_SCHEDULED:check");
        assertThat(jobColumn(run, "status")).isEqualTo("failed");
        assertThat(jobColumn(run, "error_code")).isEqualTo("SUPPLIER_BLOCKED");
        String facts = jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = 'EXTERNAL_JOB_FAILED'")
                .param("run", run).query(String.class).single();
        assertThat(facts).contains("\"errorCode\": \"SUPPLIER_BLOCKED\"", "\"routed\": true");
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :run").param("run", run).query(Long.class)
                .single()).as("the manager was never asked").isZero();
        // The job has ended: the worker can no longer change it.
        assertThat(fail(job, false, "SUPPLIER_BLOCKED").status()).isEqualTo(409);
    }

    @Test
    void aBusinessErrorRaisesTheSectionErrorItsStepOptsInTo() throws Exception {
        UUID run = startRun(section(""));
        JsonNode job = lease();
        assertThat(fail(job, false, "ANY_WORKER_CODE").status()).isEqualTo(204);
        awaitLifecycle(PURCHASING, run, "completed");
        List<String> events = events(run);
        assertThat(events).contains("SECTION_ENTERED:verify", "EXTERNAL_JOB_FAILED:check", "ERROR_RAISED:check",
                "ERROR_CAUGHT:verify", "SECTION_LEFT:verify", "RUN_COMPLETED:rejected")
                .doesNotContain("STEP_FAILED:check", "STEP_INTERRUPTED:check", "RUN_NEEDS_ATTENTION:check");
        String raised = jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = 'ERROR_RAISED'")
                .param("run", run).query(String.class).single();
        assertThat(raised).contains("\"errorCode\": \"SUPPLIER_BLOCKED\"", "\"source\": \"workerError\"",
                "\"workerErrorCode\": \"ANY_WORKER_CODE\"");
        assertThat(jobColumn(run, "status")).isEqualTo("failed");
    }

    @Test
    void retryableFailuresAndFinalFailuresWithoutACodeAreUnchanged() throws Exception {
        UUID run = startRun(failedRoute());
        JsonNode job = lease();
        assertThat(fail(job, true, null).status()).isEqualTo(204);
        awaitJob(run, "available");
        assertThat(events(run)).contains("EXTERNAL_JOB_RETRY_SCHEDULED:check").doesNotContain("STEP_FAILED:check");
        // A final failure without a business error code is a technical failure: attention, even with a failed route.
        JsonNode again = lease();
        assertThat(again.path("fence").asInt()).isEqualTo(2);
        assertThat(fail(again, false, null).status()).isEqualTo(204);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("needsAttention");
        assertThat(run(PURCHASING, run).path("attention").path("reason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(events(run)).doesNotContain("STEP_FAILED:check");
        assertThat(jobColumn(run, "error_code")).isNull();
    }

    @Test
    void aBusinessErrorWithoutARouteNeedsAttentionAndBadCodesAreRefused() throws Exception {
        UUID run = startRun(d -> { });
        JsonNode job = lease();
        assertThat(fail(job, true, "SUPPLIER_BLOCKED").status()).as("a business error is final").isEqualTo(422);
        assertThat(fail(job, false, "ORQIX_STEP_FAILED").status()).as("reserved prefix").isEqualTo(422);
        assertThat(fail(job, false, "supplier blocked").status()).as("format").isIn(400, 422);
        assertThat(jobColumn(run, "status")).isEqualTo("locked");
        assertThat(fail(job, false, "SUPPLIER_BLOCKED").status()).isEqualTo(204);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("needsAttention");
        JsonNode attention = run(PURCHASING, run).path("attention");
        assertThat(attention.path("reason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(jobColumn(run, "error_code")).isEqualTo("SUPPLIER_BLOCKED");
        assertThat(jobColumn(run, "last_error")).startsWith("SUPPLIER_BLOCKED: ");
    }

    @Test
    void aSectionTimeLimitCancelsTheOpenJob() throws Exception {
        UUID run = startRun(section(",\"timeoutAfter\":\"P1D\",\"onTimeout\":\"notifyRejected\""));
        JsonNode job = lease();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            fastForward(PURCHASING, run);
            return "completed".equals(lifecycle(PURCHASING, run));
        });
        assertThat(jobColumn(run, "status")).isEqualTo("cancelled");
        assertThat(events(run)).contains("STEP_INTERRUPTED:check", "SECTION_LEFT:verify");
        Response late = api("wally.worker").post(Map.of("workerId", "w1", "fence", job.path("fence").asInt(),
                "result", Map.of("score", 1)), UUID.randomUUID().toString(), "/workspaces/{ws}/external-jobs/{id}/complete",
                PURCHASING, job.path("id").asString());
        assertThat(late.status()).as(late.toString()).isEqualTo(409);
    }
}
