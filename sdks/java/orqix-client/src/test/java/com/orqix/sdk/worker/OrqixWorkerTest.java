package com.orqix.sdk.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orqix.sdk.OrqixApiException;
import com.orqix.sdk.OrqixClient;
import com.orqix.sdk.ScriptedServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrqixWorkerTest {

    static final String WS = "00000000-0000-7000-8000-000000000201";
    static final String JOB_ID = "0193a0c4-7777-7000-8000-000000000007";
    static final String JOBS = """
            {"items":[{"id":"%s","topic":"address-check","input":{"postcode":"W1A 1AA","amount":12.5},"fence":3,
             "leaseExpiresAt":"2026-11-09T10:00:00Z","attempt":1,"maxAttempts":3,"runReference":"ORQ-1043",
             "nodeId":"check","notYetKnown":"ignored"}]}
            """.formatted(JOB_ID);
    static final String EMPTY = "{\"items\":[]}";

    private final JsonMapper json = JsonMapper.builder().build();
    private ScriptedServer server;
    private OrqixClient client;

    @BeforeEach
    void start() throws Exception {
        server = new ScriptedServer();
        client = OrqixClient.builder().baseUrl(server.baseUrl()).workspaceId(WS).token(() -> "worker-token").build();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private OrqixWorker.Builder worker() {
        return OrqixWorker.builder(client, "address-checker-1").pollInterval(Duration.ofMillis(10))
                .finalCallRetries(3, Duration.ofMillis(5));
    }

    private JsonNode body(ScriptedServer.Received request) {
        return json.readTree(request.body());
    }

    @Test
    void completesAJobWithTheFenceAndAJobFenceKey() {
        server.once("POST", "/external-jobs/fetch-and-lock", 200, JOBS);
        server.once("POST", "/external-jobs/" + JOB_ID + "/complete", 204, null);
        AtomicReference<Job> seen = new AtomicReference<>();
        try (OrqixWorker w = worker().handler("address-check", job -> {
            seen.set(job);
            return Map.of("valid", true, "score", 0.9);
        }).build()) {
            assertEquals(1, w.runOnce());
        }

        assertEquals("W1A 1AA", seen.get().input().get("postcode"));
        assertEquals("ORQ-1043", seen.get().runReference());
        assertEquals(Instant.parse("2026-11-09T10:00:00Z"), seen.get().leaseExpiresAt().toInstant());
        JsonNode fetch = body(server.received("POST", "/fetch-and-lock").get(0));
        assertEquals("address-checker-1", fetch.path("workerId").asString());
        assertEquals("address-check", fetch.path("topics").get(0).asString());
        assertEquals(5, fetch.path("maxJobs").asInt());
        assertEquals(120, fetch.path("leaseSeconds").asInt());
        ScriptedServer.Received complete = server.received("POST", "/complete").get(0);
        assertEquals(JOB_ID + ":3:complete", complete.header("Idempotency-Key"));
        assertEquals("Bearer worker-token", complete.header("Authorization"));
        assertEquals(3, body(complete).path("fence").asInt());
        assertTrue(body(complete).path("result").path("valid").asBoolean());
    }

    @Test
    void aBusinessErrorIsAFinalFailWithItsCode() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.once("POST", "/" + JOB_ID + "/fail", 204, null);
        try (OrqixWorker w = worker().handler("address-check", job -> {
            throw new BusinessErrorException("ADDRESS_UNKNOWN", "No such street");
        }).build()) {
            assertEquals(0, w.runOnce());
        }
        ScriptedServer.Received fail = server.received("POST", "/fail").get(0);
        assertEquals(JOB_ID + ":3:fail", fail.header("Idempotency-Key"));
        assertEquals("ADDRESS_UNKNOWN", body(fail).path("errorCode").asString());
        assertFalse(body(fail).path("retryable").asBoolean());
        assertEquals("No such street", body(fail).path("message").asString());
    }

    @Test
    void aJobFailureKeepsItsRetryChoiceAndDelay() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.once("POST", "/" + JOB_ID + "/fail", 204, null);
        try (OrqixWorker w = worker().handler("address-check", job -> {
            throw new JobFailedException("Directory offline", true, 60);
        }).build()) {
            w.runOnce();
        }
        JsonNode fail = body(server.received("POST", "/fail").get(0));
        assertTrue(fail.path("retryable").asBoolean());
        assertEquals(60, fail.path("retryAfterSeconds").asInt());
        assertFalse(fail.has("errorCode"));
    }

    @Test
    void anUnexpectedExceptionReportsOnlyItsClassName() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.once("POST", "/" + JOB_ID + "/fail", 204, null);
        try (OrqixWorker w = worker().handler("address-check", job -> {
            throw new IllegalStateException("secret connection string");
        }).build()) {
            w.runOnce();
        }
        JsonNode fail = body(server.received("POST", "/fail").get(0));
        assertEquals("The worker raised IllegalStateException", fail.path("message").asString());
        assertTrue(fail.path("retryable").asBoolean());
    }

    @Test
    void aLostLeaseDropsTheJobAndTheLoopGoesOn() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.once("POST", "/" + JOB_ID + "/complete", 409,
                "{\"status\":409,\"code\":\"JOB_LEASE_LOST\",\"correlationId\":\"c\"}");
        try (OrqixWorker w = worker().handler("address-check", job -> Map.of()).build()) {
            assertEquals(0, w.runOnce());
        }
        assertEquals(1, server.received("POST", "/complete").size(), "a lost lease is not retried");
    }

    @Test
    void transientFailuresOfTheFinalCallRepeatWithTheSameKey() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.once("POST", "/" + JOB_ID + "/complete", 503, "{\"status\":503,\"code\":\"DEPENDENCY_UNAVAILABLE\"}");
        server.once("POST", "/" + JOB_ID + "/complete", 204, null);
        try (OrqixWorker w = worker().handler("address-check", job -> Map.of("valid", true)).build()) {
            assertEquals(1, w.runOnce());
        }
        List<ScriptedServer.Received> completes = server.received("POST", "/complete");
        assertEquals(2, completes.size());
        assertEquals(completes.get(0).header("Idempotency-Key"), completes.get(1).header("Idempotency-Key"));
        assertEquals(completes.get(0).body(), completes.get(1).body());
    }

    @Test
    void aRefusedResultIsReportedAsAFailure() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.once("POST", "/" + JOB_ID + "/complete", 422, "{\"status\":422,\"code\":\"VALIDATION_FAILED\"}");
        server.once("POST", "/" + JOB_ID + "/fail", 204, null);
        try (OrqixWorker w = worker().handler("address-check", job -> Map.of("unexpected", 1)).build()) {
            assertEquals(0, w.runOnce());
        }
        assertEquals("The worker's result was refused (VALIDATION_FAILED)",
                body(server.received("POST", "/fail").get(0)).path("message").asString());
    }

    @Test
    void theLeaseIsExtendedWhileTheHandlerRuns() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.always("POST", "/" + JOB_ID + "/extend-lock", 200, "{\"leaseExpiresAt\":\"2026-11-09T10:02:00Z\"}");
        server.once("POST", "/" + JOB_ID + "/complete", 204, null);
        try (OrqixWorker w = worker().extendEvery(Duration.ofMillis(20)).handler("address-check", job -> {
            // Finish once the server has seen a lease extension (bounded wait, no fixed sleep).
            server.await(r -> r.path().endsWith("/extend-lock"), 10);
            return Map.of("valid", true);
        }).build()) {
            assertEquals(1, w.runOnce());
        }
        JsonNode extend = body(server.received("POST", "/extend-lock").get(0));
        assertEquals(3, extend.path("fence").asInt());
        assertEquals("address-checker-1", extend.path("workerId").asString());
    }

    @Test
    void authenticationFailureStopsTheLoop() {
        server.once("POST", "/fetch-and-lock", 401, "{\"status\":401,\"code\":\"UNAUTHENTICATED\"}");
        try (OrqixWorker w = worker().handler("address-check", job -> Map.of()).build()) {
            OrqixApiException e = assertThrows(OrqixApiException.class, w::run);
            assertEquals("UNAUTHENTICATED", e.code());
        }
    }

    @Test
    void runPollsUntilStopped() throws Exception {
        server.always("POST", "/fetch-and-lock", 200, EMPTY);
        CountDownLatch done = new CountDownLatch(1);
        try (OrqixWorker w = worker().handler("address-check", job -> Map.of()).build()) {
            Thread loop = new Thread(() -> {
                w.run();
                done.countDown();
            });
            loop.start();
            server.await(r -> server.received("POST", "/fetch-and-lock").size() >= 2, 10);
            w.stop();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void callbackHandOffUsesItsOwnKeys() {
        server.once("POST", "/fetch-and-lock", 200, JOBS);
        server.once("POST", "/" + JOB_ID + "/await-callback", 200,
                "{\"callbackId\":\"0193a0c4-8888-7000-8000-000000000008\",\"deadline\":\"2026-11-09T11:00:00Z\"}");
        server.once("POST", "/callbacks/0193a0c4-8888-7000-8000-000000000008/complete", 204, null);
        server.once("POST", "/callbacks/0193a0c4-8888-7000-8000-000000000008/fail", 204, null);
        try (OrqixWorker w = worker().handler("address-check", job -> Map.of()).build()) {
            Job job = w.fetchAndLock(List.of("address-check")).get(0);
            CallbackTicket ticket = w.awaitCallback(job, 3600);
            // A restarted gateway rebuilds the ticket from its outbox.
            CallbackTicket restored = new CallbackTicket(ticket.jobId(), ticket.callbackId(), ticket.deadline());
            w.completeCallback(restored, Map.of("score", 0.7));
            w.failCallback(restored, "x".repeat(600));
        }
        assertEquals(JOB_ID + ":3:await-callback",
                server.received("POST", "/await-callback").get(0).header("Idempotency-Key"));
        assertEquals("0193a0c4-8888-7000-8000-000000000008:complete",
                server.received("POST", "/callbacks/0193a0c4-8888-7000-8000-000000000008/complete").get(0)
                        .header("Idempotency-Key"));
        JsonNode fail = body(server.received("POST", "/callbacks/0193a0c4-8888-7000-8000-000000000008/fail").get(0));
        assertEquals(500, fail.path("message").asString().length(), "messages are cut to 500 characters");
    }

    @Test
    void businessErrorCodesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new BusinessErrorException("ORQIX_INTERNAL", "x"));
        assertThrows(IllegalArgumentException.class, () -> new BusinessErrorException("lower", "x"));
        assertEquals("OK_CODE", new BusinessErrorException("OK_CODE", "x").code());
    }

    @Test
    void builderChecksTopicsAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> worker().handler("Bad Topic", job -> Map.of()));
        assertThrows(IllegalArgumentException.class, () -> worker().leaseSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> worker().maxJobs(11));
        assertThrows(IllegalStateException.class, () -> worker().build().runOnce());
    }
}
