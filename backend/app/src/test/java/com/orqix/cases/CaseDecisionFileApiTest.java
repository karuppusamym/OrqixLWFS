package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.shared.problem.ApiException;
import com.orqix.support.Fixtures;
import com.orqix.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Decision tasks on pinned shared decision tables, case file item events and timer dates (WM04, S118). */
class CaseDecisionFileApiTest extends IntegrationTestBase {
    private final CmmnSubsetValidator validator = new CmmnSubsetValidator();

    static String source() throws Exception {
        try (var input = CaseDecisionFileApiTest.class.getResourceAsStream("/cases-extra/decision-file.cmmn")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String key(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private static final List<Map<String, Object>> FIELDS = List.of(Map.of("key", "amount", "label", "Amount", "type", "number"),
            Map.of("key", "route", "label", "Route", "type", "text"), Map.of("key", "comment", "label", "Comment", "type", "text"));

    private String decision() throws Exception {
        ObjectNode table = ((ObjectNode) Fixtures.purchaseTemplate().path("decisions").get(0)).deepCopy();
        table.remove("key"); table.remove("version");
        String decisionKey = "policy-" + UUID.randomUUID().toString().substring(0, 8);
        var created = api("dana.designer").post(Map.of("key", decisionKey, "name", "Policy " + decisionKey, "content", table), "/workspaces/{ws}/shared-assets/decision", SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return decisionKey;
    }
    private Response define(String caseKey, String decisionKey, Object decisionTasks, List<Map<String, Object>> fields) throws Exception {
        return api("dana.designer").post(Map.of("key", caseKey, "name", "Routed " + caseKey, "sourceXml", source().replace("decisionCase", caseKey).replace("DECISIONKEY", decisionKey),
                "dataFields", fields, "decisionTasks", decisionTasks), "/workspaces/{ws}/cases/definitions", SANDBOX);
    }
    private static final List<Map<String, Object>> MAPPING = List.of(Map.of("taskId", "routeTask", "inputMapping", Map.of("/input/amount", "amount"), "resultMapping", Map.of("route", "route")));
    private JsonNode start(String definition, Map<String, Object> data) throws Exception {
        var started = api("rhea.requester").post(Map.of("data", data), UUID.randomUUID().toString(), "/workspaces/{ws}/cases/definitions/{id}/instances", SANDBOX, definition);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return started.body();
    }
    private String path(JsonNode instance) { return "/workspaces/" + SANDBOX + "/cases/instances/" + instance.path("id").asString(); }
    private JsonNode plan(JsonNode instance) throws Exception { return api("olga.operator").get(path(instance) + "/plan").body(); }
    private JsonNode item(JsonNode plan, String element) {
        for (var item : plan.path("items")) if (element.equals(item.path("elementId").asString())) return item;
        return null;
    }
    private JsonNode values(JsonNode instance) throws Exception { return api("olga.operator").get(path(instance) + "/data").body().path("values"); }
    private int version(JsonNode instance) throws Exception { return api("olga.operator").get(path(instance)).body().path("version").asInt(); }

    @Test void decisionTaskEvaluatesThePinnedTableAndRoutesWorkByItsResult() throws Exception {
        String decisionKey = decision();
        var created = define(key("routed"), decisionKey, MAPPING, FIELDS);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String definition = created.body().path("id").asString();
        var detail = api("dana.designer").get("/workspaces/{ws}/cases/definitions/{id}", SANDBOX, definition).body();
        assertThat(detail.path("decisionTasks").get(0).path("decisionKey").asString()).isEqualTo(decisionKey);
        assertThat(detail.path("decisionTasks").get(0).path("decisionVersion").asInt()).isEqualTo(1);

        var large = start(definition, Map.of("amount", 9000));
        assertThat(values(large).path("route").asString()).isEqualTo("financeReview");
        var plan = plan(large);
        assertThat(item(plan, "route").path("type").asString()).isEqualTo("decisionTask");
        assertThat(item(plan, "route").path("state").asString()).isEqualTo("completed");
        assertThat(item(plan, "route").path("workflow").path("outcome").asString()).isEqualTo("Row 1");
        assertThat(item(plan, "finance").path("state").asString()).isIn("active", "enabled");
        assertThat(plan.path("events").toString()).contains("decisionEvaluated");

        var small = start(definition, Map.of("amount", 100));
        assertThat(values(small).path("route").asString()).isEqualTo("standard");
        assertThat(item(plan(small), "finance").path("state").asString()).isEqualTo("available");
    }

    @Test void aMissingInputLeavesTheTaskForRetryAndBadMappingsAreRefused() throws Exception {
        String decisionKey = decision();
        var created = define(key("retry"), decisionKey, MAPPING, FIELDS);
        var instance = start(created.body().path("id").asString(), Map.of());
        var route = item(plan(instance), "route");
        assertThat(route.path("state").asString()).isEqualTo("active");
        assertThat(route.path("workflow").path("status").asString()).isEqualTo("failed");
        assertThat(route.path("workflow").path("detail").asString()).contains("amount");
        var corrected = api("olga.operator").post(Map.of("expectedVersion", version(instance), "reason", "Amount known now", "values", Map.of("amount", 6000)),
                UUID.randomUUID().toString(), path(instance) + "/data");
        assertThat(corrected.status()).as(corrected.toString()).isEqualTo(200);
        var retried = api("olga.operator").post(Map.of("expectedVersion", version(instance), "reason", "Evaluate again"), UUID.randomUUID().toString(),
                path(instance) + "/plan-items/" + route.path("id").asString() + "/retryWorkflow");
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        assertThat(values(instance).path("route").asString()).isEqualTo("financeReview");

        assertThat(define(key("unknown"), "no-such-decision", MAPPING, FIELDS).status()).isEqualTo(422);
        assertThat(define(key("missing"), decisionKey, List.of(Map.of("taskId", "routeTask", "inputMapping", Map.of(), "resultMapping", Map.of())), FIELDS).status()).isEqualTo(422);
        assertThat(define(key("output"), decisionKey, List.of(Map.of("taskId", "routeTask", "inputMapping", Map.of("/input/amount", "amount"), "resultMapping", Map.of("route", "nothing"))), FIELDS).status()).isEqualTo(422);
        assertThat(define(key("nofile"), decisionKey, MAPPING, FIELDS.subList(0, 2)).status()).as("the case file item names an undeclared field").isEqualTo(422);
    }

    @Test void caseFileItemEventsReleaseWorkWhenTheFieldGetsItsFirstValue() throws Exception {
        var created = define(key("file"), decision(), MAPPING, FIELDS);
        var instance = start(created.body().path("id").asString(), Map.of("amount", 100));
        var before = plan(instance);
        assertThat(item(before, "answer").path("state").asString()).isEqualTo("available");
        assertThat(before.path("items").toString()).doesNotContain("__file");
        var commented = api("olga.operator").post(Map.of("expectedVersion", version(instance), "reason", "Requester asked a question",
                "values", Map.of("amount", 100, "route", "standard", "comment", "Can this ship earlier?")), UUID.randomUUID().toString(), path(instance) + "/data");
        assertThat(commented.status()).as(commented.toString()).isEqualTo(200);
        var after = plan(instance);
        assertThat(item(after, "answer").path("state").asString()).isIn("active", "enabled");
        assertThat(after.path("events").toString()).contains("caseFileEvent");
        var started = start(created.body().path("id").asString(), Map.of("amount", 100, "comment", "Asked at the start"));
        assertThat(item(plan(started), "answer").path("state").asString()).as("a value given at start creates the item").isIn("active", "enabled");
    }

    @Test void admitsCaseFilesDecisionsAndTimerDatesInsideTheGrammar() throws Exception {
        String xml = source().replace("DECISIONKEY", "amount-policy");
        assertThat(validator.decisionTasks(xml)).containsEntry("routeTask", "amount-policy");
        assertThat(validator.fileItems(xml)).containsEntry("commentItem", "comment");
        assertThat(validator.fileTriggers(xml)).containsEntry("commented__file1", new CmmnSubsetValidator.FileTrigger("comment", "create"));
        assertThat(validator.engineXml(xml)).doesNotContain("caseFileItemOnPart").doesNotContain("<decision ").contains("commented__file1_item");
        String timer = CmmnEventRulesTest.source("timer-and-data");
        String future = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(10).withNano(0).toString();
        validator.validate(timer.replace("PT1H", future), "triggerCase");
        for (var invalid : new String[] {
                xml.replace("multiplicity=\"ZeroOrOne\"", "multiplicity=\"OneOrMore\""),
                xml.replace("<standardEvent>create</standardEvent>", "<standardEvent>delete</standardEvent>"),
                xml.replace("orqixdec:amount-policy", "orqixdec:Bad_Key"),
                xml.replace("xmlns:orqixdec=\"urn:orqix:decision\"", "xmlns:orqixdec=\"urn:other\""),
                xml.replace("isBlocking=\"true\"", "isBlocking=\"false\""),
                xml.replace("sourceRef=\"commentItem\"", "sourceRef=\"route\"") })
            assertThatThrownBy(() -> validator.validate(invalid, "decisionCase")).as(invalid).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(timer.replace("PT1H", "2020-01-01T00:00:00Z"), "triggerCase")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(timer.replace("PT1H", "R3/PT1H"), "triggerCase")).isInstanceOf(ApiException.class);
    }
}
