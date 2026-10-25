package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.PolicyService;
import com.orqix.integration.AgentWriteTools;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.ConnectionDefinition.WriteTool;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EffectJournal;
import com.orqix.integration.adapters.AdapterTransport;
import com.orqix.integration.adapters.LocalStubTransport;
import com.orqix.integration.agentwrite.AgentWriteRetention;
import com.orqix.integration.agentwrite.AgentWriteService;
import com.orqix.support.AgentLlmStub;
import com.orqix.support.McpStub;
import com.orqix.support.MutableClock;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IN04 (docs/build/S75) on the real stack (PostgreSQL, Flowable, WireMock model and MCP server, local stub transport):
 * approval before publish and before every write, separation of duties, closed arguments with fixed recipients, one
 * journaled delivery, revocation, an unknown outcome that is never resent and its version-checked reconciliation;
 * approval expiry, the pre-send approval fence and retention of write records (docs/build/S85).
 * Every API response is validated against openapi.yaml by the test client.
 */
@ExtendWith(OutputCaptureExtension.class)
class AgentWriteToolsTest extends RuntimeTestBase {

    private static final String CANARY = "write-canary-" + UUID.randomUUID();
    private static final String LOOKUP = "{\"vendorName\":\"Acme Ltd\"}";
    private static final String HIGH = "{\"riskScore\":72,\"riskBand\":\"high\",\"sanctionsHit\":false,\"registryId\":\"VR-1\"}";

    @Autowired
    private LocalStubTransport stub;

    private final List<UUID> grants = new ArrayList<>();
    private String suffix;
    private String mail;
    private String connection;

    @BeforeEach
    void setUp() throws Exception {
        AgentLlmStub.reset();
        McpStub.reset();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        mail = "agent-mail-" + suffix;
        connection = "vendor-writer-" + suffix;
        Response adapter = api("wade.admin").post(Map.of("key", mail, "kind", "email", "name", "Procurement mail " + suffix,
                "secretRef", "agent-mail", "config", Map.of("from", "noreply@example.com",
                        "allowedRecipientDomains", List.of("example.com"), "maxRecipients", 2)),
                "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(adapter.status()).as(adapter.toString()).isEqualTo(201);
        ObjectNode definition = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot",
                "../.."), "examples/connections/vendor-risk-assistant.connection.json")));
        definition.put("key", connection);
        definition.put("name", "Vendor writer " + suffix);
        ObjectNode agent = (ObjectNode) definition.path("operations").path("assessVendor").path("agent");
        agent.put("maxToolCalls", 5);
        agent.putArray("writeTools").addObject().put("name", "notify_procurement").put("label", "Tell procurement")
                .put("description", "Emails the procurement team a short note about a high-risk vendor.")
                .put("adapter", mail).put("kind", "email").put("maxCalls", 1)
                .putArray("to").add("procurement@example.com");
        Response registered = api("wade.admin").post(Map.of("definition", definition),
                "/workspaces/{ws}/connections/{key}/versions", PURCHASING, connection);
        assertThat(registered.status()).as(registered.toString()).isEqualTo(201);
        jdbc.sql("""
                INSERT INTO connection_binding (workspace_id, connection_key, base_url, auth_type, enabled, revision, updated_by)
                SELECT :ws, :key, :url, 'none', true, 1, id FROM subject WHERE external_subject = 'kc-wade.admin'
                """).param("ws", PURCHASING).param("key", connection).param("url", McpStub.url()).update();
    }

    @AfterEach
    void removeGrants() throws Exception {
        for (UUID grant : grants) {
            api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, grant);
        }
        grants.clear();
    }

    // ------------------------------------------------------------------ helpers

    private UUID subject(String user) throws Exception {
        return UUID.fromString(api(user).get("/me").body().path("subject").path("id").asString());
    }

    private void grantApprover(String user) throws Exception {
        Response granted = api("wade.admin").post(Map.of("permission", "agent.approve-write-tool",
                "principal", Map.of("type", "subject", "value", subject(user).toString())),
                "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(granted.status()).as(granted.toString()).isEqualTo(201);
        grants.add(UUID.fromString(granted.body().path("id").asString()));
    }

    private record Draft(UUID id, String key) {
    }

    /** Creates the vendor workflow on the writer connection and saves its draft (revision 2); does not publish. */
    private Draft draft() throws Exception {
        String key = "vw-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4);
        Response created = api("dana.designer").post(Map.of("key", key, "name", "Vendor writer " + key,
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot", "../.."),
                "examples/vendor-onboarding-check.json")));
        example.put("workflowKey", key);
        ((ObjectNode) example.path("execution")).put("retryDelaySeconds", 5);
        ((ObjectNode) example.path("dependencies").path("connections").get(0)).put("key", connection);
        for (JsonNode node : example.path("nodes")) {
            if ("http".equals(node.path("type").asString())) {
                ((ObjectNode) node.path("config")).put("connectionRef", connection);
            }
        }
        Response saved = api("dana.designer").put(Map.of("definition", example), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return new Draft(id, key);
    }

    private Response publish(Draft draft) throws Exception {
        return api("paul.publisher").post(Map.of("draftRevision", 2, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, draft.id());
    }

    private Response approve(String user, Draft draft, String reason) throws Exception {
        return approve(user, draft, reason, null);
    }

    /** {@code expiresInDays} null leaves the member out (no expiry). */
    private Response approve(String user, Draft draft, String reason, Integer expiresInDays) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("workflowKey", draft.key(), "connectionKey", connection,
                "connectionVersion", 1, "operation", "assessVendor", "toolName", "notify_procurement", "reason", reason));
        if (expiresInDays != null) {
            body.put("expiresInDays", expiresInDays);
        }
        return api(user).post(body, "/workspaces/{ws}/agent-write-tools/approvals", PURCHASING);
    }

    private Draft approvedAndPublished(String approver) throws Exception {
        return approvedAndPublished(approver, null);
    }

    private Draft approvedAndPublished(String approver, Integer expiresInDays) throws Exception {
        Draft draft = draft();
        Response refused = publish(draft);
        assertThat(refused.status()).as(refused.toString()).isNotEqualTo(201);
        assertThat(refused.toString()).contains("WF_AGENT_WRITE_TOOL_UNAPPROVED");
        grantApprover(approver);
        Response approved = approve(approver, draft, "Procurement asked for high-risk notes", expiresInDays);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(201);
        Response published = publish(draft);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return draft;
    }

    private UUID start(String user, Draft draft) throws Exception {
        Response started = api(user).post(Map.of("workflowId", draft.id(), "input", Map.of("vendorName", "Acme Ltd",
                "country", "United Kingdom", "annualSpend", 12000)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private static String note(String subject, String body) {
        return "{\"subject\":\"" + subject + "\",\"body\":\"" + body + "\"}";
    }

    private long sent() {
        return stub.sent().stream().filter(s -> s.adapterKey().equals(mail)).count();
    }

    private Map<String, Object> writeEffect(UUID run) {
        return jdbc.sql("""
                SELECT id, node_id, connection_key, operation, effect_class, state, attempts, result::text AS result,
                       last_error_detail
                FROM effect_journal WHERE run_id = :run AND operation LIKE 'agentWrite.%'
                """).param("run", run).query().singleRow();
    }

    private Response retry(UUID run) throws Exception {
        int version = run(PURCHASING, run).path("version").asInt();
        return api("olga.operator").post(Map.of("reason", "Checked", "expectedVersion", version),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/retry", PURCHASING, run);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void anApprovedWriteIsJournaledAndDeliveredOnceToFixedRecipientsWithoutStoringTheText(CapturedOutput logs)
            throws Exception {
        // Approval needs the grant: no role carries it, a reason is required.
        Draft probe = draft();
        assertThat(approve("olga.operator", probe, "Operators cannot approve").status()).isEqualTo(403);
        Draft draft = approvedAndPublished("mani.manager");
        Response duplicate = approve("mani.manager", draft, "Again");
        assertThat(duplicate.status()).as(duplicate.toString()).isEqualTo(409);
        assertThat(approve("mani.manager", probe, "   ").code()).isEqualTo("REASON_REQUIRED");

        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", LOOKUP, 0.0001);
        AgentLlmStub.toolCall("c2", "notify_procurement", note("High risk vendor Acme Ltd", CANARY), 0.0001);
        AgentLlmStub.answer(HIGH, 0.0001);
        UUID run = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");
        assertThat(lifecycle(PURCHASING, run)).as(run(PURCHASING, run).toString()).isEqualTo("waiting");

        // Exactly one delivery, to the fixed recipient, of exactly the model's text.
        assertThat(sent()).isEqualTo(1);
        ObjectNode expected = json.object();
        expected.putArray("to").add("procurement@example.com");
        expected.put("subject", "High risk vendor Acme Ltd");
        expected.put("body", CANARY);
        assertThat(stub.sent().stream().filter(s -> s.adapterKey().equals(mail)).findFirst().orElseThrow().payloadSha256())
                .isEqualTo(json.canonicalSha256(expected));
        // The write is its own nonIdempotent journal entry next to the (readOnly) agent step.
        Map<String, Object> effect = writeEffect(run);
        assertThat(effect).containsEntry("node_id", "assess:write:notify_procurement:1").containsEntry("connection_key", mail)
                .containsEntry("operation", "agentWrite.email").containsEntry("effect_class", "nonIdempotent")
                .containsEntry("state", "succeeded").containsEntry("attempts", 1);
        assertThat(String.valueOf(effect.get("result"))).contains("stubbed").doesNotContain(CANARY, "procurement@");
        Map<String, Object> call = jdbc.sql("""
                SELECT approved_by, requested_by, argument_chars, argument_sha256, call_index, version
                FROM agent_write_call WHERE run_id = :run
                """).param("run", run).query().singleRow();
        assertThat(call.get("approved_by")).isEqualTo(subject("mani.manager"));
        assertThat(call.get("requested_by")).isEqualTo(subject("rhea.requester"));
        assertThat((Integer) call.get("argument_chars")).isGreaterThan(CANARY.length());
        // The model saw a closed schema without any recipient member, and only after approval.
        JsonNode tools = AgentLlmStub.requests().get(0).path("tools");
        JsonNode write = null;
        for (JsonNode tool : tools) {
            if ("notify_procurement".equals(tool.path("function").path("name").asString())) {
                write = tool.path("function").path("parameters");
            }
        }
        assertThat(write).isNotNull();
        assertThat(write.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(write.path("properties").propertyNames()).containsExactlyInAnyOrder("subject", "body");
        // Nothing of the text anywhere Orqix keeps or logs it.
        for (String table : List.of("effect_journal", "agent_write_call", "agent_call", "audit_event", "run_event")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table + " t WHERE to_jsonb(t)::text LIKE :canary")
                    .param("canary", "%" + CANARY + "%").query(Integer.class).single()).as(table).isZero();
        }
        assertThat(logs.getAll()).doesNotContain(CANARY);
        String audit = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'agent.write' AND metadata ->> 'runId' = :run")
                .param("run", run.toString()).query(String.class).single();
        assertThat(audit).contains("\"outcome\": \"succeeded\"").contains("notify_procurement");
        // Listing for operators and approvers; not for requesters.
        Response calls = api("olga.operator").get("/workspaces/{ws}/agent-writes", PURCHASING);
        assertThat(calls.status()).as(calls.toString()).isEqualTo(200);
        assertThat(calls.body().path("items").toString()).contains(run.toString()).doesNotContain(CANARY);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/agent-writes", PURCHASING).status()).isEqualTo(403);
        Response overview = api("mani.manager").get("/workspaces/{ws}/agent-write-tools", PURCHASING);
        assertThat(overview.status()).as(overview.toString()).isEqualTo(200);
        assertThat(overview.body().path("canApprove").asBoolean()).isTrue();
        assertThat(overview.body().path("tools").toString()).contains("procurement@example.com", connection);
    }

    @Test
    void theModelCannotChooseRecipientsOrExceedLimitsAndInvalidArgumentsSendNothing() throws Exception {
        Draft draft = approvedAndPublished("mani.manager");
        AgentLlmStub.toolCall("c1", "notify_procurement",
                "{\"to\":[\"attacker@evil.test\"],\"subject\":\"x\",\"body\":\"y\"}", 0.0001);
        AgentLlmStub.toolCall("c2", "notify_procurement", note("Two\\nlines", "y"), 0.0001);
        AgentLlmStub.toolCall("c3", "notify_procurement", note("ok", "z".repeat(4001)), 0.0001);
        AgentLlmStub.toolCall("c4", "notify_procurement", note("Valid", "Body"), 0.0001);
        AgentLlmStub.toolCall("c5", "notify_procurement", note("Again", "Body"), 0.0001);
        AgentLlmStub.answer(HIGH, 0.0001);
        UUID run = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");

        assertThat(sent()).isEqualTo(1);
        assertThat(writeEffect(run)).containsEntry("state", "succeeded").containsEntry("attempts", 1);
        String toolCalls = jdbc.sql("SELECT tool_calls::text FROM agent_call WHERE run_id = :run").param("run", run)
                .query(String.class).single();
        assertThat(toolCalls.split("invalidArguments", -1)).hasSize(4);
        assertThat(toolCalls).contains("written").contains("refusedLimit").doesNotContain("evil.test");
        // What the model was told for the recipient attempt names the member, never echoes the value.
        JsonNode second = AgentLlmStub.requests().get(1).path("messages");
        String refusal = second.get(second.size() - 1).path("content").asString();
        assertThat(refusal).contains("'to' is not allowed").contains("Nothing was sent").doesNotContain("evil.test");
    }

    @Test
    void withoutAnActiveApprovalOrForTheApproverOwnRequestNothingIsAskedOrSent() throws Exception {
        Draft draft = approvedAndPublished("mani.manager");
        JsonNode approval = approvalFor(draft.key(), "active");
        assertThat(approval).isNotNull();
        UUID approvalId = UUID.fromString(approval.path("id").asString());
        // Revocation: reason and version checked; requesters may not revoke.
        Response denied = api("rhea.requester").post(Map.of("reason", "No", "expectedVersion", 1),
                "/workspaces/{ws}/agent-write-tools/approvals/{id}/revoke", PURCHASING, approvalId);
        assertThat(denied.status()).isEqualTo(403);
        Response stale = api("mani.manager").post(Map.of("reason", "Stop", "expectedVersion", 7),
                "/workspaces/{ws}/agent-write-tools/approvals/{id}/revoke", PURCHASING, approvalId);
        assertThat(stale.code()).isEqualTo("CONFIGURATION_VERSION_CONFLICT");
        Response revoked = api("mani.manager").post(Map.of("reason", "Vendor programme paused", "expectedVersion", 1),
                "/workspaces/{ws}/agent-write-tools/approvals/{id}/revoke", PURCHASING, approvalId);
        assertThat(revoked.status()).as(revoked.toString()).isEqualTo(200);
        assertThat(revoked.body().path("status").asString()).isEqualTo("revoked");

        UUID run = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, run, "needsAttention", "waiting", "completed");
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).as(detail.toString()).isEqualTo("needsAttention");
        assertThat(detail.path("attention").path("reason").asString()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(detail.path("attention").toString()).contains("Tell procurement").contains("approve");
        assertThat(AgentLlmStub.requests()).isEmpty();
        assertThat(sent()).isZero();

        // Separation of duties: the approver's own request is refused even with an active approval.
        grantApprover("rhea.requester");
        Response selfApproved = approve("rhea.requester", draft, "Approving for my own team");
        assertThat(selfApproved.status()).as(selfApproved.toString()).isEqualTo(201);
        UUID own = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, own, "needsAttention", "waiting", "completed");
        JsonNode ownDetail = run(PURCHASING, own);
        assertThat(ownDetail.path("lifecycle").asString()).as(ownDetail.toString()).isEqualTo("needsAttention");
        assertThat(ownDetail.path("attention").toString()).contains("approved that tool");
        assertThat(AgentLlmStub.requests()).isEmpty();
        assertThat(sent()).isZero();
        // ... and someone else's request through the same approval may write.
        AgentLlmStub.toolCall("c1", "notify_procurement", note("Note", "Body"), 0.0001);
        AgentLlmStub.answer(HIGH, 0.0001);
        UUID other = start("mani.manager", draft);
        awaitLifecycle(PURCHASING, other, "waiting", "needsAttention");
        assertThat(sent()).isEqualTo(1);
        // The database repeats the rule.
        assertThatThrownBy(() -> superuser("""
                INSERT INTO orqix.agent_write_call (id, workspace_id, run_id, agent_effect_id, node_id, effect_key,
                    connection_key, connection_version, operation, tool_name, call_index, adapter_key, kind, approval_id,
                    approved_by, requested_by, argument_sha256, argument_chars)
                VALUES (?, ?, ?, ?, 'x', ?, 'k', 1, 'op', 'notify_procurement', 1, 'a', 'email', ?, ?, ?, ?, 1)
                """, UUID.randomUUID(), PURCHASING, own, UUID.randomUUID(), UUID.randomUUID().toString(), approvalId,
                subject("rhea.requester"), subject("rhea.requester"), "0".repeat(64)))
                .hasMessageContaining("agent_write_call_separation");
    }

    @Test
    void anUnknownWriteIsNeverResentAndAnOperatorRecordsTheOutcomeWithAReasonAndVersion() throws Exception {
        Draft draft = approvedAndPublished("mani.manager");
        // First execution: the write succeeds, then the answer never matches the schema (needs attention).
        AgentLlmStub.toolCall("c1", "notify_procurement", note("Note", "Body"), 0.0001);
        AgentLlmStub.answer("not json", 0.0001);
        AgentLlmStub.answer("{\"riskScore\":\"high\"}", 0.0001);
        UUID run = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, run, "needsAttention", "waiting");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("needsAttention");
        assertThat(sent()).isEqualTo(1);
        // A crash after the send began and before its outcome was recorded (expired lease, no outcome).
        UUID writeEffect = (UUID) writeEffect(run).get("id");
        jdbc.sql("""
                UPDATE effect_journal SET state = 'inFlight', lease_owner = 'crashed', lease_until = now() - interval '1 minute'
                WHERE id = :id
                """).param("id", writeEffect).update();

        // The operator retries the agent step: the write slot is found without an outcome and is NOT resent.
        AgentLlmStub.toolCall("c2", "notify_procurement", note("Note", "Body"), 0.0001);
        Response retried = retry(run);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60))
                .pollInterval(java.time.Duration.ofMillis(200))
                .until(() -> "EFFECT_UNKNOWN".equals(run(PURCHASING, run).path("attention").path("reason").asString()));
        assertThat(writeEffect(run)).containsEntry("state", "unknown");
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("attention").path("reason").asString()).as(detail.toString()).isEqualTo("EFFECT_UNKNOWN");
        assertThat(sent()).isEqualTo(1);
        // The run cannot continue, and the generic reconcile refuses agent writes.
        assertThat(retry(run).code()).isEqualTo("EFFECT_NOT_RECONCILED");
        Response generic = api("olga.operator").post(Map.of("resolution", "confirmedApplied", "evidence",
                Map.of("note", "Checked the mailbox"), "result", Map.of()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{run}/effects/{effect}/reconcile", PURCHASING, run, writeEffect);
        assertThat(generic.code()).as(generic.toString()).isEqualTo("EFFECT_STATE_CONFLICT");

        // The dedicated reconciliation: listed as unknown, run.operate only, reason and version required.
        Response unknown = api("olga.operator").get("/workspaces/{ws}/agent-writes?state=unknown", PURCHASING);
        JsonNode item = null;
        for (JsonNode candidate : unknown.body().path("items")) {
            if (candidate.path("runId").asString().equals(run.toString())) {
                item = candidate;
            }
        }
        assertThat(item).as(unknown.toString()).isNotNull();
        assertThat(item.path("state").asString()).isEqualTo("unknown");
        UUID writeId = UUID.fromString(item.path("id").asString());
        int version = item.path("version").asInt();
        String path = "/workspaces/{ws}/agent-writes/{id}/reconcile";
        assertThat(api("mani.manager").post(Map.of("outcome", "succeeded", "reason", "x", "expectedVersion", version),
                path, PURCHASING, writeId).status()).isEqualTo(403);
        assertThat(api("olga.operator").post(Map.of("outcome", "succeeded", "reason", "Seen", "expectedVersion", version + 5),
                path, PURCHASING, writeId).code()).isEqualTo("CONFIGURATION_VERSION_CONFLICT");
        assertThat(api("olga.operator").post(Map.of("outcome", "succeeded", "reason", " ", "expectedVersion", version),
                path, PURCHASING, writeId).code()).isEqualTo("REASON_REQUIRED");
        Response reconciled = api("olga.operator").post(Map.of("outcome", "succeeded",
                "reason", "The note is in the procurement mailbox", "remoteReference", "MSG-1", "expectedVersion", version),
                path, PURCHASING, writeId);
        assertThat(reconciled.status()).as(reconciled.toString()).isEqualTo(200);
        assertThat(reconciled.body().path("state").asString()).isEqualTo("succeeded");
        assertThat(reconciled.body().path("reconciledOutcome").asString()).isEqualTo("succeeded");
        assertThat(reconciled.body().path("version").asInt()).isEqualTo(version + 1);
        assertThat(api("olga.operator").post(Map.of("outcome", "notApplied", "reason", "Again", "expectedVersion",
                version + 1), path, PURCHASING, writeId).code()).isEqualTo("EFFECT_STATE_CONFLICT");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'agent.write.reconciled' AND resource_id = :id")
                .param("id", writeId.toString()).query(Integer.class).single()).isEqualTo(1);

        // Retry: the confirmed write is reused, not resent, and the request continues.
        AgentLlmStub.toolCall("c3", "notify_procurement", note("Note", "Body"), 0.0001);
        AgentLlmStub.answer(HIGH, 0.0001);
        Response resumed = retry(run);
        assertThat(resumed.status()).as(resumed.toString()).isEqualTo(200);
        awaitLifecycle(PURCHASING, run, "waiting");
        assertThat(sent()).isEqualTo(1);
        String toolCalls = jdbc.sql("SELECT tool_calls::text FROM agent_call WHERE run_id = :run ORDER BY created_at DESC LIMIT 1")
                .param("run", run).query(String.class).single();
        assertThat(toolCalls).contains("reused");
    }

    // ------------------------------------------------------------------ S85: approval expiry and the pre-send fence

    /** The approval of this workflow's tool shown with {@code status}, or null. */
    private JsonNode approvalFor(String workflowKey, String status) throws Exception {
        Response overview = api("mani.manager").get("/workspaces/{ws}/agent-write-tools", PURCHASING);
        assertThat(overview.status()).as(overview.toString()).isEqualTo(200);
        for (JsonNode approval : overview.body().path("approvals")) {
            if (approval.path("workflowKey").asString().equals(workflowKey)
                    && approval.path("status").asString().equals(status)) {
                return approval;
            }
        }
        return null;
    }

    /** Moves an approval's window into the past (the application clock is the system clock). */
    private static void expire(UUID approvalId) throws Exception {
        superuser("""
                UPDATE orqix.agent_write_tool_approval
                SET approved_at = now() - interval '31 days', expires_at = now() - interval '1 day' WHERE id = ?
                """, approvalId);
    }

    private static Instant instant(JsonNode node, String member) {
        return Instant.parse(node.path(member).asString());
    }

    @Test
    void anApprovalMayExpireWithinNinetyDaysAndAnExpiredApprovalAuthorizesNothingUntilApprovedAgain() throws Exception {
        grantApprover("mani.manager");
        // Bounds: 0 and 91 days are refused and store nothing.
        Draft probe = draft();
        for (int days : new int[] {0, 91}) {
            Response refused = approve("mani.manager", probe, "Bounded", days);
            assertThat(refused.status()).as(days + " days: " + refused).isIn(400, 422);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM agent_write_tool_approval WHERE workflow_key = :key")
                .param("key", probe.key()).query(Integer.class).single()).isZero();
        // Default unchanged: omitted or explicitly null means the approval never expires.
        Map<String, Object> explicitNull = new HashMap<>(Map.of("workflowKey", probe.key(), "connectionKey", connection,
                "connectionVersion", 1, "operation", "assessVendor", "toolName", "notify_procurement",
                "reason", "No end date"));
        explicitNull.put("expiresInDays", null);
        Response open = api("mani.manager").post(explicitNull, "/workspaces/{ws}/agent-write-tools/approvals", PURCHASING);
        assertThat(open.status()).as(open.toString()).isEqualTo(201);
        assertThat(open.body().path("expiresAt").isNull()).isTrue();
        assertThat(open.body().path("status").asString()).isEqualTo("active");
        // 1 and 90 days are accepted: exactly that many 24-hour days after the approval; the audit records the instant.
        Draft longest = draft();
        Response ninety = approve("mani.manager", longest, "For the quarter", 90);
        assertThat(ninety.status()).as(ninety.toString()).isEqualTo(201);
        assertThat(Duration.between(instant(ninety.body(), "approvedAt"), instant(ninety.body(), "expiresAt")))
                .isEqualTo(Duration.ofDays(90));
        String audited = jdbc.sql("""
                SELECT metadata ->> 'expiresAt' FROM audit_event WHERE action = 'agent.write-tool.approved' AND resource_id = :id
                """).param("id", ninety.body().path("id").asString()).query(String.class).single();
        assertThat(Instant.parse(audited)).isEqualTo(instant(ninety.body(), "expiresAt"));

        Draft draft = draft();
        Response approved = approve("mani.manager", draft, "Procurement pilot", 1);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(201);
        assertThat(Duration.between(instant(approved.body(), "approvedAt"), instant(approved.body(), "expiresAt")))
                .isEqualTo(Duration.ofDays(1));
        Response published = publish(draft);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        UUID approvalId = UUID.fromString(approved.body().path("id").asString());

        // It expires: the list says so (the stored row is still 'active' until someone approves again).
        expire(approvalId);
        JsonNode shown = approvalFor(draft.key(), "expired");
        assertThat(shown).isNotNull();
        assertThat(shown.path("expiresAt").isNull()).isFalse();
        assertThat(approvalFor(draft.key(), "active")).isNull();
        // Nothing is left to revoke.
        Response revoke = api("mani.manager").post(Map.of("reason", "Stop", "expectedVersion", shown.path("version").asInt()),
                "/workspaces/{ws}/agent-write-tools/approvals/{id}/revoke", PURCHASING, approvalId);
        assertThat(revoke.code()).as(revoke.toString()).isEqualTo("CONFIGURATION_VERSION_CONFLICT");
        assertThat(revoke.toString()).contains("expired");

        // The runtime refuses before the model is asked, with the reason; nothing is sent.
        UUID run = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, run, "needsAttention", "waiting", "completed");
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).as(detail.toString()).isEqualTo("needsAttention");
        assertThat(detail.path("attention").path("reason").asString()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(detail.path("attention").toString()).contains("Tell procurement").contains("expired");
        assertThat(AgentLlmStub.requests()).isEmpty();
        assertThat(sent()).isZero();

        // Publishing a workflow whose only approval expired is refused, and the message names expiry.
        Draft other = draft();
        Response trial = approve("mani.manager", other, "Short trial", 1);
        assertThat(trial.status()).as(trial.toString()).isEqualTo(201);
        expire(UUID.fromString(trial.body().path("id").asString()));
        Response refused = publish(other);
        assertThat(refused.status()).as(refused.toString()).isNotEqualTo(201);
        assertThat(refused.toString()).contains("WF_AGENT_WRITE_TOOL_UNAPPROVED").contains("expired");

        // Approving again closes the old row as 'expired' and authorizes; an operator retry then writes once.
        Response again = approve("mani.manager", draft, "Pilot extended", null);
        assertThat(again.status()).as(again.toString()).isEqualTo(201);
        assertThat(again.body().path("expiresAt").isNull()).isTrue();
        Map<String, Object> old = jdbc.sql("SELECT status, version FROM agent_write_tool_approval WHERE id = :id")
                .param("id", approvalId).query().singleRow();
        assertThat(old).containsEntry("status", "expired").containsEntry("version", 2);
        assertThat(approvalFor(draft.key(), "active").path("id").asString()).isEqualTo(again.body().path("id").asString());
        AgentLlmStub.toolCall("c1", "notify_procurement", note("Note", "Body"), 0.0001);
        AgentLlmStub.answer(HIGH, 0.0001);
        Response retried = retry(run);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        awaitLifecycle(PURCHASING, run, "waiting");
        assertThat(sent()).isEqualTo(1);
    }

    @Autowired
    private PolicyService policy;
    @Autowired
    private ConnectionService connections;
    @Autowired
    private AdapterTransport transport;
    @Autowired
    private AuditWriter auditWriter;
    @Autowired
    private PlatformTransactionManager transactions;

    private interface Step {
        void run() throws Exception;
    }

    /**
     * The production service on a test clock, whose journal runs {@code afterBegin} steps once the write's slot is
     * registered and its effect is in flight: after the authorization and before the fence and the send.
     */
    private AgentWriteService service(Clock clock, List<Step> afterBegin) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        EffectJournal hooked = new EffectJournal(jdbc, json, Clock.systemUTC()) {
            @Override
            public BeginResult begin(EffectRequest request, String leaseOwner, Duration leaseDuration,
                    String policyDenial) {
                BeginResult result = tx.execute(status -> super.begin(request, leaseOwner, leaseDuration, policyDenial));
                while (!afterBegin.isEmpty()) {
                    try {
                        afterBegin.remove(0).run();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return result;
            }
        };
        return new AgentWriteService(policy, connections, hooked, transport, auditWriter, jdbc, json, clock,
                transactions);
    }

    private ObjectNode arguments(String subject, String body) {
        ObjectNode arguments = json.object();
        arguments.put("subject", subject);
        arguments.put("body", body);
        return arguments;
    }

    @Test
    void theExpiryInstantIsExactAndTheFenceStopsTheSendAfterARevocationOrExpiryThatFollowsAuthorization()
            throws Exception {
        Draft draft = approvedAndPublished("mani.manager", 10);
        AgentLlmStub.toolCall("c1", "notify_procurement", note("Note", "Body"), 0.0001);
        AgentLlmStub.answer(HIGH, 0.0001);
        UUID run = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");
        assertThat(sent()).isEqualTo(1);
        JsonNode approval = approvalFor(draft.key(), "active");
        Instant expiresAt = instant(approval, "expiresAt");
        UUID agentEffect = jdbc.sql("SELECT id FROM effect_journal WHERE run_id = :run AND node_id = 'assess'")
                .param("run", run).query(UUID.class).single();
        ConnectionDefinition definition = connections.definition(PURCHASING, connection, 1).orElseThrow().definition();
        WriteTool tool = definition.operations().get("assessVendor").agent().writeTool("notify_procurement");
        AgentWriteTools.Scope scope = new AgentWriteTools.Scope(PURCHASING, run, agentEffect, "assess",
                subject("rhea.requester"), definition, "assessVendor");
        MutableClock clock = new MutableClock(expiresAt.minus(1, ChronoUnit.MICROS));
        List<Step> afterBegin = new ArrayList<>();
        AgentWriteService service = service(clock, afterBegin);

        // One microsecond before the expiry it authorizes; at the instant it does not, and the compiler's facts agree.
        assertThat(service.authorize(scope, tool).allowed()).isTrue();
        assertThat(service.activeApprovals(PURCHASING)).anyMatch(f -> f.workflowKey().equals(draft.key()));
        clock.set(expiresAt);
        assertThat(service.authorize(scope, tool).refusal()).isEqualTo("WRITE_APPROVAL_EXPIRED");
        assertThat(service.activeApprovals(PURCHASING)).noneMatch(f -> f.workflowKey().equals(draft.key()));
        // Every write checks again: at the instant nothing is registered or sent.
        AgentWriteTools.WriteResult late = service.write(scope, tool, 2, arguments("Late", "Body"));
        assertThat(late.outcome()).isEqualTo(AgentWriteTools.Outcome.REFUSED);
        assertThat(late.errorCode()).isEqualTo("WRITE_APPROVAL_EXPIRED");
        assertThat(late.effectId()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM agent_write_call WHERE run_id = :run").param("run", run)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(sent()).isEqualTo(1);

        // Revoked after the authorization, before the send (the old S75 race): the fence sees it, nothing is sent.
        clock.set(expiresAt.minus(1, ChronoUnit.DAYS));
        UUID approvalId = UUID.fromString(approval.path("id").asString());
        afterBegin.add(() -> assertThat(api("mani.manager").post(Map.of("reason", "Programme paused", "expectedVersion",
                approval.path("version").asInt()), "/workspaces/{ws}/agent-write-tools/approvals/{id}/revoke", PURCHASING,
                approvalId).status()).isEqualTo(200));
        AgentWriteTools.WriteResult revoked = service.write(scope, tool, 2, arguments("Second", "Body"));
        assertThat(afterBegin).isEmpty();
        assertThat(revoked.outcome()).isEqualTo(AgentWriteTools.Outcome.REFUSED);
        assertThat(revoked.errorCode()).isEqualTo("WRITE_NOT_APPROVED");
        assertThat(revoked.effectId()).isNotNull();
        assertThat(jdbc.sql("SELECT state, last_error_code FROM effect_journal WHERE id = :id").param("id", revoked.effectId())
                .query().singleRow()).containsEntry("state", "permanentFailure")
                .containsEntry("last_error_code", "WRITE_NOT_APPROVED");
        assertThat(sent()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT outcome FROM audit_event WHERE action = 'agent.write' AND metadata ->> 'effectId' = :effect
                """).param("effect", revoked.effectId().toString()).query(String.class).single()).isEqualTo("denied");

        // Expired after the authorization, before the send: likewise.
        Response renewed = approve("mani.manager", draft, "Renewed for a day", 1);
        assertThat(renewed.status()).as(renewed.toString()).isEqualTo(201);
        Instant renewedExpiry = instant(renewed.body(), "expiresAt");
        clock.set(renewedExpiry.minus(1, ChronoUnit.HOURS));
        afterBegin.add(() -> clock.set(renewedExpiry));
        AgentWriteTools.WriteResult expired = service.write(scope, tool, 3, arguments("Third", "Body"));
        assertThat(afterBegin).isEmpty();
        assertThat(expired.outcome()).isEqualTo(AgentWriteTools.Outcome.REFUSED);
        assertThat(expired.errorCode()).isEqualTo("WRITE_APPROVAL_EXPIRED");
        assertThat(jdbc.sql("SELECT state, last_error_code FROM effect_journal WHERE id = :id").param("id", expired.effectId())
                .query().singleRow()).containsEntry("state", "permanentFailure")
                .containsEntry("last_error_code", "WRITE_APPROVAL_EXPIRED");
        assertThat(sent()).isEqualTo(1);
    }

    @Test
    void theNinetyDayBoundDoesNotDependOnTheSessionTimeZone() throws Exception {
        grantApprover("mani.manager");
        Response approved = approve("mani.manager", draft(), "For the quarter", 90);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(201);
        String id = approved.body().path("id").asString();
        // A (past) window across the US spring clock change, in a session using that zone (the JDBC driver sends the JVM's).
        try (Connection c = DriverManager.getConnection(TestDatabase.jdbcUrl(), "postgres", "postgres");
                Statement s = c.createStatement()) {
            s.execute("SET TIME ZONE 'America/New_York'");
            // The cause V122 removes: in this zone '90 days' is one hour shorter than 90 * 24 hours.
            try (var rs = s.executeQuery("SELECT (timestamptz '2026-01-10 12:00+00' + interval '90 days')"
                    + " < (timestamptz '2026-01-10 12:00+00' + interval '2160 hours')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
            assertThat(s.executeUpdate("UPDATE orqix.agent_write_tool_approval SET approved_at = timestamptz "
                    + "'2026-01-10 12:00+00', expires_at = timestamptz '2026-01-10 12:00+00' + interval '2160 hours' "
                    + "WHERE id = '" + id + "'")).isEqualTo(1);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE orqix.agent_write_tool_approval SET expires_at = "
                    + "approved_at + interval '2160 hours 1 second' WHERE id = '" + id + "'"))
                    .hasMessageContaining("agent_write_tool_approval_expiry");
        }
    }

    // ------------------------------------------------------------------ S85: retention of write records

    @Autowired
    private AgentWriteRetention retention;

    /** A run whose agent wrote once (delivered); it waits at the manager task afterwards. */
    private UUID writingRun(Draft draft) throws Exception {
        AgentLlmStub.toolCall("c1", "notify_procurement", note("Note", "Body"), 0.0001);
        AgentLlmStub.answer(HIGH, 0.0001);
        UUID run = start("rhea.requester", draft);
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        return run;
    }

    private void endedDaysAgo(UUID run, int days) throws Exception {
        Response cancelled = api("olga.operator").post(Map.of("reason", "No longer needed", "expectedVersion",
                run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        jdbc.sql("UPDATE workflow_run SET ended_at = now() - make_interval(days => :days) WHERE id = :id")
                .param("days", days).param("id", run).update();
    }

    private int writeRecords(UUID run) {
        return jdbc.sql("SELECT count(*) FROM agent_write_call WHERE run_id = :run").param("run", run)
                .query(Integer.class).single();
    }

    private int journalEntries(UUID run) {
        return jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :run").param("run", run)
                .query(Integer.class).single();
    }

    @Test
    void retentionIsAdministratorOnlyVersionedAndRemovesOnlyFinalWriteRecordsOfLongEndedRequests() throws Exception {
        String settings = "/workspaces/{ws}/agent-write-retention";
        try {
            JsonNode initial = api("wade.admin").get(settings, PURCHASING).body();
            int version = initial.path("version").asInt();
            // Only holders of workspace.manage-access read or change it; the values are bounded.
            for (String user : List.of("dana.designer", "olga.operator", "rhea.requester")) {
                assertThat(api(user).get(settings, PURCHASING).status()).as(user).isEqualTo(403);
                assertThat(api(user).put(Map.of("retentionDays", 30, "expectedVersion", version), null, settings,
                        PURCHASING).status()).as(user).isEqualTo(403);
            }
            for (int days : new int[] {0, 3651}) {
                assertThat(api("wade.admin").put(Map.of("retentionDays", days, "expectedVersion", version), null,
                        settings, PURCHASING).status()).as(days + " days").isIn(400, 422);
            }

            Draft draft = approvedAndPublished("mani.manager");
            UUID old = writingRun(draft); // ended 40 days ago, delivered: removed
            UUID unknown = writingRun(draft); // ended 40 days ago, outcome unknown: kept until reconciled
            UUID running = writingRun(draft); // still waiting: kept
            UUID recent = writingRun(draft); // ended 5 days ago: kept
            UUID unregistered = writingRun(draft); // ended 40 days ago, one slot never registered: kept
            endedDaysAgo(old, 40);
            endedDaysAgo(unknown, 40);
            endedDaysAgo(recent, 5);
            endedDaysAgo(unregistered, 40);
            jdbc.sql("UPDATE effect_journal SET state = 'unknown' WHERE run_id = :run AND operation LIKE 'agentWrite.%'")
                    .param("run", unknown).update();
            Map<String, Object> slot = jdbc.sql("""
                    SELECT agent_effect_id, approval_id, approved_by, requested_by FROM agent_write_call WHERE run_id = :run
                    """).param("run", unregistered).query().singleRow();
            superuser("""
                    INSERT INTO orqix.agent_write_call (id, workspace_id, run_id, agent_effect_id, node_id, effect_key,
                        connection_key, connection_version, operation, tool_name, call_index, adapter_key, kind, approval_id,
                        approved_by, requested_by, argument_sha256, argument_chars)
                    VALUES (?, ?, ?, ?, 'assess:write:notify_procurement:2', ?, ?, 1, 'assessVendor', 'notify_procurement',
                        2, ?, 'email', ?, ?, ?, ?, 10)
                    """, UUID.randomUUID(), PURCHASING, unregistered, slot.get("agent_effect_id"),
                    "never-registered-" + UUID.randomUUID(), connection, mail, slot.get("approval_id"),
                    slot.get("approved_by"), slot.get("requested_by"), "0".repeat(64));
            int oldJournal = journalEntries(old);
            UUID oldWrite = jdbc.sql("SELECT id FROM agent_write_call WHERE run_id = :run").param("run", old)
                    .query(UUID.class).single();

            Response saved = api("wade.admin").put(Map.of("retentionDays", 30, "expectedVersion", version), null,
                    settings, PURCHASING);
            assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
            assertThat(saved.body().path("retentionDays").asInt()).isEqualTo(30);
            assertThat(saved.body().path("version").asInt()).isEqualTo(version + 1);
            assertThat(saved.body().path("eligibleNow").asInt()).isEqualTo(1);
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM audit_event WHERE action = 'agent.write.retention.updated' AND actor_type = 'user'
                      AND metadata ->> 'retentionDays' = '30' AND resource_id = :ws
                    """).param("ws", PURCHASING.toString()).query(Integer.class).single()).isGreaterThanOrEqualTo(1);
            Response stale = api("wade.admin").put(Map.of("retentionDays", 10, "expectedVersion", version), null,
                    settings, PURCHASING);
            assertThat(stale.status()).as(stale.toString()).isEqualTo(409);

            // The hourly sweep may have run in between; either way exactly the old request's record goes.
            assertThat(retention.purgeExpired()).isLessThanOrEqualTo(1);
            assertThat(retention.purgeExpired()).isZero();
            assertThat(writeRecords(old)).isZero();
            assertThat(writeRecords(unknown)).isEqualTo(1);
            assertThat(writeRecords(running)).isEqualTo(1);
            assertThat(writeRecords(recent)).isEqualTo(1);
            assertThat(writeRecords(unregistered)).isEqualTo(2);
            // The effect journal is untouched; the removal is audited by the system with ids only.
            assertThat(journalEntries(old)).isEqualTo(oldJournal);
            assertThat(jdbc.sql("SELECT state FROM effect_journal WHERE run_id = :run AND operation LIKE 'agentWrite.%'")
                    .param("run", old).query(String.class).single()).isEqualTo("succeeded");
            Map<String, Object> purged = jdbc.sql("""
                    SELECT actor_type, metadata ->> 'runId' AS run_id, metadata ->> 'retentionDays' AS days
                    FROM audit_event WHERE action = 'agent.write.purged' AND resource_id = :id
                    """).param("id", oldWrite.toString()).query().singleRow();
            assertThat(purged).containsEntry("actor_type", "system").containsEntry("run_id", old.toString())
                    .containsEntry("days", "30");

            // Once an operator records the unknown outcome, that request's record becomes eligible.
            JsonNode item = null;
            for (JsonNode candidate : api("olga.operator").get("/workspaces/{ws}/agent-writes?state=unknown", PURCHASING)
                    .body().path("items")) {
                if (candidate.path("runId").asString().equals(unknown.toString())) {
                    item = candidate;
                }
            }
            assertThat(item).isNotNull();
            Response reconciled = api("olga.operator").post(Map.of("outcome", "notApplied", "reason",
                    "Not in the mailbox", "expectedVersion", item.path("version").asInt()),
                    "/workspaces/{ws}/agent-writes/{id}/reconcile", PURCHASING, UUID.fromString(item.path("id").asString()));
            assertThat(reconciled.status()).as(reconciled.toString()).isEqualTo(200);
            assertThat(api("wade.admin").get(settings, PURCHASING).body().path("eligibleNow").asInt()).isEqualTo(1);
            assertThat(retention.purgeExpired()).isLessThanOrEqualTo(1);
            assertThat(writeRecords(unknown)).isZero();
            assertThat(writeRecords(unregistered)).isEqualTo(2);
            assertThat(writeRecords(running)).isEqualTo(1);

            // Clearing the setting (null days) stops removal.
            Map<String, Object> clear = new HashMap<>();
            clear.put("retentionDays", null);
            clear.put("expectedVersion", version + 1);
            Response cleared = api("wade.admin").put(clear, null, settings, PURCHASING);
            assertThat(cleared.status()).as(cleared.toString()).isEqualTo(200);
            assertThat(cleared.body().path("retentionDays").isNull()).isTrue();
            assertThat(cleared.body().path("eligibleNow").asInt()).isZero();
            jdbc.sql("UPDATE workflow_run SET ended_at = now() - interval '400 days' WHERE id = :id").param("id", recent)
                    .update();
            assertThat(retention.purgeExpired()).isZero();
            assertThat(writeRecords(recent)).isEqualTo(1);
        } finally {
            jdbc.sql("UPDATE workspace_agent_write_policy SET retention_days = NULL WHERE workspace_id = :ws")
                    .param("ws", PURCHASING).update();
        }
    }

    private static void superuser(String sql, Object... params) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestDatabase.jdbcUrl(), "postgres", "postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }
}
