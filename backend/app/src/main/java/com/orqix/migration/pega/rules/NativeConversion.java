package com.orqix.migration.pega.rules;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import com.orqix.migration.camunda.*;
import com.orqix.migration.dmn.DmnAnalyzer;
import com.orqix.migration.dmn.DmnTable;
import com.orqix.migration.pega.rules.RuleArchive.Entry;
import com.orqix.migration.pega.rules.RuleResolution.*;
import com.orqix.migration.pega.rules.RuleResolution.Analysis;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts one starting point of a resolved native archive (an effective case type, or an effective starting flow) into a
 * canonical workflow draft (docs/build/S112 "Conversion"). Every rule the starting point references is resolved in the
 * case class context; supported rule types are converted, everything else becomes a blocker that names its source path.
 * Flows go through the same BPMN analyzer and converter as the XPDL and Blueprint imports, decision tables and trees
 * through the DMN analyzer, so the normal compiler still gates the draft. Workbasket-to-group mappings and the
 * approve/reject meaning of flow actions are never inferred; they are supplied explicitly at conversion.
 */
public final class NativeConversion {
    public static final String ADAPTER = "pega-native-0.1";
    private static final Pattern SHAPE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");
    private static final Pattern FIELD_KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Pattern PROPERTY_REF = Pattern.compile("\\.([A-Za-z][A-Za-z0-9_]{0,63})(?:\\.([A-Za-z][A-Za-z0-9_]{0,63}))?");
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,8})?");
    private static final Pattern TEXT_LITERAL = Pattern.compile("\"([A-Za-z0-9][A-Za-z0-9 _.-]{0,99})\"");
    private static final Set<String> IDENTITY = Set.of("pxObjClass", "pyClassName", "pyRuleName", "pyRuleSet", "pyRuleSetVersion",
            "pyRuleAvailable", "pzInsKey", "pyLabel", "pyDescription", "pyCircumstanceProp", "pyCircumstanceVal");
    private static final Set<String> IMPLICIT = Set.of("Rule-Declare-Expressions", "Rule-Declare-Trigger", "Rule-Declare-OnChange",
            "Rule-Declare-Constraints", "Rule-Declare-Index");
    private static final Map<String, String> WIDGETS = Map.of("pxTextInput", "text", "pxTextArea", "textarea", "pxInteger", "number",
            "pxNumber", "number", "pxCurrency", "money", "pxDropdown", "select", "pxRadioButtons", "radio", "pxCheckbox", "checkbox", "pxDateTime", "");

    /**
     * The conversion result of one source rule. {@code resolution}: the rule's outcome in the case context ({@code selected},
     * a shadowing reason, or {@code outsideContext}). {@code conversion}: converted, review, blocked, notUsed, shadowed or
     * supported. {@code targetSha256} hashes the mapping-independent target fragment (DMN bytes, field schema, layout,
     * due time, stages or generated flow fragment).
     */
    public record RuleResult(String rule, String ruleType, String className, String ruleName, String ruleset, String version,
            String availability, String sourceSha256, String role, String resolution, String conversion, String targetKind,
            List<String> targets, String targetSha256, List<String> reasons) { }
    /** An assignment to map: its work queue (a group mapping) and its two flow actions (one approves, one rejects). */
    public record Assignment(String id, String label, String workQueue, String flow, List<Action> actions) { }
    public record Action(String connectorId, String flowAction) { }
    public record Plan(Start start, String name, String label, List<RuleResult> rules, List<Assignment> assignments, List<Report.Issue> sourceIssues,
            boolean blocked, Build build, Report.CompatibilityReport report) { }
    record Build(String bpmn, ObjectNode inputSchema, ObjectNode inputUi, Map<String, DmnTable> tables, Map<String, Integer> dueMinutes,
            Map<String, String> endKinds, List<StagePlan> stages, Map<String, String> paths, String processId, String processName,
            Set<String> decisionTasks, String description) { }
    record StagePlan(String id, String label, Set<String> bpmnNodes, String sla) { }
    public record Result(ObjectNode definition, Report.CompatibilityReport report, Map<String, String> sourceMap) { }

    private NativeConversion() { }

    /** Archive-level report when no starting point can be planned (no application, no case type, cycles). */
    public static Report.CompatibilityReport archiveReport(Analysis a) {
        List<Report.Issue> issues = new ArrayList<>(archiveIssues(a));
        for (Entry e : a.entries()) issues.add(new Planner(a, null, null).entryIssue(e));
        if (a.startingPoints().isEmpty() && a.archiveBlockers().isEmpty())
            issues.add(issue("archive", null, "archive", Report.BLOCKER, "PEGA_NATIVE_NO_STARTING_POINT",
                "The archive has no effective case type and no flow marked as a starting flow, so there is nothing to convert.",
                "Export the application with its case type, or mark the flow to convert as a starting flow.", null, "archive"));
        return report(a, null, null, issues, null, false);
    }

    public static Plan plan(Analysis a, Start start, Json json) {
        Planner p = new Planner(a, start, json);
        p.run();
        return p.finish();
    }

    /** Builds the draft definition with the reviewer's explicit mappings. Blocked plans never reach this point. */
    public static Result convert(Plan plan, UUID migrationId, JsonNode request, Json json) {
        Build b = plan.build();
        ObjectNode effective = json.object();
        effective.put("workflowKey", request.path("workflowKey").asString(""));
        effective.put("name", request.path("name").asString(""));
        effective.set("groupMappings", request.path("groupMappings").isObject() ? request.path("groupMappings").deepCopy() : json.object());
        effective.set("approvalRoutes", request.path("approvalRoutes").isObject() ? request.path("approvalRoutes").deepCopy() : json.object());
        effective.set("inputSchema", b.inputSchema().deepCopy());
        effective.put("acknowledgeInfo", request.path("acknowledgeInfo").asBoolean(false));
        effective.put("acknowledgeReview", request.path("acknowledgeReview").asBoolean(false));
        ObjectNode decisions = effective.putObject("decisionMappings");
        for (String task : b.decisionTasks()) decisions.putObject(task).put("migrationId", migrationId.toString());
        var analysis = Analyzer.analyze(b.bpmn(), Limits.DEFAULT);
        var converted = Converter.convert(analysis, effective, json, Map.of(), b.tables(), Map.of());
        boolean ackInfo = request.path("acknowledgeInfo").asBoolean(false), ackReview = request.path("acknowledgeReview").asBoolean(false);
        var report = withConverter(plan, converted.report(), ackInfo, ackReview);
        Map<String, String> sourceMap = new LinkedHashMap<>();
        if (converted.definition() == null) return new Result(null, report, sourceMap);
        ObjectNode def = converted.definition();
        Map<String, String> canonical = converted.sourceMap() == null ? Map.of() : converted.sourceMap();
        for (JsonNode node : def.path("nodes")) {
            String id = node.path("id").asString();
            String source = canonical.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey)
                    .filter(k -> b.dueMinutes().containsKey(k) || b.endKinds().containsKey(k)).findFirst().orElse(null);
            if (source == null) continue;
            ObjectNode config = (ObjectNode) node.path("config");
            Integer minutes = b.dueMinutes().get(source);
            if (minutes != null && "approval".equals(node.path("type").asString())) {
                config.remove("dueAfterHours"); config.remove("dueAfterMinutes");
                if (minutes % 60 == 0 && minutes / 60 <= 720) config.put("dueAfterHours", minutes / 60); else config.put("dueAfterMinutes", minutes);
            }
            String kind = b.endKinds().get(source);
            if (kind != null && "end".equals(node.path("type").asString())) config.put("outcomeKind", kind);
        }
        if (!b.stages().isEmpty()) {
            ArrayNode stages = def.putArray("stages");
            for (StagePlan s : b.stages()) {
                Set<String> nodes = new LinkedHashSet<>();
                for (String n : s.bpmnNodes()) if (canonical.containsKey(n)) nodes.add(canonical.get(n));
                if (nodes.isEmpty()) continue;
                ObjectNode stage = stages.addObject(); stage.put("id", s.id()); stage.put("label", s.label());
                ArrayNode list = stage.putArray("nodes"); nodes.forEach(list::add);
                if (s.sla() != null) stage.put("sla", s.sla());
            }
            if (stages.isEmpty()) def.remove("stages");
        }
        if (b.inputUi() != null) def.set("inputUi", b.inputUi().deepCopy());
        ObjectNode metadata = def.has("metadata") && def.path("metadata").isObject() ? (ObjectNode) def.path("metadata") : def.putObject("metadata");
        metadata.put("description", b.description());
        canonical.forEach((bpmnId, target) -> sourceMap.put(b.paths().getOrDefault(bpmnId, bpmnId), target));
        for (RuleResult r : plan.rules()) if (!r.targets().isEmpty() && ("converted".equals(r.conversion()) || "review".equals(r.conversion())))
            sourceMap.putIfAbsent(r.rule(), String.join(",", r.targets()));
        return new Result(def, report, sourceMap);
    }

    private static Report.CompatibilityReport withConverter(Plan plan, Report.CompatibilityReport converted, boolean ackInfo, boolean ackReview) {
        List<Report.Issue> issues = new ArrayList<>();
        for (Report.Issue i : plan.sourceIssues()) issues.add(acknowledge(i, ackInfo, ackReview));
        issues.addAll(converterIssues(plan.build(), converted));
        Analysis none = null;
        return report(none, plan, converted, issues, plan.report().sourceVersion(), true);
    }

    private static Report.Issue acknowledge(Report.Issue i, boolean ackInfo, boolean ackReview) {
        if (Report.OPEN.equals(i.resolutionStatus()) && (Report.INFO.equals(i.severity()) && ackInfo || Report.REVIEW.equals(i.severity()) && ackReview))
            return i.withResolution(Report.ACKNOWLEDGED);
        return i;
    }

    /** Converter findings on the generated BPMN, with their generated element ids replaced by source paths. */
    private static List<Report.Issue> converterIssues(Build b, Report.CompatibilityReport converted) {
        List<Report.Issue> out = new ArrayList<>();
        if (converted == null || b == null) return out;
        for (Report.Issue i : converted.issues()) {
            if (b.decisionTasks().contains(i.sourceElementId()) && Set.of("DMN_IMPORT_NOT_SUPPORTED", "DECISION_MAPPING_REQUIRED").contains(i.reasonCode())) continue;
            String path = b.paths().getOrDefault(i.sourceElementId(), b.paths().getOrDefault(i.sourceElementId() == null ? "" : i.sourceElementId().replaceFirst("-route$", ""), "generated"));
            out.add(new Report.Issue(i.sourceElementId(), i.sourceName(), i.sourceKind(), i.severity(), i.reasonCode(), i.explanation(),
                    i.suggestedAction(), i.targetNodeId(), i.resolutionStatus(), path));
        }
        return out;
    }

    private static Report.CompatibilityReport report(Analysis a, Plan plan, Report.CompatibilityReport converted, List<Report.Issue> issues,
            String version, boolean withConverter) {
        boolean ready = withConverter && converted != null && converted.publishable()
                && issues.stream().noneMatch(i -> i.blocksPublishing() || Report.INFO.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus()));
        var required = converted == null ? new Report.RequiredMappings(List.of(), List.of(), List.of())
                : new Report.RequiredMappings(converted.requiredMappings().groups(), converted.requiredMappings().approvalTasks(), List.of());
        String hash = plan != null ? plan.report().sourceHash() : a.sourceSha256();
        return new Report.CompatibilityReport("pega", version, ADAPTER, hash, plan == null ? null : plan.name(), plan == null ? null : plan.label(),
                Report.ElementCounts.of(issues), List.copyOf(issues),
                List.of("Native rule pages are decoded with a strict synthetic grammar (docs/build/S112). Fidelity against genuine Pega exports is customer qualification (AC01/EXT01), not established here.",
                        "Effective rules are resolved from the archive's own application ruleset stack, class inheritance and availability. Circumstances, date ranges, access and built-on applications other than PegaRULES are not evaluated; affected rules block.",
                        "Activities, Java, connectors, declarative rules and other unsupported rule types are never executed; when referenced they block conversion.",
                        "Workbasket-to-group mappings and the approve/reject meaning of flow actions are supplied explicitly. No live cases, history, identities or credentials are migrated."),
                required, ready);
    }

    private static List<Report.Issue> archiveIssues(Analysis a) {
        List<Report.Issue> out = new ArrayList<>();
        for (Blocker b : a.archiveBlockers()) {
            if (b.code().equals("PEGA_NATIVE_ARCHIVE_INCOMPLETE")) continue; // reported on each refused entry
            String detail = switch (b.code()) {
                case "PEGA_NATIVE_APPLICATION_MISSING" -> "The archive has no available Rule-Application, so the ruleset stack used for rule resolution is unknown.";
                case "PEGA_NATIVE_APPLICATION_AMBIGUOUS" -> "The archive has more than one available Rule-Application; the application is not chosen implicitly.";
                case "PEGA_NATIVE_APPLICATION_INVALID" -> "The Rule-Application has an unsupported ruleset list or built-on application. Only PegaRULES may be built on; its rules are not in the archive.";
                case "PEGA_NATIVE_DUPLICATE_KEY" -> "Two instances share one pzInsKey. The archive is inconsistent.";
                case "PEGA_NATIVE_CLASS_AMBIGUOUS" -> "Two class rules of the same rank define this class.";
                case "PEGA_NATIVE_CLASS_INVALID" -> "The class rule has unsupported properties or no parent class.";
                case "PEGA_NATIVE_INHERITANCE_CYCLE" -> "The class inheritance of this context is cyclic.";
                default -> b.code();
            };
            String first = b.paths().isEmpty() ? "archive" : b.paths().getFirst();
            out.add(issue(first, String.join(", ", b.paths()), "archive", Report.BLOCKER, b.code(), detail,
                    "Correct the export; nothing is converted while the archive cannot be resolved.", null, first));
        }
        return out;
    }

    static Report.Issue issue(String id, String name, String kind, String severity, String code, String explanation, String action, String target, String path) {
        return new Report.Issue(id, name, kind, severity, code, explanation, action, target, Report.OPEN, path);
    }

    /** Planning state for one starting point. */
    private static final class Planner {
        final Analysis a; final Start start; final Json json; final String ctx;
        final Map<String, Use> uses = new LinkedHashMap<>(); final List<Report.Issue> sub = new ArrayList<>();
        final LinkedHashMap<String, Field> fields = new LinkedHashMap<>(); final Map<String, String> propertyKeys = new HashMap<>();
        final Map<String, DmnTable> tables = new LinkedHashMap<>(); final Map<String, Integer> due = new LinkedHashMap<>();
        final Map<String, String> endKinds = new LinkedHashMap<>(); final List<StagePlan> stages = new ArrayList<>();
        final Map<String, String> paths = new LinkedHashMap<>(); final Set<String> decisionTasks = new LinkedHashSet<>();
        final Set<String> contexts = new LinkedHashSet<>(); final StringBuilder xml = new StringBuilder(); final Set<String> conditionFields = new HashSet<>(); final Map<String, DmnTable> taskTables = new LinkedHashMap<>(); final List<Assignment> assignments = new ArrayList<>();
        ObjectNode inputUi; String processId; String processName; String label;

        Planner(Analysis a, Start start, Json json) { this.a = a; this.start = start; this.json = json; this.ctx = start == null ? null : start.context(); if (ctx != null) contexts.add(ctx); }

        final class Use {
            final Entry e; final String role; String conversion = "converted"; String targetKind; final List<String> targets = new ArrayList<>();
            String fragment; final List<String> reasons = new ArrayList<>(); String explanation; String review;
            Use(Entry e, String role) { this.e = e; this.role = role; }
        }
        static final class Field { String key; ObjectNode schema; boolean required; String rule; String description; }
        record Step(String stageId, String flowName, String fromPath, Use from) { }
        record Shape(String id, String type, Page page) { }
        record FlowDef(Step step, Entry entry, Use use, LinkedHashMap<String, Shape> shapes, List<Page> connectors, Page start) { }

        Use use(Entry e, String role) { return uses.computeIfAbsent(e.path(), k -> new Use(e, role)); }

        void block(Use u, String code, String explanation) {
            u.conversion = "blocked";
            if (!u.reasons.contains(code)) u.reasons.add(code);
            if (u.explanation == null) u.explanation = explanation;
        }
        void review(Use u, String code, String explanation) {
            if (!"blocked".equals(u.conversion)) u.conversion = "review";
            if (!u.reasons.contains(code)) u.reasons.add(code);
            u.review = u.review == null ? explanation : u.review + " " + explanation;
        }
        void subBlock(Use u, String elementId, String kind, String code, String explanation, String path) {
            block(u, code, explanation);
            sub.add(issue(u.e.path() + "#" + elementId, elementId, kind, Report.BLOCKER, code, explanation,
                    "Rebuild this behavior explicitly in Orqix; no draft is created while it is open.", null, path));
        }
        void refuse(Use u, Page.Grammar g) {
            subBlock(u, g.sourcePath().substring(g.sourcePath().indexOf('#') + 1), u.e.ruleType(), "PEGA_NATIVE_GRAMMAR_UNSUPPORTED",
                    "Outside the supported rule grammar: " + g.getMessage() + ".", g.sourcePath());
        }
        static Set<String> with(String... extra) { Set<String> s = new HashSet<>(IDENTITY); s.addAll(List.of(extra)); return s; }

        /** Resolves a referenced rule in a class context; a failed resolution blocks the referencing element. */
        Entry lookup(String context, String type, String name, Use from, String fromId, String fromPath, String role) {
            contexts.add(context);
            Lookup l = a.lookup(context, type, name);
            if (l.resolved()) return l.chosen();
            String code = switch (l.status()) {
                case "blocked" -> "PEGA_NATIVE_RULE_BLOCKED"; case "circumstanceUnresolved" -> "PEGA_NATIVE_CIRCUMSTANCE_UNRESOLVED";
                case "ambiguous" -> "PEGA_NATIVE_RULE_AMBIGUOUS"; case "finalOverridden" -> "PEGA_NATIVE_FINAL_OVERRIDDEN";
                default -> "PEGA_NATIVE_REFERENCE_UNRESOLVED";
            };
            String why = switch (l.status()) {
                case "blocked" -> "the highest-ranked candidate is Blocked, so rule resolution finds no rule";
                case "circumstanceUnresolved" -> "a circumstanced variant exists and circumstances depend on case data at run time";
                case "ambiguous" -> "two candidates have the same class, ruleset and version";
                case "finalOverridden" -> "a Final rule is overridden from another ruleset";
                default -> "no available candidate exists in the archive for the application stack and class " + context;
            };
            subBlock(from, fromId, "reference", code, type + " '" + name + "' cannot be resolved: " + why + ".", fromPath);
            for (Candidate c : l.candidates()) {
                Entry e = a.entry(c.rule());
                Use u = use(e, role);
                block(u, code, "Rule resolution for " + type + " '" + name + "' in " + context + " stopped (" + l.status() + "); this candidate's outcome is " + c.outcome() + ".");
            }
            return null;
        }

        void run() {
            Entry root = a.entry(start.rule());
            Use u = use(root, start.kind().equals("caseType") ? "caseType" : "flow");
            processId = Identifiers.sanitize(root.ruleName(), false);
            processName = root.page().text("pyLabel") == null ? root.ruleName() : root.page().text("pyLabel");
            label = processName;
            List<Step> steps = new ArrayList<>(); String harness = null;
            if (start.kind().equals("caseType")) {
                try {
                    Page p = root.page(); p.allow(with("pyNewHarness", "pyStages"));
                    List<Page> rows = p.pages("pyStages");
                    if (rows.isEmpty() || rows.size() > 20) throw new Page.Grammar(p.pathOf("pyStages"), "1 to 20 stages are required");
                    Set<String> ids = new HashSet<>();
                    for (Page s : rows) {
                        s.allow(Set.of("pyStageID", "pyStageName", "pyServiceLevelName", "pyProcesses"));
                        String id = s.text("pyStageID"), name = s.text("pyStageName");
                        if (id == null || !KEY.matcher(id).matches() || !ids.add(id)) throw new Page.Grammar(s.pathOf("pyStageID"), "a unique lowercase stage key is required");
                        if (RuleArchive.blank(name) || name.length() > 80) throw new Page.Grammar(s.pathOf("pyStageName"), "a stage name of at most 80 characters is required");
                        List<Page> processes = s.pages("pyProcesses");
                        if (processes.isEmpty() || processes.size() > 5) throw new Page.Grammar(s.pathOf("pyProcesses"), "1 to 5 processes per stage are required");
                        String sla = null;
                        if (s.text("pyServiceLevelName") != null) {
                            Entry level = lookup(ctx, "Rule-Obj-ServiceLevel", s.text("pyServiceLevelName"), u, id, s.pathOf("pyServiceLevelName"), "serviceLevel");
                            Integer minutes = level == null ? null : serviceLevel(level, 129_600, "stage time limit");
                            if (minutes != null) { sla = duration(minutes); use(level, "serviceLevel").targets.add("stage:" + id); }
                        }
                        stages.add(new StagePlan(id, name, new LinkedHashSet<>(), sla));
                        for (Page process : processes) {
                            process.allow(Set.of("pyFlowName"));
                            if (RuleArchive.blank(process.text("pyFlowName"))) throw new Page.Grammar(process.path(), "pyFlowName is required");
                            steps.add(new Step(id, process.text("pyFlowName"), process.pathOf("pyFlowName"), u));
                        }
                    }
                    harness = p.text("pyNewHarness");
                } catch (Page.Grammar g) { refuse(u, g); return; }
                u.targetKind = "workflowStages";
                stages.forEach(s -> u.targets.add(s.id()));
            } else {
                steps.add(new Step(null, root.ruleName(), root.page().path(), u));
                harness = "New";
            }
            List<FlowDef> flows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Step step : steps) {
                Entry flow = lookup(ctx, "Rule-Obj-Flow", step.flowName(), step.from(), step.stageId() == null ? "flow" : step.stageId(), step.fromPath(), "flow");
                if (flow == null) continue;
                if (!seen.add(flow.path())) { subBlock(step.from(), step.flowName(), "reference", "PEGA_NATIVE_FLOW_REUSED", "The flow runs more than once in the case type; repeated processes are not converted.", step.fromPath()); continue; }
                FlowDef def = flow(step, flow);
                if (def != null) flows.add(def);
            }
            if (harness != null) harness(harness, u, start.kind().equals("caseType") ? root.page().pathOf("pyNewHarness") : root.page().path());
            declaratives(u);
            boolean flowsOk = flows.size() == steps.size() && flows.stream().noneMatch(f -> "blocked".equals(f.use().conversion));
            if (flowsOk) emit(flows);
            transform(u);
        }

        FlowDef flow(Step step, Entry entry) {
            Use u = use(entry, "flow");
            try {
                Page p = entry.page(); p.allow(with("pyIsStartingFlow", "pyShapes", "pyConnectors"));
                LinkedHashMap<String, Shape> shapes = new LinkedHashMap<>(); Page startShape = null;
                for (Page s : p.pages("pyShapes")) {
                    String id = s.text("pyShapeID"), type = s.text("pyShapeType");
                    if (id == null || !SHAPE_ID.matcher(id).matches() || shapes.containsKey(id)) throw new Page.Grammar(s.pathOf("pyShapeID"), "a unique shape id is required");
                    if (RuleArchive.blank(type)) throw new Page.Grammar(s.pathOf("pyShapeType"), "pyShapeType is required");
                    switch (type) {
                        case "Start" -> { s.allow(Set.of("pyShapeID", "pyShapeType", "pyShapeName")); if (startShape != null) throw new Page.Grammar(s.path(), "a flow has exactly one Start shape"); startShape = s; }
                        case "End" -> s.allow(Set.of("pyShapeID", "pyShapeType", "pyShapeName", "pyStatusWork"));
                        case "Assignment" -> s.allow(Set.of("pyShapeID", "pyShapeType", "pyShapeName", "pyRouterType", "pyRouterParam", "pyServiceLevelName"));
                        case "Decision" -> s.allow(Set.of("pyShapeID", "pyShapeType", "pyShapeName", "pyDecisionType", "pyDecisionName"));
                        default -> { }
                    }
                    shapes.put(id, new Shape(id, type, s));
                }
                if (startShape == null) throw new Page.Grammar(p.pathOf("pyShapes"), "a flow needs one Start shape");
                List<Page> connectors = p.pages("pyConnectors");
                for (Page c : connectors) {
                    c.allow(Set.of("pyConnectorID", "pyFrom", "pyTo", "pyConditionType", "pyWhenName", "pyResultValue", "pyFlowActionName", "pyLabel"));
                    if (c.text("pyConnectorID") == null || !SHAPE_ID.matcher(c.text("pyConnectorID")).matches()) throw new Page.Grammar(c.pathOf("pyConnectorID"), "a connector id is required");
                    if (!shapes.containsKey(c.text("pyFrom")) || !shapes.containsKey(c.text("pyTo"))) throw new Page.Grammar(c.path(), "a connector must join two shapes of the same flow");
                    if (!Set.of("Always", "When", "Result", "Else", "FlowAction").contains(String.valueOf(c.text("pyConditionType")))) throw new Page.Grammar(c.pathOf("pyConditionType"), "unsupported connector condition type");
                }
                u.targetKind = "workflowNodes";
                FlowDef def = new FlowDef(step, entry, u, shapes, connectors, startShape);
                validate(def);
                return def;
            } catch (Page.Grammar g) { refuse(u, g); return null; }
        }

        List<Page> outgoing(FlowDef f, String shape) { return f.connectors().stream().filter(c -> shape.equals(c.text("pyFrom"))).toList(); }

        void validate(FlowDef f) {
            Use u = f.use();
            List<Page> fromStart = outgoing(f, f.start().text("pyShapeID"));
            if (fromStart.size() != 1 || !"Always".equals(fromStart.getFirst().text("pyConditionType")))
                subBlock(u, f.start().text("pyShapeID"), "flowShape", "PEGA_NATIVE_FLOW_START", "The Start shape needs exactly one Always connector.", f.start().path());
            for (Shape s : f.shapes().values()) {
                List<Page> out = outgoing(f, s.id());
                switch (s.type()) {
                    case "Start" -> { }
                    case "End" -> {
                        String status = s.page().text("pyStatusWork");
                        if (status != null && !Set.of("Resolved-Completed", "Resolved-Rejected").contains(status))
                            subBlock(u, s.id(), "flowShape", "PEGA_NATIVE_STATUS_UNSUPPORTED", "Only Resolved-Completed and Resolved-Rejected end statuses are supported; '" + status + "' is not.", s.page().pathOf("pyStatusWork"));
                        if (!out.isEmpty()) subBlock(u, s.id(), "flowShape", "PEGA_NATIVE_FLOW_SHAPE", "An End shape cannot have outgoing connectors.", s.page().path());
                    }
                    case "Assignment" -> assignment(f, s, out);
                    case "Decision" -> decisionShape(f, s, out);
                    default -> unsupportedShape(f, s);
                }
            }
        }

        void assignment(FlowDef f, Shape s, List<Page> out) {
            Use u = f.use(); Page p = s.page();
            if (!"ToWorkbasket".equals(p.text("pyRouterType")) || p.text("pyRouterParam") == null || !p.text("pyRouterParam").matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                subBlock(u, s.id(), "flowShape", "PEGA_NATIVE_ROUTING_UNSUPPORTED", "Only routing to a literal work queue (ToWorkbasket) is supported. Worklist, current-operator and custom routers are not inferred.", p.pathOf("pyRouterType"));
                return;
            }
            Set<String> actions = new HashSet<>();
            boolean ok = out.size() == 2;
            for (Page c : out) {
                if (!"FlowAction".equals(c.text("pyConditionType")) || RuleArchive.blank(c.text("pyFlowActionName")) || !actions.add(c.text("pyFlowActionName"))) ok = false;
                else {
                    Entry action = lookup(ctx, "Rule-Obj-FlowAction", c.text("pyFlowActionName"), u, c.text("pyConnectorID"), c.pathOf("pyFlowActionName"), "flowAction");
                    if (action != null) flowAction(action, s.id());
                }
            }
            assignments.add(new Assignment(s.id(), p.text("pyShapeName") == null ? s.id() : p.text("pyShapeName"), p.text("pyRouterParam"), f.entry().ruleName(),
                    out.stream().map(c -> new Action(c.text("pyConnectorID"), c.text("pyFlowActionName"))).toList()));
            if (!ok) subBlock(u, s.id(), "flowShape", "PEGA_NATIVE_ASSIGNMENT_ACTIONS", "An assignment must offer exactly two distinct flow actions, which are then mapped explicitly to approve and reject.", p.path());
            String sla = p.text("pyServiceLevelName");
            if (sla != null) {
                Entry level = lookup(ctx, "Rule-Obj-ServiceLevel", sla, u, s.id(), p.pathOf("pyServiceLevelName"), "serviceLevel");
                Integer minutes = level == null ? null : serviceLevel(level, 43_200, "approval due time");
                if (minutes != null) { due.put(s.id(), minutes); use(level, "serviceLevel").targets.add(s.id()); }
            }
        }

        void flowAction(Entry action, String shape) {
            Use u = use(action, "flowAction");
            try {
                action.page().allow(with("pySection"));
                u.targetKind = "approvalOutcome"; if (!u.targets.contains(shape)) u.targets.add(shape);
                u.fragment = "flowAction:" + action.ruleName();
                if (action.page().text("pySection") != null)
                    review(u, "PEGA_NATIVE_FLOWACTION_FORM_NOT_IMPORTED", "The flow action's section '" + action.page().text("pySection")
                            + "' is not imported: the approval uses the standard decision form (approve or reject with a reason).");
            } catch (Page.Grammar g) {
                subBlock(u, g.sourcePath().substring(g.sourcePath().indexOf('#') + 1), "Rule-Obj-FlowAction", "PEGA_NATIVE_FLOWACTION_PROCESSING",
                        "The flow action runs processing that is not converted (" + g.getMessage() + "): pre/post activities, validation and data transforms are never executed.", g.sourcePath());
            }
        }

        void decisionShape(FlowDef f, Shape s, List<Page> out) {
            Use u = f.use(); Page p = s.page(); String type = String.valueOf(p.text("pyDecisionType"));
            long elses = out.stream().filter(c -> "Else".equals(c.text("pyConditionType"))).count();
            if (elses != 1) { subBlock(u, s.id(), "flowShape", "PEGA_NATIVE_DECISION_ELSE", "A decision shape needs exactly one Else connector so every case has a route.", p.path()); return; }
            if (type.equals("Fork")) {
                List<Page> whens = out.stream().filter(c -> "When".equals(c.text("pyConditionType"))).toList();
                if (whens.size() != 1 || out.size() != 2) { subBlock(u, s.id(), "flowShape", "PEGA_NATIVE_FORK_ORDER", "A fork must have one When connector and one Else connector. With several When conditions the likelihood order is not reproduced.", p.path()); return; }
                Page c = whens.getFirst();
                Entry when = lookup(ctx, "Rule-Obj-When", String.valueOf(c.text("pyWhenName")), u, c.text("pyConnectorID"), c.pathOf("pyWhenName"), "when");
                if (when != null) when(when, c.text("pyConnectorID"));
                return;
            }
            if (!type.equals("DecisionTable") && !type.equals("DecisionTree")) { subBlock(u, s.id(), "flowShape", "PEGA_NATIVE_DECISION_UNSUPPORTED", "Decision shapes of type '" + type + "' are not supported; only DecisionTable, DecisionTree and Fork.", p.pathOf("pyDecisionType")); return; }
            String ruleType = type.equals("DecisionTable") ? "Rule-Declare-DecisionTable" : "Rule-Declare-DecisionTree";
            Entry rule = lookup(ctx, ruleType, String.valueOf(p.text("pyDecisionName")), u, s.id(), p.pathOf("pyDecisionName"), "decision");
            if (rule == null) return;
            NativeDecisions.Table table = decision(rule);
            if (table == null) { block(u, "PEGA_NATIVE_DEPENDENCY_BLOCKED", "A rule this flow depends on cannot be converted."); return; }
            for (Page c : out) {
                String kind = c.text("pyConditionType");
                if (kind.equals("Else")) continue;
                if (!kind.equals("Result") || !table.results().contains(String.valueOf(c.text("pyResultValue"))))
                    subBlock(u, c.text("pyConnectorID"), "connector", "PEGA_NATIVE_RESULT_UNKNOWN", "A decision connector must test a result the decision can return (" + table.results() + ").", c.path());
            }
            use(rule, "decision").targets.add(s.id());
        }

        void unsupportedShape(FlowDef f, Shape s) {
            Page p = s.page();
            String[] ref = switch (s.type()) {
                case "Utility" -> new String[] {"Rule-Obj-Activity", p.text("pyActivityName"), "PEGA_NATIVE_ACTIVITY_NOT_EXECUTED", "Activities (and any Java they run) are never executed."};
                case "Integrator" -> new String[] {"Rule-Connect-REST", p.text("pyConnectorName"), "PEGA_NATIVE_CONNECTOR_NOT_EXECUTED", "Connectors are not converted; map the integration to an approved Orqix connection and rebuild the step."};
                case "Subprocess" -> new String[] {"Rule-Obj-Flow", p.text("pyFlowName"), "PEGA_NATIVE_SUBFLOW_UNSUPPORTED", "Subflows are not converted; publish the child separately and rebuild the call."};
                default -> null;
            };
            String code = ref == null ? "PEGA_NATIVE_SHAPE_UNSUPPORTED" : ref[2];
            subBlock(f.use(), s.id(), "flowShape", code, "The " + s.type() + " shape '" + s.id() + "' is not supported." + (ref == null ? "" : " " + ref[3]), p.path());
            if (ref != null && ref[1] != null) {
                Lookup l = a.lookup(ctx, ref[0], ref[1]);
                Entry target = l.chosen() != null ? l.chosen() : a.entries().stream().filter(e -> e.isRule() && e.ruleName().equalsIgnoreCase(ref[1])
                        && a.context(ctx).chain().contains(e.className())).findFirst().orElse(null);
                if (target != null) block(use(target, s.type().equals("Utility") ? "activity" : s.type().equals("Integrator") ? "connector" : "flow"), code,
                        "Referenced from the " + s.type() + " shape '" + s.id() + "' of " + f.entry().ruleName() + ". " + ref[3]);
            }
        }

        NativeDecisions.Table decision(Entry rule) {
            Use u = use(rule, "decision");
            if (u.fragment != null) return "blocked".equals(u.conversion) ? null : decisionTables.get(rule.path());
            try {
                Page p = rule.page(); NativeDecisions.Table table;
                String id = NativeDecisions.decisionId(rule.ruleName(), p.pathOf("pyRuleName"));
                if (rule.ruleType().equals("Rule-Declare-DecisionTable")) {
                    p.allow(with("pyEvaluateAllRows", "pyColumns", "pyRows", "pyOtherwise"));
                    if (!"false".equals(p.text("pyEvaluateAllRows"))) throw new Page.Grammar(p.pathOf("pyEvaluateAllRows"), "first-match evaluation (pyEvaluateAllRows false) must be explicit");
                    List<Page> columns = p.pages("pyColumns");
                    if (columns.size() != 1) throw new Page.Grammar(p.pathOf("pyColumns"), "exactly one condition column is supported");
                    Page column = columns.getFirst(); column.allow(Set.of("pyProperty", "pyColumnDataType"));
                    String field = property(column.text("pyProperty"), u, "column", column.pathOf("pyProperty"));
                    if (field == null) return null;
                    conditionFields.add(field);
                    String type = fields.get(field).schema.path("type").asString();
                    String declared = String.valueOf(column.text("pyColumnDataType"));
                    if (!Map.of("Text", "string", "Integer", "integer", "Decimal", "number").getOrDefault(declared, "?").equals(type) || fields.get(field).schema.has("format"))
                        throw new Page.Grammar(column.pathOf("pyColumnDataType"), "the column type must match the property type (Text, Integer or Decimal)");
                    List<Page> rows = p.pages("pyRows");
                    if (rows.isEmpty() || rows.size() > 100) throw new Page.Grammar(p.pathOf("pyRows"), "1 to 100 rows are required");
                    List<NativeDecisions.Row> list = new ArrayList<>();
                    for (Page r : rows) { r.allow(Set.of("pyCondition", "pyResult")); list.add(NativeDecisions.row(r.text("pyCondition"), "=", type, r.text("pyResult"), r.path())); }
                    table = new NativeDecisions.Table(id, label(rule), field, type, list, NativeDecisions.literal(p.text("pyOtherwise"), p.pathOf("pyOtherwise")));
                } else {
                    p.allow(with("pyBranches", "pyOtherwise"));
                    List<Page> rows = p.pages("pyBranches");
                    if (rows.isEmpty() || rows.size() > 100) throw new Page.Grammar(p.pathOf("pyBranches"), "1 to 100 branches are required");
                    String prop = null; String field = null; String type = null; List<NativeDecisions.Row> list = new ArrayList<>();
                    for (Page r : rows) {
                        r.allow(Set.of("pyProperty", "pyOperator", "pyValue", "pyResult"));
                        if (prop == null) {
                            prop = r.text("pyProperty"); field = property(prop, u, "branch", r.pathOf("pyProperty"));
                            if (field == null) return null;
                            conditionFields.add(field);
                            type = fields.get(field).schema.path("type").asString();
                            if (!Set.of("string", "integer", "number").contains(type) || fields.get(field).schema.has("format")) throw new Page.Grammar(r.pathOf("pyProperty"), "branches must test a text or numeric property");
                        } else if (!prop.equals(r.text("pyProperty"))) throw new Page.Grammar(r.pathOf("pyProperty"), "every branch must test the same property");
                        String op = String.valueOf(r.text("pyOperator"));
                        if (!Set.of("=", "!=", ">", ">=", "<", "<=").contains(op)) throw new Page.Grammar(r.pathOf("pyOperator"), "unsupported operator");
                        list.add(NativeDecisions.row(r.text("pyValue"), op, type, r.text("pyResult"), r.path()));
                    }
                    table = new NativeDecisions.Table(id, label(rule), field, type, list, NativeDecisions.literal(p.text("pyOtherwise"), p.pathOf("pyOtherwise")));
                }
                String dmn = NativeDecisions.dmn(table);
                var analysis = DmnAnalyzer.analyze(dmn, Limits.DEFAULT, json);
                DmnTable converted = analysis.table(id);
                u.fragment = dmn; u.targetKind = "dmnDecision";
                if (converted == null) {
                    block(u, "PEGA_NATIVE_DECISION_UNCONVERTIBLE", "The generated decision table did not pass the DMN analyzer: "
                            + analysis.blockersOf(id).stream().map(Report.Issue::explanation).toList());
                    return null;
                }
                tables.put(id, converted); decisionTables.put(rule.path(), table);
                return table;
            } catch (Page.Grammar g) { u.fragment = ""; refuse(u, g); return null; }
        }
        final Map<String, NativeDecisions.Table> decisionTables = new HashMap<>();

        /** A when rule as a canonical-subset condition (JUEL text read by the shared expression parser). */
        String when(Entry rule, String connector) {
            Use u = use(rule, "when");
            try {
                Page p = rule.page(); p.allow(with("pyConditions", "pyLogicString"));
                List<Page> rows = p.pages("pyConditions");
                if (rows.isEmpty() || rows.size() > 8) throw new Page.Grammar(p.pathOf("pyConditions"), "1 to 8 conditions are required");
                Map<String, String> terms = new LinkedHashMap<>();
                for (Page r : rows) {
                    r.allow(Set.of("pyLabel", "pyLeft", "pyOperator", "pyRight"));
                    String name = r.text("pyLabel");
                    if (name == null || !name.matches("[A-H]") || terms.containsKey(name)) throw new Page.Grammar(r.pathOf("pyLabel"), "conditions need unique labels A to H");
                    String field = property(r.text("pyLeft"), u, "condition " + name, r.pathOf("pyLeft"));
                    if (field == null) return null;
                    conditionFields.add(field);
                    String op = String.valueOf(r.text("pyOperator"));
                    String juel = Map.of("=", "==", "!=", "!=", ">", ">", ">=", ">=", "<", "<", "<=", "<=").get(op);
                    if (juel == null) throw new Page.Grammar(r.pathOf("pyOperator"), "unsupported operator");
                    String type = fields.get(field).schema.path("type").asString(), right = String.valueOf(r.text("pyRight")), literal;
                    var text = TEXT_LITERAL.matcher(right);
                    if ((type.equals("number") || type.equals("integer")) && NUMBER.matcher(right).matches() && !(type.equals("integer") && right.contains("."))) literal = right;
                    else if (type.equals("boolean") && (right.equals("true") || right.equals("false")) && (op.equals("=") || op.equals("!="))) literal = right;
                    else if (type.equals("string") && !fields.get(field).schema.has("format") && text.matches() && (op.equals("=") || op.equals("!="))) {
                        if (fields.get(field).schema.has("enum") && !fields.get(field).schema.path("enum").toString().contains("\"" + text.group(1) + "\""))
                            throw new Page.Grammar(r.pathOf("pyRight"), "the value is not one of the property's allowed values");
                        literal = "'" + text.group(1) + "'";
                    } else throw new Page.Grammar(r.pathOf("pyRight"), "the literal does not fit the property type, or the operator is not supported for it");
                    terms.put(name, field + " " + juel + " " + literal);
                }
                String logic = String.valueOf(p.text("pyLogicString")).trim();
                String[] tokens = logic.split("\\s+");
                String connective = tokens.length > 1 ? tokens[1] : null;
                List<String> order = new ArrayList<>();
                for (int i = 0; i < tokens.length; i++) {
                    if (i % 2 == 1) { if (!tokens[i].equals(connective) || !Set.of("AND", "OR").contains(connective)) throw new Page.Grammar(p.pathOf("pyLogicString"), "only one connective (all AND or all OR) is supported"); }
                    else if (!terms.containsKey(tokens[i]) || order.contains(tokens[i])) throw new Page.Grammar(p.pathOf("pyLogicString"), "the logic string must use every condition label once");
                    else order.add(tokens[i]);
                }
                if (tokens.length % 2 == 0 || order.size() != terms.size()) throw new Page.Grammar(p.pathOf("pyLogicString"), "the logic string must use every condition label once");
                String expression = "${" + String.join("AND".equals(connective) ? " && " : " || ", order.stream().map(terms::get).toList()) + "}";
                u.fragment = expression; u.targetKind = "branchCondition"; if (!u.targets.contains(connector)) u.targets.add(connector);
                whens.put(rule.path(), expression);
                return expression;
            } catch (Page.Grammar g) { refuse(u, g); return null; }
        }
        final Map<String, String> whens = new HashMap<>();

        /** A property reference (.Prop or .Page.Prop) as a typed request field; data class pages are flattened one level. */
        String property(String ref, Use from, String fromId, String fromPath) {
            if (propertyKeys.containsKey(ref)) return propertyKeys.get(ref);
            var m = ref == null ? null : PROPERTY_REF.matcher(ref);
            if (m == null || !m.matches()) { subBlock(from, fromId, "reference", "PEGA_NATIVE_PROPERTY_REFERENCE", "Only direct properties (.Name) and properties of one embedded data page (.Page.Name) are supported; '" + ref + "' is not.", fromPath); return null; }
            Entry top = lookup(ctx, "Rule-Obj-Property", m.group(1), from, fromId, fromPath, "property");
            if (top == null) return null;
            Use topUse = use(top, "property");
            String mode, pageClass;
            try { top.page().allow(with("pyPropertyMode", "pyStringType", "pyMaxLength", "pyPageClass", "pyTableType", "pyLocalList")); mode = top.page().text("pyPropertyMode"); pageClass = top.page().text("pyPageClass"); }
            catch (Page.Grammar g) { refuse(topUse, g); return null; }
            Entry leaf = top; String key = lower(m.group(1)); String context = ctx;
            if (m.group(2) != null) {
                if (!"Page".equals(mode) || RuleArchive.blank(pageClass)) { subBlock(topUse, "pyPropertyMode", "Rule-Obj-Property", "PEGA_NATIVE_PROPERTY_MODE", "'" + ref + "' needs '." + m.group(1) + "' to be a Page property with a page class.", top.page().pathOf("pyPropertyMode")); return null; }
                leaf = lookup(pageClass, "Rule-Obj-Property", m.group(2), from, fromId, fromPath, "property");
                if (leaf == null) return null;
                key = lower(m.group(1)) + m.group(2).substring(0, 1).toUpperCase(Locale.ROOT) + m.group(2).substring(1);
                context = pageClass;
                topUse.targetKind = "requestFieldGroup";
                if (!topUse.targets.contains("/input/" + key)) topUse.targets.add("/input/" + key);
                topUse.fragment = topUse.targets.toString();
            } else if (!"Single Value".equals(mode)) {
                subBlock(topUse, "pyPropertyMode", "Rule-Obj-Property", "PEGA_NATIVE_PROPERTY_MODE", "Only Single Value properties become request fields; '" + mode + "' properties (lists, groups, nested pages) are not converted.", top.page().pathOf("pyPropertyMode"));
                return null;
            }
            Use u = use(leaf, "property");
            try {
                Page p = leaf.page(); p.allow(with("pyPropertyMode", "pyStringType", "pyMaxLength", "pyPageClass", "pyTableType", "pyLocalList"));
                if (!"Single Value".equals(p.text("pyPropertyMode"))) throw new Page.Grammar(p.pathOf("pyPropertyMode"), "only Single Value properties become fields");
                if (!FIELD_KEY.matcher(key).matches()) throw new Page.Grammar(p.pathOf("pyRuleName"), "the property name cannot become a field key");
                if (fields.containsKey(key) && !Objects.equals(fields.get(key).rule, leaf.path())) throw new Page.Grammar(p.pathOf("pyRuleName"), "two properties map to the field '" + key + "'");
                ObjectNode schema = json.object();
                String type = String.valueOf(p.text("pyStringType"));
                switch (type) {
                    case "Text", "Identifier" -> {
                        schema.put("type", "string");
                        int max = 1000;
                        if (p.text("pyMaxLength") != null) {
                            if (!p.text("pyMaxLength").matches("[1-9][0-9]{0,3}") || Integer.parseInt(p.text("pyMaxLength")) > 1000) throw new Page.Grammar(p.pathOf("pyMaxLength"), "a maximum length of 1 to 1000 is supported");
                            max = Integer.parseInt(p.text("pyMaxLength"));
                        }
                        schema.put("maxLength", max);
                    }
                    case "Integer" -> schema.put("type", "integer");
                    case "Decimal", "Double" -> schema.put("type", "number");
                    case "Date" -> { schema.put("type", "string"); schema.put("format", "date"); }
                    case "TrueFalse" -> schema.put("type", "boolean");
                    default -> throw new Page.Grammar(p.pathOf("pyStringType"), "unsupported property type '" + type + "'");
                }
                String table = p.text("pyTableType");
                if (table != null && !table.equals("None")) {
                    if (!table.equals("Local List") || !schema.path("type").asString().equals("string")) throw new Page.Grammar(p.pathOf("pyTableType"), "only a local list of text values is supported");
                    List<String> values = p.texts("pyLocalList");
                    if (values.isEmpty() || values.size() > 50 || new HashSet<>(values).size() != values.size()) throw new Page.Grammar(p.pathOf("pyLocalList"), "1 to 50 distinct values are required");
                    ArrayNode e = schema.putArray("enum"); values.forEach(e::add);
                } else if (p.fields().containsKey("pyLocalList")) throw new Page.Grammar(p.pathOf("pyLocalList"), "a local list needs pyTableType Local List");
                String title = p.text("pyLabel") == null ? leaf.ruleName() : p.text("pyLabel");
                schema.put("title", title.length() > 100 ? title.substring(0, 100) : title);
                Field field = fields.computeIfAbsent(key, k -> new Field());
                field.key = key; field.schema = schema; field.rule = leaf.path();
                u.targetKind = "requestField"; if (!u.targets.contains("/input/" + key)) u.targets.add("/input/" + key);
                u.fragment = schema.toString();
                propertyKeys.put(ref, key);
                contexts.add(context);
                return key;
            } catch (Page.Grammar g) { refuse(u, g); return null; }
        }

        Integer serviceLevel(Entry rule, int maxMinutes, String target) {
            Use u = use(rule, "serviceLevel");
            try {
                Page p = rule.page();
                p.allow(with("pyGoalDays", "pyGoalHours", "pyGoalMinutes", "pyDeadlineDays", "pyDeadlineHours", "pyDeadlineMinutes", "pyUseBusinessDays", "pyGoalEscalation", "pyDeadlineEscalation"));
                if (!"false".equals(p.text("pyUseBusinessDays"))) { subBlock(u, "pyUseBusinessDays", "Rule-Obj-ServiceLevel", "PEGA_NATIVE_SLA_BUSINESS_DAYS", "Business-day or calendar-based intervals are not converted; elapsed time (pyUseBusinessDays false) must be explicit.", p.pathOf("pyUseBusinessDays")); return null; }
                for (String escalation : List.of("pyGoalEscalation", "pyDeadlineEscalation"))
                    if (!RuleArchive.blank(p.text(escalation))) { subBlock(u, escalation, "Rule-Obj-ServiceLevel", "PEGA_NATIVE_SLA_ESCALATION", "Escalation activities are never executed; the service level cannot be converted with one.", p.pathOf(escalation)); return null; }
                int goal = minutes(p, "pyGoal"), deadline = minutes(p, "pyDeadline");
                if (deadline < 1 || deadline > maxMinutes) { subBlock(u, "pyDeadline", "Rule-Obj-ServiceLevel", "PEGA_NATIVE_SLA_DEADLINE", "A deadline between 1 minute and " + (maxMinutes / 1440) + " days is required for the " + target + ".", p.path()); return null; }
                u.targetKind = "dueTime"; u.fragment = "deadlineMinutes:" + deadline;
                if (goal > 0) review(u, "PEGA_NATIVE_SLA_GOAL_NOT_REPRESENTED", "The goal interval (" + goal + " minutes) has no Orqix equivalent; only the deadline becomes the " + target + ".");
                return deadline;
            } catch (Page.Grammar g) { refuse(u, g); return null; }
        }

        static int minutes(Page p, String prefix) {
            int total = 0;
            for (var part : List.of(Map.entry("Days", 1440), Map.entry("Hours", 60), Map.entry("Minutes", 1))) {
                String v = p.text(prefix + part.getKey());
                if (v == null) continue;
                if (!v.matches("[0-9]{1,3}")) throw new Page.Grammar(p.pathOf(prefix + part.getKey()), "a whole number from 0 to 999 is required");
                total += Integer.parseInt(v) * part.getValue();
            }
            return total;
        }

        static String duration(int minutes) { return minutes % 1440 == 0 ? "P" + minutes / 1440 + "D" : minutes % 60 == 0 ? "PT" + minutes / 60 + "H" : "PT" + minutes + "M"; }

        void harness(String name, Use from, String fromPath) {
            Lookup l = a.lookup(ctx, "Rule-HTML-Harness", name);
            if (!l.resolved() && l.candidates().isEmpty() && start.kind().equals("flow")) return; // optional for starting flows
            Entry harness = lookup(ctx, "Rule-HTML-Harness", name, from, "harness", fromPath, "harness");
            if (harness == null) return;
            Use hu = use(harness, "harness");
            ObjectNode ui = json.object(); ArrayNode order = json.mapper().createArrayNode(); ObjectNode widgets = json.object(); ArrayNode sections = json.mapper().createArrayNode();
            boolean money = false;
            try {
                harness.page().allow(with("pySections"));
                List<String> names = harness.page().texts("pySections");
                if (names.isEmpty() || names.size() > 10) throw new Page.Grammar(harness.page().pathOf("pySections"), "1 to 10 sections are required");
                for (String sectionName : names) {
                    Entry section = lookup(ctx, "Rule-HTML-Section", sectionName, hu, sectionName, harness.page().pathOf("pySections"), "section");
                    if (section == null) continue;
                    Use su = use(section, "section");
                    try {
                        Page p = section.page(); p.allow(with("pyTitle", "pyColumns", "pyCells"));
                        List<Page> cells = p.pages("pyCells");
                        if (cells.isEmpty() || cells.size() > 30) throw new Page.Grammar(p.pathOf("pyCells"), "1 to 30 cells are required");
                        String columns = p.text("pyColumns") == null ? "1" : p.text("pyColumns");
                        if (!Set.of("1", "2").contains(columns)) throw new Page.Grammar(p.pathOf("pyColumns"), "one or two columns are supported");
                        String title = RuleArchive.blank(p.text("pyTitle")) ? section.ruleName() : p.text("pyTitle");
                        ObjectNode s = json.object(); s.put("id", Identifiers.sanitize(section.ruleName(), true)); s.put("title", title.length() > 80 ? title.substring(0, 80) : title);
                        s.put("columns", Integer.parseInt(columns)); ArrayNode list = s.putArray("fields");
                        for (Page cell : cells) {
                            cell.allow(Set.of("pyProperty", "pyControl", "pyLabel", "pyRequired"));
                            String control = String.valueOf(cell.text("pyControl"));
                            if (!WIDGETS.containsKey(control)) throw new Page.Grammar(cell.pathOf("pyControl"), "unsupported control " + control);
                            if (!Set.of("true", "false").contains(String.valueOf(cell.text("pyRequired")))) throw new Page.Grammar(cell.pathOf("pyRequired"), "pyRequired must be true or false");
                            String field = property(cell.text("pyProperty"), su, "cell", cell.pathOf("pyProperty"));
                            if (field == null) continue;
                            Field f = fields.get(field); String type = f.schema.path("type").asString(); boolean date = f.schema.has("format");
                            String widget = WIDGETS.get(control);
                            boolean fits = switch (widget) {
                                case "text", "textarea" -> type.equals("string") && !date && !f.schema.has("enum");
                                case "number" -> type.equals("number") || type.equals("integer");
                                case "money" -> type.equals("number");
                                case "select", "radio" -> f.schema.has("enum");
                                case "checkbox" -> type.equals("boolean");
                                default -> date;
                            };
                            if (!fits) throw new Page.Grammar(cell.pathOf("pyControl"), "the control " + control + " does not fit the property type");
                            if (list.toString().contains("\"" + field + "\"") || order.toString().contains("\"" + field + "\"")) throw new Page.Grammar(cell.pathOf("pyProperty"), "a field appears twice in the harness");
                            list.add(field); order.add(field);
                            if (!widget.isEmpty()) widgets.put(field, widget);
                            if (widget.equals("money")) { money = true; f.schema.put("multipleOf", 0.01); }
                            if ("true".equals(cell.text("pyRequired"))) f.required = true;
                            if (!RuleArchive.blank(cell.text("pyLabel"))) { String l2 = cell.text("pyLabel"); f.schema.put("title", l2.length() > 100 ? l2.substring(0, 100) : l2); }
                        }
                        if (!list.isEmpty()) sections.add(s);
                        su.targetKind = "requestLayout"; su.targets.add("inputUi.layout." + s.path("id").asString()); su.fragment = s.toString();
                    } catch (Page.Grammar g) { refuse(su, g); }
                }
            } catch (Page.Grammar g) { refuse(hu, g); return; }
            if (sections.isEmpty()) return;
            ui.set("order", order); if (!widgets.isEmpty()) ui.set("widgets", widgets);
            if (money) { ui.put("currency", "USD"); review(hu, "PEGA_NATIVE_CURRENCY_ASSUMED", "Currency controls are shown in USD and accept whole cents (multipleOf 0.01); the source currency setting is not part of the supported grammar."); }
            ui.putObject("layout").set("sections", sections);
            hu.targetKind = "requestView"; hu.targets.add("inputUi"); hu.fragment = ui.toString();
            inputUi = ui;
        }

        void declaratives(Use from) {
            for (Lookup l : a.context(ctx).keys().values()) {
                if (l.chosen() == null || !IMPLICIT.contains(l.chosen().ruleType())) continue;
                Use u = use(l.chosen(), "declarative");
                block(u, "PEGA_NATIVE_DECLARATIVE_UNSUPPORTED", "Declarative rules run automatically for every case of this class (forward or backward chaining). They are not converted, so the converted workflow would silently behave differently.");
                sub.add(issue(from.e.path() + "#declarative:" + l.chosen().ruleName(), l.chosen().ruleName(), "reference", Report.BLOCKER, "PEGA_NATIVE_DECLARATIVE_UNSUPPORTED",
                        l.chosen().label() + " applies to the case class and is not converted.", "Rebuild the calculation explicitly or remove the rule from the application before converting.", null, l.chosen().path()));
            }
        }

        void transform(Use from) {
            Lookup l = a.lookup(ctx, "Rule-Obj-Model", "pyDefault");
            if (l.candidates().isEmpty()) return;
            Entry rule = lookup(ctx, "Rule-Obj-Model", "pyDefault", from, "pyDefault", from.e.page().path(), "transform");
            if (rule == null) return;
            Use u = use(rule, "transform");
            try {
                Page p = rule.page(); p.allow(with("pySteps"));
                List<String> notes = new ArrayList<>();
                for (Page step : p.pages("pySteps")) {
                    step.allow(Set.of("pyAction", "pyTarget", "pySource"));
                    if (!"Set".equals(step.text("pyAction"))) throw new Page.Grammar(step.pathOf("pyAction"), "only Set steps are supported");
                    String source = String.valueOf(step.text("pySource"));
                    if (!(TEXT_LITERAL.matcher(source).matches() || NUMBER.matcher(source).matches() || source.equals("true") || source.equals("false")))
                        throw new Page.Grammar(step.pathOf("pySource"), "only literal values are supported; functions and property references are not evaluated");
                    String key = propertyKeys.get(step.text("pyTarget"));
                    if (key == null) { notes.add(step.text("pyTarget") + " (not used by this conversion)"); continue; }
                    fields.get(key).description = "Source default: " + source.replace("\"", "");
                    notes.add(step.text("pyTarget") + " = " + source);
                }
                u.targetKind = "fieldDescription"; u.fragment = notes.toString();
                review(u, "PEGA_NATIVE_TRANSFORM_DEFAULT_NOT_APPLIED", "Request forms have no default values. These source defaults are shown as field descriptions and the requester supplies the value: " + String.join("; ", notes) + ".");
            } catch (Page.Grammar g) {
                subBlock(u, g.sourcePath().substring(g.sourcePath().indexOf('#') + 1), "Rule-Obj-Model", "PEGA_NATIVE_TRANSFORM_UNSUPPORTED",
                        "The data transform is not converted: " + g.getMessage() + ".", g.sourcePath());
            }
        }

        /** Generated BPMN for the flows of the starting point, joined end to start in stage order. */
        void emit(List<FlowDef> flows) {
            Set<String> ids = new HashSet<>(Set.of("pega-start", "pega-completed"));
            boolean conflict = false;
            for (FlowDef f : flows) {
                for (Shape s : f.shapes().values()) if (emitted(s) && !ids.add(s.id())) { subBlock(f.use(), s.id(), "flowShape", "PEGA_NATIVE_ID_CONFLICT", "Two converted flows use the shape id '" + s.id() + "'.", s.page().path()); conflict = true; }
                for (Page c : f.connectors()) if (!ids.add(c.text("pyConnectorID"))) { subBlock(f.use(), c.text("pyConnectorID"), "connector", "PEGA_NATIVE_ID_CONFLICT", "Two converted flows use the connector id '" + c.text("pyConnectorID") + "'.", c.path()); conflict = true; }
            }
            if (conflict) return;
            xml.append("<definitions xmlns='").append(Namespaces.BPMN).append("' xmlns:c='").append(Namespaces.CAMUNDA7)
               .append("' targetNamespace='urn:orqix:pega-native'><process isExecutable='true' id='").append(esc(processId)).append("' name='").append(esc(processName)).append("'>");
            xml.append("<startEvent id='pega-start' name='Start'/>");
            boolean completed = false;
            for (int i = 0; i < flows.size(); i++) {
                FlowDef f = flows.get(i); StringBuilder fragment = new StringBuilder();
                Set<String> stageNodes = f.step().stageId() == null ? new HashSet<>() : stages.stream().filter(s -> s.id().equals(f.step().stageId())).findFirst().map(StagePlan::bpmnNodes).orElse(new HashSet<>());
                for (Shape s : f.shapes().values()) {
                    if (!emitted(s)) continue;
                    Page p = s.page(); String name = esc(p.text("pyShapeName") == null ? s.id() : p.text("pyShapeName"));
                    paths.put(s.id(), p.path()); f.use().targets.add(s.id());
                    switch (s.type()) {
                        case "Assignment" -> { fragment.append("<userTask id='").append(s.id()).append("' name='").append(name).append("' c:candidateGroups='").append(esc(p.text("pyRouterParam"))).append("'/>"); stageNodes.add(s.id()); }
                        case "End" -> { fragment.append("<endEvent id='").append(s.id()).append("' name='").append(name).append("'/>"); endKinds.put(s.id(), "Resolved-Rejected".equals(p.text("pyStatusWork")) ? "rejected" : "success"); }
                        default -> {
                            String elseFlow = outgoing(f, s.id()).stream().filter(c -> "Else".equals(c.text("pyConditionType"))).findFirst().orElseThrow().text("pyConnectorID");
                            if ("Fork".equals(p.text("pyDecisionType"))) {
                                fragment.append("<exclusiveGateway id='").append(s.id()).append("' name='").append(name).append("' default='").append(elseFlow).append("'/>");
                                stageNodes.add(s.id());
                            } else {
                                String decisionId = decisionTables.get(a.lookup(ctx, p.text("pyDecisionType").equals("DecisionTable") ? "Rule-Declare-DecisionTable" : "Rule-Declare-DecisionTree", p.text("pyDecisionName")).chosen().path()).decisionId();
                                fragment.append("<businessRuleTask id='").append(s.id()).append("' name='").append(name).append("' c:decisionRef='").append(decisionId)
                                        .append("' c:resultVariable='").append(resultVariable(s.id())).append("' c:mapDecisionResult='singleEntry'/>");
                                fragment.append("<exclusiveGateway id='").append(s.id()).append("-route' name='").append(name).append("' default='").append(elseFlow).append("'/>");
                                fragment.append("<sequenceFlow id='").append(s.id()).append("-evaluate' sourceRef='").append(s.id()).append("' targetRef='").append(s.id()).append("-route'/>");
                                decisionTasks.add(s.id()); taskTables.put(s.id(), tables.get(decisionId)); stageNodes.add(s.id()); stageNodes.add(s.id() + "-route");
                                paths.put(s.id() + "-route", p.path()); paths.put(s.id() + "-evaluate", p.path());
                            }
                        }
                    }
                }
                for (Page c : f.connectors()) {
                    String from = c.text("pyFrom"); Shape source = f.shapes().get(from); String id = c.text("pyConnectorID");
                    paths.put(id, c.path());
                    String target = target(flows, i, c.text("pyTo"));
                    if (target.equals("pega-completed")) completed = true;
                    if (source.type().equals("Start")) {
                        if (i == 0) { fragment.append("<sequenceFlow id='").append(id).append("' sourceRef='pega-start' targetRef='").append(target).append("'/>"); paths.put("pega-start", c.path()); }
                        continue;
                    }
                    String sourceRef = source.type().equals("Decision") && !"Fork".equals(source.page().text("pyDecisionType")) ? from + "-route" : from;
                    String label = switch (c.text("pyConditionType")) {
                        case "FlowAction" -> c.text("pyFlowActionName");
                        case "Result" -> c.text("pyResultValue");
                        case "Else" -> "Otherwise";
                        default -> c.text("pyLabel");
                    };
                    fragment.append("<sequenceFlow id='").append(id).append("' sourceRef='").append(sourceRef).append("' targetRef='").append(target).append("'");
                    if (label != null) fragment.append(" name='").append(esc(label)).append("'");
                    fragment.append(">");
                    String condition = switch (c.text("pyConditionType")) {
                        case "Result" -> "${" + resultVariable(from) + " == '" + c.text("pyResultValue") + "'}";
                        case "When" -> whens.get(a.lookup(ctx, "Rule-Obj-When", c.text("pyWhenName")).chosen().path());
                        default -> null;
                    };
                    if (condition != null) fragment.append("<conditionExpression>").append(esc(condition)).append("</conditionExpression>");
                    fragment.append("</sequenceFlow>");
                }
                xml.append(fragment);
                f.use().fragment = fragment.toString();
            }
            if (completed) { xml.append("<endEvent id='pega-completed' name='Completed'/>"); endKinds.put("pega-completed", "success"); }
            xml.append("</process></definitions>");
        }

        boolean emitted(Shape s) { return !s.type().equals("Start") && !(s.type().equals("End") && s.page().text("pyStatusWork") == null); }

        /** The generated element a connector reaches: a continuing End joins the next flow's first shape (or the final end). */
        String target(List<FlowDef> flows, int index, String shape) {
            FlowDef f = flows.get(index); Shape s = f.shapes().get(shape);
            if (s.type().equals("End") && s.page().text("pyStatusWork") == null) {
                if (index + 1 >= flows.size()) return "pega-completed";
                FlowDef next = flows.get(index + 1);
                return target(flows, index + 1, outgoing(next, next.start().text("pyShapeID")).getFirst().text("pyTo"));
            }
            return shape;
        }

        static String resultVariable(String shape) { return lower(shape) + "Result"; }

        static String lower(String s) { return s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1).replace("_", ""); }

        static String label(Entry e) { String l = e.page().text("pyLabel"); return l == null ? e.ruleName() : l; }

        Report.Issue entryIssue(Entry e) {
            String path = e.path();
            switch (e.status()) {
                case "refused" -> { return issue(path, path, "archiveEntry", Report.BLOCKER, e.reason(), e.detail(), "Re-export the entry as a readable rule page or remove it from the export; conversion stays blocked while it is present.", null, path); }
                case "manifest" -> { return issue(path, path, "archiveEntry", Report.SUPPORTED, "PEGA_NATIVE_MANIFEST", "Archive manifest; descriptive only.", null, null, path); }
                case "data" -> { return issue(path, e.insKey(), e.ruleType(), Report.INFO, "PEGA_NATIVE_DATA_INSTANCE_EXCLUDED", "Data instance (" + e.ruleType() + "). Operators, access groups, work queues and other records are not migrated; groups and permissions are mapped explicitly in the workspace.", "Acknowledge this exclusion.", null, path); }
                default -> { }
            }
            if (e.ruleType().equals("Rule-Application"))
                return issue(path, e.label(), e.ruleType(), Report.SUPPORTED, "PEGA_NATIVE_APPLICATION_STACK", "Application ruleset stack used for rule resolution: "
                        + a.stack().stream().map(s -> s.getKey() + ":" + s.getValue()).toList() + ".", null, null, path);
            if (e.ruleType().equals("Rule-Obj-Class"))
                return issue(path, e.label(), e.ruleType(), Report.SUPPORTED, "PEGA_NATIVE_CLASS_DEFINITION", "Class definition used for inheritance during rule resolution.", null, null, path);
            Use u = uses.get(path);
            if (u != null) {
                String target = u.targets.isEmpty() ? null : u.targets.getFirst();
                return switch (u.conversion) {
                    case "blocked" -> issue(path, e.label(), e.ruleType(), Report.BLOCKER, u.reasons.getFirst(), u.explanation, "Rebuild this behavior explicitly in Orqix; no draft is created while it is open.", target, path);
                    case "review" -> issue(path, e.label(), e.ruleType(), Report.REVIEW, u.reasons.getFirst(), "Converted (" + u.targetKind + "). " + u.review, "Review and acknowledge before converting.", target, path);
                    default -> issue(path, e.label(), e.ruleType(), Report.SUPPORTED, "PEGA_NATIVE_RULE_CONVERTED", "Converted to " + u.targetKind + (u.targets.isEmpty() ? "" : " " + u.targets) + ".", null, target, path);
                };
            }
            String outcome = outcome(e);
            if (outcome != null && !outcome.equals("selected") && !outcome.equals("blocked") && !outcome.equals("circumstanced") && !outcome.equals("duplicate") && !outcome.equals("baseFallback") && !outcome.equals("overridesFinal"))
                return issue(path, e.label(), e.ruleType(), Report.SUPPORTED, "PEGA_NATIVE_RULE_SHADOWED", "Not the effective rule (" + describe(outcome) + "), so Pega would not run it either.", null, null, path);
            return issue(path, e.label(), e.ruleType(), Report.INFO, "PEGA_NATIVE_RULE_NOT_USED", (outcome == null ? "Outside the class context of this starting point" : "Effective rule, but not referenced by this starting point")
                    + "; it is not converted.", "Acknowledge that this rule is not part of the converted workflow.", null, path);
        }

        String outcome(Entry e) {
            if (contexts.isEmpty()) return null;
            for (String c : contexts) for (Lookup l : a.context(c).keys().values()) for (Candidate cd : l.candidates()) if (cd.rule().equals(e.path())) return cd.outcome();
            return null;
        }

        static String describe(String outcome) {
            return switch (outcome) {
                case "rulesetNotInApplication" -> "its ruleset is not in the application stack";
                case "versionNotInApplication" -> "its ruleset version is above the application's version or in another major version";
                case "notAvailable" -> "it is marked not available";
                case "withdrawn" -> "it is withdrawn";
                case "maskedByWithdrawn" -> "a withdrawn higher version in the same class and ruleset hides it";
                case "lowerRanked" -> "a closer class, higher ruleset or higher version wins";
                case "belowBlocked" -> "it ranks below a blocked rule";
                case "finalOverridden" -> "it is Final but overridden";
                default -> outcome;
            };
        }

        Plan finish() {
            List<Report.Issue> issues = new ArrayList<>(archiveIssues(a));
            for (Entry e : a.entries()) issues.add(entryIssue(e));
            issues.addAll(sub);
            boolean blocked = issues.stream().anyMatch(Report.Issue::blocksPublishing) || xml.isEmpty();
            ObjectNode schema = json.object(); schema.put("type", "object"); schema.put("additionalProperties", false);
            ArrayNode required = schema.putArray("required"); ObjectNode props = schema.putObject("properties");
            for (Field f : fields.values()) {
                if (f.description != null) f.schema.put("description", f.description);
                props.set(f.key, f.schema);
                Use fieldUse = uses.get(f.rule);
                if (fieldUse != null) fieldUse.fragment = f.schema.toString();
                if (f.required || conditionFields.contains(f.key)) required.add(f.key);
            }
            Build build = new Build(xml.toString(), schema, inputUi, Map.copyOf(taskTables), Map.copyOf(due), Map.copyOf(endKinds), List.copyOf(stages),
                    Map.copyOf(paths), processId, processName, Set.copyOf(decisionTasks),
                    "Converted from the native Pega rule archive " + a.sourceSha256().substring(0, 12) + " (" + start.kind() + " " + a.entry(start.rule()).label()
                            + ", adapter " + ADAPTER + "). Review the per-rule conversion results and verify behavior before publishing.");
            List<RuleResult> results = new ArrayList<>();
            for (Entry e : a.entries()) {
                if (!e.isRule()) continue;
                Use u = uses.get(e.path()); String outcome = outcome(e);
                String conversion = u != null ? u.conversion : e.ruleType().equals("Rule-Application") || e.ruleType().equals("Rule-Obj-Class") ? "supported"
                        : outcome != null && !Set.of("selected", "blocked", "circumstanced", "duplicate", "baseFallback", "overridesFinal").contains(outcome) ? "shadowed" : "notUsed";
                String fragment = u == null ? null : u.fragment;
                results.add(new RuleResult(e.path(), e.ruleType(), e.className(), e.ruleName(), e.ruleset(), e.version(), e.availability(), e.sha256(),
                        u == null ? null : u.role, outcome == null ? "outsideContext" : outcome, conversion, u == null ? null : u.targetKind,
                        u == null ? List.of() : List.copyOf(u.targets), fragment == null || fragment.isEmpty() ? null : Json.sha256Hex(fragment.getBytes(StandardCharsets.UTF_8)),
                        u == null ? List.of() : List.copyOf(u.reasons)));
            }
            Report.CompatibilityReport converted = null;
            if (!xml.isEmpty()) {
                var analysis = Analyzer.analyze(xml.toString(), Limits.DEFAULT);
                converted = Converter.report(analysis, analysis.items().stream().map(com.orqix.migration.camunda.Analysis.Item::issue).toList());
            }
            String version = a.application() == null ? null : "Rule-Application " + a.application().ruleName() + " " + a.application().version() + " (declared; synthetic grammar " + ADAPTER + ")";
            List<Report.Issue> all = new ArrayList<>(issues); all.addAll(converterIssues(build, converted));
            var report = new Report.CompatibilityReport("pega", version, ADAPTER, a.sourceSha256(), processId, label, Report.ElementCounts.of(all), List.copyOf(all),
                    NativeConversion.report(a, null, null, List.of(), null, false).assumptions(),
                    converted == null ? new Report.RequiredMappings(List.of(), List.of(), List.of()) : new Report.RequiredMappings(converted.requiredMappings().groups(), converted.requiredMappings().approvalTasks(), List.of()),
                    false);
            return new Plan(start, processId, label, List.copyOf(results), List.copyOf(assignments), List.copyOf(issues), blocked, build, report);
        }
    }

    static String esc(String text) { return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;"); }
}
