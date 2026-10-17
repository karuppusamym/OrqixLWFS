package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-RUN-01/02/06/07/08, T-API-04/05, T-TASK-01…04, T-SEC-02/03/04: starts, inbox lists, concurrent commands,
 * idempotent replays, release pinning and separation of duties.
 */
class RunsAndTasksTest extends RuntimeTestBase {

    private static UUID shared;

    private UUID workflow() throws Exception {
        if (shared == null) {
            shared = publishPurchase(PURCHASING, null);
        }
        return shared;
    }

    @Test
    void tRun01_inputValidationFixtures() throws Exception {
        UUID workflowId = workflow();
        for (JsonNode fixture : Fixtures.purchaseScenarios().path("inputValidation")) {
            int before = runCount(workflowId);
            Response response = api("rhea.requester").post(Map.of("workflowId", workflowId, "input", fixture.path("input")),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
            if (fixture.path("valid").asBoolean()) {
                assertThat(response.status()).as(fixture.path("name").asString() + " " + response).isEqualTo(201);
            } else {
                assertThat(response.status()).as(fixture.path("name").asString() + " " + response).isEqualTo(422);
                assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
                List<String> pointers = new ArrayList<>();
                response.body().path("fieldErrors").forEach(e -> pointers.add(e.path("pointer").asString()));
                for (JsonNode expected : fixture.path("fieldErrors")) {
                    assertThat(pointers).as(fixture.path("name").asString()).contains("/input" + expected.asString());
                }
                assertThat(runCount(workflowId)).isEqualTo(before);
            }
        }
    }

    @Test
    void tApi04_startReplayConcurrentAndReuse() throws Exception {
        UUID workflowId = workflow();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("workflowId", workflowId, "input", input(new BigDecimal("12.50")));
        Response first = api("rhea.requester").post(body, key, "/workspaces/{ws}/runs", PURCHASING);
        assertThat(first.status()).isEqualTo(201);
        Response replay = api("rhea.requester").post(body, key, "/workspaces/{ws}/runs", PURCHASING);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("id").asString()).isEqualTo(first.body().path("id").asString());
        Response reused = api("rhea.requester").post(Map.of("workflowId", workflowId, "input", input(new BigDecimal("13"))),
                key, "/workspaces/{ws}/runs", PURCHASING);
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // Concurrent identical starts: exactly one run.
        String concurrentKey = UUID.randomUUID().toString();
        int before = runCount(workflowId);
        List<Response> responses = parallel(8, () -> api("rhea.requester").post(body, concurrentKey,
                "/workspaces/{ws}/runs", PURCHASING));
        assertThat(responses).extracting(Response::status).containsOnly(201, 200);
        assertThat(responses.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(runCount(workflowId)).isEqualTo(before + 1);
    }

    @Test
    void tApi05_tamperedOrMismatchedCursorIsRejected() throws Exception {
        UUID workflowId = workflow();
        start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN);
        start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN);
        Response page = api("olga.operator").get("/workspaces/{ws}/runs?limit=1", PURCHASING);
        String cursor = page.body().path("nextCursor").asString();
        assertThat(cursor).isNotBlank();
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs?limit=1&cursor=" + cursor, PURCHASING).status())
                .isEqualTo(200);
        String tampered = cursor.substring(0, cursor.length() - 2) + (cursor.endsWith("AA") ? "BB" : "AA");
        Response bad = api("olga.operator").get("/workspaces/{ws}/runs?limit=1&cursor=" + tampered, PURCHASING);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.code()).isEqualTo("INVALID_CURSOR");
        Response mismatched = api("olga.operator").get("/workspaces/{ws}/runs?limit=1&lifecycle=waiting&cursor=" + cursor,
                PURCHASING);
        assertThat(mismatched.code()).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void tTask01_inboxListsMatchEligibilityAndSeparationOfDuties_tSec03() throws Exception {
        UUID workflowId = workflow();
        UUID rheaRun = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.ONE).path("id").asString());
        UUID maniRun = UUID.fromString(start("mani.manager", PURCHASING, workflowId, BigDecimal.ONE).path("id").asString());
        List<String> maniClaimable = runIds(api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=100",
                PURCHASING).body());
        assertThat(maniClaimable).contains(rheaRun.toString()).doesNotContain(maniRun.toString());
        List<String> maxClaimable = runIds(api("max.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=100",
                PURCHASING).body());
        assertThat(maxClaimable).contains(rheaRun.toString(), maniRun.toString());
        assertThat(api("fin.finance").get("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING).body()
                .path("items").findValues("runId").stream().map(JsonNode::asString).toList())
                .doesNotContain(rheaRun.toString());
        assertThat(api("rhea.requester").get("/workspaces/{ws}/tasks?view=claimable", PURCHASING).status()).isEqualTo(403);
        // Own task: claim refused with SELF_APPROVAL_DENIED; not visible as a candidate task either.
        String maniTask = openTask(PURCHASING, maniRun, "manager").path("id").asString();
        Response own = claim("mani.manager", PURCHASING, maniTask);
        assertThat(own.status()).isEqualTo(403);
        assertThat(own.code()).isEqualTo("SELF_APPROVAL_DENIED");
        assertThat(claim("fin.finance", PURCHASING, maniTask).status()).isEqualTo(404);
        // Assigned and completed views.
        String rheaTask = openTask(PURCHASING, rheaRun, "manager").path("id").asString();
        assertThat(claim("max.manager", PURCHASING, rheaTask).status()).isEqualTo(200);
        JsonNode assigned = api("max.manager").get("/workspaces/{ws}/tasks?view=assigned&limit=100", PURCHASING).body();
        assertThat(runIds(assigned)).contains(rheaRun.toString());
        assertThat(assigned.path("counts").path("assigned").asInt()).isPositive();
        assertThat(decide("max.manager", PURCHASING, rheaTask, "reject", "Not needed").status()).isEqualTo(201);
        JsonNode completed = api("max.manager").get("/workspaces/{ws}/tasks?view=completed&limit=100", PURCHASING).body();
        assertThat(runIds(completed)).contains(rheaRun.toString());
    }

    @Test
    void tSec03_selfApprovalAllowedOnlyInTestWorkspaceAndFlagged() throws Exception {
        UUID workflowId = publishPurchase(SANDBOX, d -> ((ObjectNode) d.path("security")).put("selfApproval", "allow"));
        UUID runId = UUID.fromString(start("mani.manager", SANDBOX, workflowId, BigDecimal.ONE).path("id").asString());
        String taskId = openTaskIn(SANDBOX, runId, "manager");
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=100", SANDBOX).body()
                .path("items").findValues("runId").stream().map(JsonNode::asString).toList()).contains(runId.toString());
        assertThat(claim("mani.manager", SANDBOX, taskId).status()).isEqualTo(200);
        Response decided = decide("mani.manager", SANDBOX, taskId, "approve", null);
        assertThat(decided.status()).as(decided.toString()).isEqualTo(201);
        assertThat(decided.body().path("policyFlags").get(0).asString()).isEqualTo("selfApprovalAllowed");
        // The same definition cannot be published in a production workspace.
        Response created = api("dana.designer").post(Map.of("key", "self-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Self", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id)
                .body().path("definition");
        ((ObjectNode) definition.path("security")).put("selfApproval", "allow");
        api("dana.designer").put(Map.of("definition", definition), "\"r1\"", "/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, id);
        Response report = api("dana.designer").post(Map.of("revision", 2), "/workspaces/{ws}/workflows/{id}/validate",
                PURCHASING, id);
        assertThat(report.body().path("issues").findValues("code").toString()).contains("WF_SELF_APPROVAL_NOT_ALLOWED");
    }

    @Test
    void tTask02_concurrentClaimsOneWinner() throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow(), BigDecimal.ONE).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        List<Response> responses = parallel(2, List.of("mani.manager", "max.manager"), user ->
                api(user).post(Map.of("expectedVersion", 1), "/workspaces/{ws}/tasks/{id}/claim", PURCHASING, taskId));
        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
        assertThat(responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().code())
                .isIn("TASK_ALREADY_CLAIMED", "TASK_VERSION_CONFLICT");
    }

    @Test
    void tTask03_concurrentDecisionsOneDecisionOneContinuation() throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow(), BigDecimal.ONE).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        List<Response> responses = parallel(2, List.of("approve", "reject"), action -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("expectedVersion", 2);
            body.put("action", action);
            body.put("reason", "Parallel");
            return api("mani.manager").post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/tasks/{id}/decision",
                    PURCHASING, taskId);
        });
        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM task_decision WHERE engine_task_id = :t").param("t", taskId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'task.decided' AND resource_id = :t")
                .param("t", taskId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'TASK_DECIDED'")
                .param("r", runId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void tTask04_tSec02_decisionReplayAndRefusalAfterRevocation() throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow(), BigDecimal.ONE).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("expectedVersion", 2, "action", "reject", "reason", "Too expensive");
        Response first = api("mani.manager").post(body, key, "/workspaces/{ws}/tasks/{id}/decision", PURCHASING, taskId);
        assertThat(first.status()).isEqualTo(201);
        Response replay = api("mani.manager").post(body, key, "/workspaces/{ws}/tasks/{id}/decision", PURCHASING, taskId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        // mani loses the approver group (IdP change at next login): the replay is refused, not replayed.
        Response revoked = api(null).send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/workspaces/{ws}/tasks/{id}/decision", PURCHASING, taskId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.write(body))
                .header("Idempotency-Key", key).with(com.orqix.support.IntegrationTestBase::xsrf)
                .with(com.orqix.support.TestUsers.as("mani.manager", List.of("orqix-purchasing-requesters"))));
        assertThat(revoked.status()).isIn(403, 404);
        assertThat(revoked.header("Idempotent-Replayed")).isNull();
        // Rejection without a reason is refused.
        UUID other = UUID.fromString(start("rhea.requester", PURCHASING, workflow(), BigDecimal.ONE).path("id").asString());
        String otherTask = openTask(PURCHASING, other, "manager").path("id").asString();
        claim("mani.manager", PURCHASING, otherTask);
        Response noReason = decide("mani.manager", PURCHASING, otherTask, "reject", null);
        assertThat(noReason.code()).isEqualTo("REASON_REQUIRED");
        Response notMine = decide("max.manager", PURCHASING, otherTask, "approve", null);
        assertThat(notMine.code()).isEqualTo("TASK_NOT_CLAIMED_BY_CALLER");
    }

    @Test
    void tSec04_forgedOrUnknownFieldsAreRejected() throws Exception {
        UUID workflowId = workflow();
        for (String field : List.of("actor", "initiator", "workspaceId", "assignee")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workflowId", workflowId);
            body.put("input", input(BigDecimal.ONE));
            body.put(field, UUID.randomUUID().toString());
            Response response = api("rhea.requester").post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/runs",
                    PURCHASING);
            assertThat(response.status()).as(field).isEqualTo(400);
            assertThat(response.code()).isEqualTo("MALFORMED_REQUEST");
        }
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.ONE).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        Response forged = api("mani.manager").post(Map.of("expectedVersion", 1, "assignee", UUID.randomUUID().toString()),
                "/workspaces/{ws}/tasks/{id}/claim", PURCHASING, taskId);
        assertThat(forged.status()).isEqualTo(400);
    }

    @Test
    void tRun02_eventSequenceGapFreeUnderConcurrentWriters() throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow(), BigDecimal.ONE).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        // Concurrent claim/release cycles append events while a poller reads with ?after.
        List<Long> seen = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> writer = pool.submit(() -> {
            for (int i = 0; i < 5; i++) {
                JsonNode task = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
                api("mani.manager").post(Map.of("expectedVersion", task.path("version").asInt()),
                        "/workspaces/{ws}/tasks/{id}/claim", PURCHASING, taskId);
                task = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
                api("mani.manager").post(Map.of("expectedVersion", task.path("version").asInt()),
                        "/workspaces/{ws}/tasks/{id}/release", PURCHASING, taskId);
            }
            return null;
        });
        long after = 0;
        while (!writer.isDone()) {
            JsonNode page = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/events?after=" + after, PURCHASING, runId)
                    .body();
            for (JsonNode event : page.path("items")) {
                seen.add(event.path("seq").asLong());
            }
            after = page.path("nextAfter").asLong();
        }
        writer.get();
        pool.shutdown();
        JsonNode rest = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/events?after=" + after + "&limit=100",
                PURCHASING, runId).body();
        rest.path("items").forEach(e -> seen.add(e.path("seq").asLong()));
        for (int i = 0; i < seen.size(); i++) {
            assertThat(seen.get(i)).isEqualTo(i + 1L);
        }
        assertThat(seen.size()).isGreaterThanOrEqualTo(12);
    }

    @Test
    void tRun06_07_runsStayOnTheirReleaseAndRollbackSelectsOlderRelease() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID runOnR1 = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, new BigDecimal("6000")).path("id")
                .asString());
        // Release 2 raises the finance threshold to 10,000.
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING,
                workflowId).body().path("definition");
        ((ObjectNode) definition.path("decisions").get(0).path("rows").get(0).path("when")).put("value", 10000);
        api("dana.designer").put(Map.of("definition", definition), "\"r2\"", "/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, workflowId);
        Response r2 = api("paul.publisher").post(Map.of("draftRevision", 3, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflowId);
        assertThat(r2.body().path("releaseNumber").asInt()).isEqualTo(2);
        approve("mani.manager", PURCHASING, runOnR1, "manager");
        // Run on release 1 still routes 6,000 to finance.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                .until(() -> openTask(PURCHASING, runOnR1, "finance") != null);
        assertThat(run(PURCHASING, runOnR1).path("release").path("releaseNumber").asInt()).isEqualTo(1);
        // New run on release 2 skips finance for 6,000.
        UUID runOnR2 = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, new BigDecimal("6000")).path("id")
                .asString());
        assertThat(run(PURCHASING, runOnR2).path("release").path("releaseNumber").asInt()).isEqualTo(2);
        approve("mani.manager", PURCHASING, runOnR2, "manager");
        awaitLifecycle(PURCHASING, runOnR2, "completed");
        // Rollback: select release 1 for new starts.
        JsonNode workflow = api("paul.publisher").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).body();
        String r1 = api("paul.publisher").get("/workspaces/{ws}/workflows/{id}/releases", PURCHASING, workflowId).body()
                .path("items").get(1).path("id").asString();
        Response conflict = api("paul.publisher").post(Map.of("releaseId", r1, "expectedVersion", 99),
                "/workspaces/{ws}/workflows/{id}/active-release", PURCHASING, workflowId);
        assertThat(conflict.code()).isEqualTo("WORKFLOW_VERSION_CONFLICT");
        Response rollback = api("paul.publisher").post(Map.of("releaseId", r1, "expectedVersion",
                workflow.path("version").asInt()), "/workspaces/{ws}/workflows/{id}/active-release", PURCHASING, workflowId);
        assertThat(rollback.status()).as(rollback.toString()).isEqualTo(200);
        UUID runAfterRollback = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.ONE).path("id")
                .asString());
        assertThat(run(PURCHASING, runAfterRollback).path("release").path("releaseNumber").asInt()).isEqualTo(1);
        assertThat(run(PURCHASING, runOnR1).path("release").path("releaseNumber").asInt()).isEqualTo(1);
    }

    @Test
    void tRun08_quotaExceeded() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, d -> ((ObjectNode) d.path("execution")).put("maxConcurrentRuns", 2));
        start("rhea.requester", PURCHASING, workflowId, BigDecimal.ONE);
        start("rhea.requester", PURCHASING, workflowId, BigDecimal.ONE);
        Response third = api("rhea.requester").post(Map.of("workflowId", workflowId, "input", input(BigDecimal.ONE)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(third.status()).isEqualTo(429);
        assertThat(third.code()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(third.header("Retry-After")).isNotBlank();
    }

    @Test
    void tRun09_requestReferencesAreUniqueSequentialAndShownInEveryView() throws Exception {
        UUID workflowId = workflow();
        // Concurrent starts by different callers get distinct, gap-free numbers from the workspace counter.
        List<Response> starts = parallel(6, List.of("rhea.requester", "mani.manager", "max.manager", "rhea.requester",
                "mani.manager", "max.manager"), user -> api(user).post(Map.of("workflowId", workflowId, "input",
                input(BigDecimal.ONE)), UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING));
        assertThat(starts).extracting(Response::status).containsOnly(201);
        List<Long> numbers = starts.stream().map(r -> referenceNumber(r.body().path("reference").asString())).sorted()
                .toList();
        assertThat(numbers).doesNotHaveDuplicates().isSorted().allSatisfy(n -> assertThat(n).isGreaterThan(1000L));
        assertThat(numbers.get(numbers.size() - 1) - numbers.get(0)).isEqualTo(numbers.size() - 1L);
        // The counter row always equals the highest allocated number, and references are unique per workspace.
        long highest = jdbc.sql("SELECT max(substring(reference from 5)::bigint) FROM workflow_run WHERE workspace_id = :ws")
                .param("ws", PURCHASING).query(Long.class).single();
        assertThat(jdbc.sql("SELECT run_seq FROM workspace WHERE id = :ws").param("ws", PURCHASING).query(Long.class)
                .single()).isEqualTo(highest);
        assertThat(jdbc.sql("SELECT count(*) - count(DISTINCT reference) FROM workflow_run WHERE workspace_id = :ws")
                .param("ws", PURCHASING).query(Long.class).single()).isZero();

        // The same reference appears in the run detail, the run lists, the task list and the task detail.
        JsonNode started = starts.get(0).body();
        String reference = started.path("reference").asString();
        UUID runId = UUID.fromString(started.path("id").asString());
        assertThat(reference).matches("ORQ-[1-9][0-9]{3,}");
        assertThat(run(PURCHASING, runId).path("reference").asString()).isEqualTo(reference);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body().path("reference")
                .asString()).isEqualTo(reference);
        assertThat(referenceOf(api("olga.operator").get("/workspaces/{ws}/runs?limit=100", PURCHASING).body(), "id",
                runId.toString())).isEqualTo(reference);
        assertThat(strings(api("rhea.requester").get("/workspaces/{ws}/me/requests?limit=100", PURCHASING).body(),
                "reference")).contains(reference).allMatch(r -> r.matches("ORQ-[1-9][0-9]{3,}"));
        JsonNode task = openTask(PURCHASING, runId, "manager");
        assertThat(task.path("runReference").asString()).isEqualTo(reference);
        String taskId = task.path("id").asString();
        assertThat(api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body()
                .path("runReference").asString()).isEqualTo(reference);
        assertThat(strings(api("max.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING).body(),
                "runReference")).contains(reference);

        // Request fields are labelled in form order (inputUi.order), not in the storage order of the jsonb document.
        List<String> labelOrder = new ArrayList<>();
        run(PURCHASING, runId).path("inputLabels").propertyNames().forEach(labelOrder::add);
        List<String> formOrder = new ArrayList<>();
        api("rhea.requester").get("/workspaces/{ws}/workflows/{id}/start-form", PURCHASING, workflowId).body()
                .path("inputUi").path("order").forEach(n -> formOrder.add(n.asString()));
        assertThat(formOrder).isNotEmpty();
        assertThat(labelOrder).startsWith(formOrder.toArray(String[]::new));

        // Notifications carry the reference of their request.
        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "completed", "waiting", "needsAttention");
        for (JsonNode notification : api("rhea.requester").get("/workspaces/{ws}/me/notifications?limit=100", PURCHASING)
                .body().path("items")) {
            if (runId.toString().equals(notification.path("runId").asString())) {
                assertThat(notification.path("runReference").asString()).isEqualTo(reference);
            }
        }
    }

    @Test
    void runListsCarryTheRequestTitleOnlyForCallersWhoMayReadTheData() throws Exception {
        UUID workflowId = workflow();
        JsonNode started = start("rhea.requester", PURCHASING, workflowId, BigDecimal.ONE);
        UUID runId = UUID.fromString(started.path("id").asString());
        // the start response goes to the initiator, who may read the request
        assertThat(started.path("requestTitle").asString()).isEqualTo("Replacement laptop");
        // the initiator (my requests) and an operator (run.read-data) see the title
        assertThat(titleIn(api("rhea.requester").get("/workspaces/{ws}/me/requests?limit=100", PURCHASING).body(), runId))
                .isEqualTo("Replacement laptop");
        assertThat(titleIn(api("olga.operator").get("/workspaces/{ws}/runs?limit=100", PURCHASING).body(), runId))
                .isEqualTo("Replacement laptop");
        assertThat(run(PURCHASING, runId).path("requestTitle").asString()).isEqualTo("Replacement laptop");
        // a viewer sees that the request exists, but the title is withheld together with the input
        Response viewerList = api("vic.viewer").get("/workspaces/{ws}/runs?limit=100", PURCHASING);
        assertThat(viewerList.status()).isEqualTo(200);
        assertThat(findRun(viewerList.body(), runId).has("requestTitle")).isTrue();
        assertThat(titleIn(viewerList.body(), runId)).isNull();
        JsonNode viewerDetail = api("vic.viewer").get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body();
        assertThat(viewerDetail.path("input").isNull()).isTrue();
        assertThat(viewerDetail.path("requestTitle").isNull()).isTrue();
        assertThat(viewerList.body().toString()).doesNotContain("Replacement laptop");
        // notifications of the request carry the title for the initiator
        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "completed", "waiting", "needsAttention");
        for (JsonNode notification : api("rhea.requester").get("/workspaces/{ws}/me/notifications?limit=100", PURCHASING)
                .body().path("items")) {
            if (runId.toString().equals(notification.path("runId").asString())) {
                assertThat(notification.path("requestTitle").asString()).isEqualTo("Replacement laptop");
            }
        }
    }

    @Test
    void workflowListSaysWhichDraftRevisionTheActiveReleaseWasPublishedFrom() throws Exception {
        UUID id = publishPurchase(PURCHASING, null);
        JsonNode published = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, id).body();
        assertThat(published.path("activeRelease").path("draftRevision").asInt()).isEqualTo(2);
        assertThat(published.path("draftRevision").asInt()).isEqualTo(2);
        // editing the draft moves the draft revision on; the release keeps the revision it was published from
        JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id).body();
        ObjectNode changed = (ObjectNode) draft.path("definition");
        changed.put("name", "Purchase approval (changed)");
        assertThat(api("dana.designer").put(Map.of("definition", changed), "\"r2\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id).status()).isEqualTo(200);
        JsonNode list = api("dana.designer").get("/workspaces/{ws}/workflows?limit=100", PURCHASING).body();
        JsonNode listed = null;
        for (JsonNode item : list.path("items")) {
            if (id.toString().equals(item.path("id").asString())) {
                listed = item;
            }
        }
        assertThat(listed).as("workflow listed").isNotNull();
        assertThat(listed.path("draftRevision").asInt()).isEqualTo(3);
        assertThat(listed.path("activeRelease").path("draftRevision").asInt()).isEqualTo(2);
    }

    private static JsonNode findRun(JsonNode page, UUID runId) {
        for (JsonNode item : page.path("items")) {
            if (runId.toString().equals(item.path("id").asString())) {
                return item;
            }
        }
        throw new AssertionError("run " + runId + " not listed");
    }

    private static String titleIn(JsonNode page, UUID runId) {
        return findRun(page, runId).path("requestTitle").asString(null);
    }

    private static List<String> strings(JsonNode page, String field) {
        return page.path("items").findValues(field).stream().map(JsonNode::asString).toList();
    }

    private static long referenceNumber(String reference) {
        assertThat(reference).startsWith("ORQ-");
        return Long.parseLong(reference.substring(4));
    }

    private static String referenceOf(JsonNode page, String idField, String id) {
        for (JsonNode item : page.path("items")) {
            if (id.equals(item.path(idField).asString())) {
                return item.path("reference").asString();
            }
        }
        throw new AssertionError("run " + id + " not listed");
    }

    @Test
    void tRun10_searchFindsGeneratedReferenceOrExactBusinessKey() throws Exception {
        UUID workflowId = workflow();
        String businessKey = "search-" + UUID.randomUUID();
        Response started = api("rhea.requester").post(Map.of("workflowId", workflowId,
                "input", input(BigDecimal.ONE), "businessKey", businessKey), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        String runId = started.body().path("id").asString();
        String reference = started.body().path("reference").asString();
        for (String search : List.of(reference, businessKey)) {
            Response page = api("olga.operator").get("/workspaces/{ws}/runs?businessKey=" + search, PURCHASING);
            assertThat(page.status()).as(page.toString()).isEqualTo(200);
            assertThat(page.body().path("items").size()).isEqualTo(1);
            assertThat(page.body().path("items").get(0).path("id").asString()).isEqualTo(runId);
        }
        Response partial = api("olga.operator").get("/workspaces/{ws}/runs?businessKey="
                + businessKey.substring(0, businessKey.length() - 1), PURCHASING);
        assertThat(partial.body().path("items").size()).isZero();
        Response foreign = api("fred.facilities").get("/workspaces/{ws}/runs?businessKey=" + reference, PURCHASING);
        assertThat(foreign.status()).isEqualTo(404);
    }

    @Test
    void releaseNotActiveAndNoActiveRelease() throws Exception {
        UUID workflowId = workflow();
        Response stale = api("rhea.requester").post(Map.of("workflowId", workflowId, "expectedReleaseId",
                UUID.randomUUID(), "input", input(BigDecimal.ONE)), UUID.randomUUID().toString(), "/workspaces/{ws}/runs",
                PURCHASING);
        assertThat(stale.code()).isEqualTo("RELEASE_NOT_ACTIVE");
        Response created = api("dana.designer").post(Map.of("key", "np-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Unpublished", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        Response none = api("rhea.requester").post(Map.of("workflowId", created.body().path("id").asString(),
                "input", input(BigDecimal.ONE)), UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(none.code()).isEqualTo("NO_ACTIVE_RELEASE");
    }

    // ---------------------------------------------------------------- helpers

    private String openTaskIn(UUID workspaceId, UUID runId, String node) throws Exception {
        JsonNode run = api("olga.operator").get("/workspaces/{ws}/runs/{id}", workspaceId, runId).body();
        for (JsonNode task : run.path("tasks")) {
            if (node.equals(task.path("nodeId").asString())) {
                return task.path("id").asString();
            }
        }
        throw new AssertionError("no task " + node);
    }

    private int runCount(UUID workflowId) {
        return jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :w").param("w", workflowId)
                .query(Integer.class).single();
    }

    private static List<String> runIds(JsonNode page) {
        return page.path("items").findValues("runId").stream().map(JsonNode::asString).toList();
    }

    private <T> List<Response> parallel(int n, List<T> inputs, ThrowingFunction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (T input : inputs) {
                futures.add(pool.submit(() -> action.apply(input)));
            }
            List<Response> results = new ArrayList<>();
            for (Future<Response> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private List<Response> parallel(int n, Callable<Response> action) throws Exception {
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            inputs.add(i);
        }
        return parallel(n, inputs, i -> action.call());
    }

    @FunctionalInterface
    interface ThrowingFunction<T> {
        Response apply(T input) throws Exception;
    }
}
