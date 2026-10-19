package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * UI03 BPMN source (docs/build/S60) on PostgreSQL: the compiled BPMN of a draft, the round-trip check, checking and
 * applying edited BPMN with the YAML source mode's permission boundaries (read: workflow.read; check/apply:
 * workflow.edit), If-Match, refusal with the specific problem and no leakage outside the workspace.
 */
class BpmnSourceApiTest extends com.orqix.support.RuntimeTestBase {

    private static final String BPMN = "/workspaces/{ws}/workflows/{id}/draft/bpmn";
    private static final String CHECK = BPMN + "/check";
    private static final String DRAFT = "/workspaces/{ws}/workflows/{id}/draft";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID workflow() throws Exception {
        var response = api("dana.designer").post(Map.of("key", unique("bpmn"), "name", "BPMN " + unique("w"),
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }

    private JsonNode draft(UUID wf) throws Exception {
        return api("dana.designer").get(DRAFT, PURCHASING, wf).body();
    }

    @Test
    void readersSeeTheCompiledBpmnAndEditorsCheckAndApplyExactEdits() throws Exception {
        UUID wf = workflow();
        String key = draft(wf).path("definition").path("workflowKey").asString();

        // Read-only for a viewer, with the round trip proven and the kept sections listed.
        var view = api("vic.viewer").get(BPMN, PURCHASING, wf);
        assertThat(view.status()).as(view.toString()).isEqualTo(200);
        assertThat(view.body().path("available").asBoolean()).isTrue();
        assertThat(view.body().path("revision").asInt()).isEqualTo(1);
        assertThat(view.body().path("roundTrip").path("identical").asBoolean()).as(view.toString()).isTrue();
        assertThat(view.body().path("diagram").asBoolean()).isTrue();
        String xml = view.body().path("bpmn").asString();
        assertThat(xml).contains("<process id=\"" + key + "\"").contains("orqix:approval");
        assertThat(view.body().path("keptFromDraft").toString()).contains("inputSchema").contains("decisions");

        // Checking is editing: a viewer cannot, a designer can, and nothing is saved by a check.
        String edited = xml.replace("name=\"Review request\"", "name=\"Manager review\"");
        assertThat(api("vic.viewer").post(Map.of("revision", 1, "bpmn", edited), CHECK, PURCHASING, wf).status())
                .isEqualTo(403);
        var checked = api("dana.designer").post(Map.of("revision", 1, "bpmn", edited), CHECK, PURCHASING, wf);
        assertThat(checked.status()).as(checked.toString()).isEqualTo(200);
        assertThat(checked.body().path("applicable").asBoolean()).as(checked.toString()).isTrue();
        assertThat(checked.body().path("changes").toString()).contains("Step 'manager' (Manager review)");
        assertThat(checked.body().path("notes").toString()).contains("release hash");
        assertThat(draft(wf).path("revision").asInt()).isEqualTo(1);

        // Unsupported constructs are refused with the element named; nothing reaches the draft.
        String lanes = xml.replace("<startEvent", "<laneSet id=\"lanes\"><lane id=\"managers\" name=\"Managers\"/></laneSet>"
                + "<startEvent");
        var refused = api("dana.designer").post(Map.of("revision", 1, "bpmn", lanes), CHECK, PURCHASING, wf);
        assertThat(refused.body().path("applicable").asBoolean()).isFalse();
        assertThat(refused.body().path("problems").get(0).path("kind").asString()).isEqualTo("unsupported");
        assertThat(refused.body().path("problems").get(0).path("elementId").asString()).isEqualTo("lanes");
        assertThat(refused.body().path("problems").get(0).path("message").asString()).contains("Lanes");

        // Apply: viewer 403, no If-Match 428, refused text 422 with one field error per problem, then success.
        assertThat(api("vic.viewer").put(Map.of("bpmn", edited), "\"r1\"", BPMN, PURCHASING, wf).status()).isEqualTo(403);
        assertThat(api("dana.designer").put(Map.of("bpmn", edited), null, BPMN, PURCHASING, wf).status()).isEqualTo(428);
        var rejected = api("dana.designer").put(Map.of("bpmn", lanes), "\"r1\"", BPMN, PURCHASING, wf);
        assertThat(rejected.status()).as(rejected.toString()).isEqualTo(422);
        assertThat(rejected.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(rejected.body().path("fieldErrors").get(0).path("code").asString()).isEqualTo("unsupported");
        assertThat(rejected.body().path("fieldErrors").get(0).path("message").asString()).startsWith("lanes: ");
        assertThat(draft(wf).path("revision").asInt()).isEqualTo(1);

        var applied = api("dana.designer").put(Map.of("bpmn", edited), "\"r1\"", BPMN, PURCHASING, wf);
        assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
        assertThat(applied.header("ETag")).isEqualTo("\"r2\"");
        assertThat(applied.body().path("revision").asInt()).isEqualTo(2);
        JsonNode saved = draft(wf).path("definition");
        assertThat(node(saved, "manager").path("label").asString()).isEqualTo("Manager review");
        JsonNode original = api("dana.designer").get(DRAFT.replace("/draft", "/draft/bpmn") + "?revision=1", PURCHASING, wf)
                .body();
        assertThat(original.path("revision").asInt()).isEqualTo(1);
        assertThat(original.path("bpmn").asString()).isEqualTo(xml);
        assertThat(api("dana.designer").get(BPMN, PURCHASING, wf).body().path("bpmn").asString())
                .contains("name=\"Manager review\"");
        // A stale revision is 412 like saveDraft.
        var stale = api("dana.designer").put(Map.of("bpmn", edited), "\"r1\"", BPMN, PURCHASING, wf);
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().path("currentRevision").asInt()).isEqualTo(2);
        // Audited like a draft save, with the source.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.draft.saved' AND metadata @> '{\"source\": \"bpmn\", \"revision\": 2}'::jsonb")
                .param("ws", PURCHASING).param("wf", wf.toString()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void draftsWithoutAnExactBpmnFormCannotTakeBpmn() throws Exception {
        UUID wf = workflow();
        JsonNode body = draft(wf);
        String xml = api("dana.designer").get(BPMN, PURCHASING, wf).body().path("bpmn").asString();

        // A setting BPMN does not carry: the round trip names it and applying is refused.
        ObjectNode separated = (ObjectNode) body.path("definition").deepCopy();
        ((ObjectNode) node(separated, "finance").path("config")).putArray("separateFrom").add("manager");
        assertThat(api("dana.designer").put(Map.of("definition", separated), "\"r1\"", DRAFT, PURCHASING, wf).status())
                .isEqualTo(200);
        var view = api("dana.designer").get(BPMN, PURCHASING, wf).body();
        assertThat(view.path("available").asBoolean()).isTrue();
        assertThat(view.path("roundTrip").path("identical").asBoolean()).isFalse();
        assertThat(view.path("roundTrip").path("differences").get(0).path("path").asString())
                .endsWith("/config/separateFrom");
        var check = api("dana.designer").post(Map.of("revision", 2, "bpmn", view.path("bpmn").asString()), CHECK,
                PURCHASING, wf).body();
        assertThat(check.path("applicable").asBoolean()).isFalse();
        assertThat(check.path("problems").get(0).path("kind").asString()).isEqualTo("draftRoundTrip");
        assertThat(api("dana.designer").put(Map.of("bpmn", view.path("bpmn").asString()), "\"r2\"", BPMN, PURCHASING, wf)
                .status()).isEqualTo(422);

        // A draft with blockers has no BPMN; the blockers are listed.
        ObjectNode blocked = (ObjectNode) body.path("definition").deepCopy();
        ((ObjectNode) node(blocked, "manager").path("config")).put("candidateGroupRef", "no-such-group");
        assertThat(api("dana.designer").put(Map.of("definition", blocked), "\"r2\"", DRAFT, PURCHASING, wf).status())
                .isEqualTo(200);
        var none = api("vic.viewer").get(BPMN, PURCHASING, wf);
        assertThat(none.status()).as(none.toString()).isEqualTo(200);
        assertThat(none.body().path("available").asBoolean()).isFalse();
        assertThat(none.body().path("bpmn").isNull()).isTrue();
        assertThat(none.body().path("blockers").size()).isPositive();
        var check3 = api("dana.designer").post(Map.of("revision", 3, "bpmn", xml), CHECK, PURCHASING, wf).body();
        assertThat(check3.path("problems").get(0).path("kind").asString()).isEqualTo("draftRoundTrip");
        assertThat(api("dana.designer").get(BPMN + "?revision=99", PURCHASING, wf).status()).isEqualTo(404);
        assertThat(api("dana.designer").post(Map.of("revision", 99, "bpmn", xml), CHECK, PURCHASING, wf).status())
                .isEqualTo(404);
    }

    /** S70: defaults written out in the draft equal omission; applying BPMN keeps the draft's spelling of them. */
    @Test
    void writtenDefaultsDoNotStopBpmnEditing() throws Exception {
        UUID wf = workflow();
        ObjectNode written = (ObjectNode) draft(wf).path("definition").deepCopy();
        ((ObjectNode) node(written, "manager").path("config")).putArray("requiredSkills");
        ((ObjectNode) node(written, "procure").path("config")).put("compensation", "manual");
        assertThat(api("dana.designer").put(Map.of("definition", written), "\"r1\"", DRAFT, PURCHASING, wf).status())
                .isEqualTo(200);
        var view = api("vic.viewer").get(BPMN, PURCHASING, wf).body();
        assertThat(view.path("roundTrip").path("identical").asBoolean()).as(view.toString()).isTrue();
        String edited = view.path("bpmn").asString().replace("name=\"Review request\"", "name=\"Manager review\"");
        var applied = api("dana.designer").put(Map.of("bpmn", edited), "\"r2\"", BPMN, PURCHASING, wf);
        assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
        JsonNode saved = draft(wf).path("definition");
        assertThat(node(saved, "manager").path("label").asString()).isEqualTo("Manager review");
        assertThat(node(saved, "manager").path("config").path("requiredSkills").isArray()).isTrue();
        assertThat(node(saved, "procure").path("config").path("compensation").asString()).isEqualTo("manual");
    }

    /**
     * S77: a draft with a section (error route, a call that raises an error when it fails), a handler started by a
     * signal and a raised error round-trips exactly, is BPMN-editable for editors only and keeps what BPMN cannot show
     * (the order in which the section lists its steps).
     */
    @Test
    void sectionsHandlersAndErrorStepsAreBpmnEditable() throws Exception {
        UUID wf = workflow();
        ObjectNode def = (ObjectNode) draft(wf).path("definition").deepCopy();
        ArrayNode nodes = (ArrayNode) def.path("nodes");
        nodes.add(JSON.readTree("{\"id\":\"sayWithdrawn\",\"type\":\"notification\",\"label\":\"Confirm withdrawal\","
                + "\"config\":{\"channel\":\"inApp\",\"recipient\":\"initiator\",\"message\":\"Your request was withdrawn\"}}"));
        nodes.add(JSON.readTree("{\"id\":\"withdrawn\",\"type\":\"end\",\"label\":\"Withdrawn\",\"config\":"
                + "{\"businessOutcome\":\"Withdrawn\",\"outcomeKind\":\"rejected\"}}"));
        ObjectNode positions = (ObjectNode) def.path("uiLayout").path("positions");
        positions.set("sayWithdrawn", JSON.readTree("{\"x\":600,\"y\":700}"));
        positions.set("withdrawn", JSON.readTree("{\"x\":900,\"y\":700}"));
        ((ArrayNode) def.path("transitions")).add(JSON.readTree(
                "{\"id\":\"w1\",\"from\":\"sayWithdrawn\",\"to\":\"withdrawn\",\"on\":\"next\"}"));
        ((ObjectNode) node(def, "procure").path("config")).put("raiseErrorOnFailure", "ORDER_REJECTED");
        def.set("subprocesses", JSON.readTree("[{\"id\":\"order\",\"label\":\"Order\",\"type\":\"embedded\","
                + "\"nodes\":[\"notify\",\"procure\"],\"onError\":{\"to\":\"notifyRejected\",\"errorCode\":\"ORDER_REJECTED\"}},"
                + "{\"id\":\"withdraw\",\"label\":\"Withdraw\",\"type\":\"event\",\"nodes\":[\"sayWithdrawn\",\"withdrawn\"],"
                + "\"trigger\":{\"type\":\"signal\",\"signal\":\"withdraw\"},\"interrupting\":true}]"));
        var saved = api("dana.designer").put(Map.of("definition", def), "\"r1\"", DRAFT, PURCHASING, wf);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);

        var view = api("vic.viewer").get(BPMN, PURCHASING, wf);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.body().path("roundTrip").path("identical").asBoolean()).as(view.toString()).isTrue();
        assertThat(view.body().path("keptFromDraft").toString()).doesNotContain("\"subprocesses\"")
                .contains("the step order of section 'order'");
        String xml = view.body().path("bpmn").asString();
        assertThat(xml).contains("<subProcess id=\"order\"", "triggeredByEvent=\"true\"", "raiseOnFailure=\"ORDER_REJECTED\"");

        String edited = xml.replace("<subProcess id=\"order\" name=\"Order\">", "<subProcess id=\"order\" name=\"Ordering\">");
        assertThat(api("vic.viewer").post(Map.of("revision", 2, "bpmn", edited), CHECK, PURCHASING, wf).status())
                .isEqualTo(403);
        assertThat(api("vic.viewer").put(Map.of("bpmn", edited), "\"r2\"", BPMN, PURCHASING, wf).status()).isEqualTo(403);
        for (String user : new String[] {"fred.facilities", "otto.outsider"}) {
            assertThat(api(user).get(BPMN, PURCHASING, wf).status()).as(user).isEqualTo(404);
            assertThat(api(user).put(Map.of("bpmn", edited), "\"r2\"", BPMN, PURCHASING, wf).status()).as(user)
                    .isEqualTo(404);
        }
        var checked = api("dana.designer").post(Map.of("revision", 2, "bpmn", edited), CHECK, PURCHASING, wf);
        assertThat(checked.body().path("applicable").asBoolean()).as(checked.toString()).isTrue();
        assertThat(checked.body().path("changes").toString()).contains("Section or handler 'order' (Ordering)");
        assertThat(api("dana.designer").put(Map.of("bpmn", edited), null, BPMN, PURCHASING, wf).status()).isEqualTo(428);
        var applied = api("dana.designer").put(Map.of("bpmn", edited), "\"r2\"", BPMN, PURCHASING, wf);
        assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
        JsonNode after = draft(wf).path("definition");
        assertThat(after.path("subprocesses").get(0).path("label").asString()).isEqualTo("Ordering");
        // Everything else of the sections, handlers and steps is unchanged, including the draft's member order.
        ObjectNode expected = def.deepCopy();
        ((ObjectNode) expected.path("subprocesses").get(0)).put("label", "Ordering");
        assertThat(JSON.readTree(after.path("subprocesses").toString())).isEqualTo(expected.path("subprocesses"));
        assertThat(node(after, "procure").path("config").path("raiseErrorOnFailure").asString()).isEqualTo("ORDER_REJECTED");

        // A construct BPMN does not support inside a section is still refused with its element named.
        String script = xml.replace("<startEvent id=\"order__start\"",
                "<scriptTask id=\"s1\" name=\"Script\"/><startEvent id=\"order__start\"");
        var refused = api("dana.designer").put(Map.of("bpmn", script), "\"r3\"", BPMN, PURCHASING, wf);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.body().path("fieldErrors").toString()).contains("s1: ");
        assertThat(draft(wf).path("revision").asInt()).isEqualTo(3);
    }

    @Test
    void outsidersAndStartOnlyRequestersSeeNothing() throws Exception {
        UUID wf = workflow();
        String xml = api("dana.designer").get(BPMN, PURCHASING, wf).body().path("bpmn").asString();
        for (String user : new String[] {"fred.facilities", "otto.outsider"}) {
            assertThat(api(user).get(BPMN, PURCHASING, wf).status()).as(user).isEqualTo(404);
            assertThat(api(user).post(Map.of("revision", 1, "bpmn", xml), CHECK, PURCHASING, wf).status()).as(user)
                    .isEqualTo(404);
            assertThat(api(user).put(Map.of("bpmn", xml), "\"r1\"", BPMN, PURCHASING, wf).status()).as(user)
                    .isEqualTo(404);
        }
        assertThat(api("rhea.requester").get(BPMN, PURCHASING, wf).status()).isIn(403, 404);
        assertThat(api("dana.designer").get(BPMN, FACILITIES, wf).status()).isIn(403, 404);
        assertThat(api("dana.designer").post(Map.of("revision", 1, "bpmn", ""), CHECK, PURCHASING, wf).status())
                .isIn(400, 422);
        assertThat(draft(wf).path("revision").asInt()).isEqualTo(1);
    }

    private static JsonNode node(JsonNode definition, String id) {
        for (JsonNode n : (ArrayNode) definition.path("nodes")) {
            if (id.equals(n.path("id").asString())) {
                return n;
            }
        }
        throw new IllegalArgumentException(id);
    }
}
