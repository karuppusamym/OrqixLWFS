package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * PL02b (docs/build/S74): the optional approval gate before promotion into a production workspace. Real PostgreSQL;
 * every response is validated against openapi.yaml by the test client.
 */
class PromotionApprovalTest extends RuntimeTestBase {

    private final List<UUID> grants = new java.util.ArrayList<>();

    @AfterEach
    void restore() throws Exception {
        for (UUID grant : grants) {
            api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, grant);
        }
        grants.clear();
        jdbc.sql("DELETE FROM workspace_environment WHERE workspace_id = :ws").param("ws", PURCHASING).update();
    }

    private void setGate(boolean on) throws Exception {
        int version = api("wade.admin").get("/workspaces/{ws}/environment", PURCHASING).body().path("version").asInt();
        var set = api("wade.admin").put(Map.of("stage", "production", "expectedVersion", version, "requireApproval", on),
                null, "/workspaces/{ws}/environment", PURCHASING);
        assertThat(set.status()).as(set.toString()).isEqualTo(200);
        assertThat(set.body().path("requireApproval").asBoolean()).isEqualTo(on);
    }

    private void grantApproval(String user) throws Exception {
        String subject = api(user).get("/me").body().path("subject").path("id").asString();
        var granted = api("wade.admin").post(Map.of("permission", "workflow.approve-promotion",
                "principal", Map.of("type", "subject", "value", subject)), "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(granted.status()).as(granted.toString()).isEqualTo(201);
        grants.add(UUID.fromString(granted.body().path("id").asString()));
    }

    private UUID activeRelease(UUID ws, UUID wf) throws Exception {
        JsonNode items = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases", ws, wf).body().path("items");
        return UUID.fromString(items.get(0).path("id").asString());
    }

    private String releasePath(UUID wf, UUID release, String suffix) {
        return "/workspaces/{ws}/workflows/" + wf + "/releases/" + release + suffix;
    }

    private Response requestPromotion(String user, UUID wf, UUID release, Integer expectedDraft) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("targetWorkspaceId", PURCHASING.toString());
        body.put("note", "Needed for the quarter close");
        if (expectedDraft != null) {
            body.put("expectedDraftRevision", expectedDraft);
        }
        return api(user).post(body, releasePath(wf, release, "/promotion-requests"), SANDBOX);
    }

    private JsonNode targetWorkflow(String key) throws Exception {
        for (JsonNode w : api("dana.designer").get("/workspaces/{ws}/workflows?q=" + key, PURCHASING).body().path("items")) {
            if (key.equals(w.path("key").asString())) {
                return w;
            }
        }
        return null;
    }

    private int audit(String action, UUID ws) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = :a AND workspace_id = :ws").param("a", action)
                .param("ws", ws).query(Integer.class).single();
    }

    @Test
    void aGatedProductionWorkspaceTakesPromotionsOnlyThroughASecondPersonsApproval() throws Exception {
        setGate(true);
        UUID source = publishPurchase(SANDBOX, null);
        UUID release = activeRelease(SANDBOX, source);
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", SANDBOX, source).body().path("key").asString();

        var report = api("dana.designer").post(Map.of("targetWorkspaceId", PURCHASING.toString()),
                releasePath(source, release, "/promotion-check"), SANDBOX);
        assertThat(report.status()).as(report.toString()).isEqualTo(200);
        assertThat(report.body().path("approvalRequired").asBoolean()).isTrue();
        assertThat(report.body().path("compatible").asBoolean()).isTrue();

        // A direct promotion is refused and creates nothing.
        var direct = api("dana.designer").post(Map.of("targetWorkspaceId", PURCHASING.toString()),
                releasePath(source, release, "/promote"), SANDBOX);
        assertThat(direct.status()).isEqualTo(409);
        assertThat(direct.code()).isEqualTo("PROMOTION_APPROVAL_REQUIRED");
        assertThat(targetWorkflow(key)).isNull();

        // Asking needs the same rights as promoting.
        assertThat(requestPromotion("paul.publisher", source, release, null).status()).isEqualTo(403);
        assertThat(requestPromotion("otto.outsider", source, release, null).status()).isEqualTo(404);
        var asked = requestPromotion("dana.designer", source, release, null);
        assertThat(asked.status()).as(asked.toString()).isEqualTo(201);
        assertThat(asked.body().path("status").asString()).isEqualTo("pending");
        assertThat(asked.body().path("note").asString()).contains("quarter close");
        UUID requestId = UUID.fromString(asked.body().path("id").asString());
        assertThat(targetWorkflow(key)).as("a request creates nothing in the target").isNull();
        assertThat(requestPromotion("dana.designer", source, release, null).status()).as("one waiting request per workflow")
                .isEqualTo(409);

        // Who sees the request.
        assertThat(api("vic.viewer").get("/workspaces/{ws}/promotion-requests", PURCHASING).body().path("items")).hasSize(1);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/promotion-requests", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/promotion-requests", PURCHASING).status()).isEqualTo(404);

        // Only an explicit grant approves; the requester never does, even with the grant.
        String approvePath = "/workspaces/{ws}/promotion-requests/" + requestId + "/approve";
        String rejectPath = "/workspaces/{ws}/promotion-requests/" + requestId + "/reject";
        assertThat(api("paul.publisher").post(Map.of(), approvePath, PURCHASING).status()).isEqualTo(403);
        assertThat(api("dana.designer").post(Map.of(), approvePath, PURCHASING).status()).isEqualTo(403);
        grantApproval("paul.publisher");
        grantApproval("dana.designer");
        var self = api("dana.designer").post(Map.of(), approvePath, PURCHASING);
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.code()).isEqualTo("SELF_APPROVAL_DENIED");
        var selfReject = api("dana.designer").post(Map.of("note", "no"), rejectPath, PURCHASING);
        assertThat(selfReject.code()).isEqualTo("SELF_APPROVAL_DENIED");
        assertThat(audit("auth.denied", PURCHASING)).isGreaterThanOrEqualTo(3);
        assertThat(targetWorkflow(key)).isNull();
        // The database refuses the same thing even if the application were bypassed.
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE promotion_request SET status = 'approved', decided_by = requested_by, decided_at = now(),
                       promotion_id = (SELECT id FROM release_promotion LIMIT 1) WHERE id = :id
                """).param("id", requestId).update()).hasMessageContaining("promotion_request");

        var noReason = api("paul.publisher").post(Map.of(), rejectPath, PURCHASING);
        assertThat(noReason.status()).isEqualTo(422);
        assertThat(noReason.code()).isEqualTo("REASON_REQUIRED");

        var approved = api("paul.publisher").post(Map.of("note", "Checked the report"), approvePath, PURCHASING);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        assertThat(approved.body().path("status").asString()).isEqualTo("approved");
        assertThat(approved.body().path("decidedBy").asString()).isEqualTo("Paul Publisher");
        assertThat(approved.body().path("promotionId").isNull()).isFalse();
        JsonNode created = targetWorkflow(key);
        assertThat(created).as("approval saved the draft in the target").isNotNull();
        UUID targetId = UUID.fromString(created.path("id").asString());
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, targetId).body()
                .path("activeRelease").isNull()).as("approval never publishes").isTrue();
        assertThat(api("paul.publisher").post(Map.of(), approvePath, PURCHASING).status()).as("decided once").isEqualTo(409);

        JsonNode history = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/promotions", PURCHASING, targetId).body()
                .path("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("promotedBy").asString()).isEqualTo("Dana Designer");
        assertThat(history.get(0).path("approvedBy").asString()).isEqualTo("Paul Publisher");

        assertThat(audit("release.promotion.requested", SANDBOX)).isGreaterThanOrEqualTo(1);
        assertThat(audit("release.promotion.approval-requested", PURCHASING)).isGreaterThanOrEqualTo(1);
        // Scoped to this request: other tests in the class also approve promotions into this workspace.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'release.promotion.approved' AND resource_id = :id")
                .param("id", requestId.toString()).query(Integer.class).single()).isEqualTo(1);
        assertThat(audit("release.promoted", SANDBOX)).isGreaterThanOrEqualTo(1);
        UUID pia = UUID.fromString(api("paul.publisher").get("/me").body().path("subject").path("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'release.promotion.received' AND actor_subject_id = :a"
                + " AND metadata->>'requestId' = :id")
                .param("a", pia).param("id", requestId.toString()).query(Integer.class).single())
                .as("the approver is the audited actor").isEqualTo(1);
    }

    @Test
    void aStaleTargetDraftLeavesTheRequestPendingAndOnlyTheRequesterCancels() throws Exception {
        setGate(true);
        grantApproval("paul.publisher");
        UUID source = publishPurchase(SANDBOX, null);
        UUID release = activeRelease(SANDBOX, source);
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", SANDBOX, source).body().path("key").asString();

        // First round: approved, so the target has the workflow at draft revision 1.
        UUID first = UUID.fromString(requestPromotion("dana.designer", source, release, null).body().path("id").asString());
        assertThat(api("paul.publisher").post(Map.of(), "/workspaces/{ws}/promotion-requests/" + first + "/approve",
                PURCHASING).status()).isEqualTo(200);
        UUID targetId = UUID.fromString(targetWorkflow(key).path("id").asString());

        // A request into the existing workflow must name the draft revision it expects.
        assertThat(requestPromotion("dana.designer", source, release, null).status()).isEqualTo(422);
        assertThat(requestPromotion("dana.designer", source, release, 9).status()).isEqualTo(412);
        var second = requestPromotion("dana.designer", source, release, 1);
        assertThat(second.status()).as(second.toString()).isEqualTo(201);
        UUID secondId = UUID.fromString(second.body().path("id").asString());

        // Someone else saves the target draft before the approver decides: approval fails and nothing changes.
        jdbc.sql("UPDATE workflow SET draft_revision = draft_revision + 1 WHERE id = :id").param("id", targetId).update();
        var stale = api("paul.publisher").post(Map.of(), "/workspaces/{ws}/promotion-requests/" + secondId + "/approve",
                PURCHASING);
        assertThat(stale.status()).as(stale.toString()).isEqualTo(412);
        assertThat(stale.code()).isEqualTo("STALE_REVISION");
        assertThat(api("paul.publisher").get("/workspaces/{ws}/promotion-requests", PURCHASING).body().path("items")
                .get(0).path("status").asString()).isEqualTo("pending");

        // Only the requester withdraws it, once.
        assertThat(api("paul.publisher").post(null, "/workspaces/{ws}/promotion-requests/" + secondId + "/cancel",
                PURCHASING).status()).isEqualTo(403);
        var cancelled = api("dana.designer").post(null, "/workspaces/{ws}/promotion-requests/" + secondId + "/cancel",
                PURCHASING);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asString()).isEqualTo("cancelled");
        assertThat(api("dana.designer").post(null, "/workspaces/{ws}/promotion-requests/" + secondId + "/cancel",
                PURCHASING).status()).isEqualTo(409);

        // A rejection records the reason and also ends the request.
        var third = requestPromotion("dana.designer", source, release, 2);
        assertThat(third.status()).as(third.toString()).isEqualTo(201);
        var rejected = api("paul.publisher").post(Map.of("note", "Not before the freeze ends"),
                "/workspaces/{ws}/promotion-requests/" + third.body().path("id").asString() + "/reject", PURCHASING);
        assertThat(rejected.status()).as(rejected.toString()).isEqualTo(200);
        assertThat(rejected.body().path("status").asString()).isEqualTo("rejected");
        assertThat(rejected.body().path("decisionNote").asString()).contains("freeze");
        // Only this workflow's requests: other tests in the class also ask for promotions into this workspace.
        List<JsonNode> all = new java.util.ArrayList<>();
        api("dana.designer").get("/workspaces/{ws}/promotion-requests?status=all", PURCHASING).body().path("items")
                .forEach(r -> { if (key.equals(r.path("workflowKey").asString())) all.add(r); });
        assertThat(all).extracting(r -> r.path("status").asString()).containsExactly("rejected", "cancelled", "approved");
        List<JsonNode> pending = new java.util.ArrayList<>();
        api("dana.designer").get("/workspaces/{ws}/promotion-requests", PURCHASING).body().path("items")
                .forEach(r -> { if (key.equals(r.path("workflowKey").asString())) pending.add(r); });
        assertThat(pending).isEmpty();
    }

    @Test
    void theGateIsAProductionOnlySettingAndOtherwisePromotionStaysDirect() throws Exception {
        // It cannot be switched on for a workspace that is not production.
        int version = api("wade.admin").get("/workspaces/{ws}/environment", SANDBOX).body().path("version").asInt();
        assertThat(api("wade.admin").put(Map.of("stage", "test", "expectedVersion", version, "requireApproval", true), null,
                "/workspaces/{ws}/environment", SANDBOX).status()).isEqualTo(422);

        // Moving a gated workspace out of production switches the gate off with it.
        setGate(true);
        int gated = api("wade.admin").get("/workspaces/{ws}/environment", PURCHASING).body().path("version").asInt();
        var staging = api("wade.admin").put(Map.of("stage", "staging", "expectedVersion", gated), null,
                "/workspaces/{ws}/environment", PURCHASING);
        assertThat(staging.status()).as(staging.toString()).isEqualTo(200);
        assertThat(staging.body().path("requireApproval").asBoolean()).isFalse();
        setGateStage("production");
        assertThat(api("wade.admin").get("/workspaces/{ws}/environment", PURCHASING).body().path("requireApproval").asBoolean())
                .isFalse();

        // Without the gate a request is pointless and direct promotion works as before.
        UUID source = publishPurchase(SANDBOX, null);
        UUID release = activeRelease(SANDBOX, source);
        var refused = requestPromotion("dana.designer", source, release, null);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("targetWorkspaceId", PURCHASING.toString()),
                releasePath(source, release, "/promotion-check"), SANDBOX).body().path("approvalRequired").asBoolean()).isFalse();
        assertThat(api("dana.designer").post(Map.of("targetWorkspaceId", PURCHASING.toString()),
                releasePath(source, release, "/promote"), SANDBOX).status()).isEqualTo(201);
    }

    /** Notifications of one kind the user has in a workspace, newest first. */
    private List<JsonNode> notificationsOf(String user, UUID ws, String kind) throws Exception {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode n : api(user).get("/workspaces/{ws}/me/notifications", ws).body().path("items")) {
            if (kind.equals(n.path("kind").asString())) {
                out.add(n);
            }
        }
        return out;
    }

    @Test
    void approversHearOfARequestAndTheRequesterHearsOfTheDecision() throws Exception {
        // S106 (PL02a): in-app notices, never to the requester for their own request.
        setGate(true);
        grantApproval("paul.publisher");
        grantApproval("dana.designer");
        int paulBefore = notificationsOf("paul.publisher", PURCHASING, "promotionRequest").size();
        int danaBefore = notificationsOf("dana.designer", PURCHASING, "promotionRequest").size();
        UUID source = publishPurchase(SANDBOX, null);
        UUID release = activeRelease(SANDBOX, source);
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", SANDBOX, source).body().path("key").asString();
        // An approver through an identity-provider group grant is found from the grant, not from a scan of all users.
        String group = "promo-approvers-" + UUID.randomUUID().toString().substring(0, 8);
        UUID olga = UUID.fromString(api("olga.operator").get("/me").body().path("subject").path("id").asString());
        var groupGrant = api("wade.admin").post(Map.of("permission", "workflow.approve-promotion",
                "principal", Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(groupGrant.status()).as(groupGrant.toString()).isEqualTo(201);
        grants.add(UUID.fromString(groupGrant.body().path("id").asString()));
        jdbc.sql("UPDATE subject SET idp_groups = idp_groups || to_jsonb(CAST(:g AS text)) WHERE id = :id")
                .param("g", group).param("id", olga).update();
        var asked = requestPromotion("dana.designer", source, release, null);
        assertThat(asked.status()).as(asked.toString()).isEqualTo(201);
        UUID requestId = UUID.fromString(asked.body().path("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE recipient_subject_id = :s AND dedup_key = :k")
                .param("s", olga).param("k", "promotion-request:" + requestId + ":" + olga).query(Long.class).single())
                .as("approver through a group grant").isEqualTo(1);

        List<JsonNode> paul = notificationsOf("paul.publisher", PURCHASING, "promotionRequest");
        assertThat(paul).hasSize(paulBefore + 1);
        assertThat(paul.get(0).path("message").asString()).contains(key, "release 1").doesNotContain("quarter close");
        assertThat(paul.get(0).path("runId").isNull() || paul.get(0).path("runId").isMissingNode()).isTrue();
        assertThat(notificationsOf("dana.designer", PURCHASING, "promotionRequest")).as("never the requester").hasSize(danaBefore);
        String meta = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'release.promotion.approval-requested' AND resource_id = :id")
                .param("id", requestId.toString()).query(String.class).single();
        assertThat(meta).contains("\"approversNotified\"");

        int decisionsBefore = notificationsOf("dana.designer", SANDBOX, "promotionDecision").size();
        var rejected = api("paul.publisher").post(Map.of("note", "Wait for the audit"),
                "/workspaces/{ws}/promotion-requests/" + requestId + "/reject", PURCHASING);
        assertThat(rejected.status()).as(rejected.toString()).isEqualTo(200);
        List<JsonNode> decisions = notificationsOf("dana.designer", SANDBOX, "promotionDecision");
        assertThat(decisions).hasSize(decisionsBefore + 1);
        assertThat(decisions.get(0).path("message").asString()).contains(key, "was rejected", "Wait for the audit");
        assertThat(decisions.get(0).path("workflowId").asString()).isEqualTo(source.toString());

        var again = requestPromotion("dana.designer", source, release, null);
        assertThat(again.status()).as(again.toString()).isEqualTo(201);
        var approved = api("paul.publisher").post(Map.of(), "/workspaces/{ws}/promotion-requests/" + again.body().path("id").asString()
                + "/approve", PURCHASING);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        assertThat(notificationsOf("dana.designer", SANDBOX, "promotionDecision").get(0).path("message").asString())
                .contains("was approved");
    }

    private void setGateStage(String stage) throws Exception {
        int version = api("wade.admin").get("/workspaces/{ws}/environment", PURCHASING).body().path("version").asInt();
        assertThat(api("wade.admin").put(Map.of("stage", stage, "expectedVersion", version), null,
                "/workspaces/{ws}/environment", PURCHASING).status()).isEqualTo(200);
    }
}
