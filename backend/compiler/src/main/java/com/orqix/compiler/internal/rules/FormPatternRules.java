package com.orqix.compiler.internal.rules;

import java.util.List;
import java.util.Map;

import com.orqix.compiler.FormPattern;
import com.orqix.compiler.internal.json.JsonPointers;

import tools.jackson.databind.JsonNode;

/**
 * {@code pattern} and {@code patternMessage} of form text fields (docs/build/S90): only on plain text (no choices, date,
 * file or other type), the pattern must compile in the safe subset of {@link FormPattern}, some answer within the
 * field's length limits must match it, and the message is plain text without expression markers or control
 * characters. Every finding is {@code WF_FORM_PATTERN_INVALID} with the reason. Runs on the document that passed the
 * JSON Schema, so shapes are known.
 */
public final class FormPatternRules {

    public static final String CODE = "WF_FORM_PATTERN_INVALID";

    private FormPatternRules() {
    }

    public static void validate(JsonNode root, Issues issues) {
        for (String form : List.of("inputSchema", "outputSchema")) {
            for (Map.Entry<String, JsonNode> e : root.path(form).path("properties").properties()) {
                String base = JsonPointers.of(form, "properties", e.getKey());
                JsonNode field = e.getValue();
                check(field, base, issues);
                JsonNode items = field.path("items");
                if (items.isObject()) {
                    check(items, base + "/items", issues);
                    for (Map.Entry<String, JsonNode> column : items.path("properties").properties()) {
                        check(column.getValue(), base + "/items/properties/" + JsonPointers.escape(column.getKey()),
                                issues);
                    }
                }
            }
        }
    }

    private static void check(JsonNode field, String base, Issues issues) {
        JsonNode message = field.get("patternMessage");
        if (message != null && message.isString()) {
            String text = message.stringValue();
            if (text.contains("${") || text.contains("#{")) {
                issues.blocker(CODE, base + "/patternMessage", null, null,
                        "The pattern message cannot contain \"${\" or \"#{\".");
            } else if (text.codePoints().anyMatch(c -> c < 0x20 || c == 0x7F) || text.isBlank()) {
                issues.blocker(CODE, base + "/patternMessage", null, null,
                        "The pattern message must be one line of plain text.");
            }
        }
        JsonNode pattern = field.get("pattern");
        if (pattern == null || !pattern.isString()) {
            return;
        }
        String title = field.path("title").asString("This question");
        if (!"string".equals(field.path("type").asString("")) || field.has("format") || field.has("enum")) {
            issues.blocker(CODE, base + "/pattern", null, null, "\"" + title + "\" is not a plain text question, so it"
                    + " cannot have a pattern. Patterns apply to text answers only (not choices, dates, files, numbers"
                    + " or yes/no).");
            return;
        }
        FormPattern compiled;
        try {
            compiled = FormPattern.compile(pattern.stringValue());
        } catch (FormPattern.Invalid invalid) {
            issues.blocker(CODE, base + "/pattern", null, null, "The pattern of \"" + title + "\" cannot be used: "
                    + invalid.getMessage());
            return;
        }
        String example = compiled.example();
        int shortest = example.codePointCount(0, example.length());
        if (field.path("maxLength").isIntegralNumber() && shortest > field.path("maxLength").asInt()) {
            issues.blocker(CODE, base + "/pattern", null, null, "No answer to \"" + title + "\" can match its pattern"
                    + " within its longest length (" + field.path("maxLength").asInt() + " characters): the"
                    + " shortest match has " + shortest + ".");
        }
    }
}
