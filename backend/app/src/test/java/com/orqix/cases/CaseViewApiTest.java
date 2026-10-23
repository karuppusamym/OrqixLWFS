package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class CaseViewApiTest extends IntegrationTestBase {
    private final List<Map<String,Object>> fields = List.of(
            Map.of("key","summary","label","Summary","type","text","required",true,"maxLength",80),
            Map.of("key","amount","label","Amount","type","number","maximum",100),
            Map.of("key","checked","label","Checked","type","boolean"),
            Map.of("key","priority","label","Priority","type","choice","options",List.of("normal","urgent")));
    private ObjectNode view() {
        var view = json.object().put("schemaVersion",1).put("kind","case");
        view.putArray("actions").addObject().put("id","save").put("type","saveData").put("label","Save reviewed details");
        var controls = view.putArray("sections").addObject().put("id","details").put("label","Shared case details").put("columns",1).putArray("fields");
        for (var field : fields) {
            var control = controls.addObject().put("id",field.get("key").toString()).put("binding","case."+field.get("key"))
                    .put("label","Review "+field.get("key")).put("type",field.get("type").toString()).put("required",Boolean.TRUE.equals(field.get("required")));
            if (field.containsKey("options")) {
                var options = control.putArray("options");
                for (Object option : (List<?>) field.get("options")) options.addObject().put("value",option.toString()).put("label",option.toString());
            }
        }
        return view;
    }
    private UUID publish(ObjectNode view) throws Exception {
        var created=api("dana.designer").post(Map.of("name","Case view "+UUID.randomUUID(),"definition",view),"/workspaces/{ws}/views",SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id=UUID.fromString(created.body().path("id").asString());
        assertThat(api("dana.designer").post(Map.of("expectedRevision",1),"/workspaces/{ws}/views/{id}/publish",SANDBOX,id).status()).isEqualTo(200);
        return id;
    }
    private Response definition(UUID workspace, UUID viewId, int version, String key) throws Exception {
        String xml;
        try (var input=getClass().getResourceAsStream("/cases/manual-required.cmmn")) {
            xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("lifecycleCase",key);
        }
        return api("dana.designer").post(Map.of("key",key,"name","Pinned case","sourceXml",xml,"dataFields",fields,
                "viewRef",Map.of("id",viewId.toString(),"version",version)),"/workspaces/{ws}/cases/definitions",workspace);
    }
    private String key() { return "view"+UUID.randomUUID().toString().replace("-","").substring(0,12); }
    private JsonNode start(JsonNode definition) throws Exception {
        var response=api("rhea.requester").post(Map.of("data",Map.of("summary","Protected value","checked",false,"amount",50)),UUID.randomUUID().toString(),
                "/workspaces/{ws}/cases/definitions/{id}/instances",SANDBOX,definition.path("id").asString());
        assertThat(response.status()).as(response.toString()).isEqualTo(201); return response.body();
    }

    @Test void copiesOneVersionAcrossDefinitionsAndKeepsExistingCasesStableAfterLibraryPublication() throws Exception {
        var original=view(); UUID id=publish(original);
        for (int i=0;i<2;i++) {
            var created=definition(SANDBOX,id,1,key()); assertThat(created.status()).as(created.toString()).isEqualTo(201);
            assertThat(created.body().path("viewBinding").path("definition")).isEqualTo(original);
        }
        var created=definition(SANDBOX,id,1,key()).body(); var instance=start(created);
        var changed=original.deepCopy(); ((ObjectNode) changed.path("sections").get(0)).put("label","New case layout");
        assertThat(api("dana.designer").put(Map.of("name","Changed "+UUID.randomUUID(),"expectedRevision",1,"definition",changed),null,"/workspaces/{ws}/views/{id}/draft",SANDBOX,id).status()).isEqualTo(200);
        assertThat(api("dana.designer").post(Map.of("expectedRevision",2),"/workspaces/{ws}/views/{id}/publish",SANDBOX,id).status()).isEqualTo(200);
        var data=api("mani.manager").get("/workspaces/{ws}/cases/instances/{id}/data",SANDBOX,instance.path("id").asString());
        assertThat(data.status()).as(data.toString()).isEqualTo(200);
        assertThat(data.body().path("viewBinding").path("definition")).isEqualTo(original);
        assertThat(data.body().path("values").path("checked").asBoolean(true)).isFalse();
        assertThat(definition(SANDBOX,id,2,key()).body().path("viewBinding").path("definition")).isEqualTo(changed);
    }

    @Test void refusesForeignMissingVersionsAndIncompatibleTypesWithoutCreatingDefinitions() throws Exception {
        UUID id=publish(view());
        assertThat(definition(PURCHASING,id,1,key()).status()).isEqualTo(422);
        assertThat(definition(SANDBOX,id,99,key()).status()).isEqualTo(422);
        assertThat(definition(SANDBOX,UUID.randomUUID(),1,key()).status()).isEqualTo(422);
        var bad=view(); ((ObjectNode) bad.path("sections").get(0).path("fields").get(0)).put("required",false);
        UUID incompatible=publish(bad); String key=key();
        assertThat(definition(SANDBOX,incompatible,1,key).status()).isEqualTo(422);
        var list=api("dana.designer").get("/workspaces/{ws}/cases/definitions",SANDBOX).body();
        assertThat(list.toString()).doesNotContain(key);
    }

    @Test void copiedViewDoesNotGrantDataReadOrEditAndCannotRelaxDataConstraints() throws Exception {
        UUID id=publish(view()); var created=definition(SANDBOX,id,1,key()); assertThat(created.status()).isEqualTo(201);
        String instance=start(created.body()).path("id").asString();
        String path="/workspaces/"+SANDBOX+"/cases/instances/"+instance+"/data";
        assertThat(api("vic.viewer").get(path).status()).isEqualTo(404);
        var requester=api("rhea.requester").get(path); assertThat(requester.status()).isEqualTo(200);
        assertThat(requester.body().path("canEdit").asBoolean()).isFalse();
        Map<String,Object> change=Map.of("expectedVersion",1,"reason","Checked","values",Map.of("summary","Updated","amount",30,"checked",true));
        assertThat(api("rhea.requester").post(change,UUID.randomUUID().toString(),path).status()).isEqualTo(403);
        assertThat(api("mani.manager").post(Map.of("expectedVersion",1,"reason","Checked","values",Map.of("summary","Updated","amount",101)),UUID.randomUUID().toString(),path).status()).isEqualTo(422);
        String command=UUID.randomUUID().toString();
        assertThat(api("mani.manager").post(change,command,path).status()).isEqualTo(200);
        assertThat(api("mani.manager").post(change,command,path).status()).isEqualTo(200);
        assertThat(api("mani.manager").get(path).body().path("version").asInt()).isEqualTo(2);
    }
}
