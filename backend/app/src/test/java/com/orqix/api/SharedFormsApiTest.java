package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared forms (docs/build/S127): a fourth kind of shared asset. A form version is checked with the publishing compiler
 * when it is saved; a workflow copies its questions into the request form and pins the version. The copy is checked at
 * validation and publish like a shared decision (missing, archived, edited, newer) and by the compiler like any other
 * request question, so reuse never bypasses a publishing rule. Where-used lists every workflow that copied it.
 */
class SharedFormsApiTest extends RuntimeTestBase {

    private static final String ASSETS = "/workspaces/{ws}/shared-assets";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Delivery details: three questions, a rule that shows and requires the reason when urgent, and one group. */
    private static ObjectNode deliveryForm() {
        return (ObjectNode) JSON.readTree("""
                {"inputSchema":{"type":"object","additionalProperties":false,"required":["deliveryDate"],
                  "properties":{
                    "deliveryDate":{"type":"string","format":"date","title":"Needed by"},
                    "urgent":{"type":"boolean","title":"Urgent delivery"},
                    "urgentReason":{"type":"string","title":"Why is it urgent?","maxLength":500}}},
                 "inputUi":{"order":["deliveryDate","urgent","urgentReason"],
                  "widgets":{"urgent":"checkbox","urgentReason":"textarea"},
                  "visibleWhen":{"urgentReason":{"fieldRef":"/input/urgent","operator":"eq","value":true}},
                  "requiredWhen":{"urgentReason":{"fieldRef":"/input/urgent","operator":"eq","value":true}},
                  "layout":{"sections":[{"id":"delivery","title":"Delivery","columns":1,
                    "fields":["deliveryDate","urgent","urgentReason"]}]}}}
                """);
    }

    private JsonNode createForm(String key, ObjectNode content) throws Exception {
        var created = api("dana.designer").post(Map.of("key", key, "name", "Form " + key, "content", content),
                ASSETS + "/form", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body();
    }

    private record Draft(UUID id, int revision, ObjectNode definition) {
    }

    private Draft workflow() throws Exception {
        var created = api("dana.designer").post(Map.of("key", unique("sf"), "name", "Shared form " + unique("w"),
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        return new Draft(id, 1, (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id)
                .body().path("definition"));
    }

    private Draft save(Draft draft) throws Exception {
        var saved = api("dana.designer").put(Map.of("definition", draft.definition()), "\"r" + draft.revision() + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, draft.id());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return new Draft(draft.id(), draft.revision() + 1, draft.definition());
    }

    private JsonNode validate(Draft draft) throws Exception {
        var report = api("dana.designer").post(Map.of("revision", draft.revision()), "/workspaces/{ws}/workflows/{id}/validate",
                PURCHASING, draft.id());
        assertThat(report.status()).as(report.toString()).isEqualTo(200);
        return report.body();
    }

    private Response publish(Draft draft) throws Exception {
        return api("paul.publisher").post(Map.of("draftRevision", draft.revision(), "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, draft.id());
    }

    private static List<String> codes(JsonNode report, String pathPrefix) {
        List<String> codes = new ArrayList<>();
        for (JsonNode issue : report.path("issues")) {
            if (issue.path("path").asString("").startsWith(pathPrefix)) {
                codes.add(issue.path("code").asString() + ":" + issue.path("severity").asString());
            }
        }
        return codes;
    }

    /**
     * What the Studio does on insert: copies the chosen questions (shared key to workflow key), their required flags,
     * widgets and rules (references renamed), and the groups, then links the version with the copied questions.
     */
    private static void insert(ObjectNode definition, String key, int version, JsonNode content, Map<String, String> fields) {
        ObjectNode schema = (ObjectNode) definition.path("inputSchema");
        ObjectNode properties = (ObjectNode) schema.path("properties");
        ArrayNode required = schema.has("required") ? (ArrayNode) schema.path("required") : schema.putArray("required");
        ObjectNode ui = definition.has("inputUi") ? (ObjectNode) definition.path("inputUi") : definition.putObject("inputUi");
        ArrayNode order = ui.has("order") ? (ArrayNode) ui.path("order") : ui.putArray("order");
        ObjectNode widgets = ui.has("widgets") ? (ObjectNode) ui.path("widgets") : ui.putObject("widgets");
        JsonNode sharedUi = content.path("inputUi");
        for (var e : fields.entrySet()) {
            String shared = e.getKey();
            String local = e.getValue();
            properties.set(local, content.path("inputSchema").path("properties").path(shared).deepCopy());
            content.path("inputSchema").path("required").forEach(r -> {
                if (shared.equals(r.asString())) {
                    required.add(local);
                }
            });
            order.add(local);
            if (sharedUi.path("widgets").has(shared)) {
                widgets.set(local, sharedUi.path("widgets").path(shared).deepCopy());
            }
            for (String rule : List.of("visibleWhen", "requiredWhen")) {
                if (sharedUi.path(rule).has(shared)) {
                    ObjectNode target = ui.has(rule) ? (ObjectNode) ui.path(rule) : ui.putObject(rule);
                    ObjectNode condition = (ObjectNode) sharedUi.path(rule).path(shared).deepCopy();
                    String ref = condition.path("fieldRef").asString().substring("/input/".length());
                    condition.put("fieldRef", "/input/" + fields.getOrDefault(ref, ref));
                    target.set(local, condition);
                }
            }
        }
        ObjectNode layout = ui.has("layout") ? (ObjectNode) ui.path("layout") : ui.putObject("layout");
        ArrayNode sections = layout.has("sections") ? (ArrayNode) layout.path("sections") : layout.putArray("sections");
        for (JsonNode section : sharedUi.path("layout").path("sections")) {
            ObjectNode copy = (ObjectNode) section.deepCopy();
            ArrayNode copied = copy.putArray("fields");
            section.path("fields").forEach(f -> {
                if (fields.containsKey(f.asString())) {
                    copied.add(fields.get(f.asString()));
                }
            });
            copy.put("id", key + "-" + section.path("id").asString());
            sections.add(copy);
        }
        ObjectNode dependencies = (ObjectNode) definition.path("dependencies");
        ArrayNode assets = dependencies.has("assets") ? (ArrayNode) dependencies.path("assets") : dependencies.putArray("assets");
        ObjectNode ref = assets.addObject().put("kind", "form").put("key", key).put("version", version).put("target", "request");
        ObjectNode map = ref.putObject("fields");
        fields.forEach(map::put);
    }

    private static Map<String, String> identity(String... keys) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : keys) {
            map.put(key, key);
        }
        return map;
    }

    @Test
    void sharedFormsAreCheckedWithThePublishingCompilerAndVersioned() throws Exception {
        String key = unique("delivery");

        // A rule on a question the form does not have is refused with the compiler's finding.
        ObjectNode broken = deliveryForm();
        ((ObjectNode) broken.path("inputUi").path("visibleWhen")).putObject("missing")
                .put("fieldRef", "/input/urgent").put("operator", "eq").put("value", true);
        var refused = api("dana.designer").post(Map.of("key", key, "name", "Broken", "content", broken), ASSETS + "/form", PURCHASING);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.toString()).contains("/content/inputUi/visibleWhen/missing").contains("WF_FORM_INVALID");

        // Workflow-level settings do not belong to a shared form.
        ObjectNode workflowLevel = deliveryForm();
        ((ObjectNode) workflowLevel.path("inputUi")).put("submitLabel", "Send");
        assertThat(api("dana.designer").post(Map.of("key", key, "name", "Level", "content", workflowLevel), ASSETS + "/form",
                PURCHASING).status()).isEqualTo(422);

        // Requesters and viewers cannot create one.
        assertThat(api("rhea.requester").post(Map.of("key", key, "name", "No", "content", deliveryForm()), ASSETS + "/form",
                PURCHASING).status()).isEqualTo(403);
        assertThat(api("vic.viewer").post(Map.of("key", key, "name", "No", "content", deliveryForm()), ASSETS + "/form",
                PURCHASING).status()).isEqualTo(403);

        JsonNode created = createForm(key, deliveryForm());
        assertThat(created.path("asset").path("kind").asString()).isEqualTo("form");
        assertThat(created.path("asset").path("latestVersion").asInt()).isEqualTo(1);
        assertThat(created.path("asset").path("owner").path("displayName").asString()).isNotBlank();
        assertThat(created.path("versions").get(0).path("content").path("inputSchema").path("properties").size()).isEqualTo(3);
        assertThat(api("dana.designer").post(Map.of("key", key, "name", "Another name", "content", deliveryForm()),
                ASSETS + "/form", PURCHASING).status()).isEqualTo(409);

        // Versions: stale expected version 409, identical 422, a change adds version 2; version 1 is unchanged.
        ObjectNode v2 = deliveryForm();
        ((ObjectNode) v2.path("inputSchema").path("properties").path("urgentReason")).put("maxLength", 300);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 2, "content", v2), ASSETS + "/form/{key}/versions",
                PURCHASING, key).status()).isEqualTo(409);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 1, "content", deliveryForm()),
                ASSETS + "/form/{key}/versions", PURCHASING, key).status()).isEqualTo(422);
        var added = api("dana.designer").post(Map.of("expectedVersion", 1, "content", v2, "changeNote", "Shorter reason"),
                ASSETS + "/form/{key}/versions", PURCHASING, key);
        assertThat(added.status()).as(added.toString()).isEqualTo(201);
        JsonNode detail = api("vic.viewer").get(ASSETS + "/form/{key}", PURCHASING, key).body();
        assertThat(detail.path("asset").path("latestVersion").asInt()).isEqualTo(2);
        assertThat(detail.path("versions").get(0).path("changeNote").asString()).isEqualTo("Shorter reason");
        assertThat(detail.path("versions").get(1).path("content").path("inputSchema").path("properties").path("urgentReason")
                .path("maxLength").asInt()).isEqualTo(500);

        // Search lists forms with the other kinds; requesters see nothing.
        var search = api("vic.viewer").get(ASSETS + "?kind=form&q=" + key, PURCHASING);
        assertThat(search.status()).isEqualTo(200);
        assertThat(search.body().path("items").get(0).path("key").asString()).isEqualTo(key);
        assertThat(api("vic.viewer").get(ASSETS, PURCHASING).body().path("searchedKinds").toString()).contains("form");
        assertThat(api("rhea.requester").get(ASSETS + "/form/{key}", PURCHASING, key).status()).isEqualTo(403);

        // Audit names the kind, version and question count, never the content.
        String metadata = jdbc.sql("SELECT string_agg(action || ' ' || metadata::text, ' ') FROM audit_event"
                + " WHERE resource_type = 'sharedAsset' AND resource_id = :id").param("id", "form:" + key)
                .query(String.class).single();
        assertThat(metadata).contains("shared.form.created").contains("shared.form.versioned")
                .contains("\"questions\": 3").doesNotContain("urgent");
    }

    @Test
    void insertedFormsArePinnedCheckedAndListedWhereUsed() throws Exception {
        String key = unique("delivery");
        createForm(key, deliveryForm());

        // Workflow A copies every question; "urgent" clashed and was renamed to "isUrgent" (the rule follows it).
        Draft a = workflow();
        Map<String, String> renamed = new LinkedHashMap<>();
        renamed.put("deliveryDate", "deliveryDate");
        renamed.put("urgent", "isUrgent");
        renamed.put("urgentReason", "urgentReason");
        insert(a.definition(), key, 1, deliveryForm(), renamed);
        a = save(a);
        JsonNode clean = validate(a);
        assertThat(codes(clean, "/dependencies/assets")).isEmpty();
        assertThat(clean.path("publishable").asBoolean()).as(clean.toString()).isTrue();
        var releaseA = publish(a);
        assertThat(releaseA.status()).as(releaseA.toString()).isEqualTo(201);

        // Workflow B copies the same version unchanged and publishes too.
        Draft b = workflow();
        insert(b.definition(), key, 1, deliveryForm(), identity("deliveryDate", "urgent", "urgentReason"));
        b = save(b);
        assertThat(codes(validate(b), "/dependencies/assets")).isEmpty();
        assertThat(publish(b).status()).isEqualTo(201);

        // Where-used lists both, with the version their draft and active release use.
        var usage = api("vic.viewer").get(ASSETS + "/form/{key}/usage", PURCHASING, key);
        assertThat(usage.status()).as(usage.toString()).isEqualTo(200);
        assertThat(usage.body().path("items").size()).isEqualTo(2);
        for (JsonNode used : usage.body().path("items")) {
            assertThat(List.of(a.id().toString(), b.id().toString())).contains(used.path("workflowId").asString());
            assertThat(used.path("draftVersion").asInt()).isEqualTo(1);
            assertThat(used.path("activeReleaseVersion").asInt()).isEqualTo(1);
            assertThat(used.path("stale").asBoolean()).isFalse();
        }

        // A library change never reaches a workflow: version 2 only makes a warning, the copy stays as it was.
        ObjectNode v2 = deliveryForm();
        ((ObjectNode) v2.path("inputSchema").path("properties").path("deliveryDate")).put("title", "Deliver by");
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 1, "content", v2), ASSETS + "/form/{key}/versions",
                PURCHASING, key).status()).isEqualTo(201);
        assertThat(codes(validate(a), "/dependencies/assets")).containsExactly("WF_ASSET_STALE:warning");
        assertThat(a.definition().path("inputSchema").path("properties").path("deliveryDate").path("title").asString())
                .isEqualTo("Needed by");
        assertThat(api("vic.viewer").get(ASSETS + "/form/{key}/usage", PURCHASING, key).body().path("items").get(0)
                .path("stale").asBoolean()).isTrue();

        // Editing a copied question, or pointing a copied rule at another question, is a difference that blocks.
        ((ObjectNode) a.definition().path("inputSchema").path("properties").path("urgentReason")).put("maxLength", 100);
        a = save(a);
        JsonNode edited = validate(a);
        assertThat(codes(edited, "/dependencies/assets")).contains("WF_ASSET_DIVERGED:blocker");
        assertThat(publish(a).code()).isEqualTo("WORKFLOW_INVALID");
        ((ObjectNode) a.definition().path("inputSchema").path("properties").path("urgentReason")).put("maxLength", 500);
        ((ObjectNode) a.definition().path("inputUi").path("visibleWhen").path("urgentReason")).put("fieldRef", "/input/deliveryDate")
                .put("operator", "eq").put("value", "2026-12-01");
        a = save(a);
        assertThat(codes(validate(a), "/dependencies/assets")).contains("WF_ASSET_DIVERGED:blocker");

        // Moving questions between groups or reordering them is presentation, not a difference.
        ((ObjectNode) a.definition().path("inputUi").path("visibleWhen").path("urgentReason")).put("fieldRef", "/input/isUrgent")
                .put("operator", "eq").put("value", true);
        ((ArrayNode) a.definition().path("inputUi").path("order")).removeAll();
        ((ObjectNode) a.definition().path("inputUi").path("layout")).remove("sections");
        a = save(a);
        assertThat(codes(validate(a), "/dependencies/assets")).containsExactly("WF_ASSET_STALE:warning");

        // Archive blocks; restore clears it.
        int revision = api("vic.viewer").get(ASSETS + "/form/{key}", PURCHASING, key).body().path("asset").path("revision").asInt();
        assertThat(api("dana.designer").put(Map.of("archived", true, "expectedRevision", revision), null,
                ASSETS + "/form/{key}/archived", PURCHASING, key).status()).isEqualTo(200);
        assertThat(codes(validate(a), "/dependencies/assets")).contains("WF_ASSET_ARCHIVED:blocker");
        assertThat(api("dana.designer").put(Map.of("archived", false, "expectedRevision", revision + 1), null,
                ASSETS + "/form/{key}/archived", PURCHASING, key).status()).isEqualTo(200);
    }

    @Test
    void copiedQuestionsStillMeetEveryPublishingRuleAndBadLinksAreReported() throws Exception {
        String key = unique("delivery");
        createForm(key, deliveryForm());

        // Skipping "urgent" (the workflow has no such question) leaves the copied rule pointing at nothing: the copy
        // equals the shared version, but the compiler refuses the rule, so reuse cannot bypass validation.
        Draft c = workflow();
        insert(c.definition(), key, 1, deliveryForm(), identity("deliveryDate", "urgentReason"));
        c = save(c);
        JsonNode report = validate(c);
        assertThat(codes(report, "/dependencies/assets")).isEmpty();
        assertThat(codes(report, "/inputUi")).anyMatch(code -> code.endsWith(":blocker"));
        assertThat(report.path("publishable").asBoolean()).isFalse();
        assertThat(publish(c).code()).isEqualTo("WORKFLOW_INVALID");

        // Bad links: wrong target, a question the version does not have, the same form twice, an unknown form.
        Draft d = workflow();
        insert(d.definition(), key, 1, deliveryForm(), identity("deliveryDate", "urgent", "urgentReason"));
        ArrayNode assets = (ArrayNode) d.definition().path("dependencies").path("assets");
        ObjectNode wrongTarget = (ObjectNode) assets.get(0).deepCopy();
        assets.removeAll();
        ObjectNode first = assets.addObject().put("kind", "form").put("key", key).put("version", 1).put("target", "start");
        first.set("fields", wrongTarget.path("fields").deepCopy());
        String other = unique("other");
        createForm(other, deliveryForm());
        ObjectNode unknownQuestion = assets.addObject().put("kind", "form").put("key", other).put("version", 1).put("target", "request");
        unknownQuestion.putObject("fields").put("notThere", "title");
        ObjectNode missing = assets.addObject().put("kind", "form").put("key", unique("nothing")).put("version", 1)
                .put("target", "request");
        missing.putObject("fields").put("deliveryDate", "purpose");
        ObjectNode twice = assets.addObject().put("kind", "form").put("key", other).put("version", 1).put("target", "request");
        twice.putObject("fields").put("urgent", "costCenter");
        d = save(d);
        JsonNode bad = validate(d);
        assertThat(codes(bad, "/dependencies/assets/0")).containsExactly("WF_ASSET_REFERENCE_INVALID:blocker");
        assertThat(codes(bad, "/dependencies/assets/1")).containsExactly("WF_ASSET_REFERENCE_INVALID:blocker");
        assertThat(codes(bad, "/dependencies/assets/2")).containsExactly("WF_ASSET_MISSING:blocker");
        assertThat(codes(bad, "/dependencies/assets/3")).containsExactly("WF_ASSET_REFERENCE_INVALID:blocker");

        // A link without the list of copied questions is refused by the contract.
        assets.removeAll();
        assets.addObject().put("kind", "form").put("key", key).put("version", 1).put("target", "request");
        var saved = api("dana.designer").put(Map.of("definition", d.definition()), "\"r" + d.revision() + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, d.id());
        if (saved.status() == 200) {
            d = new Draft(d.id(), d.revision() + 1, d.definition());
            assertThat(codes(validate(d), "/dependencies/assets/0")).containsExactly("WF_ASSET_REFERENCE_INVALID:blocker");
        }
    }
}
