package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.integration.adapters.InboundService;
import com.orqix.runtime.internal.CallbackSweeper;
import com.orqix.runtime.internal.MessageSweeper;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * EX10 (docs/build/S98) and IN05 (a) (S99): a "Wait for a message" step answered by an external system. Real HTTP stack,
 * PostgreSQL and Flowable; every response is validated against the OpenAPI contract. Token issued once and stored as a
 * hash, poll with ETag, one resume with idempotent replay, expiry, revocation, suspension, cross-request and
 * cross-workspace misuse, the signed inbound route, permissions and audit content.
 */
@ExtendWith(OutputCaptureExtension.class)
class CallbackTokenApiTest extends RuntimeTestBase {

    private static final String SECRET = "ex10-inbound-secret-value";

    @Autowired
    private MessageSweeper messageSweeper;

    @Autowired
    private CallbackSweeper callbackSweeper;

    @BeforeEach
    void secret() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ex10-inbound"), SECRET);
    }

    private UUID publishExternalWait(String validFor) throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/order-payment-wait.json")));
        ((ObjectNode) example.path("nodes").get(1).path("config")).putObject("externalCallback")
                .put("tokenValidFor", validFor);
        return publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
    }

    private UUID startOrder(UUID workflow) throws Exception {
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input",
                Map.of("orderNumber", "SO-" + UUID.randomUUID().toString().substring(0, 8), "customerName", "Acme Ltd")),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private JsonNode waitOf(UUID run) throws Exception {
        Response list = api("olga.operator").get("/workspaces/{ws}/callback-waits?runId=" + run, PURCHASING);
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        assertThat(list.body().path("items")).hasSize(1);
        return list.body().path("items").get(0);
    }

    private JsonNode issue(UUID waitId) throws Exception {
        Response issued = api("olga.operator").post(null, "/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitId);
        assertThat(issued.status()).as(issued.toString()).isEqualTo(201);
        assertThat(issued.header("Cache-Control")).contains("no-store");
        return issued.body();
    }

    private Response poll(UUID workspace, String token, String etag) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/api/v1/workspaces/{ws}/callbacks/wait", workspace);
        if (token != null) {
            get.header("X-Orqix-Callback-Token", token);
        }
        if (etag != null) {
            get.header("If-None-Match", etag);
        }
        return api(null).send(get);
    }

    private Response resume(UUID workspace, String token, String key, Object result) throws Exception {
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders
                .post("/api/v1/workspaces/{ws}/callbacks/wait/resume", workspace)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("result", result)));
        if (token != null) {
            post.header("X-Orqix-Callback-Token", token);
        }
        if (key != null) {
            post.header("Idempotency-Key", key);
        }
        return api(null).send(post);
    }

    private static Map<String, Object> paid() {
        return Map.of("paymentReference", "PAY-EX10", "amountPaid", 99.5);
    }

    private void awaitLifecycleSwept(UUID run, String lifecycle) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(300)).until(() -> {
            messageSweeper.run();
            return lifecycle.equals(lifecycle(PURCHASING, run));
        });
    }

    @Test
    void anExternalSystemPollsAndResumesWithTheTokenAloneAndReplaysAreSafe() throws Exception {
        UUID wf = publishExternalWait("P7D");
        UUID run = startOrder(wf);
        JsonNode wait = waitOf(run);
        assertThat(wait.path("state").asString()).isEqualTo("waiting");
        assertThat(wait.path("tokenIssued").asBoolean()).isFalse();
        assertThat(wait.path("tokenValidFor").asString()).isEqualTo("PT168H");
        assertThat(wait.path("fields").findValuesAsString("name")).containsExactly("paymentReference", "amountPaid");
        UUID waitId = UUID.fromString(wait.path("id").asString());

        JsonNode issued = issue(waitId);
        String token = issued.path("token").asString();
        assertThat(token).startsWith("ocb_").hasSize(47);
        // Stored only as SHA-256; the clear token is nowhere in the database or the audit log.
        assertThat(jdbc.sql("SELECT token_sha256 FROM callback_wait WHERE id = :id").param("id", waitId)
                .query(String.class).single()).isEqualTo(com.orqix.shared.json.Json.sha256Hex(token));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE metadata::text LIKE :t").param("t", "%" + token.substring(4, 20) + "%")
                .query(Long.class).single()).isZero();
        // The operator view never shows the token again.
        assertThat(waitOf(run).toString()).doesNotContain(token);
        assertThat(waitOf(run).path("tokenIssued").asBoolean()).isTrue();

        Response first = poll(PURCHASING, token, null);
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        assertThat(first.body().path("state").asString()).isEqualTo("waiting");
        assertThat(first.body().path("runId").asString()).isEqualTo(run.toString());
        String etag = first.header("ETag");
        assertThat(etag).startsWith("\"cbw-");
        assertThat(poll(PURCHASING, token, etag).status()).isEqualTo(304);

        // Missing key, invalid result: refused, nothing changes and the wait stays open.
        assertThat(resume(PURCHASING, token, null, paid()).status()).isEqualTo(400);
        Response invalid = resume(PURCHASING, token, UUID.randomUUID().toString(), Map.of("amountPaid", "lots"));
        assertThat(invalid.status()).as(invalid.toString()).isEqualTo(422);
        assertThat(invalid.body().path("fieldErrors").toString()).contains("/result");
        assertThat(poll(PURCHASING, token, etag).status()).isEqualTo(304);

        String key = UUID.randomUUID().toString();
        Response resumed = resume(PURCHASING, token, key, paid());
        assertThat(resumed.status()).as(resumed.toString()).isEqualTo(200);
        assertThat(resumed.body().path("state").asString()).isEqualTo("resumed");
        assertThat(resumed.body().path("acceptedFields").toString()).contains("paymentReference");
        awaitLifecycleSwept(run, "completed");
        assertThat(run(PURCHASING, run).path("output").path("paymentReference").asString()).isEqualTo("PAY-EX10");

        Response replay = resume(PURCHASING, token, key, paid());
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(resumed.body());
        Response otherKey = resume(PURCHASING, token, UUID.randomUUID().toString(), paid());
        assertThat(otherKey.status()).isEqualTo(409);
        assertThat(otherKey.code()).isEqualTo("CALLBACK_ALREADY_RESUMED");
        Response otherBody = resume(PURCHASING, token, key, Map.of("paymentReference", "PAY-OTHER"));
        assertThat(otherBody.code()).isEqualTo("CALLBACK_ALREADY_RESUMED");

        Response after = poll(PURCHASING, token, etag);
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body().path("state").asString()).isEqualTo("resumed");
        assertThat(after.body().path("result")).isEqualTo(resumed.body());
        // Exactly one delivery through the message machinery.
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'WAIT_ENDED'").param("r", run)
                .query(Long.class).single()).isEqualTo(1);

        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'callback.resumed' AND resource_id = :id")
                .param("id", waitId.toString()).query(String.class).single();
        assertThat(json.parse(metadata).propertyNames()).containsExactlyInAnyOrder("waitId", "runId", "fieldCount", "channel");
        assertThat(metadata).doesNotContain("PAY-EX10").doesNotContain(token);
        String issuedMeta = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'callback.token-issued' AND resource_id = :id")
                .param("id", waitId.toString()).query(String.class).single();
        assertThat(json.parse(issuedMeta).propertyNames())
                .containsExactlyInAnyOrder("waitId", "runId", "nodeId", "validForSeconds");
    }

    @Test
    void aTokenCannotTouchAnotherRequestOrWorkspaceAndRevokedOrReplacedTokensAreUnknown() throws Exception {
        UUID wf = publishExternalWait("P1D");
        UUID runA = startOrder(wf);
        UUID runB = startOrder(wf);
        UUID waitA = UUID.fromString(waitOf(runA).path("id").asString());
        UUID waitB = UUID.fromString(waitOf(runB).path("id").asString());
        String tokenA = issue(waitA).path("token").asString();
        String tokenB = issue(waitB).path("token").asString();

        // Another workspace, no token, a malformed and a made-up token: all plain 404.
        assertThat(poll(SANDBOX, tokenA, null).status()).isEqualTo(404);
        assertThat(resume(SANDBOX, tokenA, UUID.randomUUID().toString(), paid()).status()).isEqualTo(404);
        assertThat(poll(PURCHASING, null, null).status()).isEqualTo(404);
        assertThat(poll(PURCHASING, "not-a-token", null).status()).isEqualTo(404);
        assertThat(poll(PURCHASING, "ocb_" + "A".repeat(43), null).status()).isEqualTo(404);

        // Token A only ever reaches request A: resuming with it leaves B waiting.
        assertThat(poll(PURCHASING, tokenA, null).body().path("runId").asString()).isEqualTo(runA.toString());
        assertThat(resume(PURCHASING, tokenA, UUID.randomUUID().toString(), paid()).status()).isEqualTo(200);
        awaitLifecycleSwept(runA, "completed");
        assertThat(lifecycle(PURCHASING, runB)).isEqualTo("waiting");
        assertThat(poll(PURCHASING, tokenB, null).body().path("state").asString()).isEqualTo("waiting");

        // Replacing B's token makes the old one unknown; revoking makes the new one unknown too.
        String tokenB2 = issue(waitB).path("token").asString();
        assertThat(poll(PURCHASING, tokenB, null).status()).isEqualTo(404);
        assertThat(poll(PURCHASING, tokenB2, null).status()).isEqualTo(200);
        assertThat(api("olga.operator").delete("/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitB).status())
                .isEqualTo(204);
        assertThat(poll(PURCHASING, tokenB2, null).status()).isEqualTo(404);
        assertThat(resume(PURCHASING, tokenB2, UUID.randomUUID().toString(), paid()).status()).isEqualTo(404);
        assertThat(lifecycle(PURCHASING, runB)).isEqualTo("waiting");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'callback.token-revoked' AND resource_id = :id")
                .param("id", waitB.toString()).query(Long.class).single()).isEqualTo(2);

        // A resumed wait cannot get a new token.
        Response again = api("olga.operator").post(null, "/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitA);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("CALLBACK_NOT_WAITING");
    }

    @Test
    void permissionsExpirySuspensionAndCancellationAreEnforced() throws Exception {
        UUID wf = publishExternalWait("PT10M");
        UUID run = startOrder(wf);
        UUID waitId = UUID.fromString(waitOf(run).path("id").asString());
        // Requesters and designers cannot see or issue; outsiders do not see the workspace.
        assertThat(api("rhea.requester").get("/workspaces/{ws}/callback-waits", PURCHASING).status()).isEqualTo(403);
        assertThat(api("rhea.requester").post(null, "/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitId)
                .status()).isEqualTo(403);
        assertThat(api("otto.outsider").post(null, "/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitId)
                .status()).isEqualTo(404);
        assertThat(api("olga.operator").post(null, "/workspaces/{ws}/callback-waits/{id}/token", PURCHASING,
                UUID.randomUUID()).status()).isEqualTo(404);
        String token = issue(waitId).path("token").asString();

        // Suspended by an operator: the callback is refused with RUN_SUSPENDED (S98 D10), then accepted after resumeRun.
        Response suspended = api("olga.operator").post(Map.of("reason", "Checking the payment", "expectedVersion",
                run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, run);
        assertThat(suspended.status()).as(suspended.toString()).isEqualTo(200);
        assertThat(poll(PURCHASING, token, null).body().path("suspended").asBoolean()).isTrue();
        String key = UUID.randomUUID().toString();
        Response refused = resume(PURCHASING, token, key, paid());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("RUN_SUSPENDED");
        Response resumedRun = api("olga.operator").post(Map.of("reason", "Checked", "expectedVersion",
                run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/resume", PURCHASING, run);
        assertThat(resumedRun.status()).as(resumedRun.toString()).isEqualTo(200);

        // Expiry: the token can still poll ("expired") but cannot resume; the sweeper audits it once.
        jdbc.sql("UPDATE callback_wait SET token_expires_at = :t WHERE id = :id")
                .param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(5))).param("id", waitId).update();
        assertThat(poll(PURCHASING, token, null).body().path("state").asString()).isEqualTo("expired");
        Response expired = resume(PURCHASING, token, key, paid());
        assertThat(expired.status()).isEqualTo(409);
        assertThat(expired.code()).isEqualTo("CALLBACK_NOT_WAITING");
        callbackSweeper.run();
        callbackSweeper.run();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'callback.token-expired' AND resource_id = :id")
                .param("id", waitId.toString()).query(Long.class).single()).isEqualTo(1);

        // A fresh token works again; cancelling the request turns the state into cancelled/requestEnded.
        String fresh = issue(waitId).path("token").asString();
        Response cancelled = api("olga.operator").post(Map.of("reason", "Customer withdrew", "expectedVersion",
                run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isIn(200, 202);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
            JsonNode state = poll(PURCHASING, fresh, null).body();
            return "cancelled".equals(state.path("state").asString())
                    && "requestEnded".equals(state.path("reason").asString());
        });
        assertThat(resume(PURCHASING, fresh, UUID.randomUUID().toString(), paid()).code()).isEqualTo("CALLBACK_NOT_WAITING");
    }

    @Test
    void anOrdinaryMessageWaitHasNoCallbackRowAndAnAnsweredElsewhereWaitIsCancelled() throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/order-payment-wait.json")));
        UUID plain = publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
        UUID plainRun = startOrder(plain);
        assertThat(api("olga.operator").get("/workspaces/{ws}/callback-waits?runId=" + plainRun, PURCHASING).body()
                .path("items")).isEmpty();

        UUID wf = publishExternalWait("P1D");
        UUID run = startOrder(wf);
        UUID waitId = UUID.fromString(waitOf(run).path("id").asString());
        String token = issue(waitId).path("token").asString();
        String orderNumber = run(PURCHASING, run).path("input").path("orderNumber").asString();
        Response sent = api("olga.operator").post(Map.of("name", "payment-received", "correlationKey", orderNumber,
                "payload", paid()), UUID.randomUUID().toString(), "/workspaces/{ws}/messages", PURCHASING);
        assertThat(sent.status()).isEqualTo(202);
        JsonNode state = poll(PURCHASING, token, null).body();
        assertThat(state.path("state").asString()).isEqualTo("cancelled");
        assertThat(state.path("reason").asString()).isEqualTo("answeredElsewhere");
    }

    // ---------------------------------------------------------------- IN05 (a): signed inbound route with a token

    private Response signed(String triggerId, String eventId, String token, byte[] body) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders
                .post("/api/v1/workspaces/{ws}/inbound/{t}", PURCHASING, triggerId)
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Orqix-Event-Id", eventId).header("X-Orqix-Timestamp", ts)
                .header("X-Orqix-Signature", "sha256=" + InboundService.sign(SECRET, ts, body));
        if (token != null) {
            post.header("X-Orqix-Callback-Token", token);
        }
        return api(null).send(post);
    }

    private String trigger(UUID workflowId) throws Exception {
        Response created = api("wade.admin").post(Map.of("key", "pay-" + UUID.randomUUID().toString().substring(0, 8),
                "source", "kafka", "name", "Payments", "workflowId", workflowId, "secretRef", "ex10-inbound",
                "filter", Map.of("allowedTopics", List.of("payments")),
                "mapping", Map.of("paymentReference", "/value/ref", "amountPaid", "/value/amount")),
                "/workspaces/{ws}/inbound-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body().path("id").asString();
    }

    @Test
    void aSignedInboundEventWithATokenResumesOnlyAWaitOfItsOwnWorkflow() throws Exception {
        UUID wf = publishExternalWait("P1D");
        UUID run = startOrder(wf);
        UUID waitId = UUID.fromString(waitOf(run).path("id").asString());
        String token = issue(waitId).path("token").asString();
        byte[] body = json.write(Map.of("topic", "payments", "value", Map.of("ref", "PAY-IN05", "amount", 10)))
                .getBytes(StandardCharsets.UTF_8);

        // A trigger of another workflow cannot use the token, whatever the payload says.
        UUID other = publishPurchase(PURCHASING, null);
        String foreign = trigger(other);
        assertThat(signed(foreign, "evt-1", token, body).status()).isEqualTo(404);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");

        String own = trigger(wf);
        long eventsBefore = jdbc.sql("SELECT count(*) FROM inbound_event WHERE trigger_id = :t")
                .param("t", UUID.fromString(own)).query(Long.class).single();
        // A bad signature is still 401 before the token is looked at.
        String ts = Long.toString(Instant.now().getEpochSecond());
        assertThat(api(null).send(MockMvcRequestBuilders.post("/api/v1/workspaces/{ws}/inbound/{t}", PURCHASING, own)
                .contentType(MediaType.APPLICATION_JSON).content(body).header("X-Orqix-Event-Id", "evt-x")
                .header("X-Orqix-Timestamp", ts).header("X-Orqix-Signature", "sha256=00")
                .header("X-Orqix-Callback-Token", token)).status()).isEqualTo(401);

        Response accepted = signed(own, "evt-2", token, body);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
        assertThat(accepted.body().path("status").asString()).isEqualTo("resumed");
        assertThat(accepted.body().path("waitId").asString()).isEqualTo(waitId.toString());
        assertThat(accepted.body().path("duplicate").asBoolean()).isFalse();
        awaitLifecycleSwept(run, "completed");
        assertThat(run(PURCHASING, run).path("output").path("paymentReference").asString()).isEqualTo("PAY-IN05");
        // The same event again is an acknowledged duplicate; nothing is recorded as a start event.
        Response duplicate = signed(own, "evt-2", token, body);
        assertThat(duplicate.status()).isEqualTo(202);
        assertThat(duplicate.body().path("duplicate").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE trigger_id = :t").param("t", UUID.fromString(own))
                .query(Long.class).single()).isEqualTo(eventsBefore);
        assertThat(jdbc.sql("SELECT metadata->>'channel' FROM audit_event WHERE action = 'callback.resumed' AND resource_id = :id")
                .param("id", waitId.toString()).query(String.class).single()).isEqualTo("inbound");
        // A different event after the resume conflicts.
        assertThat(signed(own, "evt-3", token, body).status()).isEqualTo(409);
    }

    // ---------------------------------------------------------------- review fixes (S98)

    @Test
    void revokingAfterAResumeIsRefusedSoTheReplayKeepsWorking() throws Exception {
        UUID wf = publishExternalWait("P1D");
        UUID run = startOrder(wf);
        UUID waitId = UUID.fromString(waitOf(run).path("id").asString());
        String token = issue(waitId).path("token").asString();
        String key = UUID.randomUUID().toString();
        Response resumed = resume(PURCHASING, token, key, paid());
        assertThat(resumed.status()).isEqualTo(200);
        Response revoke = api("olga.operator").delete("/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitId);
        assertThat(revoke.status()).isEqualTo(409);
        assertThat(revoke.code()).isEqualTo("CALLBACK_ALREADY_RESUMED");
        Response replay = resume(PURCHASING, token, key, paid());
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(resumed.body());
    }

    @Test
    void theTokenNeverAppearsInAuditRowsLogsOrProblemBodies(CapturedOutput output) throws Exception {
        UUID wf = publishExternalWait("P1D");
        UUID run = startOrder(wf);
        UUID waitId = UUID.fromString(waitOf(run).path("id").asString());
        String token = issue(waitId).path("token").asString();
        String secretPart = token.substring(4);
        List<String> bodies = new java.util.ArrayList<>();
        bodies.add(poll(SANDBOX, token, null).toString());
        bodies.add(resume(PURCHASING, token, null, paid()).toString());
        bodies.add(resume(PURCHASING, token, UUID.randomUUID().toString(), Map.of("amountPaid", "lots")).toString());
        bodies.add(resume(SANDBOX, token, UUID.randomUUID().toString(), paid()).toString());
        // The signed inbound path with the token: a foreign trigger (404) and the own trigger (resume).
        byte[] body = json.write(Map.of("topic", "payments", "value", Map.of("ref", "PAY-SECRET", "amount", 1)))
                .getBytes(StandardCharsets.UTF_8);
        bodies.add(signed(trigger(publishPurchase(PURCHASING, null)), "evt-s1", token, body).toString());
        Response accepted = signed(trigger(wf), "evt-s2", token, body);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
        bodies.add(accepted.toString());
        bodies.add(resume(PURCHASING, token, UUID.randomUUID().toString(), paid()).toString());
        bodies.add(api("olga.operator").delete("/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitId).toString());
        bodies.add(api("olga.operator").get("/workspaces/{ws}/callback-waits?runId=" + run, PURCHASING).toString());
        assertThat(bodies).allSatisfy(b -> assertThat(b).doesNotContain(secretPart));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE metadata::text LIKE :t OR resource_id LIKE :t")
                .param("t", "%" + secretPart.substring(0, 20) + "%").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE metadata::text LIKE :t")
                .param("t", "%" + com.orqix.shared.json.Json.sha256Hex(token) + "%").query(Long.class).single()).isZero();
        assertThat(output.getAll()).doesNotContain(secretPart).doesNotContain("PAY-SECRET");
    }

    @Test
    void closedWaitsArePurgedAfterTheRetentionSoTheirTokensBecomeUnknownWhileOpenWaitsStay() throws Exception {
        UUID wf = publishExternalWait("P1D");
        UUID done = startOrder(wf);
        UUID open = startOrder(wf);
        UUID doneWait = UUID.fromString(waitOf(done).path("id").asString());
        UUID openWait = UUID.fromString(waitOf(open).path("id").asString());
        String doneToken = issue(doneWait).path("token").asString();
        String openToken = issue(openWait).path("token").asString();
        String key = UUID.randomUUID().toString();
        assertThat(resume(PURCHASING, doneToken, key, paid()).status()).isEqualTo(200);
        awaitLifecycleSwept(done, "completed");

        // Within the retention nothing closed recently is removed, and the replay still works.
        callbackSweeper.purge(Instant.now());
        assertThat(resume(PURCHASING, doneToken, key, paid()).status()).isEqualTo(200);

        // 31 days later (retention 30 days) the resumed wait is gone; the open one stays and still works.
        int purged = callbackSweeper.purge(Instant.now().plus(Duration.ofDays(31)));
        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM callback_wait WHERE id = :id").param("id", doneWait).query(Long.class).single()).isZero();
        assertThat(poll(PURCHASING, doneToken, null).status()).isEqualTo(404);
        assertThat(resume(PURCHASING, doneToken, key, paid()).status()).isEqualTo(404);
        assertThat(poll(PURCHASING, openToken, null).body().path("state").asString()).isEqualTo("waiting");
        String meta = jdbc.sql("""
                SELECT metadata::text FROM audit_event WHERE action = 'callback.waits-purged' AND workspace_id = :ws
                ORDER BY occurred_at DESC LIMIT 1
                """).param("ws", PURCHASING).query(String.class).single();
        assertThat(meta).contains("\"count\"").contains("\"retentionSeconds\": 2592000").doesNotContain(doneToken.substring(4, 20));
    }

    @Test
    void eachTokenIsLimitedToSixtyCallsAMinuteAndOtherTokensAreUnaffected() throws Exception {
        UUID wf = publishExternalWait("P1D");
        UUID waitA = UUID.fromString(waitOf(startOrder(wf)).path("id").asString());
        UUID waitB = UUID.fromString(waitOf(startOrder(wf)).path("id").asString());
        String tokenA = issue(waitA).path("token").asString();
        String tokenB = issue(waitB).path("token").asString();
        String etag = poll(PURCHASING, tokenA, null).header("ETag");
        for (int i = 1; i < 60; i++) {
            assertThat(poll(PURCHASING, tokenA, etag).status()).as("call %d", i + 1).isIn(200, 304);
        }
        Response limited = poll(PURCHASING, tokenA, etag);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);
        assertThat(resume(PURCHASING, tokenA, UUID.randomUUID().toString(), paid()).status()).isEqualTo(429);
        assertThat(limited.toString()).doesNotContain(tokenA.substring(4, 20));
        // Unknown tokens are 404, not counted, and other tokens keep working.
        assertThat(poll(PURCHASING, "ocb_" + "B".repeat(43), null).status()).isEqualTo(404);
        assertThat(poll(PURCHASING, tokenB, null).status()).isEqualTo(200);
    }
}
