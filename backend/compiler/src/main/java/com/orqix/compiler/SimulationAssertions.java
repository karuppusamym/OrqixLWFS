package com.orqix.compiler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Assertions of a test case over the result of a dry run (STU03, docs/build/S96-test-tab-and-integration-mocks.md):
 * the final outcome, output values (equals, contains, range), the ordered path of steps visited, the decision rules
 * that fired and the approver choices used. Pure: it reads only the trace document. Every failed assertion carries a
 * readable expected and actual text.
 * <p>
 * Twin: frontend/src/mocks/assertions.ts; examples/fixtures/simulation/assertions.json is evaluated by both.
 */
public final class SimulationAssertions {

    /** Expected outcome meaning "the request stops and needs attention". */
    public static final String NEEDS_ATTENTION = "__needsAttention__";
    static final int MAX_ITEMS = 20;
    static final int MAX_PATH = 100;
    static final int MAX_TEXT = 200;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> KEYS = Set.of("outcome", "outputs", "path", "rules", "approvals");

    private SimulationAssertions() {
    }

    /** @throws IllegalArgumentException when the assertions do not have the bounded shape */
    public static void validate(JsonNode a) {
        if (a == null || a.isNull() || a.isMissingNode()) {
            return;
        }
        if (!a.isObject()) {
            throw new IllegalArgumentException("The assertions must be an object.");
        }
        for (String key : a.propertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown assertion " + key + ".");
            }
        }
        if (a.has("outcome") && !(a.get("outcome").isString() && text(a.get("outcome")))) {
            throw new IllegalArgumentException("assertions.outcome must be text of at most 200 characters.");
        }
        for (JsonNode o : list(a, "outputs", MAX_ITEMS)) {
            if (!o.isObject() || !text(o.path("field")) || !Set.of("equals", "contains", "range").contains(o.path("op")
                    .asString(""))) {
                throw new IllegalArgumentException("Each output assertion needs a field and op equals, contains or range.");
            }
            String op = o.path("op").asString();
            if ("range".equals(op)) {
                boolean min = o.path("min").isNumber();
                boolean max = o.path("max").isNumber();
                if ((!min && !max) || (o.has("min") && !min) || (o.has("max") && !max)
                        || (min && max && o.get("min").decimalValue().compareTo(o.get("max").decimalValue()) > 0)) {
                    throw new IllegalArgumentException("A range assertion needs a number min, max or both, min not above max.");
                }
            } else if (!scalar(o.path("value"))) {
                throw new IllegalArgumentException("An " + op + " assertion needs a text, number or true/false value.");
            }
        }
        if (a.has("path")) {
            JsonNode p = a.get("path");
            if (!p.isObject() || !Set.of("exact", "inOrder").contains(p.path("match").asString("exact"))) {
                throw new IllegalArgumentException("assertions.path needs steps and match exact or inOrder.");
            }
            List<JsonNode> steps = list(p, "steps", MAX_PATH);
            if (steps.isEmpty() || steps.stream().anyMatch(s -> !s.isString() || !text(s))) {
                throw new IllegalArgumentException("assertions.path.steps must list 1 to 100 step IDs.");
            }
        }
        for (JsonNode r : list(a, "rules", MAX_ITEMS)) {
            JsonNode rule = r.path("rule");
            boolean ok = r.isObject() && text(r.path("stepId")) && ((rule.isIntegralNumber() && rule.asInt() >= 1
                    && rule.asInt() <= 1000) || "otherwise".equals(rule.asString(null)));
            if (!ok) {
                throw new IllegalArgumentException("Each rule assertion needs a stepId and a rule number or otherwise.");
            }
        }
        for (JsonNode r : list(a, "approvals", MAX_ITEMS)) {
            if (!r.isObject() || !text(r.path("stepId")) || !Set.of("approved", "rejected", "timedOut")
                    .contains(r.path("decision").asString(""))) {
                throw new IllegalArgumentException("Each approval assertion needs a stepId and decision approved,"
                        + " rejected or timedOut.");
            }
        }
    }

    /**
     * @param resultJson     the trace document of {@link WorkflowSimulator#simulate}
     * @param assertionsJson the assertions, or null
     * @return {"passed": boolean, "results": [{kind, label, passed, expected, actual, detail?}]}
     */
    public static String evaluate(String resultJson, String assertionsJson) {
        JsonNode a = assertionsJson == null ? F.objectNode() : StrictJson.parse(assertionsJson);
        return StrictJson.write(evaluate(StrictJson.parse(resultJson), a));
    }

    static ObjectNode evaluate(JsonNode result, JsonNode a) {
        validate(a);
        ArrayNode results = F.arrayNode();
        Map<String, String> labels = new LinkedHashMap<>();
        List<String> visited = new ArrayList<>();
        for (JsonNode e : result.path("trace")) {
            String id = e.path("stepId").asString();
            labels.putIfAbsent(id, e.path("stepLabel").asString(id));
            if (!visited.contains(id)) {
                visited.add(id);
            }
        }
        for (JsonNode n : result.path("coverage").path("steps").path("notVisited")) {
            labels.putIfAbsent(n.path("id").asString(), n.path("label").asString(n.path("id").asString()));
        }
        JsonNode outcome = result.path("outcome");
        boolean finished = "finished".equals(outcome.path("status").asString());
        if (a != null && a.has("outcome")) {
            String expected = a.get("outcome").asString();
            String actual = finished ? outcome.path("businessOutcome").asString("") : NEEDS_ATTENTION;
            String actualText = finished ? actual : "Needs attention" + (outcome.path("attention").isObject()
                    ? " at " + quote(outcome.path("attention").path("stepLabel").asString()) : "");
            add(results, "outcome", "Final outcome", expected.equals(actual),
                    NEEDS_ATTENTION.equals(expected) ? "Needs attention" : expected, actualText, null);
        }
        for (JsonNode o : list(a, "outputs", MAX_ITEMS)) {
            String field = o.path("field").asString();
            JsonNode actual = outputValue(result, field);
            String op = o.path("op").asString();
            boolean passed;
            String expected;
            switch (op) {
                case "equals" -> {
                    passed = actual != null && same(actual, o.get("value"));
                    expected = "equals " + show(o.get("value"));
                }
                case "contains" -> {
                    passed = actual != null && contains(actual, o.get("value"));
                    expected = "contains " + show(o.get("value"));
                }
                default -> {
                    BigDecimal min = o.path("min").isNumber() ? o.get("min").decimalValue() : null;
                    BigDecimal max = o.path("max").isNumber() ? o.get("max").decimalValue() : null;
                    passed = actual != null && actual.isNumber() && (min == null
                            || actual.decimalValue().compareTo(min) >= 0) && (max == null
                            || actual.decimalValue().compareTo(max) <= 0);
                    expected = min != null && max != null ? "between " + num(min) + " and " + num(max)
                            : min != null ? "at least " + num(min) : "at most " + num(max);
                }
            }
            add(results, "output", fieldLabel(field, labels), passed, expected,
                    actual == null ? "no value" : show(actual), null);
        }
        if (a != null && a.has("path")) {
            JsonNode p = a.get("path");
            boolean exact = "exact".equals(p.path("match").asString("exact"));
            List<String> expected = new ArrayList<>();
            p.path("steps").forEach(s -> expected.add(s.asString()));
            String detail = exact ? exactDifference(expected, visited, labels) : orderDifference(expected, visited, labels);
            add(results, "path", exact ? "Steps visited" : "Steps visited, in this order", detail == null,
                    names(expected, labels), names(visited, labels), detail);
        }
        for (JsonNode r : list(a, "rules", MAX_ITEMS)) {
            String stepId = r.path("stepId").asString();
            JsonNode entry = entry(result, stepId, "decision");
            String expected = r.path("rule").isNumber() ? "rule " + r.path("rule").asInt() : "otherwise";
            String actual = entry == null ? "not reached" : entry.path("details").path("matchedRow").isNumber()
                    ? "rule " + entry.path("details").path("matchedRow").asInt() : "otherwise";
            add(results, "rule", "Decision " + quote(labels.getOrDefault(stepId, stepId)), expected.equals(actual),
                    expected, actual, null);
        }
        for (JsonNode r : list(a, "approvals", MAX_ITEMS)) {
            String stepId = r.path("stepId").asString();
            JsonNode entry = entry(result, stepId, "approval");
            String expected = r.path("decision").asString();
            String actual = entry == null ? "not reached" : entry.path("details").path("decision").asString("not reached");
            add(results, "approval", "Approval " + quote(labels.getOrDefault(stepId, stepId)), expected.equals(actual),
                    expected, actual, null);
        }
        ObjectNode out = F.objectNode();
        boolean all = true;
        for (JsonNode r : results) {
            all &= r.path("passed").asBoolean();
        }
        out.put("passed", all);
        out.set("results", results);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void add(ArrayNode results, String kind, String label, boolean passed, String expected,
                            String actual, String detail) {
        ObjectNode r = results.addObject();
        r.put("kind", kind);
        r.put("label", label);
        r.put("passed", passed);
        r.put("expected", expected);
        r.put("actual", actual);
        if (detail != null) {
            r.put("detail", detail);
        }
    }

    private static List<JsonNode> list(JsonNode a, String key, int max) {
        List<JsonNode> items = new ArrayList<>();
        if (a == null || !a.has(key)) {
            return items;
        }
        JsonNode v = a.get(key);
        if (!v.isArray() || v.size() > max) {
            throw new IllegalArgumentException("assertions." + key + " must be a list of at most " + max + " items.");
        }
        v.forEach(items::add);
        return items;
    }

    private static boolean text(JsonNode n) {
        return n.isString() && !n.asString().isBlank() && n.asString().length() <= MAX_TEXT;
    }

    private static boolean scalar(JsonNode n) {
        return (n.isString() && n.asString().length() <= MAX_TEXT) || n.isNumber() || n.isBoolean();
    }

    /** The last trace entry of a step of this kind, or null. */
    private static JsonNode entry(JsonNode result, String stepId, String kind) {
        JsonNode found = null;
        for (JsonNode e : result.path("trace")) {
            if (stepId.equals(e.path("stepId").asString()) && kind.equals(e.path("kind").asString())) {
                found = e;
            }
        }
        return found;
    }

    /** An output key, or "/steps/<id>/<name>" for a value a step wrote. */
    private static JsonNode outputValue(JsonNode result, String field) {
        if (field.startsWith("/steps/")) {
            String[] parts = field.substring(1).split("/", -1);
            if (parts.length != 3) {
                return null;
            }
            JsonNode found = null;
            for (JsonNode e : result.path("trace")) {
                if (parts[1].equals(e.path("stepId").asString()) && e.path("wrote").has(parts[2])) {
                    found = e.path("wrote").get(parts[2]);
                }
            }
            return found == null || found.isNull() ? null : found;
        }
        JsonNode v = result.path("outputs").get(field);
        return v == null || v.isNull() ? null : v;
    }

    private static String fieldLabel(String field, Map<String, String> labels) {
        if (field.startsWith("/steps/")) {
            String[] parts = field.substring(1).split("/", -1);
            if (parts.length == 3) {
                return "Value " + parts[2] + " of " + quote(labels.getOrDefault(parts[1], parts[1]));
            }
        }
        return "Output " + field;
    }

    private static boolean same(JsonNode actual, JsonNode expected) {
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        return actual.equals(expected);
    }

    private static boolean contains(JsonNode actual, JsonNode expected) {
        if (actual.isArray()) {
            for (JsonNode item : actual) {
                if (same(item, expected)) {
                    return true;
                }
            }
            return false;
        }
        if (actual.isString()) {
            return actual.asString().contains(expected.isNumber() ? num(expected.decimalValue()) : expected.asString());
        }
        return false;
    }

    static String show(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "no value";
        }
        if (v.isString()) {
            return quote(v.asString());
        }
        if (v.isNumber()) {
            return num(v.decimalValue());
        }
        if (v.isBoolean()) {
            return String.valueOf(v.booleanValue());
        }
        return StrictJson.write(v);
    }

    private static String num(BigDecimal value) {
        BigDecimal v = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return (v.scale() < 0 ? v.setScale(0) : v).toPlainString();
    }

    private static String quote(String text) {
        return "\"" + text + "\"";
    }

    private static String names(List<String> ids, Map<String, String> labels) {
        List<String> parts = new ArrayList<>();
        ids.forEach(id -> parts.add(labels.getOrDefault(id, id)));
        return parts.isEmpty() ? "no steps" : String.join(" → ", parts);
    }

    private static String exactDifference(List<String> expected, List<String> visited, Map<String, String> labels) {
        int n = Math.max(expected.size(), visited.size());
        for (int i = 0; i < n; i++) {
            String want = i < expected.size() ? expected.get(i) : null;
            String got = i < visited.size() ? visited.get(i) : null;
            if (want != null && want.equals(got)) {
                continue;
            }
            if (want == null) {
                return "The run went on to " + quote(labels.getOrDefault(got, got)) + " after the expected path ended"
                        + " (step " + (i + 1) + ").";
            }
            if (got == null) {
                return "The run stopped before " + quote(labels.getOrDefault(want, want)) + " (step " + (i + 1) + ").";
            }
            return "Step " + (i + 1) + ": expected " + quote(labels.getOrDefault(want, want)) + " but the run went to "
                    + quote(labels.getOrDefault(got, got)) + ".";
        }
        return null;
    }

    private static String orderDifference(List<String> expected, List<String> visited, Map<String, String> labels) {
        int from = 0;
        for (String want : expected) {
            int at = visited.subList(from, visited.size()).indexOf(want);
            if (at < 0) {
                return visited.contains(want) ? quote(labels.getOrDefault(want, want)) + " was visited, but not in the"
                        + " expected order." : quote(labels.getOrDefault(want, want)) + " was not visited.";
            }
            from += at + 1;
        }
        return null;
    }
}
