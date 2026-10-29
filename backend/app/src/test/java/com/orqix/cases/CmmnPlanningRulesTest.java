package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.orqix.shared.problem.ApiException;

/** Admission rules for discretionary planning, workflow tasks, general repetition and diagram interchange (S110). */
class CmmnPlanningRulesTest {
    private final CmmnSubsetValidator validator = new CmmnSubsetValidator();

    private static String source(String name) throws Exception { return CmmnEventRulesTest.source(name); }

    @Test void admitsPlanningTablesWithAuthorizedRolesAndDiagramInterchange() throws Exception {
        var xml = source("discretionary");
        assertThat(validator.validate(xml, "planCase")).containsExactlyInAnyOrder("managers", "finance-approvers");
        var planners = validator.discretionaryItems(xml);
        assertThat(planners).containsOnlyKeys("expert" + CmmnSubsetValidator.PLANNER);
        assertThat(planners.values().iterator().next().groups()).containsExactly("managers");
        assertThat(validator.userEventGroups(xml).get("expert" + CmmnSubsetValidator.PLANNER)).containsExactly("managers");
        assertThat(validator.requiredPlanItems(xml)).contains("expert");
    }

    @Test void engineModelKeepsStandardIdsAndReleasesDiscretionaryWorkThroughAPlanningEvent() throws Exception {
        var engine = validator.engineXml(source("discretionary"));
        assertThat(engine).doesNotContain("planningTable").doesNotContain("discretionaryItem")
                .contains("id=\"expert\"").contains("definitionRef=\"expertTask\"")
                .contains("expert__planner_item").contains("<standardEvent>occur</standardEvent>")
                .contains("CMMNShape");
        var workflow = validator.engineXml(source("workflow-task").replace("WORKFLOWKEY", "purchase-flow"));
        assertThat(workflow).contains("<task").contains("id=\"purchaseTask\"").contains("isBlocking=\"true\"")
                .doesNotContain("processTask").doesNotContain("externalRef");
        assertThat(validator.workflowTasks(source("workflow-task").replace("WORKFLOWKEY", "purchase-flow")))
                .containsEntry("purchaseTask", "purchase-flow");
    }

    @Test void admitsRepeatedStagesMilestonesAndEntryTriggeredRepetition() throws Exception {
        var xml = source("general-repetition");
        assertThat(validator.validate(xml, "loopCase")).containsExactly("managers");
        assertThat(validator.repeatedPlanItems(xml)).containsExactlyInAnyOrder("round", "check", "checked");
    }

    @Test void refusesPlanningAndWorkflowConstructsOutsideTheExecutableBoundary() throws Exception {
        var planned = source("discretionary");
        var workflow = source("workflow-task").replace("WORKFLOWKEY", "purchase-flow");
        for (var invalid : new String[] {
                planned.replace(" authorizedRoleRefs=\"managerRole\"", ""),
                planned.replace("authorizedRoleRefs=\"managerRole\"", "authorizedRoleRefs=\"intakeTask\""),
                planned.replace("definitionRef=\"expertTask\"", "definitionRef=\"doneMilestone\""),
                planned.replace("definitionRef=\"expertTask\"", "definitionRef=\"intakeTask\""),
                planned.replace("<itemControl><requiredRule/></itemControl>", "<itemControl><repetitionRule><condition>${repetitionCounter &lt; 2}</condition></repetitionRule></itemControl>"),
                planned.replace("<planItem id=\"intake\"", "<planItem id=\"expert__planner\" definitionRef=\"x\"/><planItem id=\"intake\""),
                planned.replace("autoComplete=\"false\"", "autoComplete=\"true\""),
                planned.replace("cmmnElementRef=\"expert\"", "cmmnElementRef=\"missing\""),
                planned.replace("<di:waypoint x=\"314\" y=\"120\"/>", ""),
                planned.replace("<cmmndi:CMMNLabel/></cmmndi:CMMNEdge>", "<cmmndi:CMMNLabel><di:waypoint x=\"1\" y=\"1\"/></cmmndi:CMMNLabel></cmmndi:CMMNEdge>"),
                planned.replace("<dc:Bounds x=\"60\" y=\"80\"", "<dc:Bounds x=\"NaN\" y=\"80\""),
                planned.replace("<cmmndi:CMMNEdge", "<cmmndi:CMMNStyle id=\"s\"/><cmmndi:CMMNEdge"),
                workflow.replace("orqixwf:purchase-flow", "http://example.test/process"),
                workflow.replace("xmlns:orqixwf=\"urn:orqix:workflow\"", "xmlns:orqixwf=\"urn:example:other\""),
                workflow.replace("orqixwf:purchase-flow", "orqixwf:Bad_Key"),
                workflow.replace("isBlocking=\"true\"", "isBlocking=\"false\""),
                workflow.replace("<itemControl><requiredRule/></itemControl>", "<itemControl><requiredRule/><manualActivationRule/></itemControl>"),
                workflow.replace("processRef=\"purchaseProcess\"", "processRef=\"followTask\"") }) {
            assertThatThrownBy(() -> {
                var groups = validator.validate(invalid, invalid.contains("planCase") ? "planCase" : "workflowCase");
                validator.workflowTasks(invalid);
                fail("Accepted: " + groups);
            }).as(invalid).isInstanceOf(ApiException.class);
        }
    }

    @Test void refusesRepetitionThePinnedEngineCannotTerminateOrEvaluate() throws Exception {
        var xml = source("general-repetition");
        for (var invalid : new String[] {
                xml.replace("<entryCriterion id=\"checkedEntry\" sentryRef=\"checkDone\"/>", ""),
                xml.replace("<entryCriterion id=\"checkEntry\" sentryRef=\"roundDone\"/>", "<entryCriterion id=\"checkEntry\" sentryRef=\"roundDone\"/><exitCriterion id=\"checkExit\" sentryRef=\"checkDone\"/>"),
                xml.replace("${repetitionCounter &lt; 2}", "${repetitionCounter &lt; 40}"),
                xml.replace("autoComplete=\"false\"", "autoComplete=\"true\"") }) {
            assertThatThrownBy(() -> validator.validate(invalid, "loopCase")).as(invalid).isInstanceOf(ApiException.class);
        }
    }
}
