package com.orqix.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.IntegrationTestBase;

/** Browser-session API journey; every response is checked against OpenAPI by IntegrationTestBase. */
class CaseApiTest extends IntegrationTestBase {
    @Test void nestedStagesAndExitSentriesAdvanceTheCaseAndKeepMilestoneHistory() throws Exception {
        String key = "nested" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String source = CmmnStageValidatorTest.source().replace("nestedCase", key);
        String base = "/workspaces/" + SANDBOX + "/cases";
        var created = api("dana.designer").post(Map.of("key", key, "name", "Nested review", "sourceXml", source), base + "/definitions");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var started = api("rhea.requester").post(Map.of(), "start-" + key,
                base + "/definitions/" + created.body().path("id").asString() + "/instances");
        assertThat(started.status()).isEqualTo(201);
        String instanceId = started.body().path("id").asString();
        String detail = base + "/instances/" + instanceId;
        var plan = api("rhea.requester").get(detail + "/plan");
        assertThat(plan.status()).as(plan.toString()).isEqualTo(200);
        assertThat(plan.body().path("items")).anySatisfy(item -> {
            assertThat(item.path("elementId").asString()).isEqualTo("phase");
            assertThat(item.path("state").asString()).isEqualTo("active");
        });
        assertThat(plan.body().path("milestones")).isEmpty();
        assertThat(api("otto.outsider").get(detail + "/plan").status()).isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/" + PURCHASING + "/cases/instances/" + instanceId + "/plan").status())
                .isEqualTo(404);
        String collectId = null;
        String alternateId = null;
        for (var task : started.body().path("tasks")) {
            if ("Collect evidence".equals(task.path("name").asString())) collectId = task.path("id").asString();
            if ("Alternate evidence".equals(task.path("name").asString())) alternateId = task.path("id").asString();
        }
        assertThat(collectId).isNotNull();
        assertThat(alternateId).isNotNull();
        var next = api("mani.manager").post(Map.of("expectedVersion", 1), "collect-" + key,
                detail + "/tasks/" + collectId + "/complete");
        assertThat(next.status()).as(next.toString()).isEqualTo(200);
        assertThat(next.body().path("tasks")).hasSize(1);
        assertThat(next.body().path("tasks").get(0).path("name").asString()).isEqualTo("Final decision");
        assertThat(api("mani.manager").post(Map.of("expectedVersion", 2), "late-" + key,
                detail + "/tasks/" + alternateId + "/complete").status()).isEqualTo(404);
        var progressed = api("rhea.requester").get(detail + "/plan");
        assertThat(progressed.body().path("milestones")).hasSize(2);
        assertThat(progressed.body().path("items")).anySatisfy(item -> {
            assertThat(item.path("elementId").asString()).isEqualTo("obsolete");
            assertThat(item.path("state").asString()).isEqualTo("terminated");
            assertThat(item.path("parentStageId").isString()).isTrue();
        });
        var completed = api("mani.manager").post(Map.of("expectedVersion", 2), "decide-" + key,
                detail + "/tasks/" + next.body().path("tasks").get(0).path("id").asString() + "/complete");
        assertThat(completed.status()).as(completed.toString()).isEqualTo(200);
        assertThat(completed.body().path("status").asString()).isEqualTo("completed");
        var ended = api("rhea.requester").get(detail + "/plan");
        assertThat(ended.status()).isEqualTo(200);
        assertThat(ended.body().path("milestones")).hasSize(2);
        assertThat(ended.body().path("items")).allSatisfy(item -> assertThat(item.path("state").asString())
                .isIn("completed", "terminated"));
        assertThat(api("mani.manager").get(detail + "/plan").status()).as("ended cases retain the existing read policy").isEqualTo(404);
    }

    @Test void exitingAStageTerminatesItsNestedWorkWithoutCompletingThoseTasks() throws Exception {
        String key = "exit" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String source = """
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                  xmlns:flowable="http://flowable.org/cmmn" targetNamespace="urn:orqix:exit">
                  <case id="CASEKEY"><casePlanModel id="plan">
                    <planItem id="phase" definitionRef="phaseStage"><exitCriterion id="phaseExit" sentryRef="stopped"/></planItem>
                    <planItem id="stop" name="Stop evidence phase" definitionRef="stopTask"/>
                    <sentry id="stopped"><planItemOnPart id="stopEvent" sourceRef="stop"><standardEvent>complete</standardEvent></planItemOnPart></sentry>
                    <stage id="phaseStage" name="Evidence phase">
                      <planItem id="inner" definitionRef="innerStage"/>
                      <stage id="innerStage" name="Inner stage">
                        <planItem id="collect" name="Collect evidence" definitionRef="collectTask"/>
                        <humanTask id="collectTask" name="Collect evidence" flowable:candidateGroups="managers"/>
                      </stage>
                    </stage>
                    <humanTask id="stopTask" name="Stop evidence phase" flowable:candidateGroups="managers"/>
                  </casePlanModel></case>
                </definitions>
                """.replace("CASEKEY", key);
        String base = "/workspaces/" + SANDBOX + "/cases";
        var created = api("dana.designer").post(Map.of("key", key, "name", "Stage exit", "sourceXml", source), base + "/definitions");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var started = api("rhea.requester").post(Map.of(), "start-" + key,
                base + "/definitions/" + created.body().path("id").asString() + "/instances");
        assertThat(started.status()).isEqualTo(201);
        String detail = base + "/instances/" + started.body().path("id").asString();
        String stopId = null;
        for (var task : started.body().path("tasks")) {
            if ("Stop evidence phase".equals(task.path("name").asString())) stopId = task.path("id").asString();
        }
        assertThat(stopId).isNotNull();
        var stopped = api("mani.manager").post(Map.of("expectedVersion", 1), "stop-" + key,
                detail + "/tasks/" + stopId + "/complete");
        assertThat(stopped.status()).as(stopped.toString()).isEqualTo(200);
        assertThat(stopped.body().path("tasks")).isEmpty();
        assertThat(stopped.body().path("status").asString()).isEqualTo("completed");
        var plan = api("rhea.requester").get(detail + "/plan");
        assertThat(plan.body().path("items")).anySatisfy(item -> {
            assertThat(item.path("elementId").asString()).isEqualTo("collect");
            assertThat(item.path("state").asString()).isEqualTo("terminated");
        });
        assertThat(plan.body().path("milestones")).isEmpty();
    }

    @Test void designerPublishesRequesterStartsAndEligibleWorkerCompletes() throws Exception {
        String key="apicase"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        String source;
        try (var input=getClass().getResourceAsStream("/cases/cmmn-compatibility.cmmn")) {
            source=new String(input.readAllBytes(),StandardCharsets.UTF_8)
                    .replace("compatibilityCase",key).replace("case-workers","managers");
        }
        String base="/workspaces/"+SANDBOX+"/cases";
        var created=api("dana.designer").post(Map.of("key",key,"name","API case","sourceXml",source),base+"/definitions");
        assertThat(created.status()).isEqualTo(201);
        String definitionId=created.body().path("id").asString();
        assertThat(api("rhea.requester").get(base+"/definitions").body().toString()).contains(definitionId);
        var started=api("rhea.requester").post(Map.of(),"start-api-"+key,
                base+"/definitions/"+definitionId+"/instances");
        assertThat(started.status()).isEqualTo(201);
        String instanceId=started.body().path("id").asString();
        String taskId=started.body().path("tasks").get(0).path("id").asString();
        assertThat(api("mani.manager").get(base+"/instances/"+instanceId).body().path("tasks").get(0)
                .path("eligible").asBoolean()).isTrue();
        assertThat(api("otto.outsider").get(base+"/instances/"+instanceId).status()).isEqualTo(404);
        var completed=api("mani.manager").post(Map.of("expectedVersion",1),"complete-api-"+key,
                base+"/instances/"+instanceId+"/tasks/"+taskId+"/complete");
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.body().path("version").asInt()).isEqualTo(2);
        assertThat(completed.body().path("tasks").get(0).path("name").asString()).isEqualTo("Review evidence");
        var terminated = api("olga.operator").post(Map.of("expectedVersion", 2, "reason", "No longer needed"),
                "terminate-api-" + key, base + "/instances/" + instanceId + "/terminate");
        assertThat(terminated.status()).isEqualTo(200);
        assertThat(terminated.body().path("status").asString()).isEqualTo("terminated");
        assertThat(terminated.body().path("tasks")).isEmpty();
        assertThat(terminated.body().toString()).doesNotContain("No longer needed");
    }
}
