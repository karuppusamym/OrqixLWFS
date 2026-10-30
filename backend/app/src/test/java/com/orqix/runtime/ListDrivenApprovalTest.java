package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * G2 list-driven approvals on PostgreSQL and Flowable (docs/build/S88): the finance review opens one task per line item
 * of the request, each task names its item, all items must be approved, the first rejection cancels the open items,
 * and the step's typed outputs approvedCount and itemDecisions reach the request result.
 */
class ListDrivenApprovalTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    /** Adds a required list of line items, maps the two outputs and makes finance (managers) approve per line. */
    private static Consumer<ObjectNode> perLine(boolean allowSamePerson) {
        return d -> {
            ObjectNode schema = (ObjectNode) d.path("inputSchema");
            ((ObjectNode) schema.path("properties")).set("lines", n("{\"type\":\"array\",\"title\":\"Line items\","
                    + "\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"required\":[\"description\",\"amount\"],\"properties\":{"
                    + "\"description\":{\"type\":\"string\",\"title\":\"Description\",\"minLength\":1,\"maxLength\":80},"
                    + "\"amount\":{\"type\":\"number\",\"title\":\"Amount\",\"minimum\":0}}}}"));
            ((ArrayNode) schema.path("required")).add("lines");
            ArrayNode order = (ArrayNode) d.path("inputUi").path("order");
            order.add("lines");
            ObjectNode out = (ObjectNode) d.path("outputSchema").path("properties");
            out.set("approvedItems", n("{\"type\":\"integer\",\"title\":\"Approved items\"}"));
            out.set("itemDecisions", n("{\"type\":\"array\",\"title\":\"Decision per item\",\"minItems\":1,"
                    + "\"maxItems\":5,\"items\":{\"type\":\"string\",\"title\":\"Decision\"}}"));
            ObjectNode mapping = (ObjectNode) d.path("outputMapping");
            mapping.set("approvedItems", n("{\"fieldRef\":\"/steps/finance/approvedCount\",\"required\":false}"));
            mapping.set("itemDecisions", n("{\"fieldRef\":\"/steps/finance/itemDecisions\",\"required\":false}"));
            for (JsonNode node : d.path("nodes")) {
                if ("finance".equals(node.path("id").asString())) {
                    ObjectNode config = (ObjectNode) node.path("config");
                    config.put("candidateGroupRef", "managers");
                    ObjectNode perItem = config.putObject("perItem");
                    perItem.put("itemsRef", "/input/lines");
                    perItem.put("itemLabel", "description");
                    if (allowSamePerson) {
                        perItem.put("allowSamePerson", true);
                    }
                }
            }
        };
    }

    private static List<Map<String, Object>> lines(String... descriptions) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String description : descriptions) {
            lines.add(Map.of("description", description, "amount", new BigDecimal("2500")));
        }
        return lines;
    }

    private Response submit(UUID workflow, Object lines) throws Exception {
        Map<String, Object> input = input(new BigDecimal("7500"));
        if (lines != null) {
            input.put("lines", lines);
        }
        return api("rhea.requester").post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
    }

    /** Starts a request, has the manager approve it and waits until one finance task per line item is open. */
    private UUID atFinance(UUID workflow, String... descriptions) throws Exception {
        Response started = submit(workflow, lines(descriptions));
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openItems(run).size() == descriptions.length);
        return run;
    }

    /** Open finance tasks in list order. */
    private List<String> openItems(UUID run) {
        return jdbc.sql("SELECT engine_task_id FROM task_context WHERE run_id = :run AND node_id = 'finance' AND status = 'open' ORDER BY item_index")
                .param("run", run).query(String.class).list();
    }

    private List<JsonNode> financeVisits(UUID run) throws Exception {
        JsonNode path = api("olga.operator").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, run).body();
        List<JsonNode> visits = new ArrayList<>();
        path.path("visited").forEach(v -> {
            if ("finance".equals(v.path("nodeId").asString())) {
                visits.add(v);
            }
        });
        return visits;
    }

    @Test
    void eachLineItemGetsItsOwnTaskAndEveryItemNeedsADifferentPerson() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(false));
        UUID run = atFinance(workflow, "Laptop", "Docking station");
        List<String> tasks = openItems(run);
        // Each task names its item; the request shows one waiting entry per item.
        assertThat(jdbc.sql("SELECT label FROM task_context WHERE run_id = :run AND node_id = 'finance' ORDER BY item_index")
                .param("run", run).query(String.class).list())
                .containsExactly("Finance review: Laptop (item 1 of 2)", "Finance review: Docking station (item 2 of 2)");
        assertThat(run(PURCHASING, run).path("currentSteps").size()).isEqualTo(2);
        JsonNode task = api("mani.manager").get("/workspaces/{ws}/tasks/{id}", PURCHASING, tasks.get(0)).body();
        assertThat(task.toString()).contains("Laptop (item 1 of 2)");

        assertThat(claim("mani.manager", PURCHASING, tasks.get(0)).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, tasks.get(0), "approve", null).status()).isEqualTo(201);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");

        // One person, one item: the S51 separation of duties applies to the other item.
        Response refused = claim("mani.manager", PURCHASING, tasks.get(1));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().path("code").asString()).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(api("mani.manager").getAllPages("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING).toString())
                .doesNotContain(tasks.get(1));

        // Permissions are unchanged: people outside the candidate group cannot take an item.
        assertThat(claim("fin.finance", PURCHASING, tasks.get(1)).status()).isGreaterThanOrEqualTo(400);
        assertThat(api("fin.finance").getAllPages("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING).toString())
                .doesNotContain(tasks.get(1));

        assertThat(claim("max.manager", PURCHASING, tasks.get(1)).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, tasks.get(1), "approve", null).status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("outcomeKind").asString()).isEqualTo("success");
        assertThat(done.path("output").path("approvedItems").asInt()).isEqualTo(2);
        assertThat(done.path("output").path("itemDecisions").toString()).isEqualTo("[\"approved\",\"approved\"]");

        // The run path shows one visit per item, each naming its item.
        List<JsonNode> visits = financeVisits(run);
        assertThat(visits).hasSize(2);
        assertThat(visits).extracting(v -> v.path("itemLabel").asString()).containsExactlyInAnyOrder("Laptop", "Docking station");
        assertThat(visits).extracting(v -> v.path("outcome").asString()).containsOnly("approved");
        // The manager visit (an ordinary approval) carries no item fields.
        JsonNode path = api("olga.operator").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, run).body();
        path.path("visited").forEach(v -> {
            if ("manager".equals(v.path("nodeId").asString())) {
                assertThat(v.has("itemIndex")).isFalse();
            }
        });
    }

    @Test
    void theFirstRejectionRejectsTheStepCancelsTheOpenItemsAndRecordsEveryItem() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(false));
        UUID run = atFinance(workflow, "Laptop", "Monitor", "Chair");
        List<String> tasks = openItems(run);
        assertThat(claim("max.manager", PURCHASING, tasks.get(1)).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, tasks.get(1), "reject", "Not budgeted").status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(done.path("output").path("approvedItems").asInt()).isZero();
        assertThat(done.path("output").path("itemDecisions").toString())
                .isEqualTo("[\"cancelled\",\"rejected\",\"cancelled\"]");
        assertThat(openItems(run)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :run AND node_id = 'finance' AND status = 'cancelled'")
                .param("run", run).query(Long.class).single()).isEqualTo(2L);
        assertThat(managementService.createJobQuery().processInstanceId(processInstance(PURCHASING, run)).count()).isZero();
        assertThat(decide("mani.manager", PURCHASING, tasks.get(0), "approve", null).status()).isGreaterThanOrEqualTo(400);

        List<JsonNode> visits = financeVisits(run);
        assertThat(visits).hasSize(3);
        assertThat(visits).extracting(v -> v.path("itemLabel").asString() + "=" + v.path("outcome").asString())
                .containsExactlyInAnyOrder("Laptop=cancelled", "Monitor=rejected", "Chair=cancelled");
        assertThat(jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = 'TASK_DECIDED' AND node_id = 'finance'")
                .param("run", run).query(String.class).single()).contains("\"itemLabel\": \"Monitor\"", "\"otherApproversCancelled\": 2");
    }

    @Test
    void withAllowSamePersonOnePersonMayDecideEveryItem() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(true));
        UUID run = atFinance(workflow, "Laptop", "Monitor", "Chair");
        for (String task : openItems(run)) {
            assertThat(claim("max.manager", PURCHASING, task).status()).isEqualTo(200);
            assertThat(decide("max.manager", PURCHASING, task, "approve", null).status()).isEqualTo(201);
        }
        awaitLifecycle(PURCHASING, run, "completed");
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("outcomeKind").asString()).isEqualTo("success");
        assertThat(done.path("output").path("approvedItems").asInt()).isEqualTo(3);
        assertThat(done.path("output").path("itemDecisions").size()).isEqualTo(3);
    }

    @Test
    void anEmptyOrMissingListIsRefusedWhenTheRequestIsSubmitted() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(false));
        Response empty = submit(workflow, List.of());
        assertThat(empty.status()).as(empty.toString()).isBetween(400, 499);
        Response missing = submit(workflow, null);
        assertThat(missing.status()).as(missing.toString()).isBetween(400, 499);
        Response tooMany = submit(workflow, lines("a", "b", "c", "d", "e", "f"));
        assertThat(tooMany.status()).as(tooMany.toString()).isBetween(400, 499);
    }

    @Test
    void aRequestWaitingAtAListApprovalCannotBeMovedToAnotherRelease() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLine(false));
        UUID run = atFinance(workflow, "Laptop", "Monitor");
        JsonNode source = MAPPER.readTree(jdbc.sql("SELECT definition::text FROM workflow_release WHERE id = (SELECT release_id FROM workflow_run WHERE id = :run)")
                .param("run", run).query(String.class).single());
        var findings = com.orqix.runtime.internal.ReleaseCompatibility.check(source, source, List.of("finance"));
        assertThat(findings).extracting(f -> f.code()).contains("PER_ITEM");
    }

    // ---------------------------------------------------------------- S104: quorum, one at a time, proposed person

    /** {@link #perLine} plus S104 settings, a list minimum and an approver email column on each line. */
    private static Consumer<ObjectNode> perLineWith(boolean allowSamePerson, int minItems, String settings) {
        return perLine(allowSamePerson).andThen(d -> {
            ObjectNode lines = (ObjectNode) d.path("inputSchema").path("properties").path("lines");
            lines.put("minItems", minItems);
            ((ObjectNode) lines.path("items").path("properties")).set("approver",
                    n("{\"type\":\"string\",\"title\":\"Approver email\",\"maxLength\":200}"));
            for (JsonNode node : d.path("nodes")) {
                if ("finance".equals(node.path("id").asString())) {
                    ((ObjectNode) node.path("config").path("perItem")).setAll((ObjectNode) n(settings));
                }
            }
        });
    }

    private String output(UUID run, String field) throws Exception {
        return run(PURCHASING, run).path("output").path(field).toString();
    }

    @Test
    void aQuorumApprovesTheStepAsSoonAsItIsReachedAndCancelsTheRest() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLineWith(false, 3, "{\"quorum\":2}"));
        UUID run = atFinance(workflow, "Laptop", "Monitor", "Chair");
        List<String> tasks = openItems(run);
        assertThat(claim("mani.manager", PURCHASING, tasks.get(0)).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, tasks.get(0), "approve", null).status()).isEqualTo(201);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(claim("max.manager", PURCHASING, tasks.get(1)).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, tasks.get(1), "approve", null).status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(run(PURCHASING, run).path("outcomeKind").asString()).isEqualTo("success");
        assertThat(output(run, "approvedItems")).isEqualTo("2");
        assertThat(output(run, "itemDecisions")).isEqualTo("[\"approved\",\"approved\",\"cancelled\"]");
        assertThat(openItems(run)).isEmpty();
        assertThat(managementService.createJobQuery().processInstanceId(processInstance(PURCHASING, run)).count()).isZero();
        assertThat(financeVisits(run)).extracting(v -> v.path("itemLabel").asString() + "=" + v.path("outcome").asString())
                .containsExactlyInAnyOrder("Laptop=approved", "Monitor=approved", "Chair=cancelled");
    }

    @Test
    void aRejectionBelowTheQuorumKeepsTheStepOpenUntilTheQuorumIsDecided() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLineWith(true, 3, "{\"quorum\":2}"));
        UUID run = atFinance(workflow, "Laptop", "Monitor", "Chair");
        List<String> tasks = openItems(run);
        assertThat(claim("max.manager", PURCHASING, tasks.get(0)).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, tasks.get(0), "reject", "Not this one").status()).isEqualTo(201);
        // One rejection still leaves two approvals possible: the other items stay open.
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(openItems(run)).containsExactly(tasks.get(1), tasks.get(2));
        assertThat(claim("max.manager", PURCHASING, tasks.get(1)).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, tasks.get(1), "reject", "Nor this one").status()).isEqualTo(201);
        // Two rejections of three: a quorum of two can no longer be reached.
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(run(PURCHASING, run).path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(output(run, "itemDecisions")).isEqualTo("[\"rejected\",\"rejected\",\"cancelled\"]");
        assertThat(output(run, "approvedItems")).isEqualTo("0");
    }

    @Test
    void oneAtATimeOpensTheNextItemOnlyAfterTheLastAndKeepsOnePersonPerItem() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLineWith(false, 1, "{\"sequential\":true}"));
        Response started = submit(workflow, lines("Laptop", "Monitor"));
        UUID run = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openItems(run).size() == 1);
        String first = openItems(run).get(0);
        assertThat(jdbc.sql("SELECT label FROM task_context WHERE engine_task_id = :t").param("t", first).query(String.class).single())
                .isEqualTo("Finance review: Laptop (item 1 of 2)");
        assertThat(claim("mani.manager", PURCHASING, first).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, first, "approve", null).status()).isEqualTo(201);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openItems(run).size() == 1 && !openItems(run).get(0).equals(first));
        String second = openItems(run).get(0);
        assertThat(jdbc.sql("SELECT label FROM task_context WHERE engine_task_id = :t").param("t", second).query(String.class).single())
                .isEqualTo("Finance review: Monitor (item 2 of 2)");
        Response refused = claim("mani.manager", PURCHASING, second);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().path("code").asString()).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(claim("max.manager", PURCHASING, second).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, second, "approve", null).status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(output(run, "itemDecisions")).isEqualTo("[\"approved\",\"approved\"]");
    }

    @Test
    void oneAtATimeStopsAtTheFirstRejectionWithoutOpeningTheRest() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, perLineWith(false, 1, "{\"sequential\":true}"));
        Response started = submit(workflow, lines("Laptop", "Monitor", "Chair"));
        UUID run = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openItems(run).size() == 1);
        String first = openItems(run).get(0);
        assertThat(claim("max.manager", PURCHASING, first).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, first, "reject", "No").status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(run(PURCHASING, run).path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(output(run, "itemDecisions")).isEqualTo("[\"rejected\",\"cancelled\",\"cancelled\"]");
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :run AND node_id = 'finance'")
                .param("run", run).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void theProposedPersonIsAssignedOnlyWhenEligibleOtherwiseTheItemWaitsInTheQueue() throws Exception {
        // Make sure both managers are known people before the request names them.
        api("max.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=1", PURCHASING);
        api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=1", PURCHASING);
        UUID workflow = publishPurchase(PURCHASING, perLineWith(true, 1, "{\"assigneeFrom\":\"approver\"}"));
        List<Map<String, Object>> rows = List.of(
                Map.of("description", "Laptop", "amount", new BigDecimal("2500"), "approver", "MAX.manager@orqix.example"),
                Map.of("description", "Monitor", "amount", new BigDecimal("400"), "approver", "rhea.requester@orqix.example"),
                Map.of("description", "Chair", "amount", new BigDecimal("300"), "approver", "nobody@elsewhere.example"),
                Map.of("description", "Desk", "amount", new BigDecimal("600")));
        Response started = submit(workflow, rows);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openItems(run).size() == 4);
        UUID max = jdbc.sql("SELECT id FROM subject WHERE email = 'max.manager@orqix.example'").query(UUID.class).single();
        List<String> assignees = jdbc.sql("""
                SELECT coalesce(assignee_subject_id::text, '-') FROM task_context
                WHERE run_id = :run AND node_id = 'finance' ORDER BY item_index
                """).param("run", run).query(String.class).list();
        // Max is eligible (case does not matter); the requester may not approve their own request; an unknown email and
        // an empty column leave the item in the group queue.
        assertThat(assignees).containsExactly(max.toString(), "-", "-", "-");
        assertThat(jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = 'TASK_DISPATCHED'")
                .param("run", run).query(String.class).single()).contains("\"policy\": \"proposed\"", "\"itemIndex\": 0");
        List<String> unavailable = jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = 'TASK_PROPOSED_UNAVAILABLE' ORDER BY seq")
                .param("run", run).query(String.class).list();
        assertThat(unavailable).hasSize(2);
        assertThat(String.join(" ", unavailable)).doesNotContain("@");
        // Max decides the assigned item without claiming it.
        assertThat(decide("max.manager", PURCHASING, openItems(run).get(0), "approve", null).status()).isEqualTo(201);
    }
}
