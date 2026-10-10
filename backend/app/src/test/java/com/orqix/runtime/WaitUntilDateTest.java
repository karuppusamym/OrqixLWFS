package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Wait until a date" through publish, the engine's timer and the public API (S12 section 10): the due date is
 * the date at 09:00 UTC, the wait survives an executor restart and ends once, a past date ends at once, and dates the
 * form or the horizon do not allow are refused when the request is started.
 */
class WaitUntilDateTest extends RuntimeTestBase {

    @Autowired
    private org.flowable.engine.ProcessEngine processEngine;

    private static final String START = "{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}}";
    private static final String END = "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Completed\",\"outcomeKind\":\"success\"}}";

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** Request form: a required date "startDate" (with optional bounds) and nothing else; a one-step wait. */
    private UUID publishDateWait(String extraDateKeywords, String waitConfig) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            d.set("nodes", json.parse("[" + START + ","
                    + "{\"id\":\"pause\",\"type\":\"wait\",\"label\":\"Wait for the start date\",\"config\":" + waitConfig + "},"
                    + END + "]"));
            d.set("transitions", json.parse("[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"pause\",\"on\":\"next\"},"
                    + "{\"id\":\"t2\",\"from\":\"pause\",\"to\":\"end\",\"on\":\"next\"}]"));
            d.set("decisions", json.parse("[]"));
            d.set("outputMapping", json.object());
            d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
            d.set("dependencies", json.parse("{\"connections\":[]}"));
            d.set("inputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"required\":[\"startDate\"],\"properties\":{\"startDate\":{\"type\":\"string\","
                    + "\"format\":\"date\",\"title\":\"Start date\"" + extraDateKeywords + "}}}"));
            d.remove("inputUi");
            d.remove("uiLayout");
        });
    }

    private Response startOn(UUID workflow, Object date) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("startDate", date);
        return api("rhea.requester").post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
    }

    private UUID runOf(Response started) {
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private java.util.List<String> eventTypes(UUID runId) {
        return jdbc.sql("SELECT type FROM run_event WHERE workspace_id = :ws AND run_id = :id ORDER BY seq")
                .param("ws", PURCHASING).param("id", runId).query(String.class).list();
    }

    private JsonNode waitStep(UUID runId) throws Exception {
        JsonNode run = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body();
        assertThat(run.path("currentSteps")).hasSize(1);
        return run.path("currentSteps").get(0);
    }

    @Test
    void futureDateWaitsUntilNineUtcSurvivesRestartAndEndsOnce() throws Exception {
        UUID wf = publishDateWait("", "{\"untilField\":\"/input/startDate\"}");
        LocalDate date = today().plusDays(3);
        UUID run = runOf(startOn(wf, date.toString()));

        JsonNode step = waitStep(run);
        assertThat(step.path("kind").asString()).isEqualTo("wait");
        assertThat(Instant.parse(step.path("dueAt").asString())).isEqualTo(date.atTime(9, 0).toInstant(ZoneOffset.UTC));
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        String pid = processInstance(PURCHASING, run);
        Job timer = managementService.createTimerJobQuery().processInstanceId(pid).singleResult();
        assertThat(timer.getDuedate().toInstant()).isEqualTo(date.atTime(9, 0).toInstant(ZoneOffset.UTC));
        assertThat(eventTypes(run)).contains("WAIT_STARTED");

        // The executor restarts: the persisted timer is still there and the run still waits for the date.
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().shutdown();
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().start();
        Thread.sleep(2500);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(managementService.createTimerJobQuery().processInstanceId(pid).singleResult().getDuedate())
                .isEqualTo(timer.getDuedate());

        // The date arrives (the test moves the timer forward) and the wait ends exactly once.
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(eventTypes(run)).containsOnlyOnce("WAIT_STARTED").containsOnlyOnce("WAIT_ENDED")
                .containsOnlyOnce("RUN_COMPLETED");
        JsonNode events = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body();
        assertThat(events.path("items").toString()).contains(date + "T09:00:00Z");
    }

    @Test
    void theTimeOfDayComesFromAtTime() throws Exception {
        UUID wf = publishDateWait("", "{\"untilField\":\"/input/startDate\",\"atTime\":\"17:45\"}");
        LocalDate date = today().plusDays(1);
        UUID run = runOf(startOn(wf, date.toString()));
        assertThat(Instant.parse(waitStep(run).path("dueAt").asString()))
                .isEqualTo(date.atTime(17, 45).toInstant(ZoneOffset.UTC));
        awaitLifecycle(PURCHASING, run, "completed");
    }

    @Test
    void aDateInThePastEndsTheWaitAtOnce() throws Exception {
        UUID wf = publishDateWait("", "{\"untilField\":\"/input/startDate\"}");
        UUID run = runOf(startOn(wf, today().minusDays(30).toString()));
        // No fast-forwarding: the timer is already due, so the job executor ends the wait on its own.
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> "completed".equals(lifecycle(PURCHASING, run)));
        assertThat(eventTypes(run)).contains("WAIT_STARTED", "WAIT_ENDED", "RUN_COMPLETED");
    }

    @Test
    void todayAfterTheTimeOfDayAlsoEndsAtOnce() throws Exception {
        UUID wf = publishDateWait("", "{\"untilField\":\"/input/startDate\",\"atTime\":\"00:00\"}");
        UUID run = runOf(startOn(wf, today().toString()));
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> "completed".equals(lifecycle(PURCHASING, run)));
    }

    @Test
    void theHorizonIsOneYearFromToday() throws Exception {
        UUID wf = publishDateWait("", "{\"untilField\":\"/input/startDate\"}");
        LocalDate latest = today().plusDays(366);
        Response tooFar = startOn(wf, latest.plusDays(1).toString());
        assertThat(tooFar.status()).as(tooFar.toString()).isEqualTo(422);
        assertThat(tooFar.body().toString()).contains("/input/startDate").contains("formatMaximum")
                .contains(latest.toString());
        // The last allowed day is accepted.
        runOf(startOn(wf, latest.toString()));
    }

    @Test
    void theFormRefusesWhatIsNotADateOrOutsideItsBounds() throws Exception {
        UUID wf = publishDateWait(",\"formatMinimum\":\"2020-01-01\",\"formatMaximum\":\"2090-12-31\"",
                "{\"untilField\":\"/input/startDate\"}");
        for (Object bad : new Object[] {"2026-02-30", "14 Oct 2026", "2026-10-14T09:00:00Z", 20261014, ""}) {
            Response r = startOn(wf, bad);
            assertThat(r.status()).as(bad + " " + r).isEqualTo(422);
            assertThat(r.body().toString()).contains("/input/startDate");
        }
        Response early = startOn(wf, "2019-12-31");
        assertThat(early.status()).isEqualTo(422);
        assertThat(early.body().toString()).contains("formatMinimum");
        Response late = startOn(wf, "2091-01-01");
        assertThat(late.status()).isEqualTo(422);
        assertThat(late.body().toString()).contains("formatMaximum");
        // A required field cannot be left out.
        Response missing = api("rhea.requester").post(Map.of("workflowId", wf, "input", new LinkedHashMap<>()),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(missing.status()).isEqualTo(422);
    }

    @Test
    void publishingRefusesAWaitOnTheWrongKindOfField() throws Exception {
        String designer = "dana.designer";
        String key = "wd-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = api(designer).post(Map.of("key", key, "name", "Wrong " + key, "templateId", "purchase-approval"),
                "/workspaces/{ws}/workflows", PURCHASING);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) api(designer).get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id)
                .body().path("definition");
        definition.set("nodes", json.parse("[" + START + ","
                + "{\"id\":\"pause\",\"type\":\"wait\",\"label\":\"Pause\",\"config\":{\"untilField\":\"/input/title\"}},"
                + END + "]"));
        definition.set("transitions", json.parse("[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"pause\",\"on\":\"next\"},"
                + "{\"id\":\"t2\",\"from\":\"pause\",\"to\":\"end\",\"on\":\"next\"}]"));
        definition.set("decisions", json.parse("[]"));
        definition.set("outputMapping", json.object());
        definition.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        definition.set("dependencies", json.parse("{\"connections\":[]}"));
        definition.remove("uiLayout");
        Response saved = api(designer).put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response report = api(designer).post(Map.of("revision", 2), "/workspaces/{ws}/workflows/{id}/validate",
                PURCHASING, id);
        assertThat(report.body().path("issues").findValues("code").toString()).contains("WF_WAIT_FIELD_TYPE");
    }
}
