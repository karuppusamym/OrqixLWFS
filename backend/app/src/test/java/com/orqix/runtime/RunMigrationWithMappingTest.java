package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-O02 general migration with a step mapping (docs/build/S143) on PostgreSQL and Flowable through the public API:
 * a renamed approval, a changed history checked for missing data, parallel branches, a parent held by its child
 * request, and a batch with a dry run, a refused request and suspension during the move.
 */
class RunMigrationWithMappingTest extends RuntimeTestBase {

    private UUID release(UUID workflow, Consumer<ObjectNode> patch) throws Exception {
        JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body();
        int revision = draft.path("revision").asInt();
        ObjectNode definition = (ObjectNode) draft.path("definition");
        patch.accept(definition);
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r" + revision + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response published = api("paul.publisher").post(
                Map.of("draftRevision", saved.body().path("revision").asInt(), "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return UUID.fromString(published.body().path("id").asString());
    }

    /** Renames step {@code from} to {@code to} everywhere in the definition. */
    private static Consumer<ObjectNode> rename(String from, String to, String label) {
        return d -> {
            for (JsonNode node : d.path("nodes")) {
                if (from.equals(node.path("id").asString())) {
                    ((ObjectNode) node).put("id", to).put("label", label);
                }
            }
            for (JsonNode t : d.path("transitions")) {
                for (String end : List.of("from", "to")) {
                    if (from.equals(t.path(end).asString())) {
                        ((ObjectNode) t).put(end, to);
                    }
                }
            }
            d.remove("uiLayout");
        };
    }

    private UUID startRun(UUID workflow, String amount) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal(amount)).path("id").asString());
    }

    private int version(UUID run) throws Exception {
        return run(PURCHASING, run).path("version").asInt();
    }

    private Response suspend(UUID run) throws Exception {
        return api("olga.operator").post(Map.of("reason", "Moving with a mapping", "expectedVersion", version(run)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, run);
    }

    private Response resume(UUID run) throws Exception {
        return api("olga.operator").post(Map.of("reason", "Moved", "expectedVersion", version(run)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/resume", PURCHASING, run);
    }

    private JsonNode plan(UUID run, UUID target, List<Map<String, String>> mappings) throws Exception {
        Response r = api("olga.operator").post(Map.of("targetReleaseId", target, "mappings", mappings),
                "/workspaces/{ws}/runs/{id}/migration-plan", PURCHASING, run);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    private Response migrate(String user, UUID run, UUID target, List<Map<String, String>> mappings, int version,
            String key) throws Exception {
        return api(user).post(Map.of("targetReleaseId", target, "mappings", mappings, "reason",
                "Line manager step renamed", "expectedVersion", version), key,
                "/workspaces/{ws}/runs/{id}/migrate-with-mapping", PURCHASING, run);
    }

    private static List<String> codes(JsonNode findings) {
        List<String> codes = new java.util.ArrayList<>();
        findings.forEach(f -> codes.add(f.path("code").asString()));
        return codes;
    }

    private long count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    @Test
    void aWaitingApprovalContinuesUnderItsNewIdAndTheRequestFinishesOnTheNewRelease() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = startRun(workflow, "6000");
        String taskBefore = openTask(PURCHASING, run, "manager").path("id").asString();
        UUID r2 = release(workflow, rename("manager", "lineManager", "Line manager"));

        // S38 refuses: the step is gone. The dry run suggests the mapping and is not ready until suspended.
        JsonNode unmapped = plan(run, r2, List.of());
        assertThat(unmapped.path("ready").asBoolean()).isFalse();
        assertThat(codes(unmapped.path("findings"))).containsExactly("STEP_MISSING");
        assertThat(unmapped.path("suggestedMappings").get(0).path("to").asString()).isEqualTo("lineManager");
        List<Map<String, String>> mapping = List.of(Map.of("from", "manager", "to", "lineManager"));
        Response early = migrate("olga.operator", run, r2, mapping, version(run), UUID.randomUUID().toString());
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().path("code").asString()).isEqualTo("RUN_NOT_SUSPENDED");

        assertThat(suspend(run).status()).isEqualTo(200);
        JsonNode mapped = plan(run, r2, mapping);
        assertThat(mapped.path("allowed").asBoolean()).as(mapped.toString()).isTrue();
        assertThat(mapped.path("steps").get(0).path("action").asString()).isEqualTo("rename");
        // Access, stale version and a release of another workflow.
        int version = version(run);
        assertThat(migrate("rhea.requester", run, r2, mapping, version, UUID.randomUUID().toString()).status())
                .isEqualTo(403);
        assertThat(migrate("olga.operator", run, r2, mapping, version + 5, UUID.randomUUID().toString()).status())
                .isEqualTo(409);
        assertThat(migrate("olga.operator", run, UUID.randomUUID(), mapping, version, UUID.randomUUID().toString())
                .status()).isEqualTo(404);

        String key = UUID.randomUUID().toString();
        Response migrated = migrate("olga.operator", run, r2, mapping, version, key);
        assertThat(migrated.status()).as(migrated.toString()).isEqualTo(200);
        assertThat(migrated.body().path("release").path("releaseNumber").asInt()).isEqualTo(2);
        assertThat(migrated.body().path("currentSteps")).hasSize(1);
        assertThat(migrated.body().path("currentSteps").get(0).path("nodeId").asString()).isEqualTo("lineManager");
        assertThat(openTask(PURCHASING, run, "manager")).isNull();
        JsonNode moved = openTask(PURCHASING, run, "lineManager");
        assertThat(moved).isNotNull();
        assertThat(moved.path("label").asString()).isEqualTo("Line manager");
        // The open task is carried over (same engine task, so a claim is kept), never duplicated.
        assertThat(moved.path("id").asString()).isEqualTo(taskBefore);
        assertThat(count("SELECT count(*) FROM task_context WHERE run_id = :id AND status = 'open'", run)).isEqualTo(1);
        Response replay = migrate("olga.operator", run, r2, mapping, version, key);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM run_event WHERE run_id = :id AND type = 'RUN_MIGRATED'", run)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata->>'mapped' FROM audit_event WHERE resource_id = :id AND action = 'run.migrated'")
                .param("id", run.toString()).query(String.class).single()).isEqualTo("1");
        assertThat(jdbc.sql("SELECT data->'mapped'->0->>'to' FROM run_event WHERE run_id = :id AND type = 'RUN_MIGRATED'")
                .param("id", run).query(String.class).single()).isEqualTo("lineManager");

        assertThat(resume(run).status()).isEqualTo(200);
        approve("mani.manager", PURCHASING, run, "lineManager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
        approve("fin.finance", PURCHASING, run, "finance");
        awaitLifecycle(PURCHASING, run, "completed");
    }

    private static final String CHECK = """
            {"id":"check","type":"calculate","label":"Size","config":{"outputs":[
              {"key":"band","expression":"if input.amount >= 5000 then \\"big\\" else \\"small\\"","type":"string"}]}}""";

    /** Inserts the calculation {@code check} before the manager and lets the route read it. */
    private Consumer<ObjectNode> addCheck() {
        return d -> {
            ((ArrayNode) d.path("nodes")).add(json.parse(CHECK));
            for (JsonNode t : d.path("transitions")) {
                if ("start".equals(t.path("from").asString())) {
                    ((ObjectNode) t).put("to", "check");
                }
                if ("t5".equals(t.path("id").asString())) {
                    ((ObjectNode) t).set("condition", json.parse(
                            "{\"fieldRef\":\"/steps/check/band\",\"operator\":\"eq\",\"value\":\"big\"}"));
                }
            }
            ((ArrayNode) d.path("transitions")).add(json.parse(
                    "{\"id\":\"tc\",\"from\":\"check\",\"to\":\"manager\",\"on\":\"next\"}"));
            d.remove("uiLayout");
        };
    }

    @Test
    void aChangedHistoryMovesOnlyWhenEveryStepAheadFindsItsData() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = startRun(workflow, "6000");
        UUID readsNewData = release(workflow, addCheck());
        // The draft already has the calculation: the next release only routes on the decision again.
        UUID addsUnreadStep = release(workflow, d -> {
            for (JsonNode t : d.path("transitions")) {
                if ("t5".equals(t.path("id").asString())) {
                    ((ObjectNode) t).set("condition", json.parse("{\"fieldRef\":\"/steps/amountPolicy/route\","
                            + "\"operator\":\"eq\",\"value\":\"financeReview\"}"));
                }
            }
        });
        assertThat(suspend(run).status()).isEqualTo(200);

        JsonNode refused = plan(run, readsNewData, List.of());
        assertThat(codes(refused.path("findings"))).containsExactly("DATA_MISSING");
        assertThat(refused.path("findings").get(0).path("nodeId").asString()).isEqualTo("route");
        Response no = migrate("olga.operator", run, readsNewData, List.of(), version(run), UUID.randomUUID().toString());
        assertThat(no.status()).isEqualTo(409);
        assertThat(no.body().path("code").asString()).isEqualTo("RUN_NOT_MIGRATABLE");
        assertThat(no.body().path("findings").get(0).path("code").asString()).isEqualTo("DATA_MISSING");

        // S38 refuses the release that only adds an upstream step; the mapped migration accepts it.
        JsonNode options = api("olga.operator").get("/workspaces/{ws}/runs/{id}/migration-options", PURCHASING, run).body();
        for (JsonNode option : options.path("options")) {
            if (addsUnreadStep.toString().equals(option.path("release").path("id").asString())) {
                assertThat(option.path("findings").get(0).path("code").asString()).isEqualTo("HISTORY_CHANGED");
            }
        }
        Response ok = migrate("olga.operator", run, addsUnreadStep, List.of(), version(run), UUID.randomUUID().toString());
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(ok.body().path("release").path("releaseNumber").asInt()).isEqualTo(3);
        assertThat(resume(run).status()).isEqualTo(200);
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
    }

    private void graph(ObjectNode d, String nodes, String transitions) {
        d.set("nodes", json.parse(nodes));
        d.set("transitions", json.parse(transitions));
        d.set("decisions", json.parse("[]"));
        d.set("outputMapping", json.object());
        d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        d.set("dependencies", json.parse("{\"connections\":[]}"));
        d.remove("uiLayout");
    }

    private static final String START = "{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}}";
    private static final String END = "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Completed\",\"outcomeKind\":\"success\"}}";
    private static final String REJECT = "{\"id\":\"reject\",\"type\":\"end\",\"label\":\"Rejected\",\"config\":{\"businessOutcome\":\"Rejected\",\"outcomeKind\":\"rejected\"}}";

    private static String approval(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"approval\",\"label\":\"Review " + id
                + "\",\"config\":{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true}}";
    }

    private static String flow(String id, String from, String to, String on) {
        return "{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"on\":\"" + on + "\"}";
    }

    private static final String SPLIT = "{\"id\":\"split\",\"type\":\"parallelSplit\",\"label\":\"Together\",\"config\":{\"joinRef\":\"join\"}}";
    private static final String JOIN = "{\"id\":\"join\",\"type\":\"parallelJoin\",\"label\":\"All done\",\"config\":{}}";

    @Test
    void parallelBranchesMoveTogetherAndTheRequestFollowsTheNewSteps() throws Exception {
        String flows = String.join(",", flow("t1", "start", "split", "next"), flow("t2", "split", "one", "next"),
                flow("t3", "split", "two", "next"), flow("t4", "one", "join", "approved"),
                flow("t5", "two", "join", "approved"), flow("t6", "one", "reject", "rejected"),
                flow("t7", "two", "reject", "rejected"));
        UUID workflow = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + SPLIT + "," + approval("one") + ","
                + approval("two") + "," + JOIN + "," + END + "," + REJECT + "]",
                "[" + flows + "," + flow("t8", "join", "end", "next") + "]"));
        UUID run = startRun(workflow, "10");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "two") != null);
        // Release 2 adds a final review after the join.
        UUID r2 = release(workflow, d -> graph(d, "[" + START + "," + SPLIT + "," + approval("one") + ","
                + approval("two") + "," + JOIN + "," + approval("final") + "," + END + "," + REJECT + "]",
                "[" + flows + "," + flow("t8", "join", "final", "next") + "," + flow("t9", "final", "end", "approved")
                        + "," + flow("t10", "final", "reject", "rejected") + "]"));

        JsonNode s38 = api("olga.operator").get("/workspaces/{ws}/runs/{id}/migration-options", PURCHASING, run).body();
        assertThat(s38.path("options").get(0).path("findings").get(0).path("code").asString()).isEqualTo("STEP_COUNT");
        assertThat(suspend(run).status()).isEqualTo(200);
        JsonNode plan = plan(run, r2, List.of());
        assertThat(plan.path("allowed").asBoolean()).as(plan.toString()).isTrue();
        assertThat(plan.path("steps")).hasSize(2);
        Response migrated = migrate("olga.operator", run, r2, List.of(), version(run), UUID.randomUUID().toString());
        assertThat(migrated.status()).as(migrated.toString()).isEqualTo(200);
        assertThat(resume(run).status()).isEqualTo(200);
        approve("mani.manager", PURCHASING, run, "one");
        approve("max.manager", PURCHASING, run, "two");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "final") != null);
        approve("mani.manager", PURCHASING, run, "final");
        awaitLifecycle(PURCHASING, run, "completed");
    }

    @Test
    void aParentWaitingForItsChildRequestMovesWithoutSuspension() throws Exception {
        UUID childWf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + approval("review") + "," + END
                + "," + REJECT + "]", "[" + flow("a", "start", "review", "next") + "," + flow("b", "review", "end",
                        "approved") + "," + flow("c", "review", "reject", "rejected") + "]"));
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, childWf).body().path("key")
                .asString();
        String child = "{\"id\":\"child\",\"type\":\"startWorkflow\",\"label\":\"Purchase\",\"config\":{\"workflowRef\":\""
                + key + "\",\"inputMapping\":{\"title\":\"/input/title\",\"purpose\":\"/input/purpose\",\"amount\":"
                + "\"/input/amount\",\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{}}}";
        UUID parentWf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + child + "," + END + "]",
                "[" + flow("t1", "start", "child", "next") + "," + flow("t2", "child", "end", "next") + "]"));
        UUID parent = startRun(parentWf, "10");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> count(
                "SELECT count(*) FROM workflow_run WHERE parent_run_id = :id", parent) == 1);
        UUID childRun = jdbc.sql("SELECT id FROM workflow_run WHERE parent_run_id = :id").param("id", parent)
                .query(UUID.class).single();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, childRun, "review") != null);
        UUID r2 = release(parentWf, d -> graph(d, "[" + START + "," + child + "," + approval("after") + "," + END + ","
                + REJECT + "]", "[" + flow("t1", "start", "child", "next") + "," + flow("t2", "child", "after", "next")
                        + "," + flow("t3", "after", "end", "approved") + "," + flow("t4", "after", "reject", "rejected")
                        + "]"));

        JsonNode plan = plan(parent, r2, List.of());
        assertThat(plan.path("ready").asBoolean()).as(plan.toString()).isTrue();
        assertThat(plan.path("hold").asString()).isEqualTo("childRequests");
        // The child itself follows its parent and is not moved on its own.
        UUID childRelease = UUID.fromString(run(PURCHASING, childRun).path("release").path("id").asString());
        JsonNode childPlan = plan(childRun, childRelease, List.of());
        assertThat(childPlan.path("ready").asBoolean()).isFalse();
        assertThat(childPlan.path("notReadyReason").asString()).contains("parent");
        Response migrated = migrate("olga.operator", parent, r2, List.of(), version(parent), UUID.randomUUID().toString());
        assertThat(migrated.status()).as(migrated.toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT data->>'heldBy' FROM run_event WHERE run_id = :id AND type = 'RUN_MIGRATED'")
                .param("id", parent).query(String.class).single()).isEqualTo("childRequests");

        approve("mani.manager", PURCHASING, childRun, "review");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, parent, "after") != null);
        approve("mani.manager", PURCHASING, parent, "after");
        awaitLifecycle(PURCHASING, parent, "completed");
    }

    @Test
    void aBatchPreviewsEachRequestThenMovesTheAllowedOnesAndKeepsTheRefusal() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID a = startRun(workflow, "6000");
        UUID b = startRun(workflow, "7000");
        UUID atFinance = startRun(workflow, "8000");
        approve("mani.manager", PURCHASING, atFinance, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, atFinance, "finance") != null);
        UUID r1 = UUID.fromString(run(PURCHASING, a).path("release").path("id").asString());
        UUID r2 = release(workflow, rename("manager", "lineManager", "Line manager").andThen(d -> {
            for (JsonNode node : d.path("nodes")) {
                if ("finance".equals(node.path("id").asString())) {
                    ((ObjectNode) node.path("config")).put("dueAfterHours", 72);
                }
            }
        }));
        List<Map<String, String>> mapping = List.of(Map.of("from", "manager", "to", "lineManager"));
        List<UUID> ids = List.of(a, b, atFinance);

        Response preview = api("olga.operator").post(Map.of("sourceReleaseId", r1, "targetReleaseId", r2, "mappings",
                mapping, "runIds", ids, "suspendDuring", true), "/workspaces/{ws}/migration-batches/preview", PURCHASING);
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat(preview.body().path("total").asInt()).isEqualTo(3);
        assertThat(preview.body().path("allowed").asInt()).isEqualTo(2);
        assertThat(preview.body().path("items").get(2).path("findings").get(0).path("code").asString())
                .isEqualTo("STEP_CHANGED");
        assertThat(preview.body().path("items").get(0).path("hold").asString()).isEqualTo("suspendDuring");
        // Without runIds, the active requests of the release are listed (at least these three).
        Response all = api("olga.operator").post(Map.of("sourceReleaseId", r1, "targetReleaseId", r2, "mappings",
                mapping), "/workspaces/{ws}/migration-batches/preview", PURCHASING);
        assertThat(all.body().path("total").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(api("rhea.requester").post(Map.of("sourceReleaseId", r1, "targetReleaseId", r2),
                "/workspaces/{ws}/migration-batches/preview", PURCHASING).status()).isEqualTo(403);

        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("sourceReleaseId", r1, "targetReleaseId", r2,
                "mappings", mapping, "runIds", ids, "reason", "Rename the manager step", "suspendDuring", true,
                "expectedCount", 2));
        assertThat(api("olga.operator").post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/migration-batches",
                PURCHASING).status()).isEqualTo(422);
        body.put("expectedCount", 3);
        String key = UUID.randomUUID().toString();
        Response batch = api("olga.operator").post(body, key, "/workspaces/{ws}/migration-batches", PURCHASING);
        assertThat(batch.status()).as(batch.toString()).isEqualTo(200);
        assertThat(batch.body().path("state").asString()).isEqualTo("completed");
        assertThat(batch.body().path("migrated").asInt()).isEqualTo(2);
        assertThat(batch.body().path("refused").asInt()).isEqualTo(1);
        JsonNode refusedItem = batch.body().path("items").get(2);
        assertThat(refusedItem.path("state").asString()).isEqualTo("refused");
        assertThat(refusedItem.path("findings").get(0).path("code").asString()).isEqualTo("STEP_CHANGED");
        // Moved requests are on release 2 and running again; the refused one is untouched and not left suspended.
        for (UUID id : List.of(a, b)) {
            JsonNode detail = run(PURCHASING, id);
            assertThat(detail.path("release").path("id").asString()).isEqualTo(r2.toString());
            assertThat(detail.path("suspension").isMissingNode() || detail.path("suspension").isNull()).isTrue();
            assertThat(openTask(PURCHASING, id, "lineManager")).isNotNull();
        }
        JsonNode untouched = run(PURCHASING, atFinance);
        assertThat(untouched.path("release").path("id").asString()).isEqualTo(r1.toString());
        assertThat(untouched.path("suspension").isMissingNode() || untouched.path("suspension").isNull()).isTrue();

        Response replay = api("olga.operator").post(body, key, "/workspaces/{ws}/migration-batches", PURCHASING);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("id").asString()).isEqualTo(batch.body().path("id").asString());
        Response read = api("olga.operator").get("/workspaces/{ws}/migration-batches/{id}", PURCHASING,
                batch.body().path("id").asString());
        assertThat(read.body().path("items")).hasSize(3);
        assertThat(count("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action LIKE 'run.migration-batch.%'",
                batch.body().path("id").asString())).isEqualTo(2);
        approve("mani.manager", PURCHASING, a, "lineManager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, a, "finance") != null);
    }
}
