package com.orqix.migration.blueprint;

import java.util.*;
import java.util.function.Function;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reviewed structured transcription of a blueprint document (docs/build/S91): the single source of truth for
 * conversion. Every element carries its source page reference and a disposition. Parsing is strict (allowed members,
 * types and lengths) so an edited transcription cannot smuggle behaviour the converter does not understand; semantic
 * gaps (a step without a role, a decision without outcomes, ...) are reported as findings by {@link BlueprintFindings},
 * never repaired silently.
 */
public record Transcription(CaseType caseType, String start, List<Stage> stages, List<Role> roles, List<Field> fields,
        List<Step> steps, List<Decision> decisions, List<Sla> slas, List<Outcome> outcomes, List<Integration> integrations) {

    public static final String FORMAT = "orqix-blueprint-transcription-0.1";
    public static final String PROPOSED = "proposed", MAPPED = "mapped", MANUAL = "manuallyResolved", EXCLUDED = "excluded";
    public static final Set<String> DISPOSITIONS = Set.of(PROPOSED, MAPPED, MANUAL, EXCLUDED);
    public static final Set<String> FIELD_TYPES = Set.of("string", "number", "integer", "boolean", "date");
    public static final Set<String> STEP_KINDS = Set.of("approval", "task", "automated");
    public static final Set<String> SLA_UNITS = Set.of("minutes", "hours", "days", "workingDays");
    public static final Set<String> OUTCOME_KINDS = Set.of("success", "rejected");
    public static final Set<String> OPERATORS = Set.of(">=", ">", "<=", "<", "==", "!=");
    static final String ID = "^[A-Za-z][A-Za-z0-9_-]{0,63}$";
    static final String FIELD_KEY = "^[a-z][A-Za-z0-9]{0,63}$";
    static final int MAX_ITEMS = 200;

    /** Source reference, review disposition and optional reason shared by every element. */
    public record Meta(Integer sourcePage, String sourceExcerpt, String disposition, String dispositionReason) {
        public Meta withDisposition(String value) { return new Meta(sourcePage, sourceExcerpt, value, dispositionReason); }
        public boolean excluded() { return EXCLUDED.equals(disposition); }
    }
    public interface Element { String id(); String name(); Meta meta(); String kind(); }
    public record CaseType(String name, Meta meta) implements Element {
        public String id() { return "caseType"; } public String kind() { return "caseType"; }
    }
    public record Stage(String id, String name, Meta meta) implements Element { public String kind() { return "stage"; } }
    public record Role(String id, String name, Meta meta) implements Element { public String kind() { return "role"; } }
    public record Field(String id, String name, String type, boolean required, Meta meta) implements Element { public String kind() { return "field"; } }
    public record Step(String id, String name, String stage, String type, String role, String next, String onReject, Meta meta) implements Element {
        public String kind() { return "step"; }
    }
    public record Rule(String field, String operator, String value, String target) { }
    public record Decision(String id, String name, List<Rule> rules, String otherwise, Meta meta) implements Element { public String kind() { return "decision"; } }
    public record Sla(String id, String name, String step, Integer amount, String unit, Meta meta) implements Element { public String kind() { return "sla"; } }
    public record Outcome(String id, String name, String type, Meta meta) implements Element { public String kind() { return "outcome"; } }
    public record Integration(String id, String name, String target, String step, Meta meta) implements Element { public String kind() { return "integration"; } }

    public Transcription {
        stages = List.copyOf(stages); roles = List.copyOf(roles); fields = List.copyOf(fields); steps = List.copyOf(steps);
        decisions = List.copyOf(decisions); slas = List.copyOf(slas); outcomes = List.copyOf(outcomes); integrations = List.copyOf(integrations);
    }

    /** Every element in a stable order: case type, stages, roles, fields, steps, decisions, SLAs, outcomes, integrations. */
    public List<Element> elements() {
        List<Element> all = new ArrayList<>();
        if (caseType != null) all.add(caseType);
        all.addAll(stages); all.addAll(roles); all.addAll(fields); all.addAll(steps); all.addAll(decisions); all.addAll(slas);
        all.addAll(outcomes); all.addAll(integrations);
        return all;
    }

    public Optional<Element> element(String id) {
        return id == null ? Optional.empty() : elements().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** The same transcription with every element's disposition replaced (used to force AI proposals back to review). */
    public Transcription withDispositions(Function<Element, String> disposition) {
        return new Transcription(caseType == null ? null : new CaseType(caseType.name(), caseType.meta().withDisposition(disposition.apply(caseType))), start,
                stages.stream().map(s -> new Stage(s.id(), s.name(), s.meta().withDisposition(disposition.apply(s)))).toList(),
                roles.stream().map(r -> new Role(r.id(), r.name(), r.meta().withDisposition(disposition.apply(r)))).toList(),
                fields.stream().map(f -> new Field(f.id(), f.name(), f.type(), f.required(), f.meta().withDisposition(disposition.apply(f)))).toList(),
                steps.stream().map(s -> new Step(s.id(), s.name(), s.stage(), s.type(), s.role(), s.next(), s.onReject(), s.meta().withDisposition(disposition.apply(s)))).toList(),
                decisions.stream().map(d -> new Decision(d.id(), d.name(), d.rules(), d.otherwise(), d.meta().withDisposition(disposition.apply(d)))).toList(),
                slas.stream().map(s -> new Sla(s.id(), s.name(), s.step(), s.amount(), s.unit(), s.meta().withDisposition(disposition.apply(s)))).toList(),
                outcomes.stream().map(o -> new Outcome(o.id(), o.name(), o.type(), o.meta().withDisposition(disposition.apply(o)))).toList(),
                integrations.stream().map(i -> new Integration(i.id(), i.name(), i.target(), i.step(), i.meta().withDisposition(disposition.apply(i)))).toList());
    }

    // ------------------------------------------------------------------------------------------------ JSON

    public ObjectNode toJson(Json json) {
        ObjectNode root = json.object();
        root.put("format", FORMAT);
        if (caseType != null) { ObjectNode c = root.putObject("caseType"); c.put("name", caseType.name()); meta(c, caseType.meta()); }
        if (start != null) root.put("start", start);
        ArrayNode a = root.putArray("stages");
        for (var s : stages) { ObjectNode o = a.addObject(); o.put("id", s.id()); o.put("name", s.name()); meta(o, s.meta()); }
        a = root.putArray("roles");
        for (var r : roles) { ObjectNode o = a.addObject(); o.put("id", r.id()); o.put("name", r.name()); meta(o, r.meta()); }
        a = root.putArray("fields");
        for (var f : fields) { ObjectNode o = a.addObject(); o.put("id", f.id()); o.put("name", f.name()); opt(o, "type", f.type()); o.put("required", f.required()); meta(o, f.meta()); }
        a = root.putArray("steps");
        for (var s : steps) {
            ObjectNode o = a.addObject(); o.put("id", s.id()); o.put("name", s.name()); opt(o, "stage", s.stage()); opt(o, "type", s.type());
            opt(o, "role", s.role()); opt(o, "next", s.next()); opt(o, "onReject", s.onReject()); meta(o, s.meta());
        }
        a = root.putArray("decisions");
        for (var d : decisions) {
            ObjectNode o = a.addObject(); o.put("id", d.id()); o.put("name", d.name());
            ArrayNode rules = o.putArray("rules");
            for (var r : d.rules()) { ObjectNode x = rules.addObject(); opt(x, "field", r.field()); opt(x, "operator", r.operator()); opt(x, "value", r.value()); opt(x, "target", r.target()); }
            opt(o, "otherwise", d.otherwise()); meta(o, d.meta());
        }
        a = root.putArray("slas");
        for (var s : slas) {
            ObjectNode o = a.addObject(); o.put("id", s.id()); o.put("name", s.name()); opt(o, "step", s.step());
            if (s.amount() != null) o.put("amount", s.amount().intValue());
            opt(o, "unit", s.unit()); meta(o, s.meta());
        }
        a = root.putArray("outcomes");
        for (var x : outcomes) { ObjectNode o = a.addObject(); o.put("id", x.id()); o.put("name", x.name()); opt(o, "type", x.type()); meta(o, x.meta()); }
        a = root.putArray("integrations");
        for (var i : integrations) { ObjectNode o = a.addObject(); o.put("id", i.id()); o.put("name", i.name()); opt(o, "target", i.target()); opt(o, "step", i.step()); meta(o, i.meta()); }
        return root;
    }

    public String sha256(Json json) { return Json.canonicalSha256(json.write(toJson(json))); }

    private static void opt(ObjectNode node, String name, String value) { if (value != null) node.put(name, value); }
    private static void meta(ObjectNode node, Meta meta) {
        if (meta.sourcePage() != null) node.put("sourcePage", meta.sourcePage().intValue());
        opt(node, "sourceExcerpt", meta.sourceExcerpt());
        node.put("disposition", meta.disposition());
        opt(node, "dispositionReason", meta.dispositionReason());
    }

    /**
     * Strict parse of a submitted or stored transcription.
     *
     * @throws ApiException MALFORMED_REQUEST naming the first offending path
     */
    public static Transcription parse(JsonNode node) {
        object(node, Set.of("format", "caseType", "start", "stages", "roles", "fields", "steps", "decisions", "slas", "outcomes", "integrations"), "transcription");
        if (Json.depth(node) > 16) fail("transcription", "must have at most 16 levels");
        if (!FORMAT.equals(node.path("format").asString(""))) fail("transcription/format", "must be " + FORMAT);
        CaseType caseType = null;
        if (node.has("caseType") && !node.get("caseType").isNull()) {
            JsonNode c = node.get("caseType");
            object(c, withMeta("name"), "caseType");
            caseType = new CaseType(text(c, "name", "caseType", 200, false), meta(c, "caseType"));
        }
        Set<String> ids = new HashSet<>();
        List<Stage> stages = list(node, "stages", (o, p) -> { object(o, withMeta("id", "name"), p); return new Stage(id(o, p, ids), text(o, "name", p, 200, true), meta(o, p)); });
        List<Role> roles = list(node, "roles", (o, p) -> { object(o, withMeta("id", "name"), p); return new Role(id(o, p, ids), text(o, "name", p, 200, true), meta(o, p)); });
        List<Field> fields = list(node, "fields", (o, p) -> {
            object(o, withMeta("id", "name", "type", "required"), p);
            String id = text(o, "id", p, 64, true);
            if (!id.matches(FIELD_KEY)) fail(p + "/id", "must be a field key such as amount or costCenter");
            if (!ids.add(id)) fail(p + "/id", "duplicates another element id");
            if (o.has("required") && !o.get("required").isBoolean()) fail(p + "/required", "must be boolean");
            return new Field(id, text(o, "name", p, 200, true), choice(o, "type", p, FIELD_TYPES), o.path("required").asBoolean(false), meta(o, p));
        });
        List<Step> steps = list(node, "steps", (o, p) -> {
            object(o, withMeta("id", "name", "stage", "type", "role", "next", "onReject"), p);
            return new Step(id(o, p, ids), text(o, "name", p, 200, true), ref(o, "stage", p), choice(o, "type", p, STEP_KINDS), ref(o, "role", p),
                    ref(o, "next", p), ref(o, "onReject", p), meta(o, p));
        });
        List<Decision> decisions = list(node, "decisions", (o, p) -> {
            object(o, withMeta("id", "name", "rules", "otherwise"), p);
            JsonNode rulesNode = o.path("rules");
            if (o.has("rules") && !rulesNode.isArray()) fail(p + "/rules", "must be an array");
            if (rulesNode.size() > 20) fail(p + "/rules", "must have at most 20 rules");
            List<Rule> rules = new ArrayList<>();
            for (int i = 0; i < rulesNode.size(); i++) {
                String rp = p + "/rules/" + i; JsonNode r = rulesNode.get(i);
                object(r, Set.of("field", "operator", "value", "target"), rp);
                rules.add(new Rule(ref(r, "field", rp), choice(r, "operator", rp, OPERATORS), text(r, "value", rp, 200, false), ref(r, "target", rp)));
            }
            return new Decision(id(o, p, ids), text(o, "name", p, 200, true), rules, ref(o, "otherwise", p), meta(o, p));
        });
        List<Sla> slas = list(node, "slas", (o, p) -> {
            object(o, withMeta("id", "name", "step", "amount", "unit"), p);
            Integer amount = null;
            if (o.has("amount") && !o.get("amount").isNull()) {
                if (!o.get("amount").isIntegralNumber() || !o.get("amount").canConvertToInt() || o.get("amount").asInt() < 1 || o.get("amount").asInt() > 100000)
                    fail(p + "/amount", "must be an integer between 1 and 100000");
                amount = o.get("amount").asInt();
            }
            return new Sla(id(o, p, ids), text(o, "name", p, 200, true), ref(o, "step", p), amount, choice(o, "unit", p, SLA_UNITS), meta(o, p));
        });
        List<Outcome> outcomes = list(node, "outcomes", (o, p) -> {
            object(o, withMeta("id", "name", "type"), p);
            return new Outcome(id(o, p, ids), text(o, "name", p, 200, true), choice(o, "type", p, OUTCOME_KINDS), meta(o, p));
        });
        List<Integration> integrations = list(node, "integrations", (o, p) -> {
            object(o, withMeta("id", "name", "target", "step"), p);
            return new Integration(id(o, p, ids), text(o, "name", p, 200, true), text(o, "target", p, 200, false), ref(o, "step", p), meta(o, p));
        });
        return new Transcription(caseType, ref(node, "start", "transcription"), stages, roles, fields, steps, decisions, slas, outcomes, integrations);
    }

    private interface ItemParser<T> { T parse(JsonNode node, String path); }
    private static <T> List<T> list(JsonNode root, String name, ItemParser<T> parser) {
        JsonNode array = root.path(name);
        if (array.isMissingNode() || array.isNull()) return List.of();
        if (!array.isArray()) fail(name, "must be an array");
        if (array.size() > MAX_ITEMS) fail(name, "must have at most " + MAX_ITEMS + " items");
        List<T> items = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) items.add(parser.parse(array.get(i), name + "/" + i));
        return items;
    }
    private static Set<String> withMeta(String... names) {
        Set<String> all = new HashSet<>(List.of(names)); all.addAll(List.of("sourcePage", "sourceExcerpt", "disposition", "dispositionReason"));
        return all;
    }
    private static Meta meta(JsonNode o, String p) {
        Integer page = null;
        if (o.has("sourcePage") && !o.get("sourcePage").isNull()) {
            if (!o.get("sourcePage").isIntegralNumber() || !o.get("sourcePage").canConvertToInt() || o.get("sourcePage").asInt() < 1 || o.get("sourcePage").asInt() > 1000)
                fail(p + "/sourcePage", "must be a page number");
            page = o.get("sourcePage").asInt();
        }
        String disposition = o.path("disposition").asString("");
        if (!DISPOSITIONS.contains(disposition)) fail(p + "/disposition", "must be one of " + new TreeSet<>(DISPOSITIONS));
        return new Meta(page, text(o, "sourceExcerpt", p, 500, false), disposition, text(o, "dispositionReason", p, 500, false));
    }
    private static String id(JsonNode o, String p, Set<String> ids) {
        String id = text(o, "id", p, 64, true);
        if (!id.matches(ID)) fail(p + "/id", "must start with a letter and use letters, digits, - or _ (at most 64)");
        if (!ids.add(id)) fail(p + "/id", "duplicates another element id");
        return id;
    }
    private static String ref(JsonNode o, String name, String p) {
        String value = text(o, name, p, 64, false);
        if (value != null && !value.matches(ID)) fail(p + "/" + name, "must reference an element id");
        return value;
    }
    private static String choice(JsonNode o, String name, String p, Set<String> allowed) {
        String value = text(o, name, p, 64, false);
        if (value != null && !allowed.contains(value)) fail(p + "/" + name, "must be one of " + new TreeSet<>(allowed));
        return value;
    }
    private static String text(JsonNode o, String name, String p, int max, boolean required) {
        JsonNode v = o.path(name);
        if (v.isMissingNode() || v.isNull()) { if (required) fail(p + "/" + name, "is required"); return null; }
        if (!v.isString() || v.asString().isBlank() || v.asString().length() > max) fail(p + "/" + name, "must be a nonblank string of at most " + max + " characters");
        return v.asString();
    }
    private static void object(JsonNode node, Set<String> allowed, String path) {
        if (node == null || !node.isObject()) fail(path, "must be an object");
        node.propertyNames().forEach(name -> { if (!allowed.contains(name)) fail(path + "/" + name, "is not a supported member"); });
    }
    private static void fail(String path, String message) { throw ApiException.malformed(path + " " + message + "."); }
}
