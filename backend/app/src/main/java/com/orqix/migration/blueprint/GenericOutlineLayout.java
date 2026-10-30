package com.orqix.migration.blueprint;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.orqix.migration.camunda.Identifiers;
import com.orqix.migration.blueprint.Transcription.*;

/**
 * Generic, layout-agnostic outline reader (docs/build/S91): recognises common blueprint concepts by section headings
 * (Case type, Stages, Steps, Personas/Roles, Fields/Data, Decisions/Rules, SLAs, Outcomes, Integrations) and by the
 * list items below them, with simple {@code key: value} attributes. It is NOT a parser of Pega's own Blueprint export
 * layout and claims no compatibility with it.
 * <p>
 * Text is data: nothing in a page can change which sections exist, add attributes the reader does not know, or alter
 * dispositions. Every element starts {@link Transcription#PROPOSED}; inferred values (step order, a single success
 * outcome after the last step) stay proposals that a reviewer confirms or corrects. Values the text does not state are
 * left empty and surface as findings.
 */
public final class GenericOutlineLayout implements BlueprintLayout {
    public static final String ID = "generic-outline";
    public static final String REVISION = "0.1";

    enum Section { CASE_TYPE, STAGES, STEPS, ROLES, FIELDS, DECISIONS, SLAS, OUTCOMES, INTEGRATIONS }
    private static final Map<String, Section> HEADINGS = new HashMap<>();
    static {
        for (String h : List.of("case type", "case types", "case type name")) HEADINGS.put(h, Section.CASE_TYPE);
        for (String h : List.of("stages", "case stages", "case lifecycle", "lifecycle")) HEADINGS.put(h, Section.STAGES);
        for (String h : List.of("steps", "process steps", "processes", "assignments", "workflow steps", "flow")) HEADINGS.put(h, Section.STEPS);
        for (String h : List.of("personas", "roles", "actors", "personas and roles", "personas & roles", "users and roles")) HEADINGS.put(h, Section.ROLES);
        for (String h : List.of("fields", "data", "data model", "data fields", "case data", "data objects", "properties")) HEADINGS.put(h, Section.FIELDS);
        for (String h : List.of("decisions", "decision rules", "rules", "business rules", "routing", "routing rules", "conditions")) HEADINGS.put(h, Section.DECISIONS);
        for (String h : List.of("sla", "slas", "service levels", "service level agreements", "goals and deadlines", "deadlines")) HEADINGS.put(h, Section.SLAS);
        for (String h : List.of("outcomes", "resolutions", "resolution", "end states", "results")) HEADINGS.put(h, Section.OUTCOMES);
        for (String h : List.of("integrations", "data sources", "systems", "external systems", "interfaces")) HEADINGS.put(h, Section.INTEGRATIONS);
    }
    private static final Pattern NUMBERING = Pattern.compile("^(?:section\\s+)?(?:\\d+(?:\\.\\d+)*|[ivx]+)[.)]?\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^(?:[-*\u2022\u2023\u25e6\u2013\u2014]|\\d{1,3}[.)]|[a-z][.)])\\s+(.*)$");
    private static final Pattern CASE_TYPE_INLINE = Pattern.compile("^case\\s+type\\s*[:\u2013\u2014-]\\s*(.{1,200})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(?i)(?:^|[;|,(]|\\s[\u2013\u2014-]\\s)\\s*(role|persona|actor|assigned to|stage|type|kind|next|then|on approve|on reject|if rejected|after|target|system|endpoint|step)\\s*[:=]\\s*([^;|,()]+)");
    private static final Pattern RULE = Pattern.compile(
            "(?i)\\bif\\s+(.+?)\\s*(>=|<=|!=|==|=|>|<|\u2265|\u2264|\u2260)\\s*(.+?)\\s+(?:then|go to|->|\u2192)\\s+(?:go to\\s+)?([^;]+)");
    private static final Pattern OTHERWISE = Pattern.compile("(?i)\\b(?:otherwise|else)\\s*[:,]?\\s*(?:go to\\s+)?([^;]+)");
    private static final Pattern DURATION = Pattern.compile("(?i)(\\d{1,6})\\s*(minutes?|mins?|hours?|hrs?|h|days?|d|business days?|working days?)?\\b");

    @Override public String id() { return ID; }
    @Override public String revision() { return REVISION; }

    private record Line(int page, String text) { }
    private record Raw(String name, Map<String, String> attributes, String parenthetical, Line line) { }

    @Override
    public Result transcribe(List<Page> pages, String requestedCaseType) {
        Map<Section, List<Raw>> items = new EnumMap<>(Section.class);
        String caseTypeName = null; Line caseTypeLine = null;
        Integer[] unclassified = new Integer[pages.size()];
        Arrays.fill(unclassified, 0);
        for (Page page : pages) {
            Section section = null;
            for (String rawLine : page.text().split("\\R")) {
                String text = rawLine.strip().replaceAll("\\s+", " ");
                if (text.isEmpty()) continue;
                Line line = new Line(page.number(), text.length() > 300 ? text.substring(0, 300) : text);
                Matcher inline = CASE_TYPE_INLINE.matcher(text);
                if (inline.matches() && caseTypeName == null) { caseTypeName = clean(inline.group(1)); caseTypeLine = line; section = Section.CASE_TYPE; continue; }
                Section heading = heading(text);
                if (heading != null) { section = heading; continue; }
                Matcher bullet = BULLET.matcher(text);
                if (section == Section.CASE_TYPE && caseTypeName == null && !bullet.matches()) { caseTypeName = clean(text); caseTypeLine = line; continue; }
                if (section != null && bullet.matches()) {
                    if (section == Section.CASE_TYPE) { if (caseTypeName == null) { caseTypeName = clean(bullet.group(1)); caseTypeLine = line; continue; } }
                    else { items.computeIfAbsent(section, s -> new ArrayList<>()).add(raw(bullet.group(1), line)); continue; }
                }
                unclassified[page.number() - 1]++;
            }
        }
        Set<String> ids = new HashSet<>();
        CaseType caseType = caseTypeName == null || caseTypeName.isBlank() ? null : new CaseType(trim(caseTypeName, 200), meta(caseTypeLine));
        List<Stage> stages = new ArrayList<>();
        for (Raw r : items.getOrDefault(Section.STAGES, List.of())) stages.add(new Stage(id("stage-", r.name(), ids), r.name(), meta(r.line())));
        List<Role> roles = new ArrayList<>();
        for (Raw r : items.getOrDefault(Section.ROLES, List.of())) roles.add(new Role(id("role-", r.name(), ids), r.name(), meta(r.line())));
        List<Field> fields = new ArrayList<>();
        for (Raw r : items.getOrDefault(Section.FIELDS, List.of())) {
            String hint = (r.parenthetical() + " " + r.attributes().getOrDefault("type", "")).toLowerCase(Locale.ROOT);
            fields.add(new Field(fieldKey(r.name(), ids), r.name(), fieldType(hint), hint.matches(".*\\brequired\\b.*") && !hint.contains("not required"), meta(r.line())));
        }
        List<Raw> stepItems = items.getOrDefault(Section.STEPS, List.of());
        List<String> stepIds = new ArrayList<>();
        for (Raw r : stepItems) stepIds.add(id("step-", r.name(), ids));
        List<Raw> decisionItems = items.getOrDefault(Section.DECISIONS, List.of());
        List<String> decisionIds = new ArrayList<>();
        for (Raw r : decisionItems) decisionIds.add(id("decision-", r.name(), ids));
        List<Raw> outcomeItems = items.getOrDefault(Section.OUTCOMES, List.of());
        List<Outcome> outcomes = new ArrayList<>();
        for (Raw r : outcomeItems) outcomes.add(new Outcome(id("outcome-", r.name(), ids), r.name(), outcomeType(r.parenthetical() + " " + r.attributes().getOrDefault("type", "")), meta(r.line())));
        // Names that routes can point at: steps, decisions and outcomes.
        Map<String, String> targets = new HashMap<>();
        for (int i = 0; i < stepItems.size(); i++) targets.putIfAbsent(norm(stepItems.get(i).name()), stepIds.get(i));
        for (int i = 0; i < decisionItems.size(); i++) targets.putIfAbsent(norm(decisionItems.get(i).name()), decisionIds.get(i));
        for (Outcome o : outcomes) targets.putIfAbsent(norm(o.name()), o.id());
        Map<String, String> fieldByName = new HashMap<>();
        for (Field f : fields) { fieldByName.putIfAbsent(norm(f.name()), f.id()); fieldByName.putIfAbsent(norm(f.id()), f.id()); }
        Map<String, String> decisionAfter = new HashMap<>();
        List<Decision> decisions = new ArrayList<>();
        for (int i = 0; i < decisionItems.size(); i++) {
            Raw r = decisionItems.get(i);
            String after = r.attributes().get("after");
            if (after != null && targets.containsKey(norm(after))) decisionAfter.putIfAbsent(targets.get(norm(after)), decisionIds.get(i));
            List<Rule> rules = new ArrayList<>();
            Matcher m = RULE.matcher(r.line().text());
            while (m.find() && rules.size() < 20) {
                rules.add(new Rule(fieldByName.get(norm(m.group(1))), operator(m.group(2)), trim(clean(m.group(3)), 200), targets.get(norm(m.group(4)))));
            }
            Matcher otherwise = OTHERWISE.matcher(r.line().text());
            decisions.add(new Decision(decisionIds.get(i), r.name(), rules, otherwise.find() ? targets.get(norm(otherwise.group(1))) : null, meta(r.line())));
        }
        String onlySuccess = outcomes.stream().filter(o -> "success".equals(o.type())).count() == 1
                ? outcomes.stream().filter(o -> "success".equals(o.type())).findFirst().get().id() : null;
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < stepItems.size(); i++) {
            Raw r = stepItems.get(i);
            Map<String, String> a = r.attributes();
            String role = first(a, "role", "persona", "actor", "assigned to");
            String roleId = role == null ? null : roles.stream().filter(x -> norm(x.name()).equals(norm(role))).map(Role::id).findFirst().orElse(null);
            String stage = a.get("stage");
            String stageId = stage == null ? null : stages.stream().filter(x -> norm(x.name()).equals(norm(stage))).map(Stage::id).findFirst().orElse(null);
            String explicitNext = first(a, "then", "next", "on approve");
            String next = explicitNext != null ? targets.get(norm(explicitNext))
                    : decisionAfter.containsKey(stepIds.get(i)) ? decisionAfter.get(stepIds.get(i))
                    : i + 1 < stepIds.size() ? stepIds.get(i + 1) : onlySuccess;
            String reject = first(a, "on reject", "if rejected");
            steps.add(new Step(stepIds.get(i), r.name(), stageId, stepType(r.parenthetical() + " " + first(a, "type", "kind") + " " + r.name()),
                    roleId, next, reject == null ? null : targets.get(norm(reject)), meta(r.line())));
        }
        List<Sla> slas = new ArrayList<>();
        for (Raw r : items.getOrDefault(Section.SLAS, List.of())) {
            String rest = r.line().text().substring(Math.min(r.line().text().length(), r.line().text().indexOf(r.name()) + r.name().length()));
            Matcher d = DURATION.matcher(rest);
            Integer amount = null; String unit = null;
            if (d.find()) {
                try { amount = Integer.valueOf(d.group(1)); } catch (NumberFormatException e) { amount = null; }
                if (amount != null && (amount < 1 || amount > 100000)) amount = null;
                unit = unit(d.group(2));
            }
            String step = first(r.attributes(), "step");
            String stepId = targets.get(norm(step == null ? r.name() : step));
            slas.add(new Sla(id("sla-", r.name(), ids), r.name(), stepId != null && stepId.startsWith("step-") ? stepId : null, amount, unit, meta(r.line())));
        }
        List<Integration> integrations = new ArrayList<>();
        for (Raw r : items.getOrDefault(Section.INTEGRATIONS, List.of())) {
            String target = first(r.attributes(), "target", "system", "endpoint");
            String step = first(r.attributes(), "step");
            integrations.add(new Integration(id("integration-", r.name(), ids), r.name(), target == null || target.isBlank() || target.matches("(?i)none|n/a|tbd|unknown") ? null : trim(target, 200),
                    step == null ? null : targets.get(norm(step)), meta(r.line())));
        }
        String start = !steps.isEmpty() ? steps.getFirst().id() : !decisions.isEmpty() ? decisions.getFirst().id() : null;
        return new Result(new Transcription(caseType, start, stages, roles, fields, steps, decisions, slas, outcomes, integrations), List.of(unclassified));
    }

    static Section heading(String text) {
        String normalized = NUMBERING.matcher(text.toLowerCase(Locale.ROOT)).replaceFirst("").replaceAll("[:.\u2013\u2014-]+$", "").strip();
        return normalized.split(" ").length <= 5 ? HEADINGS.get(normalized) : null;
    }

    private static Raw raw(String item, Line line) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(item);
        int firstAttribute = item.length();
        while (m.find()) {
            attributes.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), clean(m.group(2)));
            firstAttribute = Math.min(firstAttribute, m.start());
        }
        String parenthetical = "";
        int open = item.indexOf('(');
        if (open >= 0) { int close = item.indexOf(')', open); parenthetical = item.substring(open + 1, close > open ? close : item.length()); }
        String head = item.substring(0, Math.min(firstAttribute, open >= 0 ? open : item.length()));
        for (String separator : List.of(";", " \u2014 ", " \u2013 ", " - ", ":", "|")) {
            int at = head.indexOf(separator);
            if (at > 0) head = head.substring(0, at);
        }
        String name = clean(head);
        return new Raw(trim(name.isEmpty() ? clean(item) : name, 200), attributes, parenthetical, line);
    }

    private static Transcription.Meta meta(Line line) {
        return new Transcription.Meta(line == null ? null : line.page(), line == null ? null : trim(line.text(), 500), Transcription.PROPOSED, null);
    }
    static String id(String prefix, String name, Set<String> used) {
        String base = Identifiers.sanitize(prefix + name.toLowerCase(Locale.ROOT), false);
        return Identifiers.unique(base, used);
    }
    static String fieldKey(String name, Set<String> used) {
        StringBuilder key = new StringBuilder();
        for (String word : name.replaceAll("[^A-Za-z0-9 ]", " ").strip().split("\\s+")) {
            if (word.isEmpty()) continue;
            key.append(key.isEmpty() ? word.toLowerCase(Locale.ROOT) : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT));
        }
        String base = key.isEmpty() || !Character.isLetter(key.charAt(0)) ? "field" + key : key.toString();
        base = base.substring(0, Math.min(60, base.length()));
        String candidate = base; int n = 2;
        while (!used.add(candidate)) candidate = base + n++;
        return candidate;
    }
    private static String fieldType(String hint) {
        if (hint.matches(".*\\b(decimal|number|numeric|currency|money|amount)\\b.*")) return "number";
        if (hint.matches(".*\\b(integer|whole number|count|int)\\b.*")) return "integer";
        if (hint.matches(".*\\b(boolean|yes/no|yes-no|checkbox|true/false|flag)\\b.*")) return "boolean";
        if (hint.matches(".*\\b(date)\\b.*")) return "date";
        if (hint.matches(".*\\b(text|string|short text|long text|paragraph)\\b.*")) return "string";
        return null;
    }
    private static String stepType(String hint) {
        String h = hint.toLowerCase(Locale.ROOT);
        if (h.matches(".*\\b(approval|approve|authori[sz]e|authori[sz]ation|sign-off)\\b.*")) return "approval";
        if (h.matches(".*\\b(automated|automatic|system|service|integration)\\b.*")) return "automated";
        if (h.matches(".*\\b(task|manual|collect|form|capture|enter)\\b.*")) return "task";
        return null;
    }
    private static String outcomeType(String hint) {
        String h = hint.toLowerCase(Locale.ROOT);
        if (h.matches(".*\\b(rejected|reject|declined|denied|withdrawn|cancelled|canceled)\\b.*")) return "rejected";
        if (h.matches(".*\\b(success|successful|approved|completed|resolved|fulfilled)\\b.*")) return "success";
        return null;
    }
    private static String operator(String symbol) {
        return switch (symbol) { case "=" -> "=="; case "\u2265" -> ">="; case "\u2264" -> "<="; case "\u2260" -> "!="; default -> symbol; };
    }
    private static String unit(String word) {
        if (word == null) return null;
        String w = word.toLowerCase(Locale.ROOT);
        if (w.startsWith("business") || w.startsWith("working")) return "workingDays";
        if (w.startsWith("min")) return "minutes";
        if (w.startsWith("h")) return "hours";
        return "days";
    }
    private static String first(Map<String, String> attributes, String... keys) {
        for (String key : keys) if (attributes.get(key) != null && !attributes.get(key).isBlank()) return attributes.get(key);
        return null;
    }
    static String norm(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip(); }
    private static String clean(String text) { return text == null ? "" : text.strip().replaceAll("^[\\s:\u2013\u2014-]+|[\\s.;:,\u2013\u2014-]+$", ""); }
    static String trim(String text, int max) { return text.length() > max ? text.substring(0, max) : text; }
}
