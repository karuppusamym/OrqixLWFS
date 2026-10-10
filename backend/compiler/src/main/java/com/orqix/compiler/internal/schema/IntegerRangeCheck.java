package com.orqix.compiler.internal.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.schema.SchemaValidation.Problem;

import tools.jackson.databind.JsonNode;

/**
 * The schema bounds some integers only from below (versions, text lengths). The typed model stores them as Java
 * {@code int}, so values beyond that range are reported as schema problems instead of failing later.
 */
final class IntegerRangeCheck {

    private IntegerRangeCheck() {
    }

    static List<Problem> check(JsonNode doc) {
        List<Problem> problems = new ArrayList<>();
        JsonNode decisions = doc.path("decisions");
        for (int i = 0; i < decisions.size(); i++) {
            check(decisions.get(i).get("version"), "/decisions/" + i + "/version", problems);
        }
        JsonNode connections = doc.path("dependencies").path("connections");
        for (int i = 0; i < connections.size(); i++) {
            check(connections.get(i).get("definitionVersion"),
                    "/dependencies/connections/" + i + "/definitionVersion", problems);
        }
        for (String form : List.of("inputSchema", "outputSchema")) {
            for (Map.Entry<String, JsonNode> e : doc.path(form).path("properties").properties()) {
                String base = JsonPointers.of(form, "properties", e.getKey());
                check(e.getValue().get("minLength"), base + "/minLength", problems);
                check(e.getValue().path("items").get("minLength"), base + "/items/minLength", problems);
            }
        }
        return problems;
    }

    private static void check(JsonNode value, String path, List<Problem> problems) {
        if (value != null && value.isNumber() && !value.canConvertToInt()) {
            problems.add(new Problem(path, "This number is too large."));
        }
    }
}
