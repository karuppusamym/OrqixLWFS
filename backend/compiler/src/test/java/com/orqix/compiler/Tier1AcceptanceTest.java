package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

class Tier1AcceptanceTest {
    private ObjectNode definition() {return (ObjectNode)TestSupport.json("examples/hackathon/tier1-approval.json");}
    private ValidationReport validate(ObjectNode d) {return Compilers.workflowCompiler().validate(TestSupport.text(d), context());}
    private CompileContext context() {
        var base=TestSupport.defaultContext();var groups=new HashMap<>(base.groups());
        for(int i=1;i<=5;i++)groups.put("role"+i,new CompileContext.GroupInfo("role"+i,"Role "+i,1));
        return new CompileContext(false,groups,base.connectionVersions(),base.enabledConnectionBindings());
    }
    private ObjectNode config(ObjectNode d) {return (ObjectNode)d.path("nodes").get(1).path("config");}
    @Test void compilesMinuteCalendarAndStageConfigurationWithoutChangingLegacyOutput() throws Exception {
        var d=definition();assertThat(validate(d).issues()).isEmpty();
        var compiled=Compilers.workflowCompiler().compile(TestSupport.text(d),context());
        assertThat(compiled.bpmnXml()).contains("dueAfterMinutes=\"480\"","synthetic-demo-v1");
    }
    @Test void acceptsTenStatesAndFiveRolesAndRejectsTheNextOne() {
        var d=definition();var nodes=(ArrayNode)d.path("nodes");var edges=(ArrayNode)d.path("transitions");
        ((ObjectNode)edges.get(0)).put("to","notice0");
        for(int i=0;i<6;i++) {
            var n=nodes.addObject().put("id","notice"+i).put("type","notification").put("label","Notice "+i);
            n.putObject("config").put("channel","inApp").put("recipient","initiator").put("message","A status update.");
            edges.addObject().put("id","extra"+i).put("from","notice"+i).put("to",i==5?"manager":"notice"+(i+1)).put("on","next");
        }
        var skills=config(d).putArray("requiredSkills");for(int i=1;i<=4;i++)skills.add("role"+i);
        assertThat(validate(d).publishable()).as(validate(d).issues().toString()).isTrue();
        skills.add("role5");assertThat(validate(d).issues()).extracting(ValidationIssue::code).contains("WF_TIER1_ROLE_LIMIT");
        nodes.add(nodes.get(4).deepCopy());assertThat(validate(d).issues()).extracting(ValidationIssue::code).contains("WF_TIER1_STATE_LIMIT");
    }
    @Test void rejectsMissingControlsUnsafeTemplatesAndAmbiguousDstBoundaries() {
        var d=definition();var cfg=config(d);cfg.remove("calendar");cfg.put("dueAfterHours",8);
        ((ObjectNode)cfg.path("notifications").path("assignment")).put("body","{{secret}}");
        assertThat(validate(d).issues()).extracting(ValidationIssue::code).contains("WF_TIER1_SLA_REQUIRED","WF_SLA_DURATION_CONFLICT","WF_MAIL_TEMPLATE_INVALID");
        d=definition();cfg=config(d);var cal=(ObjectNode)cfg.path("calendar");
        cal.putArray("weekdays").add(7);cal.put("start","01:30").put("end","04:00");
        assertThat(validate(d).issues()).extracting(ValidationIssue::code).contains("WF_TIER1_CALENDAR_INVALID");
    }
    @Test void rejectsParallelAndSelfApprovalInTier1WithoutRestrictingGeneralPlatform() {
        var d=definition();config(d).putObject("multiInstance").put("approvers",2);
        ((ObjectNode)d.path("security")).put("selfApproval","allow");
        assertThat(validate(d).issues()).extracting(ValidationIssue::code).contains("WF_TIER1_CONSTRUCT","WF_TIER1_SELF_APPROVAL");
        d.remove("acceptanceProfile");
        assertThat(validate(d).issues()).extracting(ValidationIssue::code).doesNotContain("WF_TIER1_CONSTRUCT","WF_TIER1_SELF_APPROVAL");
    }
    @Test void schedulesExactHolidayExampleAndSplitIntervalsInBusinessMinutes() {
        var cal=BusinessCalendar.read(config(definition()).path("calendar"));
        assertThat(cal.deadlineMinutes(Instant.parse("2026-10-16T20:00:00Z"),480)).isEqualTo(Instant.parse("2026-10-20T20:00:00Z"));
        var split=new BusinessCalendar(ZoneOffset.UTC,Set.of(1,2,3,4,5),Set.of(),LocalTime.of(9,0),LocalTime.of(17,0),
                List.of(new BusinessCalendar.Window(LocalTime.of(9,0),LocalTime.NOON),new BusinessCalendar.Window(LocalTime.of(13,0),LocalTime.of(17,0))));
        assertThat(split.deadlineMinutes(Instant.parse("2026-10-16T11:30:00Z"),60)).isEqualTo(Instant.parse("2026-10-16T13:30:00Z"));
        assertThatThrownBy(()-> new BusinessCalendar(ZoneOffset.UTC,Set.of(1),Set.of(),LocalTime.MIN,LocalTime.NOON,
                List.of(new BusinessCalendar.Window(LocalTime.of(9,0),LocalTime.NOON),new BusinessCalendar.Window(LocalTime.of(11,0),LocalTime.of(17,0)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rendersValuesLiterallyAndRefusesMailHeaderInjection() {
        var d=definition();var template=config(d).path("notifications").path("assignment");
        assertThat(MailTemplates.render("{{reference}} {{workflow}}",Map.of("reference","$1\\test","workflow","<tag>"))).isEqualTo("$1\\test <tag>");
        ((ObjectNode)template).put("subject","Hello\r\nBcc: attacker@example.invalid");
        assertThatThrownBy(()->MailTemplates.validate(template)).isInstanceOf(IllegalArgumentException.class);
    }
}
