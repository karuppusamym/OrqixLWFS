package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.orqix.shared.problem.ApiException;

class CmmnEventRulesTest {
    private final CmmnSubsetValidator validator=new CmmnSubsetValidator();
    static String source(String name) throws Exception {
        try(var input=CmmnEventRulesTest.class.getResourceAsStream("/cases/"+name+".cmmn")) {
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void validatesStandardRolesOccurrenceAndFiniteSequentialRepetition() throws Exception {
        var events=source("user-events");
        assertThat(validator.validate(events,"eventCase")).containsExactlyInAnyOrder("finance-approvers","managers");
        assertThat(validator.userEventGroups(events)).containsKey("releaseEvent");
        assertThat(validator.userEventGroups(events).get("releaseEvent")).containsExactly("finance-approvers");
        assertThat(validator.validate(source("repeated-human-task"),"repeatCase")).containsExactly("managers");
    }
    @Test void refusesMissingOrUnresolvedRolesAndWrongListenerEvents() throws Exception {
        var xml=source("user-events");
        for(var invalid:new String[]{xml.replace("authorizedRoleRefs=\"financeRole\"",""),
                xml.replace("authorizedRoleRefs=\"financeRole\"","authorizedRoleRefs=\"reviewTask\""),
                xml.replace("name=\"finance-approvers\"","name=\"bad.group\""),
                xml.replace("<standardEvent>occur</standardEvent>","<standardEvent>complete</standardEvent>"),
                xml.replace("name=\"Release review\" definitionRef=\"releaseEvent\"/>","name=\"Release review\" definitionRef=\"releaseEvent\"><entryCriterion sentryRef=\"released\"/></planItem>"),
                xml.replace("name=\"Release review\" definitionRef=\"releaseEvent\"/>","name=\"Release review\" definitionRef=\"releaseEvent\"><itemControl><requiredRule/></itemControl></planItem>")})
            assertThatThrownBy(()->validator.validate(invalid,"eventCase")).isInstanceOf(ApiException.class);
    }
    @Test void refusesUnboundedOrExecutableRepetitionAndUnsafeDefinitionOverrides() throws Exception {
        var xml=source("repeated-human-task");
        for(var condition:new String[]{"${true}","${repetitionCounter &lt; 31}","${bean.run()}","${repetitionCounter++ &lt; 3}","${repetitionCounter &lt; 0}"})
            assertThatThrownBy(()->validator.validate(xml.replace("${repetitionCounter &lt; 3}",condition),"repeatCase")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->validator.validate(xml.replace("autoComplete=\"false\"","autoComplete=\"true\""),"repeatCase")).isInstanceOf(ApiException.class);
        var badDefault=xml.replace("<repetitionRule><condition>${repetitionCounter &lt; 3}</condition></repetitionRule>","")
                .replace("<humanTask id=\"reviewTask\" name=\"Review evidence\" flowable:candidateGroups=\"managers\"/>",
                        "<milestone id=\"reviewTask\"><defaultControl><repetitionRule><condition>${repetitionCounter &lt; 3}</condition></repetitionRule></defaultControl></milestone>");
        assertThatThrownBy(()->validator.validate(badDefault,"repeatCase")).isInstanceOf(ApiException.class);
    }
}
