package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.orqix.shared.problem.ApiException;
import com.orqix.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;

/** Case tasks that start a pinned child case, timer listeners and data-triggered sentries (WM04, S113). */
class CaseNestingTriggersApiTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired CaseWorkflowReconciler reconciler;
    @Autowired org.flowable.cmmn.api.CmmnManagementService management;
    private final CmmnSubsetValidator validator = new CmmnSubsetValidator();

    private static String key(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private Response define(String key, String xml, List<Map<String, Object>> fields, List<Map<String, Object>> caseTasks) throws Exception {
        return api("dana.designer").post(Map.of("key", key, "name", "Case " + key, "sourceXml", xml, "dataFields", fields, "caseTasks", caseTasks),
                "/workspaces/{ws}/cases/definitions", SANDBOX);
    }
    private JsonNode start(String definitionId, Map<String, Object> data) throws Exception {
        var started = api("rhea.requester").post(Map.of("data", data), UUID.randomUUID().toString(), "/workspaces/{ws}/cases/definitions/{id}/instances", SANDBOX, definitionId);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return started.body();
    }
    private String path(UUID id) { return "/workspaces/" + SANDBOX + "/cases/instances/" + id; }
    private String path(JsonNode instance) { return path(UUID.fromString(instance.path("id").asString())); }
    private JsonNode plan(JsonNode instance) throws Exception { return api("olga.operator").get(path(instance) + "/plan").body(); }
    private JsonNode item(JsonNode plan, String element) {
        for (var item : plan.path("items")) if (element.equals(item.path("elementId").asString()) && !"terminated".equals(item.path("state").asString())) return item;
        return null;
    }
    private JsonNode get(JsonNode instance) throws Exception { return api("olga.operator").get(path(instance)).body(); }
    private String taskId(JsonNode instance, String name) throws Exception {
        for (var task : api("mani.manager").get(path(instance)).body().path("tasks")) if (name.equals(task.path("name").asString())) return task.path("id").asString();
        throw new AssertionError("No task " + name + " in " + api("mani.manager").get(path(instance)).body());
    }
    private static final List<Map<String, Object>> CHILD_FIELDS = List.of(Map.of("key", "amount", "label", "Amount", "type", "number", "required", true));
    private static final List<Map<String, Object>> PARENT_FIELDS = List.of(
            Map.of("key", "total", "label", "Total", "type", "number"), Map.of("key", "verdict", "label", "Verdict", "type", "text"));

    private String child() throws Exception {
        String childKey = key("child");
        var child = define(childKey, CmmnEventRulesTest.source("child-review").replace("childCase", childKey), CHILD_FIELDS, List.of());
        assertThat(child.status()).as(child.toString()).isEqualTo(201);
        return childKey;
    }

    private String parent(String childKey) throws Exception {
        String parentKey = key("parent");
        var parent = define(parentKey, CmmnEventRulesTest.source("parent-case").replace("parentCase", parentKey).replace("CHILDKEY", childKey), PARENT_FIELDS,
                List.of(Map.of("taskId", "supplierTask", "inputMapping", Map.of("amount", "total"), "resultMapping", Map.of("verdict", "outcome"))));
        assertThat(parent.status()).as(parent.toString()).isEqualTo(201);
        var detail = api("dana.designer").get("/workspaces/{ws}/cases/definitions/{id}", SANDBOX, parent.body().path("id").asString()).body();
        assertThat(detail.path("caseTasks").get(0).path("childKey").asString()).isEqualTo(childKey);
        assertThat(detail.path("caseTasks").get(0).path("childVersion").asInt()).isEqualTo(1);
        return parent.body().path("id").asString();
    }

    @Test void caseTaskStartsThePinnedChildCaseAndItsCompletionFinishesTheParentTask() throws Exception {
        var instance = start(parent(child()), Map.of("total", 1200));
        var task = item(plan(instance), "supplier");
        assertThat(task.path("type").asString()).isEqualTo("caseTask");
        assertThat(task.path("state").asString()).isEqualTo("active");
        UUID childId = UUID.fromString(task.path("workflow").path("caseId").asString());
        var childData = api("olga.operator").get(path(childId) + "/data").body();
        assertThat(childData.path("values").path("amount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(jdbc.sql("SELECT parent_case_id FROM case_instance WHERE id=:id").param("id", childId).query(UUID.class).single())
                .isEqualTo(UUID.fromString(instance.path("id").asString()));
        assertThat(plan(instance).path("canClose").asBoolean()).isFalse();

        var childInstance = api("mani.manager").get(path(childId)).body();
        String childTask = childInstance.path("tasks").get(0).path("id").asString();
        var done = api("mani.manager").post(Map.of("expectedVersion", childInstance.path("version").asInt()), UUID.randomUUID().toString(), path(childId) + "/tasks/" + childTask + "/complete");
        assertThat(done.status()).as(done.toString()).isEqualTo(200);
        assertThat(done.body().path("status").asString()).isEqualTo("completed");
        reconciler.run();
        var after = plan(instance);
        assertThat(item(after, "supplier").path("state").asString()).isEqualTo("completed");
        assertThat(item(after, "followUp").path("state").asString()).isIn("active", "enabled");
        assertThat(api("olga.operator").get(path(instance) + "/data").body().path("values").path("verdict").asString()).isEqualTo("Completed");
        assertThat(after.path("events").toString()).contains("childCaseStarted").contains("childCaseCompleted");
    }

    @Test void terminatingTheParentTerminatesTheChildAndBadMappingsAreRefused() throws Exception {
        String childKey = child();
        var instance = start(parent(childKey), Map.of("total", 50));
        UUID childId = UUID.fromString(item(plan(instance), "supplier").path("workflow").path("caseId").asString());
        var terminated = api("olga.operator").post(Map.of("expectedVersion", get(instance).path("version").asInt(), "reason", "Withdrawn"), UUID.randomUUID().toString(), path(instance) + "/terminate");
        assertThat(terminated.status()).as(terminated.toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM case_instance WHERE id=:id").param("id", childId).query(String.class).single()).isEqualTo("terminated");

        String xml = CmmnEventRulesTest.source("parent-case").replace("CHILDKEY", childKey);
        assertThat(define(key("bad"), xml.replace("parentCase", "badunknown").replace("orqixcase:" + childKey, "orqixcase:no-such-case"), PARENT_FIELDS,
                List.of(Map.of("taskId", "supplierTask", "inputMapping", Map.of("amount", "total")))).status()).isEqualTo(422);
        String missing = key("missing");
        assertThat(define(missing, xml.replace("parentCase", missing), PARENT_FIELDS, List.of(Map.of("taskId", "supplierTask", "inputMapping", Map.of()))).status()).isEqualTo(422);
        String wrongType = key("wrong");
        assertThat(define(wrongType, xml.replace("parentCase", wrongType), PARENT_FIELDS, List.of(Map.of("taskId", "supplierTask", "inputMapping", Map.of("amount", "verdict")))).status()).isEqualTo(422);
        String self = key("self");
        assertThat(define(self, xml.replace("parentCase", self).replace("orqixcase:" + childKey, "orqixcase:" + self), PARENT_FIELDS,
                List.of(Map.of("taskId", "supplierTask", "inputMapping", Map.of("amount", "total")))).status()).isEqualTo(422);
    }

    @Test void timerOccurrenceIsRecordedOnceAndReleasesItsWork() throws Exception {
        String k = key("timer");
        var created = define(k, CmmnEventRulesTest.source("timer-and-data").replace("triggerCase", k),
                List.of(Map.of("key", "amount", "label", "Amount", "type", "number"), Map.of("key", "region", "label", "Region", "type", "text")), List.of());
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var instance = start(created.body().path("id").asString(), Map.of("amount", 100, "region", "EU"));
        assertThat(item(plan(instance), "chase")).isNotNull();
        assertThat(item(plan(instance), "chase").path("state").asString()).isEqualTo("available");
        String engine = jdbc.sql("SELECT engine_instance_id FROM case_instance WHERE id=:id").param("id", UUID.fromString(instance.path("id").asString())).query(String.class).single();
        var timer = management.createTimerJobQuery().caseInstanceId(engine).singleResult();
        assertThat(timer).isNotNull();
        var job = management.moveTimerToExecutableJob(timer.getId());
        try { management.executeJob(job.getId()); } catch (RuntimeException alreadyRunByTheExecutor) { /* the async executor may have taken it */ }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() -> { reconciler.run(); return plan(instance).path("events").toString().contains("timerOccurred"); });
        reconciler.run();
        var after = plan(instance);
        assertThat(after.path("events")).filteredOn(e -> e.path("action").asString().equals("timerOccurred")).hasSize(1);
        assertThat(item(after, "chase").path("state").asString()).isIn("active", "enabled");
    }

    @Test void dataTriggeredSentriesFollowCaseDataChangesAndCombineWithEvents() throws Exception {
        String k = key("data");
        var fields = List.<Map<String, Object>>of(Map.of("key", "amount", "label", "Amount", "type", "number"), Map.of("key", "region", "label", "Region", "type", "text"));
        var created = define(k, CmmnEventRulesTest.source("timer-and-data").replace("triggerCase", k), fields, List.of());
        String definition = created.body().path("id").asString();
        var instance = start(definition, Map.of("amount", 100, "region", "EU"));
        assertThat(item(plan(instance), "escalate").path("state").asString()).isEqualTo("available");
        var changed = api("mani.manager").post(Map.of("expectedVersion", get(instance).path("version").asInt(), "reason", "Supplier quote arrived",
                "values", Map.of("amount", 9000, "region", "EU")), UUID.randomUUID().toString(), path(instance) + "/data");
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        assertThat(item(plan(instance), "escalate").path("state").asString()).isIn("active", "enabled");

        var small = start(definition, Map.of("amount", 300, "region", "EU"));
        var reviewed = api("mani.manager").post(Map.of("expectedVersion", get(small).path("version").asInt()), UUID.randomUUID().toString(), path(small) + "/tasks/" + taskId(small, "Review") + "/complete");
        assertThat(reviewed.status()).as(reviewed.toString()).isEqualTo(200);
        assertThat(plan(small).path("milestones")).filteredOn(m -> m.path("elementId").asString().equals("approved")).hasSize(1);
        var elsewhere = start(definition, Map.of("amount", 300, "region", "US"));
        api("mani.manager").post(Map.of("expectedVersion", get(elsewhere).path("version").asInt()), UUID.randomUUID().toString(), path(elsewhere) + "/tasks/" + taskId(elsewhere, "Review") + "/complete");
        assertThat(plan(elsewhere).path("milestones")).isEmpty();

        String unknown = key("unknown");
        assertThat(define(unknown, CmmnEventRulesTest.source("timer-and-data").replace("triggerCase", unknown), List.of(Map.of("key", "amount", "label", "Amount", "type", "number")), List.of()).status())
                .as("region is read by an if-part but not declared").isEqualTo(422);
    }

    @Test void conditionsTimersAndCaseTasksStayInsideTheAdmittedGrammar() throws Exception {
        String xml = CmmnEventRulesTest.source("timer-and-data");
        assertThat(validator.conditionFields(xml)).containsExactly("amount", "region");
        assertThat(validator.timers(xml)).containsExactly("reminderTimer");
        for (var invalid : new String[] {
                xml.replace("${cd_amount &gt; 5000}", "${cd_amount.getClass()}"),
                xml.replace("${cd_amount &gt; 5000}", "${execution.setVariable('x', 1)}"),
                xml.replace("${cd_amount &gt; 5000}", "${cd_amount &gt; 5000 &amp;&amp; cd_region == 'EU' || true}"),
                xml.replace("${cd_amount &gt; 5000}", "${amount &gt; 5000}"),
                xml.replace("PT1H", "P1M"), xml.replace("PT1H", "PT10S"), xml.replace("PT1H", "P400D"), xml.replace("PT1H", "${duration}"),
                xml.replace("<planItem id=\"reminder\" name=\"Reminder after one hour\" definitionRef=\"reminderTimer\"/>",
                        "<planItem id=\"reminder\" name=\"Reminder after one hour\" definitionRef=\"reminderTimer\"><entryCriterion id=\"x\" sentryRef=\"largeAmount\"/></planItem>"),
                xml.replace("autoComplete=\"false\"", "autoComplete=\"true\"") })
            assertThatThrownBy(() -> validator.validate(invalid, "triggerCase")).as(invalid).isInstanceOf(ApiException.class);
        String parent = CmmnEventRulesTest.source("parent-case").replace("CHILDKEY", "child-key");
        assertThat(validator.caseTasks(parent)).containsEntry("supplierTask", "child-key");
        assertThat(validator.engineXml(parent)).contains("<task").doesNotContain("caseTask");
        for (var invalid : new String[] { parent.replace("xmlns:orqixcase=\"urn:orqix:case\"", "xmlns:orqixcase=\"urn:other\""),
                parent.replace("isBlocking=\"true\"", "isBlocking=\"false\""), parent.replace("orqixcase:child-key", "orqixcase:parentCase") })
            assertThatThrownBy(() -> validator.validate(invalid, "parentCase")).as(invalid).isInstanceOf(ApiException.class);
    }
}
