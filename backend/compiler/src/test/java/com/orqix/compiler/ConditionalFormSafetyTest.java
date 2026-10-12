package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import com.orqix.compiler.internal.json.StrictJson;

/** Public validation regressions: a conditionally collected value must never reach an unguarded external call. */
class ConditionalFormSafetyTest {
    private static final String OPS="{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"OPS\"}";
    private ObjectNode workflow(String guard) {
        ObjectNode d=TestSupport.purchase();
        var nodes=StrictJson.parse("""
                [{"id":"start","type":"start","label":"Request submitted","config":{}},
                 {"id":"branch","type":"branch","label":"Check eligibility","config":{}},
                 {"id":"end","type":"end","label":"Request completed","config":{"businessOutcome":"Completed","outcomeKind":"success"}},
                 {"id":"skip","type":"end","label":"Request skipped","config":{"businessOutcome":"Skipped","outcomeKind":"success"}}]
                """);
        var array=(tools.jackson.databind.node.ArrayNode)nodes;
        TestSupport.purchase().path("nodes").forEach(n->{if("procure".equals(n.path("id").asString()))array.add(n.deepCopy());});
        d.set("nodes",array);
        d.set("transitions",StrictJson.parse("""
                [{"id":"t1","from":"start","to":"branch","on":"next"},
                 {"id":"t2","from":"branch","to":"procure","on":"condition","condition":%s},
                 {"id":"t3","from":"branch","to":"skip","on":"default"},
                 {"id":"t4","from":"procure","to":"end","on":"next"}]
                """.formatted(guard)));
        d.set("decisions",StrictJson.parse("[]"));d.set("outputMapping",StrictJson.parse("{}"));
        d.set("outputSchema",StrictJson.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));d.remove("uiLayout");
        return d;
    }
    private ObjectNode ui(ObjectNode d) { return (ObjectNode)d.path("inputUi"); }
    private List<String> blockers(ObjectNode d) {
        return Compilers.workflowCompiler().validate(TestSupport.text(d),TestSupport.defaultContext()).issues().stream()
                .filter(i->i.severity()==Severity.BLOCKER).map(ValidationIssue::code).toList();
    }
    @Test void rejectsAnExternalCallWhoseFormValueIsHiddenOnItsPath() {
        var d=workflow("{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"IT\"}");
        ui(d).putObject("visibleWhen").set("amount",StrictJson.parse(OPS));
        assertThat(blockers(d)).contains("WF_FIELD_MAYBE_ABSENT");
    }
    @Test void acceptsARequiredVisibleFieldAfterTheSameGuard() {
        var d=workflow(OPS);ui(d).putObject("visibleWhen").set("amount",StrictJson.parse(OPS));
        assertThat(blockers(d)).isEmpty();
    }
    @Test void requiresConditionalRequirednessForAnOtherwiseOptionalVisibleField() {
        var d=workflow(OPS);ui(d).putObject("visibleWhen").set("amount",StrictJson.parse(OPS));
        var required=(tools.jackson.databind.node.ArrayNode)d.path("inputSchema").path("required");
        for(int i=required.size()-1;i>=0;i--)if("amount".equals(required.get(i).asString()))required.remove(i);
        assertThat(blockers(d)).contains("WF_FIELD_MAYBE_ABSENT");
        ui(d).putObject("requiredWhen").set("amount",StrictJson.parse(OPS));
        assertThat(blockers(d)).isEmpty();
    }
    @Test void sectionVisibilityRequiresTheSameBranchGuard() {
        var d=workflow("{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"IT\"}");
        var section=ui(d).putObject("layout").put("helpText","above").putArray("sections").addObject()
                .put("id","financial").put("title","Financial details").put("columns",2);
        section.putArray("fields").add("amount");section.set("visibleWhen",StrictJson.parse(OPS));
        assertThat(blockers(d)).contains("WF_FIELD_MAYBE_ABSENT");
        ((ObjectNode)d.path("transitions").get(1)).set("condition",StrictJson.parse(OPS));
        assertThat(blockers(d)).isEmpty();
    }
    @Test void anAllGuardProvesAllVisibilityComparisonsButAnAnyRouteDoesNot() {
        String second="{\"fieldRef\":\"/input/title\",\"operator\":\"ne\",\"value\":\"Forbidden\"}";
        String all="{\"all\":["+OPS+","+second+"]}";
        var d=workflow(all);ui(d).putObject("visibleWhen").set("amount",StrictJson.parse(all));
        assertThat(blockers(d)).isEmpty();
        ((ObjectNode)d.path("transitions").get(1)).set("condition",StrictJson.parse("{\"any\":["+OPS+","+second+"]}"));
        assertThat(blockers(d)).contains("WF_FIELD_MAYBE_ABSENT");
    }
    @Test void eitherSatisfiedAlternativeProvesAnAnyVisibilityCondition() {
        var d=workflow(OPS);ui(d).putObject("visibleWhen").set("amount",StrictJson.parse("{\"any\":["+OPS+",{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"IT\"}]}"));
        assertThat(blockers(d)).isEmpty();
    }
}
