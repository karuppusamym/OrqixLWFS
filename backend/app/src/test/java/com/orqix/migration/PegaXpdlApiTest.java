package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class PegaXpdlApiTest extends RuntimeTestBase {
    private String fixture(String name) throws Exception { return Files.readString(Path.of("../../examples/migration/pega-xpdl/"+name+".xpdl")); }
    private JsonNode upload(String source) throws Exception {
        var result=api("dana.designer").post(Map.of("fileName","flow.xpdl","content",source,"sourceFormat","pegaXpdl"),"/workspaces/{ws}/migrations",PURCHASING);
        assertThat(result.status()).as(result.toString()).isEqualTo(201); return result.body();
    }
    private ObjectNode request() {
        return json.object().put("workflowKey","xpdl-"+UUID.randomUUID().toString().substring(0,8)).put("name","Automatic XPDL flow").put("acknowledgeInfo",true).put("acknowledgeReview",true);
    }
    private Response convert(JsonNode job,ObjectNode request) throws Exception { return api("dana.designer").post(request,"/workspaces/{ws}/migrations/{id}/convert",PURCHASING,job.path("id").asString()); }
    private UUID publishConverted(JsonNode converted) throws Exception {
        UUID id=UUID.fromString(converted.path("workflow").path("id").asString());
        assertThat(converted.path("workflow").path("activeRelease").isNull()).isTrue();
        int revision=converted.path("draft").path("revision").asInt();
        var published=api("paul.publisher").post(Map.of("draftRevision",revision,"acknowledgeWarnings",true),UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{id}/publish",PURCHASING,id);
        assertThat(published.status()).as(published.toString()).isEqualTo(201); return id;
    }
    private UUID start(UUID workflow,Map<String,?> input) throws Exception {
        var started=api("rhea.requester").post(Map.of("workflowId",workflow,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201); return UUID.fromString(started.body().path("id").asString());
    }
    @Test void automaticallyBuildsSmallAndConditionalFlowsAndRunsBoundaryCases() throws Exception {
        for(String name:List.of("small","medium")) {
            String source=fixture(name); var job=upload(source); var request=request();
            var noAck=convert(job,request.deepCopy().put("acknowledgeInfo",false)); assertThat(noAck.status()).isEqualTo(422);
            var result=convert(job,request); assertThat(result.status()).as(result.toString()).isEqualTo(201);
            assertThat(result.body().path("report").path("sourceHash").asString()).isEqualTo(job.path("report").path("sourceHash").asString());
            assertThat(result.body().path("report").path("adapterVersion").asString()).isEqualTo("pega-xpdl-0.1");
            var stored=api("dana.designer").get("/workspaces/{ws}/migrations/{id}",PURCHASING,job.path("id").asString()).body();
            assertThat(stored.path("sourceMap").isEmpty()).isFalse();
            UUID workflow=publishConverted(result.body());
            if(name.equals("small")) { UUID run=start(workflow,Map.of()); awaitLifecycle(PURCHASING,run,"completed","needsAttention"); assertThat(run(PURCHASING,run).path("outcome").asString()).isEqualTo("Done"); }
            else for(String amount:List.of("4999.99","5000","5000.01")) {
                UUID run=start(workflow,Map.of("amount",new BigDecimal(amount))); awaitLifecycle(PURCHASING,run,"completed","needsAttention");
                assertThat(run(PURCHASING,run).path("outcome").asString()).isEqualTo(amount.equals("4999.99")?"Standard":"Finance");
            }
            assertThat(convert(job,request).status()).isEqualTo(201);
            assertThat(convert(job,request.deepCopy().put("name","Changed")).status()).isEqualTo(409);
        }
    }
    @Test void mapsLiteralPerformerAndExplicitApprovalRoutesWithoutAManualCanonicalDefinition() throws Exception {
        var job=upload(fixture("review-branch")); var request=request();
        assertThat(convert(job,request).status()).isEqualTo(422);
        request.putObject("groupMappings").put("Managers","managers");
        request.putObject("approvalRoutes").putObject("review").put("approvedFlowId","approvedPath").put("rejectedFlowId","rejectedPath");
        var converted=convert(job,request); assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        UUID workflow=publishConverted(converted.body());
        UUID approved=start(workflow,Map.of("amount",5000));
        awaitLifecycle(PURCHASING,approved,"waiting","needsAttention"); approve("mani.manager",PURCHASING,approved,"review");
        awaitLifecycle(PURCHASING,approved,"completed","needsAttention"); assertThat(run(PURCHASING,approved).path("outcome").asString()).isEqualTo("Finance");
        UUID rejected=start(workflow,Map.of("amount",5000)); awaitLifecycle(PURCHASING,rejected,"waiting","needsAttention");
        var task=openTask(PURCHASING,rejected,"review"); assertThat(claim("mani.manager",PURCHASING,task.path("id").asString()).status()).isEqualTo(200);
        assertThat(decide("mani.manager",PURCHASING,task.path("id").asString(),"reject","Not approved").status()).isEqualTo(201);
        awaitLifecycle(PURCHASING,rejected,"completed","needsAttention"); assertThat(run(PURCHASING,rejected).path("outcome").asString()).isEqualTo("Rejected");
    }
    @Test void unsupportedRulesStayBlockedAndWorkspacePermissionsStillApply() throws Exception {
        String source=fixture("small").replace("<Activities>","<ExtendedAttributes><ExtendedAttribute Name=\"RunJava\" Value=\"doNotExecute()\"/></ExtendedAttributes><Activities>");
        var job=upload(source); var request=request();
        assertThat(job.path("report").path("elementCounts").path("blocker").asInt()).isPositive();
        assertThat(convert(job,request).status()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id=:ws AND key=:key").param("ws",PURCHASING).param("key",request.path("workflowKey").asString()).query(Integer.class).single()).isZero();
        assertThat(api("rhea.requester").post(Map.of("fileName","flow.xpdl","sourceFormat","pegaXpdl","content",fixture("small")),"/workspaces/{ws}/migrations",PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/migrations/{id}",PURCHASING,job.path("id").asString()).status()).isEqualTo(404);
    }
}
