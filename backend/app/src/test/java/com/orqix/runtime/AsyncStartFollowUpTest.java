package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.PollWaits;
import com.orqix.support.ReportJobStub;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Start and follow up (CG-W02, docs/build/S133 section 2) on PostgreSQL and Flowable through the public API: an
 * operation that answers 202 with a Location gives the status operation's path parameter as a result
 * ({@code /@async/jobId}), which a Check until done step on the status operation maps in, exactly as Studio's "Call and
 * wait for completion" builds it. A Location outside the binding is never followed (STATUS_URL_OUTSIDE_BINDING), and one
 * that does not fit the status operation is refused too.
 */
class AsyncStartFollowUpTest extends RuntimeTestBase {

    private static final String KEY = "report-jobs";

    @Autowired
    PollWaits polls;

    @BeforeEach
    void connection() throws Exception {
        JsonNode definition = json.parse("""
                {"schemaVersion":"1.0","kind":"http","key":"report-jobs","version":1,"name":"Report jobs",
                 "operations":{
                  "startReport":{"label":"Start a report","method":"POST","path":"/api/jobs",
                   "requestSchema":{"type":"object","required":["title"],"properties":{"title":{"type":"string","maxLength":100}},
                     "additionalProperties":false},
                   "responseSchema":{"type":"object","properties":{"accepted":{"type":"boolean"}}},
                   "successStatuses":[202],"effectClass":"nonIdempotent","maxTimeoutSeconds":10,"testable":false,
                   "async":{"acceptedStatuses":[202],"statusFrom":{"header":"Location"},"statusOperation":"getJob",
                     "pollIntervalSeconds":10}},
                  "getJob":{"label":"Get a report job","method":"GET","path":"/api/jobs/{jobId}",
                   "pathParams":{"jobId":{"type":"string","pattern":"^[a-z0-9]{1,40}$"}},
                   "responseSchema":{"type":"object","required":["state"],"properties":{
                     "state":{"type":"string","enum":["running","done"]},"documentId":{"type":"string","maxLength":60}}},
                   "successStatuses":[200],"effectClass":"readOnly","maxTimeoutSeconds":10,"testable":true}}}
                """);
        Response registered = api("wade.admin").post(Map.of("definition", definition),
                "/workspaces/{ws}/connections/{key}/versions", PURCHASING, KEY);
        assertThat(registered.status()).as(registered.toString()).isIn(201, 409);
        int bound = jdbc.sql("SELECT count(*) FROM connection_binding WHERE workspace_id = :ws AND connection_key = :k")
                .param("ws", PURCHASING).param("k", KEY).query(Integer.class).single();
        if (bound == 0) {
            Response binding = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/reports",
                    "auth", Map.of("type", "none"), "enabled", true), null,
                    "/workspaces/{ws}/connections/{key}/binding", PURCHASING, KEY);
            assertThat(binding.status()).as(binding.toString()).isEqualTo(200);
        }
    }

    /** procure starts the report; waitReport is the Check until done that Studio adds after it. */
    private UUID workflow() throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ((ObjectNode) d.path("dependencies").path("connections").get(0)).put("key", KEY).put("definitionVersion", 1);
            for (JsonNode node : d.path("nodes")) {
                if ("procure".equals(node.path("id").asString())) {
                    ObjectNode config = (ObjectNode) node.path("config");
                    config.put("connectionRef", KEY).put("operation", "startReport");
                    config.set("inputMapping", json.toTree(Map.of("title", "/input/title")));
                    config.set("resultMapping", json.toTree(Map.of("jobId", "/@async/jobId")));
                }
            }
            ((ArrayNode) d.path("nodes")).add(json.parse("""
                    {"id":"waitReport","type":"http","label":"Wait for the report","config":{
                     "connectionRef":"report-jobs","operation":"getJob","inputMapping":{"jobId":"/steps/procure/jobId"},
                     "resultMapping":{"state":"/state","procurementId":"/documentId"},"timeoutSeconds":3,
                     "poll":{"every":"PT10S","until":{"fieldRef":"/steps/waitReport/state","operator":"eq","value":"done"},
                       "giveUpAfter":"PT1H"}}}"""));
            ((ArrayNode) d.path("nodes")).add(json.parse("""
                    {"id":"reportGaveUp","type":"end","label":"No report","config":{"businessOutcome":"No report",
                     "outcomeKind":"rejected"}}"""));
            for (JsonNode t : d.path("transitions")) {
                if ("procure".equals(t.path("from").asString())) {
                    ((ObjectNode) t).put("from", "waitReport");
                }
            }
            ((ArrayNode) d.path("transitions")).add(json.parse(
                    "{\"id\":\"toWait\",\"from\":\"procure\",\"to\":\"waitReport\",\"on\":\"next\"}"));
            ((ArrayNode) d.path("transitions")).add(json.parse(
                    "{\"id\":\"gaveUp\",\"from\":\"waitReport\",\"to\":\"reportGaveUp\",\"on\":\"timedOut\"}"));
            ((ObjectNode) d.path("outputMapping").path("procurementId")).put("fieldRef", "/steps/waitReport/procurementId");
            d.remove("uiLayout");
        });
    }

    private UUID startAndApprove(UUID workflow) throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        return run;
    }

    @Test
    void theAcceptedAnswersStatusLocationFeedsTheCheckUntilDone() throws Exception {
        UUID run = startAndApprove(workflow());
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
            jdbc.sql("UPDATE poll_wait SET next_check_at = now() - interval '1 second' WHERE run_id = :run"
                    + " AND state = 'waiting'").param("run", run).update();
            polls.runDue();
            fastForward(PURCHASING, run);
            return List.of("completed", "needsAttention").contains(lifecycle(PURCHASING, run));
        });
        JsonNode result = run(PURCHASING, run);
        assertThat(result.path("lifecycle").asString()).isEqualTo("completed");
        String document = result.path("output").path("procurementId").asString();
        assertThat(document).startsWith("DOC-job");
        String jobId = document.substring("DOC-".length());
        assertThat(ReportJobStub.reads(jobId)).isEqualTo(2);
        // The start call's own result carries the job id read from the Location header.
        assertThat(jdbc.sql("SELECT result->>'jobId' FROM effect_journal WHERE run_id = :run AND node_id = 'procure'")
                .param("run", run).query(String.class).single()).isEqualTo(jobId);
    }

    @Test
    void aStatusLocationOutsideTheBindingIsNeverFollowed() throws Exception {
        UUID workflow = workflow();
        for (Map.Entry<String, String> c : Map.of(
                "http://169.254.169.254/latest/meta-data/jobs/x", "STATUS_URL_OUTSIDE_BINDING",
                Stub.baseUrl().replace("localhost", "127.0.0.1") + "/reports/api/jobs/abc", "STATUS_URL_OUTSIDE_BINDING",
                "/other/api/jobs/abc", "STATUS_URL_OUTSIDE_BINDING",
                "/reports/api/jobs/..%2F..%2Fadmin", "STATUS_URL_OUTSIDE_BINDING",
                "/reports/api/admin/abc", "STATUS_URL_UNEXPECTED",
                "/reports/api/jobs/BAD_ID", "STATUS_URL_UNEXPECTED").entrySet()) {
            ReportJobStub.nextLocation(c.getKey());
            UUID run = startAndApprove(workflow);
            awaitLifecycle(PURCHASING, run, "needsAttention", "completed");
            assertThat(lifecycle(PURCHASING, run)).as(c.getKey()).isEqualTo("needsAttention");
            assertThat(jdbc.sql("SELECT last_error_code FROM effect_journal WHERE run_id = :run AND node_id = 'procure'")
                    .param("run", run).query(String.class).single()).as(c.getKey()).isEqualTo(c.getValue());
            assertThat(jdbc.sql("SELECT count(*) FROM poll_wait WHERE run_id = :run").param("run", run)
                    .query(Integer.class).single()).isZero();
        }
    }
}
