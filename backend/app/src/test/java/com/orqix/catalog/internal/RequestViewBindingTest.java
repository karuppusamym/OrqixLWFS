package com.orqix.catalog.internal;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class RequestViewBindingTest extends RuntimeTestBase {
    @Autowired RequestViewCheck check;
    private ObjectNode view() {
        var result = json.object().put("schemaVersion",1).put("kind","request");
        result.putArray("actions").addObject().put("id","submit").put("type","submit").put("label","Send for review");
        var fields = result.putArray("sections").addObject().put("id","details").put("label","Shared details").put("columns",2).putArray("fields");
        var properties = Fixtures.purchaseTemplate().path("inputSchema").path("properties");
        for (String key : properties.propertyNames()) {
            var property = properties.path(key);
            var field = fields.addObject().put("id",key).put("label","Shared " + key).put("binding","request." + key)
                    .put("required",true).put("type",property.has("enum") ? "choice" : property.path("type").asString().equals("number") ? "number" : "text");
            if (property.has("enum")) {
                var options = field.putArray("options");
                property.path("enum").forEach(value -> options.addObject().put("value",value.asString()).put("label",value.asString()));
            }
        }
        return result;
    }
    private UUID publishView(ObjectNode view) throws Exception {
        String base = "/workspaces/{ws}/views";
        var created = api("dana.designer").post(Map.of("name","Shared " + UUID.randomUUID(),"definition",view),base,PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        assertThat(api("paul.publisher").post(Map.of("expectedRevision",1),base + "/{id}/publish",PURCHASING,id).status()).isEqualTo(200);
        return id;
    }
    private void bind(ObjectNode workflow, UUID id, ObjectNode view) {
        var ui = (ObjectNode) workflow.path("inputUi");
        ui.putObject("viewRef").put("id",id.toString()).put("version",1);
        ui.put("submitLabel","Send for review");
        var order = ui.putArray("order"); var widgets = ui.putObject("widgets");
        var section = ui.putObject("layout").putArray("sections").addObject().put("id","details").put("title","Shared details").put("columns",2);
        var fields = section.putArray("fields");
        for (var field : view.path("sections").get(0).path("fields")) {
            String key = field.path("id").asString();
            order.add(key); fields.add(key);
            ((ObjectNode) workflow.path("inputSchema").path("properties").path(key)).put("title",field.path("label").asString());
            String type = field.path("type").asString();
            widgets.put(key,type.equals("choice") ? "select" : type);
        }
    }
    @Test void reusesOnePublishedViewAcrossWorkflowsAndKeepsReleasedFormsPinnedAfterLibraryEdits() throws Exception {
        var view = view(); var id = publishView(view);
        var first = publishPurchase(PURCHASING, workflow -> bind(workflow,id,view));
        var second = publishPurchase(PURCHASING, workflow -> bind(workflow,id,view));
        for (UUID workflow : List.of(first,second)) {
            var form = api("rhea.requester").get("/workspaces/{ws}/workflows/{id}/start-form",PURCHASING,workflow);
            assertThat(form.status()).as(form.toString()).isEqualTo(200);
            assertThat(form.body().path("inputUi").path("viewRef").path("version").asInt()).isEqualTo(1);
            assertThat(form.body().path("inputUi").path("submitLabel").asString()).isEqualTo("Send for review");
        }
        var changed = view.deepCopy();
        ((ObjectNode) changed.path("sections").get(0)).put("label","New library layout");
        assertThat(api("dana.designer").put(Map.of("expectedRevision",1,"name","Changed " + UUID.randomUUID(),"definition",changed),null,
                "/workspaces/{ws}/views/{id}/draft",PURCHASING,id).status()).isEqualTo(200);
        assertThat(api("paul.publisher").post(Map.of("expectedRevision",2),"/workspaces/{ws}/views/{id}/publish",PURCHASING,id).status()).isEqualTo(200);
        var form = api("rhea.requester").get("/workspaces/{ws}/workflows/{id}/start-form",PURCHASING,first).body();
        assertThat(form.path("inputUi").path("layout").path("sections").get(0).path("title").asString()).isEqualTo("Shared details");
        var draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft",PURCHASING,first).body();
        var definition = (ObjectNode) draft.path("definition");
        ((ObjectNode) definition.path("inputUi").path("layout").path("sections").get(0)).put("title","Diverged layout");
        int revision = draft.path("revision").asInt();
        assertThat(api("dana.designer").put(Map.of("definition",definition),"\"r" + revision + "\"",
                "/workspaces/{ws}/workflows/{id}/draft",PURCHASING,first).status()).isEqualTo(200);
        var blocked = api("paul.publisher").post(Map.of("draftRevision",revision + 1,"acknowledgeWarnings",true),
                UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{id}/publish",PURCHASING,first);
        assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        assertThat(blocked.body().toString()).contains(AssetReferenceCheck.DIVERGED);
    }
    @Test void deniesForeignMissingAndDivergedPinsAndRejectsBindingOrActionChanges() throws Exception {
        var view = view(); var id = publishView(view);
        var workflow = (ObjectNode) Fixtures.purchaseTemplate().deepCopy(); bind(workflow,id,view);
        assertThat(check.issues(PURCHASING,workflow)).isEmpty();
        assertThat(check.issues(SANDBOX,workflow)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.MISSING);
        ((ObjectNode) workflow.path("inputSchema").path("properties").path("amount")).put("type","string");
        assertThat(check.issues(PURCHASING,workflow)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.DIVERGED);
        var clean = (ObjectNode) Fixtures.purchaseTemplate().deepCopy(); bind(clean,id,view);
        ((ObjectNode) clean.path("inputUi").path("layout").path("sections").get(0)).put("title","Tampered");
        assertThat(check.issues(PURCHASING,clean)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.DIVERGED);
        var badAction = view.deepCopy(); ((ObjectNode) badAction.path("actions").get(0)).put("type","saveDraft");
        assertThat(check.matches(workflow,badAction)).isNotEmpty();
        var missing = (ObjectNode) Fixtures.purchaseTemplate().deepCopy(); bind(missing,UUID.randomUUID(),view);
        assertThat(check.issues(PURCHASING,missing)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.MISSING);
        ((ObjectNode) missing.path("inputUi")).putNull("viewRef");
        assertThat(check.issues(PURCHASING,missing)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.INVALID);
    }
}
