package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class WorkManagementRulesTest {
    private ObjectNode workflow() { return TestSupport.purchase(); }
    private ObjectNode approval(ObjectNode workflow) { return (ObjectNode)workflow.path("nodes").get(1).path("config"); }
    private ValidationReport report(ObjectNode workflow) { return Compilers.workflowCompiler().validate(TestSupport.text(workflow),TestSupport.defaultContext()); }
    @Test void compilesPinnedSkillsCalendarAndRecurringPolicy() throws Exception {
        var workflow=workflow(); var approval=approval(workflow);
        approval.putArray("requiredSkills").add("finance-approvers");
        approval.put("dueAfterHours",8);
        approval.set("calendar",com.orqix.compiler.internal.json.StrictJson.parse("{\"zone\":\"America/New_York\",\"weekdays\":[1,2,3,4,5],\"start\":\"09:00\",\"end\":\"17:00\",\"holidays\":[\"2026-12-25\"]}"));
        approval.putObject("escalation").put("intervalHours",24).put("maxNotices",3);
        assertThat(report(workflow).publishable()).isTrue();
        var compiled=Compilers.workflowCompiler().compile(TestSupport.text(workflow),TestSupport.defaultContext());
        assertThat(compiled.groups()).contains("managers","finance-approvers");
        assertThat(compiled.bpmnXml()).contains("requiredSkills=\"finance-approvers\"","calendar=","escalation=");
    }
    @Test void rejectsUnknownSkillAndCalendarConfiguration() {
        var workflow=workflow(); var approval=approval(workflow);
        approval.putArray("requiredSkills").add("missing");
        approval.set("calendar",com.orqix.compiler.internal.json.StrictJson.parse("{\"zone\":\"Unknown/Zone\",\"weekdays\":[1],\"start\":\"09:00\",\"end\":\"17:00\"}"));
        approval.remove("dueAfterHours");
        approval.putObject("escalation").put("intervalHours",1).put("maxNotices",2);
        assertThat(report(workflow).issues()).extracting(ValidationIssue::code)
                .contains("WF_SKILL_UNBOUND","WF_CALENDAR_INVALID","WF_CALENDAR_DUE_REQUIRED","WF_ESCALATION_DUE_REQUIRED");
    }
    @Test void rejectsInvalidDateEmptyDaysAndTooManyNotices() {
        var workflow=workflow(); var approval=approval(workflow);
        approval.set("calendar",com.orqix.compiler.internal.json.StrictJson.parse("{\"zone\":\"UTC\",\"weekdays\":[1],\"start\":\"09:00\",\"end\":\"17:00\",\"holidays\":[\"2026-02-30\"]}"));
        assertThat(report(workflow).issues()).extracting(ValidationIssue::code).contains("WF_CALENDAR_INVALID");
        approval.putObject("escalation").put("intervalHours",0).put("maxNotices",21);
        assertThat(report(workflow).publishable()).isFalse();
    }
}
