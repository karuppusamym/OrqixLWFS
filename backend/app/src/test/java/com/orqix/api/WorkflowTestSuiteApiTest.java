package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.IntegrationTestBase;

class WorkflowTestSuiteApiTest extends IntegrationTestBase {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    UUID workflow() throws Exception {
        var response=api("dana.designer").post(Map.of("key","suite-"+UUID.randomUUID().toString().substring(0,8),"name","Shared tests","templateId","purchase-approval"),"/workspaces/{ws}/workflows",PURCHASING);
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }
    Map<String,Object> testCase(String expect) {
        return Map.of("id","small","name","Small purchase","input",Map.of("title","Laptop","purpose","Replacement","costCenter","IT","amount",1800),"expect",expect);
    }
    Response save(UUID id, int version, String expect) throws Exception {
        return api("dana.designer").put(Map.of("expectedVersion",version,"cases",List.of(testCase(expect))),null,"/workspaces/{ws}/workflows/{id}/test-suite",PURCHASING,id);
    }
    Response publish(UUID id,int revision) throws Exception {
        return api("paul.publisher").post(Map.of("draftRevision",revision,"acknowledgeWarnings",true),UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{id}/publish",PURCHASING,id);
    }
    @Test void immutableSuiteCasAndDesignerOnlySamples() throws Exception {
        UUID id=workflow();
        assertThat(save(id,0,"Fulfilled").body().path("version").asInt()).isEqualTo(1);
        assertThat(save(id,0,"Rejected").status()).isEqualTo(409);
        assertThat(api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/test-suite",PURCHASING,id).status()).isEqualTo(403);
        assertThat(api("paul.publisher").get("/workspaces/{ws}/workflows/{id}/test-suite",PURCHASING,id).status()).isEqualTo(403);
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/test-suite",FACILITIES,id).status()).isEqualTo(404);
    }
    @Test void assertionsAndMocksInSharedTestsAndACountOnlySummaryForReaders() throws Exception {
        UUID id=workflow();
        var empty=api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/test-suite/summary",PURCHASING,id);
        assertThat(empty.status()).as(empty.toString()).isEqualTo(200);
        assertThat(empty.body().path("cases").asInt()).isZero();
        assertThat(empty.body().path("lastRun").isNull()).isTrue();
        Map<String,Object> mocked=Map.of("id","mocked","name","Mocked order","input",Map.of("title","Laptop","purpose","Replacement","costCenter","IT","amount",1800),
            "scenario",Map.of("calls",Map.of("procure",Map.of("status",201,"body",Map.of("id","PO-SECRET-9","status","CREATED")))),
            "expect","Fulfilled",
            "assertions",Map.of("outputs",List.of(Map.of("field","/steps/procure/procurementId","op","equals","value","PO-OTHER"))));
        var saved=api("dana.designer").put(Map.of("expectedVersion",0,"cases",List.of(testCase("Fulfilled"),mocked)),null,"/workspaces/{ws}/workflows/{id}/test-suite",PURCHASING,id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        var badAssertion=api("dana.designer").put(Map.of("expectedVersion",1,"cases",List.of(Map.of("id","x","name","x","input",Map.of(),"expect","Fulfilled","assertions",Map.of("path",Map.of("steps",List.of()))))),null,"/workspaces/{ws}/workflows/{id}/test-suite",PURCHASING,id);
        assertThat(badAssertion.status()).isEqualTo(422);
        var run=api("dana.designer").post(Map.of("revision",1,"suiteVersion",1),"/workspaces/{ws}/workflows/{id}/test-suite/run",PURCHASING,id);
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        assertThat(run.body().path("passed").asBoolean()).isFalse();
        var second=run.body().path("results").get(1);
        assertThat(second.path("passed").asBoolean()).isFalse();
        assertThat(second.path("failures").get(0).path("expected").asString()).isEqualTo("equals \"PO-OTHER\"");
        assertThat(second.path("failures").get(0).path("actual").asString()).isEqualTo("\"PO-SECRET-9\"");
        // What is kept of the run holds no value of the samples.
        String stored=jdbc.sql("SELECT results::text FROM workflow_test_result WHERE workflow_id=:wf").param("wf",id).query(String.class).single();
        assertThat(stored).doesNotContain("PO-SECRET-9","PO-OTHER","Laptop");
        var summary=api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/test-suite/summary",PURCHASING,id);
        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.body().path("suiteVersion").asInt()).isEqualTo(1);
        assertThat(summary.body().path("cases").asInt()).isEqualTo(2);
        assertThat(summary.body().path("lastRun").path("passedCases").asInt()).isEqualTo(1);
        assertThat(summary.body().path("lastRun").path("totalCases").asInt()).isEqualTo(2);
        assertThat(summary.body().path("lastRun").path("revision").asInt()).isEqualTo(1);
        assertThat(summary.body().toString()).doesNotContain("Laptop","Mocked order","PO-");
        assertThat(api("fred.facilities").get("/workspaces/{ws}/workflows/{id}/test-suite/summary",PURCHASING,id).status()).isEqualTo(404);
    }
    @Test void publicationRequiresPassingCurrentSuiteAndExactDraft() throws Exception {
        UUID id=workflow(); save(id,0,"Fulfilled");
        assertThat(publish(id,1).status()).isEqualTo(422);
        var run=api("dana.designer").post(Map.of("revision",1,"suiteVersion",1),"/workspaces/{ws}/workflows/{id}/test-suite/run",PURCHASING,id);
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        assertThat(run.body().path("passed").asBoolean()).isTrue();
        assertThat(run.body().toString()).doesNotContain("Laptop","Replacement","costCenter");
        assertThat(publish(id,1).status()).isEqualTo(201);
        var draft=api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft",PURCHASING,id);
        api("dana.designer").put(Map.of("definition",draft.body().path("definition")),"\"r1\"","/workspaces/{ws}/workflows/{id}/draft",PURCHASING,id);
        assertThat(publish(id,2).status()).isEqualTo(422);
        save(id,1,"Rejected");
        var fail=api("dana.designer").post(Map.of("revision",2,"suiteVersion",2),"/workspaces/{ws}/workflows/{id}/test-suite/run",PURCHASING,id);
        assertThat(fail.status()).as(fail.toString()).isEqualTo(200);
        assertThat(fail.body().path("passed").asBoolean()).isFalse();
        assertThat(publish(id,2).status()).isEqualTo(422);
    }
}
