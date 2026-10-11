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
                            || "validateDraft".equals(operationId) || "evaluateDecision".equals(operationId))
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
        ids.put("connectionKey", "procurement");
        ids.put("decisionKey", "amount-policy");
        ids.put("groupKey", "managers");
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
            path = path.replace(m.group(), ids.getOrDefault(m.group(1), "x"));
        }
        if ("listTasks".equals(operationId)) {
            path += "?view=claimable";
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

    private Object sampleBody(String operationId, Map<String, String> ids) {
        Map<String, Object> principal = Map.of("type", "idpGroup", "value", "orqix-foreign");
        return switch (operationId) {
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
            case "evaluateDecision" -> Map.of("revision", 1, "values", Map.of("/input/amount", 1));
            case "simulateWorkflow" -> Map.of("revision", 1, "input", Map.of("amount", 1));
            case "publishDraft" -> Map.of("draftRevision", 1);
            case "setActiveRelease" -> Map.of("releaseId", ids.get("releaseId"), "expectedVersion", 1);
            case "startRun" -> Map.of("workflowId", ids.get("workflowId"), "input", input(BigDecimal.ONE));
            case "sendMessage" -> Map.of("name", "payment-received", "correlationKey", "SO-FOREIGN");
            case "cancelRun", "retryRun" -> Map.of("reason", "Foreign attempt", "expectedVersion", 1);
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
            case "testConnection" -> Map.of("version", 1, "operation", "health");
            case "createRoleBinding" -> Map.of("role", "VIEWER", "principal", principal);
            case "createPermissionGrant" -> Map.of("permission", "workflow.start", "principal", principal);
            case "putGroup" -> Map.of("name", "Foreign", "members", List.of());
            default -> null;
        };
    }

    /** Row counts of every application table that a command could change, plus engine runtime/deployment tables. */
    private Map<String, Long> snapshot() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("organization", "workspace", "subject", "role_binding", "permission_grant",
                "workspace_group", "workspace_group_member", "migration_job", "migration_project", "migration_policy", "migration_review", "workflow", "workflow_draft_revision", "workflow_release",
                "release_artifact", "connection_definition", "connection_binding", "workflow_run", "task_context",
                "run_attachment", "run_attachment_content", "run_stage",
                "task_decision", "task_reminder", "inbound_message", "message_subscription", "document_snapshot", "effect_journal", "run_event", "notification", "idempotency_record",
                "act_re_deployment", "act_ru_execution", "act_ru_task", "act_ru_job", "act_ru_deadletter_job")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        counts.put("notification_read", jdbc.sql("SELECT count(*) FROM notification WHERE read_at IS NOT NULL")
                .query(Long.class).single());
        counts.put("binding_revisions", jdbc.sql("SELECT coalesce(sum(revision), 0) FROM connection_binding")
                .query(Long.class).single());
        counts.put("task_versions", jdbc.sql("SELECT coalesce(sum(version), 0) FROM task_context").query(Long.class).single());
        counts.put("run_versions", jdbc.sql("SELECT coalesce(sum(version), 0) FROM workflow_run").query(Long.class).single());
        return counts;
    }
}
