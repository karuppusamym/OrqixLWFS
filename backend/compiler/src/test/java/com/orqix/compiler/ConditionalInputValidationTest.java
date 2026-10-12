package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Public pinned-form validation, including malicious submissions that retain invisible request values. */
class ConditionalInputValidationTest {
    private static final String SCHEMA="""
        {"type":"object","additionalProperties":false,"required":["needsReview","priority","detail"],"properties":{
          "needsReview":{"type":"boolean","title":"Needs review"},
          "priority":{"type":"integer","title":"Priority","minimum":0,"maximum":100},
          "detail":{"type":"string","title":"Review detail","minLength":1,"maxLength":20},
          "extra":{"type":"string","title":"Extra detail","maxLength":20}}}
        """;
    private static final String FLAG="{\"fieldRef\":\"/input/needsReview\",\"operator\":\"eq\",\"value\":true}";
    private static final String HIGH="{\"fieldRef\":\"/input/priority\",\"operator\":\"gte\",\"value\":10}";
    private static final String UI="{\"visibleWhen\":{\"detail\":"+FLAG+"},\"requiredWhen\":{\"extra\":"+FLAG+"},\"layout\":{\"sections\":[{\"id\":\"extraSection\",\"title\":\"More detail\",\"fields\":[\"extra\"],\"visibleWhen\":"+HIGH+"}]}}";
    private java.util.List<FormValidator.FieldError> errors(String input) { return Compilers.formValidator().validate(SCHEMA,input,UI); }
    @Test void hiddenRequiredFieldsMayBeAbsentButCannotBeSubmitted() {
        assertThat(errors("{\"needsReview\":false,\"priority\":0}")).isEmpty();
        var errors=errors("{\"needsReview\":false,\"priority\":0,\"detail\":\"Hidden sensitive value\"}");
        assertThat(errors).extracting(FormValidator.FieldError::pointer).containsExactly("/detail");
        assertThat(errors).extracting(FormValidator.FieldError::code).containsExactly("hidden");
    }
    @Test void conditionallyRequiredFieldsAreRequiredOnlyWhileVisible() {
        assertThat(errors("{\"needsReview\":true,\"priority\":0,\"detail\":\"Visible\"}")).isEmpty();
        assertThat(errors("{\"needsReview\":true,\"priority\":10,\"detail\":\"Visible\"}"))
                .extracting(FormValidator.FieldError::pointer).containsExactly("/extra");
        assertThat(errors("{\"needsReview\":true,\"priority\":10,\"detail\":\"Visible\",\"extra\":\"Additional\"}")).isEmpty();
    }
    @Test void retainedSectionValuesAndVisibleInvalidValuesAreRejected() {
        assertThat(errors("{\"needsReview\":true,\"priority\":0,\"detail\":\"Visible\",\"extra\":\"Hidden section\"}"))
                .extracting(FormValidator.FieldError::code).containsExactly("hidden");
        assertThat(errors("{\"needsReview\":true,\"priority\":0,\"detail\":false}"))
                .extracting(FormValidator.FieldError::code).containsExactly("type");
    }
    @Test void allAndAnyUseSharedTypedComparisonsAndWrongControllerTypesDoNotGrantVisibility() {
        String all="{\"visibleWhen\":{\"detail\":{\"all\":["+FLAG+","+HIGH+"]}}}";
        String any="{\"visibleWhen\":{\"detail\":{\"any\":["+FLAG+","+HIGH+"]}}}";
        var validator=Compilers.formValidator();
        assertThat(validator.validate(SCHEMA,"{\"needsReview\":true,\"priority\":0}",all)).isEmpty();
        assertThat(validator.validate(SCHEMA,"{\"needsReview\":false,\"priority\":10}",any)).extracting(FormValidator.FieldError::code).contains("required");
        assertThat(validator.validate(SCHEMA,"{\"needsReview\":\"true\",\"priority\":0}",UI)).extracting(FormValidator.FieldError::code).containsExactly("type");
    }
    @Test void theLegacyTwoArgumentValidatorStillRequiresItsDeclaredFields() {
        assertThat(Compilers.formValidator().validate(SCHEMA,"{\"needsReview\":false,\"priority\":0}"))
                .extracting(FormValidator.FieldError::pointer).containsExactly("/detail");
    }
}
