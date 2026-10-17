package com.orqix.migration.pega;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.orqix.migration.camunda.*;
import com.orqix.shared.json.Json;
import tools.jackson.databind.json.JsonMapper;

class PegaXpdlTest {
    private static String fixture(String name) throws Exception { return Files.readString(Path.of("../../examples/migration/pega-xpdl/"+name+".xpdl")); }
    @Test void allThreeFixturesAreReadWithoutCanonicalDefinitionsOrMappingPackages() throws Exception {
        for(String name:List.of("small","medium","review-branch")) {
            String source=fixture(name); var model=PegaXpdl.analyze(source);
            assertThat(model.blocked()).as(name).isFalse(); assertThat(model.analysis()).isNotNull();
            assertThat(model.report().sourceSystem()).isEqualTo("pega");
            assertThat(model.report().adapterVersion()).isEqualTo(PegaXpdl.ADAPTER);
            assertThat(model.report().sourceHash()).isEqualTo(Json.sha256Hex(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(model.report().issues()).allSatisfy(issue->assertThat(issue.sourcePath()).startsWith("/Package"));
        }
    }
    @Test void conversionRetainsHashPathsAndRequiresMetadataAcknowledgement() throws Exception {
        var model=PegaXpdl.analyze(fixture("medium")); var json=new Json(JsonMapper.builder().build());
        var request=json.object().put("workflowKey","xpdl-example").put("name","Example").put("acknowledgeInfo",true).put("acknowledgeReview",true);
        var converted=Converter.convert(model.analysis(),request,json,Map.of());
        assertThat(model.report(converted.report(),false).publishable()).isFalse();
        assertThat(model.report(converted.report(),true).publishable()).isTrue();
        assertThat(model.report(converted.report(),true).sourceHash()).isEqualTo(model.sourceHash());
        assertThat(converted.definition().path("nodes").toString()).contains("branch");
    }
    @Test void unsupportedChildrenAttributesExpressionsAndEventsAreNeverSilentlyDropped() throws Exception {
        String source=fixture("small");
        for(String changed:List.of(
            source.replace("<Activities>","<DataFields/><Activities>"),
            source.replace("<Event>","<Event><Timer/>"),
            source.replace("Trigger=\"None\"","Trigger=\"Message\""),
            source.replace("Id=\"start\"","Id=\"start\" custom=\"code\""),
            source.replace("<Activities>","<ExtendedAttributes><ExtendedAttribute Name=\"RunActivity\" Value=\"secret\"/></ExtendedAttributes><Activities>"))) {
            var model=PegaXpdl.analyze(changed); assertThat(model.blocked()).isTrue();
            assertThat(model.analysis()).isNull(); assertThat(model.report().publishable()).isFalse();
            assertThat(model.report().issues()).anyMatch(i->i.severity().equals(Report.BLOCKER));
        }
        var unsupported=PegaXpdl.analyze(fixture("medium").replace("amount &gt;= 5000","@callSomething(amount)"));
        assertThat(unsupported.report().issues()).anyMatch(i->i.severity().equals(Report.BLOCKER));
    }
    @Test void duplicateIdsSingletonsDefaultRoutesVersionsAndProcessesAreRefused() throws Exception {
        String source=fixture("small");
        for(String changed:List.of(source.replace("Id=\"done\"","Id=\"start\""),source.replace("</Event>","<StartEvent/></Event>"),source.replace("<XPDLVersion>2.0</XPDLVersion>","<XPDLVersion>2.1</XPDLVersion>"),source.replace("</WorkflowProcesses>","<WorkflowProcess Id=\"extra\"/></WorkflowProcesses>"))) {
            assertThat(PegaXpdl.analyze(changed).blocked()).isTrue();
        }
        String medium=fixture("medium");
        assertThat(PegaXpdl.analyze(medium.replace("</Transitions>","<Transition Id=\"extra\" From=\"amountRoute\" To=\"low\"><Condition Type=\"OTHERWISE\"/></Transition></Transitions>")).blocked()).isTrue();
    }
    @Test void unsafeXmlAndNativeRulePagesAreNotAcceptedAsXpdl() {
        assertThatThrownBy(()->PegaXpdl.analyze("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>")).isInstanceOf(SourceException.class);
        assertThatThrownBy(()->PegaXpdl.analyze("<pagedata><pxObjClass>Rule-Obj-Flow</pxObjClass></pagedata>")).isInstanceOf(SourceException.class);
    }
    @Test void computedAssignmentsAndNonManualImplementationsAreBlocked() throws Exception {
        assertThat(PegaXpdl.analyze(fixture("review-branch").replace("<Performer>Managers</Performer>","<Performer>.RoutingGroup</Performer>")).blocked()).isTrue();
        assertThat(PegaXpdl.analyze(fixture("review-branch").replace("<No/>","<Tool Id=\"CallJava\"/>")).blocked()).isTrue();
        assertThat(PegaXpdl.analyze(fixture("review-branch").replace("<FinishMode><Manual/></FinishMode>","")).blocked()).isTrue();
    }
    @Test void knownPresentationAndSchemaHintsAreReportedWithoutFetchingOrImportingThem() throws Exception {
        String source=fixture("small").replace("<Package xmlns=", "<Package xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"https://do-not-fetch.invalid/schema\" xmlns=")
            .replace("</Event>","</Event><NodeGraphicsInfos><NodeGraphicsInfo Page=\"1\" Width=\"40\" Height=\"40\"><Coordinates XCoordinate=\"10\" YCoordinate=\"20\"/></NodeGraphicsInfo></NodeGraphicsInfos>");
        var model=PegaXpdl.analyze(source); assertThat(model.blocked()).isFalse();
        assertThat(model.report().issues()).anyMatch(i->i.reasonCode().equals("PEGA_XPDL_PRESENTATION_EXCLUDED"));
        assertThat(PegaXpdl.analyze(source.replace("Width=\"40\"","Width=\"40\" Java=\"doNotRun()\"")).blocked()).isTrue();
    }
}
