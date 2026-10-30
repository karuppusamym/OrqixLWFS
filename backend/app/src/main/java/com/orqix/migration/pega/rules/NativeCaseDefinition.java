package com.orqix.migration.pega.rules;

import java.util.*;
import java.util.regex.Pattern;
import com.orqix.migration.camunda.Identifiers;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.pega.rules.RuleArchive.Entry;
import com.orqix.migration.pega.rules.RuleResolution.Analysis;
import com.orqix.migration.pega.rules.RuleResolution.Start;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Pega case type as a CMMN case definition in the case designer's admitted subset (docs/build/S117). Stages become
 * auto-completing CMMN stages run in order; each process becomes a blocking workflow task pinned to the published
 * workflow converted from that flow (workflowMappings); optional processes become discretionary items planned by a
 * mapped group; alternate stages become stages entered by a user event that a mapped group raises, which also exits the
 * primary stages; case and stage service levels become timers that reach a "deadline passed" milestone. Typed case data
 * fields come from the properties the case type and its processes use. Nothing is created here: the caller previews the
 * proposal, then creates the definition through the case definition API, which validates and pins it.
 */
public final class NativeCaseDefinition {
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");
    private static final Pattern GROUP = Pattern.compile("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");
    private static final Set<String> IDENTITY = Set.of("pxObjClass", "pyClassName", "pyRuleName", "pyRuleSet", "pyRuleSetVersion",
            "pyRuleAvailable", "pzInsKey", "pyLabel", "pyDescription");

    /** One process of the case type: its workflow task, the flow it runs and how it starts. */
    public record Process(String taskId, String stageId, String stageLabel, String flow, String rule, boolean optional, boolean alternate) { }
    /** An alternate stage and the user event that enters it. */
    public record Alternate(String stageId, String label) { }
    public record Proposal(String key, String name, String sourceXml, ArrayNode dataFields, ArrayNode workflowTasks,
            List<Process> processes, List<Alternate> alternates, Set<String> groups, boolean blocked, Report.CompatibilityReport report) { }

    private record Stage(String id, String label, String path, List<Process> processes, String sla) { }

    private NativeCaseDefinition() { }

    /** The case target's structure (for the review page), without mappings. */
    public static Proposal preview(Analysis a, Start caseType, Json json) {
        return build(a, caseType, json.object(), json);
    }

    public static Proposal build(Analysis a, Start caseType, JsonNode request, Json json) {
        List<Report.Issue> issues = new ArrayList<>();
        Entry root = a.entry(caseType.rule());
        String key = request.path("key").asString(Identifiers.sanitize(root.ruleName(), true));
        String name = request.path("name").asString(root.page().text("pyLabel") == null ? root.ruleName() : root.page().text("pyLabel"));
        List<Stage> primary = new ArrayList<>(), alternates = new ArrayList<>();
        String caseSla = null;
        Page p = root.page();
        try {
            Set<String> allowed = new HashSet<>(IDENTITY);
            allowed.addAll(Set.of("pyNewHarness", "pyStages", "pyAlternateStages", "pyServiceLevelName", "pyCreateValidate"));
            p.allow(allowed);
            Set<String> ids = new HashSet<>();
            primary.addAll(stages(p.pages("pyStages"), false, ids));
            alternates.addAll(stages(p.pages("pyAlternateStages"), true, ids));
            if (primary.isEmpty() || primary.size() + alternates.size() > 20) throw new Page.Grammar(p.pathOf("pyStages"), "1 to 20 stages are required");
        } catch (Page.Grammar g) {
            issues.add(NativeConversion.issue(root.path() + "#grammar", root.label(), "Rule-Obj-CaseType", Report.BLOCKER, "PEGA_NATIVE_GRAMMAR_UNSUPPORTED",
                    "Outside the supported case type grammar: " + g.getMessage() + ".", "Correct the export; no case definition is created.", null, g.sourcePath()));
        }
        // Service levels: elapsed deadlines become timers.
        List<Stage> resolved = new ArrayList<>();
        for (Stage s : primary) resolved.add(new Stage(s.id(), s.label(), s.path(), s.processes(), sla(a, caseType, s.sla(), s.path(), issues)));
        primary = resolved; resolved = new ArrayList<>();
        for (Stage s : alternates) resolved.add(new Stage(s.id(), s.label(), s.path(), s.processes(), sla(a, caseType, s.sla(), s.path(), issues)));
        alternates = resolved;
        if (p.text("pyServiceLevelName") != null) caseSla = sla(a, caseType, p.text("pyServiceLevelName"), p.pathOf("pyServiceLevelName"), issues);

        // The case type as a workflow plan gives the request form (harness, validation) and the per-rule results.
        var casePlan = NativeConversion.plan(a, caseType, json);
        Set<String> caseRules = new HashSet<>();
        for (var r : casePlan.rules()) if (r.role() != null && Set.of("harness", "section", "property", "validate", "transform", "declarative", "caseType").contains(r.role())) caseRules.add(r.rule());
        Set<String> workflowOnly = Set.of("PEGA_NATIVE_ALTERNATE_STAGE_UNSUPPORTED", "PEGA_NATIVE_OPTIONAL_PROCESS_UNSUPPORTED", "PEGA_NATIVE_CASE_SLA_UNSUPPORTED",
                "PEGA_NATIVE_CASE_SLA_AS_STAGE", "PEGA_NATIVE_DEPENDENCY_BLOCKED");
        for (Report.Issue i : casePlan.sourceIssues()) {
            String rule = i.sourceElementId().contains("#") ? i.sourceElementId().substring(0, i.sourceElementId().indexOf('#')) : i.sourceElementId();
            if (workflowOnly.contains(i.reasonCode()) || !caseRules.contains(rule) && !rule.equals("archive") && !a.archiveBlockers().stream().anyMatch(b -> b.paths().contains(rule))) continue;
            if (Report.BLOCKER.equals(i.severity()) || Report.ACTION.equals(i.severity()) || Report.REVIEW.equals(i.severity())) issues.add(i);
        }
        LinkedHashMap<String, JsonNode> fields = new LinkedHashMap<>(); Set<String> required = new LinkedHashSet<>();
        collect(casePlan.build().inputSchema(), fields, required);

        // Each process is converted on its own as a workflow (a "process" starting point); its request fields are case data.
        Map<String, Set<String>> processFields = new LinkedHashMap<>();
        List<Process> processes = new ArrayList<>();
        for (Stage s : primary) processes.addAll(s.processes());
        for (Stage s : alternates) processes.addAll(s.processes());
        for (Process proc : processes) {
            if (processFields.containsKey(proc.flow())) continue;
            var lookup = a.lookup(caseType.context(), "Rule-Obj-Flow", proc.flow());
            if (!lookup.resolved()) {
                issues.add(NativeConversion.issue(root.path() + "#" + proc.taskId(), proc.flow(), "Rule-Obj-CaseType", Report.BLOCKER, "PEGA_NATIVE_REFERENCE_UNRESOLVED",
                        "The process flow '" + proc.flow() + "' cannot be resolved (" + lookup.status() + ").", "Correct the export.", null, root.path()));
                processFields.put(proc.flow(), Set.of());
                continue;
            }
            var plan = NativeConversion.plan(a, new Start(lookup.chosen().path(), "process", caseType.context()), json);
            if (plan.blocked())
                issues.add(NativeConversion.issue(lookup.chosen().path() + "#process", proc.flow(), "Rule-Obj-Flow", Report.BLOCKER, "PEGA_NATIVE_PROCESS_NOT_CONVERTIBLE",
                        "The process flow '" + proc.flow() + "' cannot be converted to a workflow, so no workflow task can run it: "
                                + plan.report().issues().stream().filter(Report.Issue::blocksPublishing).map(Report.Issue::reasonCode).distinct().toList() + ".",
                        "Convert this flow on its own (starting point " + lookup.chosen().path() + ") to see and resolve its findings.", null, lookup.chosen().path()));
            processFields.put(proc.flow(), new LinkedHashSet<>(collect(plan.build().inputSchema(), fields, required)));
        }

        // Explicit mappings: the published workflow of each process and the group that plans an optional process or moves
        // the case to an alternate stage. Nothing is inferred.
        JsonNode workflows = request.path("workflowMappings"), roles = request.path("roleMappings");
        Map<String, String> workflowKeys = new LinkedHashMap<>(); Map<String, String> roleOf = new LinkedHashMap<>();
        for (Process proc : processes) {
            String wf = workflows.path(proc.flow()).asString(null);
            if (wf == null || !KEY.matcher(wf).matches())
                issues.add(NativeConversion.issue(root.path() + "#" + proc.taskId(), proc.flow(), "processTask", Report.ACTION, "PEGA_NATIVE_WORKFLOW_MAPPING_REQUIRED",
                        "Name the published workflow converted from the flow '" + proc.flow() + "' under workflowMappings.", "Convert and publish the flow first, then map it here.", null, root.path()));
            else workflowKeys.put(proc.flow(), wf);
            if (proc.optional()) role(roles, proc.flow(), "plans the optional process '" + proc.flow() + "'", root, roleOf, issues);
        }
        for (Stage alt : alternates) role(roles, alt.id(), "moves the case to the alternate stage '" + alt.label() + "'", root, roleOf, issues);
        if (!key.isEmpty() && !KEY.matcher(key).matches())
            issues.add(NativeConversion.issue(root.path() + "#key", key, "case", Report.BLOCKER, "PEGA_NATIVE_CASE_KEY_INVALID", "The case key must be 2 to 63 lowercase letters, digits and hyphens.", null, null, root.path()));

        ArrayNode dataFields = json.mapper().createArrayNode();
        for (var field : fields.entrySet()) {
            ObjectNode f = caseField(field.getKey(), field.getValue(), required.contains(field.getKey()), json, root, issues);
            if (f != null) dataFields.add(f);
        }
        if (dataFields.size() > 30) issues.add(NativeConversion.issue(root.path() + "#fields", null, "case", Report.BLOCKER, "PEGA_NATIVE_CASE_TOO_LARGE", "A case definition has at most 30 data fields; this case type uses " + dataFields.size() + ".", null, null, root.path()));
        ArrayNode tasks = json.mapper().createArrayNode();
        for (Process proc : processes) {
            ObjectNode t = tasks.addObject(); t.put("taskId", proc.taskId());
            ObjectNode in = t.putObject("inputMapping");
            for (String f : processFields.getOrDefault(proc.flow(), Set.of())) in.put(f, f);
            t.putObject("resultMapping");
        }
        String xml = cmmn(key, name, primary, alternates, caseSla, workflowKeys, roleOf);

        boolean ackInfo = request.path("acknowledgeInfo").asBoolean(false), ackReview = request.path("acknowledgeReview").asBoolean(false);
        List<Report.Issue> shown = new ArrayList<>();
        for (Report.Issue i : issues) shown.add(Report.OPEN.equals(i.resolutionStatus()) && (Report.REVIEW.equals(i.severity()) && ackReview || Report.INFO.equals(i.severity()) && ackInfo) ? i.withResolution(Report.ACKNOWLEDGED) : i);
        for (Stage s : primary) if (s.sla() != null || s.processes().stream().anyMatch(Process::optional))
            issues.add(NativeConversion.issue(root.path() + "#stage:" + s.id(), s.label(), "stage", Report.REVIEW, "PEGA_NATIVE_STAGE_MANUAL_COMPLETION",
                    "The stage '" + s.label() + "' keeps waiting for its deadline timer or optional process after its required processes finish, so a case worker completes it (Complete stage). Pega moves on automatically.",
                    "Review and acknowledge.", null, root.path()));
        if (!alternates.isEmpty() || caseSla != null)
            issues.add(NativeConversion.issue(root.path() + "#close", root.label(), "case", Report.REVIEW, "PEGA_NATIVE_CASE_MANUAL_CLOSE",
                    "The case keeps waiting for its alternate-stage event or deadline timer after the last stage, so a case worker closes it (Close case). Pega resolves it automatically.",
                    "Review and acknowledge.", null, root.path()));
        shown.clear();
        for (Report.Issue i : issues) shown.add(Report.OPEN.equals(i.resolutionStatus()) && (Report.REVIEW.equals(i.severity()) && ackReview || Report.INFO.equals(i.severity()) && ackInfo) ? i.withResolution(Report.ACKNOWLEDGED) : i);
        shown.add(NativeConversion.issue(root.path(), root.label(), "Rule-Obj-CaseType", Report.SUPPORTED, "PEGA_NATIVE_CASE_DEFINITION",
                "Converted to a CMMN case definition: " + primary.size() + " stage(s), " + alternates.size() + " alternate stage(s), " + processes.size()
                        + " workflow task(s)" + (caseSla == null ? "" : ", case deadline " + caseSla) + ".", null, key, root.path()));
        boolean blocked = shown.stream().anyMatch(Report.Issue::blocksPublishing) || !a.archiveBlockers().isEmpty();
        boolean ready = !blocked && shown.stream().noneMatch(i -> Report.OPEN.equals(i.resolutionStatus()) && (Report.REVIEW.equals(i.severity()) || Report.INFO.equals(i.severity())));
        Set<String> groups = new TreeSet<>(roleOf.values());
        var report = new Report.CompatibilityReport("pega", casePlan.report().sourceVersion(), NativeConversion.ADAPTER, a.sourceSha256(), key, name,
                Report.ElementCounts.of(shown), List.copyOf(shown),
                List.of("The case type becomes a CMMN case definition in the case designer's admitted subset (docs/build/S117). Each process is a workflow task that runs the published workflow converted from that flow; the case definition pins its release when it is created.",
                        "Optional processes become discretionary items and alternate stages are entered by a user event; the groups allowed to do this are mapped explicitly. Service level deadlines become timers that reach a milestone; goals and escalations are not reproduced.",
                        "Case data fields come from the properties the case type and its processes use. The grammar is synthetic; fidelity against genuine exports remains AC01/EXT01."),
                new Report.RequiredMappings(List.copyOf(groups), List.of(), List.of(), processes.stream().map(Process::flow).distinct().toList()), ready);
        List<Alternate> alts = alternates.stream().map(s -> new Alternate(s.id(), s.label())).toList();
        return new Proposal(key, name, xml, dataFields, tasks, List.copyOf(processes), alts, groups, blocked, report);
    }

    private static List<Stage> stages(List<Page> rows, boolean alternate, Set<String> ids) {
        List<Stage> out = new ArrayList<>();
        for (Page s : rows) {
            s.allow(Set.of("pyStageID", "pyStageName", "pyServiceLevelName", "pyProcesses"));
            String id = s.text("pyStageID"), label = s.text("pyStageName");
            if (id == null || !id.matches("^[a-z][a-z0-9-]{0,40}$") || !ids.add(id)) throw new Page.Grammar(s.pathOf("pyStageID"), "a unique lowercase stage key is required");
            if (RuleArchive.blank(label) || label.length() > 80) throw new Page.Grammar(s.pathOf("pyStageName"), "a stage name of at most 80 characters is required");
            List<Page> processes = s.pages("pyProcesses");
            if (processes.isEmpty() || processes.size() > 5) throw new Page.Grammar(s.pathOf("pyProcesses"), "1 to 5 processes per stage are required");
            List<Process> list = new ArrayList<>();
            for (Page proc : processes) {
                proc.allow(Set.of("pyFlowName", "pyOptional"));
                String flow = proc.text("pyFlowName");
                if (RuleArchive.blank(flow)) throw new Page.Grammar(proc.path(), "pyFlowName is required");
                String optional = proc.text("pyOptional");
                if (optional != null && !Set.of("true", "false").contains(optional)) throw new Page.Grammar(proc.pathOf("pyOptional"), "pyOptional must be true or false");
                String task = ("t_" + id + "_" + Identifiers.sanitize(flow, false)).replace('-', '_');
                list.add(new Process(task.substring(0, Math.min(64, task.length())), id, label, flow, null, "true".equals(optional), alternate));
            }
            if (list.stream().allMatch(Process::optional)) throw new Page.Grammar(s.pathOf("pyProcesses"), "a stage needs at least one process that is not optional");
            out.add(new Stage(id, label, s.path(), list, s.text("pyServiceLevelName")));
        }
        return out;
    }

    private static void role(JsonNode roles, String name, String what, Entry root, Map<String, String> roleOf, List<Report.Issue> issues) {
        String group = roles.path(name).asString(null);
        if (group == null || !GROUP.matcher(group).matches())
            issues.add(NativeConversion.issue(root.path() + "#role:" + name, name, "caseRole", Report.ACTION, "PEGA_NATIVE_ROLE_MAPPING_REQUIRED",
                    "Name the workspace group that " + what + " under roleMappings['" + name + "'].", "Map a workspace group; Pega access groups are not migrated.", null, root.path()));
        else roleOf.put(name, group);
    }

    /** An elapsed service level deadline as an ISO duration; business days and escalations have no timer equivalent. */
    private static String sla(Analysis a, Start caseType, String name, String path, List<Report.Issue> issues) {
        if (name == null) return null;
        var l = a.lookup(caseType.context(), "Rule-Obj-ServiceLevel", name);
        if (!l.resolved()) {
            issues.add(NativeConversion.issue(path, name, "Rule-Obj-ServiceLevel", Report.BLOCKER, "PEGA_NATIVE_REFERENCE_UNRESOLVED", "The service level '" + name + "' cannot be resolved (" + l.status() + ").", null, null, path));
            return null;
        }
        Entry rule = l.chosen(); Page p = rule.page();
        try {
            Set<String> allowed = new HashSet<>(IDENTITY);
            allowed.addAll(Set.of("pyGoalDays", "pyGoalHours", "pyGoalMinutes", "pyDeadlineDays", "pyDeadlineHours", "pyDeadlineMinutes", "pyUseBusinessDays",
                    "pyBusinessCalendar", "pyGoalEscalation", "pyDeadlineEscalation", "pyPassedDeadlineHours", "pyPassedDeadlineLimit"));
            p.allow(allowed);
            if (!"false".equals(p.text("pyUseBusinessDays"))) throw new Page.Grammar(p.pathOf("pyUseBusinessDays"), "case timers count elapsed time; business-day service levels are not converted here");
            for (String f : List.of("pyGoalEscalation", "pyDeadlineEscalation", "pyPassedDeadlineHours", "pyPassedDeadlineLimit"))
                if (p.fields().containsKey(f)) throw new Page.Grammar(p.pathOf(f), "escalations are not converted for case timers: the timer only reaches a 'deadline passed' milestone");
            int deadline = NativeConversion.minutesOf(p, "pyDeadline"), goal = NativeConversion.minutesOf(p, "pyGoal");
            if (deadline < 1 || deadline > 366 * 1440) throw new Page.Grammar(p.path(), "a deadline between 1 minute and 366 days is required");
            String duration = deadline % 1440 == 0 ? "P" + deadline / 1440 + "D" : deadline % 60 == 0 ? "PT" + deadline / 60 + "H" : "PT" + deadline + "M";
            issues.add(NativeConversion.issue(rule.path(), rule.label(), "Rule-Obj-ServiceLevel", Report.REVIEW, "PEGA_NATIVE_SLA_AS_TIMER",
                    "The deadline (" + duration + ") becomes a timer that reaches a 'deadline passed' milestone." + (goal > 0 ? " The goal (" + goal + " minutes) is not reproduced." : ""),
                    "Review and acknowledge.", null, rule.path()));
            return duration;
        } catch (Page.Grammar g) {
            issues.add(NativeConversion.issue(rule.path() + "#timer", rule.label(), "Rule-Obj-ServiceLevel", Report.BLOCKER, "PEGA_NATIVE_SLA_TIMER_UNSUPPORTED",
                    "The service level is not converted to a case timer: " + g.getMessage() + ".", "Rebuild it in the case designer.", null, g.sourcePath()));
            return null;
        }
    }

    private static Set<String> collect(ObjectNode schema, LinkedHashMap<String, JsonNode> fields, Set<String> required) {
        Set<String> keys = new LinkedHashSet<>();
        if (schema == null) return keys;
        schema.path("properties").properties().forEach(e -> { fields.putIfAbsent(e.getKey(), e.getValue()); keys.add(e.getKey()); });
        schema.path("required").forEach(r -> required.add(r.asString()));
        return keys;
    }

    /** A request field schema as a case data field: text, number, integer, boolean, date or choice. */
    private static ObjectNode caseField(String key, JsonNode s, boolean required, Json json, Entry root, List<Report.Issue> issues) {
        ObjectNode f = json.object(); f.put("key", key);
        String title = s.path("title").asString(key);
        f.put("label", title.length() > 120 ? title.substring(0, 120) : title);
        String type = s.path("type").asString();
        if (s.has("pattern") || s.has("minLength")) {
            issues.add(NativeConversion.issue(root.path() + "#field:" + key, key, "caseDataField", Report.BLOCKER, "PEGA_NATIVE_CASE_FIELD_CHECK_UNSUPPORTED",
                    "The field '" + key + "' has a pattern or minimum length check. Case data fields check only required, maximum length, numeric range and choices, so the validation would be lost.",
                    "Remove the check from the validate rule, or convert the case type to a workflow, whose request form supports it.", null, root.path()));
            return null;
        }
        switch (type) {
            case "string" -> {
                if (s.has("enum")) { f.put("type", "choice"); f.set("options", s.path("enum").deepCopy()); }
                else if ("date".equals(s.path("format").asString(null))) f.put("type", "date");
                else { f.put("type", "text"); if (s.has("maxLength")) f.put("maxLength", s.path("maxLength").asInt()); }
            }
            case "number", "integer" -> {
                f.put("type", type);
                if (s.has("minimum")) f.set("minimum", s.path("minimum").deepCopy());
                if (s.has("maximum")) f.set("maximum", s.path("maximum").deepCopy());
            }
            case "boolean" -> f.put("type", "boolean");
            default -> { return null; }
        }
        f.put("required", required);
        return f;
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    static String cmmn(String key, String name, List<Stage> primary, List<Stage> alternates, String caseSla, Map<String, String> workflows, Map<String, String> roles) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<definitions xmlns=\"http://www.omg.org/spec/CMMN/20151109/MODEL\" xmlns:flowable=\"http://flowable.org/cmmn\" xmlns:orqixwf=\"urn:orqix:workflow\" targetNamespace=\"urn:orqix:pega-native\">\n");
        x.append("  <case id=\"").append(esc(key)).append("\" name=\"").append(esc(name)).append("\">\n");
        x.append("    <casePlanModel id=\"plan\" name=\"").append(esc(name)).append("\" autoComplete=\"false\">\n");
        StringBuilder defs = new StringBuilder(), sentries = new StringBuilder();
        Map<String, String> roleIds = new LinkedHashMap<>();
        roles.values().forEach(g -> roleIds.putIfAbsent(g, "role_" + g.replace('-', '_')));
        for (int i = 0; i < primary.size(); i++) {
            Stage s = primary.get(i);
            x.append("      <planItem id=\"stage_").append(s.id()).append("\" name=\"").append(esc(s.label())).append("\" definitionRef=\"").append(s.id()).append("\">\n");
            x.append("        <itemControl><requiredRule/></itemControl>\n");
            if (i > 0) {
                String sentry = s.id() + "_after";
                x.append("        <entryCriterion id=\"").append(sentry).append("_entry\" sentryRef=\"").append(sentry).append("\"/>\n");
                sentries.append("      <sentry id=\"").append(sentry).append("\"><planItemOnPart id=\"").append(sentry).append("_on\" sourceRef=\"stage_").append(primary.get(i - 1).id())
                        .append("\"><standardEvent>complete</standardEvent></planItemOnPart></sentry>\n");
            }
            for (Stage alt : alternates) {
                String sentry = s.id() + "_leave_" + alt.id();
                x.append("        <exitCriterion id=\"").append(sentry).append("_exit\" sentryRef=\"").append(sentry).append("\"/>\n");
                sentries.append("      <sentry id=\"").append(sentry).append("\"><planItemOnPart id=\"").append(sentry).append("_on\" sourceRef=\"move_").append(alt.id())
                        .append("\"><standardEvent>occur</standardEvent></planItemOnPart></sentry>\n");
            }
            x.append("      </planItem>\n");
            stage(defs, s, workflows, roles, roleIds);
        }
        for (Stage alt : alternates) {
            x.append("      <planItem id=\"move_").append(alt.id()).append("\" name=\"Move to ").append(esc(alt.label())).append("\" definitionRef=\"").append(alt.id()).append("_event\"/>\n");
            String sentry = alt.id() + "_entered";
            x.append("      <planItem id=\"stage_").append(alt.id()).append("\" name=\"").append(esc(alt.label())).append("\" definitionRef=\"").append(alt.id()).append("\">\n");
            x.append("        <entryCriterion id=\"").append(sentry).append("_entry\" sentryRef=\"").append(sentry).append("\"/>\n      </planItem>\n");
            sentries.append("      <sentry id=\"").append(sentry).append("\"><planItemOnPart id=\"").append(sentry).append("_on\" sourceRef=\"move_").append(alt.id())
                    .append("\"><standardEvent>occur</standardEvent></planItemOnPart></sentry>\n");
            String group = roles.get(alt.id());
            defs.append("      <userEventListener id=\"").append(alt.id()).append("_event\" name=\"Move to ").append(esc(alt.label())).append("\"")
                    .append(group == null ? "" : " authorizedRoleRefs=\"" + roleIds.get(group) + "\"").append("/>\n");
            stage(defs, alt, workflows, roles, roleIds);
        }
        if (caseSla != null) deadline(x, defs, sentries, "case", "Case deadline", caseSla, "      ");
        x.append(sentries).append(defs);
        x.append("    </casePlanModel>\n");
        if (!roleIds.isEmpty()) {
            x.append("    <caseRoles>");
            roleIds.forEach((group, id) -> x.append("<role id=\"").append(id).append("\" name=\"").append(esc(group)).append("\"/>"));
            x.append("</caseRoles>\n");
        }
        x.append("  </case>\n");
        Set<String> flows = new LinkedHashSet<>();
        for (Stage s : primary) s.processes().forEach(p -> flows.add(p.flow()));
        for (Stage s : alternates) s.processes().forEach(p -> flows.add(p.flow()));
        for (String flow : flows)
            x.append("  <process id=\"").append(processId(flow)).append("\" name=\"").append(esc(flow)).append("\" externalRef=\"orqixwf:")
                    .append(esc(workflows.getOrDefault(flow, "unmapped"))).append("\"/>\n");
        return x.append("</definitions>\n").toString();
    }

    static String processId(String flow) { return "p_" + Identifiers.sanitize(flow, false).replace('-', '_'); }

    private static void stage(StringBuilder defs, Stage s, Map<String, String> workflows, Map<String, String> roles, Map<String, String> roleIds) {
        StringBuilder inner = new StringBuilder(), innerDefs = new StringBuilder(), innerSentries = new StringBuilder(), table = new StringBuilder();
        String previous = null;
        for (Process proc : s.processes()) {
            if (proc.optional()) {
                String group = roles.get(proc.flow());
                table.append("          <discretionaryItem id=\"").append(proc.taskId()).append("_item\" name=\"").append(esc(proc.flow())).append("\" definitionRef=\"").append(proc.taskId()).append("\"")
                        .append(group == null ? "" : " authorizedRoleRefs=\"" + roleIds.get(group) + "\"").append("/>\n");
            } else {
                inner.append("        <planItem id=\"").append(proc.taskId()).append("_item\" name=\"").append(esc(proc.flow())).append("\" definitionRef=\"").append(proc.taskId()).append("\">\n");
                inner.append("          <itemControl><requiredRule/></itemControl>\n");
                if (previous != null) {
                    String sentry = proc.taskId() + "_after";
                    inner.append("          <entryCriterion id=\"").append(sentry).append("_entry\" sentryRef=\"").append(sentry).append("\"/>\n");
                    innerSentries.append("        <sentry id=\"").append(sentry).append("\"><planItemOnPart id=\"").append(sentry).append("_on\" sourceRef=\"").append(previous)
                            .append("_item\"><standardEvent>complete</standardEvent></planItemOnPart></sentry>\n");
                }
                inner.append("        </planItem>\n");
                previous = proc.taskId();
            }
            innerDefs.append("        <processTask id=\"").append(proc.taskId()).append("\" name=\"").append(esc(proc.flow())).append("\" processRef=\"").append(processId(proc.flow())).append("\" isBlocking=\"true\"/>\n");
        }
        if (s.sla() != null) deadline(inner, innerDefs, innerSentries, s.id(), s.label() + " deadline", s.sla(), "        ");
        defs.append("      <stage id=\"").append(s.id()).append("\" name=\"").append(esc(s.label())).append("\" autoComplete=\"false\">\n")
                .append(inner).append(innerSentries);
        if (!table.isEmpty()) defs.append("        <planningTable id=\"").append(s.id()).append("_options\">\n").append(table).append("        </planningTable>\n");
        defs.append(innerDefs).append("      </stage>\n");
    }

    private static void deadline(StringBuilder items, StringBuilder defs, StringBuilder sentries, String prefix, String label, String duration, String indent) {
        String id = prefix.replace('-', '_');
        items.append(indent).append("<planItem id=\"").append(id).append("_deadline\" name=\"").append(esc(label)).append("\" definitionRef=\"").append(id).append("_timer\"/>\n");
        items.append(indent).append("<planItem id=\"").append(id).append("_late\" name=\"").append(esc(label)).append(" passed\" definitionRef=\"").append(id).append("_milestone\">")
                .append("<entryCriterion id=\"").append(id).append("_late_entry\" sentryRef=\"").append(id).append("_due\"/></planItem>\n");
        sentries.append(indent).append("<sentry id=\"").append(id).append("_due\"><planItemOnPart id=\"").append(id).append("_due_on\" sourceRef=\"").append(id)
                .append("_deadline\"><standardEvent>occur</standardEvent></planItemOnPart></sentry>\n");
        defs.append(indent).append("<timerEventListener id=\"").append(id).append("_timer\" name=\"").append(esc(label)).append("\"><timerExpression>").append(duration).append("</timerExpression></timerEventListener>\n");
        defs.append(indent).append("<milestone id=\"").append(id).append("_milestone\" name=\"").append(esc(label)).append(" passed\"/>\n");
    }
}
