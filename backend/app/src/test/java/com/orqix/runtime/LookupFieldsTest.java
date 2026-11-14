package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.ServiceNowStub;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-F02 lookup fields (docs/build/S140) on PostgreSQL and Flowable through the public API, with the ServiceNow-shaped
 * WireMock stub: search-as-you-type under the requester's start right, the minimum search length, the memory cache,
 * the binding's request limit (S131), an unavailable system, and the server-side check of the chosen value when the
 * request is submitted. Nothing of a lookup is journaled or stored.
 */
class LookupFieldsTest extends RuntimeTestBase {

    private static final String KEY = "servicenow-users";

    @BeforeEach
    void registerUsers() throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                "examples", "connections", "servicenow-users.connection.json")));
        Response v1 = api("wade.admin").post(Map.of("definition", example),
                "/workspaces/{ws}/connections/{key}/versions", PURCHASING, KEY);
        assertThat(v1.status()).as(v1.toString()).isIn(201, 409);
        jdbc.sql("DELETE FROM connection_binding WHERE workspace_id = :ws AND connection_key = :key")
                .param("ws", PURCHASING).param("key", KEY).update();
        Response bound = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/servicenow",
                "auth", Map.of("type", "none"), "enabled", true), null,
                "/workspaces/{ws}/connections/{key}/binding", PURCHASING, KEY);
        assertThat(bound.status()).as(bound.toString()).isEqualTo(200);
        ServiceNowStub.seedUser("ann.buyer", "ann.buyer@supplier.example");
        ServiceNowStub.seedUser("andy.other", "andy.other@supplier.example");
        ServiceNowStub.seedUser("bob.elsewhere", "bob@elsewhere.example");
    }

    /** The purchase template with an optional lookup question "buyerEmail". */
    private UUID publishWithLookup() throws Exception {
        return publishPurchase(PURCHASING, definition -> {
            ((ArrayNode) definition.path("dependencies").path("connections"))
                    .add(json.object().put("key", KEY).put("definitionVersion", 1));
            ObjectNode field = json.object().put("type", "string").put("title", "Buyer").put("maxLength", 254);
            field.set("lookup", json.object().put("connection", KEY).put("operation", "searchUsers")
                    .put("searchInput", "text").put("rows", "/result/[*]").put("valueColumn", "email")
                    .put("labelColumn", "name").put("detailColumn", "email").put("minSearchLength", 2));
            ((ObjectNode) definition.path("inputSchema").path("properties")).set("buyerEmail", field);
        });
    }

    private Response search(String user, UUID workflow, String text) throws Exception {
        return api(user).post(Map.of("search", text), "/workspaces/{ws}/workflows/{wf}/lookups/{field}", PURCHASING,
                workflow, "buyerEmail");
    }

    private Response start(UUID workflow, String buyer) throws Exception {
        Map<String, Object> input = input(new BigDecimal("120"));
        input.put("buyerEmail", buyer);
        return api("rhea.requester").post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
    }

    private long userCalls() {
        return ServiceNowStub.calls().stream().filter(c -> c.path().equals("/api/now/table/sys_user")).count();
    }

    @Test
    void optionsAreSearchedAsThePersonTypesCachedAndTheChosenValueIsCheckedAtSubmit() throws Exception {
        UUID wf = publishWithLookup();

        // Below the minimum length nothing is called.
        Response tooShort = search("rhea.requester", wf, "a");
        assertThat(tooShort.status()).as(tooShort.toString()).isEqualTo(200);
        assertThat(tooShort.body().path("options")).isEmpty();
        assertThat(tooShort.body().path("minSearchLength").asInt()).isEqualTo(2);
        assertThat(userCalls()).isZero();

        Response found = search("rhea.requester", wf, "an");
        assertThat(found.status()).as(found.toString()).isEqualTo(200);
        assertThat(found.header("Cache-Control")).contains("no-store");
        assertThat(found.body().path("options").findValuesAsString("value"))
                .containsExactlyInAnyOrder("ann.buyer@supplier.example", "andy.other@supplier.example");
        assertThat(found.body().path("options").findValuesAsString("label")).contains("ann.buyer");
        assertThat(found.body().path("cached").asBoolean()).isFalse();
        assertThat(ServiceNowStub.calls().getLast().query().get("sysparm_query"))
                .isEqualTo("active=true^emailSTARTSWITHan");
        long afterFirst = userCalls();

        // The same search again is answered from memory.
        Response again = search("mani.manager", wf, "AN");
        assertThat(again.body().path("cached").asBoolean()).isTrue();
        assertThat(userCalls()).isEqualTo(afterFirst);

        // A text the operation's input does not accept is not sent (empty list, no call).
        Response odd = search("rhea.requester", wf, "a b");
        assertThat(odd.body().path("options")).isEmpty();
        assertThat(userCalls()).isEqualTo(afterFirst);

        // A value offered by a search is accepted without another call.
        Response ok = start(wf, "ann.buyer@supplier.example");
        assertThat(ok.status()).as(ok.toString()).isEqualTo(201);
        assertThat(userCalls()).isEqualTo(afterFirst);

        // A value nobody was offered is asked for, and refused when the system does not return it exactly.
        Response forged = start(wf, "mallory@supplier.example");
        assertThat(forged.status()).as(forged.toString()).isEqualTo(422);
        JsonNode error = forged.body().path("fieldErrors").get(0);
        assertThat(error.path("pointer").asString()).isEqualTo("/input/buyerEmail");
        assertThat(error.path("code").asString()).isEqualTo("lookup");
        assertThat(userCalls()).isGreaterThan(afterFirst);

        // A real value that was never searched is checked with the system and accepted.
        Response checked = start(wf, "bob@elsewhere.example");
        assertThat(checked.status()).as(checked.toString()).isEqualTo(201);

        // Nothing of a lookup is journaled or kept: no effect rows, no audit with the text.
        assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE connection_key = :key").param("key", KEY)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE metadata::text LIKE '%ann.buyer%' OR metadata::text LIKE '%mallory%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void onlyPeopleWhoMayStartTheWorkflowCanSearchAndUnknownQuestionsAreNotFound() throws Exception {
        UUID wf = publishWithLookup();
        assertThat(search("vic.viewer", wf, "an").status()).isEqualTo(403);
        assertThat(search("otto.outsider", wf, "an").status()).isEqualTo(404);
        Response unknown = api("rhea.requester").post(Map.of("search", "an"),
                "/workspaces/{ws}/workflows/{wf}/lookups/{field}", PURCHASING, wf, "title");
        assertThat(unknown.status()).isEqualTo(404);
        Response tooLong = search("rhea.requester", wf, "x".repeat(101));
        assertThat(tooLong.status()).isEqualTo(422);
    }

    @Test
    void theBindingRequestLimitAndAnUnavailableSystemAreReportedAndNothingIsAcceptedUnchecked() throws Exception {
        UUID wf = publishWithLookup();
        Response limit = api("wade.admin").put(Map.of("maxRequestsPerMinute", 1), null,
                "/workspaces/{ws}/connections/{key}/rate-limit", PURCHASING, KEY);
        assertThat(limit.status()).as(limit.toString()).isEqualTo(200);
        assertThat(search("rhea.requester", wf, "ann").status()).isEqualTo(200);
        Response limited = search("rhea.requester", wf, "andy");
        assertThat(limited.status()).as(limited.toString()).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isNotBlank();
        // The submit check of an unchecked value is refused while the system cannot be asked.
        Response refused = start(wf, "bob@elsewhere.example");
        assertThat(refused.status()).as(refused.toString()).isEqualTo(429);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :wf").param("wf", wf)
                .query(Long.class).single()).isZero();

        jdbc.sql("DELETE FROM connection_rate_limit").update();
        rateLimiterReset();
        jdbc.sql("UPDATE connection_binding SET enabled = false WHERE workspace_id = :ws AND connection_key = :key")
                .param("ws", PURCHASING).param("key", KEY).update();
        Response down = search("rhea.requester", wf, "bo");
        assertThat(down.status()).as(down.toString()).isEqualTo(503);
        assertThat(start(wf, "bob@elsewhere.example").status()).isEqualTo(503);
        // Without a lookup answer the request still starts: an optional lookup question may stay empty.
        Response empty = api("rhea.requester").post(Map.of("workflowId", wf, "input", input(new BigDecimal("120"))),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(empty.status()).as(empty.toString()).isEqualTo(201);
        assertThat(List.of(201)).contains(empty.status());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.orqix.integration.health.BindingRateLimiter limiter;

    private void rateLimiterReset() {
        limiter.invalidate(PURCHASING, KEY);
    }
}
