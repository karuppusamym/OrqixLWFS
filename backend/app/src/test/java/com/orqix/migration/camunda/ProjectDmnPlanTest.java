package com.orqix.migration.camunda;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.orqix.shared.json.Json;
import tools.jackson.databind.json.JsonMapper;

class ProjectDmnPlanTest {
    private static final Json JSON=new Json(JsonMapper.builder().build());
    private String dmn() throws Exception { return Files.readString(Path.of("../../examples/migration/dmn-approval-level.dmn")); }
    private String bpmn(String namespace,String ref) {
        String call=namespace.equals("7")?"<businessRuleTask id='rules' c:decisionRef='"+ref+"'/>":"<businessRuleTask id='rules'><extensionElements><z:calledDecision decisionId='"+ref+"'/></extensionElements></businessRuleTask>";
        return "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL' xmlns:c='http://camunda.org/schema/1.0/bpmn' xmlns:z='http://camunda.org/schema/zeebe/1.0' targetNamespace='urn:test'><process id='parent' isExecutable='true'><startEvent id='start'/>"+call+"<endEvent id='end'/><sequenceFlow id='f1' sourceRef='start' targetRef='rules'/><sequenceFlow id='f2' sourceRef='rules' targetRef='end'/></process></definitions>";
    }
    private ProjectAnalyzer.Source source(String name,String content) { return new ProjectAnalyzer.Source(name,content,null); }
    @Test void xmlNamespacesIdentifyDmnAndOrderItBeforeBothCamundaCallers() throws Exception {
        for(String version:List.of("7","8")) {
            var plan=ProjectAnalyzer.analyze(List.of(source("parent.xml",bpmn(version,"approvalLevel")),source("rules.xml",dmn())),JSON);
            assertThat(plan.issues()).isEmpty(); assertThat(plan.conversionOrder()).containsExactly("dmn:rules.xml","bpmn:parent");
            assertThat(plan.files().get(0).decisionDependencies()).containsEntry("rules","approvalLevel");
            assertThat(plan.files().get(1).decisionIds()).containsExactly("approvalLevel"); assertThat(plan.files().get(1).sourceFormat()).isEqualTo("dmn");
        }
    }
    @Test void missingAndDuplicateDecisionsAreNotGuessed() throws Exception {
        var missing=ProjectAnalyzer.analyze(List.of(source("parent.bpmn",bpmn("7","absent"))),JSON);
        assertThat(missing.issues()).extracting(ProjectAnalyzer.Issue::code).containsExactly("PROJECT_MISSING_DECISION");
        var duplicate=ProjectAnalyzer.analyze(List.of(source("parent.bpmn",bpmn("7","approvalLevel")),source("one.dmn",dmn()),source("two.dmn",dmn())),JSON);
        assertThat(duplicate.issues()).extracting(ProjectAnalyzer.Issue::code).containsExactly("PROJECT_DUPLICATE_DECISION"); assertThat(duplicate.conversionOrder()).isEmpty();
    }
    @Test void multiDecisionFilesRequireSeparateReviewRatherThanSelectingAnArbitraryDecision() throws Exception {
        String xml=dmn(); String decision=xml.substring(xml.indexOf("  <decision "),xml.indexOf("  </decision>")+"  </decision>".length());
        var plan=ProjectAnalyzer.analyze(List.of(source("multi.dmn",xml.replace("</definitions>",decision.replace("approvalLevel","otherLevel")+"</definitions>"))),JSON);
        assertThat(plan.issues()).extracting(ProjectAnalyzer.Issue::code).contains("PROJECT_DMN_SELECTION_REQUIRED"); assertThat(plan.conversionOrder()).isEmpty();
    }
    @Test void unsupportedDmnSemanticsRemainVisibleButBlockProjectConversion() throws Exception {
        var plan=ProjectAnalyzer.analyze(List.of(source("bad.dmn",Files.readString(Path.of("../../examples/migration/dmn-unsupported.dmn")))),JSON);
        assertThat(plan.files().get(0).sourceFormat()).isEqualTo("dmn"); assertThat(plan.issues()).extracting(ProjectAnalyzer.Issue::code).contains("PROJECT_DMN_NOT_CONVERTIBLE"); assertThat(plan.conversionOrder()).isEmpty();
    }
    @Test void wrongExplicitFormatAndUnsafeXmlAreRejected() throws Exception {
        assertThatThrownBy(()->ProjectAnalyzer.analyze(List.of(new ProjectAnalyzer.Source("rules.xml",dmn(),"bpmn")),JSON)).isInstanceOf(SourceException.class);
        assertThatThrownBy(()->ProjectAnalyzer.analyze(List.of(source("unsafe.dmn","<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>")),JSON)).isInstanceOf(SourceException.class);
    }
}
