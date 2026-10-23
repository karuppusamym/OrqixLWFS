package com.orqix.catalog.internal;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.node.ObjectNode;

class WorklistViewsTest extends RuntimeTestBase {
    private ObjectNode view() {
        var root=json.object().put("schemaVersion",1).put("kind","worklist");
        var fields=root.putArray("sections").addObject().put("id","tasks").put("label","Review queue").put("columns",1).putArray("fields");
        fields.addObject().put("id","title").put("label","Work request").put("binding","task.requestTitle").put("type","text");
        fields.addObject().put("id","priority").put("label","Urgency").put("binding","task.priority").put("type","number");
        root.putArray("actions").addObject().put("id","open").put("type","openTask").put("label","Review work");return root;
    }
    private UUID publish(ObjectNode view) throws Exception {
        var created=api("dana.designer").post(Map.of("name","Worklist "+UUID.randomUUID(),"definition",view),"/workspaces/{ws}/views",SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id=UUID.fromString(created.body().path("id").asString());
        assertThat(api("dana.designer").post(Map.of("expectedRevision",1),"/workspaces/{ws}/views/{id}/publish",SANDBOX,id).status()).isEqualTo(200);return id;
    }
    private int revision() throws Exception { return api("dana.designer").get("/workspaces/{ws}/worklist-view",SANDBOX).body().path("revision").asInt(); }
    @Test void publishesWorkspacePresentationWithoutRequiringLibraryReadFromTaskWorkers() throws Exception {
        var source=view(); UUID id=publish(source); int version=revision();
        var assigned=api("dana.designer").put(Map.of("expectedRevision",version,"viewRef",Map.of("id",id.toString(),"version",1)),null,"/workspaces/{ws}/worklist-view",SANDBOX);
        assertThat(assigned.status()).as(assigned.toString()).isEqualTo(200);
        assertThat(assigned.body().path("binding").path("definition")).isEqualTo(source);
        var manager=api("max.manager").get("/workspaces/{ws}/worklist-view",SANDBOX);
        assertThat(manager.status()).as(manager.toString()).isEqualTo(200);
        assertThat(manager.body().path("binding").path("id").asString()).isEqualTo(id.toString());
        assertThat(api("max.manager").get("/workspaces/{ws}/views/{id}",SANDBOX,id).status()).isEqualTo(403);
        assertThat(api("max.manager").put(Map.of("expectedRevision",version+1),null,"/workspaces/{ws}/worklist-view",SANDBOX).status()).isEqualTo(403);
        assertThat(api("dana.designer").put(Map.of("expectedRevision",version),null,"/workspaces/{ws}/worklist-view",SANDBOX).status()).isEqualTo(409);
        var reset=api("dana.designer").put(Map.of("expectedRevision",version+1),null,"/workspaces/{ws}/worklist-view",SANDBOX);
        assertThat(reset.status()).isEqualTo(200);assertThat(reset.body().path("binding").isNull()).isTrue();
    }
    @Test void laterPublicationCannotChangeAssignedSnapshotAndForeignPinsAreRefused() throws Exception {
        var original=view();UUID id=publish(original);int revision=revision();
        assertThat(api("dana.designer").put(Map.of("expectedRevision",revision,"viewRef",Map.of("id",id.toString(),"version",1)),null,"/workspaces/{ws}/worklist-view",SANDBOX).status()).isEqualTo(200);
        var changed=original.deepCopy();((ObjectNode)changed.path("sections").get(0)).put("label","Later queue");
        assertThat(api("dana.designer").put(Map.of("expectedRevision",1,"name","Changed "+UUID.randomUUID(),"definition",changed),null,"/workspaces/{ws}/views/{id}/draft",SANDBOX,id).status()).isEqualTo(200);
        assertThat(api("dana.designer").post(Map.of("expectedRevision",2),"/workspaces/{ws}/views/{id}/publish",SANDBOX,id).status()).isEqualTo(200);
        assertThat(api("max.manager").get("/workspaces/{ws}/worklist-view",SANDBOX).body().path("binding").path("definition")).isEqualTo(original);
        assertThat(api("paul.publisher").put(Map.of("expectedRevision",0,"viewRef",Map.of("id",id.toString(),"version",1)),null,"/workspaces/{ws}/worklist-view",PURCHASING).status()).isEqualTo(422);
        assertThat(api("dana.designer").put(Map.of("expectedRevision",revision+1),null,"/workspaces/{ws}/worklist-view",SANDBOX).status()).isEqualTo(200);
    }
    @Test void refusesArbitraryBindingsActionsAndSensitiveRequestData() throws Exception {
        for(String binding:new String[]{"request.secret","task.input","task.assignee.email"}) {
            var invalid=view();((ObjectNode)invalid.path("sections").get(0).path("fields").get(0)).put("binding",binding);
            assertThat(api("dana.designer").post(Map.of("name","Invalid "+UUID.randomUUID(),"definition",invalid),"/workspaces/{ws}/views",SANDBOX).status()).isEqualTo(422);
        }
        var invalid=view();((ObjectNode)invalid.path("actions").get(0)).put("type","approve");
        assertThat(api("dana.designer").post(Map.of("name","Invalid "+UUID.randomUUID(),"definition",invalid),"/workspaces/{ws}/views",SANDBOX).status()).isEqualTo(422);
    }
}
