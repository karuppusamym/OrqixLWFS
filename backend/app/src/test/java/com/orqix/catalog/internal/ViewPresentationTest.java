package com.orqix.catalog.internal;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import com.orqix.shared.problem.ApiException;
import tools.jackson.databind.node.ObjectNode;

class ViewPresentationTest extends RuntimeTestBase {
    private ObjectNode view(String kind) {
        var view=json.object().put("schemaVersion",1).put("kind",kind);
        view.putArray("sections").addObject().put("id","details").put("label","Details").put("columns",2)
                .putArray("fields").addObject().put("id","title").put("label","Title").put("type","text")
                .put("binding",kind.equals("case")?"case.title":kind.equals("worklist")?"task.requestTitle":"request.title");
        var actions=view.putArray("actions");
        if(kind.equals("approval")) actions.addObject().put("id","reject").put("label","Reject").put("type","reject");
        String action=kind.equals("case")?"saveData":kind.equals("worklist")?"openTask":kind.equals("approval")?"approve":"submit";
        actions.addObject().put("id","act").put("label","Continue").put("type",action);
        view.putObject("presentation").put("baseLocale","en").put("breakpoint","lg").put("density","compact").put("tone","neutral")
                .putObject("translations").putObject("fr").put("details","Détails").put("title","Titre").put("act","Continuer");
        return view;
    }
    @Test void allViewKindsPublishPresentationAndKeepOldTranslationsImmutable() throws Exception {
        for(var kind:new String[]{"request","approval","case","worklist"}) {
            var original=view(kind);
            var created=api("dana.designer").post(Map.of("name",kind+UUID.randomUUID(),"definition",original),"/workspaces/{ws}/views",PURCHASING);
            assertThat(created.status()).as(created.toString()).isEqualTo(201);String id=created.body().path("id").asString();
            assertThat(api("paul.publisher").post(Map.of("expectedRevision",1),"/workspaces/{ws}/views/{id}/publish",PURCHASING,id).status()).isEqualTo(200);
            var changed=original.deepCopy();((ObjectNode)changed.path("presentation").path("translations").path("fr")).put("title","Titre modifié");
            assertThat(api("dana.designer").put(Map.of("expectedRevision",1,"name","Changed "+kind,"definition",changed),null,"/workspaces/{ws}/views/{id}/draft",PURCHASING,id).status()).isEqualTo(200);
            assertThat(api("vic.viewer").get("/workspaces/{ws}/views/{id}/versions/1",PURCHASING,id).body().path("definition")).isEqualTo(original);
        }
    }
    @Test void refusesUnknownTargetsArbitraryStyleTokensInvalidLanguagesAndMalformedLabels() {
        var original=view("case");
        for(var patch:new String[]{"{\"breakpoint\":\"200px\"}","{\"density\":\"dense\"}","{\"tone\":\"url(https://evil)\"}",
                "{\"css\":\"body{display:none}\"}","{\"baseLocale\":\"EN_us\"}","{\"translations\":{\"en\":{\"title\":\"Title\"}}}",
                "{\"translations\":{\"fr\":{\"unknown\":\"Text\"}}}","{\"translations\":{\"fr\":{\"title\":\" \"}}}",
                "{\"translations\":{\"fr\":{\"title\":2}}}","{\"translations\":[]}"}) {
            var invalid=original.deepCopy();var presentation=(ObjectNode)invalid.path("presentation");
            for(var entry:json.parse(patch).properties())presentation.set(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->ViewDefinition.validate(json,invalid)).isInstanceOf(ApiException.class);
        }
    }
    @Test void translatesChoiceLabelsByStableOptionTargetsWithoutChangingValues() {
        var view=view("case");var field=(ObjectNode)view.path("sections").get(0).path("fields").get(0);
        field.put("type","choice");field.putArray("options").addObject().put("value","urgent").put("label","urgent");
        ((ObjectNode)view.path("presentation").path("translations").path("fr")).put("title.option.0","Urgent en français");
        assertThat(ViewDefinition.validate(json,view)).isEqualTo("case");
        ((ObjectNode)view.path("presentation").path("translations").path("fr")).put("title.option.1","Unknown option");
        assertThatThrownBy(()->ViewDefinition.validate(json,view)).isInstanceOf(ApiException.class);
    }
}
