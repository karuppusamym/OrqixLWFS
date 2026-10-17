package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.integration.EffectDispatcher;
import com.orqix.integration.EffectJournal;
import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;
import tools.jackson.databind.node.ObjectNode;

/** Published real-engine failure boundaries; unknown effects and recoverable incidents never take that path. */
class FailureRoutesRuntimeTest extends RuntimeTestBase {
    @Autowired EffectJournal journal;

    private UUID workflow(String connection, boolean readOnly) throws Exception {
        return publishPurchase(PURCHASING, definition -> {
            var nodes = (tools.jackson.databind.node.ArrayNode)definition.path("nodes");
            nodes.add(json.parse("{\"id\":\"handled\",\"type\":\"end\",\"label\":\"Handled system rejection\",\"config\":{\"businessOutcome\":\"System failure handled\",\"outcomeKind\":\"failed\"}}"));
            ((tools.jackson.databind.node.ArrayNode)definition.path("transitions")).add(json.parse("{\"id\":\"systemFailed\",\"from\":\"procure\",\"to\":\"handled\",\"on\":\"failed\"}"));
            for(var node: nodes) if("procure".equals(node.path("id").asString())) {
                var config = (ObjectNode)node.path("config"); config.put("connectionRef",connection);
                if(readOnly) { config.put("operation","health").put("timeoutSeconds",3);config.set("inputMapping",json.object());config.set("resultMapping",json.object().put("proof","/proof")); }
            }
            ((ObjectNode)definition.path("dependencies").path("connections").get(0)).put("key",connection);
            definition.set("outputSchema",json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"failureKind\":{\"type\":\"string\",\"title\":\"Failure kind\",\"maxLength\":100},\"failureHttpStatus\":{\"type\":\"integer\",\"title\":\"HTTP status\"},\"failureMessage\":{\"type\":\"string\",\"title\":\"Failure message\",\"maxLength\":200}}}"));
            var mapping = json.object();for(String name:new String[]{"failureKind","failureHttpStatus","failureMessage"}) mapping.set(name,json.object().put("fieldRef","/steps/procure/"+name).put("required",false));
            definition.set("outputMapping",mapping);
        });
    }
    private UUID waiting(UUID workflow) throws Exception { return UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString()); }
    private void continueRun(UUID run) throws Exception { approve("mani.manager",PURCHASING,run,"manager"); }
    private long routed(UUID run) { return jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:id AND type='STEP_FAILED'").param("id",run).query(Long.class).single(); }

    @Test void knownClientRejectionTakesFailureBoundaryOnceAndPublishesRedactedFacts() throws Exception {
        UUID run = waiting(workflow("procurement",false));String processId=processInstance(PURCHASING,run);
        Stub.fault(Stub.Mode.STATUS400,1,0);continueRun(run);awaitLifecycle(PURCHASING,run,"completed","needsAttention");
        var completed=run(PURCHASING,run);assertThat(completed.path("lifecycle").asString()).isEqualTo("completed");assertThat(completed.path("outcome").asString()).isEqualTo("System failure handled");assertThat(completed.path("outcomeKind").asString()).isEqualTo("failed");
        assertThat(completed.path("output").path("failureKind").asString()).isEqualTo("rejected");assertThat(completed.path("output").path("failureHttpStatus").asInt()).isEqualTo(400);
        assertThat(completed.path("output").path("failureMessage").asString()).isEqualTo("The external system rejected this request.");
        assertThat(Stub.postCount()).isEqualTo(1);assertThat(routed(run)).isEqualTo(1);assertThat(managementService.createDeadLetterJobQuery().processInstanceId(processId).count()).isZero();
        assertThat(completed.path("effects").get(0).path("state").asString()).isEqualTo("permanentFailure");
    }
    @Test void crashWindowRecordedRejectionIsReusedWithoutSendingTheCallAgain() throws Exception {
        UUID run=waiting(workflow("procurement",false));
        var body=json.object().put("title","Replacement laptop").put("amount",BigDecimal.TEN).put("costCenter","OPS");
        var effect=journal.begin(new EffectJournal.EffectRequest(PURCHASING,run,"procure",1,EffectDispatcher.effectKey(PURCHASING,run,"procure",1),"procurement",1,"createOrder","idempotentWithKey",json.canonicalSha256(body)),"test-recorded-node",Duration.ofSeconds(30),null).effect();
        journal.finish(PURCHASING,effect.id(),EffectJournal.PERMANENT_FAILURE,400,"HTTP_400","Recorded rejection before engine continuation",null,null);
        continueRun(run);awaitLifecycle(PURCHASING,run,"completed","needsAttention");
        assertThat(lifecycle(PURCHASING,run)).isEqualTo("completed");assertThat(Stub.postCount()).isZero();assertThat(routed(run)).isEqualTo(1);
        assertThat(run(PURCHASING,run).path("output").path("failureKind").asString()).isEqualTo("rejected");
    }
    @Test void invalidReadOnlyResponseRoutesButMutatingInvalidResponseStaysUnknown() throws Exception {
        String key="readonly-failure-"+UUID.randomUUID().toString().substring(0,8);var connection=(ObjectNode)Fixtures.procurementConnection();connection.put("key",key);
        var health=(ObjectNode)connection.path("operations").path("health");health.set("responseSchema",json.parse("{\"type\":\"object\",\"required\":[\"proof\"],\"properties\":{\"proof\":{\"type\":\"string\"}}}"));
        assertThat(api("wade.admin").post(Map.of("definition",connection),"/workspaces/{ws}/connections/{key}/versions",PURCHASING,key).status()).isEqualTo(201);
        assertThat(api("wade.admin").put(Map.of("baseUrl",Stub.baseUrl(),"enabled",true,"auth",Map.of("type","none")),null,"/workspaces/{ws}/connections/{key}/binding",PURCHASING,key).status()).isEqualTo(200);
        UUID read=waiting(workflow(key,true));continueRun(read);awaitLifecycle(PURCHASING,read,"completed","needsAttention");
        assertThat(lifecycle(PURCHASING,read)).isEqualTo("completed");assertThat(run(PURCHASING,read).path("output").path("failureKind").asString()).isEqualTo("invalidResponse");assertThat(routed(read)).isEqualTo(1);
        UUID write=waiting(workflow("procurement",false));Stub.fault(Stub.Mode.INVALID_BODY,1,0);continueRun(write);awaitLifecycle(PURCHASING,write,"needsAttention","completed");
        assertThat(lifecycle(PURCHASING,write)).isEqualTo("needsAttention");assertThat(run(PURCHASING,write).path("attention").path("reason").asString()).isEqualTo("EFFECT_UNKNOWN");assertThat(routed(write)).isZero();
    }
    @Test void disabledConnectionAndExhaustedRetriesRemainIncidents() throws Exception {
        UUID disabled=waiting(workflow("procurement",false));jdbc.sql("UPDATE connection_binding SET enabled=false WHERE workspace_id=:ws AND connection_key='procurement'").param("ws",PURCHASING).update();
        continueRun(disabled);awaitLifecycle(PURCHASING,disabled,"needsAttention","completed");assertThat(lifecycle(PURCHASING,disabled)).isEqualTo("needsAttention");assertThat(routed(disabled)).isZero();assertThat(Stub.postCount()).isZero();
        bindStub(PURCHASING);UUID retries=waiting(workflow("procurement",false));Stub.fault(Stub.Mode.STATUS500,10,0);continueRun(retries);awaitLifecycle(PURCHASING,retries,"needsAttention","completed");
        assertThat(lifecycle(PURCHASING,retries)).isEqualTo("needsAttention");assertThat(run(PURCHASING,retries).path("attention").path("reason").asString()).isEqualTo("RETRIES_EXHAUSTED");assertThat(routed(retries)).isZero();
    }
    @Test void successfulCallStillTakesItsOriginalNextRoute() throws Exception {
        UUID run=waiting(workflow("procurement",false));continueRun(run);awaitLifecycle(PURCHASING,run,"completed","needsAttention");
        assertThat(run(PURCHASING,run).path("outcome").asString()).isEqualTo("Fulfilled");assertThat(routed(run)).isZero();assertThat(Stub.postCount()).isEqualTo(1);
    }
}
