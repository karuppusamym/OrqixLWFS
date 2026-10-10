package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import com.orqix.migration.camunda.*;
import com.orqix.migration.pega.PegaSource;
import com.orqix.shared.json.Json;
import tools.jackson.databind.json.JsonMapper;

class MigrationSafetyTest {
    @Test void pegaMappingRequiresAReviewedVersionAndConsistentFileProvenance() throws Exception {
        var json = new Json(JsonMapper.builder().build());
        var source = PegaSource.archive(archive("rules.xml", "synthetic".getBytes(StandardCharsets.UTF_8)));
        var pkg = json.object(); pkg.put("format", "orqix-pega-mapping-0.1"); pkg.put("sourceArchiveHash", source.sourceHash());
        pkg.putObject("definition").putArray("nodes").addObject().put("id", "start");
        var mapping = pkg.putObject("fileMappings").putObject("rules.xml");
        mapping.put("disposition", "mapped"); mapping.putArray("targetNodeIds").add("start"); mapping.put("explanation", "Reviewed synthetic mapping.");
        pkg.put("sourceVersion", "ENTER-PEGA-VERSION");
        assertThatThrownBy(() -> PegaSource.mapping(pkg, source, true)).isInstanceOf(SourceException.class);
        pkg.put("sourceVersion", 24);
        assertThatThrownBy(() -> PegaSource.mapping(pkg, source, true)).isInstanceOf(SourceException.class);
        pkg.put("sourceVersion", "synthetic-24");
        assertThat(PegaSource.mapping(pkg, source, true).publishable()).isTrue();
        mapping.put("disposition", "excluded");
        assertThatThrownBy(() -> PegaSource.mapping(pkg, source, true)).isInstanceOf(SourceException.class).hasMessageContaining("Excluded files");
    }
    @Test void pegaXmlInventoryReportsRuleIdentityDependenciesAndSuggestedMapping() throws Exception {
        String xml = "<pagedata><pxObjClass>Rule-Obj-Flow</pxObjClass><pyRuleName>Purchase</pyRuleName><pyRuleSet>Procurement</pyRuleSet><pyRuleSetVersion>01-02-03</pyRuleSetVersion><pySubFlowName>Compliance</pySubFlowName></pagedata>";
        var report = PegaSource.archive(archive("rules.xml", xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(report.issues().get(0).explanation()).contains("Rule-Obj-Flow", "Purchase", "Procurement", "Compliance", "child workflows");
        assertThat(report.publishable()).isFalse();
    }
    @Test void importedParallelTimersAndMappedChildKeepExplicitSemantics() {
        String xml = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
             <process id="pattern" isExecutable="true"><startEvent id="start"/><parallelGateway id="split"/>
              <intermediateCatchEvent id="left"><timerEventDefinition><timeDuration>PT1M</timeDuration></timerEventDefinition></intermediateCatchEvent>
              <intermediateCatchEvent id="right"><timerEventDefinition><timeDuration>PT2M</timeDuration></timerEventDefinition></intermediateCatchEvent>
              <parallelGateway id="join"/><callActivity id="child" calledElement="Purchase"/><endEvent id="end"/>
              <sequenceFlow id="a" sourceRef="start" targetRef="split"/><sequenceFlow id="b" sourceRef="split" targetRef="left"/>
              <sequenceFlow id="c" sourceRef="split" targetRef="right"/><sequenceFlow id="d" sourceRef="left" targetRef="join"/>
              <sequenceFlow id="e" sourceRef="right" targetRef="join"/><sequenceFlow id="f" sourceRef="join" targetRef="child"/>
              <sequenceFlow id="g" sourceRef="child" targetRef="end"/>
             </process></definitions>
            """;
        var json = new Json(JsonMapper.builder().build());
        var analysis = Analyzer.analyze(xml, Limits.DEFAULT);
        var request = json.parse("{\"workflowKey\":\"imported-pattern\",\"name\":\"Imported patterns\",\"acknowledgeInfo\":true}");
        assertThat(Converter.convert(analysis, request, json, java.util.Map.of()).definition()).isNull();
        ((tools.jackson.databind.node.ObjectNode) request).putObject("childWorkflowMappings").putObject("child")
            .put("workflowRef", "purchase-request").putObject("inputMapping");
        ((tools.jackson.databind.node.ObjectNode) request.path("childWorkflowMappings").path("child")).putObject("resultMapping");
        var converted = Converter.convert(analysis, request, json, java.util.Map.of());
        assertThat(converted.report().publishable()).isTrue();
        assertThat(converted.definition().path("nodes")).anySatisfy(n -> {
            assertThat(n.path("type").asString()).isEqualTo("parallelSplit");
            assertThat(n.path("config").path("joinRef").asString()).isEqualTo("join");
        });
        assertThat(converted.definition().path("nodes")).anyMatch(n -> n.path("type").asString().equals("wait"));
        assertThat(converted.definition().path("nodes")).anyMatch(n -> n.path("config").path("workflowRef").asString().equals("purchase-request"));
    }
    private byte[] archive(String name,byte[] content) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry(); }
        return bytes.toByteArray();
    }
    @Test void rejectsTraversalAndOversizedExpandedArchiveEntries() throws Exception {
        byte[] traversal=archive("../rules.xml",new byte[0]);
        assertThatThrownBy(()->PegaSource.archive(traversal)).isInstanceOf(SourceException.class).hasMessageContaining("Unsafe");
        byte[] bomb=archive("rules.xml",new byte[1_048_577]);
        assertThatThrownBy(()->PegaSource.archive(bomb)).isInstanceOf(SourceException.class).hasMessageContaining("extraction limits");
        assertThatThrownBy(()->PegaSource.archive("not a zip".getBytes(StandardCharsets.UTF_8))).isInstanceOf(SourceException.class);
    }
    @Test void unsupportedSourceBehaviorCannotBeAcknowledgedIntoExecutableSupport() {
        String xml="""
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
             <process id="unsupported" isExecutable="true"><startEvent id="start"/><scriptTask id="script"/><endEvent id="end"/>
              <sequenceFlow id="first" sourceRef="start" targetRef="script"/><sequenceFlow id="last" sourceRef="script" targetRef="end"/>
             </process></definitions>
            """;
        var a=Analyzer.analyze(xml,Limits.DEFAULT);
        assertThat(a.items().stream().map(Analysis.Item::issue)).anyMatch(i->i.reasonCode().equals("TASK_TYPE_NOT_SUPPORTED"));
        assertThat(Converter.report(a,a.items().stream().map(Analysis.Item::issue).toList()).publishable()).isFalse();
    }
    @Test void rejectsXmlEntitiesAndEnforcesXmlDepthAndElementLimits() {
        assertThatThrownBy(()->SafeXml.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'http://localhost/'>]><x>&x;</x>",Limits.DEFAULT)).isInstanceOf(SourceException.class);
        assertThatThrownBy(()->SafeXml.parse("<a><b><c/></b></a>",new Limits(1024,10,2))).isInstanceOf(SourceException.class);
        assertThatThrownBy(()->SafeXml.parse("<a><b/><c/></a>",new Limits(1024,2,10))).isInstanceOf(SourceException.class);
    }
}
