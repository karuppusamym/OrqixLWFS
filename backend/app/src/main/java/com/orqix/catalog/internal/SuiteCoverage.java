package com.orqix.catalog.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.FormVisibility;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-Q03 (S148): what a set of dry runs covers together. Steps, routes and decision rows come from the coverage of each
 * run (the simulator's own counts, S26): an item is covered when at least one case reached it. Form rules (a field's or
 * section's {@code visibleWhen}, a field's {@code requiredWhen}) are covered when the cases hold both sides: at least one
 * case where the condition holds and one where it does not. Cross-field validation rules are not counted: a case that
 * broke one would not run. Pure; the same rules as {@code frontend/src/mocks/suiteCoverage.ts} (shared fixture
 * {@code examples/fixtures/simulation/suite-coverage.json}).
 */
public final class SuiteCoverage {

    /** Kinds in the order they are reported; each is also a key of a coverage minimum. */
    public static final List<String> KINDS = List.of("steps", "routes", "rules", "formRules");
    private static final int MAX_LISTED = 100;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** One case that ran: its request answers and the {@code coverage} member of its dry-run result. */
    public record Run(JsonNode input, JsonNode coverage) {
    }

    /** Counts of one kind. {@code percent} is rounded down; a kind with nothing to cover is 100. */
    public record Count(int covered, int total) {
        public int percent() {
            return total == 0 ? 100 : (int) Math.floor(covered * 100.0 / total);
        }
    }

    private SuiteCoverage() {
    }

    /** @return the coverage document of the runs, or null when no case ran */
    public static ObjectNode of(JsonNode definition, List<Run> runs) {
        if (runs.isEmpty()) {
            return null;
        }
        ObjectNode out = F.objectNode();
        out.put("cases", runs.size());
        missed(out, "steps", runs, "steps", "notVisited", "visited", n -> n.path("id").asString(), "total");
        missed(out, "routes", runs, "routes", "notTaken", "taken", n -> n.path("transitionId").asString(), "total");
        missed(out, "rules", runs, "rules", "notMatched", "matched",
                n -> n.path("decision").asString() + "#" + n.path("row").asString(), "total");
        formRules(out, definition, runs);
        StringBuilder summary = new StringBuilder("Together the cases cover ");
        summary.append(phrase(out, "steps", "steps")).append(", ").append(phrase(out, "routes", "routes")).append(", ")
                .append(phrase(out, "rules", "decision rules")).append(" and ").append(phrase(out, "formRules", "form rules"))
                .append('.');
        out.put("summary", summary.toString());
        return out;
    }

    /** Counts only, as kept with a recorded run: {kind: {covered, total}}. */
    public static ObjectNode counts(JsonNode coverage) {
        ObjectNode out = F.objectNode();
        for (String kind : KINDS) {
            out.putObject(kind).put("covered", coverage.path(kind).path("covered").asInt())
                    .put("total", coverage.path(kind).path("total").asInt());
        }
        return out;
    }

    /** The kinds whose share is below the minimum, as {kind, percent, minimum}; empty when the minimum is met. */
    public static List<Map<String, Object>> shortfalls(JsonNode coverage, JsonNode minimum) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (minimum == null || minimum.isNull() || minimum.isMissingNode()) {
            return out;
        }
        for (String kind : KINDS) {
            if (!minimum.has(kind)) {
                continue;
            }
            Count count = new Count(coverage == null ? 0 : coverage.path(kind).path("covered").asInt(),
                    coverage == null ? 1 : coverage.path(kind).path("total").asInt());
            int least = minimum.path(kind).asInt();
            if (count.percent() < least) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("kind", kind);
                s.put("percent", count.percent());
                s.put("minimum", least);
                out.add(s);
            }
        }
        return out;
    }

    private static String phrase(ObjectNode out, String kind, String noun) {
        return out.path(kind).path("covered").asInt() + " of " + out.path(kind).path("total").asInt() + " " + noun;
    }

    /** An item is missed by the suite only when every run missed it. */
    private static void missed(ObjectNode out, String kind, List<Run> runs, String member, String missedList,
            String hitCount, java.util.function.Function<JsonNode, String> key, String totalCount) {
        int total = runs.get(0).coverage().path(member).path(totalCount).asInt();
        Map<String, JsonNode> notCovered = new LinkedHashMap<>();
        runs.get(0).coverage().path(member).path(missedList).forEach(n -> notCovered.put(key.apply(n), n));
        for (Run run : runs.subList(1, runs.size())) {
            java.util.Set<String> here = new java.util.HashSet<>();
            run.coverage().path(member).path(missedList).forEach(n -> here.add(key.apply(n)));
            notCovered.keySet().retainAll(here);
        }
        ObjectNode node = out.putObject(kind);
        node.put("covered", Math.max(0, total - notCovered.size()));
        node.put("total", total);
        node.put("percent", new Count(Math.max(0, total - notCovered.size()), total).percent());
        ArrayNode list = node.putArray("notCovered");
        notCovered.values().stream().limit(MAX_LISTED).forEach(n -> list.add(n.deepCopy()));
    }

    private record FormRule(String rule, String field, String section, JsonNode condition) {
    }

    private static void formRules(ObjectNode out, JsonNode definition, List<Run> runs) {
        JsonNode ui = definition.path("inputUi");
        JsonNode schema = definition.path("inputSchema");
        List<FormRule> rules = new ArrayList<>();
        ui.path("visibleWhen").properties().forEach(e -> rules.add(new FormRule("visibleWhen", e.getKey(), null, e.getValue())));
        ui.path("requiredWhen").properties().forEach(e -> rules.add(new FormRule("requiredWhen", e.getKey(), null, e.getValue())));
        for (JsonNode section : ui.path("layout").path("sections")) {
            if (section.has("visibleWhen")) {
                rules.add(new FormRule("visibleWhen", null, section.path("id").asString(), section.path("visibleWhen")));
            }
        }
        int covered = 0;
        ObjectNode node = out.putObject("formRules");
        ArrayNode list = F.arrayNode();
        for (FormRule rule : rules) {
            boolean holds = false;
            boolean fails = false;
            for (Run run : runs) {
                if (FormVisibility.matches(rule.condition(), run.input(), schema)) {
                    holds = true;
                } else {
                    fails = true;
                }
            }
            if (holds && fails) {
                covered++;
            } else if (list.size() < MAX_LISTED) {
                ObjectNode o = list.addObject();
                o.put("rule", rule.rule());
                if (rule.field() != null) {
                    o.put("field", rule.field());
                    o.put("label", schema.path("properties").path(rule.field()).path("title").asString(rule.field()));
                } else {
                    o.put("section", rule.section());
                    o.put("label", sectionTitle(ui, rule.section()));
                }
                o.put("missing", holds ? "fails" : "holds");
            }
        }
        node.put("covered", covered);
        node.put("total", rules.size());
        node.put("percent", new Count(covered, rules.size()).percent());
        node.set("notCovered", list);
    }

    private static String sectionTitle(JsonNode ui, String id) {
        for (JsonNode section : ui.path("layout").path("sections")) {
            if (id.equals(section.path("id").asString())) {
                return section.path("title").asString(id);
            }
        }
        return id;
    }
}
