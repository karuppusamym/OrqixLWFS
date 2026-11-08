package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.runtime.internal.GuestLinkSender;
import com.orqix.runtime.internal.StageMailTransport;
import com.orqix.shared.json.Json;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-F05 guest response forms (docs/build/S140) on PostgreSQL and Flowable through the public API: a one-time link
 * issued once and stored as a hash, the minimal guest view, typed answers and files, one submit with an idempotent
 * replay that resumes the wait like a message, revocation, expiry, cross-workspace misuse, the email channel with the
 * approved-domain check at send time (D-CG7), and audit without tokens or addresses. SMTP is a mock.
 */
class GuestResponseFormsTest extends RuntimeTestBase {

    @MockitoBean
    StageMailTransport mail;

    @Autowired
    GuestLinkSender sender;

    @BeforeEach
    void noDomains() {
        jdbc.sql("DELETE FROM guest_domain").update();
        Mockito.reset(mail);
        when(mail.enabled()).thenReturn(true);
    }

    private UUID publishGuestWait(boolean emailed) throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                "examples", "order-payment-wait.json")));
        ObjectNode guest = json.object().put("linkValidFor", "P3D").put("title", "Confirm the payment")
                .put("instructions", "Enter the reference from your bank and attach the remittance advice.");
        guest.set("attachments", json.object().put("maxFiles", 2).set("acceptTypes",
                json.mapper().createArrayNode().add("pdf").add("txt")));
        if (emailed) {
            guest.put("recipientFrom", "/input/vendorEmail");
            ObjectNode email = json.object().put("type", "string").put("title", "Vendor email").put("maxLength", 254);
            ((ObjectNode) example.path("inputSchema").path("properties")).set("vendorEmail", email);
        }
        ((ObjectNode) example.path("nodes").get(1).path("config")).set("guestResponse", guest);
        return publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
    }

    private UUID startOrder(UUID workflow, String vendorEmail) throws Exception {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("orderNumber", "SO-" + UUID.randomUUID().toString().substring(0, 8));
        input.put("customerName", "Acme Ltd");
        if (vendorEmail != null) {
            input.put("vendorEmail", vendorEmail);
        }
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private JsonNode linkOf(UUID run) throws Exception {
        Response list = api("olga.operator").get("/workspaces/{ws}/guest-links?runId=" + run, PURCHASING);
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        assertThat(list.body().path("items")).hasSize(1);
        return list.body().path("items").get(0);
    }

    private String issue(UUID linkId) throws Exception {
        Response issued = api("olga.operator").post(null, "/workspaces/{ws}/guest-links/{id}/token", PURCHASING, linkId);
        assertThat(issued.status()).as(issued.toString()).isEqualTo(201);
        assertThat(issued.header("Cache-Control")).contains("no-store");
        String url = issued.body().path("url").asString();
        assertThat(url).contains("/guest/" + PURCHASING + "#ogf_");
        return url.substring(url.indexOf('#') + 1);
    }

    private Response form(UUID workspace, String token) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/api/v1/workspaces/{ws}/guest-response", workspace);
        if (token != null) {
            get.header("X-Orqix-Guest-Token", token);
        }
        return api(null).send(get);
    }

    /** Multipart upload with the token alone; the answer is checked against the OpenAPI contract. */
    private org.springframework.mock.web.MockHttpServletResponse upload(String token, String name, String type,
            byte[] bytes) throws Exception {
        var post = MockMvcRequestBuilders.multipart("/api/v1/workspaces/{ws}/guest-response/files", PURCHASING)
                .file(new MockMultipartFile("file", name, type, bytes));
        post.header("X-Orqix-Guest-Token", token);
        var result = mvc.perform(post).andReturn();
        com.orqix.support.OpenApiContract.validate(result);
        return result.getResponse();
    }

    private Response submit(String token, String key, Object result, List<String> files) throws Exception {
        Map<String, Object> body = files == null ? Map.of("result", result) : Map.of("result", result, "files", files);
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders
                .post("/api/v1/workspaces/{ws}/guest-response/submit", PURCHASING)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        post.header("X-Orqix-Guest-Token", token);
        if (key != null) {
            post.header("Idempotency-Key", key);
        }
        return api(null).send(post);
    }

    private static Map<String, Object> paid() {
        return Map.of("paymentReference", "PAY-GUEST-1", "amountPaid", 99.5);
    }

    @Test
    void aGuestAnswersOnceWithFilesAndTheWaitResumesLikeAMessage() throws Exception {
        UUID wf = publishGuestWait(false);
        UUID run = startOrder(wf, null);
        JsonNode link = linkOf(run);
        assertThat(link.path("state").asString()).isEqualTo("waiting");
        assertThat(link.path("sendState").asString()).isEqualTo("manual");
        assertThat(link.path("tokenIssued").asBoolean()).isFalse();
        UUID linkId = UUID.fromString(link.path("id").asString());

        assertThat(form(PURCHASING, null).status()).isEqualTo(404);
        assertThat(form(PURCHASING, "ogf_" + "A".repeat(43)).status()).isEqualTo(404);
        String token = issue(linkId);
        assertThat(jdbc.sql("SELECT token_sha256 FROM guest_response WHERE id = :id").param("id", linkId)
                .query(String.class).single()).isEqualTo(Json.sha256Hex(token));
        // Another workspace never sees the link.
        assertThat(form(SANDBOX, token).status()).isEqualTo(404);

        Response view = form(PURCHASING, token);
        assertThat(view.status()).as(view.toString()).isEqualTo(200);
        assertThat(view.body().path("state").asString()).isEqualTo("open");
        assertThat(view.body().path("title").asString()).isEqualTo("Confirm the payment");
        assertThat(view.body().path("questions").findValuesAsString("name"))
                .containsExactly("paymentReference", "amountPaid");
        assertThat(view.body().path("files").path("maxFiles").asInt()).isEqualTo(2);
        assertThat(view.toString()).doesNotContain("Acme Ltd").doesNotContain("SO-");

        var png = upload(token, "photo.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        assertThat(png.getStatus()).as(png.getContentAsString()).isEqualTo(422);
        var txt = upload(token, "remittance.txt", "text/plain",
                "Remittance PAY-GUEST-1".getBytes(StandardCharsets.UTF_8));
        assertThat(txt.getStatus()).as(txt.getContentAsString()).isEqualTo(201);
        String fileId = json.parse(txt.getContentAsString()).path("id").asString();

        // Invalid answers keep the wait open.
        Response bad = submit(token, UUID.randomUUID().toString(), Map.of("paymentReference", 12), List.of(fileId));
        assertThat(bad.status()).as(bad.toString()).isEqualTo(422);
        assertThat(bad.body().path("fieldErrors").findValuesAsString("pointer")).allMatch(p -> p.startsWith("/result"));
        assertThat(submit(token, null, paid(), null).status()).isEqualTo(400);

        String key = UUID.randomUUID().toString();
        Response sent = submit(token, key, paid(), List.of(fileId));
        assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
        assertThat(sent.body().path("fileCount").asInt()).isEqualTo(1);
        Response replay = submit(token, key, paid(), List.of(fileId));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        Response other = submit(token, UUID.randomUUID().toString(), paid(), List.of(fileId));
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.body().path("code").asString()).isEqualTo("GUEST_RESPONSE_SUBMITTED");
        assertThat(form(PURCHASING, token).body().path("state").asString()).isEqualTo("submitted");

        // The answer resumed the wait through the message path and the file is on the request.
        List<String> types = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body()
                .path("items").findValuesAsString("type");
        assertThat(types).contains("WAIT_ENDED", "ATTACHMENT_ADDED", "GUEST_RESPONSE_SUBMITTED");
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment WHERE run_id = :run AND uploaded_by = :guest")
                .param("run", run).param("guest", UUID.fromString("00000000-0000-4000-8000-00000000f140"))
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM guest_response_file WHERE guest_response_id = :id")
                .param("id", linkId).query(Long.class).single()).isZero();
        assertThat(linkOf(run).path("state").asString()).isEqualTo("submitted");
        assertThat(api("olga.operator").delete("/workspaces/{ws}/guest-links/{id}/token", PURCHASING, linkId).status())
                .isEqualTo(409);

        String audit = jdbc.sql("SELECT string_agg(action || ' ' || metadata::text, ' | ') FROM audit_event"
                + " WHERE resource_id = :id").param("id", linkId.toString()).query(String.class).single();
        assertThat(audit).contains("guest.link-issued", "guest.link-viewed", "guest.file-added", "guest.submitted")
                .doesNotContain(token.substring(4, 20)).doesNotContain("PAY-GUEST-1");
    }

    @Test
    void revokedAndExpiredLinksStopWorkingAndExpiryIsAuditedOnce() throws Exception {
        UUID wf = publishGuestWait(false);
        UUID run = startOrder(wf, null);
        UUID linkId = UUID.fromString(linkOf(run).path("id").asString());
        String first = issue(linkId);
        String second = issue(linkId);
        assertThat(form(PURCHASING, first).status()).isEqualTo(404);
        assertThat(form(PURCHASING, second).status()).isEqualTo(200);
        assertThat(api("olga.operator").delete("/workspaces/{ws}/guest-links/{id}/token", PURCHASING, linkId).status())
                .isEqualTo(204);
        assertThat(form(PURCHASING, second).status()).isEqualTo(404);
        assertThat(linkOf(run).path("state").asString()).isEqualTo("revoked");
        // Operators without run.operate cannot issue.
        assertThat(api("rhea.requester").post(null, "/workspaces/{ws}/guest-links/{id}/token", PURCHASING, linkId)
                .status()).isIn(403, 404);

        String third = issue(linkId);
        jdbc.sql("UPDATE guest_response SET token_expires_at = :t WHERE id = :id")
                .param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(5))).param("id", linkId).update();
        assertThat(form(PURCHASING, third).body().path("state").asString()).isEqualTo("expired");
        Response late = submit(third, UUID.randomUUID().toString(), paid(), null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().path("code").asString()).isEqualTo("GUEST_RESPONSE_CLOSED");
        assertThat(sender.expire()).isGreaterThanOrEqualTo(1);
        assertThat(sender.expire()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'guest.link-expired' AND resource_id = :id")
                .param("id", linkId.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anEmailedLinkGoesOnlyToApprovedDomainsCheckedWhenItIsSent() throws Exception {
        UUID wf = publishGuestWait(true);
        UUID run = startOrder(wf, "pat@supplier.example");
        UUID linkId = UUID.fromString(linkOf(run).path("id").asString());
        assertThat(linkOf(run).path("sendState").asString()).isEqualTo("pending");

        // Not approved: nothing is sent.
        sender.send();
        verify(mail, never()).send(any(), anyString(), anyString(), anyString());
        JsonNode refused = linkOf(run);
        assertThat(refused.path("sendState").asString()).isEqualTo("refused");
        assertThat(refused.path("tokenIssued").asBoolean()).isFalse();

        Response domains = api("wade.admin").put(Map.of("domains", List.of("Supplier.Example", "partner.example")),
                null, "/workspaces/{ws}/guest-domains", PURCHASING);
        assertThat(domains.status()).as(domains.toString()).isEqualTo(200);
        assertThat(domains.body().path("domains").toString()).isEqualTo("[\"partner.example\",\"supplier.example\"]");
        assertThat(api("wade.admin").get("/workspaces/{ws}/guest-domains", PURCHASING).body().path("domains").toString())
                .contains("supplier.example").contains("partner.example");
        assertThat(api("wade.admin").put(Map.of("domains", List.of("not a domain")), null,
                "/workspaces/{ws}/guest-domains", PURCHASING).status()).isEqualTo(422);
        assertThat(api("rhea.requester").put(Map.of("domains", List.of("x.example")), null,
                "/workspaces/{ws}/guest-domains", PURCHASING).status()).isIn(403, 404);

        Response resend = api("olga.operator").post(null, "/workspaces/{ws}/guest-links/{id}/send", PURCHASING, linkId);
        assertThat(resend.status()).as(resend.toString()).isEqualTo(200);
        assertThat(resend.body().path("sendState").asString()).isEqualTo("pending");
        sender.send();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(linkId), eq("pat@supplier.example"), eq("Please answer: Confirm the payment"),
                body.capture());
        String text = body.getValue();
        assertThat(text).contains("/guest/" + PURCHASING + "#ogf_");
        String token = text.substring(text.indexOf("#ogf_") + 1, text.indexOf("#ogf_") + 1 + 47);
        JsonNode sent = linkOf(run);
        assertThat(sent.path("sendState").asString()).isEqualTo("sent");
        assertThat(sent.path("sentDomain").asString()).isEqualTo("supplier.example");
        assertThat(form(PURCHASING, token).status()).isEqualTo(200);
        assertThat(submit(token, UUID.randomUUID().toString(), paid(), null).status()).isEqualTo(200);

        String audit = jdbc.sql("SELECT string_agg(action || ' ' || metadata::text, ' | ') FROM audit_event"
                + " WHERE resource_id = :id").param("id", linkId.toString()).query(String.class).single();
        assertThat(audit).contains("guest.link-refused", "domainNotApproved", "guest.link-sent")
                .doesNotContain("pat@supplier.example");
    }
}
