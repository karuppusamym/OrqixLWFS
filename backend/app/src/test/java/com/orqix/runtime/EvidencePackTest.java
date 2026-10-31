package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Control evidence packs per release and per run (docs/27 section 3, Create) through the public API. */
class EvidencePackTest extends RuntimeTestBase {

    private static final String RELEASE_PACK = "/workspaces/{ws}/workflows/{wf}/releases/{rel}/evidence-pack";
    private static final String RUN_PACK = "/workspaces/{ws}/runs/{run}/evidence-pack";

    private UUID releaseId(UUID workflow, int index) throws Exception {
        return UUID.fromString(api("paul.publisher").get("/workspaces/{ws}/workflows/{id}/releases", PURCHASING, workflow)
                .body().path("items").get(index).path("id").asString());
    }

    private long audits(String action, String resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = :a AND resource_id = :r")
                .param("a", action).param("r", resourceId).query(Long.class).single();
    }

    private UUID subject(String user) throws Exception {
        return UUID.fromString(api(user).get("/me").body().path("subject").path("id").asString());
    }

    @Test
    void releasePackShowsIdentityApprovalsTestsAndDiffInEveryFormat() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID r1 = releaseId(workflow, 0);
        // Release 2: rename the manager step and raise a rule threshold.
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, workflow).body().path("definition");
        for (JsonNode node : definition.path("nodes")) {
            if ("manager".equals(node.path("id").asString())) {
                ((ObjectNode) node).put("label", "Line manager approval");
            }
        }
        ((ObjectNode) definition.path("decisions").get(0).path("rows").get(0).path("when")).put("value", 10000);
        assertThat(api("dana.designer").put(Map.of("definition", definition), "\"r2\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).status()).isEqualTo(200);
        assertThat(api("paul.publisher").post(Map.of("draftRevision", 3, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow).status())
                .isEqualTo(201);
        UUID r2 = releaseId(workflow, 0);

        Response first = api("vic.viewer").get(RELEASE_PACK + "?format=json", PURCHASING, workflow, r1);
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        assertThat(first.body().path("diff").path("summary").asString()).startsWith("First release");

        Response response = api("vic.viewer").get(RELEASE_PACK + "?format=json", PURCHASING, workflow, r2);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Disposition")).contains("-r2-evidence-pack.json");
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        JsonNode pack = response.body();
        String semantic = jdbc.sql("SELECT semantic_hash FROM workflow_release WHERE id = :id").param("id", r2)
                .query(String.class).single().trim();
        assertThat(pack.path("release").path("releaseNumber").asInt()).isEqualTo(2);
        assertThat(pack.path("release").path("semanticHash").asString()).isEqualTo(semantic);
        assertThat(pack.path("release").path("active").asBoolean()).isTrue();
        assertThat(pack.path("artifacts").findValuesAsString("name")).contains("process.bpmn20.xml", "source-map.json");
        assertThat(pack.path("approvals").findValuesAsString("role")).containsExactly("Author", "Reviewer", "Publisher");
        assertThat(pack.path("approvals").get(0).path("subject").path("id").asString())
                .isEqualTo(subject("dana.designer").toString());
        assertThat(pack.path("approvals").get(2).path("subject").path("id").asString())
                .isEqualTo(subject("paul.publisher").toString());
        assertThat(pack.path("controls").path("independentReview").asBoolean()).isTrue();
        assertThat(pack.path("controls").path("warningsAcknowledged").isBoolean()).isTrue();
        assertThat(pack.path("tests").path("status").asString()).isIn("notConfigured", "passed");
        assertThat(pack.path("diff").path("previousReleaseNumber").asInt()).isEqualTo(1);
        assertThat(pack.path("diff").path("sameBehavior").asBoolean()).isFalse();
        assertThat(pack.path("diff").path("changes").findValuesAsString("title")).anySatisfy(t -> assertThat(t)
                .contains("Line manager approval").contains("renamed"));
        assertThat(pack.path("diff").path("changes").findValuesAsString("area")).contains("rule");
        assertThat(pack.path("history").findValuesAsString("action")).contains("workflow.published");
        assertThat(pack.path("integrity").path("digest").asString()).hasSize(64);
        assertThat(audits("release.evidence-pack-exported", r2.toString())).isEqualTo(1);

        Response pdf = api("vic.viewer").get(RELEASE_PACK, PURCHASING, workflow, r2);
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.header("Content-Type")).isEqualTo("application/pdf");
        try (PDDocument loaded = Loader.loadPDF(pdf.bytes())) {
            String text = new PDFTextStripper().getText(loaded);
            assertThat(text).contains("Release evidence pack", "Approvals and separation of duties", "Publication tests",
                    "Changes from the previous release", "Line manager approval", semantic.substring(0, 16));
        }
        Response docx = api("vic.viewer").get(RELEASE_PACK + "?format=docx", PURCHASING, workflow, r2);
        assertThat(docx.status()).isEqualTo(200);
        assertThat(documentXml(docx.bytes())).contains("Release evidence pack", "Line manager approval", semantic);

        assertThat(api("vic.viewer").get(RELEASE_PACK + "?format=xlsx", PURCHASING, workflow, r2).code())
                .isEqualTo("VALIDATION_FAILED");
        // Another workspace's caller cannot even see the release; an unknown release is 404.
        assertThat(api("vic.viewer").get(RELEASE_PACK, SANDBOX, workflow, r2).status()).isEqualTo(404);
        assertThat(api("vic.viewer").get(RELEASE_PACK, PURCHASING, workflow, UUID.randomUUID()).status()).isEqualTo(404);
        // A requester without workflow.read gets no pack.
        assertThat(api("rhea.requester").get(RELEASE_PACK, PURCHASING, workflow, r2).status()).isIn(403, 404);
        // audit.read adds release packs only for workflows the caller may start: an approver who can neither read
        // nor start workflows still gets 404, while a requester who can start this one gets the pack.
        Response financeGrant = grantAuditRead("fin.finance");
        Response requesterGrant = grantAuditRead("rhea.requester");
        try {
            assertThat(api("fin.finance").get(RELEASE_PACK, PURCHASING, workflow, r2).status()).isEqualTo(404);
            Response auditor = api("rhea.requester").get(RELEASE_PACK + "?format=json", PURCHASING, workflow, r2);
            assertThat(auditor.status()).as(auditor.toString()).isEqualTo(200);
        } finally {
            revoke(financeGrant);
            revoke(requesterGrant);
        }
    }

    private Response grantAuditRead(String user) throws Exception {
        Response granted = api("wade.admin").post(Map.of("permission", "audit.read", "principal",
                Map.of("type", "subject", "value", subject(user).toString())),
                "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(granted.status()).as(granted.toString()).isEqualTo(201);
        return granted;
    }

    private void revoke(Response granted) throws Exception {
        assertThat(api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING,
                granted.body().path("id").asString()).status()).isEqualTo(204);
    }

    @Test
    void runPackShowsStepsDecisionsSlaCorrectionsAndEffects() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        JsonNode task = openTask(PURCHASING, run, "manager");
        assertThat(task).isNotNull();
        String taskId = task.path("id").asString();
        // A guarded correction: suspend and resume through the operator API.
        Response suspended = api("olga.operator").post(Map.of("reason", "Checking the supplier",
                "expectedVersion", run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, run);
        assertThat(suspended.status()).as(suspended.toString()).isEqualTo(200);
        Response resumed = api("olga.operator").post(Map.of("reason", "Supplier confirmed",
                "expectedVersion", run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/resume", PURCHASING, run);
        assertThat(resumed.status()).as(resumed.toString()).isEqualTo(200);
        // An SLA breach recorded by the watchdog.
        jdbc.sql("""
                INSERT INTO task_sla_breach (workspace_id, task_id, run_id, due_at, detected_at)
                VALUES (:ws, :task, :run, now() - interval '2 hours', now() - interval '1 hour')
                """).param("ws", PURCHASING).param("task", taskId).param("run", run).update();
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");

        Response response = api("olga.operator").get(RUN_PACK + "?format=json", PURCHASING, run);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        JsonNode pack = response.body();
        UUID releaseId = UUID.fromString(run(PURCHASING, run).path("release").path("id").asString());
        String semantic = jdbc.sql("SELECT semantic_hash FROM workflow_release WHERE id = :id").param("id", releaseId)
                .query(String.class).single().trim();
        assertThat(pack.path("release").path("semanticHash").asString()).isEqualTo(semantic);
        assertThat(pack.path("run").path("startedBy").path("id").asString()).isEqualTo(subject("rhea.requester").toString());
        JsonNode step = pack.path("steps").get(0);
        assertThat(step.path("nodeId").asString()).isEqualTo("manager");
        assertThat(step.path("decision").asString()).isEqualTo("approve");
        assertThat(step.path("decidedBy").path("id").asString()).isEqualTo(subject("mani.manager").toString());
        assertThat(step.path("assignee").path("displayName").asString()).isNotBlank();
        assertThat(step.path("slaBreached").asBoolean()).isTrue();
        assertThat(pack.path("slaBreaches").size()).isEqualTo(1);
        assertThat(pack.path("corrections").findValuesAsString("action")).containsExactly("run.suspended", "run.resumed");
        assertThat(pack.path("corrections").get(0).path("actor").path("id").asString())
                .isEqualTo(subject("olga.operator").toString());
        long effects = jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :run").param("run", run)
                .query(Long.class).single();
        assertThat(pack.path("effects").size()).isEqualTo((int) effects);
        assertThat(pack.path("timeline").findValuesAsString("type")).contains("RUN_STARTED", "TASK_DECIDED");
        assertThat(pack.path("summary").path("decisions").asInt()).isEqualTo(1);
        String raw = new String(response.bytes(), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("Replacement laptop", "Device failed", "Checking the supplier");
        assertThat(audits("run.evidence-pack-exported", run.toString())).isEqualTo(1);

        Response pdf = api("olga.operator").get(RUN_PACK, PURCHASING, run);
        assertThat(pdf.header("Content-Type")).isEqualTo("application/pdf");
        try (PDDocument loaded = Loader.loadPDF(pdf.bytes())) {
            assertThat(new PDFTextStripper().getText(loaded)).contains("Run evidence pack", "Human steps and decisions",
                    "SLA breaches", "Guarded corrections", "run.suspended", "Integration effects", "Timeline");
        }
        assertThat(documentXml(api("olga.operator").get(RUN_PACK + "?format=docx", PURCHASING, run).bytes()))
                .contains("Run evidence pack", "run.resumed");

        // The initiator can see the run but lacks run.read: refused and audited. Other workspaces: 404.
        long deniedBefore = jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'auth.denied' AND outcome = 'denied'")
                .query(Long.class).single();
        Response denied = api("rhea.requester").get(RUN_PACK, PURCHASING, run);
        assertThat(denied.status()).as(denied.toString()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'auth.denied' AND outcome = 'denied'")
                .query(Long.class).single()).isGreaterThan(deniedBefore);
        assertThat(api("olga.operator").get(RUN_PACK, SANDBOX, run).status()).isEqualTo(404);
        // A designer cannot see runs at all; audit.read does not widen that (it covers requests they can access).
        assertThat(api("dana.designer").get(RUN_PACK, PURCHASING, run).status()).isEqualTo(404);
        Response granted = api("wade.admin").post(Map.of("permission", "audit.read", "principal",
                Map.of("type", "subject", "value", subject("rhea.requester").toString())),
                "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(granted.status()).as(granted.toString()).isEqualTo(201);
        try {
            Response auditor = api("rhea.requester").get(RUN_PACK + "?format=json", PURCHASING, run);
            assertThat(auditor.status()).as(auditor.toString()).isEqualTo(200);
            assertThat(auditor.body().path("generatedBy").path("id").asString())
                    .isEqualTo(subject("rhea.requester").toString());
        } finally {
            assertThat(api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING,
                    granted.body().path("id").asString()).status()).isEqualTo(204);
        }
    }

    private static String documentXml(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if ("word/document.xml".equals(e.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("word/document.xml missing");
    }
}
