package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.orqix.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;

class CaseEventApiTest extends IntegrationTestBase {
    private JsonNode start(String fixture,String original) throws Exception {
        return startSource(CmmnEventRulesTest.source(fixture),original);
    }
    private JsonNode startSource(String xml,String original) throws Exception {
        String key="events"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var created=api("dana.designer").post(Map.of("key",key,"name","Event case","sourceXml",xml.replace(original,key)),
                "/workspaces/{ws}/cases/definitions",SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var started=api("rhea.requester").post(Map.of(),"start-"+key,"/workspaces/{ws}/cases/definitions/{id}/instances",SANDBOX,created.body().path("id").asString());
        assertThat(started.status()).as(started.toString()).isEqualTo(201);return started.body();
    }
    private String path(JsonNode instance){return "/workspaces/"+SANDBOX+"/cases/instances/"+instance.path("id").asString();}
    private JsonNode plan(JsonNode instance,String user) throws Exception {
        var response=api(user).get(path(instance)+"/plan");assertThat(response.status()).as(response.toString()).isEqualTo(200);return response.body();
    }
    private JsonNode item(JsonNode plan,String element,String state) {
        for(var item:plan.path("items")) if(element.equals(item.path("elementId").asString())&&state.equals(item.path("state").asString()))return item;
        throw new AssertionError("Missing "+element+" in "+state+": "+plan);
    }
    private Response command(JsonNode instance,String item,String action,int version,String key,String user) throws Exception {
        return api(user).post(Map.of("expectedVersion",version,"reason","Protected event reason"),key,path(instance)+"/plan-items/"+item+"/"+action);
    }
    @Test void userEventRequiresCurrentRoleAndOccursOnceWithoutGrantingCaseDataAccess() throws Exception {
        var instance=start("user-events","eventCase");
        assertThat(instance.path("tasks")).isEmpty();
        var release=item(plan(instance,"fin.finance"),"release","available");String id=release.path("id").asString();
        assertThat(release.path("allowedActions").path("occur").asBoolean()).isTrue();
        assertThat(api("fin.finance").get(path(instance)+"/data").status()).isEqualTo(404);
        assertThat(command(instance,id,"occur",1,"wrong-role-"+instance.path("id").asString(),"mani.manager").status()).isEqualTo(403);
        String key="occur-"+instance.path("id").asString();
        var occurred=command(instance,id,"occur",1,key,"fin.finance");
        assertThat(occurred.status()).as(occurred.toString()).isEqualTo(200);
        assertThat(occurred.body().path("canRead").asBoolean()).isFalse();
        assertThat(command(instance,id,"occur",1,key,"fin.finance").body()).isEqualTo(occurred.body());
        assertThat(command(instance,id,"occur",1,"stale-"+key,"fin.finance").status()).isEqualTo(409);
        assertThat(command(instance,id,"occur",2,"again-"+key,"fin.finance").status()).isEqualTo(404);
        assertThat(api("fin.finance",List.of("orqix-purchasing-managers")).post(Map.of("expectedVersion",1,"reason","Protected event reason"),key,path(instance)+"/plan-items/"+id+"/occur").status()).isEqualTo(403);
        var updated=plan(instance,"mani.manager");
        assertThat(item(updated,"review","enabled").path("required").asBoolean()).isTrue();
        assertThat(updated.path("events")).hasSize(2);
        assertThat(updated.toString()).doesNotContain("Protected event reason");
        assertThat(plan(instance,"olga.operator").toString()).contains("Protected event reason");
    }
    @Test void userEventsRespectCaseHoldScopeAndVersionRaces() throws Exception {
        var instance=start("user-events","eventCase");String id=item(plan(instance,"fin.finance"),"release","available").path("id").asString();
        var other=start("user-events","eventCase");
        assertThat(command(other,id,"occur",1,"foreign-item-"+UUID.randomUUID(),"fin.finance").status()).isEqualTo(404);
        assertThat(api("fin.finance").post(Map.of("expectedVersion",1,"reason","Wrong workspace"),UUID.randomUUID().toString(),
                "/workspaces/"+PURCHASING+"/cases/instances/"+instance.path("id").asString()+"/plan-items/"+id+"/occur").status()).isEqualTo(404);
        assertThat(api("olga.operator").post(Map.of("expectedVersion",1,"reason","Hold"),UUID.randomUUID().toString(),path(instance)+"/suspend").status()).isEqualTo(200);
        assertThat(command(instance,id,"occur",2,UUID.randomUUID().toString(),"fin.finance").body().path("code").asString()).isEqualTo("RUN_SUSPENDED");
        assertThat(api("olga.operator").post(Map.of("expectedVersion",2,"reason","Resume"),UUID.randomUUID().toString(),path(instance)+"/resume").status()).isEqualTo(200);
        var one=CompletableFuture.supplyAsync(()->race(instance,id));var two=CompletableFuture.supplyAsync(()->race(instance,id));
        assertThat(List.of(one.get(30,java.util.concurrent.TimeUnit.SECONDS),two.get(30,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        var updated=plan(instance,"rhea.requester");
        assertThat(updated.path("events")).hasSize(4);
        assertThat(updated.path("items")).filteredOn(node->node.path("elementId").asString().equals("review")&&node.path("state").asString().equals("enabled")).hasSize(1);
    }
    private int race(JsonNode instance,String id){try{return command(instance,id,"occur",3,UUID.randomUUID().toString(),"fin.finance").status();}catch(Exception e){throw new RuntimeException(e);}}
    @Test void repeatedRequiredManualTasksCreateDistinctInstancesAndOnlyFinishAtTheLimit() throws Exception {
        var instance=start("repeated-human-task","repeatCase");var ids=new HashSet<String>();int version=1;
        for(int occurrence=1;occurrence<=3;occurrence++) {
            var current=plan(instance,"mani.manager");
            var review=item(current,"review","enabled");String id=review.path("id").asString();assertThat(ids.add(id)).isTrue();
            assertThat(review.path("required").asBoolean()).isTrue();
            assertThat(api("olga.operator").post(Map.of("expectedVersion",version,"reason","Cannot skip required review"),UUID.randomUUID().toString(),path(instance)+"/close").status()).isEqualTo(422);
            String key="start-"+id;var active=command(instance,id,"start",version,key,"mani.manager");
            assertThat(active.status()).as(active.toString()).isEqualTo(200);
            assertThat(command(instance,id,"start",version,key,"mani.manager").body()).isEqualTo(active.body());version++;
            var completed=api("mani.manager").post(Map.of("expectedVersion",version),"finish-"+id,path(instance)+"/tasks/"+active.body().path("tasks").get(0).path("id").asString()+"/complete");
            assertThat(completed.status()).as(completed.toString()).isEqualTo(200);version++;
            assertThat(completed.body().path("status").asString()).isEqualTo(occurrence==3?"completed":"active");
        }
        var ended=plan(instance,"rhea.requester");assertThat(ended.path("items")).hasSize(3);assertThat(ended.path("events")).hasSize(7);
        assertThat(ended.path("items")).allSatisfy(item->assertThat(item.path("state").asString()).isEqualTo("completed"));
    }
    @Test void requiredRepeatedChildrenPreventPrematureStageCompletion() throws Exception {
        String source=CmmnEventRulesTest.source("repeated-human-task")
                .replace("<planItem id=\"review\"", "<planItem id=\"phase\" definitionRef=\"phaseStage\"/><stage id=\"phaseStage\" autoComplete=\"false\"><planItem id=\"review\"")
                .replace("</casePlanModel>","</stage></casePlanModel>");
        var instance=startSource(source,"repeatCase");
        var current=plan(instance,"mani.manager");String review=item(current,"review","enabled").path("id").asString();
        var active=command(instance,review,"start",1,UUID.randomUUID().toString(),"mani.manager");assertThat(active.status()).isEqualTo(200);
        assertThat(api("mani.manager").post(Map.of("expectedVersion",2),UUID.randomUUID().toString(),path(instance)+"/tasks/"+active.body().path("tasks").get(0).path("id").asString()+"/complete").status()).isEqualTo(200);
        var phase=item(plan(instance,"olga.operator"),"phase","active");
        assertThat(phase.path("allowedActions").path("completeStage").asBoolean()).isFalse();
        assertThat(command(instance,phase.path("id").asString(),"completeStage",3,UUID.randomUUID().toString(),"olga.operator").status()).isEqualTo(409);
    }

    @Test void repeatedItemCancellationIsRefusedAndCaseTerminationStopsAllWork() throws Exception {
        var instance=start("repeated-human-task","repeatCase");String first=item(plan(instance,"mani.manager"),"review","enabled").path("id").asString();
        assertThat(command(instance,first,"terminate",1,UUID.randomUUID().toString(),"olga.operator").status()).isEqualTo(409);
        assertThat(api("olga.operator").post(Map.of("expectedVersion",1,"reason","Stop repeated work"),UUID.randomUUID().toString(),path(instance)+"/terminate").status()).isEqualTo(200);
        assertThat(plan(instance,"rhea.requester").path("items")).hasSize(1).allSatisfy(item->assertThat(item.path("state").asString()).isEqualTo("terminated"));
    }
}
