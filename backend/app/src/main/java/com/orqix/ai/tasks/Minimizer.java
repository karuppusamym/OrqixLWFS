package com.orqix.ai.tasks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.orqix.ai.skills.AiSkills;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Guardrail stage 2, minimization (doc 29 section 7.4): only the mapped values, each treated by the stricter of the
 * workspace action for its field classification ({@code send}, {@code mask}, {@code hash}, {@code drop}) and the step's
 * own redaction rule. A step can tighten the policy, never loosen it.
 */
final class Minimizer {

    /** @param problem non-null when a value cannot be sent at all (too long for the skill's contract) */
    record Result(ObjectNode sent, Map<String, String> actions, String problem) {
    }

    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final List<String> STRICTNESS = List.of("send", "maskCardNumbersAndEmails", "mask", "maskDigits",
            "hash", "drop");

    private Minimizer() {
    }

    static Result apply(Json json, JsonNode contract, ObjectNode input, AiSkills.Policy policy,
            Map<String, String> redaction) {
        ObjectNode sent = json.object();
        Map<String, String> actions = new LinkedHashMap<>();
        String problem = null;
        for (var e : input.properties()) {
            String name = e.getKey();
            JsonNode value = e.getValue();
            JsonNode declared = contract.path(name);
            String action = policy.action(declared.path("classification").asString(null));
            String rule = redaction.get(name);
            if (rule != null && STRICTNESS.indexOf(rule) > STRICTNESS.indexOf(action)) {
                action = rule;
            }
            actions.put(name, action);
            if (value.isString() && declared.has("maxLength")
                    && value.asString().length() > declared.path("maxLength").asInt()) {
                problem = "The value of " + name + " is longer than the skill accepts.";
            }
            switch (action) {
                case "drop" -> {
                    // not sent at all
                }
                case "hash" -> sent.put(name, "sha256:" + Json.sha256Hex(text(value)).substring(0, 16));
                case "maskDigits" -> sent.put(name, text(value).replaceAll("\\d", "#"));
                case "mask", "maskCardNumbersAndEmails" -> sent.put(name, value.isString()
                        ? EMAIL.matcher(CARD.matcher(value.asString()).replaceAll("[card number]"))
                                .replaceAll("[e-mail address]") : text(value));
                default -> sent.set(name, value);
            }
        }
        return new Result(sent, actions, problem);
    }

    private static String text(JsonNode value) {
        return value.isString() ? value.asString() : value.toString();
    }
}
