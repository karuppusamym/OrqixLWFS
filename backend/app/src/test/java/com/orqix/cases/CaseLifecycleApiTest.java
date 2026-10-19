package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.orqix.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;

class CaseLifecycleApiTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    private String source() throws Exception {
        try(var input=getClass().getResourceAsStream("/cases/manual-required.cmmn")) {
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private JsonNode start(String source) throws Exception {
        String key="controls"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var definition=api("dana.designer").post(Map.of("key",key,"name","Controlled review","sourceXml",source.replace("lifecycleCase",key)),
                "/workspaces/{ws}/cases/definitions",SANDBOX);
        assertThat(definition.status()).as(definition.toString()).isEqualTo(201);
        var started=api("rhea.requester").post(Map.of(),"start-"+key,"/workspaces/{ws}/cases/definitions/{id}/instances",
                SANDBOX,definition.body().path("id").asString());
        assertThat(started.status()).as(started.toString()).isEqualTo(201); return started.body();
    }
    private String detail(JsonNode instance) { return "/workspaces/"+SANDBOX+"/cases/instances/"+instance.path("id").asString(); }
    private JsonNode plan(JsonNode instance,String user) throws Exception {
        var response=api(user).get(detail(instance)+"/plan");
        assertThat(response.status()).as(response.toString()).isEqualTo(200); return response.body();
    }
    private JsonNode item(JsonNode plan,String elementId) {
        for(var item:plan.path("items")) if(elementId.equals(item.path("elementId").asString())) return item;
        throw new AssertionError("Missing plan item "+elementId+": "+plan);
    }
    private Response itemCommand(JsonNode instance,String itemId,String action,int version,String key,String user) throws Exception {
        return api(user).post(Map.of("expectedVersion",version,"reason","Reviewed manual work"),key,
                detail(instance)+"/plan-items/"+itemId+"/"+action);
    }
    private Response lifecycle(JsonNode instance,String action,int version,String key) throws Exception {
        return api("olga.operator").post(Map.of("expectedVersion",version,"reason","Protected operator note"),key,detail(instance)+"/"+action);
    }

    @Test void requiredManualWorkBlocksCloseAndEligibleWorkersCanDisableEnableStartAndComplete() throws Exception {
        var instance=start(source()); assertThat(instance.path("tasks")).isEmpty();
        var initial=plan(instance,"mani.manager");
        assertThat(initial.path("canClose").asBoolean()).isFalse();
        var required=item(initial,"requiredReview");
        assertThat(required.path("state").asString()).isEqualTo("enabled");
        assertThat(required.path("required").asBoolean()).isTrue();
        assertThat(required.path("allowedActions").path("start").asBoolean()).isTrue();
        assertThat(lifecycle(instance,"close",1,"too-soon-"+instance.path("id").asString()).status()).isEqualTo(422);
        String itemId=required.path("id").asString();
        assertThat(itemCommand(instance,itemId,"start",1,"wrong-worker-"+instance.path("id").asString(),"fin.finance").status()).isEqualTo(403);
        assertThat(itemCommand(instance,itemId,"disable",1,"required-disable-"+instance.path("id").asString(),"mani.manager").status()).isEqualTo(409);
        String optionalId=item(initial,"optionalReview").path("id").asString();
        var disabled=itemCommand(instance,optionalId,"disable",1,"disable-"+instance.path("id").asString(),"mani.manager");
        assertThat(disabled.status()).as(disabled.toString()).isEqualTo(200);
        assertThat(item(plan(instance,"mani.manager"),"optionalReview").path("state").asString()).isEqualTo("disabled");
        assertThat(lifecycle(instance,"close",2,"disabled-required-"+instance.path("id").asString()).status()).isEqualTo(422);
        assertThat(itemCommand(instance,optionalId,"enable",2,"enable-"+instance.path("id").asString(),"mani.manager").status()).isEqualTo(200);
        String key="activate-"+instance.path("id").asString();
        var active=itemCommand(instance,itemId,"start",3,key,"mani.manager");
        assertThat(active.status()).as(active.toString()).isEqualTo(200); assertThat(active.body().path("tasks")).hasSize(1);
        assertThat(itemCommand(instance,itemId,"start",3,key,"mani.manager").body()).isEqualTo(active.body());
        assertThat(itemCommand(instance,itemId,"start",3,"stale-"+instance.path("id").asString(),"mani.manager").status()).isEqualTo(409);
        String taskId=active.body().path("tasks").get(0).path("id").asString();
        var completed=api("mani.manager").post(Map.of("expectedVersion",4),"finish-"+instance.path("id").asString(),detail(instance)+"/tasks/"+taskId+"/complete");
        assertThat(completed.status()).as(completed.toString()).isEqualTo(200);
        assertThat(completed.body().path("status").asString()).isEqualTo("active");
        assertThat(plan(instance,"olga.operator").path("canClose").asBoolean()).isTrue();
        var closed=lifecycle(instance,"close",5,"close-"+instance.path("id").asString());
        assertThat(closed.status()).as(closed.toString()).isEqualTo(200); assertThat(closed.body().path("status").asString()).isEqualTo("completed");
        var history=plan(instance,"rhea.requester").path("events");
        assertThat(history).hasSize(6);
        assertThat(history).anySatisfy(event -> {
            assertThat(event.path("action").asString()).isEqualTo("taskCompleted");
            assertThat(event.path("planItemId").asString()).isEqualTo(itemId);
        });
    }

    @Test void holdsBlockWorkAndSurviveFreshReadsWhileReasonsRemainDataProtected() throws Exception {
        var instance=start(source()); String itemId=item(plan(instance,"mani.manager"),"requiredReview").path("id").asString();
        var active=itemCommand(instance,itemId,"start",1,"start-manual-"+instance.path("id").asString(),"mani.manager");
        assertThat(active.status()).isEqualTo(200);
        var held=lifecycle(instance,"suspend",2,"hold-"+instance.path("id").asString());
        assertThat(held.status()).as(held.toString()).isEqualTo(200); assertThat(held.body().path("status").asString()).isEqualTo("suspended");
        assertThat(api("mani.manager").get(detail(instance)).body().path("tasks").get(0).path("eligible").asBoolean()).isFalse();
        var blocked=api("mani.manager").post(Map.of("expectedVersion",3),"held-decision-"+instance.path("id").asString(),
                detail(instance)+"/tasks/"+active.body().path("tasks").get(0).path("id").asString()+"/complete");
        assertThat(blocked.body().path("code").asString()).isEqualTo("RUN_SUSPENDED");
        assertThat(plan(instance,"rhea.requester").toString()).doesNotContain("Protected operator note");
        assertThat(plan(instance,"olga.operator").toString()).contains("Protected operator note");
        assertThat(lifecycle(instance,"resume",3,"resume-"+instance.path("id").asString()).status()).isEqualTo(200);
        assertThat(lifecycle(instance,"suspend",4,"hold-again-"+instance.path("id").asString()).status()).isEqualTo(200);
        var terminated=api("olga.operator").post(Map.of("expectedVersion",5,"reason","End held work"),"end-held-"+instance.path("id").asString(),detail(instance)+"/terminate");
        assertThat(terminated.status()).as(terminated.toString()).isEqualTo(200); assertThat(terminated.body().path("status").asString()).isEqualTo("terminated");
        assertThat(plan(instance,"rhea.requester").path("events")).hasSize(6);
    }

    @Test void requiredStageWorkPreventsManualStageCompletion() throws Exception {
        String source=source().replace("<casePlanModel id=\"plan\" name=\"Review plan\" autoComplete=\"false\">", """
                <casePlanModel id="plan" name="Review plan">
                <planItem id="stageItem" definitionRef="reviewStage"><itemControl><requiredRule/><manualActivationRule/></itemControl></planItem>
                <stage id="reviewStage" name="Review stage" autoComplete="false">
                """).replace("</casePlanModel>","</stage></casePlanModel>");
        var instance=start(source);
        String stageId=item(plan(instance,"olga.operator"),"stageItem").path("id").asString();
        assertThat(itemCommand(instance,stageId,"activateStage",1,"stage-"+instance.path("id").asString(),"olga.operator").status()).isEqualTo(200);
        assertThat(itemCommand(instance,stageId,"completeStage",2,"early-stage-"+instance.path("id").asString(),"olga.operator").status()).isEqualTo(409);
        String requiredId=item(plan(instance,"mani.manager"),"requiredReview").path("id").asString();
        var active=itemCommand(instance,requiredId,"start",2,"task-"+instance.path("id").asString(),"mani.manager");
        assertThat(active.status()).isEqualTo(200);
        assertThat(api("mani.manager").post(Map.of("expectedVersion",3),"finish-stage-task-"+instance.path("id").asString(),
                detail(instance)+"/tasks/"+active.body().path("tasks").get(0).path("id").asString()+"/complete").status()).isEqualTo(200);
        var finished=itemCommand(instance,stageId,"completeStage",4,"complete-stage-"+instance.path("id").asString(),"olga.operator");
        assertThat(finished.status()).as(finished.toString()).isEqualTo(200);
        assertThat(finished.body().path("status").asString()).isEqualTo("completed");
    }

    @Test void competingStartsWithOneCaseVersionCannotCreateTwoTasks() throws Exception {
        var instance=start(source()); String itemId=item(plan(instance,"mani.manager"),"requiredReview").path("id").asString();
        var first=CompletableFuture.supplyAsync(()->competingStart(instance,itemId,"one-"+instance.path("id").asString()));
        var second=CompletableFuture.supplyAsync(()->competingStart(instance,itemId,"two-"+instance.path("id").asString()));
        assertThat(java.util.List.of(first.get(30,java.util.concurrent.TimeUnit.SECONDS),second.get(30,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200,409);
        assertThat(api("mani.manager").get(detail(instance)).body().path("tasks")).hasSize(1);
        assertThat(plan(instance,"mani.manager").path("events")).hasSize(2);
    }
    @Test void aPlanItemCannotBeOperatedThroughAnotherCase() throws Exception {
        var first=start(source()); var second=start(source());
        String itemId=item(plan(second,"mani.manager"),"requiredReview").path("id").asString();
        assertThat(itemCommand(first,itemId,"start",1,"cross-case-"+first.path("id").asString(),"mani.manager").status()).isEqualTo(404);
        assertThat(itemCommand(second,itemId,"terminate",1,"waive-"+second.path("id").asString(),"olga.operator").status()).isEqualTo(200);
        assertThat(plan(first,"mani.manager").path("events")).hasSize(1);
    }

    private int competingStart(JsonNode instance,String itemId,String key) {
        try { return itemCommand(instance,itemId,"start",1,key,"mani.manager").status(); }
        catch(Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
    }
}
