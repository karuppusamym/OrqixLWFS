package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.integration.packages.PackageTriggerService;
import com.orqix.runtime.internal.PollWaits;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.PackageTestBase;
import com.orqix.support.ServiceNowStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The IT access request of doc 29 section 6.14 end to end (docs/build/S133 section 8), on PostgreSQL and Flowable
 * through the public API, with the ServiceNow ITSM package installed through review and a second approver, its starter
 * workflow and its webhook trigger, against the ServiceNow-shaped WireMock stub: create with lookup reconciliation,
 * webhook resume (signed by the business-rule scheme), the safety-net check after a dropped webhook, Canceled to the
 * failed route, the deadline to the timed-out route, and 429 handling. Nothing contacts a real instance.
 */
class ServiceNowPackageE2ETest extends PackageTestBase {

    private static final String SECRET = "servicenow-incident-hook";
    private static final String SECRET_VALUE = "e2e-only-business-rule-secret-0123456789";

    @Autowired
    PollWaits polls;

    @Autowired
    OrqixProperties properties;

    private String packageId;
    private String triggerId;

    @BeforeEach
    void installAndConfigure() throws Exception {
        packageId = serviceNowInstalled();
        bindToStub("servicenow-itsm");
        Files.createDirectories(properties.secrets().dir());
        Files.writeString(properties.secrets().dir().resolve(SECRET), SECRET_VALUE);
        triggerId = jdbc.sql("SELECT id::text FROM package_trigger WHERE workspace_id = :ws"
                + " AND package_id = 'com.orqix.servicenow-itsm' AND trigger_key = 'incident-updated'")
                .param("ws", PURCHASING).query(String.class).optional().orElse(null);
        if (triggerId == null) {
            Response created = api("wade.admin").post(Map.of("packageId", "com.orqix.servicenow-itsm",
                    "triggerKey", "incident-updated", "secretRef", SECRET), "/workspaces/{ws}/package-triggers", PURCHASING);
            assertThat(created.status()).as(created.toString()).isEqualTo(201);
            assertThat(created.body().path("verification").asString()).isEqualTo("hmacSha256");
            assertThat(created.body().path("receivePath").asString()).endsWith("/events");
            triggerId = created.body().path("id").asString();
        }
    }

    @AfterEach
    void stopLeftoverChecks() {
        jdbc.sql("UPDATE poll_wait SET state = 'cancelled', end_reason = 'testEnded', next_check_at = NULL, lease_owner = NULL"
                + " WHERE state IN ('waiting', 'checking') AND workspace_id = :ws AND run_id IN (SELECT id FROM workflow_run"
                + " WHERE workflow_id IN (SELECT id FROM workflow WHERE key LIKE 'itar-%'))").param("ws", PURCHASING).update();
    }

    // ------------------------------------------------------------------ workflow helpers

    /** The package's starter workflow, created and published, with fast test timings. */
    private UUID workflow() throws Exception {
        String key = "itar-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = api("dana.designer").post(Map.of("key", key, "name", "IT access " + key),
                "/workspaces/{ws}/connector-packages/{id}/workflow-templates/{t}/workflows", PURCHASING, packageId,
                "it-access-request");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, id).body().path("definition");
        ((ObjectNode) definition.path("execution")).put("retryDelaySeconds", 5);
        for (JsonNode node : definition.path("nodes")) {
            if ("http".equals(node.path("type").asString())) {
                ((ObjectNode) node.path("config")).put("timeoutSeconds", 3);
            }
        }
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response published = api("paul.publisher").post(Map.of("draftRevision", 2, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return id;
    }

    private UUID start(UUID workflow, String accessTo) throws Exception {
        Response started = api("wade.admin").post(Map.of("workflowId", workflow, "input", Map.of("accessTo", accessTo,
                "justification", "Month-end close needs the finance share", "urgency", "2")),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    /** Waits until the request waits for ServiceNow's answer; returns the incident it raised. */
    private ObjectNode awaitWaiting(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            retriesNow(run);
            return jdbc.sql("SELECT count(*) FROM message_subscription WHERE run_id = :run AND message_name ="
                    + " 'servicenow-incident-updated' AND status = 'waiting'").param("run", run).query(Integer.class)
                    .single() > 0;
        });
        String correlation = jdbc.sql("SELECT correlation_key FROM message_subscription WHERE run_id = :run")
                .param("run", run).query(String.class).single();
        ObjectNode incident = ServiceNowStub.byCorrelation(correlation);
        assertThat(incident).as("incident with correlation_id " + correlation).isNotNull();
        return incident;
    }

    /** Moves retry timers to now; never the wait's own time limit (only the deadline test lets five days pass). */
    private void retriesNow(UUID run) {
        String pid = processInstance(PURCHASING, run);
        if (pid == null) {
            return;
        }
        for (org.flowable.job.api.Job timer : managementService.createTimerJobQuery().processInstanceId(pid).list()) {
            if (timer.getElementId() != null && timer.getElementId().startsWith("waitOutcome")) {
                continue;
            }
            try {
                managementService.moveTimerToExecutableJob(timer.getId());
            } catch (RuntimeException ignored) {
                // picked up concurrently
            }
        }
    }

    /** Runs due checks and engine jobs until the request has ended; {@code deadlinePasses} fires the 5-day limit. */
    private JsonNode awaitEnded(UUID run) throws Exception {
        return awaitEnded(run, false);
    }

    private JsonNode awaitEnded(UUID run, boolean deadlinePasses) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
            jdbc.sql("UPDATE poll_wait SET next_check_at = now() - interval '1 second' WHERE run_id = :run"
                    + " AND state = 'waiting' AND kind = 'check'").param("run", run).update();
            polls.runDue();
            if (deadlinePasses) {
                fastForward(PURCHASING, run);
            } else {
                retriesNow(run);
            }
            return List.of("completed", "needsAttention", "failed").contains(lifecycle(PURCHASING, run));
        });
        return run(PURCHASING, run);
    }

    private MvcResponse webhook(Map<String, Object> event, String secret) throws Exception {
        byte[] body = json.write(event).getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
        String signature = Base64.getEncoder().encodeToString(PackageTriggerService.hmacSha256(
                secret.getBytes(StandardCharsets.UTF_8), (timestamp + ".").getBytes(StandardCharsets.UTF_8), body));
        var result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/{ws}/package-triggers/{id}/events",
                PURCHASING, triggerId).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-ServiceNow-Timestamp", timestamp).header("X-ServiceNow-Signature", signature)).andReturn();
        com.orqix.support.OpenApiContract.validate(result);
        String text = result.getResponse().getContentAsString();
        return new MvcResponse(result.getResponse().getStatus(), text.isEmpty() ? json.object() : json.parse(text));
    }

    private record MvcResponse(int status, JsonNode body) {
    }

    private static Map<String, Object> event(ObjectNode incident) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("correlation_id", incident.path("correlation_id").asString());
        event.put("sys_id", incident.path("sys_id").asString());
        event.put("number", incident.path("number").asString());
        event.put("state", incident.path("state").asString());
        event.put("close_code", incident.path("close_code").asString(""));
        event.put("sys_mod_count", incident.path("sys_mod_count").asString());
        return event;
    }

    private List<JsonNode> events(UUID run, String type) throws Exception {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode e : api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body()
                .path("items")) {
            if (type.equals(e.path("type").asString())) {
                out.add(e.path("data"));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the IT access request

    @Test
    void createIsReconciledByLookupAndTheSignedWebhookResumesTheRequestOnce() throws Exception {
        UUID workflow = workflow();
        // The incident is created, but the answer arrives after Orqix gave up: the lookup finds it.
        ServiceNowStub.fault(ServiceNowStub.CreateFault.COMMIT_THEN_TIMEOUT, 6000);
        UUID run = start(workflow, "Finance share (reconciled)");
        ObjectNode incident = awaitWaiting(run);
        assertThat(ServiceNowStub.creates()).isEqualTo(1);
        assertThat(incident.path("correlation_id").asString()).startsWith("ORQ-").endsWith("-raiseIncident-1");
        JsonNode succeeded = events(run, "EFFECT_SUCCEEDED").get(0);
        assertThat(succeeded.path("reconciled").asBoolean()).isTrue();
        // A forged or unsigned post is refused before anything happens.
        ServiceNowStub.update(incident.path("sys_id").asString(), "6", "Solved (Permanently)");
        incident = ServiceNowStub.byCorrelation(incident.path("correlation_id").asString());
        assertThat(webhook(event(incident), "wrong-secret").status()).isEqualTo(401);
        // The business rule's signed post resumes the one waiting request.
        MvcResponse resumed = webhook(event(incident), SECRET_VALUE);
        assertThat(resumed.status()).as(resumed.body().toString()).isEqualTo(202);
        assertThat(resumed.body().path("status").asString()).isEqualTo("resumed");
        assertThat(webhook(event(incident), SECRET_VALUE).body().path("status").asString()).isEqualTo("duplicate");
        JsonNode ended = awaitEnded(run);
        assertThat(ended.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(ended.path("outcome").asString()).isEqualTo("Access granted");
        assertThat(ended.path("output").path("incidentNumber").asString()).isEqualTo(incident.path("number").asString());
        assertThat(ended.path("output").path("closeCode").asString()).isEqualTo("Solved (Permanently)");
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = 'WAIT_ENDED'").param("run", run)
                .query(Long.class).single()).isEqualTo(1);
        // The safety net of the wait was closed with the message.
        assertThat(jdbc.sql("SELECT end_reason FROM poll_wait WHERE run_id = :run AND kind = 'fallback'").param("run", run)
                .query(String.class).single()).isEqualTo("messageReceived");
        JsonNode trigger = api("wade.admin").get("/workspaces/{ws}/package-triggers", PURCHASING).body();
        assertThat(trigger.toString()).contains("\"recordsHandled\"");
    }

    @Test
    void whenTheWebhookIsLostTheSafetyNetCheckFindsTheOutcome() throws Exception {
        UUID run = start(workflow(), "Payroll system (webhook lost)");
        ObjectNode incident = awaitWaiting(run);
        ServiceNowStub.update(incident.path("sys_id").asString(), "6", "Solved (Work Around)");
        // No post arrives. The safety-net check is every 2 hours; make it due now.
        jdbc.sql("UPDATE poll_wait SET next_check_at = now() - interval '1 second' WHERE run_id = :run AND state = 'waiting'")
                .param("run", run).update();
        JsonNode ended = awaitEnded(run);
        assertThat(ended.path("outcome").asString()).isEqualTo("Access granted");
        assertThat(ended.path("output").path("closeCode").asString()).isEqualTo("Solved (Work Around)");
        assertThat(jdbc.sql("SELECT state FROM poll_wait WHERE run_id = :run AND kind = 'fallback'").param("run", run)
                .query(String.class).single()).isEqualTo("met");
        // The late post is acknowledged and never resumes a second time.
        incident = ServiceNowStub.byCorrelation(incident.path("correlation_id").asString());
        MvcResponse late = webhook(event(incident), SECRET_VALUE);
        assertThat(late.status()).isEqualTo(202);
        assertThat(late.body().path("status").asString()).isIn("buffered", "resumed");
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = 'WAIT_ENDED'").param("run", run)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aCanceledIncidentTakesTheFailedRoute() throws Exception {
        UUID run = start(workflow(), "Old CRM (cancelled)");
        ObjectNode incident = awaitWaiting(run);
        ServiceNowStub.update(incident.path("sys_id").asString(), "8", "Canceled");
        incident = ServiceNowStub.byCorrelation(incident.path("correlation_id").asString());
        assertThat(webhook(event(incident), SECRET_VALUE).status()).isEqualTo(202);
        JsonNode ended = awaitEnded(run);
        assertThat(ended.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(ended.path("outcome").asString()).isEqualTo("Cancelled by IT");
        assertThat(jdbc.sql("SELECT state FROM poll_wait WHERE run_id = :run AND kind = 'check'").param("run", run)
                .query(String.class).single()).isEqualTo("failed");
    }

    @Test
    void noAnswerBeforeTheDeadlineTakesTheTimedOutRoute() throws Exception {
        UUID run = start(workflow(), "Data warehouse (no answer)");
        awaitWaiting(run);
        // Five days pass: the wait's timer fires (moved to now), the safety net closes with it.
        JsonNode ended = awaitEnded(run, true);
        assertThat(ended.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(ended.path("outcome").asString()).isEqualTo("No answer in time");
        assertThat(jdbc.sql("SELECT state FROM poll_wait WHERE run_id = :run AND kind = 'fallback'").param("run", run)
                .query(String.class).single()).isNotIn("waiting", "checking");
    }

    @Test
    void aRateLimitedCreateIsRetriedAfterRetryAfterWithoutADuplicate() throws Exception {
        UUID workflow = workflow();
        ServiceNowStub.fault(ServiceNowStub.CreateFault.RATE_LIMITED, 0);
        UUID run = start(workflow, "VPN (rate limited)");
        ObjectNode incident = awaitWaiting(run);
        List<ServiceNowStub.Call> posts = ServiceNowStub.calls().stream()
                .filter(c -> "POST".equals(c.method()) && c.path().equals("/api/now/table/incident")).toList();
        assertThat(posts).hasSize(2);
        assertThat(ServiceNowStub.incidents().stream().filter(i -> incident.path("correlation_id").asString()
                .equals(i.path("correlation_id").asString())).count()).isEqualTo(1);
        // Both attempts carried the same reconciliation key.
        assertThat(json.parse(posts.get(0).body()).path("correlation_id").asString())
                .isEqualTo(json.parse(posts.get(1).body()).path("correlation_id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM connection_call WHERE workspace_id = :ws AND connection_key ="
                + " 'servicenow-itsm' AND http_status = 429").param("ws", PURCHASING).query(Integer.class).single())
                .isGreaterThanOrEqualTo(1);
    }
}
