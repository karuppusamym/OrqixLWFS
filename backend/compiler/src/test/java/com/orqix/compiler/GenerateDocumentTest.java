package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class GenerateDocumentTest {
    private ObjectNode sample() {
        ObjectNode doc = TestSupport.purchase();
        var nodes = (tools.jackson.databind.node.ArrayNode) doc.get("nodes");
        ObjectNode original = (ObjectNode) nodes.get(1);
        String id = original.path("id").asString();
        original.put("type", "generateDocument");
        original.put("label", "Generate receipt");
        original.set("config", com.orqix.compiler.internal.json.StrictJson.parse("{\"format\":\"pdf\",\"fileName\":\"Receipt\",\"template\":\"Amount: {{amount}}\\nApproved request\",\"inputMapping\":{\"amount\":\"/input/amount\"}}"));
        // Simplify to an acyclic request -> generation -> finish.
        ObjectNode end = (ObjectNode) nodes.get(nodes.size() - 1);
        doc.set("nodes", com.orqix.compiler.internal.json.StrictJson.parse("[" + nodes.get(0) + "," + original + "," + end + "]"));
        doc.set("transitions", com.orqix.compiler.internal.json.StrictJson.parse("[{\"id\":\"t1\",\"from\":\"" + nodes.get(0).path("id").asString() + "\",\"to\":\"" + id + "\",\"on\":\"next\"},{\"id\":\"t2\",\"from\":\"" + id + "\",\"to\":\"" + end.path("id").asString() + "\",\"on\":\"next\"}]"));
        doc.set("decisions", com.orqix.compiler.internal.json.StrictJson.parse("[]"));
        doc.set("dependencies", com.orqix.compiler.internal.json.StrictJson.parse("{\"connections\":[]}"));
        doc.set("outputMapping", com.orqix.compiler.internal.json.StrictJson.parse("{}"));
        doc.set("outputSchema", com.orqix.compiler.internal.json.StrictJson.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        doc.remove("uiLayout");
        return doc;
    }
    @Test void validatesSafeTemplateAndGeneratesAsyncDelegateWithPreservedLineBreaks() throws Exception {
        var compiler = Compilers.workflowCompiler(); var doc = sample();
        var report = compiler.validate(TestSupport.text(doc), TestSupport.defaultContext());
        assertThat(report.issues().stream().filter(i -> i.severity() == Severity.BLOCKER).toList()).isEmpty();
        var compiled = compiler.compile(TestSupport.text(doc), TestSupport.defaultContext());
        assertThat(compiled.bpmnXml()).contains("${orqixGenerateDocumentTask}", "templateBase64=", "async=\"true\"");
    }
    @Test void rejectsUnmappedPlaceholdersAndComplexReferences() {
        var doc = sample(); var config = (ObjectNode) doc.path("nodes").get(1).get("config");
        config.put("template", "{{missing}}");
        var report = Compilers.workflowCompiler().validate(TestSupport.text(doc), TestSupport.defaultContext());
        assertThat(report.issues()).anyMatch(i -> i.code().equals("WF_DOCUMENT_MAPPING"));
        config.put("template", "{{amount + 1}}");
        report = Compilers.workflowCompiler().validate(TestSupport.text(doc), TestSupport.defaultContext());
        assertThat(report.issues()).anyMatch(i -> i.code().equals("WF_DOCUMENT_TEMPLATE"));
    }
}
