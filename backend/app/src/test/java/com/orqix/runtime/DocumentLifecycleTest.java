package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.AttachmentLifecycle;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * DOC08 (docs/build/S52): the document template registry with immutable versions, attachment quarantine and release,
 * the OCR provider contract with its local stub, and per-workspace retention, through the public API on PostgreSQL.
 */
class DocumentLifecycleTest extends RuntimeTestBase {

    private static final String TEMPLATES = "/workspaces/{ws}/document-templates";
    private static final String ATTACHMENTS = "/workspaces/{ws}/runs/{run}/attachments";

    @Autowired
    private AttachmentLifecycle lifecycle;

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private Response createTemplate(String user, String name, String body) throws Exception {
        return api(user).post(Map.of("name", name, "format", "pdf", "body", body, "changeNote", "First draft"),
                TEMPLATES, PURCHASING);
    }

    private UUID startRun() throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, publishPurchase(PURCHASING, null), BigDecimal.TEN)
                .path("id").asString());
    }

    private String addFile(UUID run, String name, String type, byte[] content) throws Exception {
        Response created = api("rhea.requester").upload(UUID.randomUUID().toString(), name, type, content, Map.of(),
                ATTACHMENTS, PURCHASING, run);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body().path("id").asString();
    }

    private Response download(String user, UUID run, String id) throws Exception {
        return api(user).get(ATTACHMENTS + "/{id}/content", PURCHASING, run, id);
    }

    private Response quarantine(String user, UUID run, String id, String reason) throws Exception {
        return api(user).post(Map.of("reason", reason), ATTACHMENTS + "/{id}/quarantine", PURCHASING, run, id);
    }

    private JsonNode file(UUID run, String id) throws Exception {
        for (JsonNode item : api("olga.operator").get(ATTACHMENTS, PURCHASING, run).body().path("items")) {
            if (item.path("id").asString().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("file not listed");
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- templates

    @Test
    void templateVersionsAreImmutableOrderedAndGuardedByTheLatestVersionSeen() throws Exception {
        String name = unique("Offer letter");
        Response created = createTemplate("dana.designer", name, "Dear {{name}}, your total is {{amount}}. {{name}}");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(created.body().path("latestVersion").asInt()).isEqualTo(1);
        assertThat(created.body().path("versions").get(0).path("placeholders").toString())
                .isEqualTo("[\"name\",\"amount\"]");

        Response second = api("dana.designer").post(Map.of("expectedVersion", 1, "format", "docx",
                "body", "Hello {{name}}", "changeNote", "Shorter"), TEMPLATES + "/{id}/versions", PURCHASING, id);
        assertThat(second.status()).as(second.toString()).isEqualTo(201);
        assertThat(second.body().path("latestVersion").asInt()).isEqualTo(2);
        JsonNode versions = second.body().path("versions");
        assertThat(versions.get(0).path("version").asInt()).isEqualTo(2);
        assertThat(versions.get(0).path("format").asString()).isEqualTo("docx");
        // Version 1 is untouched by version 2.
        assertThat(versions.get(1).path("body").asString()).startsWith("Dear {{name}}");
        assertThat(versions.get(1).path("format").asString()).isEqualTo("pdf");

        // A writer who saw only version 1 is refused; so is an identical version.
        Response stale = api("dana.designer").post(Map.of("expectedVersion", 1, "format", "pdf", "body", "Other {{x}}"),
                TEMPLATES + "/{id}/versions", PURCHASING, id);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("WORKFLOW_VERSION_CONFLICT");
        Response same = api("dana.designer").post(Map.of("expectedVersion", 2, "format", "docx", "body", "Hello {{name}}"),
                TEMPLATES + "/{id}/versions", PURCHASING, id);
        assertThat(same.status()).isEqualTo(422);

        // Names are unique ignoring case; unsafe syntax and oversized text are refused with nothing written.
        assertThat(createTemplate("dana.designer", name.toUpperCase(), "x").status()).isEqualTo(409);
        assertThat(createTemplate("dana.designer", unique("Bad"), "Hello {{name").status()).isEqualTo(422);
        assertThat(createTemplate("dana.designer", unique("Bad"), "Hello {{a.b-c}}").status()).isEqualTo(422);
        assertThat(createTemplate("dana.designer", unique("Big"), "x".repeat(16001)).status()).isEqualTo(400);

        // Archive hides it from the default list, keeps history, and blocks new versions until restored.
        assertThat(api("dana.designer").put(Map.of("archived", true), null, TEMPLATES + "/{id}/archived", PURCHASING, id)
                .body().path("archived").asBoolean()).isTrue();
        assertThat(api("dana.designer").get(TEMPLATES, PURCHASING).body().path("items").toString()).doesNotContain(id);
        assertThat(api("dana.designer").get(TEMPLATES + "?includeArchived=true", PURCHASING).body().path("items")
                .toString()).contains(id);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 2, "format", "pdf", "body", "New {{x}}"),
                TEMPLATES + "/{id}/versions", PURCHASING, id).status()).isEqualTo(422);
        api("dana.designer").put(Map.of("archived", false), null, TEMPLATES + "/{id}/archived", PURCHASING, id);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 2, "format", "pdf", "body", "New {{x}}"),
                TEMPLATES + "/{id}/versions", PURCHASING, id).status()).isEqualTo(201);

        // Audit records carry counts only, never template text or names.
        String metadata = jdbc.sql("SELECT string_agg(metadata::text, ' ') FROM audit_event WHERE resource_id = :id")
                .param("id", id).query(String.class).single();
        assertThat(metadata).doesNotContain("Hello").doesNotContain("Dear").doesNotContain(name);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action LIKE 'document.template.%'")
                .param("id", id).query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void templatePermissionsFollowWorkflowReadAndEditAndStayInsideTheWorkspace() throws Exception {
        String id = createTemplate("dana.designer", unique("Letter"), "Hello {{name}}").body().path("id").asString();
        // Readers (workflow.read) see it; they cannot change it.
        assertThat(api("vic.viewer").get(TEMPLATES + "/{id}", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api("vic.viewer").get(TEMPLATES, PURCHASING).status()).isEqualTo(200);
        assertThat(createTemplate("vic.viewer", unique("No"), "x").status()).isEqualTo(403);
        assertThat(api("vic.viewer").post(Map.of("expectedVersion", 1, "format", "pdf", "body", "y {{a}}"),
                TEMPLATES + "/{id}/versions", PURCHASING, id).status()).isEqualTo(403);
        assertThat(api("vic.viewer").put(Map.of("archived", true), null, TEMPLATES + "/{id}/archived", PURCHASING, id)
                .status()).isEqualTo(403);
        // Requesters and approvers have no workflow.read; outsiders and other workspaces cannot even see it exists.
        assertThat(api("rhea.requester").get(TEMPLATES, PURCHASING).status()).isEqualTo(403);
        assertThat(api("fred.facilities").get(TEMPLATES + "/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(api("otto.outsider").get(TEMPLATES, PURCHASING).status()).isEqualTo(404);
        assertThat(api("dana.designer").get(TEMPLATES + "/{id}", PURCHASING, UUID.randomUUID()).status()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- quarantine

    @Test
    void quarantineBlocksDownloadKeepsBytesAndOnlyOperatorsReleaseIt() throws Exception {
        UUID run = startRun();
        String id = addFile(run, "note.txt", "text/plain", text("quarantine me"));
        assertThat(download("rhea.requester", run, id).status()).isEqualTo(200);

        // The initiator and designers cannot quarantine; the operator can, and the reason is required.
        assertThat(quarantine("rhea.requester", run, id, "I want this blocked").status()).isEqualTo(403);
        assertThat(quarantine("dana.designer", run, id, "Designers cannot").status()).isIn(403, 404);
        assertThat(quarantine("olga.operator", run, id, "x").status()).isIn(400, 422);
        Response done = quarantine("olga.operator", run, id, "Suspected malware sample");
        assertThat(done.status()).as(done.toString()).isEqualTo(204);
        assertThat(quarantine("olga.operator", run, id, "Suspected malware sample").status()).isEqualTo(204);

        JsonNode listed = file(run, id);
        assertThat(listed.path("downloadable").asBoolean()).isFalse();
        assertThat(listed.path("quarantineReason").asString()).isEqualTo("Suspected malware sample");
        assertThat(listed.path("quarantinedAt").isNull()).isFalse();
        Response blocked = download("rhea.requester", run, id);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("ATTACHMENT_BLOCKED");
        // The bytes are kept for review; the initiator cannot destroy them by removing the file.
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment_content WHERE attachment_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(1);
        assertThat(api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status()).isEqualTo(403);
        // Text extraction is refused too.
        assertThat(api("olga.operator").post(null, ATTACHMENTS + "/{id}/ocr", PURCHASING, run, id).status()).isEqualTo(409);

        // Release is operator-only, restores download, and releasing a free file is a no-op.
        assertThat(api("rhea.requester").post(null, ATTACHMENTS + "/{id}/release", PURCHASING, run, id).status())
                .isEqualTo(403);
        assertThat(api("olga.operator").post(null, ATTACHMENTS + "/{id}/release", PURCHASING, run, id).status())
                .isEqualTo(204);
        assertThat(api("olga.operator").post(null, ATTACHMENTS + "/{id}/release", PURCHASING, run, id).status())
                .isEqualTo(204);
        assertThat(download("rhea.requester", run, id).status()).isEqualTo(200);
        assertThat(file(run, id).path("downloadable").asBoolean()).isTrue();

        // Timeline and audit record the change without the reason text.
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type IN ('ATTACHMENT_QUARANTINED', 'ATTACHMENT_RELEASED')")
                .param("r", run).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT string_agg(metadata::text, ' ') FROM audit_event WHERE resource_id = :id AND action LIKE 'attachment.%'")
                .param("id", id).query(String.class).single()).doesNotContain("malware");
    }

    @Test
    void foreignWorkspacePrincipalsCannotReachLifecycleOperations() throws Exception {
        UUID run = startRun();
        String id = addFile(run, "note.txt", "text/plain", text("hello"));
        for (String user : new String[] {"fred.facilities", "otto.outsider"}) {
            assertThat(quarantine(user, run, id, "Foreign attempt").status()).as(user).isEqualTo(404);
            assertThat(api(user).post(null, ATTACHMENTS + "/{id}/release", PURCHASING, run, id).status()).isEqualTo(404);
            assertThat(api(user).post(null, ATTACHMENTS + "/{id}/ocr", PURCHASING, run, id).status()).isEqualTo(404);
            assertThat(api(user).get("/workspaces/{ws}/attachment-retention", PURCHASING).status()).isEqualTo(404);
        }
        assertThat(file(run, id).path("quarantinedAt").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- OCR contract

    @Test
    void ocrStubExtractsTextFilesReportsOtherTypesUnsupportedAndKeepsDataAuthorization() throws Exception {
        UUID run = startRun();
        String txt = addFile(run, "invoice.txt", "text/plain", text("Invoice 42\nTotal 100"));
        String pdf = addFile(run, "scan.pdf", "application/pdf",
                text("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"));

        // No result exists until one is requested.
        assertThat(api("rhea.requester").get(ATTACHMENTS + "/{id}/ocr", PURCHASING, run, txt).status()).isEqualTo(404);
        // Starting extraction needs run.operate; the initiator may read the result afterwards but not start it.
        assertThat(api("rhea.requester").post(null, ATTACHMENTS + "/{id}/ocr", PURCHASING, run, txt).status()).isEqualTo(403);

        Response extracted = api("olga.operator").post(null, ATTACHMENTS + "/{id}/ocr", PURCHASING, run, txt);
        assertThat(extracted.status()).as(extracted.toString()).isEqualTo(200);
        assertThat(extracted.body().path("status").asString()).isEqualTo("extracted");
        assertThat(extracted.body().path("provider").asString()).isEqualTo("local-stub");
        assertThat(extracted.body().path("text").asString()).isEqualTo("Invoice 42\nTotal 100");
        Response read = api("rhea.requester").get(ATTACHMENTS + "/{id}/ocr", PURCHASING, run, txt);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().path("text").asString()).contains("Invoice 42");
        // A reader without request-data access never sees the text.
        assertThat(api("vic.viewer").get(ATTACHMENTS + "/{id}/ocr", PURCHASING, run, txt).status()).isEqualTo(403);

        Response unsupported = api("olga.operator").post(null, ATTACHMENTS + "/{id}/ocr", PURCHASING, run, pdf);
        assertThat(unsupported.status()).isEqualTo(200);
        assertThat(unsupported.body().path("status").asString()).isEqualTo("unsupported");
        assertThat(unsupported.body().path("text").isNull() || unsupported.body().path("text").isMissingNode()).isTrue();

        // Quarantine deletes the stored text; removal does too.
        quarantine("olga.operator", run, txt, "Review");
        assertThat(jdbc.sql("SELECT count(*) FROM attachment_ocr_result WHERE attachment_id = :id")
                .param("id", UUID.fromString(txt)).query(Integer.class).single()).isZero();
        assertThat(api("rhea.requester").get(ATTACHMENTS + "/{id}/ocr", PURCHASING, run, txt).status()).isEqualTo(409);
    }

    // ---------------------------------------------------------------- retention

    @Test
    void retentionIsAdministratorOnlyVersionedAndPurgesOnlyOldUnquarantinedFilesOfEndedRequests() throws Exception {
        String settings = "/workspaces/{ws}/attachment-retention";
        try {
            JsonNode initial = api("wade.admin").get(settings, PURCHASING).body();
            int version = initial.path("version").asInt();
            // Only holders of workspace.manage-access, not designers or operators.
            assertThat(api("dana.designer").get(settings, PURCHASING).status()).isEqualTo(403);
            assertThat(api("olga.operator").put(Map.of("retentionDays", 30, "expectedVersion", version), null, settings,
                    PURCHASING).status()).isEqualTo(403);
            assertThat(api("wade.admin").put(Map.of("retentionDays", 0, "expectedVersion", version), null, settings,
                    PURCHASING).status()).isIn(400, 422);
            assertThat(api("wade.admin").put(Map.of("retentionDays", 4000, "expectedVersion", version), null, settings,
                    PURCHASING).status()).isIn(400, 422);

            // Files: one on a finished request, one quarantined on a finished request, one on a running request.
            UUID ended = startRun();
            String old = addFile(ended, "old.txt", "text/plain", text("old data"));
            String held = addFile(ended, "held.txt", "text/plain", text("held data"));
            assertThat(quarantine("olga.operator", ended, held, "Legal hold during review").status()).isEqualTo(204);
            approve("mani.manager", PURCHASING, ended, "manager");
            awaitLifecycle(PURCHASING, ended, "completed");
            UUID running = startRun();
            String active = addFile(running, "active.txt", "text/plain", text("active data"));
            jdbc.sql("UPDATE workflow_run SET ended_at = now() - interval '40 days' WHERE id = :id").param("id", ended).update();

            Response saved = api("wade.admin").put(Map.of("retentionDays", 30, "expectedVersion", version), null, settings,
                    PURCHASING);
            assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
            assertThat(saved.body().path("retentionDays").asInt()).isEqualTo(30);
            assertThat(saved.body().path("version").asInt()).isEqualTo(version + 1);
            assertThat(saved.body().path("eligibleNow").asInt()).isEqualTo(1);
            // A stale version is refused.
            Response stale = api("wade.admin").put(Map.of("retentionDays", 10, "expectedVersion", version), null, settings,
                    PURCHASING);
            assertThat(stale.status()).isEqualTo(409);

            assertThat(lifecycle.purgeExpired()).isEqualTo(1);
            assertThat(lifecycle.purgeExpired()).isZero();

            JsonNode purged = file(ended, old);
            assertThat(purged.path("removedAt").isNull()).isFalse();
            assertThat(purged.path("downloadable").asBoolean()).isFalse();
            assertThat(download("olga.operator", ended, old).status()).isEqualTo(404);
            assertThat(jdbc.sql("SELECT count(*) FROM run_attachment_content WHERE attachment_id = :id")
                    .param("id", UUID.fromString(old)).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT purged_at IS NOT NULL AND deleted_by IS NULL FROM run_attachment WHERE id = :id")
                    .param("id", UUID.fromString(old)).query(Boolean.class).single()).isTrue();
            // Quarantined and running-request files remain.
            assertThat(file(ended, held).path("removedAt").isNull()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM run_attachment_content WHERE attachment_id IN (:a, :b)")
                    .param("a", UUID.fromString(held)).param("b", UUID.fromString(active)).query(Integer.class).single())
                    .isEqualTo(2);
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action = 'attachment.purged' AND actor_type = 'system'")
                    .param("id", old).query(Integer.class).single()).isEqualTo(1);

            // Releasing makes the held file eligible on the next sweep.
            api("olga.operator").post(null, ATTACHMENTS + "/{id}/release", PURCHASING, ended, held);
            assertThat(lifecycle.purgeExpired()).isEqualTo(1);
            assertThat(file(ended, held).path("removedAt").isNull()).isFalse();

            // Clearing the setting (null days) stops purging.
            Map<String, Object> clear = new java.util.HashMap<>();
            clear.put("retentionDays", null);
            clear.put("expectedVersion", version + 1);
            Response cleared = api("wade.admin").put(clear, null, settings, PURCHASING);
            assertThat(cleared.status()).as(cleared.toString()).isEqualTo(200);
            assertThat(cleared.body().path("retentionDays").isNull()).isTrue();
            assertThat(cleared.body().path("eligibleNow").asInt()).isZero();
        } finally {
            jdbc.sql("UPDATE workspace_attachment_policy SET retention_days = NULL WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        }
        assertThat(api("wade.admin").get(settings, PURCHASING).body().path("eligibleNow").asInt()).isZero();
    }
}
