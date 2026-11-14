package com.orqix.runtime;

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
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-E01 bounded loops and CG-E06 for each on any step (docs/build/S138) through the public API, the real engine and
 * PostgreSQL: visit numbers on tasks and effects, the limitReached path, separation of duties across visits, the run
 * path, and per-item calls with their own effect keys, sequential, in parallel batches and stopping early.
 */
class LoopsAndForEachRuntimeTest extends RuntimeTestBase {

    // ---------------------------------------------------------------- definitions

    /** Finance can send the request back to the manager at most {@code max} times, then it is rejected. */
    private static Consumer<ObjectNode> sendBack(int max) {
        return d -> {
            for (JsonNode t : d.path("transitions")) {
                if ("t8".equals(t.path("id").asString())) {
                    ((ObjectNode) t).put("to", "manager").put("maxTraversals", max).put("label", "Send back");
                }
            }
            ((ArrayNode) d.path("transitions")).addObject().put("id", "t12").put("from", "finance")
                    .put("to", "notifyRejected").put("on", "limitReached");
        };
    }

    /** After the order, the request goes back to the manager once (a second order, a second notice). */
    private static Consumer<ObjectNode> reorderOnce() {
        return d -> {
            for (JsonNode t : d.path("transitions")) {
                if ("t10".equals(t.path("id").asString())) {
                    ((ObjectNode) t).put("to", "manager").put("maxTraversals", 1);
                }
            }
            ((ArrayNode) d.path("transitions")).addObject().put("id", "t12").put("from", "notify")
                    .put("to", "fulfilled").put("on", "limitReached");
        };
    }

    /** The order call runs once per order line. */
    private static Consumer<ObjectNode> perLine(int max, String mode, Integer batchSize, String when) {
        return d -> {
            ObjectNode lines = ((ObjectNode) d.path("inputSchema").path("properties")).putObject("lines");
            lines.put("type", "array").put("title", "Order lines").put("minItems", 1).put("maxItems", max);
            ObjectNode row = lines.putObject("items");
            row.put("type", "object").put("additionalProperties", false);
            row.putArray("required").add("title").add("amount");
            ObjectNode props = row.putObject("properties");
            props.putObject("title").put("type", "string").put("title", "Item").put("minLength", 1).put("maxLength", 120);
            props.putObject("amount").put("type", "number").put("title", "Amount").put("minimum", 0.01)
                    .put("maximum", 1000000).put("multipleOf", 0.01);
            for (JsonNode n : d.path("nodes")) {
                if ("procure".equals(n.path("id").asString())) {
                    ObjectNode c = (ObjectNode) n.path("config");
                    ((ObjectNode) c.path("inputMapping")).remove(List.of("title", "amount"));
                    ObjectNode f = c.putObject("forEach");
                    f.put("itemsRef", "/input/lines").put("maxItems", max);
                    f.putObject("itemMapping").put("title", "title").put("amount", "amount");
                    f.putObject("collect").put("orderIds", "procurementId");
                    if (mode != null) {
                        f.put("mode", mode);
                    }
                    if (batchSize != null) {
                        f.put("batchSize", batchSize);
                    }
                    if (when != null) {
                        f.putObject("completion").put("when", when);
                    }
                }
            }
            ((ObjectNode) d.path("outputMapping")).removeAll();
        };
    }

    // ---------------------------------------------------------------- helpers

    private UUID startWith(UUID workflow, BigDecimal amount, int lines) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>(input(amount));
        if (lines > 0) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < lines; i++) {
                rows.add(Map.of("title", "Line " + (i + 1), "amount", new BigDecimal("10.00")));
            }
            input.put("lines", rows);
        }
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private void awaitOpenTask(UUID run, String node) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            fastForward(PURCHASING, run);
            return openTask(PURCHASING, run, node) != null;
        });
    }

    private void decideAs(String user, UUID run, String node, String action) throws Exception {
        awaitOpenTask(run, node);
        String task = openTask(PURCHASING, run, node).path("id").asString();
        Response claimed = claim(user, PURCHASING, task);
        assertThat(claimed.status()).as(claimed.toString()).isEqualTo(200);
        Response decided = decide(user, PURCHASING, task, action, "reject".equals(action) ? "Fix the quote" : null);
        assertThat(decided.status()).as(decided.toString()).isEqualTo(201);
    }

    private List<Integer> taskOccurrences(UUID run, String node) {
        return jdbc.sql("SELECT occurrence FROM task_context WHERE run_id = :run AND node_id = :node ORDER BY created_at")
                .param("run", run).param("node", node).query(Integer.class).list();
    }

    private JsonNode path(UUID run) throws Exception {
        return api("olga.operator").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, run).body();
    }

    private int events(UUID run, String type) {
        return jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = :type")
                .param("run", run).param("type", type).query(Integer.class).single();
    }

    // ---------------------------------------------------------------- CG-E01

    @Test
    void financeSendsTheRequestBackAndTheSecondVisitHasItsOwnTasks() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, sendBack(2));
        UUID run = startWith(workflow, new BigDecimal("9000"), 0);
        decideAs("mani.manager", run, "manager", "approve");
        decideAs("fin.finance", run, "finance", "reject");
        // Back at the manager: a new task, visit 2.
        decideAs("mani.manager", run, "manager", "approve");
        decideAs("fin.finance", run, "finance", "approve");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("outcome").asString()).as(detail.toString()).isEqualTo("Fulfilled");
        assertThat(taskOccurrences(run, "manager")).containsExactly(1, 2);
        assertThat(taskOccurrences(run, "finance")).containsExactly(1, 2);
        assertThat(Stub.orderCount()).isEqualTo(1);

        JsonNode path = path(run);
        assertThat(path.path("taken").toString()).contains("\"t8\"").doesNotContain("\"t12\"");
        JsonNode loop = path.path("loops").get(0);
        assertThat(loop.path("transitionId").asString()).isEqualTo("t8");
        assertThat(loop.path("maxTraversals").asInt()).isEqualTo(2);
        assertThat(loop.path("traversals").asInt()).isEqualTo(1);
        assertThat(loop.path("limitReached").asBoolean()).isFalse();
        List<Integer> managerVisits = new ArrayList<>();
        path.path("visited").forEach(v -> {
            if ("manager".equals(v.path("nodeId").asString())) {
                managerVisits.add(v.path("occurrence").asInt());
            }
        });
        assertThat(managerVisits).containsExactly(1, 2);
        for (JsonNode t : path.path("diagram").path("transitions")) {
            if ("t8".equals(t.path("id").asString())) {
                assertThat(t.path("maxTraversals").asInt()).isEqualTo(2);
            }
        }
    }

    @Test
    void afterTheLimitTheRequestTakesTheLimitReachedPath() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, sendBack(2));
        UUID run = startWith(workflow, new BigDecimal("9000"), 0);
        for (int visit = 1; visit <= 3; visit++) {
            decideAs("mani.manager", run, "manager", "approve");
            decideAs("fin.finance", run, "finance", "reject");
        }
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Rejected");
        assertThat(taskOccurrences(run, "manager")).containsExactly(1, 2, 3);
        assertThat(events(run, "LOOP_TRAVERSED")).isEqualTo(2);
        assertThat(events(run, "LOOP_LIMIT_REACHED")).isEqualTo(1);
        JsonNode path = path(run);
        assertThat(path.path("taken").toString()).contains("\"t8\"", "\"t12\"");
        assertThat(path.path("loops").get(0).path("traversals").asInt()).isEqualTo(2);
        assertThat(path.path("loops").get(0).path("limitReached").asBoolean()).isTrue();
        assertThat(Stub.orderCount()).isZero();
    }

    @Test
    void separationOfDutiesCountsTheDecisionsOfEveryVisit() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, sendBack(2).andThen(d -> {
            for (JsonNode n : d.path("nodes")) {
                if ("finance".equals(n.path("id").asString())) {
                    ((ObjectNode) n.path("config")).put("candidateGroupRef", "managers");
                    ((ObjectNode) n.path("config")).putArray("separateFrom").add("manager");
                }
            }
        }));
        UUID run = startWith(workflow, new BigDecimal("9000"), 0);
        decideAs("mani.manager", run, "manager", "approve");
        decideAs("max.manager", run, "finance", "reject");
        // Visit 2: max decides the manager step this time.
        decideAs("max.manager", run, "manager", "approve");
        awaitOpenTask(run, "finance");
        String task = openTask(PURCHASING, run, "finance").path("id").asString();
        // mani decided the manager step in visit 1, max in visit 2: neither may decide finance now.
        Response mani = claim("mani.manager", PURCHASING, task);
        assertThat(mani.status()).isEqualTo(403);
        assertThat(mani.body().path("code").asString()).isEqualTo("SEPARATION_OF_DUTIES");
        Response max = claim("max.manager", PURCHASING, task);
        assertThat(max.status()).isEqualTo(403);
        assertThat(max.body().path("code").asString()).isEqualTo("SEPARATION_OF_DUTIES");
    }

    @Test
    void aCallInsideTheLoopIsANewEffectOnEachVisit() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, reorderOnce());
        UUID run = startWith(workflow, new BigDecimal("1200"), 0);
        decideAs("mani.manager", run, "manager", "approve");
        decideAs("mani.manager", run, "manager", "approve");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(Stub.orderCount()).isEqualTo(2);
        List<Integer> occurrences = jdbc.sql("""
                SELECT occurrence FROM effect_journal WHERE run_id = :run AND node_id = 'procure' ORDER BY occurrence
                """).param("run", run).query(Integer.class).list();
        assertThat(occurrences).containsExactly(1, 2);
        assertThat(jdbc.sql("SELECT count(DISTINCT effect_key) FROM effect_journal WHERE run_id = :run")
                .param("run", run).query(Integer.class).single()).isEqualTo(2);
        // One notice per visit of the notification step.
        assertThat(notifications(PURCHASING, run, "workflowMessage")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CG-E06

    @Test
    void theCallRunsOncePerLineWithItsOwnEffectKey() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(30, null, null, null));
        UUID run = startWith(workflow, new BigDecimal("1200"), 3);
        decideAs("mani.manager", run, "manager", "approve");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(Stub.orderCount()).isEqualTo(3);
        List<String> nodes = jdbc.sql("SELECT node_id FROM effect_journal WHERE run_id = :run ORDER BY node_id")
                .param("run", run).query(String.class).list();
        assertThat(nodes).containsExactly("procure#i0", "procure#i1", "procure#i2");
        assertThat(jdbc.sql("SELECT count(*) FROM step_item_result WHERE run_id = :run AND succeeded")
                .param("run", run).query(Integer.class).single()).isEqualTo(3);

        JsonNode items = api("olga.operator").get("/workspaces/{ws}/runs/{id}/steps/procure/items", PURCHASING, run)
                .body();
        assertThat(items.path("items").size()).isEqualTo(3);
        assertThat(items.path("mode").asString()).isEqualTo("sequential");
        assertThat(items.path("items").get(2).path("itemIndex").asInt()).isEqualTo(2);
        // The requester sees their own request's items; an outsider does not even see the request.
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/steps/procure/items", PURCHASING, run)
                .status()).isEqualTo(200);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/runs/{id}/steps/procure/items", PURCHASING, run)
                .status()).isEqualTo(404);
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}/steps/manager/items", PURCHASING, run)
                .status()).isEqualTo(404);

        JsonNode forEach = path(run).path("forEach").get(0);
        assertThat(forEach.path("nodeId").asString()).isEqualTo("procure");
        assertThat(forEach.path("items").asInt()).isEqualTo(3);
        assertThat(forEach.path("completed").asInt()).isEqualTo(3);
        assertThat(forEach.path("done").asBoolean()).isTrue();
        String outputs = processVariable(run, "orqix_step_procure");
        assertThat(outputs).contains("\"itemCount\":3", "\"completedCount\":3", "\"stoppedEarly\":false", "\"orderIds\":[");
    }

    @Test
    void aRetriedItemIsNotSentTwice() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(30, null, null, null));
        Stub.fault(Stub.Mode.STATUS503, 1, 0);
        UUID run = startWith(workflow, new BigDecimal("1200"), 2);
        decideAs("mani.manager", run, "manager", "approve");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("completed");
        assertThat(Stub.orderCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :run").param("run", run)
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void parallelItemsAboveTheBatchSizeRunInBatches() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(60, "parallel", 10, null));
        UUID run = startWith(workflow, new BigDecimal("1200"), 27);
        decideAs("mani.manager", run, "manager", "approve");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("completed");
        assertThat(Stub.orderCount()).isEqualTo(27);
        assertThat(jdbc.sql("SELECT count(DISTINCT node_id) FROM effect_journal WHERE run_id = :run")
                .param("run", run).query(Integer.class).single()).isEqualTo(27);
        assertThat(processVariable(run, "orqix_step_procure")).contains("\"itemCount\":27", "\"completedCount\":27");
        assertThat(path(run).path("forEach").get(0).path("parallel").asBoolean()).isTrue();
    }

    @Test
    void theStepStopsAtTheFirstSuccess() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(30, null, null, "firstSuccess"));
        UUID run = startWith(workflow, new BigDecimal("1200"), 4);
        decideAs("mani.manager", run, "manager", "approve");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("completed");
        assertThat(Stub.orderCount()).isEqualTo(1);
        assertThat(processVariable(run, "orqix_step_procure")).contains("\"itemCount\":4", "\"completedCount\":1",
                "\"stoppedEarly\":true");
    }

    @Test
    void aWorkerStepRunsOneJobPerItemOneAfterTheOther() throws Exception {
        String topic = "line-check-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO permission_grant (id, workspace_id, permission, principal_type, principal_value, created_at)
                VALUES (:id, :ws, 'job.work', 'idpGroup', 'orqix-purchasing-workers', now()) ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("ws", PURCHASING).update();
        try {
            UUID workflow = publishPurchase(PURCHASING, perLine(30, null, null, null).andThen(d -> {
                ObjectNode check = ((ArrayNode) d.path("nodes")).addObject();
                check.put("id", "check").put("type", "externalTask").put("label", "Check each line");
                ObjectNode c = check.putObject("config");
                c.put("topic", topic);
                c.putObject("inputMapping");
                c.putArray("result").addObject().put("name", "ok").put("type", "boolean").put("required", true);
                ObjectNode f = c.putObject("forEach");
                f.put("itemsRef", "/input/lines").put("maxItems", 30);
                f.putObject("itemMapping").put("item", "title");
                f.putObject("collect").put("checks", "ok");
                for (JsonNode t : d.path("transitions")) {
                    if ("start".equals(t.path("from").asString())) {
                        ((ObjectNode) t).put("to", "check");
                    }
                }
                ((ArrayNode) d.path("transitions")).addObject().put("id", "x1").put("from", "check")
                        .put("to", "manager").put("on", "next");
            }));
            UUID run = startWith(workflow, new BigDecimal("1200"), 3);
            for (int i = 0; i < 3; i++) {
                Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                        jdbc.sql("SELECT count(*) FROM external_job WHERE run_id = :run AND status = 'available'")
                                .param("run", run).query(Integer.class).single() == 1);
                Response fetched = api("wally.worker").post(Map.of("workerId", "w", "topics", List.of(topic),
                        "maxJobs", 5, "leaseSeconds", 60), "/workspaces/{ws}/external-jobs/fetch-and-lock", PURCHASING);
                JsonNode jobs = fetched.body().path("items");
                assertThat(jobs).as("one item at a time").hasSize(1);
                assertThat(jobs.get(0).path("input").path("item").asString()).isEqualTo("Line " + (i + 1));
                Response done = api("wally.worker").post(Map.of("workerId", "w", "fence", jobs.get(0).path("fence").asInt(),
                        "result", Map.of("ok", i != 1)), UUID.randomUUID().toString(),
                        "/workspaces/{ws}/external-jobs/{id}/complete", PURCHASING, jobs.get(0).path("id").asString());
                assertThat(done.status()).as(done.toString()).isEqualTo(204);
            }
            awaitOpenTask(run, "manager");
            assertThat(processVariable(run, "orqix_step_check")).contains("\"checks\":[true,false,true]",
                    "\"itemCount\":3");
        } finally {
            jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND permission = 'job.work'")
                    .param("ws", PURCHASING).update();
        }
    }

    private String processVariable(UUID run, String name) {
        return jdbc.sql("""
                SELECT coalesce(v.text_, '') FROM act_hi_varinst v
                JOIN workflow_run r ON r.engine_process_instance_id = v.proc_inst_id_
                WHERE r.id = :run AND v.name_ = :name
                """).param("run", run).param("name", name).query(String.class).single();
    }
}
