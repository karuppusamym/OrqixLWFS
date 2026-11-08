package com.orqix.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-O01, CG-O04 and CG-O05 (docs/build/S142) on PostgreSQL and Flowable: searchable request fields and error-code
 * filters, bulk operations by filter (dry run, confirmation, cap, per-item results, shrinking set, pause, resume,
 * cancel, permission loss) and the job dashboard.
 */
class BulkAndSearchTest extends RuntimeTestBase {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private BulkOperationWorker worker;

    @Autowired
    private BulkOperations bulk;

    @Autowired
    private RunStore runStore;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<String> grants = new ArrayList<>();

    @AfterEach
    void revokeGrants() throws Exception {
        for (String id : grants) {
            api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, id);
        }
        grants.clear();
        Stub.reset();
    }

    // ---------------------------------------------------------------- helpers

    private static Consumer<ObjectNode> searchable(String... keys) {
        return definition -> {
            for (String key : keys) {
                ((ObjectNode) definition.path("inputSchema").path("properties").path(key)).put("searchable", true);
            }
        };
    }

    private UUID startWith(UUID workflowId, String title, String costCenter, String amount) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", title);
        input.put("purpose", "Needed for the team");
        input.put("costCenter", costCenter);
        input.put("amount", new BigDecimal(amount));
        var started = api("rhea.requester").post(Map.of("workflowId", workflowId, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID id = UUID.fromString(started.body().path("id").asString());
        awaitLifecycle(PURCHASING, id, "waiting");
        return id;
    }

    private List<String> references(String query) throws Exception {
        var page = api("olga.operator").get("/workspaces/{ws}/runs?" + query, PURCHASING);
        assertThat(page.status()).as(page.toString()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        page.body().path("items").forEach(i -> ids.add(i.path("id").asString()));
        return ids;
    }

    private void grantBulk(String user) throws Exception {
        String subject = api(user).get("/me").body().path("subject").path("id").asString();
        var grant = api("wade.admin").post(Map.of("permission", "run.bulk-operate", "principal",
                Map.of("type", "subject", "value", subject)), "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(grant.status()).as(grant.toString()).isEqualTo(201);
        grants.add(grant.body().path("id").asString());
    }

    private JsonNode preview(String action, Map<String, Object> filter, Integer rate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("filter", filter);
        if (rate != null) {
            body.put("ratePerMinute", rate);
        }
        var previewed = api("olga.operator").post(body, "/workspaces/{ws}/bulk-operations/preview", PURCHASING);
        assertThat(previewed.status()).as(previewed.toString()).isEqualTo(201);
        return previewed.body();
    }

    private JsonNode execute(JsonNode op, String key) throws Exception {
        var executed = api("olga.operator").post(Map.of("confirmedCount", op.path("matchedCount").asInt(), "reason",
                "Supplier contract ended"), key, "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING,
                op.path("id").asString());
        assertThat(executed.status()).as(executed.toString()).isEqualTo(200);
        return executed.body();
    }

    private JsonNode get(JsonNode op) throws Exception {
        return api("olga.operator").get("/workspaces/{ws}/bulk-operations/{id}", PURCHASING, op.path("id").asString())
                .body();
    }

    private JsonNode drain(JsonNode op) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            worker.runDue();
            return !"running".equals(get(op).path("state").asString());
        });
        try {
            return get(op);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int audits(String action, String resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", resourceId).query(Integer.class).single();
    }

    // ---------------------------------------------------------------- CG-O04

    @Test
    void searchableFieldsAreIndexedAtStartAndOnCorrectionAndFilteredLikeRunReads() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, searchable("title", "costCenter", "amount"));
        UUID laptop = startWith(workflow, "Replacement laptop", "IT", "100");
        UUID chairs = startWith(workflow, "Office chairs", "OPS", "900");
        UUID monitor = startWith(workflow, "Replacement monitor", "HR", "1500");

        JsonNode fields = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/searchable-fields", PURCHASING,
                workflow).body().path("items");
        assertThat(fields.findValuesAsString("key")).containsExactly("title", "costCenter", "amount");
        assertThat(fields.findValuesAsString("kind")).containsExactly("text", "choice", "number");
        assertThat(jdbc.sql("SELECT count(*) FROM run_search_value WHERE run_id = :r").param("r", laptop)
                .query(Integer.class).single()).isEqualTo(3);

        String wf = "workflowId=" + workflow;
        assertThat(references(wf + "&field=costCenter:eq:it")).containsExactly(laptop.toString());
        assertThat(references(wf + "&field=amount:gte:900")).containsExactlyInAnyOrder(chairs.toString(),
                monitor.toString());
        assertThat(references(wf + "&field=amount:gte:900&field=title:prefix:replacement"))
                .containsExactly(monitor.toString());
        assertThat(references(wf + "&field=amount:lte:100")).containsExactly(laptop.toString());

        // Field conditions read request data: run.read alone lists runs but cannot filter by values.
        assertThat(api("vic.viewer").get("/workspaces/{ws}/runs?" + wf, PURCHASING).status()).isEqualTo(200);
        var viewer = api("vic.viewer").get("/workspaces/{ws}/runs?" + wf + "&field=costCenter:eq:it", PURCHASING);
        assertThat(viewer.status()).isEqualTo(403);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs?" + wf + "&field=costCenter:eq:it", PURCHASING)
                .status()).isEqualTo(403);
        var unknown = api("olga.operator").get("/workspaces/{ws}/runs?" + wf + "&field=purpose:eq:team", PURCHASING);
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.body().path("fieldErrors").get(0).path("pointer").asString()).isEqualTo("/field/0");
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs?" + wf + "&field=amount:prefix:1", PURCHASING)
                .status()).isEqualTo(422);
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs?field=amount:eq:100", PURCHASING).status())
                .isEqualTo(400);

        // A correction of the request data re-indexes it in the same transaction.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            RunStore.RunRow run = runStore.lock(PURCHASING, laptop).orElseThrow();
            ObjectNode corrected = ((ObjectNode) run.input()).deepCopy().put("costCenter", "HR");
            runStore.correctInputWhileActive(PURCHASING, laptop, run.version(), corrected);
        });
        assertThat(references(wf + "&field=costCenter:eq:it")).isEmpty();
        assertThat(references(wf + "&field=costCenter:eq:HR")).containsExactlyInAnyOrder(laptop.toString(),
                monitor.toString());
    }

    @Test
    void runsThatNeedAttentionAreFilteredByErrorCodeAndRetriedInBulk() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        Stub.fault(Stub.Mode.STATUS400, 2, 0);
        List<UUID> failed = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
            approve("mani.manager", PURCHASING, run, "manager");
            awaitLifecycle(PURCHASING, run, "needsAttention", "completed");
            failed.add(run);
        }
        JsonNode attention = run(PURCHASING, failed.get(0)).path("attention");
        assertThat(attention.path("reason").asString()).isNotBlank();
        String code = attention.path("errorCode").asString();
        assertThat(code).as("the failed step's error code is on the attention record").matches("^[A-Z][A-Z0-9_]+$");
        String wf = "workflowId=" + workflow;
        assertThat(references(wf + "&errorCode=" + code)).containsExactlyInAnyOrderElementsOf(
                failed.stream().map(UUID::toString).toList());
        assertThat(references(wf + "&attentionReason=" + attention.path("reason").asString())).hasSize(2);
        assertThat(references(wf + "&errorCode=NO_SUCH_CODE")).isEmpty();
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs?errorCode=lower", PURCHASING).status())
                .isEqualTo(400);

        Stub.reset();
        grantBulk("olga.operator");
        JsonNode op = preview("retry", Map.of("workflowId", workflow.toString(), "errorCode", code), 600);
        assertThat(op.path("matchedCount").asInt()).isEqualTo(2);
        JsonNode done = drain(execute(op, UUID.randomUUID().toString()));
        assertThat(done.path("state").asString()).isEqualTo("completed");
        assertThat(done.path("progress").path("succeeded").asInt()).as(done.toString()).isEqualTo(2);
        for (UUID run : failed) {
            awaitLifecycle(PURCHASING, run, "completed");
        }
        assertThat(audits("run.retry-requested", failed.get(0).toString())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CG-O01

    @Test
    void bulkCancelActsOnlyOnTheDryRunsRequestsAfterTheCountIsConfirmed() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            runs.add(startWith(workflow, "Laptop " + i, "IT", "100"));
        }
        Map<String, Object> filter = Map.of("workflowId", workflow.toString(), "lifecycle", "waiting");
        Map<String, Object> body = Map.of("action", "cancel", "filter", filter);
        // run.bulk-operate is grant-only: the operator role alone cannot run a dry run, nor a viewer.
        assertThat(api("olga.operator").post(body, "/workspaces/{ws}/bulk-operations/preview", PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("vic.viewer").post(body, "/workspaces/{ws}/bulk-operations/preview", PURCHASING).status())
                .isEqualTo(403);
        grantBulk("olga.operator");
        var unfiltered = api("olga.operator").post(Map.of("action", "cancel", "filter", Map.of("lifecycle", "waiting")),
                "/workspaces/{ws}/bulk-operations/preview", PURCHASING);
        assertThat(unfiltered.status()).isEqualTo(422);

        JsonNode op = preview("cancel", filter, 600);
        assertThat(op.path("state").asString()).isEqualTo("previewed");
        assertThat(op.path("matchedCount").asInt()).isEqualTo(3);
        assertThat(op.path("exceedsCap").asBoolean()).isFalse();
        assertThat(op.path("cap").asInt()).isEqualTo(1000);
        assertThat(op.path("sample").findValuesAsString("runId")).containsExactlyElementsOf(
                runs.stream().map(UUID::toString).toList());
        assertThat(audits("run.bulk-previewed", op.path("id").asString())).isEqualTo(1);
        for (UUID run : runs) {
            assertThat(lifecycle(PURCHASING, run)).as("a dry run changes nothing").isEqualTo("waiting");
        }
        // A request that matches only after the dry run is never added (doc 29 section 4 row 12).
        UUID later = startWith(workflow, "Later laptop", "IT", "100");

        String id = op.path("id").asString();
        var wrongCount = api("olga.operator").post(Map.of("confirmedCount", 4, "reason", "Contract ended"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING, id);
        assertThat(wrongCount.status()).isEqualTo(409);
        assertThat(wrongCount.code()).isEqualTo("BULK_COUNT_MISMATCH");
        assertThat(api("olga.operator").post(Map.of("confirmedCount", 3, "reason", "Contract ended"), null,
                "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING, id).status()).isEqualTo(400);
        assertThat(api("olga.operator").post(Map.of("confirmedCount", 3, "reason", " "), UUID.randomUUID().toString(),
                "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING, id).status()).isEqualTo(422);

        String key = UUID.randomUUID().toString();
        JsonNode running = execute(op, key);
        assertThat(running.path("state").asString()).isIn("running", "completed");
        var replay = api("olga.operator").post(Map.of("confirmedCount", 3, "reason", "Supplier contract ended"), key,
                "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING, id);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");

        JsonNode done = drain(op);
        assertThat(done.path("state").asString()).isEqualTo("completed");
        assertThat(done.path("progress").path("succeeded").asInt()).as(done.toString()).isEqualTo(3);
        assertThat(done.path("progress").path("pending").asInt()).isZero();
        for (UUID run : runs) {
            assertThat(lifecycle(PURCHASING, run)).isEqualTo("cancelled");
            assertThat(audits("run.cancelled", run.toString())).isEqualTo(1);
        }
        assertThat(lifecycle(PURCHASING, later)).isEqualTo("waiting");
        JsonNode events = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, runs.get(0)).body();
        JsonNode cancelled = null;
        for (JsonNode e : events.path("items")) {
            if ("RUN_CANCELLED".equals(e.path("type").asString())) {
                cancelled = e;
            }
        }
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.path("data").path("reason").asString()).startsWith("Supplier contract ended (bulk operation ");
        assertThat(audits("run.bulk-started", id)).isEqualTo(1);
        assertThat(audits("run.bulk-completed", id)).isEqualTo(1);
        var again = api("olga.operator").post(Map.of("confirmedCount", 3, "reason", "Again"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING, id);
        assertThat(again.code()).isEqualTo("BULK_OPERATION_STATE_CONFLICT");
        JsonNode items = api("olga.operator").get("/workspaces/{ws}/bulk-operations/{id}/items?state=succeeded",
                PURCHASING, id).body().path("items");
        assertThat(items).hasSize(3);
        assertThat(api("olga.operator").get("/workspaces/{ws}/bulk-operations", PURCHASING).body().path("items")
                .findValuesAsString("id")).contains(id);
    }

    @Test
    void requestsThatNoLongerMatchAreSkippedAndBatchesPauseResumeAndCancel() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            runs.add(startWith(workflow, "Chair " + i, "OPS", "50"));
        }
        grantBulk("olga.operator");
        JsonNode op = preview("cancel", Map.of("workflowId", workflow.toString(), "lifecycle", "waiting"), 600);
        // The second request ends on its own before the batch reaches it.
        int version = run(PURCHASING, runs.get(1)).path("version").asInt();
        assertThat(api("olga.operator").post(Map.of("reason", "Duplicate", "expectedVersion", version),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, runs.get(1)).status())
                .isEqualTo(200);
        JsonNode done = drain(execute(op, UUID.randomUUID().toString()));
        assertThat(done.path("progress").path("succeeded").asInt()).isEqualTo(2);
        assertThat(done.path("progress").path("skipped").asInt()).isEqualTo(1);
        JsonNode skipped = api("olga.operator").get("/workspaces/{ws}/bulk-operations/{id}/items?state=skipped",
                PURCHASING, op.path("id").asString()).body().path("items").get(0);
        assertThat(skipped.path("runId").asString()).isEqualTo(runs.get(1).toString());
        assertThat(skipped.path("errorCode").asString()).isEqualTo("NO_LONGER_MATCHES");

        // One request a minute: the first item runs, the others wait for their turn.
        List<UUID> slow = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            slow.add(startWith(workflow, "Desk " + i, "OPS", "50"));
        }
        JsonNode suspend = preview("suspend", Map.of("workflowId", workflow.toString(), "lifecycle", "waiting"), 1);
        assertThat(suspend.path("matchedCount").asInt()).isEqualTo(3);
        execute(suspend, UUID.randomUUID().toString());
        worker.runDue();
        JsonNode first = get(suspend);
        assertThat(first.path("progress").path("succeeded").asInt()).isEqualTo(1);
        assertThat(first.path("progress").path("pending").asInt()).isEqualTo(2);
        String sid = suspend.path("id").asString();
        assertThat(api("olga.operator").post(Map.of("expectedVersion", 1), "/workspaces/{ws}/bulk-operations/{id}/pause",
                PURCHASING, sid).code()).isEqualTo("BULK_OPERATION_STATE_CONFLICT");
        JsonNode paused = api("olga.operator").post(Map.of("expectedVersion", first.path("version").asInt()),
                "/workspaces/{ws}/bulk-operations/{id}/pause", PURCHASING, sid).body();
        assertThat(paused.path("state").asString()).isEqualTo("paused");
        jdbc.sql("UPDATE bulk_operation SET next_item_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(sid)).update();
        worker.runDue();
        assertThat(get(suspend).path("progress").path("succeeded").asInt()).as("paused: nothing runs").isEqualTo(1);
        JsonNode resumed = api("olga.operator").post(Map.of("expectedVersion", paused.path("version").asInt()),
                "/workspaces/{ws}/bulk-operations/{id}/resume", PURCHASING, sid).body();
        assertThat(resumed.path("state").asString()).isEqualTo("running");
        JsonNode cancelled = api("olga.operator").post(Map.of("expectedVersion", get(suspend).path("version").asInt(),
                "reason", "Enough"), "/workspaces/{ws}/bulk-operations/{id}/cancel", PURCHASING, sid).body();
        assertThat(cancelled.path("state").asString()).isEqualTo("cancelled");
        assertThat(cancelled.path("progress").path("succeeded").asInt()
                + cancelled.path("progress").path("cancelled").asInt()).isEqualTo(3);
        assertThat(cancelled.path("progress").path("cancelled").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(audits("run.bulk-paused", sid)).isEqualTo(1);
        assertThat(audits("run.bulk-resumed", sid)).isEqualTo(1);
        assertThat(audits("run.bulk-cancelled", sid)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM run_suspension WHERE run_id IN (:ids)").param("ids", slow)
                .query(Integer.class).single()).isEqualTo(cancelled.path("progress").path("succeeded").asInt());
    }

    @Test
    void theCapExpiryAndLostPermissionStopABatch() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        for (int i = 0; i < 3; i++) {
            startWith(workflow, "Phone " + i, "IT", "80");
        }
        grantBulk("olga.operator");
        BulkOperations target = AopTestUtils.getUltimateTargetObject(bulk);
        int cap = target.maxItems();
        JsonNode over;
        try {
            target.maxItems(2);
            over = preview("suspend", Map.of("workflowId", workflow.toString()), null);
        } finally {
            target.maxItems(cap);
        }
        assertThat(over.path("matchedCount").asInt()).isEqualTo(3);
        assertThat(over.path("exceedsCap").asBoolean()).isTrue();
        assertThat(over.path("sample")).isEmpty();
        var refused = api("olga.operator").post(Map.of("confirmedCount", 3, "reason", "Too many"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING,
                over.path("id").asString());
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("BULK_LIMIT_EXCEEDED");

        JsonNode stale = preview("suspend", Map.of("workflowId", workflow.toString()), null);
        jdbc.sql("UPDATE bulk_operation SET preview_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(stale.path("id").asString())).update();
        var expired = api("olga.operator").post(Map.of("confirmedCount", 3, "reason", "Late"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/bulk-operations/{id}/execute", PURCHASING,
                stale.path("id").asString());
        assertThat(expired.code()).isEqualTo("BULK_PREVIEW_EXPIRED");

        JsonNode slow = preview("suspend", Map.of("workflowId", workflow.toString()), 1);
        execute(slow, UUID.randomUUID().toString());
        worker.runDue();
        // The confirmer loses run.bulk-operate: the remaining items are cancelled, not run with stale rights.
        revokeGrants();
        jdbc.sql("UPDATE bulk_operation SET next_item_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(slow.path("id").asString())).update();
        worker.runDue();
        com.orqix.runtime.api.BulkDtos.Operation stopped = target.view(
                target.find(PURCHASING, UUID.fromString(slow.path("id").asString())).orElseThrow(), false);
        assertThat(stopped.state()).isEqualTo("cancelled");
        assertThat(stopped.progress().succeeded()).isEqualTo(1);
        assertThat(stopped.progress().cancelled()).isEqualTo(2);
        assertThat(audits("run.bulk-stopped", slow.path("id").asString())).isEqualTo(1);
        jdbc.sql("DELETE FROM run_suspension WHERE workspace_id = :ws AND run_id IN (SELECT run_id FROM bulk_operation_item"
                + " WHERE operation_id = :id)").param("ws", PURCHASING).param("id", UUID.fromString(slow.path("id")
                        .asString())).update();
    }

    // ---------------------------------------------------------------- CG-O05

    @Test
    void theJobDashboardShowsQueuesWorkersAndConnections() throws Exception {
        String topic = "dash-" + UUID.randomUUID().toString().substring(0, 8);
        UUID grant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO permission_grant (id, workspace_id, permission, principal_type, principal_value, created_at)
                VALUES (:id, :ws, 'job.work', 'idpGroup', 'orqix-purchasing-workers', now())
                """).param("id", grant).param("ws", PURCHASING).update();
        try {
            UUID workflow = publishPurchase(PURCHASING, definition -> {
                ArrayNode nodes = (ArrayNode) definition.path("nodes");
                nodes.add(MAPPER.readTree("{\"id\":\"check\",\"type\":\"externalTask\",\"label\":\"Check\",\"config\":{"
                        + "\"topic\":\"" + topic + "\",\"inputMapping\":{\"purpose\":\"/input/purpose\"},"
                        + "\"result\":[{\"name\":\"score\",\"type\":\"number\",\"required\":true}]}}"));
                ArrayNode transitions = (ArrayNode) definition.path("transitions");
                for (JsonNode t : transitions) {
                    if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                        ((ObjectNode) t).put("to", "check");
                    }
                }
                transitions.add(MAPPER.readTree("{\"id\":\"x1\",\"from\":\"check\",\"to\":\"manager\",\"on\":\"next\"}"));
            });
            for (int i = 0; i < 2; i++) {
                start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> queue(topic) != null
                    && queue(topic).path("available").asInt() == 2);
            JsonNode before = queue(topic);
            assertThat(before.path("workerLastSeenAt").isNull()).isTrue();
            assertThat(before.path("oldestAgeSeconds").asLong()).isGreaterThanOrEqualTo(0);
            var fetched = api("wally.worker").post(Map.of("workerId", "gateway-1", "topics", List.of(topic),
                    "maxJobs", 1, "leaseSeconds", 60), "/workspaces/{ws}/external-jobs/fetch-and-lock", PURCHASING);
            assertThat(fetched.status()).as(fetched.toString()).isEqualTo(200);
            JsonNode after = queue(topic);
            assertThat(after.path("available").asInt()).isEqualTo(1);
            assertThat(after.path("locked").asInt()).isEqualTo(1);
            assertThat(after.path("workerLastSeenAt").isNull()).isFalse();
            assertThat(after.path("workersSeenLastHour").asInt()).isEqualTo(1);

            JsonNode dashboard = api("olga.operator").get("/workspaces/{ws}/job-dashboard", PURCHASING).body();
            assertThat(dashboard.path("connections").findValuesAsString("connectionKey")).contains("procurement");
            assertThat(dashboard.has("pollChecks")).isTrue();
            assertThat(dashboard.has("packageTriggers")).isTrue();
            assertThat(dashboard.toString()).doesNotContain("Needed for the team").doesNotContain("Device failed");
            assertThat(api("vic.viewer").get("/workspaces/{ws}/job-dashboard", PURCHASING).status()).isEqualTo(403);
            assertThat(api("wally.worker").get("/workspaces/{ws}/job-dashboard", PURCHASING).status()).isIn(403, 404);
        } finally {
            jdbc.sql("DELETE FROM permission_grant WHERE id = :id").param("id", grant).update();
        }
    }

    private JsonNode queue(String topic) throws Exception {
        var dashboard = api("olga.operator").get("/workspaces/{ws}/job-dashboard", PURCHASING);
        assertThat(dashboard.status()).as(dashboard.toString()).isEqualTo(200);
        for (JsonNode q : dashboard.body().path("jobQueues")) {
            if (topic.equals(q.path("topic").asString())) {
                return q;
            }
        }
        return null;
    }
}
