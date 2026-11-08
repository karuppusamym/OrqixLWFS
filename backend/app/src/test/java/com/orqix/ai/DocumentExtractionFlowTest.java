package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.integration.RunDocuments;
import com.orqix.support.OcrStub;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-A06 end to end on the real engine and PostgreSQL (docs/build/S146-parts/B-document-extraction.md): a published
 * workflow whose AI task (extract, invoice template) reads an uploaded request file; OCR through the Azure Document
 * Intelligence stand-in ({@link OcrStub}) chosen by the workspace under its residency rule; extraction by the AI stub;
 * per-field confidence, source and validation; a low-confidence field opens a verification task; the correction becomes
 * the step output, the decision record's human outcome and an evaluation candidate. Also: a confident document goes
 * straight on, a regulated workspace without a residency rule never reaches the cloud service, quarantined files are
 * refused, and the initiator cannot verify their own document.
 */
class DocumentExtractionFlowTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private RunDocuments documents;

    private String skill;

    @BeforeEach
    void grants() throws Exception {
        skill = "invoice-" + UUID.randomUUID().toString().substring(0, 8);
        for (String permission : List.of("ai.approve-skill", "ai.evaluate")) {
            jdbc.sql("""
                    INSERT INTO permission_grant (id, workspace_id, permission, principal_type, principal_value, created_at)
                    VALUES (:id, :ws, :p, 'idpGroup', 'orqix-purchasing-admins', now()) ON CONFLICT DO NOTHING
                    """).param("id", UUID.randomUUID()).param("ws", PURCHASING).param("p", permission).update();
        }
        policy(false, List.of("eu"));
    }

    @AfterEach
    void revoke() throws Exception {
        policy(false, List.of());
        int version = api("wade.admin").get("/workspaces/{ws}/ai-ocr-settings", PURCHASING).body().path("version").asInt();
        assertThat(api("wade.admin").put(Map.of("provider", "local", "expectedVersion", version), "\"r1\"",
                "/workspaces/{ws}/ai-ocr-settings", PURCHASING).status()).isEqualTo(200);
        jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND permission IN ('ai.approve-skill', 'ai.evaluate')")
                .param("ws", PURCHASING).update();
    }

    private void policy(boolean regulated, List<String> residency) throws Exception {
        int version = api("wade.admin").get("/workspaces/{ws}/ai-policy", PURCHASING).body().path("version").asInt();
        Response put = api("wade.admin").put(Map.of("expectedVersion", version, "regulated", regulated, "residency",
                residency, "lowRiskLabels", List.of(), "inputRetention", "digest"), "\"r1\"",
                "/workspaces/{ws}/ai-policy", PURCHASING);
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
    }

    private ObjectNode skillDocument() {
        return (ObjectNode) MAPPER.readTree("""
                {"schemaVersion":"1.0","kind":"skill","key":"%s","name":"Invoice reader","mode":"extract",
                 "instructions":"Read the supplier invoice and extract its fields.",
                 "input":{"invoice":{"type":"attachment","required":true,"classification":"customer-text"}},
                 "document":{"type":"invoice","attachmentInput":"invoice"},
                 "modelProfile":"task-openai",
                 "evaluation":{"suite":"%s-golden","minAccuracy":0.5,"minCases":2},
                 "limits":{"timeoutSeconds":60,"maxCostUsd":0.05}}
                """.formatted(skill, skill));
    }

    private static String invoice(String number, String total, boolean smudged) {
        return String.join("\n", "ACME Supplies GmbH", "Invoice number: " + number, "Invoice date: 2026-10-01",
                "Subtotal: 100.00", "VAT: 19.50", "Total: " + total + (smudged ? " smudged" : ""), "Currency: EUR");
    }

    private void approveAndEvaluate() throws Exception {
        Response created = api("dana.designer").post(Map.of("document", skillDocument()), "/workspaces/{ws}/ai-skills",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(api("dana.designer").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/submit", PURCHASING,
                skill).status()).isEqualTo(200);
        assertThat(api("wade.admin").post(Map.of("reason", "Reviewed the template and instructions"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/approve", PURCHASING, skill).status()).isEqualTo(200);
        // Evaluation cases of a document skill give the recognized text of the document.
        Map<String, Object> suite = new LinkedHashMap<>();
        suite.put("schemaVersion", "1.0");
        suite.put("kind", "evaluationSuite");
        suite.put("key", skill + "-golden");
        suite.put("name", "Golden invoices");
        suite.put("skillKey", skill);
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(Map.of("id", "i1", "input", Map.of("invoice", invoice("INV-1", "119.50", false)), "expected",
                Map.of("fields", Map.of("invoiceNumber", "INV-1", "totalAmount", 119.5)), "source", "synthetic"));
        cases.add(Map.of("id", "i2", "input", Map.of("invoice", invoice("INV-2", "119.50", false)), "expected",
                Map.of("fields", Map.of("invoiceNumber", "INV-2", "currency", "EUR"))));
        suite.put("cases", cases);
        Response saved = api("dana.designer").put(Map.of("document", suite), "\"r1\"",
                "/workspaces/{ws}/ai-eval-suites/{k}", PURCHASING, skill + "-golden");
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response started = api("wade.admin").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/evaluations",
                PURCHASING, skill);
        assertThat(started.status()).as(started.toString()).isEqualTo(202);
        String id = started.body().path("id").asString();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() ->
                !"running".equals(api("wade.admin").get("/workspaces/{ws}/ai-evaluations/{id}", PURCHASING, id).body()
                        .path("status").asString()));
        JsonNode run = api("wade.admin").get("/workspaces/{ws}/ai-evaluations/{id}", PURCHASING, id).body();
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("passed");
        // Test shortcut for the promotion path S134 covers: the version may run with autonomy auto.
        jdbc.sql("UPDATE ai_skill_version SET autonomy_ceiling = 'auto' WHERE skill_id = (SELECT id FROM ai_skill"
                + " WHERE key = :k)").param("k", skill).update();
    }

    private UUID publish() throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ((ObjectNode) d.path("inputSchema").path("properties")).set("invoiceFile",
                    MAPPER.readTree("{\"type\":\"string\",\"title\":\"Invoice\",\"format\":\"attachment\"}"));
            if (d.path("inputSchema").path("required").isArray()) {
                ((ArrayNode) d.path("inputSchema").path("required")).add("invoiceFile");
            }
            ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("""
                    {"id":"read","type":"aiTask","label":"Read the invoice","config":{"skillRef":"%s",
                     "mode":"extract","autonomy":"auto","minConfidence":0.8,
                     "inputMapping":{"invoice":"/input/invoiceFile"}}}
                    """.formatted(skill)));
            ((ObjectNode) d.path("uiLayout").path("positions")).set("read", MAPPER.readTree("{\"x\":40,\"y\":40}"));
            ArrayNode transitions = (ArrayNode) d.path("transitions");
            for (JsonNode t : transitions) {
                if ("t1".equals(t.path("id").asString())) {
                    ((ObjectNode) t).put("to", "read");
                }
            }
            transitions.add(MAPPER.readTree("{\"id\":\"r1\",\"from\":\"read\",\"to\":\"manager\",\"on\":\"next\"}"));
            transitions.add(MAPPER.readTree("{\"id\":\"r2\",\"from\":\"read\",\"to\":\"manager\",\"on\":\"needsReview\"}"));
            transitions.add(MAPPER.readTree("{\"id\":\"r3\",\"from\":\"read\",\"to\":\"manager\",\"on\":\"aiUnavailable\"}"));
        });
    }

    private UUID start(UUID workflow, String text) throws Exception {
        byte[] text8 = text.getBytes(StandardCharsets.UTF_8);
        byte[] png = new byte[PNG_SIGNATURE.length + text8.length];
        System.arraycopy(PNG_SIGNATURE, 0, png, 0, PNG_SIGNATURE.length);
        System.arraycopy(text8, 0, png, PNG_SIGNATURE.length, text8.length);
        Response staged = api("rhea.requester").upload(UUID.randomUUID().toString(), "invoice.png", "image/png", png,
                Map.of(), "/workspaces/{ws}/attachment-uploads", PURCHASING);
        assertThat(staged.status()).as(staged.toString()).isEqualTo(201);
        Map<String, Object> input = input(new BigDecimal("120"));
        input.put("invoiceFile", staged.body().path("id").asString());
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private JsonNode decision(UUID run) throws Exception {
        Response list = api("olga.operator").get("/workspaces/{ws}/runs/{id}/ai-decisions", PURCHASING, run);
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        assertThat(list.body().path("items")).hasSize(1);
        return list.body().path("items").get(0);
    }

    private JsonNode stepOutputs(UUID run) {
        Object value = runtimeService.getVariable(processInstance(PURCHASING, run), "orqix_step_read");
        return value == null ? MAPPER.createObjectNode() : MAPPER.readTree(value.toString());
    }

    @Test
    void aLowConfidenceFieldIsVerifiedAndTheCorrectionFeedsTheEvaluationSuite() throws Exception {
        approveAndEvaluate();
        // Residency: a regulated workspace without a residency rule cannot choose the cloud service.
        policy(true, List.of());
        JsonNode settings = api("wade.admin").get("/workspaces/{ws}/ai-ocr-settings", PURCHASING).body();
        assertThat(settings.path("provider").asString()).isEqualTo("local");
        assertThat(settings.path("providers").toString()).contains("stub-azure", "\"allowed\":false");
        Response refused = api("wade.admin").put(Map.of("provider", "stub-azure", "expectedVersion", 0), "\"r1\"",
                "/workspaces/{ws}/ai-ocr-settings", PURCHASING);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(api("dana.designer").put(Map.of("provider", "stub-azure", "expectedVersion", 0), "\"r1\"",
                "/workspaces/{ws}/ai-ocr-settings", PURCHASING).status()).isEqualTo(403);
        // EU residency admits the EU-tagged provider.
        policy(true, List.of("eu"));
        Response chosen = api("wade.admin").put(Map.of("provider", "stub-azure", "expectedVersion", 0), "\"r1\"",
                "/workspaces/{ws}/ai-ocr-settings", PURCHASING);
        assertThat(chosen.status()).as(chosen.toString()).isEqualTo(200);
        assertThat(chosen.body().path("refusal").isNull()).isTrue();
        UUID workflow = publish();

        // 1. A confident invoice goes straight on with its fields as step outputs.
        UUID clean = start(workflow, invoice("INV-2026-7", "119.50", false));
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200))
                .until(() -> openTask(PURCHASING, clean, "manager") != null);
        JsonNode cleanDecision = decision(clean);
        assertThat(cleanDecision.path("route").asString()).as(cleanDecision.toString()).isEqualTo("next");
        assertThat(cleanDecision.path("output").path("fields").path("invoiceNumber").asString()).isEqualTo("INV-2026-7");
        // The OCR service's own field agrees with the model: its location and confidence are used.
        assertThat(cleanDecision.path("output").path("fieldSources").path("invoiceNumber").path("kind").asString())
                .isEqualTo("ocrKeyValue");
        assertThat(cleanDecision.path("output").path("fieldConfidence").path("invoiceNumber").asDouble())
                .isEqualTo(0.97);
        assertThat(cleanDecision.path("guardrails").path("document").path("provider").asString())
                .isEqualTo("stub-azure");
        assertThat(stepOutputs(clean).path("totalAmount").decimalValue()).isEqualByComparingTo("119.5");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_generation WHERE kind = 'aiOcr' AND workflow_id = :w")
                .param("w", workflow).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_generation WHERE kind = 'aiExtract' AND workflow_id = :w")
                .param("w", workflow).query(Integer.class).single()).isEqualTo(1);

        // 2. A smudged total: low OCR confidence and a failed sum check open a verification task.
        int analyses = OcrStub.analyses();
        UUID smudged = start(workflow, invoice("INV-2026-8", "119.00", true));
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).until(() ->
                api("olga.operator").get("/workspaces/{ws}/runs/{id}/ai-verifications", PURCHASING, smudged).body()
                        .path("items").size() == 1);
        assertThat(OcrStub.analyses()).isEqualTo(analyses + 1);
        assertThat(openTask(PURCHASING, smudged, "manager")).isNull();
        JsonNode detail = run(PURCHASING, smudged);
        assertThat(detail.path("currentSteps").toString()).contains("\"kind\":\"verify\"");
        JsonNode decision = decision(smudged);
        assertThat(decision.path("route").asString()).isEqualTo("needsReview");
        assertThat(decision.path("output").path("fieldProblems").path("totalAmount").toString())
                .contains("SUM_MISMATCH");
        String verificationId = api("olga.operator").get("/workspaces/{ws}/runs/{id}/ai-verifications", PURCHASING,
                smudged).body().path("items").get(0).path("id").asString();
        Response opened = api("olga.operator").get("/workspaces/{ws}/runs/{run}/ai-verifications/{id}", PURCHASING,
                smudged, verificationId);
        assertThat(opened.status()).as(opened.toString()).isEqualTo(200);
        JsonNode view = opened.body();
        assertThat(view.path("canSubmit").asBoolean()).isTrue();
        assertThat(view.path("attachment").path("contentType").asString()).isEqualTo("image/png");
        assertThat(view.path("pages").get(0).path("width").asInt()).isEqualTo(1000);
        JsonNode total = field(view, "totalAmount");
        assertThat(total.path("needsAttention").asBoolean()).isTrue();
        assertThat(total.path("confidence").asDouble()).isLessThan(0.8);
        assertThat(total.path("source").path("kind").asString()).isEqualTo("ocrLine");
        assertThat(total.path("source").path("page").asInt()).isEqualTo(1);
        assertThat(total.path("source").path("box").path("y").asDouble()).isEqualTo(0.3);
        assertThat(total.path("source").path("span").path("end").asInt()
                - total.path("source").path("span").path("start").asInt()).isEqualTo("119.00".length());
        assertThat(total.path("sourceText").asString()).isEqualTo("Total: 119.00 smudged");
        assertThat(field(view, "invoiceNumber").path("needsAttention").asBoolean()).isFalse();

        // The initiator sees the document but never verifies it; nobody else outside the request sees it.
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{run}/ai-verifications/{id}", PURCHASING, smudged,
                verificationId).body().path("canSubmit").asBoolean()).isFalse();
        Response self = api("rhea.requester").post(Map.of("expectedVersion", 1, "fields", Map.of()),
                "/workspaces/{ws}/runs/{run}/ai-verifications/{id}/submit", PURCHASING, smudged, verificationId);
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.code()).isEqualTo("SELF_APPROVAL_DENIED");
        // Every field that needs attention must be answered; a correction that still fails the sum is refused.
        Response incomplete = api("olga.operator").post(Map.of("expectedVersion", 1, "fields", Map.of()),
                "/workspaces/{ws}/runs/{run}/ai-verifications/{id}/submit", PURCHASING, smudged, verificationId);
        assertThat(incomplete.status()).isEqualTo(422);
        assertThat(incomplete.toString()).contains("/fields/totalAmount");
        Response stillWrong = api("olga.operator").post(Map.of("expectedVersion", 1, "fields",
                Map.of("totalAmount", Map.of("action", "correct", "value", 120))),
                "/workspaces/{ws}/runs/{run}/ai-verifications/{id}/submit", PURCHASING, smudged, verificationId);
        assertThat(stillWrong.status()).isEqualTo(422);
        assertThat(stillWrong.toString()).contains("SUM_MISMATCH");
        Response badType = api("olga.operator").post(Map.of("expectedVersion", 1, "fields",
                Map.of("totalAmount", Map.of("action", "correct", "value", "a lot"))),
                "/workspaces/{ws}/runs/{run}/ai-verifications/{id}/submit", PURCHASING, smudged, verificationId);
        assertThat(badType.status()).isEqualTo(422);
        assertThat(api("olga.operator").post(Map.of("expectedVersion", 7, "fields", Map.of()),
                "/workspaces/{ws}/runs/{run}/ai-verifications/{id}/submit", PURCHASING, smudged, verificationId)
                .status()).isEqualTo(409);

        Response verified = api("olga.operator").post(Map.of("expectedVersion", 1, "fields",
                Map.of("totalAmount", Map.of("action", "correct", "value", 119.5),
                        "invoiceNumber", Map.of("action", "confirm"))),
                "/workspaces/{ws}/runs/{run}/ai-verifications/{id}/submit", PURCHASING, smudged, verificationId);
        assertThat(verified.status()).as(verified.toString()).isEqualTo(200);
        assertThat(verified.body().path("status").asString()).isEqualTo("verified");
        assertThat(verified.body().path("decision").path("corrected").toString()).isEqualTo("[\"totalAmount\"]");
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> openTask(PURCHASING, smudged, "manager") != null);
        JsonNode outputs = stepOutputs(smudged);
        assertThat(outputs.path("status").asString()).isEqualTo("verified");
        assertThat(outputs.path("totalAmount").decimalValue()).isEqualByComparingTo("119.5");
        assertThat(outputs.path("invoiceNumber").asString()).isEqualTo("INV-2026-8");
        JsonNode recorded = decision(smudged);
        assertThat(recorded.path("human").path("outcome").asString()).isEqualTo("overridden");
        assertThat(recorded.path("human").path("value").asString()).isEqualTo("corrected: totalAmount");
        JsonNode candidates = api("wade.admin").get("/workspaces/{ws}/ai-eval-candidates", PURCHASING).body();
        assertThat(candidates.toString()).contains(recorded.path("id").asString(), "\"totalAmount\":119.5");
        // Submitting twice is a conflict; the timeline and audit carry counts, never values.
        assertThat(api("olga.operator").post(Map.of("expectedVersion", 2, "fields", Map.of()),
                "/workspaces/{ws}/runs/{run}/ai-verifications/{id}/submit", PURCHASING, smudged, verificationId)
                .status()).isEqualTo(409);
        String events = String.join("\n", jdbc.sql("SELECT type || ' ' || coalesce(data::text, '') FROM run_event"
                + " WHERE run_id = :run ORDER BY seq").param("run", smudged).query(String.class).list());
        assertThat(events).contains("AI_VERIFICATION_OPENED", "AI_VERIFICATION_COMPLETED")
                .doesNotContain("ACME", "INV-2026-8", "Total:");
        assertThat(String.join("\n", jdbc.sql("SELECT action || ' ' || metadata::text FROM audit_event WHERE"
                + " action = 'ai.verification.submitted' AND resource_id = :id").param("id", verificationId)
                .query(String.class).list())).contains("\"corrected\": 1").doesNotContain("INV-2026-8", "ACME");
        // The recognized text and layout live only in the file's S52 OCR result, written by the AI task.
        UUID file = UUID.fromString(view.path("attachment").path("id").asString());
        assertThat(jdbc.sql("SELECT source || ' ' || provider || ' ' || (layout IS NOT NULL) FROM attachment_ocr_result"
                + " WHERE attachment_id = :id").param("id", file).query(String.class).single())
                .isEqualTo("aiTask stub-azure true");

        // 3. Quarantine deletes the text and refuses the file for extraction; the view shows it unavailable.
        Response quarantined = api("olga.operator").post(Map.of("reason", "Check the supplier"),
                "/workspaces/{ws}/runs/{run}/attachments/{id}/quarantine", PURCHASING, smudged, file);
        assertThat(quarantined.status()).as(quarantined.toString()).isIn(200, 204);
        assertThat(jdbc.sql("SELECT count(*) FROM attachment_ocr_result WHERE attachment_id = :id").param("id", file)
                .query(Integer.class).single()).isZero();
        try {
            documents.read(PURCHASING, smudged, file.toString());
            throw new AssertionError("A quarantined file must be refused.");
        } catch (RunDocuments.Refused e) {
            assertThat(e.code()).isEqualTo("QUARANTINED");
        }
        JsonNode after = api("olga.operator").get("/workspaces/{ws}/runs/{run}/ai-verifications/{id}", PURCHASING,
                smudged, verificationId).body();
        assertThat(after.path("attachment").path("available").asBoolean()).isFalse();
        assertThat(field(after, "totalAmount").path("sourceText").isNull()).isTrue();

        // 4. When the policy no longer admits the provider, the task ends on aiUnavailable without calling it.
        policy(true, List.of());
        int before = OcrStub.analyses();
        UUID blocked = start(workflow, invoice("INV-2026-9", "119.50", false));
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200))
                .until(() -> openTask(PURCHASING, blocked, "manager") != null);
        JsonNode unavailable = decision(blocked);
        assertThat(unavailable.path("route").asString()).isEqualTo("aiUnavailable");
        assertThat(unavailable.path("unavailableReason").asString()).contains("regulated");
        assertThat(OcrStub.analyses()).isEqualTo(before);
    }

    private static JsonNode field(JsonNode view, String name) {
        for (JsonNode f : view.path("fields")) {
            if (name.equals(f.path("name").asString())) {
                return f;
            }
        }
        throw new AssertionError("no field " + name);
    }
}
