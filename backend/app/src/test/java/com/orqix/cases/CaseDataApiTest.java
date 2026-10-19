package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.orqix.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;

class CaseDataApiTest extends IntegrationTestBase {
    private final List<Map<String,Object>> fields=List.of(
            Map.of("key","summary","label","Summary","type","text","required",true,"maxLength",80),
            Map.of("key","amount","label","Amount","type","number","minimum",0,"maximum",10000),
            Map.of("key","count","label","Count","type","integer"),
            Map.of("key","due","label","Due date","type","date"),
            Map.of("key","approved","label","Approved","type","boolean"),
            Map.of("key","priority","label","Priority","type","choice","options",List.of("normal","urgent")));

    private JsonNode definition() throws Exception {
        String key="data"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String xml;
        try(var input=getClass().getResourceAsStream("/cases/manual-required.cmmn")) {
            xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("lifecycleCase",key);
        }
        var response=api("dana.designer").post(Map.of("key",key,"name","Typed case","sourceXml",xml,"dataFields",fields),"/workspaces/{ws}/cases/definitions",SANDBOX);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);return response.body();
    }
    private JsonNode start(JsonNode definition) throws Exception {
        var response=api("rhea.requester").post(Map.of("data",Map.of("summary","Protected initial summary","amount",12.25)),"start-"+definition.path("id").asString(),
                "/workspaces/{ws}/cases/definitions/{id}/instances",SANDBOX,definition.path("id").asString());
        assertThat(response.status()).as(response.toString()).isEqualTo(201);return response.body();
    }
    private String path(JsonNode instance) { return "/workspaces/"+SANDBOX+"/cases/instances/"+instance.path("id").asString(); }
    private Response update(JsonNode instance,String user,int version,String key,Map<String,Object> values) throws Exception {
        return api(user).post(Map.of("expectedVersion",version,"reason","Protected correction reason","values",values),key,path(instance)+"/data");
    }

    @Test void validatesStartAndPinsFieldVersionsWithoutExposingValuesInMetadata() throws Exception {
        var definition=definition();
        var missing=api("rhea.requester").post(Map.of(),"missing-"+definition.path("id").asString(),"/workspaces/{ws}/cases/definitions/{id}/instances",SANDBOX,definition.path("id").asString());
        assertThat(missing.status()).isEqualTo(422);
        var instance=start(definition);
        assertThat(instance.toString()).doesNotContain("Protected initial summary");
        var data=api("rhea.requester").get(path(instance)+"/data");
        assertThat(data.status()).as(data.toString()).isEqualTo(200);
        assertThat(data.body().path("fields")).hasSize(6);
        assertThat(data.body().path("values").path("summary").asString()).isEqualTo("Protected initial summary");
        assertThat(data.body().path("changes")).isEmpty();
        assertThat(data.body().path("canEdit").asBoolean()).isFalse();
        assertThat(api("mani.manager").get(path(instance)+"/data").status()).isEqualTo(200);
        assertThat(api("vic.viewer").get(path(instance)+"/data").status()).isEqualTo(404);
        assertThat(api("fin.finance").get(path(instance)+"/data").status()).isEqualTo(404);
        var source=api("dana.designer").get("/workspaces/{ws}/cases/definitions/{id}",SANDBOX,definition.path("id").asString()).body();
        var changed=api("dana.designer").post(Map.of("key",definition.path("key").asString(),"name","New schema","sourceXml",source.path("sourceXml").asString(),"dataFields",List.of()),"/workspaces/{ws}/cases/definitions",SANDBOX);
        assertThat(changed.status()).as(changed.toString()).isEqualTo(201);
        assertThat(changed.body().path("version").asInt()).isEqualTo(2);
        assertThat(api("rhea.requester").get(path(instance)+"/data").body().path("fields")).hasSize(6);
    }

    @Test void retainsProtectedBeforeAfterHistoryAndReplaysOnlyIdenticalCommands() throws Exception {
        var instance=start(definition());String key="data-"+instance.path("id").asString();
        var result=update(instance,"mani.manager",1,key,Map.of("summary","Corrected protected value","amount",20));
        assertThat(result.status()).as(result.toString()).isEqualTo(200);
        assertThat(result.body().path("version").asInt()).isEqualTo(2);
        assertThat(update(instance,"mani.manager",1,key,Map.of("summary","Corrected protected value","amount",20)).body()).isEqualTo(result.body());
        assertThat(update(instance,"mani.manager",1,key,Map.of("summary","Different intent")).status()).isEqualTo(409);
        assertThat(update(instance,"mani.manager",1,"stale-"+key,Map.of("summary","Stale edit")).status()).isEqualTo(409);
        assertThat(update(instance,"rhea.requester",2,"starter-"+key,Map.of("summary","Not a worker")).status()).isEqualTo(403);
        var worker=api("mani.manager").get(path(instance)+"/data").body();
        assertThat(worker.path("changes")).isEmpty();
        var operator=api("olga.operator").get(path(instance)+"/data").body();
        assertThat(operator.path("changes")).hasSize(2);
        assertThat(operator.path("changes").get(0).path("before").path("summary").asString()).isEqualTo("Protected initial summary");
        assertThat(operator.path("changes").get(0).path("after").path("summary").asString()).isEqualTo("Corrected protected value");
        assertThat(api("rhea.requester").get(path(instance)+"/plan").body().toString()).doesNotContain("Protected correction reason","Corrected protected value");
        var held=api("olga.operator").post(Map.of("expectedVersion",2,"reason","Pause data edits"),"hold-"+key,path(instance)+"/suspend");
        assertThat(held.status()).isEqualTo(200);
        assertThat(api("mani.manager").get(path(instance)+"/data").body().path("canEdit").asBoolean()).isFalse();
        assertThat(update(instance,"mani.manager",3,"held-"+key,Map.of("summary","Held edit")).body().path("code").asString()).isEqualTo("RUN_SUSPENDED");
        assertThat(update(instance,"mani.manager",1,key,Map.of("summary","Corrected protected value","amount",20)).status()).isEqualTo(200);
    }

    @Test void rejectsInvalidTypedValuesWithoutChangingTheCaseVersion() throws Exception {
        var instance=start(definition());
        for(var invalid:List.of(Map.of("summary","ok","amount","12"),Map.of("summary","ok","amount",-1),
                Map.of("summary","ok","count",1.25),Map.of("summary","ok","amount",new java.math.BigDecimal("0.10000000000000001")),Map.of("summary","ok","due","2026-02-30"),
                Map.of("summary","ok","approved","true"),Map.of("summary","ok","priority","unknown"),
                Map.of("summary","ok","extra","unknown"),Map.of("summary","x".repeat(81)),Map.of("summary",""))) {
            var result=update(instance,"mani.manager",1,UUID.randomUUID().toString(),Map.copyOf(invalid));
            assertThat(result.status()).as(result.toString()).isEqualTo(422);
        }
        assertThat(api("rhea.requester").get(path(instance)).body().path("version").asInt()).isEqualTo(1);
        assertThat(new CaseService.UpdateData(1,"private",null).toString()).doesNotContain("private");
    }

    @Test void competingDataEditsSerializeAndCannotOverwriteAWinningVersion() throws Exception {
        var instance=start(definition());
        var one=CompletableFuture.supplyAsync(()->competing(instance,"First correction"));
        var two=CompletableFuture.supplyAsync(()->competing(instance,"Second correction"));
        assertThat(List.of(one.get(30,java.util.concurrent.TimeUnit.SECONDS),two.get(30,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        var data=api("olga.operator").get(path(instance)+"/data").body();
        assertThat(data.path("version").asInt()).isEqualTo(2);assertThat(data.path("changes")).hasSize(2);
    }
    @Test void rejectsUnreviewedFieldShapesBeforeCreatingAnotherDefinitionVersion() throws Exception {
        var definition=definition();
        var source=api("dana.designer").get("/workspaces/{ws}/cases/definitions/{id}",SANDBOX,definition.path("id").asString()).body();
        for(var invalid:List.of(
                List.of(Map.of("key","bad.key","label","Bad key","type","text")),
                List.of(Map.of("key","field","label","Unknown type","type","object")),
                List.of(Map.of("key","field","label","No choices","type","choice")),
                List.of(Map.of("key","field","label","Bad bounds","type","number","minimum",5,"maximum",1)),
                List.of(Map.of("key","field","label","Wrong bounds","type","text","minimum",1)),
                List.of(Map.of("key","field","label","Lossy bound","type","number","minimum",new java.math.BigDecimal("0.10000000000000001"))),
                List.of(fields.getFirst(),fields.getFirst()))) {
            var response=api("dana.designer").post(Map.of("key",definition.path("key").asString(),"name","Invalid schema","sourceXml",source.path("sourceXml").asString(),"dataFields",invalid),"/workspaces/{ws}/cases/definitions",SANDBOX);
            assertThat(response.status()).as(response.toString()).isEqualTo(422);
        }
    }

    private int competing(JsonNode instance,String summary) {
        try { return update(instance,"mani.manager",1,UUID.randomUUID().toString(),Map.of("summary",summary)).status(); }
        catch(Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
    }
}
