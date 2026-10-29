package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;

/** Discretionary planning and general repetition executed on the pinned engine through the case API (WM04, S110). */
class CasePlanningApiTest extends IntegrationTestBase {
    private JsonNode start(String fixture, String original) throws Exception {
        String key = "plan" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var created = api("dana.designer").post(Map.of("key", key, "name", "Planned case",
                "sourceXml", CmmnEventRulesTest.source(fixture).replace(original, key)), "/workspaces/{ws}/cases/definitions", SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var detail = api("dana.designer").get("/workspaces/{ws}/cases/definitions/{id}", SANDBOX, created.body().path("id").asString());
        assertThat(detail.body().path("sourceXml").asString()).as("exported source is the exact standard source")
                .isEqualTo(CmmnEventRulesTest.source(fixture).replace(original, key)).doesNotContain("__planner");
        var started = api("rhea.requester").post(Map.of(), "start-" + key, "/workspaces/{ws}/cases/definitions/{id}/instances",
                SANDBOX, created.body().path("id").asString());
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return started.body();
    }
    private String path(JsonNode instance) { return "/workspaces/" + SANDBOX + "/cases/instances/" + instance.path("id").asString(); }
    private JsonNode plan(JsonNode instance, String user) throws Exception {
        var response = api(user).get(path(instance) + "/plan");
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.body();
    }
    private JsonNode item(JsonNode plan, String element, String type, String state) {
        for (var item : plan.path("items"))
            if (element.equals(item.path("elementId").asString()) && type.equals(item.path("type").asString()) && state.equals(item.path("state").asString())) return item;
        throw new AssertionError("Missing " + type + " " + element + " in " + state + ": " + plan);
    }
    private Response command(JsonNode instance, String item, String action, int version, String key, String user) throws Exception {
        return api(user).post(Map.of("expectedVersion", version, "reason", "Protected planning reason"), key, path(instance) + "/plan-items/" + item + "/" + action);
    }
    private Response complete(JsonNode instance, String taskId, int version, String user) throws Exception {
        return api(user).post(Map.of("expectedVersion", version), UUID.randomUUID().toString(), path(instance) + "/tasks/" + taskId + "/complete");
    }
    private String taskId(JsonNode plan, JsonNode instance, String user, String name) throws Exception {
        for (var task : api(user).get(path(instance)).body().path("tasks")) if (name.equals(task.path("name").asString())) return task.path("id").asString();
        throw new AssertionError("No task " + name);
    }

    @Test void authorizedWorkerPlansDiscretionaryWorkWhichThenBindsAsRequired() throws Exception {
        var instance = start("discretionary", "planCase");
        var initial = plan(instance, "mani.manager");
        var planner = item(initial, "expert", "discretionaryItem", "available");
        assertThat(planner.path("name").asString()).isEqualTo("Expert opinion");
        assertThat(planner.path("allowedActions").path("plan").asBoolean()).isTrue();
        assertThat(planner.path("allowedActions").path("occur").asBoolean()).isFalse();
        // Unplanned required discretionary work does not bind: closing is blocked only by the active intake task.
        String id = planner.path("id").asString();
        assertThat(command(instance, id, "plan", 1, "wrong-role-" + id, "fin.finance").status()).isIn(403, 404);
        assertThat(command(instance, id, "occur", 1, "occur-" + id, "mani.manager").status()).isEqualTo(409);
        String key = "plan-" + id;
        var planned = command(instance, id, "plan", 1, key, "mani.manager");
        assertThat(planned.status()).as(planned.toString()).isEqualTo(200);
        assertThat(command(instance, id, "plan", 1, key, "mani.manager").body()).isEqualTo(planned.body());
        assertThat(command(instance, id, "plan", 2, "again-" + key, "mani.manager").status()).isIn(404, 409);
        var after = plan(instance, "olga.operator");
        item(after, "expert", "discretionaryItem", "planned");
        var expert = item(after, "expert", "humantask", "active");
        assertThat(expert.path("required").asBoolean()).isTrue();
        assertThat(after.path("canClose").asBoolean()).isFalse();
        assertThat(after.toString()).contains("Protected planning reason");
        // Intake done; the planned required expert work still prevents closing until it is done.
        int version = planned.body().path("version").asInt();
        var intake = complete(instance, taskId(after, instance, "mani.manager", "Intake"), version, "mani.manager");
        assertThat(intake.status()).as(intake.toString()).isEqualTo(200);
        version = intake.body().path("version").asInt();
        assertThat(api("olga.operator").post(Map.of("expectedVersion", version, "reason", "Too early"), UUID.randomUUID().toString(), path(instance) + "/close").status()).isEqualTo(422);
        var expertDone = complete(instance, taskId(after, instance, "fin.finance", "Expert opinion"), version, "fin.finance");
        assertThat(expertDone.status()).as(expertDone.toString()).isEqualTo(200);
        // With the planned work done nothing is left, so the engine completes the case.
        assertThat(expertDone.body().path("status").asString()).isEqualTo("completed");
        var ended = plan(instance, "olga.operator");
        assertThat(ended.path("milestones")).hasSize(1);
        assertThat(ended.path("events").toString()).contains("\"action\":\"plan\"");
    }

    @Test void unplannedDiscretionaryWorkNeverBlocksClosure() throws Exception {
        var instance = start("discretionary", "planCase");
        var intake = complete(instance, taskId(null, instance, "mani.manager", "Intake"), 1, "mani.manager");
        assertThat(intake.status()).as(intake.toString()).isEqualTo(200);
        var ready = plan(instance, "olga.operator");
        assertThat(ready.path("canClose").asBoolean()).as(ready.toString()).isTrue();
        var closed = api("olga.operator").post(Map.of("expectedVersion", 2, "reason", "No expert needed"), UUID.randomUUID().toString(), path(instance) + "/close");
        assertThat(closed.status()).as(closed.toString()).isEqualTo(200);
        assertThat(plan(instance, "rhea.requester").path("items").toString()).contains("notPlanned");
    }

    @Test void repeatedStagesTriggerRepeatedTasksAndMilestonesOnEachCompletion() throws Exception {
        var instance = start("general-repetition", "loopCase");
        int version = 1;
        for (int round = 1; round <= 2; round++) {
            var review = complete(instance, taskId(null, instance, "mani.manager", "Review"), version, "mani.manager");
            assertThat(review.status()).as(review.toString()).isEqualTo(200);
            version = review.body().path("version").asInt();
            var check = complete(instance, taskId(null, instance, "mani.manager", "Quality check"), version, "mani.manager");
            assertThat(check.status()).as(check.toString()).isEqualTo(200);
            version = check.body().path("version").asInt();
        }
        var ended = plan(instance, "olga.operator");
        assertThat(ended.path("items")).filteredOn(n -> n.path("elementId").asString().equals("round")).hasSize(2);
        assertThat(ended.path("items")).filteredOn(n -> n.path("elementId").asString().equals("check")
                && n.path("state").asString().equals("completed")).hasSize(2);
        assertThat(ended.path("milestones")).filteredOn(n -> n.path("elementId").asString().equals("checked")).hasSize(2);
        assertThat(api("mani.manager").get(path(instance)).body().path("tasks")).isEmpty();
        // The next repetitions wait for a round that will not come again, so the engine completes the case and ends them.
        assertThat(api("olga.operator").get(path(instance)).body().path("status").asString()).isEqualTo("completed");
        assertThat(ended.path("items")).filteredOn(n -> n.path("elementId").asString().equals("check") && n.path("state").asString().equals("terminated")).hasSize(1);
    }
}
