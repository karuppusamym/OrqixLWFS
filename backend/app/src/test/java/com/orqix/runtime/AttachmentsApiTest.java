package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.runtime.internal.RunAttachments;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.TestUsers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * docs/build/S17 sections 1-3: file types decided by name, declared type and bytes together, limits, headers of a
 * download, the access matrix, soft delete with audit, the scan status and the request-form upload flow.
 */
class AttachmentsApiTest extends RuntimeTestBase {

    private static final String ATTACHMENTS = "/workspaces/{ws}/runs/{run}/attachments";

    @Autowired
    private RunAttachments attachments;

    /** Tests share one database: age earlier uploads out of the rate window and drop files waiting on forms. */
    @org.junit.jupiter.api.BeforeEach
    void cleanUploads() {
        jdbc.sql("DELETE FROM run_attachment_content WHERE attachment_id IN (SELECT id FROM run_attachment WHERE run_id IS NULL)").update();
        jdbc.sql("DELETE FROM run_attachment WHERE run_id IS NULL").update();
        jdbc.sql("UPDATE run_attachment SET created_at = created_at - interval '1 hour' WHERE created_at > now() - interval '5 minutes'").update();
    }

    // ---------------------------------------------------------------- helpers

    private UUID startRun(UUID workflow) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
    }

    private Response add(String user, UUID run, String name, String type, byte[] content, String description)
            throws Exception {
        return api(user).upload(UUID.randomUUID().toString(), name, type, content,
                description == null ? Map.of() : Map.of("description", description), ATTACHMENTS, PURCHASING, run);
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pdf() {
        return text("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n");
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    }

    /** A minimal OOXML package: a ZIP with [Content_Types].xml and the main part of the kind. */
    private static byte[] ooxml(String mainPart, String... extra) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : concat("[Content_Types].xml", mainPart, extra)) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(text("<x/>"));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static List<String> concat(String a, String b, String... rest) {
        List<String> all = new java.util.ArrayList<>(List.of(a, b));
        all.addAll(List.of(rest));
        return all;
    }

    private String field(JsonNode body, String name) {
        return body.path(name).asString();
    }

    // ---------------------------------------------------------------- the happy path and the download

    @Test
    void initiatorAddsListsAndDownloadsWithSafeHeaders() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        String key = UUID.randomUUID().toString();
        byte[] file = pdf();
        Response created = api("rhea.requester").upload(key, "Quote 2026.pdf", "application/pdf", file,
                Map.of("description", "Supplier quote"), ATTACHMENTS, PURCHASING, run);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode attachment = created.body();
        assertThat(field(attachment, "name")).isEqualTo("Quote 2026.pdf");
        assertThat(field(attachment, "contentType")).isEqualTo("application/pdf");
        assertThat(attachment.path("sizeBytes").asLong()).isEqualTo(file.length);
        assertThat(field(attachment, "sha256")).hasSize(64);
        assertThat(field(attachment, "scanStatus")).isEqualTo("notScanned");
        assertThat(attachment.path("description").asString()).isEqualTo("Supplier quote");
        assertThat(attachment.path("canRemove").asBoolean()).isTrue();

        // The same key and file replays; the same key with another file is a different request.
        Response replay = api("rhea.requester").upload(key, "Quote 2026.pdf", "application/pdf", file,
                Map.of("description", "Supplier quote"), ATTACHMENTS, PURCHASING, run);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(field(replay.body(), "id")).isEqualTo(field(attachment, "id"));
        Response reused = api("rhea.requester").upload(key, "Other.pdf", "application/pdf", pdf(),
                Map.of(), ATTACHMENTS, PURCHASING, run);
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        JsonNode list = api("rhea.requester").get(ATTACHMENTS, PURCHASING, run).body();
        assertThat(list.path("items")).hasSize(1);
        assertThat(list.path("limits").path("maxFileBytes").asLong()).isEqualTo(10L * 1024 * 1024);
        assertThat(list.path("limits").path("usedFiles").asInt()).isEqualTo(1);
        assertThat(list.path("canAdd").asBoolean()).isTrue();

        Response download = api("rhea.requester").get(ATTACHMENTS + "/{id}/content", PURCHASING, run,
                field(attachment, "id"));
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.bytes()).isEqualTo(file);
        assertThat(download.header("Content-Type")).startsWith("application/octet-stream");
        assertThat(download.header("Content-Disposition")).startsWith("attachment;").contains("Quote 2026.pdf");
        assertThat(download.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download.header("Content-Security-Policy")).contains("sandbox");

        // The timeline records the file for readers of the data.
        JsonNode events = api("rhea.requester").get("/workspaces/{ws}/runs/{run}/events", PURCHASING, run).body();
        JsonNode added = null;
        for (JsonNode event : events.path("items")) {
            if ("ATTACHMENT_ADDED".equals(event.path("type").asString())) {
                added = event;
            }
        }
        assertThat(added).isNotNull();
        assertThat(added.path("data").path("name").asString()).isEqualTo("Quote 2026.pdf");
        // A reader without the request data sees that a file was added, not what it is called.
        JsonNode viewerEvents = api("vic.viewer").get("/workspaces/{ws}/runs/{run}/events", PURCHASING, run).body();
        for (JsonNode event : viewerEvents.path("items")) {
            if ("ATTACHMENT_ADDED".equals(event.path("type").asString())) {
                assertThat(event.path("data").has("name")).isFalse();
                assertThat(event.path("data").path("detailsRedacted").asBoolean()).isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- access matrix

    @Test
    void accessFollowsRequestDataVisibility() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        JsonNode file = add("rhea.requester", run, "note.txt", "text/plain", text("hello"), null).body();
        String id = field(file, "id");
        // Outsiders and people of another workspace: 404 everywhere, nothing revealed.
        for (String user : List.of("otto.outsider", "fred.facilities")) {
            assertThat(api(user).get(ATTACHMENTS, PURCHASING, run).status()).as(user).isEqualTo(404);
            assertThat(api(user).get(ATTACHMENTS + "/{id}/content", PURCHASING, run, id).status()).isEqualTo(404);
            assertThat(add(user, run, "x.txt", "text/plain", text("x"), null).status()).isEqualTo(404);
            assertThat(api(user).delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status()).isEqualTo(404);
        }
        // run.read without the request data: the run is visible, its files are not.
        assertThat(api("vic.viewer").get(ATTACHMENTS, PURCHASING, run).status()).isEqualTo(403);
        assertThat(api("vic.viewer").get(ATTACHMENTS + "/{id}/content", PURCHASING, run, id).status()).isEqualTo(403);
        assertThat(add("vic.viewer", run, "x.txt", "text/plain", text("x"), null).status()).isEqualTo(403);
        // A task participant (eligible approver) sees and downloads, and may add.
        assertThat(api("mani.manager").get(ATTACHMENTS, PURCHASING, run).status()).isEqualTo(200);
        assertThat(api("mani.manager").get(ATTACHMENTS + "/{id}/content", PURCHASING, run, id).bytes())
                .isEqualTo(text("hello"));
        Response byManager = add("mani.manager", run, "review.txt", "text/plain", text("checked"), "Checked");
        assertThat(byManager.status()).as(byManager.toString()).isEqualTo(201);
        assertThat(byManager.body().path("canRemove").asBoolean()).isFalse();
        // ... but cannot remove someone else's file; the operator can.
        assertThat(api("mani.manager").delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status()).isEqualTo(403);
        assertThat(api("olga.operator").get(ATTACHMENTS, PURCHASING, run).status()).isEqualTo(200);
        assertThat(api("olga.operator").delete(ATTACHMENTS + "/{id}", PURCHASING, run,
                field(byManager.body(), "id")).status()).isEqualTo(204);
        // Another workspace's path with this run's IDs: 404 (the workspace comes from the stored run).
        assertThat(api("rhea.requester").get(ATTACHMENTS, SANDBOX, run).status()).isEqualTo(404);
    }

    @Test
    void csrfAndIdempotencyKeyAreRequired() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        Response noKey = api("rhea.requester").upload(null, "a.txt", "text/plain", text("a"), Map.of(), ATTACHMENTS,
                PURCHASING, run);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        var noCsrf = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1" + ATTACHMENTS, PURCHASING, run)
                .file(new org.springframework.mock.web.MockMultipartFile("file", "a.txt", "text/plain", text("a")))
                .header("Idempotency-Key", UUID.randomUUID().toString()).with(TestUsers.as("rhea.requester")))
                .andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
        assertThat(attachmentRows(run)).isZero();
    }

    // ---------------------------------------------------------------- types

    @Test
    void typeIsDecidedByNameDeclaredTypeAndBytesTogether() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        // Accepted.
        assertThat(add("rhea.requester", run, "a.pdf", "application/pdf", pdf(), null).status()).isEqualTo(201);
        assertThat(add("rhea.requester", run, "a.png", "image/png", png(), null).status()).isEqualTo(201);
        assertThat(add("rhea.requester", run, "a.jpg", "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'}, null)
                .status()).isEqualTo(201);
        assertThat(add("rhea.requester", run, "a.txt", "text/plain", text("Plain text\nwith lines\n"), null).status())
                .isEqualTo(201);
        // Windows browsers send the Excel type for CSV.
        assertThat(add("rhea.requester", run, "a.csv", "application/vnd.ms-excel", text("a,b\n1,2\n"), null).status())
                .isEqualTo(201);
        assertThat(add("rhea.requester", run, "a.docx", "application/octet-stream", ooxml("word/document.xml"), null)
                .status()).isEqualTo(201);
        assertThat(add("rhea.requester", run, "a.xlsx", null, ooxml("xl/workbook.xml"), null).status()).isEqualTo(201);
        assertThat(add("rhea.requester", run, "a.pptx", null, ooxml("ppt/presentation.xml"), null).status())
                .isEqualTo(201);

        List<Object[]> refused = List.of(
                new Object[] {"program.exe", "application/octet-stream", new byte[] {'M', 'Z', 0, 0}},
                new Object[] {"notes.txt", "text/plain", new byte[] {'M', 'Z', 0, 0, 3, 0, 0, 0}},
                new Object[] {"page.html", "text/html", text("<html><body>x</body></html>")},
                new Object[] {"notes.txt", "text/plain", text("<!DOCTYPE html><script>alert(1)</script>")},
                new Object[] {"image.svg", "image/svg+xml", text("<svg xmlns='http://www.w3.org/2000/svg'/>")},
                new Object[] {"notes.txt", "text/plain", text("<svg xmlns='http://www.w3.org/2000/svg'/>")},
                new Object[] {"notes.txt", "text/plain", text("#!/bin/sh\nrm -rf /\n")},
                new Object[] {"archive.zip", "application/zip", ooxml("word/document.xml")},
                new Object[] {"fake.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        ooxml("some/other.xml")},
                new Object[] {"macro.docx", null, ooxml("word/document.xml", "word/vbaProject.bin")},
                new Object[] {"traversal.docx", null, ooxml("word/document.xml", "../evil.xml")},
                new Object[] {"old.doc", "application/msword", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}},
                new Object[] {"mail.msg", "application/vnd.ms-outlook", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}},
                new Object[] {"mail.eml", "message/rfc822", text("From: a@example.com\nSubject: x\n\nbody")},
                new Object[] {"a.pdf", "application/pdf", png()},
                new Object[] {"a.png", "image/png", pdf()},
                new Object[] {"a.pdf", "image/png", pdf()},
                new Object[] {"binary.txt", "text/plain", new byte[] {'a', 0, 'b'}},
                new Object[] {"latin1.txt", "text/plain", new byte[] {(byte) 0xE9, (byte) 0xE8}},
                new Object[] {"noextension", "application/octet-stream", pdf()},
                new Object[] {"empty.txt", "text/plain", new byte[0]});
        for (Object[] f : refused) {
            Response response = add("rhea.requester", run, (String) f[0], (String) f[1], (byte[]) f[2], null);
            assertThat(response.status()).as(f[0] + " " + f[1] + " -> " + response).isEqualTo(422);
            assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.body().path("fieldErrors").get(0).path("pointer").asString()).isEqualTo("/file");
        }
        assertThat(attachmentRows(run)).isEqualTo(8);
    }

    @Test
    void fileNamesAreSanitizedAndNeverUsedAsPaths() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        Response created = add("rhea.requester", run, "..\\..\\etc/pass<wd>‮gnp.txt", "text/plain", text("x"), null);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String name = field(created.body(), "name");
        assertThat(name).doesNotContain("/", "\\", "<", ">", "‮", "..");
        assertThat(name).endsWith(".txt");
        Response download = api("rhea.requester").get(ATTACHMENTS + "/{id}/content", PURCHASING, run,
                field(created.body(), "id"));
        assertThat(download.header("Content-Disposition")).doesNotContain("/", "\\", "..");
        // Control characters and over-long names are cut.
        Response longName = add("rhea.requester", run, "a".repeat(300) + ".txt", "text/plain", text("x"), null);
        assertThat(field(longName.body(), "name")).hasSizeLessThanOrEqualTo(120).endsWith(".txt");
        Response description = add("rhea.requester", run, "d.txt", "text/plain", text("x"), "x".repeat(201));
        assertThat(description.status()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- limits

    @Test
    void fileSizeAndQuotaLimits() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        Response tooLarge = add("rhea.requester", run, "big.txt", "text/plain", big, null);
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        byte[] exactly = new byte[10 * 1024 * 1024];
        java.util.Arrays.fill(exactly, (byte) 'a');
        assertThat(add("rhea.requester", run, "max.txt", "text/plain", exactly, null).status()).isEqualTo(201);

        // 25 files per request (removed files do not count): seed 24 more, the 26th fails.
        UUID subject = jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :r").param("r", run)
                .query(UUID.class).single();
        for (int i = 0; i < 23; i++) {
            seed(run, subject, "seed" + i + ".txt", 10, "notScanned", false);
        }
        Response twentyFifth = add("rhea.requester", run, "last.txt", "text/plain", text("x"), null);
        assertThat(twentyFifth.status()).as(twentyFifth.toString()).isEqualTo(201);
        Response twentySixth = add("rhea.requester", run, "over.txt", "text/plain", text("x"), null);
        assertThat(twentySixth.status()).isEqualTo(409);
        assertThat(twentySixth.code()).isEqualTo("ATTACHMENT_LIMIT_REACHED");
        // Removing a file frees a slot.
        String first = field(api("rhea.requester").get(ATTACHMENTS, PURCHASING, run).body().path("items").get(0), "id");
        assertThat(api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, first).status()).isEqualTo(204);
        assertThat(add("rhea.requester", run, "again.txt", "text/plain", text("x"), null).status()).isEqualTo(201);
    }

    @Test
    void totalSizeLimitPerRequest() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        UUID subject = jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :r").param("r", run)
                .query(UUID.class).single();
        for (int i = 0; i < 4; i++) {
            seed(run, subject, "big" + i + ".txt", 10L * 1024 * 1024, "notScanned", false);
        }
        seed(run, subject, "nearly.txt", 9L * 1024 * 1024, "notScanned", false); // 49 MiB used
        Response over = add("rhea.requester", run, "two.txt", "text/plain", new byte[2 * 1024 * 1024 + 1], null);
        assertThat(over.status()).isIn(409, 422);
        byte[] text = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(text, (byte) 'a');
        Response tooMuch = add("rhea.requester", run, "two.txt", "text/plain", text, null);
        assertThat(tooMuch.status()).isEqualTo(409);
        assertThat(tooMuch.code()).isEqualTo("ATTACHMENT_LIMIT_REACHED");
        assertThat(add("rhea.requester", run, "one.txt", "text/plain", text("a".repeat(1000)), null).status())
                .isEqualTo(201);
    }

    @Test
    void uploadsAreRateLimited() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        UUID subject = jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :r").param("r", run)
                .query(UUID.class).single();
        for (int i = 0; i < 30; i++) {
            seedAt(run, subject, "burst" + i + ".txt", 1, "notScanned", false, OffsetDateTime.now());
        }
        Response limited = add("rhea.requester", run, "x.txt", "text/plain", text("x"), null);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        // Other people are not affected (the request is full, which is a different answer).
        assertThat(add("olga.operator", run, "x.txt", "text/plain", text("x"), null).status()).isNotEqualTo(429);
    }

    // ---------------------------------------------------------------- scan status

    @Test
    void filesBlockedByTheScanAreNeverServed() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        UUID subject = jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :r").param("r", run)
                .query(UUID.class).single();
        UUID infected = seed(run, subject, "virus.txt", 5, "infected", false);
        UUID error = seed(run, subject, "unknown.txt", 5, "error", false);
        UUID clean = seed(run, subject, "ok.txt", 5, "clean", false);
        for (UUID blocked : List.of(infected, error)) {
            Response response = api("rhea.requester").get(ATTACHMENTS + "/{id}/content", PURCHASING, run, blocked);
            assertThat(response.status()).as(blocked.toString()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("ATTACHMENT_BLOCKED");
        }
        assertThat(api("rhea.requester").get(ATTACHMENTS + "/{id}/content", PURCHASING, run, clean).status())
                .isEqualTo(200);
        JsonNode items = api("rhea.requester").get(ATTACHMENTS, PURCHASING, run).body().path("items");
        for (JsonNode item : items) {
            boolean blocked = Set(infected, error).contains(UUID.fromString(item.path("id").asString()));
            assertThat(item.path("downloadable").asBoolean()).isEqualTo(!blocked);
        }
        // A blocked file can still be removed.
        assertThat(api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, infected).status())
                .isEqualTo(204);
    }

    private static java.util.Set<UUID> Set(UUID... ids) {
        return java.util.Set.of(ids);
    }

    // ---------------------------------------------------------------- soft delete and audit

    @Test
    void removalIsSoftAuditedAndKeepsMetadataOnly() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        JsonNode file = add("rhea.requester", run, "Contract draft.txt", "text/plain", text("secret terms"), null).body();
        String id = field(file, "id");
        assertThat(api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status()).isEqualTo(204);
        assertThat(api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status())
                .as("removing twice").isEqualTo(204);
        assertThat(api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, UUID.randomUUID()).status())
                .isEqualTo(404);
        // Metadata stays, the bytes are gone.
        JsonNode items = api("rhea.requester").get(ATTACHMENTS, PURCHASING, run).body().path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("removedAt").isNull()).isFalse();
        assertThat(items.get(0).path("removedBy").path("displayName").asString()).isNotBlank();
        assertThat(items.get(0).path("downloadable").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment_content WHERE attachment_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single()).isZero();
        assertThat(api("rhea.requester").get(ATTACHMENTS + "/{id}/content", PURCHASING, run, id).status())
                .isEqualTo(404);
        // Audit: metadata only, never the file name or the description.
        List<String> metadata = jdbc.sql("""
                SELECT action || ' ' || metadata::text FROM audit_event
                WHERE workspace_id = :ws AND resource_id = :id AND action LIKE 'attachment.%' ORDER BY occurred_at
                """).param("ws", PURCHASING).param("id", id).query(String.class).list();
        assertThat(metadata).hasSize(2);
        assertThat(metadata.get(0)).startsWith("attachment.added").contains("sizeBytes", "contentType", "scanStatus");
        assertThat(metadata.get(1)).startsWith("attachment.removed").contains(id);
        assertThat(String.join(" ", metadata)).doesNotContain("Contract").doesNotContain("secret");
        // The timeline records both.
        assertThat(jdbc.sql("SELECT string_agg(type, ',' ORDER BY seq) FROM run_event WHERE run_id = :r AND type LIKE 'ATTACHMENT%'")
                .param("r", run).query(String.class).single()).isEqualTo("ATTACHMENT_ADDED,ATTACHMENT_REMOVED");
        // Nobody can add or remove once the request ended, except operators removing.
        cancel(run);
        Response late = add("rhea.requester", run, "late.txt", "text/plain", text("x"), null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("RUN_NOT_ACTIVE");
    }

    @Test
    void initiatorCannotRemoveAfterTheRequestEnded() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        String id = field(add("rhea.requester", run, "a.txt", "text/plain", text("x"), null).body(), "id");
        cancel(run);
        assertThat(api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status()).isEqualTo(403);
        assertThat(api("olga.operator").delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status()).isEqualTo(204);
    }

    @Test
    void parallelRemovalRecordsExactlyOneAuditAndTimelineEvent() throws Exception {
        UUID run = startRun(publishPurchase(PURCHASING, null));
        String id = field(add("rhea.requester", run, "parallel.txt", "text/plain", text("hello"), null).body(), "id");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 3; i++) {
                calls.add(executor.submit(() -> {
                    start.await();
                    return api("rhea.requester").delete(ATTACHMENTS + "/{id}", PURCHASING, run, id).status();
                }));
            }
            start.countDown();
            for (var call : calls) assertThat(call.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action = 'attachment.removed'")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :id AND type = 'ATTACHMENT_REMOVED'")
                .param("id", run).query(Integer.class).single()).isEqualTo(1);
    }

    private void cancel(UUID run) throws Exception {
        JsonNode detail = run(PURCHASING, run);
        Response cancelled = api("olga.operator").post(Map.of("reason", "test", "expectedVersion",
                detail.path("version").asInt()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel",
                PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- the request form

    private UUID workflowWithFileField(List<String> accept, Long maxBytes) throws Exception {
        return publishPurchase(PURCHASING, definition -> {
            ObjectNode field = ((ObjectNode) definition.path("inputSchema").path("properties")).putObject("quote");
            field.put("type", "string");
            field.put("title", "Supplier quote");
            field.put("format", "attachment");
            if (accept != null) {
                var types = field.putArray("acceptTypes");
                accept.forEach(types::add);
            }
            if (maxBytes != null) {
                field.put("maxFileBytes", maxBytes);
            }
        });
    }

    private Response startWith(String user, UUID workflow, Object quote) throws Exception {
        Map<String, Object> input = input(BigDecimal.TEN);
        if (quote != null) {
            input.put("quote", quote);
        }
        return api(user).post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
    }

    private Response stage(String user, String name, String type, byte[] content) throws Exception {
        return api(user).upload(UUID.randomUUID().toString(), name, type, content, Map.of(),
                "/workspaces/{ws}/attachment-uploads", PURCHASING);
    }

    @Test
    void aggregateFormFilesCannotBypassTheRunByteQuota() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, definition -> {
            ObjectNode properties = (ObjectNode) definition.path("inputSchema").path("properties");
            for (int i = 0; i < 6; i++) {
                properties.putObject("file" + i).put("type", "string").put("title", "File " + i)
                        .put("format", "attachment");
            }
        });
        Map<String, Object> input = input(BigDecimal.TEN);
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.fromString(stage("rhea.requester", "file" + i + ".txt", "text/plain", text("hello")).body().path("id").asString());
            // Quota ledger boundary: individual-upload streaming size validation is tested separately above.
            jdbc.sql("UPDATE run_attachment SET size_bytes = 10485760 WHERE id = :id").param("id", id).update();
            input.put("file" + i, id.toString());
        }
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(422);
        assertThat(started.body().path("fieldErrors").toString()).contains("attachmentLimit", "/input/file5");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :id").param("id", workflow)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment WHERE run_id IS NULL AND deleted_at IS NULL")
                .query(Long.class).single()).isEqualTo(6);
    }

    @Test
    void parallelStagingCannotExceedTheSubjectQuota() throws Exception {
        for (int i = 0; i < 9; i++) assertThat(stage("rhea.requester", "prior" + i + ".txt", "text/plain", text("hello")).status()).isEqualTo(201);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 3; i++) {
                int number = i;
                calls.add(executor.submit(() -> { start.await(); return stage("rhea.requester", "parallel" + number + ".txt", "text/plain", text("hello")).status(); }));
            }
            start.countDown();
            var results = new java.util.ArrayList<Integer>();
            for (var call : calls) results.add(call.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(201, 409, 409);
        }
    }

    @Test
    void discardingStagedFilesDoesNotResetUploadAdmissionHistory() throws Exception {
        String id = stage("rhea.requester", "discard.txt", "text/plain", text("hello")).body().path("id").asString();
        assertThat(api("rhea.requester").delete("/workspaces/{ws}/attachment-uploads/{id}", PURCHASING, id).status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM run_attachment WHERE id = :id")
                .param("id", UUID.fromString(id)).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment_content WHERE attachment_id = :id")
                .param("id", UUID.fromString(id)).query(Long.class).single()).isZero();
        assertThat(stage("rhea.requester", "replacement.txt", "text/plain", text("hello")).status()).isEqualTo(201);
    }

    @Test
    void filesChosenOnTheFormAreAttachedWhenTheRequestStarts() throws Exception {
        UUID workflow = workflowWithFileField(List.of("pdf"), null);
        Response staged = stage("rhea.requester", "quote.pdf", "application/pdf", pdf());
        assertThat(staged.status()).as(staged.toString()).isEqualTo(201);
        String uploadId = field(staged.body(), "id");
        assertThat(staged.body().path("expiresAt").asString()).isNotBlank();
        // Nobody else can use or discard it.
        assertThat(startWith("mani.manager", workflow, uploadId).status()).isEqualTo(422);
        assertThat(api("mani.manager").delete("/workspaces/{ws}/attachment-uploads/{id}", PURCHASING, uploadId).status())
                .isEqualTo(404);

        Response started = startWith("rhea.requester", workflow, uploadId);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(field(started.body(), "id"));
        JsonNode items = api("rhea.requester").get(ATTACHMENTS, PURCHASING, run).body().path("items");
        assertThat(items).hasSize(1);
        assertThat(field(items.get(0), "fieldKey")).isEqualTo("quote");
        assertThat(field(items.get(0), "id")).isEqualTo(uploadId);
        assertThat(api("rhea.requester").get(ATTACHMENTS + "/{id}/content", PURCHASING, run, uploadId).bytes())
                .isEqualTo(pdf());
        // A file is attached once.
        assertThat(startWith("rhea.requester", workflow, uploadId).status()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'ATTACHMENT_ADDED'")
                .param("r", run).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theFormFieldLimitsAreEnforcedAtStart() throws Exception {
        UUID workflow = workflowWithFileField(List.of("pdf", "png"), 1000L);
        String text = field(stage("rhea.requester", "a.txt", "text/plain", text("hello")).body(), "id");
        Response wrongType = startWith("rhea.requester", workflow, text);
        assertThat(wrongType.status()).isEqualTo(422);
        assertThat(wrongType.body().path("fieldErrors").get(0).path("pointer").asString()).isEqualTo("/input/quote");
        byte[] largePdf = new byte[2000];
        byte[] header = pdf();
        System.arraycopy(header, 0, largePdf, 0, header.length);
        String large = field(stage("rhea.requester", "big.pdf", "application/pdf", largePdf).body(), "id");
        Response tooLarge = startWith("rhea.requester", workflow, large);
        assertThat(tooLarge.status()).isEqualTo(422);
        assertThat(tooLarge.body().path("fieldErrors").get(0).path("message").asString()).contains("at most");
        // Nothing was started and the file is still waiting.
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment WHERE id = :id AND run_id IS NULL")
                .param("id", UUID.fromString(large)).query(Integer.class).single()).isEqualTo(1);
        // Not a UUID, an unknown file, and a missing required field are field errors too.
        assertThat(startWith("rhea.requester", workflow, "not-an-id").status()).isEqualTo(422);
        assertThat(startWith("rhea.requester", workflow, UUID.randomUUID().toString()).status()).isEqualTo(422);
        // Blocked files cannot be attached.
        UUID blocked = UUID.fromString(field(stage("rhea.requester", "b.pdf", "application/pdf", pdf()).body(), "id"));
        jdbc.sql("UPDATE run_attachment SET scan_status = 'infected' WHERE id = :id").param("id", blocked).update();
        assertThat(startWith("rhea.requester", workflow, blocked.toString()).status()).isEqualTo(422);
    }

    @Test
    void waitingFilesExpireAreLimitedAndCanBeDiscarded() throws Exception {
        UUID workflow = workflowWithFileField(null, null);
        String id = field(stage("rhea.requester", "a.txt", "text/plain", text("hello")).body(), "id");
        assertThat(api("rhea.requester").delete("/workspaces/{ws}/attachment-uploads/{id}", PURCHASING, id).status())
                .isEqualTo(204);
        assertThat(startWith("rhea.requester", workflow, id).status()).isEqualTo(422);

        String expiring = field(stage("rhea.requester", "b.txt", "text/plain", text("hello")).body(), "id");
        jdbc.sql("UPDATE run_attachment SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(expiring)).update();
        assertThat(startWith("rhea.requester", workflow, expiring).status()).as("expired").isEqualTo(422);
        assertThat(attachments.deleteExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment WHERE id = :id").param("id", UUID.fromString(expiring))
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment_content WHERE attachment_id = :id")
                .param("id", UUID.fromString(expiring)).query(Integer.class).single()).isZero();
        // At most 10 files wait on forms.
        for (int i = 0; i < 10; i++) {
            assertThat(stage("fin.finance", "f" + i + ".txt", "text/plain", text("x" + i)).status())
                    .as("upload " + i).isIn(201, 403);
        }
        // Requesters may upload; people who cannot start anything may not.
        assertThat(stage("vic.viewer", "v.txt", "text/plain", text("x")).status()).isEqualTo(403);
        assertThat(stage("otto.outsider", "o.txt", "text/plain", text("x")).status()).isEqualTo(404);
    }

    @Test
    void requestersCanKeepAtMostTenFilesWaiting() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(stage("rhea.requester", "w" + i + ".txt", "text/plain", text("w" + i)).status())
                    .as("upload " + i).isEqualTo(201);
        }
        Response eleventh = stage("rhea.requester", "w10.txt", "text/plain", text("w10"));
        assertThat(eleventh.status()).isEqualTo(409);
        assertThat(eleventh.code()).isEqualTo("ATTACHMENT_LIMIT_REACHED");
    }

    @Test
    void attachmentsStayOutOfRunDataVisibleToReadersWithoutIt() throws Exception {
        UUID workflow = workflowWithFileField(null, null);
        String id = field(stage("rhea.requester", "a.txt", "text/plain", text("hello")).body(), "id");
        UUID run = UUID.fromString(field(startWith("rhea.requester", workflow, id).body(), "id"));
        // vic.viewer has run.read only: the detail has no input (so no file ID) and the files endpoints refuse.
        JsonNode detail = api("vic.viewer").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body();
        assertThat(detail.path("input").isNull()).isTrue();
        assertThat(api("vic.viewer").get(ATTACHMENTS + "/{id}/content", PURCHASING, run, id).status()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- database helpers

    private int attachmentRows(UUID run) {
        return jdbc.sql("SELECT count(*) FROM run_attachment WHERE run_id = :r").param("r", run).query(Integer.class)
                .single();
    }

    private UUID seed(UUID run, UUID subject, String name, long size, String scan, boolean removed) {
        return seedAt(run, subject, name, size, scan, removed, OffsetDateTime.now().minusHours(1));
    }

    private UUID seedAt(UUID run, UUID subject, String name, long size, String scan, boolean removed,
            OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO run_attachment (id, workspace_id, run_id, uploaded_by, file_name, content_type, size_bytes,
                                            sha256, scan_status, created_at)
                VALUES (:id, :ws, :run, :by, :name, 'text/plain', :size, :sha, :scan, :at)
                """).param("id", id).param("ws", PURCHASING).param("run", run).param("by", subject)
                .param("name", name).param("size", size).param("sha", "0".repeat(64)).param("scan", scan)
                .param("at", createdAt).update();
        jdbc.sql("INSERT INTO run_attachment_content (attachment_id, content) VALUES (:id, :c)").param("id", id)
                .param("c", text("hello")).update();
        return id;
    }
}
