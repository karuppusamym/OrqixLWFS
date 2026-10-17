package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** EX06 slice on PostgreSQL and Flowable: inclusive gateways and several-approver approvals (docs/build/S51). */
class InclusiveAndMultiInstanceTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // ---------------------------------------------------------------- several approvers

    /** The finance approval goes to the managers group and needs {@code approvers} different managers. */
    private static Consumer<ObjectNode> severalFinanceApprovers(int approvers) {
        return definition -> {
            for (JsonNode node : definition.path("nodes")) {
                if ("finance".equals(node.path("id").asString())) {
                    ObjectNode config = (ObjectNode) node.path("config");
                    config.put("candidateGroupRef", "managers");
                    config.putObject("multiInstance").put("approvers", approvers);
                }
            }
        };
    }

    private UUID runAtFinance(int approvers) throws Exception {
        UUID workflow = publishPurchase(PURCHASING, severalFinanceApprovers(approvers));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("6000")).path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openFinanceTasks(run).size() == approvers);
        return run;
    }

    private List<String> openFinanceTasks(UUID run) {
        return jdbc.sql("SELECT engine_task_id FROM task_context WHERE run_id = :run AND node_id = 'finance' AND status = 'open' ORDER BY engine_task_id")
                .param("run", run).query(String.class).list();
    }

    @Test
    void allApproversMustApproveAndNobodyMayDecideTwoOfTheTasks() throws Exception {
        UUID run = runAtFinance(2);
        assertThat(run(PURCHASING, run).path("currentSteps").size()).isEqualTo(2);
        List<String> tasks = openFinanceTasks(run);

        assertThat(claim("mani.manager", PURCHASING, tasks.get(0)).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, tasks.get(0), "approve", null).status()).isEqualTo(201);
        // One task is decided; the request keeps waiting for the other approver.
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(openFinanceTasks(run)).containsExactly(tasks.get(1));
        assertThat(run(PURCHASING, run).path("currentSteps").size()).isEqualTo(1);

        // The same person can neither claim nor see the second task in their queue.
        Response refused = claim("mani.manager", PURCHASING, tasks.get(1));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().path("code").asString()).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING).body().toString())
                .doesNotContain(tasks.get(1));
        assertThat(api("max.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING).body().toString())
                .contains(tasks.get(1));

        assertThat(claim("max.manager", PURCHASING, tasks.get(1)).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, tasks.get(1), "approve", null).status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(jdbc.sql("SELECT count(*) FROM task_decision WHERE run_id = :run AND node_id = 'finance'")
                .param("run", run).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT outcome_kind FROM workflow_run WHERE id = :run").param("run", run)
                .query(String.class).single()).isEqualTo("success");
    }

    @Test
    void theDatabaseKeepsTwoDecisionsOfOneStepApartEvenWhenTheChecksAreBypassed() throws Exception {
        UUID run = runAtFinance(2);
        List<String> tasks = openFinanceTasks(run);
        assertThat(claim("mani.manager", PURCHASING, tasks.get(0)).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, tasks.get(0), "approve", null).status()).isEqualTo(201);
        UUID mani = jdbc.sql("SELECT actor_subject_id FROM task_decision WHERE run_id = :run AND node_id = 'finance'").param("run", run)
                .query(UUID.class).single();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO task_decision (id, workspace_id, engine_task_id, run_id, node_id, actor_subject_id, action,
                                           policy_flags, decided_at, distinct_actor)
                VALUES (:id, :ws, :task, :run, 'finance', :actor, 'approve', '[]'::jsonb, now(), true)
                """).param("id", UUID.randomUUID()).param("ws", PURCHASING).param("task", tasks.get(1)).param("run", run)
                .param("actor", mani).update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void theFirstRejectionRejectsTheStepAndCancelsTheOtherApprovers() throws Exception {
        UUID run = runAtFinance(3);
        List<String> tasks = openFinanceTasks(run);
        assertThat(claim("mani.manager", PURCHASING, tasks.get(0)).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, tasks.get(0), "reject", "Over budget").status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(jdbc.sql("SELECT outcome_kind FROM workflow_run WHERE id = :run").param("run", run)
                .query(String.class).single()).isEqualTo("rejected");
        assertThat(openFinanceTasks(run)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :run AND node_id = 'finance' AND status = 'cancelled'")
                .param("run", run).query(Long.class).single()).isEqualTo(2L);
        assertThat(run(PURCHASING, run).path("currentSteps").size()).isZero();
        // No engine task is left behind for the cancelled approvers.
        assertThat(managementService.createJobQuery().processInstanceId(processInstance(PURCHASING, run)).count()).isZero();
        // The cancelled tasks can no longer be decided.
        Response late = decide("max.manager", PURCHASING, tasks.get(1), "approve", null);
        assertThat(late.status()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void aRequestWaitingAtSeveralApproversCannotBeMovedToAnotherRelease() throws Exception {
        UUID run = runAtFinance(2);
        // Compatibility is decided on the two release definitions; the source step carries multiInstance.
        JsonNode source = MAPPER.readTree(jdbc.sql("SELECT definition::text FROM workflow_release WHERE id = (SELECT release_id FROM workflow_run WHERE id = :run)")
                .param("run", run).query(String.class).single());
        var findings = com.orqix.runtime.internal.ReleaseCompatibility.check(source, source, List.of("finance"));
        assertThat(findings).extracting(f -> f.code()).contains("MULTI_INSTANCE");
    }

    // ---------------------------------------------------------------- inclusive gateway

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    private static String note(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"notification\",\"label\":\"" + id
                + "\",\"config\":{\"channel\":\"inApp\",\"recipient\":\"initiator\",\"message\":\"Hello\"}}";
    }

    /** manager -> review (inclusive) -> noteBig (5,000 or more) / noteMid (1,000 or more) / noteStd (default) -> joined -> amountPolicy. */
    private static Consumer<ObjectNode> inclusive() {
        return d -> {
            ArrayNode nodes = (ArrayNode) d.path("nodes");
            nodes.add(n("{\"id\":\"review\",\"type\":\"parallelSplit\",\"label\":\"Notify who applies\","
                    + "\"config\":{\"joinRef\":\"joined\",\"mode\":\"inclusive\"}}"));
            nodes.add(n(note("noteBig")));
            nodes.add(n(note("noteMid")));
            nodes.add(n(note("noteStd")));
            nodes.add(n("{\"id\":\"joined\",\"type\":\"parallelJoin\",\"label\":\"Notices done\",\"config\":{}}"));
            ArrayNode ts = (ArrayNode) d.path("transitions");
            for (JsonNode t : ts) {
                if ("t2".equals(t.path("id").asString())) {
                    ((ObjectNode) t).put("to", "review");
                }
            }
            ts.add(n("{\"id\":\"i1\",\"from\":\"review\",\"to\":\"noteBig\",\"on\":\"condition\",\"label\":\"5,000 or more\","
                    + "\"condition\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":5000}}"));
            ts.add(n("{\"id\":\"i2\",\"from\":\"review\",\"to\":\"noteMid\",\"on\":\"condition\",\"label\":\"1,000 or more\","
                    + "\"condition\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":1000}}"));
            ts.add(n("{\"id\":\"i3\",\"from\":\"review\",\"to\":\"noteStd\",\"on\":\"default\",\"label\":\"Otherwise\"}"));
            for (String id : List.of("noteBig", "noteMid", "noteStd")) {
                ts.add(n("{\"id\":\"j_" + id + "\",\"from\":\"" + id + "\",\"to\":\"joined\",\"on\":\"next\"}"));
            }
            ts.add(n("{\"id\":\"j_out\",\"from\":\"joined\",\"to\":\"amountPolicy\",\"on\":\"next\"}"));
        };
    }

    private List<String> noticesSent(UUID run) {
        return jdbc.sql("SELECT node_id FROM run_event WHERE run_id = :run AND type = 'NOTIFICATION_CREATED' AND node_id LIKE 'note%' ORDER BY node_id")
                .param("run", run).query(String.class).list();
    }

    private UUID startInclusive(UUID workflow, String amount) throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal(amount)).path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        return run;
    }

    @Test
    void everyPathWhoseConditionHoldsRunsAndTheJoinContinuesOnceAfterAllOfThem() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, inclusive());
        // 800 matches nothing: only the default path runs.
        UUID none = startInclusive(workflow, "800");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> noticesSent(none).size() == 1);
        assertThat(noticesSent(none)).containsExactly("noteStd");

        // 1,500 matches one condition; 6,000 matches both, so two paths run and the join waits for both.
        UUID mid = startInclusive(workflow, "1500");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> noticesSent(mid).size() == 1);
        assertThat(noticesSent(mid)).containsExactly("noteMid");
        UUID both = startInclusive(workflow, "6000");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, both, "finance") != null);
        assertThat(noticesSent(both)).containsExactly("noteBig", "noteMid");
        // The request continued exactly once past the join (one finance task, no duplicate decision step).
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :run AND node_id = 'finance'")
                .param("run", both).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = 'TASK_CREATED' AND node_id = 'finance'")
                .param("run", both).query(Long.class).single()).isEqualTo(1L);
    }
}
