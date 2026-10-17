package com.orqix.migration;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.TestDatabase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maker-checker governance of migrated workflows in the sandbox workspace (where designers also publish):
 * mapper = dana.designer, reviewer = paul.publisher, publisher = a third person created per test through role bindings.
 */
class MigrationGovernanceApiTest extends RuntimeTestBase {
    private static final String POLICY = "/workspaces/{ws}/migration-policy";
    private static final String REVIEW = "/workspaces/{ws}/workflows/{id}/migration-review";
    private static final String SOURCE = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:test\"><process id=\"%s\" isExecutable=\"true\"><startEvent id=\"start\"/><endEvent id=\"end\"/><sequenceFlow id=\"route\" sourceRef=\"start\" targetRef=\"end\"/></process></definitions>";
    private final Map<String, List<String>> groups = new HashMap<>();
    private final List<String> bindings = new ArrayList<>();
    private String reviewerBinding;

    private Api as(String user) { return groups.containsKey(user) ? api(user, groups.get(user)) : api(user); }
    private String bind(String role, String group) throws Exception {
        var created = api("wade.admin").post(Map.of("role", role, "principal", Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/role-bindings", SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        bindings.add(created.body().path("id").asString()); return created.body().path("id").asString();
    }
    private void unbind(String id) throws Exception {
        assertThat(api("wade.admin").delete("/workspaces/{ws}/access/role-bindings/{id}", SANDBOX, id).status()).isEqualTo(204); bindings.remove(id);
    }
    private int policyVersion() throws Exception { return api("wade.admin").get(POLICY, SANDBOX).body().path("version").asInt(); }
    @BeforeEach void people() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        groups.put("pia.publisher", List.of("gov-pub-" + s)); bind("PUBLISHER", "gov-pub-" + s);
        groups.put("rita.reviewer", List.of("gov-rev-" + s)); reviewerBinding = bind("PUBLISHER", "gov-rev-" + s);
        groups.put("ed.editor", List.of("gov-edit-" + s)); bind("WORKFLOW_DESIGNER", "gov-edit-" + s); bind("PUBLISHER", "gov-edit-" + s);
    }
    /** The sandbox is shared by every test class: leave it ungoverned and without the extra people. */
    @AfterEach void restore() throws Exception {
        var policy = api("wade.admin").get(POLICY, SANDBOX).body();
        if (policy.path("required").asBoolean()) put(false, policy.path("version").asInt(), "Test cleanup");
        for (String id : List.copyOf(bindings)) unbind(id);
    }
    private Response put(boolean required, int expectedVersion, String reason) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("required", required); body.put("expectedVersion", expectedVersion); body.put("reason", reason);
        return api("wade.admin").put(body, null, POLICY, SANDBOX);
    }
    private void require() throws Exception {
        var response = put(true, policyVersion(), "Independent review of migrations");
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
    }
    private record Migrated(UUID workflow, String migration) {}
    /** Converts a minimal source as dana.designer: she is the recorded mapper and author of draft revision 1. */
    private Migrated migrated() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        var job = api("dana.designer").post(Map.of("fileName", "governed-" + s + ".bpmn", "content", SOURCE.formatted("governed-" + s), "sourceFormat", "bpmn"), "/workspaces/{ws}/migrations", SANDBOX);
        assertThat(job.status()).as(job.toString()).isEqualTo(201);
        var request = json.object(); request.put("workflowKey", "governed-" + s); request.put("name", "Governed " + s); request.put("acknowledgeInfo", true); request.put("acknowledgeReview", true);
        var converted = api("dana.designer").post(request, "/workspaces/{ws}/migrations/{id}/convert", SANDBOX, job.body().path("id").asString());
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        return new Migrated(UUID.fromString(converted.body().path("workflow").path("id").asString()), job.body().path("id").asString());
    }
    private JsonNode review(String user, UUID workflow) throws Exception {
        var response = as(user).get(REVIEW, SANDBOX, workflow); assertThat(response.status()).as(response.toString()).isEqualTo(200); return response.body();
    }
    private Response approve(String user, UUID workflow, int revision, int expectedVersion, String reason) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("draftRevision", revision); body.put("expectedVersion", expectedVersion); body.put("reason", reason);
        return as(user).post(body, REVIEW, SANDBOX, workflow);
    }
    private Response publish(String user, UUID workflow, int revision) throws Exception {
        return as(user).post(Map.of("draftRevision", revision, "acknowledgeWarnings", true), UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", SANDBOX, workflow);
    }
    /** Saves a new draft revision as {@code user}; returns the new revision. */
    private int edit(String user, UUID workflow, int revision) throws Exception {
        var draft = as(user).get("/workspaces/{ws}/workflows/{id}/draft", SANDBOX, workflow).body();
        var definition = (ObjectNode) draft.path("definition"); definition.put("name", "Edited at revision " + revision);
        var saved = as(user).put(Map.of("definition", definition), "\"r" + revision + "\"", "/workspaces/{ws}/workflows/{id}/draft", SANDBOX, workflow);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200); return saved.body().path("revision").asInt();
    }
    private int audits(String action, String resource) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id=:ws AND action=:action AND resource_id=:id").param("ws", SANDBOX).param("action", action).param("id", resource).query(Integer.class).single();
    }
    private String sourceHash(Migrated migrated) {
        return jdbc.sql("SELECT source_sha256 FROM migration_job WHERE id=:id").param("id", UUID.fromString(migrated.migration())).query(String.class).single();
    }

    @Test void policyNeedsWorkspaceAccessManagementAndAnExpectedVersion() throws Exception {
        int version = policyVersion(); int changes = audits("migration.policy.changed", SANDBOX.toString());
        for (String user : List.of("dana.designer", "paul.publisher", "olga.operator")) {
            assertThat(api(user).get(POLICY, SANDBOX).status()).as(user).isEqualTo(403);
            var body = new LinkedHashMap<String, Object>(); body.put("required", true); body.put("expectedVersion", version); body.put("reason", "Not allowed");
            assertThat(api(user).put(body, null, POLICY, SANDBOX).status()).as(user).isEqualTo(403);
        }
        assertThat(api("otto.outsider").get(POLICY, SANDBOX).status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id=:ws AND action='auth.denied' AND metadata->>'operationId'='setMigrationPolicy'").param("ws", SANDBOX).query(Integer.class).single()).isPositive();
        assertThat(policyVersion()).isEqualTo(version); assertThat(audits("migration.policy.changed", SANDBOX.toString())).isEqualTo(changes);
        var changed = put(true, version, "Independent review of migrations");
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        assertThat(changed.body().path("required").asBoolean()).isTrue(); assertThat(changed.body().path("version").asInt()).isEqualTo(version + 1);
        assertThat(changed.body().path("reason").asString()).isEqualTo("Independent review of migrations"); assertThat(changed.body().path("updatedAt").isNull()).isFalse();
        assertThat(api("wade.admin").get(POLICY, SANDBOX).body()).isEqualTo(changed.body());
        // A stale reader cannot overwrite a newer policy; invalid requests change nothing.
        assertThat(put(false, version, "Stale").status()).isEqualTo(409);
        assertThat(put(false, version + 1, " ").status()).isEqualTo(400); assertThat(put(false, version + 1, "x".repeat(1001)).status()).isEqualTo(400);
        assertThat(put(false, -1, "Negative").status()).isEqualTo(400);
        assertThat(api("wade.admin").put(Map.of("required", true, "expectedVersion", version + 1), null, POLICY, SANDBOX).status()).isEqualTo(400);
        assertThat(policyVersion()).isEqualTo(version + 1);
        assertThat(audits("migration.policy.changed", SANDBOX.toString())).isEqualTo(changes + 1);
        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id=:ws AND action='migration.policy.changed' ORDER BY occurred_at DESC LIMIT 1").param("ws", SANDBOX).query(String.class).single()).contains("\"required\": true").contains("\"version\": " + (version + 1));
        // The policy belongs to one workspace.
        assertThat(api("wade.admin").get(POLICY, PURCHASING).body().path("required").asBoolean()).isFalse();
    }
    @Test void anIndependentReviewIsRequiredAndMapperReviewerAndPublisherMustDiffer() throws Exception {
        require(); var migrated = migrated(); UUID wf = migrated.workflow();
        // No review yet: nobody can publish, whatever their permission.
        for (String user : List.of("paul.publisher", "dana.designer", "pia.publisher")) {
            var blocked = publish(user, wf, 1); assertThat(blocked.status()).as(user + " " + blocked).isEqualTo(422);
            assertThat(blocked.code()).isEqualTo("MIGRATION_BLOCKED"); assertThat(blocked.body().path("detail").asString()).contains("independent approval");
        }
        var state = review("paul.publisher", wf);
        assertThat(state.path("migration").asBoolean()).isTrue(); assertThat(state.path("required").asBoolean()).isTrue(); assertThat(state.path("approved").asBoolean()).isFalse();
        assertThat(state.path("canApprove").asBoolean()).isTrue(); assertThat(state.path("draftRevision").asInt()).isEqualTo(1); assertThat(state.path("reviewVersion").asInt()).isZero();
        assertThat(state.path("mapper").path("displayName").asString()).isEqualTo("Dana Designer"); assertThat(state.path("reviewedBy").isNull()).isTrue();
        assertThat(state.path("sourceHash").asString()).isEqualTo(sourceHash(migrated)); assertThat(state.path("definitionHash").asString()).matches("[0-9a-f]{64}"); assertThat(state.path("mappingsHash").asString()).matches("[0-9a-f]{64}");
        assertThat(review("dana.designer", wf).path("canApprove").asBoolean()).as("the mapper cannot approve").isFalse();
        assertThat(review("olga.operator", wf).path("canApprove").asBoolean()).as("an operator cannot approve").isFalse();
        assertThat(as("otto.outsider").get(REVIEW, SANDBOX, wf).status()).isEqualTo(404);
        assertThat(as("dana.designer").get(REVIEW, PURCHASING, wf).status()).as("workflow of another workspace").isEqualTo(404);

        // The mapper has publish rights in the sandbox, yet cannot approve her own conversion.
        var self = approve("dana.designer", wf, 1, 0, "Looks fine to me"); assertThat(self.status()).as(self.toString()).isEqualTo(403);
        assertThat(self.body().path("detail").asString()).contains("mapper");
        assertThat(approve("olga.operator", wf, 1, 0, "Operator").status()).isEqualTo(403); assertThat(approve("otto.outsider", wf, 1, 0, "Outsider").status()).isEqualTo(404);
        assertThat(approve("paul.publisher", wf, 2, 0, "Wrong revision").status()).isEqualTo(409); assertThat(approve("paul.publisher", wf, 1, 3, "Wrong version").status()).isEqualTo(409);
        assertThat(approve("paul.publisher", wf, 1, 0, " ").status()).isEqualTo(400); assertThat(approve("paul.publisher", wf, 0, 0, "Revision zero").status()).isEqualTo(400);
        assertThat(review("paul.publisher", wf).path("approved").asBoolean()).isFalse(); assertThat(audits("migration.review.approved", wf.toString())).isZero();

        var approved = approve("paul.publisher", wf, 1, 0, "Mappings checked against the source");
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        assertThat(approved.body().path("approved").asBoolean()).isTrue(); assertThat(approved.body().path("reviewVersion").asInt()).isEqualTo(1);
        assertThat(approved.body().path("reviewedBy").path("displayName").asString()).isEqualTo("Paul Publisher"); assertThat(approved.body().path("reviewReason").asString()).isEqualTo("Mappings checked against the source");
        assertThat(approved.body().path("reviewedAt").isNull()).isFalse(); assertThat(approved.body().path("definitionHash").asString()).isEqualTo(state.path("definitionHash").asString());
        assertThat(approve("paul.publisher", wf, 1, 0, "Again").status()).as("an approval already exists for version 0").isEqualTo(409);
        assertThat(audits("migration.review.approved", wf.toString())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE action='migration.review.approved' AND resource_id=:id").param("id", wf.toString()).query(String.class).single())
                .contains(state.path("definitionHash").asString()).contains("\"revision\": 1").doesNotContain("Mappings checked");

        // Reviewer and mapper cannot publish what they reviewed or mapped; a third person can.
        var byReviewer = publish("paul.publisher", wf, 1); assertThat(byReviewer.status()).as(byReviewer.toString()).isEqualTo(403);
        assertThat(byReviewer.body().path("detail").asString()).contains("different people");
        assertThat(publish("dana.designer", wf, 1).status()).isEqualTo(403);
        assertThat(publish("olga.operator", wf, 1).status()).isEqualTo(403);
        var published = publish("pia.publisher", wf, 1); assertThat(published.status()).as(published.toString()).isEqualTo(201);
        assertThat(audits("workflow.published", published.body().path("id").asString())).isEqualTo(1);
        assertThat(api("wade.admin").get("/workspaces/{ws}/workflows/{id}", SANDBOX, wf).body().path("activeRelease").isNull()).isFalse();
    }
    @Test void changingTheDraftInvalidatesTheReviewAndDraftAuthorsCannotApproveOrPublish() throws Exception {
        require(); var migrated = migrated(); UUID wf = migrated.workflow();
        assertThat(approve("paul.publisher", wf, 1, 0, "Reviewed revision 1").status()).isEqualTo(200);
        int revision = edit("ed.editor", wf, 1); assertThat(revision).isEqualTo(2);
        var state = review("paul.publisher", wf);
        assertThat(state.path("draftRevision").asInt()).isEqualTo(2); assertThat(state.path("approved").asBoolean()).isFalse(); assertThat(state.path("reviewVersion").asInt()).isZero();
        assertThat(state.path("canApprove").asBoolean()).isTrue();
        assertThat(approve("paul.publisher", wf, 1, 1, "Stale revision").status()).as("revision 1 is no longer the draft").isEqualTo(409);
        var stale = publish("pia.publisher", wf, 1); assertThat(stale.status()).as(stale.toString()).isEqualTo(422); assertThat(stale.body().path("detail").asString()).contains("latest reviewed");
        var unreviewed = publish("pia.publisher", wf, 2); assertThat(unreviewed.status()).as(unreviewed.toString()).isEqualTo(422); assertThat(unreviewed.body().path("detail").asString()).contains("independent approval");
        // The author of the changed draft is not independent either, although she is not the mapper.
        assertThat(review("ed.editor", wf).path("canApprove").asBoolean()).isFalse();
        var byAuthor = approve("ed.editor", wf, 2, 0, "My own edit"); assertThat(byAuthor.status()).as(byAuthor.toString()).isEqualTo(403); assertThat(byAuthor.body().path("detail").asString()).contains("draft author");
        assertThat(approve("paul.publisher", wf, 2, 0, "Reviewed the edit").status()).isEqualTo(200);
        assertThat(publish("ed.editor", wf, 2).status()).as("draft author may not publish a governed migration").isEqualTo(403);
        assertThat(publish("paul.publisher", wf, 2).status()).isEqualTo(403); assertThat(publish("dana.designer", wf, 2).status()).isEqualTo(403);
        assertThat(publish("pia.publisher", wf, 2).status()).isEqualTo(201);
    }
    @Test void changedMappingsOrSourceInvalidateTheApproval() throws Exception {
        require(); var migrated = migrated(); UUID wf = migrated.workflow(); UUID job = UUID.fromString(migrated.migration());
        assertThat(approve("paul.publisher", wf, 1, 0, "Reviewed").status()).isEqualTo(200);
        assertThat(review("pia.publisher", wf).path("approved").asBoolean()).isTrue();
        String mappings = jdbc.sql("SELECT mappings::text FROM migration_job WHERE id=:id").param("id", job).query(String.class).single();
        jdbc.sql("UPDATE migration_job SET mappings=jsonb_set(mappings,'{name}','\"Changed after review\"') WHERE id=:id").param("id", job).update();
        var changed = review("pia.publisher", wf); assertThat(changed.path("approved").asBoolean()).isFalse(); assertThat(changed.path("detail").asString()).contains("Review source mappings");
        var blocked = publish("pia.publisher", wf, 1); assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        jdbc.sql("UPDATE migration_job SET mappings=CAST(:m AS jsonb) WHERE id=:id").param("m", mappings).param("id", job).update();
        assertThat(review("pia.publisher", wf).path("approved").asBoolean()).as("the approval covers exactly the reviewed mappings").isTrue();
        String source = sourceHash(migrated);
        try (var connection = DriverManager.getConnection(TestDatabase.jdbcUrl(), TestDatabase.MIGRATOR_USER, TestDatabase.MIGRATOR_PASSWORD); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO orqix");
            statement.executeUpdate("UPDATE migration_job SET source_sha256='" + "0".repeat(64) + "' WHERE id='" + job + "'");
            try {
                assertThat(review("pia.publisher", wf).path("approved").asBoolean()).isFalse();
                assertThat(publish("pia.publisher", wf, 1).status()).isEqualTo(422);
            } finally { statement.executeUpdate("UPDATE migration_job SET source_sha256='" + source + "' WHERE id='" + job + "'"); }
        }
        assertThat(review("pia.publisher", wf).path("approved").asBoolean()).isTrue();
        assertThat(publish("pia.publisher", wf, 1).status()).isEqualTo(201);
    }
    @Test void aReviewerWhoLostPublishAccessNoLongerCountsAsIndependentApproval() throws Exception {
        require(); var migrated = migrated(); UUID wf = migrated.workflow();
        var rita = approve("rita.reviewer", wf, 1, 0, "Reviewed by Rita"); assertThat(rita.status()).as(rita.toString()).isEqualTo(200);
        assertThat(review("pia.publisher", wf).path("approved").asBoolean()).isTrue();
        unbind(reviewerBinding);
        var lost = review("paul.publisher", wf);
        assertThat(lost.path("approved").asBoolean()).isFalse(); assertThat(lost.path("reviewedBy").path("displayName").asString()).isEqualTo("Rita Reviewer");
        var blocked = publish("pia.publisher", wf, 1); assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        assertThat(blocked.body().path("detail").asString()).contains("no longer has publishing access");
        // A fresh review supersedes the invalid one.
        assertThat(approve("paul.publisher", wf, 1, 0, "Stale expectation").status()).as("version 1 exists").isEqualTo(409);
        assertThat(approve("paul.publisher", wf, 1, 1, "Second review").status()).isEqualTo(200);
        assertThat(publish("pia.publisher", wf, 1).status()).isEqualTo(201);
    }
    @Test void historicalConversionsWithoutARecordedMapperCannotBeApprovedOrPublishedUnderThePolicy() throws Exception {
        require(); var migrated = migrated(); UUID wf = migrated.workflow();
        jdbc.sql("UPDATE migration_job SET converted_by_subject_id=NULL WHERE id=:id").param("id", UUID.fromString(migrated.migration())).update();
        var state = review("paul.publisher", wf);
        assertThat(state.path("mapper").isNull()).isTrue(); assertThat(state.path("canApprove").asBoolean()).isFalse(); assertThat(state.path("approved").asBoolean()).isFalse();
        var approval = approve("paul.publisher", wf, 1, 0, "Cannot know who mapped this");
        assertThat(approval.status()).as(approval.toString()).isEqualTo(422); assertThat(approval.code()).isEqualTo("MIGRATION_BLOCKED"); assertThat(approval.body().path("detail").asString()).contains("Historical");
        assertThat(publish("pia.publisher", wf, 1).status()).isEqualTo(422);
        // Without the policy the ordinary publish controls apply again.
        var policy = api("wade.admin").get(POLICY, SANDBOX).body(); assertThat(put(false, policy.path("version").asInt(), "Policy lifted").status()).isEqualTo(200);
        assertThat(review("paul.publisher", wf).path("required").asBoolean()).isFalse();
        assertThat(publish("pia.publisher", wf, 1).status()).isEqualTo(201);
    }
    @Test void ordinaryWorkflowsAreNotGovernedAndHaveNoMigrationReview() throws Exception {
        require();
        UUID ordinary = publishPurchase(SANDBOX, null); // authored from a template and published by its designer, policy notwithstanding
        var state = review("paul.publisher", ordinary);
        assertThat(state.path("migration").asBoolean()).isFalse(); assertThat(state.path("required").asBoolean()).isFalse(); assertThat(state.path("mapper").isNull()).isTrue();
        assertThat(state.path("canApprove").asBoolean()).isFalse(); assertThat(state.path("detail").asString()).contains("no recorded migration");
        assertThat(approve("paul.publisher", ordinary, 2, 0, "Not a migration").status()).isEqualTo(422);
        assertThat(as("paul.publisher").get(REVIEW, SANDBOX, UUID.randomUUID()).status()).isEqualTo(404);
    }
}
