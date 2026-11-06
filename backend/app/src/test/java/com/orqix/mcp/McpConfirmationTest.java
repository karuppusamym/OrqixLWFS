package com.orqix.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.McpTestClient;
import com.orqix.support.McpTestTokens;

import tools.jackson.databind.JsonNode;

/**
 * S136 writes: prepare returns a summary and a 5-minute confirmation ID and changes nothing; confirm executes once and
 * replays the same result; expired, declined and other people's confirmations; and the elicitation round trip on the
 * SSE stream.
 */
class McpConfirmationTest extends McpTestBase {

    @Autowired
    private McpConfirmations confirmations;

    @Test
    void prepareChangesNothingAndConfirmStartsTheRequestOnce() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        McpTestClient rhea = client("rhea.requester");
        long before = runs(exposed.workflowId());
        McpTestClient.Reply prepared = rhea.call(exposed.tool(), Map.of("input", input(new BigDecimal("120")),
                "businessKey", "MCP-PO-1"));
        JsonNode p = structured(prepared);
        assertThat(p.path("status").asString()).isEqualTo("confirmationRequired");
        assertThat(p.path("summary").asString()).contains("Replacement laptop").contains("120").contains("MCP-PO-1");
        String id = p.path("confirmationId").asString();
        assertThat(Duration.between(java.time.Instant.now(), java.time.Instant.parse(p.path("expiresAt").asString())))
                .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
        assertThat(runs(exposed.workflowId())).isEqualTo(before);

        // Another person cannot confirm it: it does not exist for them.
        McpTestClient.Reply foreign = client("mani.manager").call("confirm", Map.of("confirmationId", id));
        assertThat(errorCode(foreign)).isEqualTo("NOT_FOUND");
        assertThat(runs(exposed.workflowId())).isEqualTo(before);

        McpTestClient.Reply confirmed = rhea.call("confirm", Map.of("confirmationId", id));
        JsonNode c = structured(confirmed);
        assertThat(isError(confirmed)).as(confirmed.body()).isFalse();
        assertThat(c.path("action").asString()).isEqualTo("requestStarted");
        UUID runId = UUID.fromString(c.path("request").path("id").asString());
        assertThat(c.path("request").path("businessKey").asString()).isEqualTo("MCP-PO-1");
        assertThat(runs(exposed.workflowId())).isEqualTo(before + 1);
        // The request belongs to the person and runs on the engine like any other.
        assertThat(jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :id").param("id", runId)
                .query(UUID.class).single()).isEqualTo(subject("rhea.requester"));
        assertThat(processInstance(PURCHASING, runId)).isNotNull();

        // Idempotent: confirming again returns the same request, nothing new.
        JsonNode again = structured(rhea.call("confirm", Map.of("confirmationId", id)));
        assertThat(again.path("request").path("id").asString()).isEqualTo(runId.toString());
        assertThat(runs(exposed.workflowId())).isEqualTo(before + 1);
        // Arguments are dropped once confirmed; the audit has ids, never values.
        assertThat(jdbc.sql("SELECT arguments IS NULL FROM mcp_confirmation WHERE id = :id")
                .param("id", UUID.fromString(id)).query(Boolean.class).single()).isTrue();
        String audit = jdbc.sql("""
                SELECT string_agg(metadata::text, ' ') FROM audit_event
                WHERE action = 'mcp.tool-called' AND metadata->>'confirmationId' = :id
                """).param("id", id).query(String.class).single();
        assertThat(audit).contains("\"step\": \"prepare\"").contains("\"step\": \"confirm\"").contains(runId.toString())
                .doesNotContain("Replacement laptop").doesNotContain("MCP-PO-1");
    }

    @Test
    void invalidInputIsRefusedAtPrepareWithFieldErrors() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        long before = jdbc.sql("SELECT count(*) FROM mcp_confirmation").query(Long.class).single();
        McpTestClient.Reply bad = client("rhea.requester").call(exposed.tool(), Map.of("input", Map.of("title", "x")));
        assertThat(isError(bad)).isTrue();
        assertThat(errorCode(bad)).isEqualTo("VALIDATION_FAILED");
        assertThat(bad.result().path("structuredContent").path("error").path("fieldErrors").toString()).contains("/input");
        assertThat(jdbc.sql("SELECT count(*) FROM mcp_confirmation").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void anExpiredConfirmationCannotBeConfirmed() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        McpTestClient rhea = client("rhea.requester");
        String id = structured(rhea.call(exposed.tool(), Map.of("input", input(BigDecimal.TEN))))
                .path("confirmationId").asString();
        jdbc.sql("UPDATE mcp_confirmation SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        long before = runs(exposed.workflowId());
        McpTestClient.Reply late = rhea.call("confirm", Map.of("confirmationId", id));
        assertThat(errorCode(late)).isEqualTo("CONFIRMATION_EXPIRED");
        assertThat(runs(exposed.workflowId())).isEqualTo(before);
        assertThat(jdbc.sql("SELECT status FROM mcp_confirmation WHERE id = :id").param("id", UUID.fromString(id))
                .query(String.class).single()).isEqualTo("expired");
        // The sweep drops arguments of expired rows and deletes rows a day after expiry.
        confirmations.sweep(java.time.Instant.now().plus(Duration.ofDays(2)));
        assertThat(jdbc.sql("SELECT count(*) FROM mcp_confirmation WHERE id = :id").param("id", UUID.fromString(id))
                .query(Integer.class).single()).isZero();
    }

    @Test
    void elicitationAsksThePersonOnTheStreamAndAnAcceptStartsTheRequest() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        McpTestClient rhea = client("rhea.requester", true);
        long before = runs(exposed.workflowId());
        MvcResult stream = mvc.perform(MockMvcRequestBuilders.post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + McpTestTokens.token("rhea.requester"))
                .header("Mcp-Session-Id", rhea.sessionId()).header("MCP-Protocol-Version", "2025-11-25")
                .content(json.write(Map.of("jsonrpc", "2.0", "id", 41, "method", "tools/call", "params",
                        Map.of("name", exposed.tool(), "arguments", Map.of("input", input(BigDecimal.ONE)))))))
                .andReturn();
        assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
        List<JsonNode> first = rhea.events(stream.getResponse().getContentAsString());
        assertThat(first).hasSize(1);
        JsonNode elicit = first.get(0);
        assertThat(elicit.path("method").asString()).isEqualTo("elicitation/create");
        assertThat(elicit.path("params").path("mode").asString()).isEqualTo("form");
        assertThat(elicit.path("params").path("message").asString()).contains("Replacement laptop");
        assertThat(elicit.path("params").path("requestedSchema").path("properties").has("confirm")).isTrue();
        assertThat(runs(exposed.workflowId())).isEqualTo(before);

        McpTestClient.Reply answered = rhea.answer(elicit.path("id").asString(), "accept", true);
        assertThat(answered.status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> rhea.events(
                stream.getResponse().getContentAsString()).size() == 2);
        JsonNode result = rhea.events(stream.getResponse().getContentAsString()).get(1);
        assertThat(result.path("id").asInt()).isEqualTo(41);
        assertThat(result.path("result").path("structuredContent").path("action").asString()).isEqualTo("requestStarted");
        assertThat(runs(exposed.workflowId())).isEqualTo(before + 1);
    }

    @Test
    void aDeclinedElicitationDoesNothingAndCannotBeConfirmedLater() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        McpTestClient rhea = client("rhea.requester", true);
        long before = runs(exposed.workflowId());
        MvcResult stream = mvc.perform(MockMvcRequestBuilders.post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + McpTestTokens.token("rhea.requester"))
                .header("Mcp-Session-Id", rhea.sessionId())
                .content(json.write(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call", "params",
                        Map.of("name", exposed.tool(), "arguments", Map.of("input", input(BigDecimal.ONE)))))))
                .andReturn();
        JsonNode elicit = rhea.events(stream.getResponse().getContentAsString()).get(0);
        String confirmationId = elicit.path("id").asString().substring("confirm-".length());
        assertThat(rhea.answer(elicit.path("id").asString(), "decline", null).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> rhea.events(
                stream.getResponse().getContentAsString()).size() == 2);
        JsonNode result = rhea.events(stream.getResponse().getContentAsString()).get(1);
        assertThat(result.path("result").path("structuredContent").path("status").asString()).isEqualTo("declined");
        JsonNode later = structured(rhea.call("confirm", Map.of("confirmationId", confirmationId)));
        assertThat(later.path("status").asString()).isEqualTo("declined");
        assertThat(runs(exposed.workflowId())).isEqualTo(before);
    }

    @Test
    void withoutElicitationOrWithJsonOnlyTheSummaryIsReturnedAsJson() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        McpTestClient rhea = client("rhea.requester", true).accept("application/json");
        McpTestClient.Reply prepared = rhea.call(exposed.tool(), Map.of("input", input(BigDecimal.ONE)));
        assertThat(prepared.contentType()).startsWith("application/json");
        assertThat(structured(prepared).path("status").asString()).isEqualTo("confirmationRequired");
    }

    private long runs(UUID workflowId) {
        return jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :w").param("w", workflowId)
                .query(Long.class).single();
    }

    protected UUID subject(String user) {
        return jdbc.sql("SELECT id FROM subject WHERE external_subject = :s").param("s", "kc-" + user)
                .query(UUID.class).single();
    }
}
