package com.orqix.migration.blueprint;

import java.util.*;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.blueprint.Transcription.*;

/**
 * Findings and compatibility report of a blueprint transcription (docs/build/S91). Missing or ambiguous semantics become
 * findings with a source page reference; nothing is invented. Severity follows {@link Report}: open blockers and actions
 * stop conversion, info items need acknowledgement, review items are evidence.
 */
public final class BlueprintFindings {
    public static final String ADAPTER = "pega-blueprint-pdf-0.1";

    /** Facts about the uploaded document itself, kept with the job. */
    public record DocumentFacts(int pageCount, List<Integer> unclassifiedLines, List<Boolean> emptyPages, boolean javascript,
            boolean embeddedFiles, String layoutId, String layoutRevision) {
        public DocumentFacts { unclassifiedLines = List.copyOf(unclassifiedLines); emptyPages = List.copyOf(emptyPages); }
    }

    private BlueprintFindings() { }

    /**
     * @param targets source element id to canonical node id once converted (empty before)
     */
    public static Report.CompatibilityReport report(Transcription t, DocumentFacts facts, String sourceHash, boolean acknowledgeInfo,
            boolean acknowledgeReview, Map<String, String> targets) {
        List<Report.Issue> issues = new ArrayList<>();
        Map<String, Element> byId = new HashMap<>();
        for (Element e : t.elements()) byId.put(e.id(), e);
        Set<String> problems = new HashSet<>();
        Finder f = new Finder(issues, problems, facts, acknowledgeInfo, acknowledgeReview);

        // Document-level facts.
        if (facts.javascript() || facts.embeddedFiles())
            f.document("BLUEPRINT_ACTIVE_CONTENT_IGNORED", Report.INFO, "The PDF contains " + (facts.javascript() ? "JavaScript or automatic actions" : "")
                    + (facts.javascript() && facts.embeddedFiles() ? " and " : "") + (facts.embeddedFiles() ? "embedded files" : "")
                    + ". They were not executed, opened or imported; only page text was read.", "Acknowledge.");
        for (int i = 0; i < facts.pageCount(); i++) {
            if (i < facts.emptyPages().size() && facts.emptyPages().get(i))
                f.page(i + 1, "BLUEPRINT_PAGE_WITHOUT_TEXT", "Page " + (i + 1) + " has no extractable text (for example a scanned image or diagram). No OCR or image interpretation was attempted, so anything shown only there is not in the transcription.",
                        "Transcribe anything relevant from this page manually, or acknowledge.");
            else if (i < facts.unclassifiedLines().size() && facts.unclassifiedLines().get(i) > 0)
                f.page(i + 1, "BLUEPRINT_TEXT_UNCLASSIFIED", facts.unclassifiedLines().get(i) + " line(s) of text on page " + (i + 1)
                        + " were not recognised as a blueprint element. They are kept in the source but are not part of the transcription.",
                        "Read the page text and add any missing element manually, or acknowledge.");
        }
        Set<String> admittedSteps = new HashSet<>();
        for (Step s : t.steps()) if (!s.meta().excluded() && "approval".equals(s.type())) admittedSteps.add(s.id());
        boolean anyActiveFlow = t.steps().stream().anyMatch(s -> !s.meta().excluded()) || t.decisions().stream().anyMatch(d -> !d.meta().excluded());
        if (!anyActiveFlow)
            f.document("BLUEPRINT_NO_STRUCTURE", Report.BLOCKER, "No steps or decisions were recognised" + (t.elements().isEmpty() ? " and the document produced no blueprint elements" : "")
                    + ". Without a transcribed flow nothing can be converted, and no behavior is invented.", "Transcribe the steps and decisions from the source pages, or use another source.");
        if (t.outcomes().stream().allMatch(o -> o.meta().excluded()))
            f.document("BLUEPRINT_OUTCOME_MISSING", Report.BLOCKER, "No outcome (end state) is transcribed, so a converted workflow could not finish.", "Add the outcomes the source names.");
        if (t.caseType() == null) f.document("BLUEPRINT_CASE_TYPE_MISSING", Report.ACTION, "No case type name was found.", "Add the case type name from the source.");
        if (anyActiveFlow) {
            Element start = byId.get(t.start());
            if (start == null || start.meta().excluded() || !(start instanceof Step || start instanceof Decision))
                f.document("BLUEPRINT_START_MISSING", Report.ACTION, "The first step or decision of the flow is not set or no longer exists.", "Set start to the first step or decision.");
        }
        if (t.fields().stream().filter(x -> !x.meta().excluded()).count() > 30)
            f.document("BLUEPRINT_TOO_MANY_FIELDS", Report.BLOCKER, "More than 30 request fields are transcribed; the request form supports 30.", "Exclude fields that are not request inputs.");

        // Element reviews and dispositions.
        for (Element e : t.elements()) {
            Meta m = e.meta();
            if (m.sourcePage() != null && m.sourcePage() > facts.pageCount())
                f.add(e, Report.ACTION, "BLUEPRINT_SOURCE_PAGE_INVALID", "The source page " + m.sourcePage() + " does not exist; the document has " + facts.pageCount() + " page(s).", "Correct the page reference.");
            switch (m.disposition()) {
                case Transcription.PROPOSED -> f.add(e, Report.ACTION, "BLUEPRINT_REVIEW_REQUIRED", "This element was transcribed automatically and has not been reviewed against its source page.",
                        "Compare it with the source text, correct it if needed, then confirm it as mapped or exclude it with a reason.");
                case Transcription.EXCLUDED -> {
                    if (m.dispositionReason() == null) f.add(e, Report.ACTION, "BLUEPRINT_EXCLUSION_REASON_REQUIRED", "An excluded element needs a reason.", "Give the reason this element is out of scope.");
                    else f.add(e, Report.INFO, "BLUEPRINT_ELEMENT_EXCLUDED", "Excluded from conversion by the reviewer: " + m.dispositionReason(), "Acknowledge the exclusion.");
                }
                case Transcription.MAPPED -> { if (m.sourcePage() == null) f.add(e, Report.ACTION, "BLUEPRINT_SOURCE_REFERENCE_REQUIRED", "A mapped element must name its source page.", "Add the source page, or mark it manually resolved."); }
                default -> { if (m.sourcePage() == null) f.add(e, Report.REVIEW, "BLUEPRINT_MANUAL_ELEMENT", "This element was added or resolved manually and has no source page reference.", "Check it with the business owner."); }
            }
        }
        for (Stage s : t.stages()) if (!s.meta().excluded())
            f.add(s, Report.INFO, "BLUEPRINT_STAGE_INFORMATIONAL", "Stages group steps for people; the converted workflow does not execute stage boundaries. The stage stays in the transcription.", "Acknowledge.");
        for (Field x : t.fields()) if (!x.meta().excluded() && x.type() == null)
            f.add(x, Report.ACTION, "BLUEPRINT_FIELD_TYPE_UNKNOWN", "The field type is not stated or not recognised.", "Choose string, number, integer, boolean or date.");
        for (Step s : t.steps()) {
            if (s.meta().excluded()) continue;
            if (s.type() == null) f.add(s, Report.ACTION, "BLUEPRINT_STEP_KIND_UNKNOWN", "The source does not say whether this step is an approval, a manual task or an automated step.", "Set the step type.");
            else if (!"approval".equals(s.type())) f.add(s, Report.BLOCKER, "BLUEPRINT_STEP_UNSUPPORTED", "Only human approval steps are converted from a blueprint. This " + s.type()
                    + " step has no supported target and is not dropped silently.", "Reconstruct it in the studio after conversion and exclude it here with a reason, or change the source scope.");
            if (s.role() == null) f.add(s, Report.BLOCKER, "BLUEPRINT_STEP_ROLE_MISSING", "The step has no role, so nobody could be assigned.", "Choose the role from the source.");
            else if (!(byId.get(s.role()) instanceof Role r) || r.meta().excluded()) f.add(s, Report.ACTION, "BLUEPRINT_ROLE_UNDECLARED", "The role '" + s.role() + "' is not a transcribed, included role.", "Pick a declared role.");
            if (s.stage() != null && (!(byId.get(s.stage()) instanceof Stage st) || st.meta().excluded())) f.add(s, Report.ACTION, "BLUEPRINT_STAGE_UNKNOWN", "The stage '" + s.stage() + "' is not transcribed.", "Pick a transcribed stage or clear it.");
            route(f, byId, s, s.next(), "next", "BLUEPRINT_ROUTE_MISSING", "The step does not say where the flow continues after it.");
            if ("approval".equals(s.type())) route(f, byId, s, s.onReject(), "onReject", "BLUEPRINT_REJECTION_ROUTE_MISSING", "The approval has no rejection outcome; an approver can always reject.");
        }
        for (Decision d : t.decisions()) {
            if (d.meta().excluded()) continue;
            if (d.rules().isEmpty()) { f.add(d, Report.BLOCKER, "BLUEPRINT_DECISION_OUTCOMES_MISSING", "The decision has no conditions or outcomes.", "Transcribe its conditions and targets."); continue; }
            for (int i = 0; i < d.rules().size(); i++) {
                Rule r = d.rules().get(i); String at = " (condition " + (i + 1) + ")";
                Element field = byId.get(r.field());
                if (!(field instanceof Field fx) || fx.meta().excluded()) f.add(d, Report.ACTION, "BLUEPRINT_DECISION_FIELD_UNKNOWN", "A condition does not compare a transcribed request field" + at + ".", "Choose the field.");
                else if (r.operator() == null || r.value() == null) f.add(d, Report.ACTION, "BLUEPRINT_DECISION_CONDITION_INCOMPLETE", "A condition has no operator or value" + at + ".", "Complete the condition.");
                else if (!valueFits(fx.type(), r.operator(), r.value())) f.add(d, Report.ACTION, "BLUEPRINT_DECISION_VALUE_INVALID", "The value '" + r.value() + "' or operator " + r.operator()
                        + " does not fit the " + fx.type() + " field " + fx.id() + at + ". Text and true/false fields support only == and !=; dates are not compared.", "Correct the condition.");
                route(f, byId, d, r.target(), "rule target", "BLUEPRINT_ROUTE_MISSING", "A condition has no target" + at + ".");
            }
            route(f, byId, d, d.otherwise(), "otherwise", "BLUEPRINT_DECISION_DEFAULT_MISSING", "The decision has no otherwise route, so a request matching no condition would stop.");
        }
        Map<String, Integer> slasPerStep = new HashMap<>();
        for (Sla s : t.slas()) {
            if (s.meta().excluded()) continue;
            if (s.step() == null || !admittedSteps.contains(s.step())) { f.add(s, Report.ACTION, "BLUEPRINT_SLA_STEP_UNKNOWN", "The SLA is not attached to an included approval step.", "Choose the approval step it applies to, or exclude it."); continue; }
            if (slasPerStep.merge(s.step(), 1, Integer::sum) > 1) f.add(s, Report.ACTION, "BLUEPRINT_SLA_AMBIGUOUS", "More than one SLA applies to the same step.", "Keep one and exclude the others.");
            if (s.amount() == null) f.add(s, Report.ACTION, "BLUEPRINT_SLA_AMOUNT_MISSING", "The SLA has no duration.", "Enter the duration from the source.");
            if (s.unit() == null) f.add(s, Report.ACTION, "BLUEPRINT_SLA_UNIT_MISSING", "The SLA has no unit (minutes, hours or days); its meaning is not guessed.", "Choose the unit stated in the source.");
            else if ("workingDays".equals(s.unit())) f.add(s, Report.BLOCKER, "BLUEPRINT_SLA_CALENDAR_UNSUPPORTED", "Working-day SLAs need a business calendar mapping, which this importer does not provide.",
                    "Restate it as elapsed time if that is the source meaning, or exclude it and set the due time with a calendar in the studio.");
            else if (s.amount() != null && minutes(s) > 43_200) f.add(s, Report.ACTION, "BLUEPRINT_SLA_OUT_OF_RANGE", "Approval due times can be at most 30 days.", "Correct or exclude the SLA.");
        }
        for (Outcome o : t.outcomes()) if (!o.meta().excluded() && o.type() == null)
            f.add(o, Report.ACTION, "BLUEPRINT_OUTCOME_KIND_UNKNOWN", "It is not stated whether this outcome is a success or a rejection.", "Set the outcome type.");
        for (Integration i : t.integrations()) {
            if (i.meta().excluded()) continue;
            f.add(i, Report.BLOCKER, "BLUEPRINT_INTEGRATION_UNSUPPORTED", "Integrations are not converted from a blueprint; calling an external system needs an approved connection and mapping.",
                    "Exclude it with a reason and add an approved connection step in the studio, or keep the migration blocked.");
            if (i.target() == null) f.add(i, Report.ACTION, "BLUEPRINT_INTEGRATION_TARGET_MISSING", "The integration names no target system.", "Record the target system from the source.");
        }
        // Everything reviewed and without an open problem is mapped.
        for (Element e : t.elements()) {
            if (problems.contains(e.id()) || e.meta().excluded() || Transcription.PROPOSED.equals(e.meta().disposition())) continue;
            issues.add(new Report.Issue(e.id(), e.name(), kind(e), Report.SUPPORTED, "BLUEPRINT_ELEMENT_MAPPED", Transcription.MANUAL.equals(e.meta().disposition())
                    ? "Manually resolved by the reviewer." : "Reviewed against the source and admitted.", null, targets.get(e.id()), Report.RESOLVED, path(e)));
        }
        List<Report.Issue> withTargets = issues.stream().map(i -> i.targetNodeId() == null && targets.containsKey(i.sourceElementId()) ? i.withTarget(targets.get(i.sourceElementId())) : i).toList();
        List<String> groups = t.steps().stream().filter(s -> admittedSteps.contains(s.id()) && s.role() != null).map(Step::role).distinct().toList();
        boolean publishable = withTargets.stream().noneMatch(Report.Issue::blocksPublishing)
                && withTargets.stream().noneMatch(i -> Report.INFO.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus()));
        return new Report.CompatibilityReport("pega", "Blueprint PDF (" + facts.layoutId() + " " + facts.layoutRevision() + ", " + facts.pageCount() + " pages)", ADAPTER, sourceHash,
                t.caseType() == null ? null : com.orqix.migration.camunda.Identifiers.sanitize(t.caseType().name(), true), t.caseType() == null ? null : t.caseType().name(),
                Report.ElementCounts.of(withTargets), withTargets,
                List.of("SYNTHETIC-LAYOUT NOTICE: the transcription was read with the generic outline layout, not a parser of Pega's own Blueprint export; no compatibility with that export is claimed.",
                        "Only page text was read. JavaScript, attachments, images and diagrams were not executed, opened or interpreted.",
                        "The reviewed transcription, not the PDF, is converted. Only human approval steps, typed request fields, exclusive routes on field comparisons, outcomes and elapsed-time SLAs are admitted.",
                        "Roles map to workspace groups explicitly; no source users, permissions or running cases are migrated."),
                new Report.RequiredMappings(groups, List.of(), List.of()), publishable);
    }

    private static void route(Finder f, Map<String, Element> byId, Element owner, String target, String what, String missingCode, String missingText) {
        if (target == null) { f.add(owner, Report.ACTION, missingCode, missingText, "Set the " + what + " target from the source."); return; }
        Element e = byId.get(target);
        if (!(e instanceof Step || e instanceof Decision || e instanceof Outcome))
            f.add(owner, Report.ACTION, "BLUEPRINT_TARGET_UNKNOWN", "The " + what + " target '" + target + "' is not a transcribed step, decision or outcome.", "Choose an existing target.");
        else if (e.meta().excluded())
            f.add(owner, Report.BLOCKER, "BLUEPRINT_TARGET_EXCLUDED", "The " + what + " target '" + target + "' is excluded, so this route would lead nowhere.", "Route to an included element.");
        else if (e == owner) f.add(owner, Report.ACTION, "BLUEPRINT_TARGET_UNKNOWN", "An element cannot route to itself.", "Choose another target.");
    }

    static boolean valueFits(String type, String operator, String value) {
        if (type == null) return false;
        return switch (type) {
            case "number" -> value.matches("-?\\d{1,15}(\\.\\d{1,6})?");
            case "integer" -> value.matches("-?\\d{1,15}");
            case "boolean" -> Set.of("==", "!=").contains(operator) && value.matches("true|false");
            case "string" -> Set.of("==", "!=").contains(operator) && value.length() <= 200 && !value.contains("\"") && !value.contains("'") && !value.contains("\\") && !value.contains("$") && !value.contains("{");
            default -> false;
        };
    }

    static long minutes(Sla s) {
        return switch (s.unit()) { case "minutes" -> s.amount(); case "hours" -> s.amount() * 60L; default -> s.amount() * 1440L; };
    }

    static String kind(Element e) { return "blueprint" + Character.toUpperCase(e.kind().charAt(0)) + e.kind().substring(1); }
    static String path(Element e) {
        String list = switch (e.kind()) { case "caseType" -> "/caseType"; case "sla" -> "/slas/" + e.id(); default -> "/" + e.kind() + "s/" + e.id(); };
        return (e.meta().sourcePage() == null ? "" : "page " + e.meta().sourcePage() + " ") + list;
    }

    private record Finder(List<Report.Issue> issues, Set<String> problems, DocumentFacts facts, boolean acknowledgeInfo, boolean acknowledgeReview) {
        void add(Element e, String severity, String code, String explanation, String action) {
            if (Report.BLOCKER.equals(severity) || Report.ACTION.equals(severity)) problems.add(e.id());
            issues.add(new Report.Issue(e.id(), e.name(), kind(e), severity, code, explanation, action, null, status(severity), path(e)));
        }
        void page(int page, String code, String explanation, String action) {
            issues.add(new Report.Issue("page-" + page, "Page " + page, "blueprintPage", Report.INFO, code, explanation, action, null, status(Report.INFO), "page " + page));
        }
        void document(String code, String severity, String explanation, String action) {
            issues.add(new Report.Issue("document", null, "blueprintDocument", severity, code, explanation, action, null, status(severity), "/"));
        }
        private String status(String severity) {
            if (Report.INFO.equals(severity)) return acknowledgeInfo ? Report.ACKNOWLEDGED : Report.OPEN;
            if (Report.REVIEW.equals(severity)) return acknowledgeReview ? Report.ACKNOWLEDGED : Report.OPEN;
            return Report.OPEN;
        }
    }
}
