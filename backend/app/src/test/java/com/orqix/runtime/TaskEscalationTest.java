package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.TaskEscalations;
import com.orqix.shared.web.Sql;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** CG-T04 (docs/build/S141): escalation policies on PostgreSQL and Flowable through the public API. */
class TaskEscalationTest extends RuntimeTestBase {

    @Autowired
    TaskEscalations escalations;

    private static Consumer<ObjectNode> policy(boolean calendar, Consumer<ArrayNode> levels) {
        return definition -> {
            for (JsonNode node : definition.path("nodes")) {
                if ("manager".equals(node.path("id").asString())) {
                    ObjectNode config = (ObjectNode) node.path("config");
                    levels.accept(config.putObject("escalationPolicy").putArray("levels"));
                    config.put("dueAfterHours", 24);
                    if (calendar) {
                        config.putObject("calendar").put("zone", "UTC").put("start", "09:00").put("end", "17:00")
                                .putArray("weekdays").add(1).add(2).add(3).add(4).add(5);
                    }
                }
            }
        };
    }

    private static Consumer<ArrayNode> threeLevels() {
        return levels -> {
            levels.addObject().put("afterHours", 1).put("action", "notify").put("groupRef", "finance-approvers");
            levels.addObject().put("afterHours", 2).put("action", "reassign").put("groupRef", "finance-approvers");
            levels.addObject().put("afterHours", 3).put("action", "alertOperators");
        };
    }

    private void register(String... users) throws Exception {
        for (String user : users) {
            assertThat(api(user).get("/me").status()).isEqualTo(200);
        }
    }

    private UUID subject(String user) throws Exception {
        return UUID.fromString(api(user).get("/me").body().path("subject").path("id").asString());
    }

    private void mature(String task, int level) {
        jdbc.sql("UPDATE task_escalation SET next_attempt_at = now() - interval '1 minute' WHERE task_id = :task AND level = :level")
                .param("task", task).param("level", level).update();
    }

    private String status(String task, int level) {
        return jdbc.sql("SELECT status || ':' || coalesce(outcome, '') FROM task_escalation WHERE task_id = :task AND level = :level")
                .param("task", task).param("level", level).query(String.class).single();
    }

    private int escalationNotices(UUID run, UUID recipient) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE run_id = :run AND kind = 'taskEscalated' AND recipient_subject_id = :r")
                .param("run", run).param("r", recipient).query(Integer.class).single();
    }

    private List<JsonNode> escalated(UUID run) {
        return jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :id AND type = 'TASK_ESCALATED' ORDER BY seq")
                .param("id", run).query(String.class).list().stream().map(json::parse).toList();
    }

    @Test
    void levelsNotifyReassignAndAlertOnceEachWhileTheDueDateStays() throws Exception {
        register("mani.manager", "max.manager", "fin.finance", "olga.operator");
        UUID fin = subject("fin.finance");
        UUID olga = subject("olga.operator");
        UUID workflow = publishPurchase(PURCHASING, policy(false, threeLevels()));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        JsonNode open = openTask(PURCHASING, run, "manager");
        String task = open.path("id").asString();
        Instant due = Instant.parse(open.path("dueAt").asString());

        // Planned durably at creation: elapsed hours without a calendar.
        Instant created = jdbc.sql("SELECT created_at FROM task_context WHERE engine_task_id = :t").param("t", task)
                .query((rs, n) -> Sql.instant(rs, "created_at")).single();
        List<Instant> planned = jdbc.sql("SELECT due_at FROM task_escalation WHERE task_id = :t ORDER BY level")
                .param("t", task).query((rs, n) -> Sql.instant(rs, "due_at")).list();
        assertThat(planned).containsExactly(created.plusSeconds(3600), created.plusSeconds(7200), created.plusSeconds(10800));

        // Nothing is due yet.
        escalations.dispatchDue();
        assertThat(status(task, 1)).isEqualTo("pending:");

        // Level 1: the supervisor group is told, exactly once even when the worker runs again.
        mature(task, 1);
        escalations.dispatchDue();
        escalations.dispatchDue();
        assertThat(status(task, 1)).isEqualTo("done:notified");
        assertThat(escalationNotices(run, fin)).isEqualTo(1);
        assertThat(status(task, 2)).isEqualTo("pending:");

        // A manager holds the task; level 2 moves it to the finance queue and returns it there.
        assertThat(claim("mani.manager", PURCHASING, task).status()).isEqualTo(200);
        mature(task, 2);
        escalations.dispatchDue();
        assertThat(status(task, 2)).isEqualTo("done:reassigned");
        JsonNode moved = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task).body();
        assertThat(moved.path("candidateGroup").path("key").asString()).isEqualTo("finance-approvers");
        assertThat(moved.path("assignee").isNull() || moved.path("assignee").isMissingNode()).isTrue();
        assertThat(Instant.parse(moved.path("dueAt").asString())).as("the SLA keeps running").isEqualTo(due);
        assertThat(decide("mani.manager", PURCHASING, task, "approve", null).status()).isIn(403, 404, 409);
        assertThat(escalationNotices(run, fin)).isEqualTo(2);

        // Level 3: operators are alerted.
        mature(task, 3);
        escalations.dispatchDue();
        assertThat(status(task, 3)).isEqualTo("done:alerted");
        assertThat(escalationNotices(run, olga)).isEqualTo(1);

        // Timeline and audit: one event and one record per level, without request values.
        List<JsonNode> events = escalated(run);
        assertThat(events).extracting(e -> e.path("action").asString()).containsExactly("notify", "reassign", "alertOperators");
        assertThat(events.get(1).path("group").asString()).isEqualTo("finance-approvers");
        assertThat(events.toString()).doesNotContain("Replacement laptop");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'task.escalated' AND resource_id = :t")
                .param("t", task).query(Integer.class).single()).isEqualTo(3);

        // The finance approver can now claim and decide like any queue task.
        assertThat(claim("fin.finance", PURCHASING, task).status()).isEqualTo(200);
        assertThat(decide("fin.finance", PURCHASING, task, "approve", null).status()).isEqualTo(201);
    }

    @Test
    void aClosedTaskSkipsItsLevelsAndAReassignmentNeedsAnEligiblePerson() throws Exception {
        register("mani.manager", "max.manager", "fin.finance");
        UUID fin = subject("fin.finance");
        UUID workflow = publishPurchase(PURCHASING, policy(false, threeLevels()));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String task = openTask(PURCHASING, run, "manager").path("id").asString();
        approve("max.manager", PURCHASING, run, "manager");
        mature(task, 1);
        mature(task, 2);
        mature(task, 3);
        escalations.dispatchDue();
        assertThat(status(task, 1)).isEqualTo("skipped:taskClosed");
        assertThat(status(task, 3)).isEqualTo("skipped:taskClosed");
        assertThat(escalationNotices(run, fin)).isZero();
        assertThat(escalated(run)).isEmpty();

        // Requiring the managers skill leaves nobody in finance eligible: the task stays where it is.
        UUID skilled = publishPurchase(PURCHASING, policy(false, levels -> levels.addObject().put("afterHours", 1)
                .put("action", "reassign").put("groupRef", "finance-approvers")).andThen(definition -> {
                    for (JsonNode node : definition.path("nodes")) {
                        if ("manager".equals(node.path("id").asString())) {
                            ((ObjectNode) node.path("config")).putArray("requiredSkills").add("managers");
                        }
                    }
                }));
        UUID second = UUID.fromString(start("rhea.requester", PURCHASING, skilled, BigDecimal.TEN).path("id").asString());
        String secondTask = openTask(PURCHASING, second, "manager").path("id").asString();
        mature(secondTask, 1);
        escalations.dispatchDue();
        assertThat(status(secondTask, 1)).isEqualTo("skipped:noEligiblePerson");
        JsonNode stayed = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, secondTask).body();
        assertThat(stayed.path("candidateGroup").path("key").asString()).isEqualTo("managers");
        assertThat(escalated(second)).singleElement().satisfies(e -> assertThat(e.path("outcome").asString())
                .isEqualTo("noEligiblePerson"));
    }

    @Test
    void workingHoursUseTheStepCalendar() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, policy(true, levels -> levels.addObject().put("afterHours", 10)
                .put("action", "alertOperators")));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String task = openTask(PURCHASING, run, "manager").path("id").asString();
        Instant created = jdbc.sql("SELECT created_at FROM task_context WHERE engine_task_id = :t").param("t", task)
                .query((rs, n) -> Sql.instant(rs, "created_at")).single();
        var calendar = com.orqix.compiler.BusinessCalendar.read(json.parse(
                "{\"zone\":\"UTC\",\"weekdays\":[1,2,3,4,5],\"start\":\"09:00\",\"end\":\"17:00\"}"));
        Instant expected = calendar.deadlineMinutes(created, 600);
        assertThat(jdbc.sql("SELECT due_at FROM task_escalation WHERE task_id = :t AND working_time")
                .param("t", task).query((rs, n) -> Sql.instant(rs, "due_at")).single()).isEqualTo(expected);
    }
}
