package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.orqix.compiler.internal.json.StrictJson;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Compiler contract only: runtime delegates must separately implement the generated boundary-error protocol. */
class FailureRoutesCompilerTest {
    private final WorkflowCompiler compiler = Compilers.workflowCompiler();
    private static ObjectNode object(String text) { return (ObjectNode) StrictJson.parse(text); }
    private static ObjectNode end(String id) {
        return object("{\"id\":\""+id+"\",\"type\":\"end\",\"label\":\"Finished "+id+"\",\"config\":{\"businessOutcome\":\""+id+"\",\"outcomeKind\":\"success\"}}");
    }
    private static ObjectNode flow(String id, String from, String to, String route) {
        return object("{\"id\":\""+id+"\",\"from\":\""+from+"\",\"to\":\""+to+"\",\"on\":\""+route+"\"}");
    }
    private static ObjectNode call(String id) {
        // Read the actual fixture call rather than recreating its approved request/result contract.
        for (var node : TestSupport.purchase().path("nodes")) if ("http".equals(node.path("type").asString())) {
            var result = (ObjectNode) node.deepCopy(); result.put("id",id).put("label","Call "+id); return result;
        }
        throw new AssertionError("Purchase fixture needs an HTTP step");
    }
    private ObjectNode sample() {
        var doc = TestSupport.purchase(); doc.put("workflowKey","failure-route-tests"); doc.remove("uiLayout");
        var nodes = doc.putArray("nodes"); nodes.add(object("{\"id\":\"start\",\"type\":\"start\",\"label\":\"Request submitted\",\"config\":{}}"));
        nodes.add(call("place")); nodes.add(end("success")); nodes.add(end("failure"));
        var paths = doc.putArray("transitions"); paths.add(flow("begin","start","place","next")); paths.add(flow("succeeded","place","success","next")); paths.add(flow("failed","place","failure","failed"));
        doc.putArray("decisions");doc.set("outputMapping",object("{}"));doc.set("outputSchema",object("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        return doc;
    }
    private static ObjectNode config(ObjectNode doc, String id) {
        for (var node : doc.path("nodes")) if(id.equals(node.path("id").asString())) return (ObjectNode) node.path("config");
        throw new AssertionError("Missing "+id);
    }
    private List<String> blockers(ObjectNode doc) { return blockers(doc,TestSupport.defaultContext()); }
    private List<String> blockers(ObjectNode doc, CompileContext context) {
        return compiler.validate(TestSupport.text(doc),context).issues().stream().filter(i -> i.severity()==Severity.BLOCKER).map(ValidationIssue::code).toList();
    }
    @Test void supportedHttpFailureGeneratesInterruptingBoundaryAndRoutesFromThatBoundary() throws Exception {
        var doc=sample();assertThat(blockers(doc)).isEmpty();
        var compiled=compiler.compile(TestSupport.text(doc),TestSupport.defaultContext());
        assertThat(compiled.bpmnXml()).contains("boundaryEvent", "id=\"place__failed\"", "attachedToRef=\"place\"", "cancelActivity=\"true\"", "sourceRef=\"place__failed\"", "targetRef=\"failure\"");
        assertThat(compiled.sourceMapJson()).contains("place__failed");
        ((ArrayNode)doc.path("transitions")).remove(2);((ArrayNode)doc.path("nodes")).remove(3);
        assertThat(compiler.compile(TestSupport.text(doc),TestSupport.defaultContext()).bpmnXml()).doesNotContain("boundaryEvent", "place__failed");
    }
    @Test void duplicateFailureRoutesAreBlockersAndSameTargetIsAnExplicitWarning() {
        var doc=sample();((ArrayNode)doc.path("transitions")).add(flow("anotherFailure","place","failure","failed"));
        assertThat(blockers(doc)).contains("WF_FAILURE_ROUTE_COUNT");
        doc=sample();((ObjectNode)doc.path("transitions").get(2)).put("to","success");((ArrayNode)doc.path("nodes")).remove(3);
        assertThat(compiler.validate(TestSupport.text(doc),TestSupport.defaultContext()).issues()).anyMatch(i -> i.code().equals("WF_FAILURE_ROUTE_SAME_TARGET") && i.severity()==Severity.WARNING);
    }
    @ParameterizedTest @CsvSource({"failureKind", "failureHttpStatus", "failureMessage"})
    void failureFactNamesCannotOverrideSuccessfulResultNames(String name) {
        var doc=sample();((ObjectNode)config(doc,"place").path("resultMapping")).put(name,"/id");
        assertThat(blockers(doc)).contains("WF_FAILURE_ROUTE_OUTPUT_CLASH");
    }
    @Test void unsupportedStepKindsCannotHaveFailureRoutesAndUnknownRoutesAreSchemaErrors() {
        var doc=sample();((ObjectNode)doc.path("nodes").get(1)).put("type","notification").set("config",object("{\"channel\":\"inApp\",\"recipient\":\"initiator\",\"message\":\"Done\"}"));
        assertThat(blockers(doc)).contains("WF_FAILURE_ROUTE_KIND");
        doc=sample();((ObjectNode)doc.path("transitions").get(2)).put("on","unknown");
        assertThat(blockers(doc)).isNotEmpty();
    }
    private ObjectNode mappedDocument(ObjectNode doc, String ref, String fromRoute) {
        ((ArrayNode)doc.path("nodes")).add(object("{\"id\":\"receipt\",\"type\":\"generateDocument\",\"label\":\"Record routed facts\",\"config\":{\"format\":\"docx\",\"fileName\":\"Facts\",\"template\":\"Value: {{value}}\",\"inputMapping\":{\"value\":\""+ref+"\"}}}"));
        for(var path:doc.path("transitions")) if(fromRoute.equals(path.path("on").asString())) ((ObjectNode)path).put("to","receipt");
        ((ArrayNode)doc.path("transitions")).add(flow("receiptEnd","receipt",fromRoute.equals("failed")?"failure":"success","next"));
        return doc;
    }
    @ParameterizedTest @CsvSource({"failureKind", "failureHttpStatus", "failureMessage"})
    void everyFailureFactHasAResolvedScalarTypeOnItsFailedPath(String name) {
        assertThat(blockers(mappedDocument(sample(),"/steps/place/"+name,"failed"))).isEmpty();
    }
    @Test void failureFactsAreAvailableOnlyOnFailedRouteAndSuccessfulOutputsOnlyOnNextRoute() {
        assertThat(blockers(mappedDocument(sample(),"/steps/place/failureKind","failed"))).isEmpty();
        assertThat(blockers(mappedDocument(sample(),"/steps/place/failureMessage","next"))).contains("WF_FIELDREF_NOT_GUARANTEED");
        assertThat(blockers(mappedDocument(sample(),"/steps/place/procurementId","next"))).isEmpty();
        assertThat(blockers(mappedDocument(sample(),"/steps/place/procurementId","failed"))).contains("WF_FIELDREF_NOT_GUARANTEED");
    }
    private ObjectNode compensation() {
        var doc=sample();((ArrayNode)doc.path("nodes")).add(call("undo"));config(doc,"undo").put("compensates","place");
        ((ObjectNode)doc.path("transitions").get(1)).put("to","undo");((ArrayNode)doc.path("transitions")).add(flow("undone","undo","success","next"));return doc;
    }
    @Test void explicitEarlierEffectCallMayBeAnnotatedAsCompensatedWithoutImplyingAutomaticUndo() throws Exception {
        var doc=compensation();assertThat(blockers(doc)).isEmpty();
        assertThat(compiler.compile(TestSupport.text(doc),TestSupport.defaultContext()).bpmnXml()).contains("compensates=\"place\"");
    }
    @ParameterizedTest @CsvSource({"missing,WF_COMPENSATES_UNKNOWN", "undo,WF_COMPENSATES_SELF", "start,WF_COMPENSATES_TARGET", "success,WF_COMPENSATES_TARGET"})
    void unknownSelfOrNonCallCompensationTargetsAreRejected(String target,String code) {
        var doc=compensation();config(doc,"undo").put("compensates",target);assertThat(blockers(doc)).contains(code);
    }
    @Test void readOnlyCallsAndCallsThatAreNotGuaranteedEarlierCannotBeCompensated() {
        var doc=compensation();config(doc,"place").put("operation","health").put("timeoutSeconds",5);config(doc,"place").set("inputMapping",object("{}"));config(doc,"place").set("resultMapping",object("{}"));
        assertThat(blockers(doc)).contains("WF_COMPENSATES_TARGET");
        doc=sample();((ArrayNode)doc.path("nodes")).add(call("undo"));config(doc,"place").put("compensates","undo");
        ((ObjectNode)doc.path("transitions").get(1)).put("to","undo");((ArrayNode)doc.path("transitions")).add(flow("undone","undo","success","next"));
        assertThat(blockers(doc)).contains("WF_COMPENSATES_ORDER");
    }
    @Test void repeatedChildrenCannotCombineIterationWithASingleFailureRoute() {
        var doc=sample();((ObjectNode)doc.path("nodes").get(1)).put("type","startWorkflow").set("config",object("{\"workflowRef\":\"purchase-approval\",\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\",\"costCenter\":\"/input/costCenter\",\"purpose\":\"/input/purpose\"},\"resultMapping\":{},\"repeat\":{\"maxIterations\":2,\"untilOutcome\":\"Fulfilled\"}}"));
        assertThat(blockers(doc,TestSupport.onboardingContext())).contains("WF_FAILURE_ROUTE_ITERATION", "WF_FAILURE_ROUTE_CHILD_UNSUPPORTED");
    }
    @Test void singleChildFailureRoutingIsExplicitlyBlockedUntilItsContinuationProtocolExists() {
        var doc=sample();((ObjectNode)doc.path("nodes").get(1)).put("type","startWorkflow").set("config",object("{\"workflowRef\":\"purchase-approval\",\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\",\"costCenter\":\"/input/costCenter\",\"purpose\":\"/input/purpose\"},\"resultMapping\":{}}"));
        assertThat(blockers(doc,TestSupport.onboardingContext())).contains("WF_FAILURE_ROUTE_CHILD_UNSUPPORTED");
    }
    @Test void exhaustedRetriesCannotBeAuthoredAsAnImplementedFailureKind() {
        var doc=sample();((ArrayNode)doc.path("nodes")).add(object("{\"id\":\"classifyFailure\",\"type\":\"branch\",\"label\":\"Classify failure\",\"config\":{}}"));
        ((ObjectNode)doc.path("transitions").get(2)).put("to","classifyFailure");
        ((ArrayNode)doc.path("transitions")).add(object("{\"id\":\"exhausted\",\"from\":\"classifyFailure\",\"to\":\"failure\",\"on\":\"condition\",\"condition\":{\"fieldRef\":\"/steps/place/failureKind\",\"operator\":\"eq\",\"value\":\"retriesExhausted\"}}"));
        ((ArrayNode)doc.path("transitions")).add(flow("otherwise","classifyFailure","failure","default"));
        assertThat(blockers(doc)).contains("WF_LITERAL_NOT_IN_DOMAIN");
    }
}
