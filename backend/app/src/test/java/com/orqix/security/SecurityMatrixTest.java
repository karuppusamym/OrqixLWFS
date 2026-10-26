package com.orqix.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.Fixtures;
import com.orqix.support.OpenApiContract;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.TestUsers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-SEC-01 (generated from openapi.yaml): every operation declares {@code x-orqix-permission}; every
 * workspace-scoped operation called with real purchasing resource ids by principals of other workspaces returns 404;
 * platform operations return 403 to non-admins. T-SEC-11: none of those denied calls changes application or engine
 * state (row-count snapshot). T-SEC-10: bearer tokens reach only startRun, getRun, listRunEvents and sendMessage. T-SEC-06: the
 * deployed BPMN contains only the four allowed expression forms.
 */
class SecurityMatrixTest extends RuntimeTestBase {

    private static final List<String> FOREIGN_PRINCIPALS = List.of("fred.facilities", "otto.outsider");

    @Test
    void tSec01_tSec11_foreignPrincipalsGet404AndNothingChanges() throws Exception {
        Map<String, String> ids = purchasingResources();
        for (String user : FOREIGN_PRINCIPALS) {
            // Sign-in creates/refreshes the subject row; that is login, not a denied command.
            api(user).get("/me");
        }
        Map<String, Long> before = snapshot();
        List<String> failures = new ArrayList<>();
        int calls = 0;
        JsonNode document = OpenApiContract.get().document();
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                JsonNode operation = op.getValue();
                if (!operation.has("operationId")) {
                    continue;
                }
                String operationId = operation.path("operationId").asString();
                assertThat(operation.has("x-orqix-permission")).as(operationId + " declares x-orqix-permission").isTrue();
                if (!List.of("get", "head").contains(op.getKey())) {
                    assertThat(operation.has("x-orqix-audit") || "markNotificationRead".equals(operationId)
                            || "markAllNotificationsRead".equals(operationId)
                            || "validateDraft".equals(operationId) || "evaluateDecision".equals(operationId)
                            || "checkReleasePromotion".equals(operationId)
                            || "checkDraftBpmn".equals(operationId)
                            || "fetchExternalJobs".equals(operationId) || "extendExternalJobLock".equals(operationId))
                            .as(operationId + " declares x-orqix-audit").isTrue();
                }
                String template = path.getKey();
                for (String user : FOREIGN_PRINCIPALS) {
                    int status = call(user, op.getKey(), template, operationId, ids);
                    calls++;
                    int expected = template.startsWith("/workspaces/") ? 404
                            : template.startsWith("/admin/") ? 403 : 200;
                    if (status != expected) {
                        failures.add(operationId + " as " + user + " -> " + status + " (expected " + expected + ")");
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
        assertThat(calls).isGreaterThanOrEqualTo(96);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void tSec10_bearerTokensOnlyReachStartGetRunAndEvents() throws Exception {
        String machine = "machine-" + UUID.randomUUID().toString().substring(0, 8);
        UUID workflowId = publishPurchase(PURCHASING, null);
        Map<String, Object> body = Map.of("workflowId", workflowId, "input", input(BigDecimal.TEN));
        // Unknown client without a grant: the workspace is invisible.
        assertThat(bearer(machine, HttpMethod.POST, "/api/v1/workspaces/" + PURCHASING + "/runs", body)).isEqualTo(404);
        UUID subjectId = jdbc.sql("SELECT id FROM subject WHERE external_subject = :s").param("s", machine)
                .query(UUID.class).single();
        Response grant = api("wade.admin").post(Map.of("permission", "workflow.start", "workflowId", workflowId,
                "principal", Map.of("type", "subject", "value", subjectId.toString())), "/workspaces/{ws}/access/grants",
                PURCHASING);
        assertThat(grant.status()).as(grant.toString()).isEqualTo(201);
        var started = mvc.perform(bearerRequest(machine, HttpMethod.POST, "/api/v1/workspaces/" + PURCHASING + "/runs",
                body)).andReturn();
        assertThat(started.getResponse().getStatus()).as(started.getResponse().getContentAsString()).isEqualTo(201);
        OpenApiContract.validate(started);
        String runId = json.parse(started.getResponse().getContentAsString()).path("id").asString();
        // sendMessage: allowed for bearer tokens, but only with an explicit message.send grant (S16).
        Map<String, Object> message = Map.of("name", "payment-received", "correlationKey", "SO-MACHINE-1");
        assertThat(bearer(machine, HttpMethod.POST, "/api/v1/workspaces/" + PURCHASING + "/messages", message)).isEqualTo(403);
        Response messageGrant = api("wade.admin").post(Map.of("permission", "message.send",
                "principal", Map.of("type", "subject", "value", subjectId.toString())), "/workspaces/{ws}/access/grants",
                PURCHASING);
        assertThat(messageGrant.status()).as(messageGrant.toString()).isEqualTo(201);
        assertThat(bearer(machine, HttpMethod.POST, "/api/v1/workspaces/" + PURCHASING + "/messages", message)).isEqualTo(202);
        assertThat(bearer(machine, HttpMethod.GET, "/api/v1/workspaces/" + PURCHASING + "/runs/" + runId, null))
                .isEqualTo(200);
        assertThat(bearer(machine, HttpMethod.GET, "/api/v1/workspaces/" + PURCHASING + "/runs/" + runId + "/events", null))
                .isEqualTo(200);
        for (String path : List.of("/api/v1/me", "/api/v1/workspaces/" + PURCHASING + "/runs",
                "/api/v1/workspaces/" + PURCHASING + "/workflows", "/api/v1/workspaces/" + PURCHASING + "/tasks?view=claimable")) {
            assertThat(bearer(machine, HttpMethod.GET, path, null)).as(path).isEqualTo(403);
        }
        assertThat(bearer(machine, HttpMethod.POST, "/api/v1/workspaces/" + PURCHASING + "/runs/" + runId + "/cancel",
                Map.of("reason", "x", "expectedVersion", 1))).isEqualTo(403);
        // An invalid token is 401 problem JSON.
        var invalid = mvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + PURCHASING + "/runs/" + runId)
                .header("Authorization", "Bearer not-a-token")).andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void machineWorkerRoutesRequireFreshExplicitGrantAndKeepOtherRoutesDenied() throws Exception {
        String machine="native-worker-"+UUID.randomUUID();
        String base="/api/v1/workspaces/"+PURCHASING+"/external-jobs";
        var fetch=Map.of("workerId","native-gateway","topics",List.of("native-probe"),"maxJobs",1,"leaseSeconds",60);
        assertThat(bearer(machine,HttpMethod.POST,base+"/fetch-and-lock",fetch)).isIn(403,404);
        UUID subject=jdbc.sql("SELECT id FROM subject WHERE external_subject=:s").param("s",machine).query(UUID.class).single();
        var grant=api("wade.admin").post(Map.of("permission","job.work","principal",Map.of("type","subject","value",subject.toString())),"/workspaces/{ws}/access/grants",PURCHASING);
        assertThat(grant.status()).as(grant.toString()).isEqualTo(201);
        String grantId=grant.body().path("id").asString();
        try {
            assertThat(bearer(machine,HttpMethod.POST,base+"/fetch-and-lock",fetch)).isEqualTo(200);
            String unknown=base+"/"+UUID.randomUUID();
            for(var entry:Map.<String,Object>of(
                    "/extend-lock",Map.of("workerId","native-gateway","fence",1,"leaseSeconds",60),
                    "/complete",Map.of("workerId","native-gateway","fence",1,"result",Map.of()),
                    "/fail",Map.of("workerId","native-gateway","fence",1,"message","Failed","retryable",false),
                    "/await-callback",Map.of("workerId","native-gateway","fence",1,"timeoutSeconds",60),
                    "/callbacks/"+UUID.randomUUID()+"/complete",Map.of("result",Map.of()),
                    "/callbacks/"+UUID.randomUUID()+"/fail",Map.of("message","Failed")).entrySet())
                assertThat(bearer(machine,HttpMethod.POST,unknown+entry.getKey(),entry.getValue())).as(entry.getKey()).isEqualTo(404);
            assertThat(bearer(machine,HttpMethod.GET,base+"/fetch-and-lock",null)).isEqualTo(403);
            assertThat(bearer(machine,HttpMethod.GET,"/api/v1/me",null)).isEqualTo(403);
            assertThat(bearer(machine,HttpMethod.POST,"/api/v1/workspaces/"+SANDBOX+"/external-jobs/fetch-and-lock",fetch)).isEqualTo(404);
        } finally {
            jdbc.sql("DELETE FROM permission_grant WHERE id=:id").param("id",UUID.fromString(grantId)).update();
        }
        assertThat(bearer(machine,HttpMethod.POST,base+"/fetch-and-lock",fetch)).isIn(403,404);
    }

    @Test
    void tSec06_deployedBpmnContainsOnlyAllowedExpressions() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        String releaseId = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases", PURCHASING, workflowId)
                .body().path("items").get(0).path("id").asString();
        byte[] bpmn = jdbc.sql("SELECT content FROM release_artifact WHERE release_id = :r AND name = 'process.bpmn20.xml'")
                .param("r", UUID.fromString(releaseId)).query(byte[].class).single();
        Matcher matcher = Pattern.compile("\\$\\{[^}]*}").matcher(new String(bpmn, StandardCharsets.UTF_8));
        Pattern allowed = Pattern.compile("^\\$\\{(orqixRoute\\.(approved|rejected)\\(execution, '[A-Za-z][A-Za-z0-9_-]*'\\)"
                + "|orqixRoute\\.condition\\(execution, '[A-Za-z][A-Za-z0-9_-]*'\\)"
                + "|orqixDecisionTask|orqixHttpTask|orqixNotificationTask)}$");
        int count = 0;
        while (matcher.find()) {
            count++;
            assertThat(matcher.group()).matches(allowed);
        }
        assertThat(count).isPositive();
        // Label injection is blocked by the compiler before anything is deployed.
        ObjectNode definition = (ObjectNode) Fixtures.purchaseTemplate();
        ((ObjectNode) definition.path("nodes").get(1)).put("label", "Review ${clock}");
        Response created = api("dana.designer").post(Map.of("key", "inj-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Injection", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        String id = created.body().path("id").asString();
        definition.put("workflowKey", created.body().path("key").asString());
        api("dana.designer").put(Map.of("definition", definition), "\"r1\"", "/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, id);
        Response report = api("dana.designer").post(Map.of("revision", 2), "/workspaces/{ws}/workflows/{id}/validate",
                PURCHASING, id);
        assertThat(report.body().path("issues").findValues("code").toString()).contains("WF_LABEL_EXPRESSION");
    }

    // ---------------------------------------------------------------- helpers

    private int bearer(String subject, HttpMethod method, String path, Object body) throws Exception {
        var result = mvc.perform(bearerRequest(subject, method, path, body)).andReturn();
        OpenApiContract.validate(result);
        return result.getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder bearerRequest(String subject, HttpMethod method, String path, Object body) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path)
                .header("Authorization", "Bearer machine." + subject);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.write(body))
                    .header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return builder;
    }

    /** Real purchasing resources so that a foreign 404 cannot be explained by a missing resource. */
    private Map<String, String> purchasingResources() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN).path("id").asString());
        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        UUID openRun = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN).path("id").asString());
        Map<String, String> ids = new HashMap<>();
        ids.put("workspaceId", PURCHASING.toString());
        ids.put("workflowId", workflowId.toString());
        ids.put("releaseId", api("paul.publisher").get("/workspaces/{ws}/workflows/{id}/releases", PURCHASING, workflowId)
                .body().path("items").get(0).path("id").asString());
        ids.put("runId", openRun.toString());
        ids.put("taskId", openTask(PURCHASING, openRun, "manager").path("id").asString());
        ids.put("effectId", run(PURCHASING, runId).path("effects").get(0).path("id").asString());
        ids.put("notificationId", api("rhea.requester").get("/workspaces/{ws}/me/notifications", PURCHASING).body()
                .path("items").get(0).path("id").asString());
        // A file on the open run and one waiting on a form, so foreign callers are refused by access, not by a bad ID.
        UUID initiator = jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :run")
                .param("run", openRun).query(UUID.class).single();
        for (String[] file : new String[][] {{"attachmentId", "run"}, {"uploadId", "staged"}}) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO run_attachment (id, workspace_id, run_id, uploaded_by, file_name, content_type, size_bytes,
                                                sha256, scan_status, expires_at)
                    VALUES (:id, :ws, :run, :by, 'note.txt', 'text/plain', 5, :sha, 'notScanned', :expires)
                    """).param("id", id).param("ws", PURCHASING).param("run", file[1].equals("run") ? openRun : null)
                    .param("by", initiator).param("sha", "0".repeat(64))
                    .param("expires", file[1].equals("run") ? null
                            : java.time.OffsetDateTime.now().plusHours(1)).update();
            jdbc.sql("INSERT INTO run_attachment_content (attachment_id, content) VALUES (:id, :c)").param("id", id)
                    .param("c", "hello".getBytes(StandardCharsets.UTF_8)).update();
            ids.put(file[0], id.toString());
        }
        ids.put("folderId", api("dana.designer").post(Map.of("name", "Matrix " + UUID.randomUUID().toString().substring(0, 8)),
                "/workspaces/{ws}/workflow-folders", PURCHASING).body().path("id").asString());
        ids.put("templateId", api("dana.designer").post(Map.of("name", "Matrix " + UUID.randomUUID().toString().substring(0, 8),
                "format", "pdf", "body", "Hello {{name}}"), "/workspaces/{ws}/document-templates", PURCHASING)
                .body().path("id").asString());
        String stepId = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, UUID.fromString(ids.get("workflowId")))
                .body().path("definition").path("nodes").get(0).path("id").asString();
        ids.put("stepId", stepId);
        ids.put("commentId", api("dana.designer").post(Map.of("elementId", stepId, "body", "Matrix thread"),
                "/workspaces/{ws}/workflows/{id}/discussion", PURCHASING, UUID.fromString(ids.get("workflowId"))).body().path("id").asString());
        String assetKey = "matrix-" + UUID.randomUUID().toString().substring(0, 8);
        api("dana.designer").post(Map.of("key", assetKey, "name", "Matrix " + assetKey, "content", decisionContent()),
                "/workspaces/{ws}/shared-assets/decision", PURCHASING);
        ids.put("assetKind", "decision");
        ids.put("assetKey", assetKey);
        ids.put("requestId", UUID.randomUUID().toString());
        ids.put("connectionKey", "procurement");
        // A real adapter and trigger (S73), so a foreign edit is refused by access, not by a missing resource.
        String adapterKey = "matrix-" + UUID.randomUUID().toString().substring(0, 8);
        api("wade.admin").post(Map.of("key", adapterKey, "kind", "kafka", "name", "Matrix", "secretRef", "matrix-ref",
                "config", Map.of("allowedTopics", List.of("matrix.topic"))), "/workspaces/{ws}/adapters", PURCHASING);
        ids.put("adapterKey", adapterKey);
        ids.put("triggerId", api("wade.admin").post(Map.of("key", adapterKey, "source", "teams", "name", "Matrix",
                "workflowId", workflowId.toString(), "secretRef", "matrix-ref", "mapping", Map.of("title", "/text")),
                "/workspaces/{ws}/inbound-triggers", PURCHASING).body().path("id").asString());
        ids.put("decisionKey", "amount-policy");
        ids.put("groupKey", "managers");
        ids.put("callbackId", UUID.randomUUID().toString());
        ids.put("planItemId", UUID.randomUUID().toString());
        // IN04: the workspace is invisible to foreign principals, so unknown ids prove the 404 as well as real ones.
        ids.put("approvalId", UUID.randomUUID().toString());
        ids.put("writeId", UUID.randomUUID().toString());
        JsonNode access = api("wade.admin").get("/workspaces/{ws}/access", PURCHASING).body();
        ids.put("bindingId", access.path("roleBindings").get(0).path("id").asString());
        ids.put("grantId", access.path("grants").get(0).path("id").asString());
        return ids;
    }

    private int call(String user, String method, String template, String operationId, Map<String, String> ids)
            throws Exception {
        String path = template;
        Matcher m = Pattern.compile("\\{([^}]+)}").matcher(template);
        while (m.find()) {
            // The signed receive endpoint has no session: for a real trigger it answers 401 to any unsigned caller, so
            // the matrix keeps addressing an unknown trigger there (404), as before S73 added a real one.
            String value = "receiveInboundEvent".equals(operationId) && "triggerId".equals(m.group(1)) ? "x"
                    : ids.getOrDefault(m.group(1), "x");
            path = path.replace(m.group(), value);
        }
        if ("listTasks".equals(operationId)) {
            path += "?view=claimable";
        }
        if ("listSharedAssetOwnerCandidates".equals(operationId)) {
            path += "?kind=decision";
        }
        if ("clearWorkflowCanary".equals(operationId)) {
            path += "?expectedVersion=1";
        }
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method.toUpperCase()),
                "/api/v1" + path).with(TestUsers.as(user)).with(com.orqix.support.IntegrationTestBase::xsrf)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"r1\"");
        Object body = sampleBody(operationId, ids);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        }
        org.springframework.test.web.servlet.RequestBuilder request = builder;
        if (List.of("uploadAttachment", "addRunAttachment").contains(operationId)) {
            // Multipart operations (docs/build/S17): a well-formed upload, so a 404 cannot be explained by a bad body.
            request = MockMvcRequestBuilders.multipart(HttpMethod.POST, "/api/v1" + path)
                    .file(new org.springframework.mock.web.MockMultipartFile("file", "note.txt", "text/plain",
                            "hello".getBytes(StandardCharsets.UTF_8)))
                    .with(TestUsers.as(user)).with(com.orqix.support.IntegrationTestBase::xsrf)
                    .header("Idempotency-Key", UUID.randomUUID().toString());
        }
        var result = mvc.perform(request).andReturn();
        OpenApiContract.validate(result);
        return result.getResponse().getStatus();
    }

    private Object sampleBody(String operationId, Map<String, String> ids) throws Exception {
        Map<String, Object> principal = Map.of("type", "idpGroup", "value", "orqix-foreign");
        return switch (operationId) {
            case "assignWorklistView" -> Map.of("expectedRevision",0);
            case "suspendCaseInstance", "resumeCaseInstance", "closeCaseInstance", "startCasePlanItem", "enableCasePlanItem",
                    "occurCaseUserEvent", "disableCasePlanItem", "activateCaseStage", "completeCaseStage", "terminateCasePlanItem" ->
                    Map.of("expectedVersion",1,"reason","Foreign attempt");
            case "updateCaseData" -> Map.of("expectedVersion",1,"reason","Foreign attempt","values",Map.of());
            case "terminateCaseInstance" -> Map.of("expectedVersion", 1, "reason", "Foreign attempt");
            case "createCaseDefinition" -> {
                try (var source = getClass().getResourceAsStream("/cases/cmmn-compatibility.cmmn")) {
                    yield Map.of("key", "foreign-case", "name", "Foreign case", "sourceXml",
                            new String(source.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            case "createView" -> Map.of("name", "Foreign view", "definition",
                    Map.of("schemaVersion", 1, "kind", "request", "sections",
                            List.of(Map.of("id", "details", "label", "Details", "columns", 1, "fields",
                                    List.of(Map.of("id", "title", "label", "Title", "type", "text",
                                            "binding", "request.title", "required", true)))),
                            "actions", List.of(Map.of("id", "save", "label", "Save", "type", "saveDraft"))));
            case "createOrganization" -> Map.of("key", "foreign-org", "name", "Foreign");
            case "createWorkspace" -> Map.of("organizationId", UUID.randomUUID().toString(), "key", "foreign-ws",
                    "name", "Foreign", "isTest", false, "initialAdmins", List.of(principal));
            case "createWorkflow" -> Map.of("key", "foreign-wf", "name", "Foreign");
            case "uploadMigration" -> Map.of("fileName", "foreign.bpmn", "content", "<definitions/>", "sourceFormat", "bpmn");
            case "convertMigration" -> Map.of("key", "foreign-import", "name", "Foreign", "acknowledgeInfo", true);
            case "uploadMigrationProject" -> Map.of("name", "Foreign", "files",
                    List.of(Map.of("fileName", "foreign.bpmn", "content", "<definitions/>")));
            case "convertMigrationProjectNext" -> Map.of("expectedMigrationId", UUID.randomUUID().toString(),
                    "mappings", Map.of("workflowKey", "foreign-import", "name", "Foreign"));
            case "setMigrationPolicy" -> Map.of("required", true, "expectedVersion", 0, "reason", "Foreign attempt");
            case "approveMigrationReview" -> Map.of("draftRevision", 1, "expectedVersion", 0, "reason", "Foreign attempt");
            case "saveDraft" -> Map.of("definition", Map.of("schemaVersion", "0.1"));
            case "validateDraft" -> Map.of("revision", 1);
            case "checkDraftBpmn" -> Map.of("revision", 1, "bpmn", "<definitions/>");
            case "applyDraftBpmn" -> Map.of("bpmn", "<definitions/>");
            case "evaluateDecision" -> Map.of("revision", 1, "values", Map.of("/input/amount", 1));
            case "simulateWorkflow" -> Map.of("revision", 1, "input", Map.of("amount", 1));
            case "createDocumentTemplate" -> Map.of("name", "Foreign template", "format", "pdf", "body", "Hello {{name}}");
            case "addDocumentTemplateVersion" -> Map.of("expectedVersion", 1, "format", "pdf", "body", "Foreign {{name}}");
            case "setDocumentTemplateArchived" -> Map.of("archived", true);
            case "createDecisionAsset" -> Map.of("key", "foreign-decision", "name", "Foreign", "content", decisionContent());
            case "addDecisionAssetVersion" -> Map.of("expectedVersion", 1, "content", decisionContent());
            case "setSharedAssetOwner" -> Map.of("ownerSubjectId", UUID.randomUUID().toString(), "expectedRevision", 1);
            case "setSharedAssetArchived" -> Map.of("archived", true, "expectedRevision", 1);
            case "quarantineAttachment" -> Map.of("reason", "Foreign attempt");
            case "setAttachmentRetention" -> Map.of("retentionDays", 1, "expectedVersion", 0);
            case "createWorkflowFolder" -> Map.of("name", "Foreign folder");
            case "renameWorkflowFolder" -> Map.of("name", "Foreign rename", "expectedVersion", 1);
            case "setWorkflowFolder" -> Map.of("folderId", ids.get("folderId"), "expectedFolderVersion", 1);
            case "postWorkflowComment" -> Map.of("elementId", ids.get("stepId"), "body", "Foreign comment");
            case "resolveWorkflowThread" -> Map.of("resolved", true);
            case "setWorkspaceEnvironment" -> Map.of("stage", "staging", "expectedVersion", 0);
            case "setWorkflowCanary" -> Map.of("releaseId", ids.get("releaseId"), "percent", 10, "expectedVersion", 1);
            case "checkReleasePromotion", "promoteRelease", "requestReleasePromotion" -> Map.of("targetWorkspaceId", UUID.randomUUID().toString());
            case "rejectPromotionRequest" -> Map.of("note", "Foreign attempt");
            case "setWorkflowOwner" -> Map.of("ownerSubjectId", UUID.randomUUID().toString(), "expectedVersion", 1);
            case "saveWorkflowTestSuite" -> Map.of("expectedVersion", 0, "cases", List.of());
            case "runWorkflowTestSuite" -> Map.of("revision", 1, "suiteVersion", 1);
            case "correctRunInput" -> Map.of("expectedVersion", 1, "reason", "Foreign attempt", "changes", Map.of("title", "Corrected"));
            case "publishDraft" -> Map.of("draftRevision", 1);
            case "setActiveRelease" -> Map.of("releaseId", ids.get("releaseId"), "expectedVersion", 1);
            case "startRun" -> Map.of("workflowId", ids.get("workflowId"), "input", input(BigDecimal.ONE));
            case "sendMessage" -> Map.of("name", "payment-received", "correlationKey", "SO-FOREIGN");
            case "fetchExternalJobs" -> Map.of("workerId", "w1", "topics", List.of("any-topic"));
            case "awaitExternalJobCallback" -> Map.of("workerId", "w1", "fence", 1, "timeoutSeconds", 3600);
            case "completeExternalJobCallback" -> Map.of("result", Map.of());
            case "failExternalJobCallback" -> Map.of("message", "Foreign attempt");
            case "extendExternalJobLock" -> Map.of("workerId", "w1", "fence", 1);
            case "completeExternalJob" -> Map.of("workerId", "w1", "fence", 1, "result", Map.of());
            case "failExternalJob" -> Map.of("workerId", "w1", "fence", 1, "message", "Foreign attempt", "retryable", true);
            case "correctTaskInput" -> Map.of("expectedVersion", 1, "reason", "Foreign attempt", "changes", Map.of("amount", 1));
            case "migrateRun" -> Map.of("targetReleaseId", UUID.randomUUID().toString(), "reason", "Foreign attempt",
                    "expectedVersion", 1);
            case "cancelRun", "retryRun", "suspendRun", "resumeRun", "repairRun" -> Map.of("reason", "Foreign attempt", "expectedVersion", 1);
            case "sendRunSignal" -> Map.of("signal", "withdraw", "reason", "Foreign attempt", "expectedVersion", 1);
            case "reconcileEffect" -> Map.of("resolution", "confirmedNotApplied", "evidence",
                    Map.of("note", "Foreign attempt to reconcile"));
            case "claimTask", "releaseTask" -> Map.of("expectedVersion", 1);
            case "decideTask" -> Map.of("expectedVersion", 1, "action", "approve");
            case "setTaskPriority" -> Map.of("expectedVersion", 1, "priority", 50, "reason", "Foreign attempt");
            case "reassignTask" -> Map.of("expectedVersion", 1, "reason", "Foreign attempt");
            case "delegateTask" -> Map.of("expectedVersion", 1, "assigneeSubjectId", UUID.randomUUID().toString(),
                    "reason", "Foreign attempt");
            case "registerConnectionVersion" -> {
                ObjectNode definition = (ObjectNode) Fixtures.procurementConnection();
                definition.put("version", 99);
                yield Map.of("definition", definition);
            }
            case "putConnectionBinding" -> Map.of("baseUrl", "https://public.test.example", "auth", Map.of("type", "none"),
                    "enabled", true);
            case "finishConnectionOAuth" -> Map.of("state", "a".repeat(43), "code", "foreign-code");
            case "testConnection" -> Map.of("version", 1, "operation", "health");
            case "reviewOpenApiConnection" -> Map.of("source", "{}");
            case "createAdapter" -> Map.of("key", "foreign-adapter", "kind", "kafka", "name", "Foreign", "secretRef", "foreign-ref",
                    "config", Map.of("allowedTopics", List.of("foreign.topic")));
            case "testAdapterSend" -> Map.of("payload", Map.of("topic", "foreign.topic", "value", Map.of("a", 1)));
            case "createInboundTrigger" -> Map.of("key", "foreign-trigger", "source", "teams", "name", "Foreign",
                    "workflowId", ids.get("workflowId"), "secretRef", "foreign-ref", "mapping", Map.of("title", "/text"));
            case "receiveInboundEvent" -> Map.of("text", "foreign");
            case "updateAdapter" -> Map.of("name", "Foreign", "secretRef", "foreign-ref", "config",
                    Map.of("allowedTopics", List.of("foreign.topic")), "enabled", false, "expectedVersion", 1);
            case "updateInboundTrigger" -> Map.of("name", "Foreign", "secretRef", "foreign-ref", "mapping",
                    Map.of("title", "/text"), "enabled", false, "expectedVersion", 1);
            case "purgeInboundEvents" -> Map.of("olderThanDays", 1, "includeUnstarted", true);
            case "approveAgentWriteTool" -> Map.of("workflowKey", "foreign-flow", "connectionKey", "vendor-risk-assistant",
                    "connectionVersion", 1, "operation", "assessVendor", "toolName", "notify_team", "reason", "Foreign",
                    "expiresInDays", 1);
            case "revokeAgentWriteTool" -> Map.of("reason", "Foreign", "expectedVersion", 1);
            case "reconcileAgentWrite" -> Map.of("outcome", "notApplied", "reason", "Foreign", "expectedVersion", 1);
            case "setAgentWriteRetention" -> Map.of("retentionDays", 1, "expectedVersion", 0);
            case "createRoleBinding" -> Map.of("role", "VIEWER", "principal", principal);
            case "createPermissionGrant" -> Map.of("permission", "workflow.start", "principal", principal);
            case "putGroup" -> Map.of("name", "Foreign", "members", List.of());
            default -> null;
        };
    }

    private static Map<String, Object> decisionContent() {
        return Map.of("hitPolicy", "UNIQUE", "rows", List.of(Map.of("when", Map.of("fieldRef", "/input/amount", "operator", "gte",
                "value", 5000), "then", Map.of("route", "financeReview"))), "default", Map.of("route", "standard"));
    }

    /** Row counts of every application table that a command could change, plus engine runtime/deployment tables. */
    private Map<String, Long> snapshot() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("organization", "workspace", "subject", "role_binding", "permission_grant",
                "workspace_group", "workspace_group_member", "migration_job", "migration_project", "migration_policy", "migration_review", "workflow", "workflow_folder", "workflow_folder_item", "document_template", "document_template_version", "decision_asset", "decision_asset_version", "connection_asset", "workspace_attachment_policy", "workspace_agent_write_policy", "agent_write_tool_approval", "agent_write_call", "attachment_ocr_result", "workflow_element_comment", "workflow_comment_mention", "workflow_draft_revision", "workflow_release",
                "release_artifact", "case_catalog", "case_definition", "case_instance", "case_command_response", "case_termination", "case_event", "case_data_event", "connection_definition", "connection_binding", "oauth_grant", "oauth_consent", "external_job", "external_job_callback", "workflow_run", "task_context",
                "run_attachment", "run_attachment_content", "run_stage", "workflow_test_suite", "workflow_test_result", "document_source",
                "task_decision", "task_reminder", "inbound_message", "message_subscription", "document_snapshot", "adapter_config", "inbound_trigger", "inbound_event", "effect_journal", "run_event", "notification", "idempotency_record",
                "act_re_deployment", "act_ru_execution", "act_ru_task", "act_ru_job", "act_ru_deadletter_job")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        counts.put("notification_read", jdbc.sql("SELECT count(*) FROM notification WHERE read_at IS NOT NULL")
                .query(Long.class).single());
        counts.put("asset_revisions", jdbc.sql("SELECT (SELECT coalesce(sum(revision), 0) FROM decision_asset)"
                + " + (SELECT coalesce(sum(revision), 0) FROM document_template)"
                + " + (SELECT coalesce(sum(revision), 0) FROM connection_asset)").query(Long.class).single());
        counts.put("binding_revisions", jdbc.sql("SELECT coalesce(sum(revision), 0) FROM connection_binding")
                .query(Long.class).single());
        counts.put("adapter_versions", jdbc.sql("SELECT (SELECT coalesce(sum(version), 0) FROM adapter_config)"
                + " + (SELECT coalesce(sum(version), 0) FROM inbound_trigger)").query(Long.class).single());
        counts.put("task_versions", jdbc.sql("SELECT coalesce(sum(version), 0) FROM task_context").query(Long.class).single());
        counts.put("case_versions", jdbc.sql("SELECT coalesce(sum(version), 0) FROM case_instance").query(Long.class).single());
        counts.put("run_versions", jdbc.sql("SELECT coalesce(sum(version), 0) FROM workflow_run").query(Long.class).single());
        return counts;
    }
}
