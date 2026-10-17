package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** WM06 explicit task dispatch (docs/build/S39) on PostgreSQL and Flowable through the public API. */
class TaskDispatchTest extends RuntimeTestBase {

    private static Consumer<ObjectNode> dispatch(String policy) {
        return definition -> {
            for (JsonNode node : definition.path("nodes")) {
                if ("manager".equals(node.path("id").asString())) {
                    ((ObjectNode) node.path("config")).putObject("dispatch").put("policy", policy);
                }
            }
        };
    }

    private void register(String... users) throws Exception {
        for (String user : users) {
            assertThat(api(user).get("/me").status()).isEqualTo(200);
        }
    }

    private int openAssigned(String user) throws Exception {
        return jdbc.sql("""
                SELECT count(*) FROM task_context t JOIN subject s ON s.id = t.assignee_subject_id
                WHERE t.status = 'open' AND s.display_name = :name
                """).param("name", displayName(user)).query(Integer.class).single();
    }

    private String displayName(String user) throws Exception {
        return api(user).get("/me").body().path("subject").path("displayName").asString();
    }

    private UUID startRun(String user, UUID workflow) throws Exception {
        return UUID.fromString(start(user, PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
    }

    private String assignee(UUID run) throws Exception {
        JsonNode task = openTask(PURCHASING, run, "manager");
        assertThat(task).as("open manager task").isNotNull();
        return task.path("assignee").path("displayName").asString(null);
    }

    private List<JsonNode> dispatched(UUID run) {
        return jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :id AND type = 'TASK_DISPATCHED' ORDER BY seq")
                .param("id", run).query(String.class).list().stream().map(json::parse).toList();
    }

    @Test
    void leastLoadedAssignsToTheEligiblePersonWithFewestOpenTasksAndExplainsWhy() throws Exception {
        register("mani.manager", "max.manager");
        UUID workflow = publishPurchase(PURCHASING, dispatch("leastLoaded"));
        int maniBefore = openAssigned("mani.manager");
        int maxBefore = openAssigned("max.manager");

        UUID first = startRun("rhea.requester", workflow);
        String firstAssignee = assignee(first);
        String expected = maniBefore <= maxBefore ? displayName("mani.manager") : displayName("max.manager");
        assertThat(maniBefore == maxBefore ? firstAssignee : expected)
                .as("fewest open tasks wins; a tie is broken by who was assigned longest ago").isEqualTo(firstAssignee);

        // The first assignee now carries one more task, so the next one goes to the other manager.
        UUID second = startRun("rhea.requester", workflow);
        String secondAssignee = assignee(second);
        if (Math.abs(maniBefore - maxBefore) <= 1) {
            assertThat(secondAssignee).isNotEqualTo(firstAssignee);
        }

        List<JsonNode> events = dispatched(first);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("policy").asString()).isEqualTo("leastLoaded");
        assertThat(events.getFirst().path("assignee").asString()).isEqualTo(firstAssignee);
        assertThat(events.getFirst().path("eligible").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(events.getFirst().path("rule").asString()).contains("Fewest open tasks");

        // The person can decide without claiming, and the step shows the assignee.
        JsonNode run = api("rhea.requester").get("/workspaces/{ws}/runs/{id}", PURCHASING, first).body();
        assertThat(run.path("currentSteps").get(0).path("assignee").path("displayName").asString()).isEqualTo(firstAssignee);
        String owner = firstAssignee.equals(displayName("mani.manager")) ? "mani.manager" : "max.manager";
        String taskId = openTask(PURCHASING, first, "manager").path("id").asString();
        assertThat(decide(owner, PURCHASING, taskId, "approve", null).status()).isEqualTo(201);
    }

    @Test
    void roundRobinTakesTurnsAndTheAssigneeCanReturnTheTaskToTheGroup() throws Exception {
        register("mani.manager", "max.manager");
        UUID workflow = publishPurchase(PURCHASING, dispatch("roundRobin"));
        UUID a = startRun("rhea.requester", workflow);
        String firstAssignee = assignee(a);
        String owner = firstAssignee.equals(displayName("mani.manager")) ? "mani.manager" : "max.manager";
        String taskA = openTask(PURCHASING, a, "manager").path("id").asString();
        assertThat(decide(owner, PURCHASING, taskA, "approve", null).status()).isEqualTo(201);

        // Even though the first manager is free again, the other one goes next.
        UUID b = startRun("rhea.requester", workflow);
        String secondAssignee = assignee(b);
        assertThat(secondAssignee).isNotEqualTo(firstAssignee);

        String taskB = openTask(PURCHASING, b, "manager").path("id").asString();
        String secondOwner = owner.equals("mani.manager") ? "max.manager" : "mani.manager";
        JsonNode detail = api(secondOwner).get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskB).body();
        Response released = api(secondOwner).post(java.util.Map.of("expectedVersion", detail.path("version").asInt()),
                "/workspaces/{ws}/tasks/{id}/release", PURCHASING, taskB);
        assertThat(released.status()).as(released.toString()).isEqualTo(200);
        assertThat(assignee(b)).isNull();
        assertThat(claim(owner, PURCHASING, taskB).status()).isEqualTo(200);
    }

    @Test
    void theRequesterIsNeverChosenForTheirOwnRequestAndTasksWithoutAPolicyStayInTheQueue() throws Exception {
        register("mani.manager", "max.manager");
        UUID workflow = publishPurchase(PURCHASING, dispatch("leastLoaded"));
        UUID own = startRun("mani.manager", workflow);
        assertThat(assignee(own)).isEqualTo(displayName("max.manager"));

        UUID plain = publishPurchase(PURCHASING, null);
        UUID queued = startRun("rhea.requester", plain);
        assertThat(assignee(queued)).isNull();
        assertThat(dispatched(queued)).isEmpty();
    }
}
