package com.orqix.compiler.internal.bpmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explicitly written defaults and field titles in the BPMN round trip (UI03, docs/build/S65).
 * <p>
 * <b>Default.</b> A step setting is a default when all three hold: (1) the workflow schema allows leaving it out,
 * (2) the compiler's model reader ({@code ModelReader}) uses exactly this value when it is left out, so the compiled
 * model, the generated BPMN and the runtime behave identically whether it is written or omitted, and (3) the studio
 * shows an omitted setting as this value. The complete list is {@link #RULES}; nothing else is a default. In particular a
 * payload/result field {@code title} equal to its name is <i>not</i> a default (the compiler falls back to the name but the
 * studio shows a humanized name), and {@code dueAfterHours}, {@code rejectReasonRequired} or {@code timeoutSeconds} have
 * no default at all.
 * <p>
 * The BPMN cannot tell a written default from an omitted one, and {@link BpmnReader} always writes the omitted form. To
 * keep the draft's own spelling, {@link #adopt} copies it from the draft onto what was read, step by step: only for a
 * step with the same id and type, only for a setting that has the default meaning on both sides. A written default
 * that is not the default value (for example {@code atTime: "10:00"}) is never touched, so every real difference is
 * still reported.
 * <p>
 * <b>Field titles.</b> The {@code title} of a message-payload field ({@code waitForMessage}) or an external-task result
 * field ({@code externalTask}) is display text that the compiled BPMN does not carry and that no rule, route or runtime
 * decision depends on. It is kept from the draft for a field with the same name on a step with the same id and type,
 * like the sections listed as "kept from the draft"; a renamed or new field has no title (the studio then shows the
 * humanized name).
 */
public final class BpmnDefaults {

    /** One default: for steps of {@code type}, {@code key} equal to {@code value} means the same as leaving it out. */
    record Rule(String type, String key, JsonNode value, Predicate<JsonNode> appliesTo) {
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Every default (see the class documentation for the definition). */
    static final List<Rule> RULES = List.of(
            // A date wait ends at 09:00 UTC unless atTime says otherwise; atTime is only allowed with untilField.
            new Rule("wait", "atTime", F.stringNode("09:00"), config -> config.has("untilField")),
            new Rule("externalTask", "leaseSeconds", F.numberNode(120), config -> true),
            new Rule("parallelSplit", "mode", F.stringNode("all"), config -> true),
            new Rule("approval", "requiredSkills", F.arrayNode(), config -> true),
            new Rule("http", "compensation", F.stringNode("manual"), config -> true),
            new Rule("startWorkflow", "collectionMapping", F.objectNode(), config -> true),
            new Rule("waitForMessage", "payload", F.arrayNode(), config -> true));

    /** {@code required: false} on a payload or result field; its {@code title} is kept display text. */
    private static final Map<String, String> FIELD_LISTS = Map.of("waitForMessage", "payload", "externalTask", "result");

    private BpmnDefaults() {
    }

    /**
     * Section and handler defaults (S77): {@code interrupting: false} means the same as leaving it out. The model reader
     * uses false when it is absent, the generator writes the handler's flag either way, a section never uses it, and the
     * studio shows an absent flag as an unchecked "Stop the rest of the request".
     */
    static final List<Rule> SCOPE_RULES = List.of(
            new Rule("subprocesses", "interrupting", F.booleanNode(false), scope -> true));

    /** The documented defaults as text, for messages and documentation tests ({@code type.key = value}). */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        RULES.forEach(r -> out.add(r.type() + "." + r.key() + " = " + r.value()));
        FIELD_LISTS.forEach((type, list) -> out.add(type + "." + list + "[].required = false"));
        SCOPE_RULES.forEach(r -> out.add(r.type() + "[]." + r.key() + " = " + r.value()));
        out.sort(String::compareTo);
        return List.copyOf(out);
    }

    /**
     * Gives the sections and handlers read from BPMN the draft's spelling of their defaults and the draft's order of
     * their member steps (S77), subprocess by subprocess (same id and type). Membership is a set: the order in which a
     * subprocess lists its steps changes nothing in the compiled process, the validation or the runtime (the entry step
     * is unique and found from the paths), so the draft's order is kept for the steps that are still members and new
     * members follow in document order. Changes nothing else; a different set of members is a real edit.
     */
    public static void adoptScopes(JsonNode draftScopes, JsonNode targetScopes) {
        if (draftScopes == null || !draftScopes.isArray() || targetScopes == null || !targetScopes.isArray()) {
            return;
        }
        Map<String, JsonNode> draft = new LinkedHashMap<>();
        draftScopes.forEach(s -> draft.putIfAbsent(s.path("id").asString(""), s));
        for (JsonNode scope : targetScopes) {
            JsonNode before = draft.get(scope.path("id").asString(""));
            if (before == null || !before.isObject() || !scope.isObject()
                    || !before.path("type").equals(scope.path("type"))) {
                continue;
            }
            ObjectNode target = (ObjectNode) scope;
            for (Rule rule : SCOPE_RULES) {
                adoptDefault(rule.key(), rule.value(), (ObjectNode) before, target);
            }
            if (before.path("nodes").isArray() && target.path("nodes").isArray()) {
                List<String> read = new ArrayList<>();
                target.get("nodes").forEach(n -> read.add(n.asString("")));
                ArrayNode ordered = F.arrayNode();
                java.util.Set<String> placed = new java.util.HashSet<>();
                for (JsonNode n : before.get("nodes")) {
                    String id = n.asString("");
                    if (read.contains(id) && placed.add(id)) {
                        ordered.add(id);
                    }
                }
                read.stream().filter(placed::add).forEach(ordered::add);
                target.set("nodes", ordered);
            }
        }
    }

    /** Subprocesses whose member list is not in document order: "the step order of section 'id'". */
    public static List<String> keptMemberOrder(JsonNode def) {
        List<String> order = new ArrayList<>();
        def.path("nodes").forEach(n -> order.add(n.path("id").asString("")));
        List<String> out = new ArrayList<>();
        for (JsonNode s : def.path("subprocesses")) {
            List<String> members = new ArrayList<>();
            s.path("nodes").forEach(n -> members.add(n.asString("")));
            List<String> inDocumentOrder = order.stream().filter(members::contains).toList();
            if (!members.equals(inDocumentOrder)) {
                out.add("the step order of " + ("event".equals(s.path("type").asString("")) ? "handler" : "section")
                        + " '" + s.path("id").asString("") + "'");
            }
        }
        return out;
    }

    /**
     * Gives {@code target} (nodes read from BPMN or a document built from them) the draft's spelling of defaults and the
     * draft's field titles, step by step. Changes nothing else.
     *
     * @param draftNodes  the {@code nodes} of the draft
     * @param targetNodes the {@code nodes} to adjust in place
     */
    public static void adopt(JsonNode draftNodes, JsonNode targetNodes) {
        if (draftNodes == null || !draftNodes.isArray() || targetNodes == null || !targetNodes.isArray()) {
            return;
        }
        Map<String, JsonNode> draft = new LinkedHashMap<>();
        draftNodes.forEach(n -> draft.putIfAbsent(n.path("id").asString(""), n));
        for (JsonNode node : targetNodes) {
            JsonNode before = draft.get(node.path("id").asString(""));
            if (before == null || !before.path("type").equals(node.path("type")) || !before.path("config").isObject()
                    || !node.path("config").isObject()) {
                continue;
            }
            adoptConfig(node.path("type").asString(""), (ObjectNode) before.get("config"),
                    (ObjectNode) node.get("config"));
        }
    }

    private static void adoptConfig(String type, ObjectNode draft, ObjectNode target) {
        for (Rule rule : RULES) {
            if (rule.type().equals(type) && rule.appliesTo().test(target)) {
                adoptDefault(rule.key(), rule.value(), draft, target);
            }
        }
        String list = FIELD_LISTS.get(type);
        if (list != null && draft.path(list).isArray() && target.path(list).isArray()) {
            Map<String, JsonNode> fields = new LinkedHashMap<>();
            draft.get(list).forEach(f -> fields.putIfAbsent(f.path("name").asString(""), f));
            for (JsonNode field : (ArrayNode) target.get(list)) {
                JsonNode was = fields.get(field.path("name").asString(""));
                if (was == null || !was.isObject() || !field.isObject()) {
                    continue;
                }
                ObjectNode f = (ObjectNode) field;
                adoptDefault("required", F.booleanNode(false), (ObjectNode) was, f);
                if (was.has("title") && !f.has("title")) {
                    f.set("title", was.get("title"));
                }
            }
        }
    }

    /** Written in the draft and omitted in the target (or the reverse), both meaning the default: use the draft's form. */
    private static void adoptDefault(String key, JsonNode value, ObjectNode draft, ObjectNode target) {
        boolean inDraft = draft.has(key);
        boolean inTarget = target.has(key);
        if (inDraft && !inTarget && same(draft.get(key), value)) {
            target.set(key, draft.get(key).deepCopy());
        } else if (!inDraft && inTarget && same(target.get(key), value)) {
            target.remove(key);
        }
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }

    /** Steps whose field titles are kept from the draft: "field titles of step 'id'". */
    public static List<String> keptTitles(JsonNode def) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : def.path("nodes")) {
            String list = FIELD_LISTS.get(node.path("type").asString(""));
            if (list == null) {
                continue;
            }
            boolean titled = false;
            for (JsonNode f : node.path("config").path(list)) {
                titled |= f.has("title");
            }
            if (titled) {
                out.add("field titles of step '" + node.path("id").asString("") + "'");
            }
        }
        return out;
    }
}
