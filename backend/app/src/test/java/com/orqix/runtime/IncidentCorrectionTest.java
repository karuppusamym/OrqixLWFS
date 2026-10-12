package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.flowable.engine.RuntimeService;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.node.ObjectNode;

class IncidentCorrectionTest extends RuntimeTestBase {
    @Autowired RuntimeService engine;
    @Autowired com.orqix.integration.EffectJournal journal;
    private UUID workflow() throws Exception {
        return publishPurchase(PURCHASING,d->{
            ((ObjectNode)d.path("inputSchema").path("properties").path("title")).put("incidentEditable",true);
            d.set("nodes",json.parse("""
                [{"id":"start","type":"start","label":"Request","config":{}},
                 {"id":"receipt","type":"generateDocument","label":"Generate receipt","config":{"format":"docx","fileName":"Receipt","template":"Request: {{title}}","inputMapping":{"title":"/input/title"}}},
                 {"id":"end","type":"end","label":"Ready","config":{"businessOutcome":"Completed","outcomeKind":"success"}}]
                """));
            d.set("transitions",json.parse("[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"receipt\",\"on\":\"next\"},{\"id\":\"t2\",\"from\":\"receipt\",\"to\":\"end\",\"on\":\"next\"}]"));
            d.set("decisions",json.parse("[]"));d.set("dependencies",json.parse("{\"connections\":[]}"));d.set("outputMapping",json.object());
            d.set("outputSchema",json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));d.remove("uiLayout");
        });
    }
    private UUID failed() throws Exception {
        Map<String,Object> input=input(BigDecimal.TEN);input.put("title","Bad\u0001title");
        var created=api("rhea.requester").post(Map.of("workflowId",workflow(),"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID run=UUID.fromString(created.body().path("id").asString());awaitLifecycle(PURCHASING,run,"needsAttention");return run;
    }
    @Test void correctsFirstFailedPureStepAtomicallyWithReplayAndExplicitRetry() throws Exception {
        UUID run=failed();var before=api("olga.operator").get("/workspaces/{ws}/runs/{run}",PURCHASING,run).body();
        int version=before.path("version").asInt();String key=UUID.randomUUID().toString();
        var body=Map.of("expectedVersion",version,"reason","Remove unsupported control character","changes",Map.of("title","Corrected title"));
        assertThat(api("rhea.requester").post(body,key,"/workspaces/{ws}/runs/{run}/correct-input",PURCHASING,run).status()).isEqualTo(403);
        var corrected=api("olga.operator").post(body,key,"/workspaces/{ws}/runs/{run}/correct-input",PURCHASING,run);
        assertThat(corrected.status()).as(corrected.toString()).isEqualTo(200);
        assertThat(corrected.body().path("version").asInt()).isEqualTo(version+1);
        assertThat(lifecycle(PURCHASING,run)).isEqualTo("needsAttention");
        assertThat(json.parse((String)engine.getVariable(processInstance(PURCHASING,run),ProcessData.INPUT)).path("title").asString()).isEqualTo("Corrected title");
        var replay=api("olga.operator").post(body,key,"/workspaces/{ws}/runs/{run}/correct-input",PURCHASING,run);
        assertThat(replay.status()).isEqualTo(200);assertThat(replay.body().path("replayed").asBoolean()).isTrue();
        assertThat(replay.body().path("version").asInt()).isEqualTo(version+1);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:run AND type='INPUT_CORRECTED'").param("run",run).query(Integer.class).single()).isEqualTo(1);
        var metadata=api("vic.viewer").get("/workspaces/{ws}/runs/{run}/events",PURCHASING,run).body();
        assertThat(metadata.toString()).doesNotContain("Corrected title","Bad\\u0001title","Remove unsupported");
        var audit=jdbc.sql("SELECT metadata::text FROM audit_event WHERE action='run.input-corrected' AND resource_id=:run").param("run",run.toString()).query(String.class).single();
        assertThat(audit).doesNotContain("title","Remove unsupported").contains("fields","version");
        var stale=api("olga.operator").post(body,UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/correct-input",PURCHASING,run);
        assertThat(stale.status()).isEqualTo(409);
        var retry=api("olga.operator").post(Map.of("expectedVersion",version+1,"reason","Input corrected"),UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/retry",PURCHASING,run);
        assertThat(retry.status()).as(retry.toString()).isEqualTo(200);awaitLifecycle(PURCHASING,run,"completed");
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment WHERE run_id=:run").param("run",run).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void refusesNonEditableInvalidValuesAndPriorEffects() throws Exception {
        UUID run=failed();int version=api("olga.operator").get("/workspaces/{ws}/runs/{run}",PURCHASING,run).body().path("version").asInt();
        for (var changes:List.of(Map.of("amount",20),Map.of("title",true))) {
            var result=api("olga.operator").post(Map.of("expectedVersion",version,"reason","Correction","changes",changes),UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/correct-input",PURCHASING,run);
            assertThat(result.status()).as(result.toString()).isEqualTo(422);
        }
        UUID effectRun=UUID.fromString(start("rhea.requester",PURCHASING,publishPurchase(PURCHASING,null),BigDecimal.TEN).path("id").asString());
        approve("mani.manager",PURCHASING,effectRun,"manager");awaitLifecycle(PURCHASING,effectRun,"completed");
        var result=api("olga.operator").post(Map.of("expectedVersion",1,"reason","Unsafe","changes",Map.of("title","Replacement")),UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/correct-input",PURCHASING,effectRun);
        assertThat(result.status()).isEqualTo(409);
    }
    @Test void refusesEvenAnUnfinishedCapturedEffectOnAnOtherwiseCorrectableIncident() throws Exception {
        UUID run=failed();int version=api("olga.operator").get("/workspaces/{ws}/runs/{run}",PURCHASING,run).body().path("version").asInt();
        journal.begin(new com.orqix.integration.EffectJournal.EffectRequest(PURCHASING,run,"receipt",1,
                UUID.randomUUID().toString(),"procurement",1,"createPurchaseOrder","idempotentWithKey","0".repeat(64)),
                "test-captured",java.time.Duration.ofMinutes(1),null);
        var result=api("olga.operator").post(Map.of("expectedVersion",version,"reason","Captured input","changes",Map.of("title","Corrected title")),
                UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/correct-input",PURCHASING,run);
        assertThat(result.status()).as(result.toString()).isEqualTo(409);
        assertThat(result.body().path("detail").asString()).contains("external effect");
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:run AND type='INPUT_CORRECTED'").param("run",run).query(Integer.class).single()).isZero();
    }
}
