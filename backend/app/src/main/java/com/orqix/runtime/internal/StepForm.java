package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * The step form of an approval in its pinned release (S123): field access per request field and the fields the step
 * captures. Publishing proved the declaration (compiler {@code StepForms} and the correction rules), so this only reads.
 */
record StepForm(Map<String, String> access, List<String> editable, List<String> required, JsonNode capture) {

    static final StepForm NONE = new StepForm(Map.of(), List.of(), List.of(), null);

    static StepForm of(JsonNode node) {
        if (node == null) {
            return NONE;
        }
        JsonNode config = node.path("config");
        Map<String, String> access = new LinkedHashMap<>();
        config.path("fieldAccess").properties().forEach(e -> access.put(e.getKey(), e.getValue().asString()));
        LinkedHashSet<String> editable = new LinkedHashSet<>();
        config.path("correctable").forEach(f -> editable.add(f.asString()));
        List<String> required = new ArrayList<>();
        access.forEach((key, value) -> {
            if (value.equals("editable") || value.equals("required")) {
                editable.add(key);
            }
            if (value.equals("required")) {
                required.add(key);
            }
        });
        JsonNode capture = config.path("capture");
        boolean captures = capture.isObject() && capture.path("properties").isObject() && !capture.path("properties").isEmpty();
        return new StepForm(Map.copyOf(access), List.copyOf(editable), List.copyOf(required), captures ? capture : null);
    }

    boolean hidden(String key) {
        return "hidden".equals(access.get(key));
    }

    boolean captures() {
        return capture != null;
    }
}
