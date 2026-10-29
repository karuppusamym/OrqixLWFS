package com.orqix.migration.pega.rules;

import java.util.*;
import com.orqix.migration.pega.rules.RuleArchive.Entry;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Effective-rule resolution over a decoded archive (docs/build/S112 "Rule resolution"): the single Rule-Application's
 * ruleset stack, class inheritance (pattern, then directed) and availability. Every candidate of a key gets an outcome,
 * so the chosen rule and the shadowed ones are both recorded as provenance. Circumstances are never evaluated: a
 * circumstanced candidate leaves the key unresolved. {@code tools/migration-equivalence/pega_native.py} implements the
 * same rules independently; tests compare both on the fixtures.
 */
public final class RuleResolution {
    public static final String DECODER = "pega-native-0.1";
    private static final Set<String> UNRESOLVED_TYPES = Set.of("Rule-Application", "Rule-Obj-Class");
    private static final Set<String> IDENTITY = Set.of("pxObjClass", "pyClassName", "pyRuleName", "pyRuleSet", "pyRuleSetVersion",
            "pyRuleAvailable", "pzInsKey", "pyLabel", "pyDescription");

    public record Candidate(String rule, String outcome) { }
    /** status: resolved, noCandidate, circumstanceUnresolved, ambiguous, blocked or finalOverridden. */
    public record Lookup(String key, String status, Entry chosen, List<Candidate> candidates) {
        public boolean resolved() { return chosen != null; }
    }
    public record Context(String className, List<String> chain, boolean acyclic, Map<String, Lookup> keys) { }
    public record Start(String rule, String kind, String context) { }
    public record Blocker(String code, List<String> paths) { }
    public record ClassDef(String parent, boolean pattern) { }
    private record Stacked(int index, int[] version) { }

    /** The archive-wide result. Contexts for case classes are computed eagerly; data classes on demand. */
    public static final class Analysis {
        private final String sourceSha256; private final List<Entry> entries; private final List<Blocker> blockers;
        private final LinkedHashMap<String, Stacked> stack; private final Map<String, ClassDef> classes;
        private final Map<String, Context> contexts = new LinkedHashMap<>(); private final Map<String, Context> extra = new HashMap<>();
        private final List<Start> starts = new ArrayList<>(); private final Entry application;

        private Analysis(String sha, List<Entry> entries, List<Blocker> blockers, LinkedHashMap<String, Stacked> stack,
                Map<String, ClassDef> classes, Entry application) {
            this.sourceSha256 = sha; this.entries = entries; this.blockers = blockers; this.stack = stack; this.classes = classes; this.application = application;
        }
        public String sourceSha256() { return sourceSha256; }
        public List<Entry> entries() { return entries; }
        public List<Blocker> archiveBlockers() { return Collections.unmodifiableList(blockers); }
        public Map<String, Context> contexts() { return Collections.unmodifiableMap(contexts); }
        public List<Start> startingPoints() { return Collections.unmodifiableList(starts); }
        public Entry application() { return application; }
        public boolean hasStack() { return stack != null; }
        public List<Map.Entry<String, String>> stack() {
            if (stack == null) return List.of();
            return stack.entrySet().stream().map(e -> Map.entry(e.getKey(), version(e.getValue().version()))).toList();
        }
        public Entry entry(String path) { return entries.stream().filter(e -> e.path().equals(path)).findFirst().orElse(null); }

        /** Resolution of the key in the class context (computed and cached for classes that are not case contexts). */
        public Lookup lookup(String context, String ruleType, String name) {
            Context c = context(context);
            String key = ruleType + " " + name.toUpperCase(Locale.ROOT);
            Lookup found = c.keys().get(key);
            return found != null ? found : new Lookup(key, "noCandidate", null, List.of());
        }

        public Context context(String className) {
            Context known = contexts.get(className);
            if (known != null) return known;
            return extra.computeIfAbsent(className, cls -> build(cls, this));
        }

        /** The comparable result (same shape as {@code pega_native.expectation}). */
        public ObjectNode expectation(Json json) {
            ObjectNode root = json.object();
            root.put("decoder", DECODER); root.put("sourceSha256", sourceSha256);
            ArrayNode list = root.putArray("entries");
            for (Entry e : entries) {
                ObjectNode n = list.addObject(); n.put("path", e.path()); n.put("sha256", e.sha256()); n.put("status", e.status());
                if (e.reason() != null) n.put("reason", e.reason());
                if (e.ruleType() != null) n.put("ruleType", e.ruleType());
                if (e.isRule()) {
                    n.put("className", e.className()); n.put("ruleName", e.ruleName()); n.put("ruleset", e.ruleset());
                    n.put("version", e.version()); n.put("availability", e.availability()); n.put("circumstanced", e.circumstanced());
                }
            }
            ArrayNode b = root.putArray("archiveBlockers");
            for (Blocker blocker : blockers) { ObjectNode n = b.addObject(); n.put("code", blocker.code()); var p = n.putArray("paths"); blocker.paths().forEach(p::add); }
            if (stack == null) root.putNull("stack");
            else { ArrayNode s = root.putArray("stack"); stack().forEach(e -> s.addArray().add(e.getKey()).add(e.getValue())); }
            ObjectNode cs = root.putObject("contexts");
            contexts.forEach((name, c) -> cs.set(name, contextJson(c, json)));
            ArrayNode st = root.putArray("startingPoints");
            for (Start s : starts) st.addObject().put("rule", s.rule()).put("kind", s.kind()).put("context", s.context());
            return root;
        }
    }

    public static JsonNode contextJson(Context c, Json json) {
        ObjectNode n = json.object();
        var chain = n.putArray("chain"); c.chain().forEach(chain::add);
        ObjectNode keys = n.putObject("keys");
        c.keys().forEach((key, l) -> {
            ObjectNode k = keys.putObject(key); k.put("status", l.status());
            if (l.chosen() == null) k.putNull("chosen"); else k.put("chosen", l.chosen().path());
            var cand = k.putArray("candidates");
            for (Candidate cd : l.candidates()) cand.addObject().put("rule", cd.rule()).put("outcome", cd.outcome());
        });
        return n;
    }

    private RuleResolution() { }

    public static Analysis analyze(byte[] bytes) {
        List<Entry> entries = RuleArchive.decode(bytes);
        List<Blocker> blockers = new ArrayList<>();
        List<String> refused = entries.stream().filter(e -> e.status().equals("refused")).map(Entry::path).sorted().toList();
        if (!refused.isEmpty()) blockers.add(new Blocker("PEGA_NATIVE_ARCHIVE_INCOMPLETE", refused));
        Map<String, List<String>> keys = new LinkedHashMap<>();
        for (Entry e : entries) if (e.insKey() != null) keys.computeIfAbsent(e.insKey().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(e.path());
        for (List<String> paths : keys.values()) if (paths.size() > 1) blockers.add(new Blocker("PEGA_NATIVE_DUPLICATE_KEY", paths.stream().sorted().toList()));
        List<Entry> rules = entries.stream().filter(Entry::isRule).toList();
        Entry[] app = new Entry[1];
        LinkedHashMap<String, Stacked> stack = application(rules, blockers, app);
        Map<String, ClassDef> classes = stack == null ? Map.of() : classes(rules, stack, blockers);
        Analysis analysis = new Analysis(Json.sha256Hex(bytes), entries, blockers, stack, classes, app[0]);
        if (stack == null) return analysis;
        SortedSet<String> roots = new TreeSet<>();
        for (Entry r : rules) if (r.ruleType().equals("Rule-Obj-CaseType") || r.ruleType().equals("Rule-Obj-Flow") && "true".equals(textOf(r.page(), "pyIsStartingFlow")))
            roots.add(r.className());
        for (String root : roots) {
            Context c = build(root, analysis);
            if (!c.acyclic()) { blockers.add(new Blocker("PEGA_NATIVE_INHERITANCE_CYCLE", List.of(root))); continue; }
            analysis.contexts.put(root, c);
            for (Lookup l : c.keys().values()) {
                Entry chosen = l.chosen();
                if (chosen == null || !chosen.className().equals(root)) continue;
                if (chosen.ruleType().equals("Rule-Obj-CaseType")) analysis.starts.add(new Start(chosen.path(), "caseType", root));
                else if (chosen.ruleType().equals("Rule-Obj-Flow") && "true".equals(textOf(chosen.page(), "pyIsStartingFlow")))
                    analysis.starts.add(new Start(chosen.path(), "flow", root));
            }
        }
        return analysis;
    }

    private static String textOf(Page page, String name) { return page.fields().get(name) instanceof Page.Text t ? t.text() : null; }

    private static Context build(String context, Analysis a) {
        List<String> chain = new ArrayList<>(); Set<String> visited = new HashSet<>(); String current = context; boolean acyclic = true;
        while (true) {
            if (!visited.add(current)) { acyclic = false; break; }
            if (!chain.contains(current)) chain.add(current);
            ClassDef def = a.classes.get(current);
            if (def == null) break;
            if (def.pattern()) {
                String[] parts = current.split("-", -1);
                for (int size = parts.length - 1; size > 0; size--) {
                    String prefix = String.join("-", Arrays.copyOfRange(parts, 0, size));
                    if (!prefix.isEmpty() && !chain.contains(prefix)) chain.add(prefix);
                }
            }
            if (def.parent().equals("@baseclass")) break;
            current = def.parent();
        }
        if (!chain.contains("@baseclass")) chain.add("@baseclass");
        Map<String, List<Entry>> grouped = new TreeMap<>();
        if (acyclic) for (Entry r : a.entries) if (r.isRule() && !UNRESOLVED_TYPES.contains(r.ruleType()) && chain.contains(r.className()))
            grouped.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r);
        Map<String, Lookup> keys = new LinkedHashMap<>();
        grouped.forEach((key, candidates) -> keys.put(key, resolve(key, candidates, chain, a.stack)));
        return new Context(context, List.copyOf(chain), acyclic, Collections.unmodifiableMap(keys));
    }

    private static int compareVersion(int[] a, int[] b) { return Arrays.compare(a, b); }

    private static List<Integer> rank(Entry e, List<String> chain, Map<String, Stacked> stack) {
        int[] v = e.versionParts();
        return List.of(chain.indexOf(e.className()), stack.get(e.ruleset()).index(), -v[0], -v[1], -v[2]);
    }

    private static int compareRank(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < a.size(); i++) { int c = Integer.compare(a.get(i), b.get(i)); if (c != 0) return c; }
        return 0;
    }

    static Lookup resolve(String key, List<Entry> candidates, List<String> chain, Map<String, Stacked> stack) {
        Map<String, String> outcome = new HashMap<>(); List<Entry> live = new ArrayList<>();
        for (Entry c : candidates) {
            Stacked s = stack.get(c.ruleset());
            if (s == null) { outcome.put(c.path(), "rulesetNotInApplication"); continue; }
            int[] v = c.versionParts(); int[] sv = s.version();
            if (v[0] != sv[0] || v[1] > sv[1] || v[1] == sv[1] && v[2] > sv[2]) outcome.put(c.path(), "versionNotInApplication");
            else if (c.availability().equals("No")) outcome.put(c.path(), "notAvailable");
            else live.add(c);
        }
        for (Entry w : live) {
            if (!w.availability().equals("Withdrawn")) continue;
            outcome.put(w.path(), "withdrawn");
            for (Entry c : live) if (c != w && c.className().equals(w.className()) && c.ruleset().equals(w.ruleset())
                    && c.versionParts()[0] == w.versionParts()[0] && compareVersion(c.versionParts(), w.versionParts()) < 0)
                outcome.put(c.path(), "maskedByWithdrawn");
        }
        List<Entry> ranked = live.stream().filter(c -> !outcome.containsKey(c.path()))
                .sorted((x, y) -> { int c = compareRank(rank(x, chain, stack), rank(y, chain, stack)); return c != 0 ? c : x.path().compareTo(y.path()); }).toList();
        String status = "resolved"; Entry chosen = null;
        List<Entry> circumstanced = ranked.stream().filter(Entry::circumstanced).toList();
        List<Entry> base = ranked.stream().filter(c -> !c.circumstanced()).toList();
        if (ranked.isEmpty()) status = "noCandidate";
        else if (!circumstanced.isEmpty()) {
            status = "circumstanceUnresolved";
            circumstanced.forEach(c -> outcome.put(c.path(), "circumstanced"));
            for (int i = 0; i < base.size(); i++) outcome.put(base.get(i).path(), i == 0 ? "baseFallback" : "lowerRanked");
        } else if (ranked.size() > 1 && compareRank(rank(ranked.get(0), chain, stack), rank(ranked.get(1), chain, stack)) == 0) {
            status = "ambiguous";
            var top = rank(ranked.get(0), chain, stack);
            for (Entry c : ranked) outcome.put(c.path(), compareRank(rank(c, chain, stack), top) == 0 ? "duplicate" : "lowerRanked");
        } else if (ranked.get(0).availability().equals("Blocked")) {
            status = "blocked"; outcome.put(ranked.get(0).path(), "blocked");
            ranked.stream().skip(1).forEach(c -> outcome.put(c.path(), "belowBlocked"));
        } else {
            Entry best = ranked.get(0);
            List<Entry> finals = ranked.stream().skip(1).filter(c -> c.availability().equals("Final") && !c.ruleset().equals(best.ruleset())).toList();
            if (!finals.isEmpty()) {
                status = "finalOverridden"; outcome.put(best.path(), "overridesFinal");
                ranked.stream().skip(1).forEach(c -> outcome.put(c.path(), finals.contains(c) ? "finalOverridden" : "lowerRanked"));
            } else {
                chosen = best; outcome.put(best.path(), "selected");
                ranked.stream().skip(1).forEach(c -> outcome.put(c.path(), "lowerRanked"));
            }
        }
        List<Candidate> list = candidates.stream().sorted(Comparator.comparing(Entry::path)).map(c -> new Candidate(c.path(), outcome.get(c.path()))).toList();
        return new Lookup(key, status, chosen, list);
    }

    private static LinkedHashMap<String, Stacked> application(List<Entry> rules, List<Blocker> blockers, Entry[] found) {
        List<Entry> apps = rules.stream().filter(r -> r.ruleType().equals("Rule-Application") && (r.availability().equals("Yes") || r.availability().equals("Final"))).toList();
        if (apps.isEmpty()) { blockers.add(new Blocker("PEGA_NATIVE_APPLICATION_MISSING", List.of())); return null; }
        if (apps.size() > 1) { blockers.add(new Blocker("PEGA_NATIVE_APPLICATION_AMBIGUOUS", apps.stream().map(Entry::path).sorted().toList())); return null; }
        Entry app = apps.getFirst(); found[0] = app; Page page = app.page();
        LinkedHashMap<String, Stacked> stack = new LinkedHashMap<>();
        try {
            Set<String> allowed = new HashSet<>(IDENTITY); allowed.addAll(Set.of("pyRuleSets", "pyBuiltOnApplications"));
            page.allow(allowed);
            List<Page> rows = page.pages("pyRuleSets");
            if (rows.isEmpty() || rows.size() > 20) throw new Page.Grammar(page.pathOf("pyRuleSets"), "1 to 20 rulesets are required");
            for (Page row : rows) {
                row.allow(Set.of("pyRuleSetName", "pyRuleSetVersion"));
                String name = row.text("pyRuleSetName"), version = row.text("pyRuleSetVersion");
                if (name == null || !RuleArchive.NAME.matcher(name).matches() || stack.containsKey(name) || version == null || !RuleArchive.VERSION.matcher(version).matches())
                    throw new Page.Grammar(row.path(), "each ruleset needs a unique name and an NN-NN-NN version");
                stack.put(name, new Stacked(stack.size(), RuleArchive.parts(version)));
            }
            for (Page row : page.pages("pyBuiltOnApplications")) {
                row.allow(Set.of("pyApplicationName", "pyApplicationVersion"));
                if (!"PegaRULES".equals(row.text("pyApplicationName"))) throw new Page.Grammar(row.path(), "only the PegaRULES platform may be a built-on application");
            }
        } catch (Page.Grammar e) {
            blockers.add(new Blocker("PEGA_NATIVE_APPLICATION_INVALID", List.of(app.path()))); return null;
        }
        return stack;
    }

    private static Map<String, ClassDef> classes(List<Entry> rules, Map<String, Stacked> stack, List<Blocker> blockers) {
        Map<String, List<Entry>> defs = new TreeMap<>();
        for (Entry r : rules) {
            if (!r.ruleType().equals("Rule-Obj-Class") || !stack.containsKey(r.ruleset()) || !(r.availability().equals("Yes") || r.availability().equals("Final"))) continue;
            int[] v = r.versionParts(), sv = stack.get(r.ruleset()).version();
            if (v[0] != sv[0] || v[1] > sv[1] || v[1] == sv[1] && v[2] > sv[2]) continue;
            defs.computeIfAbsent(r.className(), k -> new ArrayList<>()).add(r);
        }
        Map<String, ClassDef> result = new HashMap<>();
        defs.forEach((name, found) -> {
            found.sort(Comparator.<Entry>comparingInt(r -> stack.get(r.ruleset()).index()).thenComparing((x, y) -> compareVersion(y.versionParts(), x.versionParts())));
            if (found.size() > 1 && stack.get(found.get(0).ruleset()).index() == stack.get(found.get(1).ruleset()).index() && found.get(0).version().equals(found.get(1).version())) {
                blockers.add(new Blocker("PEGA_NATIVE_CLASS_AMBIGUOUS", found.subList(0, 2).stream().map(Entry::path).sorted().toList())); return;
            }
            Page p = found.getFirst().page();
            Set<String> allowed = new HashSet<>(IDENTITY); allowed.addAll(Set.of("pyDerivesFrom", "pyClassType", "pyPatternInheritance"));
            String pattern = p.fields().containsKey("pyPatternInheritance") ? textOf(p, "pyPatternInheritance") : "true";
            if (!allowed.containsAll(p.fields().keySet()) || !name.equals(textOf(p, "pyRuleName")) || RuleArchive.blank(textOf(p, "pyDerivesFrom"))
                    || !Set.of("Abstract", "Concrete").contains(String.valueOf(textOf(p, "pyClassType"))) || !Set.of("true", "false").contains(String.valueOf(pattern))) {
                blockers.add(new Blocker("PEGA_NATIVE_CLASS_INVALID", List.of(found.getFirst().path()))); return;
            }
            result.put(name, new ClassDef(textOf(p, "pyDerivesFrom"), "true".equals(pattern)));
        });
        return result;
    }

    static String version(int[] v) { return String.format(Locale.ROOT, "%02d-%02d-%02d", v[0], v[1], v[2]); }
}
