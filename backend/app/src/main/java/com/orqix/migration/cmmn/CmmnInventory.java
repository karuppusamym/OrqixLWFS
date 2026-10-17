package com.orqix.migration.cmmn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.migration.camunda.Analyzer;
import com.orqix.migration.camunda.Limits;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.camunda.SafeXml;
import com.orqix.migration.camunda.SourceException;
import com.orqix.migration.camunda.XElement;

/** Bounded, non-executable CMMN 1.1 source inventory. Never presents engine availability as application conversion. */
public final class CmmnInventory {
    public static final String NAMESPACE = "http://www.omg.org/spec/CMMN/20151109/MODEL";
    public static final String ADAPTER_VERSION = "cmmn-inventory-0.1";
    private static final Set<String> CONTAINERS = Set.of("definitions", "case", "casePlanModel", "stage", "planningTable");
    private static final Set<String> TASKS = Set.of("humanTask", "processTask", "caseTask", "decisionTask", "task");
    private static final Set<String> EVENTS = Set.of("milestone", "eventListener", "userEventListener", "timerEventListener");
    private static final Set<String> CONTROLS = Set.of("planItem", "discretionaryItem", "sentry", "entryCriterion", "exitCriterion",
            "planItemOnPart", "caseFileItemOnPart", "standardEvent", "ifPart", "condition", "itemControl", "defaultControl",
            "repetitionRule", "manualActivationRule", "requiredRule", "completionNeutralRule");
    private static final Set<String> DATA = Set.of("caseFileModel", "caseFileItem", "caseFileItemDefinition", "property",
            "expression", "parameter", "inputs", "outputs", "input", "output", "implementationType", "documentation",
            "import", "relationship", "role", "participant", "applicabilityRule", "tableItem", "planFragment");
    private static final Set<String> REFERENCES = Set.of("definitionRef", "sentryRef", "sourceRef");

    private CmmnInventory() { }

    public static Report.CompatibilityReport analyze(String content) {
        XElement root = SafeXml.parse(content, Limits.DEFAULT);
        if (!root.is(NAMESPACE, "definitions")) {
            throw SourceException.invalid("This is not a CMMN 1.1 file. Expected cmmn:definitions in " + NAMESPACE + ".");
        }
        List<XElement> cases = root.children(NAMESPACE, "case");
        if (cases.isEmpty()) throw SourceException.invalid("The CMMN definitions contain no case.");
        List<Report.Issue> issues = new ArrayList<>();
        Map<String, XElement> ids = new HashMap<>();
        List<XElement> elements = new ArrayList<>();
        Deque<XElement> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            XElement element = pending.pop();
            elements.add(element);
            for (int i = element.children().size() - 1; i >= 0; i--) pending.push(element.children().get(i));
            String id = element.attr("id");
            if (id != null && !id.isBlank() && ids.putIfAbsent(id, element) != null) {
                issues.add(issue(element, Report.BLOCKER, "CMMN_DUPLICATE_ID", "The identifier '" + id + "' is used more than once.",
                        "Give each source element a unique identifier."));
            }
        }
        for (XElement element : elements) {
            String name = element.name();
            if (!NAMESPACE.equals(element.ns())) {
                issues.add(issue(element, Report.BLOCKER, "CMMN_EXTENSION_REVIEW_REQUIRED",
                        "This extension element has no Orqix execution mapping.", "Review its behavior before case conversion is enabled."));
            } else if (CONTAINERS.contains(name) || TASKS.contains(name) || EVENTS.contains(name) || CONTROLS.contains(name) || DATA.contains(name)) {
                issues.add(issue(element, Report.BLOCKER, "CMMN_RUNTIME_NOT_EXPOSED",
                        "The CMMN " + name + " is inventoried, but Orqix cannot publish or run imported cases yet.",
                        "Retain this source and review it when workspace case APIs and conformance are delivered."));
            } else {
                issues.add(issue(element, Report.BLOCKER, "CMMN_ELEMENT_UNRECOGNIZED",
                        "The CMMN element " + name + " is not recognized by this inventory adapter.",
                        "Review this construct and its execution meaning explicitly."));
            }
            for (String reference : REFERENCES) {
                String target = element.attr(reference);
                if (target != null && !target.isBlank() && !ids.containsKey(target)) {
                    issues.add(issue(element, Report.BLOCKER, "CMMN_REFERENCE_MISSING",
                            reference + " points to an identifier absent from this file.", "Supply the referenced element before conversion."));
                }
            }
            Set<String> extensionNames = new HashSet<>();
            for (String attribute : element.attributes().keySet()) {
                if (attribute.startsWith("{") && !attribute.startsWith("{" + NAMESPACE + "}")) extensionNames.add(attribute);
            }
            if (!extensionNames.isEmpty()) issues.add(issue(element, Report.BLOCKER, "CMMN_EXTENSION_ATTRIBUTE_REVIEW_REQUIRED",
                    "Extension attributes are present: " + String.join(", ", extensionNames.stream().sorted().toList()) + ". Values are not copied into this report.",
                    "Review extension behavior and credentials in the source system."));
        }
        for (XElement caseElement : cases) {
            if (caseElement.attr("id") == null || caseElement.attr("id").isBlank()) {
                issues.add(issue(caseElement, Report.BLOCKER, "CMMN_CASE_ID_MISSING", "A case has no identifier.", "Add a stable case id."));
            }
        }
        XElement first = cases.getFirst();
        String hash = Analyzer.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        return new Report.CompatibilityReport("cmmn", "1.1", ADAPTER_VERSION, hash, first.attr("id"), first.attr("name"),
                Report.ElementCounts.of(issues), List.copyOf(issues), List.of(
                        "This is a source inventory, not a CMMN conversion or conformance certification.",
                        "The internal CMMN engine test does not make imported cases available through workspace APIs.",
                        "All cases in this file are inventoried; none is deployed or published."),
                new Report.RequiredMappings(List.of(), List.of(), List.of()), false);
    }

    private static Report.Issue issue(XElement element, String severity, String code, String explanation, String action) {
        Deque<String> path = new ArrayDeque<>();
        for (XElement current = element; current != null; current = current.parent()) {
            String id = current.attr("id");
            path.push(current.name() + (id == null || id.isBlank() ? "[#" + current.ordinal() + "]" : "[" + id + "]"));
        }
        String id = element.attr("id");
        return new Report.Issue(id == null || id.isBlank() ? element.name() + "#" + element.ordinal() : id,
                element.attr("name"), element.name(), severity, code, explanation, action, null, Report.OPEN,
                String.join("/", path));
    }
}
