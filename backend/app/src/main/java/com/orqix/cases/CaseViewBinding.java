package com.orqix.cases;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import tools.jackson.databind.JsonNode;

/** Presentation only: data types, constraints and current operation grants come from the case service. */
final class CaseViewBinding {
    private CaseViewBinding() {}
    static void validate(List<CaseDataModel.Field> fields, JsonNode view) {
        if (!"case".equals(view.path("kind").asString())) fail("Choose a case view.");
        var actions = new HashSet<String>();
        for (var action : view.path("actions")) {
            String type = action.path("type").asString();
            if (!Set.of("saveData", "navigate").contains(type) || !actions.add(type)) fail("A case view supports one Save data action and optionally one navigation action.");
        }
        if (!actions.contains("saveData")) fail("The case view needs a Save data action.");
        var bound = new HashSet<String>();
        for (var section : view.path("sections")) for (var control : section.path("fields")) {
            String binding = control.path("binding").asString();
            if (!binding.startsWith("case.")) fail("Case view controls must bind to case data.");
            String key = binding.substring(5);
            var field = fields.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
            if (field == null || !bound.add(key)) fail("Every case field must have exactly one view binding.");
            String expected = field.type().equals("integer") ? "number" : field.type();
            if (!expected.equals(control.path("type").asString()) || Boolean.TRUE.equals(field.required()) != control.path("required").asBoolean(false))
                fail("The case view field types and required flags must match the case data definition.");
            if (expected.equals("choice")) {
                var options = new java.util.ArrayList<String>();
                for (var option : control.path("options")) {
                    if (!option.path("value").equals(option.path("label"))) fail("Case choice labels must match their values.");
                    options.add(option.path("value").asString());
                }
                if (!options.equals(field.options())) fail("Case view choices must match the case definition in order.");
            }
        }
        if (bound.size() != fields.size()) fail("Every case field must have exactly one view binding.");
    }
    private static void fail(String message) { throw new ApiException(ProblemCode.VALIDATION_FAILED, message); }
}
