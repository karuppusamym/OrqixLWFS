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
        request.put("acknowledgeInfo",true); request.put("acknowledgeReview",true); request.set("inputSchema",template.path("inputSchema"));
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
    @Test void importedParallelTimersAndPinnedChildExecuteToCompletion() throws Exception {
        String childSource = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:test\"><process id=\"simpleChild\" isExecutable=\"true\"><startEvent id=\"start\"/><endEvent id=\"end\"/><sequenceFlow id=\"route\" sourceRef=\"start\" targetRef=\"end\"/></process></definitions>";
        var childRequest = json.object();
        String childKey = "migration-child-" + UUID.randomUUID().toString().substring(0,8);
        childRequest.put("workflowKey", childKey); childRequest.put("name", "Imported child"); childRequest.put("acknowledgeInfo", true); childRequest.put("acknowledgeReview", true);
        var child = convert(upload(childSource, "bpmn"), childRequest);
        assertThat(child.status()).as(child.toString()).isEqualTo(201);
        var publishedChild = api("paul.publisher").post(Map.of("draftRevision",1,"acknowledgeWarnings",true), UUID.randomUUID().toString(),
            "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, child.body().path("workflow").path("id").asString());
        assertThat(publishedChild.status()).as(publishedChild.toString()).isEqualTo(201);
        var parentRequest = json.object(); parentRequest.put("workflowKey", "migration-parent-" + UUID.randomUUID().toString().substring(0,8));
        parentRequest.put("name", "Imported parallel timer child"); parentRequest.put("acknowledgeInfo", true); parentRequest.put("acknowledgeReview", true);
        parentRequest.set("childWorkflowMappings", json.toTree(Map.of("child", Map.of("workflowRef", childKey, "inputMapping", Map.of(), "resultMapping", Map.of()))));
        var parent = convert(upload(Files.readString(Path.of("../../examples/migration/parallel-timer-child.bpmn")), "bpmn"), parentRequest);
        assertThat(parent.status()).as(parent.toString()).isEqualTo(201);
        UUID workflow = UUID.fromString(parent.body().path("workflow").path("id").asString());
        var published = api("paul.publisher").post(Map.of("draftRevision",1,"acknowledgeWarnings",true), UUID.randomUUID().toString(),
            "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        var started = api("rhea.requester").post(Map.of("workflowId",workflow,"input",Map.of()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID runId = UUID.fromString(started.body().path("id").asString());
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workspace_id=:ws AND parent_run_id=:parent AND lifecycle='completed'")
            .param("ws",PURCHASING).param("parent",runId).query(Integer.class).single()).isEqualTo(1);
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
        request.put("acknowledgeInfo",true); request.put("acknowledgeReview",true); var converted=convert(mapping,request);
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

    // ------------------------------------------------------------------------------------------------ expansion (review, groups, operators, DMN, multi-instance)

    private String example(String name) throws Exception { return Files.readString(Path.of("../../examples/migration/"+name)); }
    private ObjectNode simpleRequest(String keyPrefix) {
        var request=json.object(); request.put("workflowKey",keyPrefix+"-"+UUID.randomUUID().toString().substring(0,8)); request.put("name","Imported "+keyPrefix);
        request.put("acknowledgeInfo",true); return request;
    }
    private UUID publishImported(JsonNode converted) throws Exception {
        UUID workflow=UUID.fromString(converted.path("workflow").path("id").asString());
        var publish=api("paul.publisher").post(Map.of("draftRevision",1,"acknowledgeWarnings",true),UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{id}/publish",PURCHASING,workflow);
        assertThat(publish.status()).as(publish.toString()).isEqualTo(201); return workflow;
    }
    private JsonNode startRun(UUID workflow,Map<String,Object> input) throws Exception {
        var started=api("rhea.requester").post(Map.of("workflowId",workflow,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID runId=UUID.fromString(started.body().path("id").asString());
        awaitLifecycle(PURCHASING,runId,"completed","needsAttention"); assertThat(lifecycle(PURCHASING,runId)).isEqualTo("completed");
        return run(PURCHASING,runId);
    }

    @Test void reviewItemsAreInTheReportCountsAndNeverBlockConversion() throws Exception {
        var job=upload(fixture("7"),"bpmn");
        assertThat(job.path("report").path("elementCounts").path("review").asInt()).isGreaterThan(0);
        var request=request(); request.remove("acknowledgeReview");
        var converted=convert(job,request); assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        var report=converted.body().path("report"); long open=0;
        for(var issue:report.path("issues")) if(issue.path("severity").asString().equals("review")) { assertThat(issue.path("resolutionStatus").asString()).isEqualTo("open"); open++; }
        assertThat(open).isEqualTo(report.path("elementCounts").path("review").asInt()).isGreaterThan(5);
        assertThat(report.path("publishable").asBoolean()).isTrue();
        // The persisted report carries the same counts.
        var persisted=api("dana.designer").get("/workspaces/{ws}/migrations/{id}",PURCHASING,job.path("id").asString()).body().path("report");
        assertThat(persisted.path("elementCounts").path("review").asInt()).isEqualTo(report.path("elementCounts").path("review").asInt());
        assertThat(persisted.path("issues").toString()).contains("\"reasonCode\":\"SERVICE_MAPPED\"","\"reasonCode\":\"APPROVAL_ROUTE_INFERRED\"");
    }

    @Test void groupedServiceTasksNeedOneMappingPerImplementation() throws Exception {
        var job=upload(example("service-task-groups.bpmn"),"bpmn");
        var groups=new ArrayList<String>(); job.path("report").path("issues").forEach(i -> { if(i.path("reasonCode").asString().equals("SERVICE_TASK_GROUP")) groups.add(i.path("sourceElementId").asString()); });
        assertThat(groups).containsExactly("topic:send-mail","class:com.example.AuditDelegate");
        assertThat(job.path("report").path("requiredMappings").path("serviceGroups").path("topic:send-mail").size()).isEqualTo(3);
        var template=json.parse(Files.readString(Path.of("../../examples/purchase-approval.json")));
        JsonNode procure=null; for(var node:template.path("nodes")) if(node.path("id").asString().equals("procure")) procure=node.path("config");
        var request=simpleRequest("grouped"); request.set("inputSchema",template.path("inputSchema"));
        var mappings=request.putObject("serviceGroupMappings");
        for(String key:List.of("topic:send-mail","class:com.example.AuditDelegate","class:com.example.ArchiveDelegate")) mappings.set(key,procure.deepCopy());
        var partial=request.deepCopy(); ((ObjectNode) partial.path("serviceGroupMappings")).remove("class:com.example.AuditDelegate");
        var blocked=convert(job,partial); assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().toString()).contains("class:com.example.AuditDelegate");
        var converted=convert(job,request); assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        long http=0; for(var node:converted.body().path("draft").path("definition").path("nodes")) if(node.path("type").asString().equals("http")) http++;
        assertThat(http).isEqualTo(6);
        publishImported(converted.body());
    }

    @Test void extendedConditionOperatorsConvertAndRouteCorrectlyThroughFlowable() throws Exception {
        for(String file:List.of("expression-operators.bpmn","expression-operators-feel.bpmn")) {
            var job=upload(example(file),"bpmn");
            var report=job.path("report"); assertThat(report.path("elementCounts").path("review").asInt()).isGreaterThan(4);
            var conditions=0; for(var issue:report.path("issues")) if(issue.path("reasonCode").asString().equals("CONDITION_CONVERTED")) conditions++;
            assertThat(conditions).isEqualTo(4);
            var converted=convert(job,simpleRequest("operators")); assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
            UUID workflow=publishImported(converted.body());
            record Case(String amount,String region,String risk,String score,String outcome) { }
            for(var c:List.of(new Case("10000.01","EU","low","1","Large"),new Case("10000","EU","low","1","Medium"),new Case("1000.01","EU","low","1","Medium"),
                    new Case("1000","EU","low","1","Routine"),new Case("999.99","US","low","1","Outside"),new Case("500","EU","high","1","Escalated"),
                    new Case("500","EU","low","80","Escalated"),new Case("500","EU","low","79.99","Routine"))) {
                var input=new LinkedHashMap<String,Object>(); input.put("amount",new BigDecimal(c.amount())); input.put("region",c.region()); input.put("riskLevel",c.risk()); input.put("riskScore",new BigDecimal(c.score()));
                assertThat(startRun(workflow,input).path("outcome").asString()).as(file+" "+c).isEqualTo(c.outcome());
            }
        }
    }

    @Test void dmnDecisionTableConvertsIntoACompilerValidatedDraftAndKeepsEveryThreshold() throws Exception {
        var job=upload(example("dmn-approval-level.dmn"),"dmn");
        var report=job.path("report");
        assertThat(report.path("sourceSystem").asString()).isEqualTo("dmn");
        assertThat(report.path("adapterVersion").asString()).isEqualTo("dmn-0.1");
        assertThat(report.path("elementCounts").path("review").asInt()).isGreaterThan(3);
        assertThat(report.path("elementCounts").path("blocker").asInt()).isZero();
        assertThat(report.path("testCases").size()).isGreaterThanOrEqualTo(8);
        for(var testCase:report.path("testCases")) assertThat(testCase.path("status").asString()).as(testCase.toString()).isEqualTo("passed");
        var request=simpleRequest("dmn-level"); request.put("acknowledgeInfo",false);
        var unacknowledged=convert(job,request); assertThat(unacknowledged.status()).isEqualTo(422);
        request.put("acknowledgeInfo",true);
        var converted=convert(job,request); assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        assertThat(convert(job,request).body().path("workflow").path("id").asString()).isEqualTo(converted.body().path("workflow").path("id").asString());
        UUID workflow=UUID.fromString(converted.body().path("workflow").path("id").asString());
        var definition=converted.body().path("draft").path("definition");
        assertThat(definition.path("decisions").size()).isEqualTo(1);
        assertThat(definition.path("decisions").get(0).path("rows").size()).isEqualTo(2);
        assertThat(converted.body().path("report").path("testCases").size()).isEqualTo(report.path("testCases").size());
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}",PURCHASING,job.path("id").asString()).body().path("sourceMap").path("approvalLevel").asString()).isEqualTo("decide");
        // Boundary values through the draft's own rule tester ...
        String key=definition.path("decisions").get(0).path("key").asString();
        record Boundary(String amount,String level,int approvers) { }
        var boundaries=List.of(new Boundary("999.99","auto",0),new Boundary("1000","manager",1),new Boundary("4999.99","manager",1),new Boundary("5000","finance",2));
        for(var b:boundaries) {
            var evaluated=api("dana.designer").post(Map.of("revision",1,"values",Map.of("/input/amount",new BigDecimal(b.amount()))),
                    "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate",PURCHASING,workflow,key);
            assertThat(evaluated.status()).as(evaluated.toString()).isEqualTo(200);
            assertThat(evaluated.body().path("output").path("level").asString()).as(b.amount()).isEqualTo(b.level());
            assertThat(evaluated.body().path("output").path("approvers").asInt()).isEqualTo(b.approvers());
        }
        // ... and through a published release executed by Flowable.
        publishImported(converted.body());
        for(var b:boundaries) {
            var output=startRun(workflow,Map.of("amount",new BigDecimal(b.amount()))).path("output");
            assertThat(output.path("level").asString()).as(b.amount()).isEqualTo(b.level());
            assertThat(output.path("approvers").asInt()).isEqualTo(b.approvers());
        }
    }

    @Test void unsupportedDmnIsReportedWithReasonCodesAndNeverCreatesADraft() throws Exception {
        var job=upload(example("dmn-unsupported.dmn"),"dmn");
        var report=job.path("report");
        assertThat(report.path("publishable").asBoolean()).isFalse();
        assertThat(report.path("elementCounts").path("blocker").asInt()).isGreaterThan(12);
        var codes=new java.util.TreeSet<String>(); report.path("issues").forEach(i -> codes.add(i.path("reasonCode").asString()));
        assertThat(codes).contains("DMN_HIT_POLICY_NOT_SUPPORTED","DMN_ENTRY_NOT_SUPPORTED","DMN_OUTPUT_EXPRESSION_NOT_SUPPORTED","DMN_MULTIPLE_INPUTS_NOT_SUPPORTED",
                "DMN_TYPE_NOT_SUPPORTED","DMN_INPUT_EXPRESSION_NOT_SUPPORTED","DMN_UNIQUE_OVERLAP","DMN_NO_MATCH_NOT_REPRESENTABLE","DMN_RULE_NOT_REPRESENTABLE",
                "DMN_DECISION_TYPE_NOT_SUPPORTED","DMN_DRD_NOT_SUPPORTED");
        assertThat(report.path("testCases").size()).isZero();
        var ambiguous=convert(job,simpleRequest("dmn-bad")); assertThat(ambiguous.status()).isEqualTo(422);
        var named=simpleRequest("dmn-bad"); named.put("decisionId","collectPolicy");
        var blocked=convert(job,named); assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().path("code").asString()).isEqualTo("MIGRATION_BLOCKED");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id=:ws AND key=:key").param("ws",PURCHASING).param("key",named.path("workflowKey").asString()).query(Integer.class).single()).isZero();
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}",PURCHASING,job.path("id").asString()).body().path("status").asString()).isEqualTo("analyzed");
    }

    @Test void businessRuleTaskBecomesADecisionStepFromAnUploadedDmnAndRoutesOnItsResult() throws Exception {
        var dmn=upload(example("dmn-first-hit.dmn"),"dmn");
        var job=upload(example("business-rule-task.bpmn"),"bpmn");
        assertThat(job.path("report").path("requiredMappings").path("decisionTasks").get(0).asString()).isEqualTo("routeRule");
        var request=simpleRequest("rule-task");
        var missing=convert(job,request); assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().toString()).contains("routeRule");
        // A migration that is not a DMN file, or that belongs to another workspace, cannot be referenced.
        var notDmn=request.deepCopy(); ((ObjectNode) notDmn).putObject("decisionMappings").putObject("routeRule").put("migrationId",job.path("id").asString());
        assertThat(convert(job,notDmn).status()).isEqualTo(422);
        var foreign=api("dana.designer").post(Map.of("fileName","dmn","content",example("dmn-first-hit.dmn"),"sourceFormat","dmn"),"/workspaces/{ws}/migrations",SANDBOX);
        var other=request.deepCopy(); ((ObjectNode) other).putObject("decisionMappings").putObject("routeRule").put("migrationId",foreign.body().path("id").asString());
        assertThat(convert(job,other).status()).isEqualTo(404);
        request.putObject("decisionMappings").putObject("routeRule").put("migrationId",dmn.path("id").asString());
        var converted=convert(job,request); assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        var definition=converted.body().path("draft").path("definition");
        assertThat(definition.path("decisions").get(0).path("rows").size()).isEqualTo(2);
        boolean readsDecision=false; for(var t:definition.path("transitions")) if(t.path("condition").path("fieldRef").asString().equals("/steps/routeRule/route")) readsDecision=true;
        assertThat(readsDecision).isTrue();
        assertThat(converted.body().path("report").toString()).contains("\"reasonCode\":\"DECISION_MAPPED\"");
        UUID workflow=publishImported(converted.body());
        record Case(String exposure,String outcome) { }
        for(var c:List.of(new Case("4999.99","Manager"),new Case("5000","Finance"),new Case("9999.99","Finance"),new Case("10000","Director"),new Case("0","Manager")))
            assertThat(startRun(workflow,Map.of("exposure",new BigDecimal(c.exposure()))).path("outcome").asString()).as(c.exposure()).isEqualTo(c.outcome());
    }

    @Test void multiInstanceCallActivityRunsEveryItemAsABoundedChild() throws Exception {
        UUID child=publishPurchase(PURCHASING,d -> {
            try {
                String generatedKey=d.path("workflowKey").asString();
                ObjectNode source=(ObjectNode) json.parse(Files.readString(Path.of("../../examples/simple-review.json")));
                source.put("workflowKey",generatedKey); d.removeAll(); d.setAll(source);
            } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        String childKey=api("dana.designer").get("/workspaces/{ws}/workflows/{id}",PURCHASING,child).body().path("key").asString();
        var job=upload(example("multi-instance-call.bpmn"),"bpmn");
        assertThat(job.path("report").toString()).contains("MULTI_INSTANCE_MAPPED");
        var request=simpleRequest("batch-import");
        request.set("inputSchema",json.parse(Files.readString(Path.of("../../examples/complex-batch-review.json"))).path("inputSchema"));
        var call=json.object(); call.put("workflowRef",childKey);
        call.set("inputMapping",json.toTree(Map.of("purpose","/input/purpose","costCenter","/input/costCenter","amount","/input/amount"))); call.putObject("resultMapping");
        request.putObject("childWorkflowMappings").set("reviewEach",call);
        assertThat(convert(job,request).status()).as("no bound given").isEqualTo(422);
        call.putObject("forEach").put("itemInput","title").put("maxItems",5);
        var converted=convert(job,request); assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        UUID parent=publishImported(converted.body());
        var input=new LinkedHashMap<String,Object>(input(new BigDecimal("120.00"))); input.put("title","Batch"); input.put("items",List.of("Laptop","Monitor"));
        var started=api("rhea.requester").post(Map.of("workflowId",parent,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID parentRun=UUID.fromString(started.body().path("id").asString());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).until(() -> { fastForward(PURCHASING,parentRun);
            return jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id=:id").param("id",parentRun).query(Long.class).single() == 2; });
        for(UUID childRun:jdbc.sql("SELECT id FROM workflow_run WHERE parent_run_id=:id ORDER BY parent_occurrence").param("id",parentRun).query(UUID.class).list())
            approve("mani.manager",PURCHASING,childRun,"review");
        awaitLifecycle(PURCHASING,parentRun,"completed","needsAttention"); assertThat(lifecycle(PURCHASING,parentRun)).isEqualTo("completed");
    }
}
