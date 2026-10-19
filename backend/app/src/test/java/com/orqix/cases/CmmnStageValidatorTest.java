package com.orqix.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.orqix.shared.problem.ApiException;

class CmmnStageValidatorTest {
    private final CmmnSubsetValidator validator = new CmmnSubsetValidator();

    static String source() throws Exception {
        try (var input = CmmnStageValidatorTest.class.getResourceAsStream("/cases/nested-stages.cmmn")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void acceptsNestedStagesExitCriteriaAndMilestoneOccurrence() throws Exception {
        assertThat(validator.validate(source(), "nestedCase")).containsExactly("managers");
        assertThat(validator.validate(source().replace("<stage id=\"phaseStage\"",
                "<stage autoComplete=\"true\" id=\"phaseStage\""), "nestedCase")).containsExactly("managers");
    }

    @Test void rejectsReferencesThatCrossStageScopes() throws Exception {
        for (String invalid : new String[] {
                source().replace("sourceRef=\"collect\"", "sourceRef=\"phase\""),
                source().replace("sentryRef=\"collected\"", "sentryRef=\"phaseCompleted\""),
                source().replace("definitionRef=\"alternateTask\"", "definitionRef=\"decideTask\"") }) {
            assertThatThrownBy(() -> validator.validate(invalid, "nestedCase")).isInstanceOf(ApiException.class);
        }
    }

    @Test void rejectsDynamicExtensionsAndRepeatedStageInstantiation() throws Exception {
        for (String invalid : new String[] {
                source().replace("<stage id=\"phaseStage\"", "<stage autoComplete=\"${true}\" id=\"phaseStage\""),
                source().replace("<stage id=\"phaseStage\"", "<stage flowable:class=\"evil.Class\" id=\"phaseStage\""),
                source().replace("<planItem id=\"phase\"", "<planItem id=\"extraPhase\" definitionRef=\"phaseStage\"/><planItem id=\"phase\""),
                source().replace("<standardEvent>occur</standardEvent>", "<standardEvent>complete</standardEvent>") }) {
            assertThatThrownBy(() -> validator.validate(invalid, "nestedCase")).isInstanceOf(ApiException.class);
        }
    }
}
