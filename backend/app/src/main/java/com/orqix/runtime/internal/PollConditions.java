package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import com.orqix.compiler.ConditionMatcher;

import tools.jackson.databind.JsonNode;

/**
 * Evaluates the done and failed conditions of a check (docs/build/S129) with the same comparison code as decisions and
 * routes ({@link ConditionMatcher}). A condition is an atomic term or an {@code all}/{@code any} group, as compiled.
 * A missing value makes a term false, so an incomplete answer is never taken as done.
 */
final class PollConditions {

    private PollConditions() {
    }

    static boolean holds(JsonNode condition, Function<String, JsonNode> values) {
        if (condition == null || condition.isMissingNode() || condition.isNull()) {
            return false;
        }
        if (condition.has("all")) {
            for (JsonNode term : condition.get("all")) {
                if (!holds(term, values)) {
                    return false;
                }
            }
            return true;
        }
        if (condition.has("any")) {
            for (JsonNode term : condition.get("any")) {
                if (holds(term, values)) {
                    return true;
                }
            }
            return false;
        }
        String operator = condition.path("operator").asString();
        JsonNode actual = values.apply(condition.path("fieldRef").asString());
        if (actual == null || actual.isMissingNode() || actual.isNull() || actual.isContainer()) {
            return false;
        }
        Object value = actual.isNumber() ? new BigDecimal(actual.asString()) : actual.isBoolean() ? actual.asBoolean()
                : actual.asString();
        try {
            return ConditionMatcher.matches(operator, value, ConditionMatcher.operand(operator, condition.path("value")));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** @return the field references of the condition that do not start with {@code ownPrefix} */
    static Set<String> foreignRefs(JsonNode condition, String ownPrefix) {
        Set<String> refs = new LinkedHashSet<>();
        collect(condition, ownPrefix, refs);
        return refs;
    }

    private static void collect(JsonNode c, String own, Set<String> refs) {
        if (c == null || c.isMissingNode() || c.isNull()) {
            return;
        }
        if (c.has("all") || c.has("any")) {
            for (JsonNode term : c.has("all") ? c.get("all") : c.get("any")) {
                collect(term, own, refs);
            }
            return;
        }
        String ref = c.path("fieldRef").asString("");
        if (!ref.isEmpty() && !ref.startsWith(own)) {
            refs.add(ref);
        }
    }
}
