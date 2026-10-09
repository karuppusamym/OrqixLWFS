package com.orqix.migration;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL, runtime-role permissions, compiler, Flowable and WireMock; responses checked against OpenAPI. */
class MigrationApiTest extends RuntimeTestBase {
    private String fixture(String version) throws Exception { return Files.readString(Path.of("../../examples/migration/camunda"+version+"-purchase.bpmn")); }
    private JsonNode upload(String content,String format) throws Exception {
        var response=api("dana.designer").post(Map.of("fileName","migration-fixture","content",content,"sourceFormat",format),"/workspaces/{ws}/migrations",PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201); return response.body();
    }
    private ObjectNode request() throws Exception {
        var template=json.parse(Files.readString(Path.of("../../examples/purchase-approval.json")));
        var request=json.object(); request.put("workflowKey","import-"+UUID.randomUUID().toString().substring(0,8)); request.put("name","Imported purchase");
        request.put("acknowledgeInfo",true); request.set("inputSchema",template.path("inputSchema"));
        request.set("groupMappings",json.toTree(Map.of("source-managers","managers","source-finance","finance-approvers")));
        for(var node:template.path("nodes")) if(node.path("id").asString().equals("procure"))
            request.set("serviceTaskMappings",json.toTree(Map.of("procure",node.path("config"))));
        return request;
    }
    private Response convert(JsonNode job,ObjectNode request) throws Exception {
        return api("dana.designer").post(request,"/workspaces/{ws}/migrations/{id}/convert",PURCHASING,job.path("id").asString());
    }
    private JsonNode startImported(String version, UUID workflow, BigDecimal amount) throws Exception {
        var values = input(amount);
        if (version.equals("8")) values.remove("purpose"); // Not referenced by the source or inferred connector schema.
        var started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", values),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return started.body();
    }
    @Test void camunda7And8ConvertAndExecuteApprovalThresholdAndRejection() throws Exception {
        for(String version:List.of("7","8")) {
            var job=upload(fixture(version),"bpmn"); assertThat(job.path("report").path("sourceSystem").asString()).isEqualTo("camunda"+version);
            var request=request();
            if (version.equals("8")) request.remove("inputSchema"); // Inference must preserve connector enums and constraints.
            var converted=convert(job,request);
            assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
            String workflow=converted.body().path("workflow").path("id").asString(); UUID wf=UUID.fromString(workflow);
            assertThat(converted.body().path("workflow").path("activeRelease").isNull()).isTrue();
            var again=convert(job,request); assertThat(again.body().path("workflow").path("id").asString()).isEqualTo(workflow);
            var different=request.deepCopy(); different.put("name","Different conversion"); assertThat(convert(job,different).status()).isEqualTo(409);
            var publish=api("paul.publisher").post(Map.of("draftRevision",1,"acknowledgeWarnings",true),UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{id}/publish",PURCHASING,wf);
            assertThat(publish.status()).as(publish.toString()).isEqualTo(201);
            for(BigDecimal amount:List.of(new BigDecimal("4999.99"),new BigDecimal("5000"))) {
                UUID runId=UUID.fromString(startImported(version,wf,amount).path("id").asString());
                approve("mani.manager",PURCHASING,runId,"manager");
                if(amount.compareTo(new BigDecimal("5000"))>=0) {
                    assertThat(openTask(PURCHASING,runId,"finance")).isNotNull(); approve("fin.finance",PURCHASING,runId,"finance");
                } else assertThat(openTask(PURCHASING,runId,"finance")).isNull();
                awaitLifecycle(PURCHASING,runId,"completed","needsAttention"); assertThat(lifecycle(PURCHASING,runId)).isEqualTo("completed");
            }
            UUID rejected=UUID.fromString(startImported(version,wf,BigDecimal.ONE).path("id").asString());
            var task=openTask(PURCHASING,rejected,"manager");
            assertThat(claim("mani.manager",PURCHASING,task.path("id").asString()).status()).isEqualTo(200);
            assertThat(decide("mani.manager",PURCHASING,task.path("id").asString(),"reject","Declined after migration").status()).isEqualTo(201);
            awaitLifecycle(PURCHASING,rejected,"completed"); assertThat(run(PURCHASING,rejected).path("effects").isEmpty()).isTrue();
        }
    }
    @Test void permissionsXxeUnmappedTasksAndCompilerFailureNeverCreateDrafts() throws Exception {
        assertThat(api("rhea.requester").post(Map.of("fileName","x","content",fixture("7")),"/workspaces/{ws}/migrations",PURCHASING).status()).isEqualTo(403);
        assertThat(api("dana.designer").post(Map.of("fileName","x","content","<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>"),"/workspaces/{ws}/migrations",PURCHASING).status()).isEqualTo(422);
        var job=upload(fixture("7"),"bpmn");
        assertThat(api("otto.outsider").get("/workspaces/{ws}/migrations/{id}",PURCHASING,job.path("id").asString()).status()).isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}",SANDBOX,job.path("id").asString()).status()).isEqualTo(404);
        var bytes=jdbc.sql("SELECT source FROM migration_job WHERE workspace_id=:ws AND id=:id").param("ws",PURCHASING)
                .param("id",UUID.fromString(job.path("id").asString())).query(byte[].class).single();
        assertThat(bytes).isEqualTo(fixture("7").getBytes(StandardCharsets.UTF_8));
        var request=request(); request.remove("serviceTaskMappings");
        assertThat(convert(job,request).status()).isEqualTo(422);
        request=request(); request.set("inputSchema",json.toTree(Map.of("type","object","additionalProperties",false,"properties",Map.of())));
        assertThat(convert(job,request).status()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id=:ws AND key=:key").param("ws",PURCHASING).param("key",request.path("workflowKey").asString()).query(Integer.class).single()).isZero();
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}",PURCHASING,job.path("id").asString()).body().path("status").asString()).isEqualTo("analyzed");
    }
    @Test void pegaArchiveAndChecksumLinkedManualMappingCreateValidatedDraft() throws Exception {
        var buffer=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(buffer)) { zip.putNextEntry(new ZipEntry("rules/process.xml")); zip.write("<synthetic-rule/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        var archive=upload(Base64.getEncoder().encodeToString(buffer.toByteArray()),"pegaArchive");
        assertThat(convert(archive,request()).status()).isEqualTo(422);
        ObjectNode pkg=json.object(); pkg.put("format","orqix-pega-mapping-0.1"); pkg.put("archiveJobId",archive.path("id").asString());
        pkg.put("sourceArchiveHash",archive.path("report").path("sourceHash").asString()); pkg.put("sourceVersion","synthetic-test-version");
        var definition=json.parse(Files.readString(Path.of("../../examples/purchase-approval.json")));
        pkg.set("definition",definition);
        List<String> nodeIds=new ArrayList<>(); definition.path("nodes").forEach(n->nodeIds.add(n.path("id").asString()));
        pkg.set("fileMappings",json.toTree(Map.of("rules/process.xml",Map.of("disposition","mapped","targetNodeIds",nodeIds,"explanation","Hand-authored test mapping, not a native Pega conversion."))));
        var mapping=upload(json.write(pkg),"pegaMapping"); var request=request(); request.put("acknowledgeInfo",false);
        assertThat(convert(mapping,request).status()).isEqualTo(422);
        request.put("acknowledgeInfo",true); var converted=convert(mapping,request);
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        assertThat(converted.body().path("report").path("sourceSystem").asString()).isEqualTo("pega");
        pkg.put("sourceArchiveHash","0".repeat(64));
        assertThat(api("dana.designer").post(Map.of("fileName","bad.json","content",json.write(pkg),"sourceFormat","pegaMapping"),"/workspaces/{ws}/migrations",PURCHASING).status()).isEqualTo(422);
    }
    @Test void malformedAndUnknownMappingsCannotCreateOrChangeDrafts() throws Exception {
        var job = upload(fixture("7"), "bpmn");
        var valid = request();
        List<ObjectNode> malformed = new ArrayList<>();
        var bad = valid.deepCopy(); bad.put("acknowledgeInfo", "true"); malformed.add(bad);
        bad = valid.deepCopy(); bad.put("inputSchema", "schema"); malformed.add(bad);
        bad = valid.deepCopy(); bad.put("unexpected", true); malformed.add(bad);
        bad = valid.deepCopy(); bad.putArray("serviceTaskMappings"); malformed.add(bad);
        bad = valid.deepCopy(); ((ObjectNode) bad.path("serviceTaskMappings").path("procure")).put("definitionVersion", 1.5); malformed.add(bad);
        bad = valid.deepCopy(); ((ObjectNode) bad.path("serviceTaskMappings").path("procure")).put("timeoutSeconds", "10"); malformed.add(bad);
        for (var candidate : malformed) assertThat(convert(job, candidate).status()).as(candidate.toString()).isEqualTo(400);
        bad = valid.deepCopy(); ((ObjectNode) bad.path("serviceTaskMappings")).set("typo-task", bad.path("serviceTaskMappings").path("procure"));
        assertThat(convert(job, bad).status()).isEqualTo(422);
        bad = valid.deepCopy(); ((ObjectNode) bad.path("groupMappings")).put("typo-group", "managers");
        assertThat(convert(job, bad).status()).isEqualTo(422);
        bad = valid.deepCopy(); bad.set("approvalRoutes", json.toTree(Map.of("manager", Map.of("approvedFlowId", "managerRejected", "rejectedFlowId", "managerApproved"))));
        assertThat(convert(job, bad).status()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id=:ws AND key=:key").param("ws", PURCHASING)
                .param("key", valid.path("workflowKey").asString()).query(Integer.class).single()).isZero();
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}", PURCHASING, job.path("id").asString()).body().path("status").asString()).isEqualTo("analyzed");
        assertThat(convert(job, valid).status()).isEqualTo(201);
    }
    @Test void sourceMapIncludesAbsorbedApprovalFlowsAndMergeGateways() throws Exception {
        String source = fixture("7").replace("targetRef=\"procure\"", "targetRef=\"serviceMerge\"")
                .replace("<bpmn:serviceTask", "<bpmn:exclusiveGateway id=\"serviceMerge\" /><bpmn:serviceTask")
                .replace("</bpmn:process>", "<bpmn:sequenceFlow id=\"mergeToProcure\" sourceRef=\"serviceMerge\" targetRef=\"procure\" /></bpmn:process>");
        var job = upload(source, "bpmn"); var converted = convert(job, request());
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        var map = api("dana.designer").get("/workspaces/{ws}/migrations/{id}", PURCHASING, job.path("id").asString()).body().path("sourceMap");
        assertThat(map.path("managerOutcome").asString()).isEqualTo("manager");
        assertThat(map.path("managerDecided").asString()).isEqualTo("manager");
        assertThat(map.path("financeDecided").asString()).isEqualTo("finance");
        assertThat(map.path("serviceMerge").asString()).isEqualTo("procure");
        assertThat(map.path("mergeToProcure").asString()).isEqualTo("procure");
    }
}
