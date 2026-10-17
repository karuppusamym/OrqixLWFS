package com.orqix.migration.dmn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.orqix.migration.camunda.Analyzer;
import com.orqix.migration.camunda.Limits;
import com.orqix.migration.camunda.Namespaces;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.camunda.SafeXml;
import com.orqix.migration.camunda.SourceException;
import com.orqix.migration.camunda.XElement;
import com.orqix.shared.json.Json;

/**
 * Adapter {@code dmn-0.1}: reads a Camunda 7/8 DMN 1.1-1.4 file and gives every decision, table, column and rule a
 * disposition. A decision table with hit policy UNIQUE or FIRST, one tested input, literal FEEL unary tests and literal
 * outputs converts to a canonical decision table (workflow-v0 {@code decisions[]}); everything else is a blocker with a
 * reason code. Entries are parsed, never evaluated. Pure Java, no Spring, no database.
 */
public final class DmnAnalyzer {

    public static final String ADAPTER_VERSION = "dmn-0.1";

    /** Camunda 7 DMN extension namespace. */
    static final String CAMUNDA_DMN = "http://camunda.org/schema/1.0/dmn";
    private static final Pattern DMN_NAMESPACE = Pattern.compile("^https?://www\\.omg\\.org/spec/DMN/(\\d{8})/(?:MODEL/?|dmn\\.xsd)$");
    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> SUPPORTED_HIT_POLICIES = Set.of("UNIQUE", "FIRST");
    private static final Set<String> NON_TABLE_EXPRESSIONS = Set.of("literalExpression", "context", "invocation", "relation",
            "list", "functionDefinition", "conditional", "filter", "for", "every", "some");

    private record Ordered(int ordinal, int sequence, DmnAnalysis.Item item) {
    }

    private final XElement root;
    private final String ns;
    private final Json json;
    private final DmnAnalysis analysis;
    private final List<Ordered> items = new ArrayList<>();
    private final Set<String> seenDecisionIds = new LinkedHashSet<>();
    private int sequence;

    private DmnAnalyzer(XElement root, String ns, Json json, DmnAnalysis analysis) {
        this.root = root;
        this.ns = ns;
        this.json = json;
        this.analysis = analysis;
    }

    /** Parses (safely) and analyses; throws {@link SourceException} when the file is not a DMN file. */
    public static DmnAnalysis analyze(String content, Limits limits, Json json) {
        XElement root = SafeXml.parse(content, limits);
        var matcher = DMN_NAMESPACE.matcher(root.ns());
        if (!"definitions".equals(root.name()) || !matcher.matches()) {
            throw SourceException.invalid("This is not a DMN 1.1 to 1.4 file. The root element must be definitions in a DMN "
                    + "namespace (http://www.omg.org/spec/DMN/...).");
        }
        String version = switch (matcher.group(1)) {
            case "20151101" -> "DMN 1.1";
            case "20180521" -> "DMN 1.2";
            case "20191111" -> "DMN 1.3";
            case "20211108" -> "DMN 1.4";
            default -> "DMN " + matcher.group(1);
        };
        String id = root.attr("id") == null || root.attr("id").isBlank() ? "definitions" : root.attr("id");
        DmnAnalysis analysis = new DmnAnalysis(version, Analyzer.sha256Hex(content.getBytes(StandardCharsets.UTF_8)), id,
                blankToNull(root.attr("name")));
        return new DmnAnalyzer(root, root.ns(), json, analysis).run();
    }

    // ------------------------------------------------------------------------------------------------ definitions

    private DmnAnalysis run() {
        analysis.assumptions().add("Source detected as " + analysis.report().sourceVersion() + " (Camunda DMN).");
        analysis.assumptions().add("Diagram layout (DMNDI) is not imported.");
        settings(null, root, "definitions");
        int decisions = 0;
        for (XElement child : root.children()) {
            String name = child.name();
            if (!ns.equals(child.ns())) {
                if (child.ns().contains("/DMN/") && child.ns().toUpperCase(Locale.ROOT).contains("DMNDI")) {
                    add(null, child, "DMNDI", Report.INFO, "DIAGRAM_NOT_IMPORTED",
                            "The diagram layout is not imported; the canvas layout is generated.", "Acknowledge.");
                } else {
                    add(null, child, name, Report.INFO, "EXTENSION_NOT_IMPORTED",
                            "The element " + child.label() + " belongs to a namespace Orqix does not know and is not imported.",
                            "Acknowledge.");
                }
                continue;
            }
            switch (name) {
                case "decision" -> {
                    decisions++;
                    decision(child);
                }
                case "inputData" -> add(null, child, name, Report.INFO, "ARTIFACT_NOT_IMPORTED",
                        "The input data '" + labelOf(child) + "' is documentation of the decision requirements. The request field is "
                                + "derived from the decision table input instead.",
                        "Acknowledge.");
                case "knowledgeSource", "itemDefinition", "textAnnotation", "association", "group", "elementCollection",
                        "performanceIndicator", "organizationUnit", "decisionService" -> {
                    if ("decisionService".equals(name)) {
                        add(null, child, name, Report.BLOCKER, "DMN_DRD_NOT_SUPPORTED",
                                "Decision services expose several decisions as one callable unit; they are not converted.",
                                "Convert the decisions one by one.");
                    } else {
                        add(null, child, name, Report.INFO, "ARTIFACT_NOT_IMPORTED",
                                "The " + name + " '" + labelOf(child) + "' is documentation or a type declaration and is not "
                                        + "imported. Input and output types come from the table columns.",
                                "Acknowledge.");
                    }
                }
                case "businessKnowledgeModel" -> add(null, child, name, Report.BLOCKER, "DMN_DRD_NOT_SUPPORTED",
                        "Business knowledge models (reusable functions) are not converted.",
                        "Inline the logic as table rules.");
                case "import" -> add(null, child, name, Report.BLOCKER, "DMN_DRD_NOT_SUPPORTED",
                        "Importing another DMN model is not supported; each file must stand alone.", "Merge the models first.");
                case "description", "documentation" -> add(null, child, name, Report.INFO, "DOCUMENTATION_NOT_IMPORTED",
                        "Documentation text is not imported.", "Acknowledge.");
                case "extensionElements" -> extensions(null, child);
                default -> unknown(null, child);
            }
        }
        if (decisions == 0) {
            add(null, root, "definitions", Report.BLOCKER, "DMN_NO_DECISION", "The file contains no decision.",
                    "Upload a DMN file with at least one decision table.");
        }
        items.sort(Comparator.<Ordered>comparingInt(Ordered::ordinal).thenComparingInt(Ordered::sequence));
        items.forEach(o -> analysis.items().add(o.item()));
        return analysis;
    }

    // ------------------------------------------------------------------------------------------------ decision

    private void decision(XElement decision) {
        String id = idOf(decision);
        if (!seenDecisionIds.add(id)) {
            // Identity is ambiguous: neither the earlier nor the later table may be selected by this id.
            analysis.tables().remove(id);
            analysis.testCases().removeIf(test -> id.equals(test.decision()));
            add(id, decision, "decision", Report.BLOCKER, "DMN_DUPLICATE_DECISION_ID",
                    "Decision ID '" + id + "' appears more than once in this file. No table with this ID can be converted.",
                    "Give every decision a unique ID and update its callers before importing the file again.");
            return;
        }
        analysis.decisions().put(id, labelOf(decision));
        settings(id, decision, "decision");
        XElement table = null;
        boolean otherExpression = false;
        boolean blocked = false;
        for (XElement child : decision.children()) {
            if (!ns.equals(child.ns())) {
                add(id, child, child.name(), Report.INFO, "EXTENSION_NOT_IMPORTED",
                        "The element " + child.label() + " is not imported.", "Acknowledge.");
                continue;
            }
            String name = child.name();
            switch (name) {
                case "decisionTable" -> {
                    if (table == null) {
                        table = child;
                    } else {
                        blocked = true;
                        add(id, child, name, Report.BLOCKER, "DMN_DECISION_TYPE_NOT_SUPPORTED",
                                "A decision holds one decision table; this is a second one.", "Split the decision.");
                    }
                }
                case "informationRequirement" -> {
                    if (child.child(ns, "requiredDecision") != null) {
                        blocked = true;
                        add(id, child, name, Report.BLOCKER, "DMN_DRD_NOT_SUPPORTED",
                                "This decision needs the result of another decision (" + child.child(ns, "requiredDecision")
                                        .attr("href") + "). Chained decisions are not converted.",
                                "Convert each decision on its own and chain them in the workflow.");
                    } else {
                        add(id, child, name, Report.INFO, "ARTIFACT_NOT_IMPORTED",
                                "The input requirement is documentation; the input comes from the table column.", "Acknowledge.");
                    }
                }
                case "knowledgeRequirement", "authorityRequirement", "variable", "question", "allowedAnswers", "description",
                        "documentation" -> add(id, child, name, Report.INFO, "ARTIFACT_NOT_IMPORTED",
                        "The " + name + " of the decision is documentation or a declaration and is not imported.", "Acknowledge.");
                case "extensionElements" -> extensions(id, child);
                default -> {
                    if (NON_TABLE_EXPRESSIONS.contains(name)) {
                        otherExpression = true;
                        blocked = true;
                        add(id, child, name, Report.BLOCKER, "DMN_DECISION_TYPE_NOT_SUPPORTED",
                                "A decision with a " + name + " expression is not converted; only decision tables are.",
                                "Rebuild the logic as a decision table.");
                    } else {
                        unknown(id, child);
                    }
                }
            }
        }
        if (table == null) {
            if (!otherExpression) {
                add(id, decision, "decision", Report.BLOCKER, "DMN_DECISION_TYPE_NOT_SUPPORTED",
                        "The decision has no decision table.", "Rebuild the logic as a decision table.");
            } else {
                add(id, decision, "decision", Report.BLOCKER, "DMN_DECISION_NOT_CONVERTIBLE",
                        "The decision is not a decision table, so it cannot be converted.", "Rebuild it as a decision table.");
            }
            return;
        }
        table(id, decision, table, blocked);
    }

    /** A column of the table. */
    private record Column(XElement element, String id, String label, String name, String typeRef, String expression,
            String allowedValues) {
    }

    private void table(String decisionId, XElement decision, XElement table, boolean blockedByDecision) {
        settings(decisionId, table, "decisionTable");
        String hitPolicy = table.attr("hitPolicy") == null ? "UNIQUE" : table.attr("hitPolicy").strip().toUpperCase(Locale.ROOT);
        List<Column> inputs = new ArrayList<>();
        List<Column> outputs = new ArrayList<>();
        List<XElement> rules = new ArrayList<>();
        for (XElement child : table.children()) {
            if (!ns.equals(child.ns())) {
                add(decisionId, child, child.name(), Report.INFO, "EXTENSION_NOT_IMPORTED",
                        "The element " + child.label() + " is not imported.", "Acknowledge.");
                continue;
            }
            switch (child.name()) {
                case "input" -> {
                    XElement expression = child.child(ns, "inputExpression");
                    XElement values = child.child(ns, "inputValues");
                    inputs.add(new Column(child, idOf(child), blankToNull(child.attr("label")), null,
                            expression == null ? null : blankToNull(expression.attr("typeRef")),
                            expression == null || expression.child(ns, "text") == null ? null : expression.child(ns, "text").text(),
                            values == null || values.child(ns, "text") == null ? null : values.child(ns, "text").text()));
                    settings(decisionId, child, "input");
                }
                case "output" -> {
                    XElement values = child.child(ns, "outputValues");
                    outputs.add(new Column(child, idOf(child), blankToNull(child.attr("label")), blankToNull(child.attr("name")),
                            blankToNull(child.attr("typeRef")), null,
                            values == null || values.child(ns, "text") == null ? null : values.child(ns, "text").text()));
                    settings(decisionId, child, "output");
                }
                case "rule" -> rules.add(child);
                case "annotation", "description" -> add(decisionId, child, child.name(), Report.INFO, "DOCUMENTATION_NOT_IMPORTED",
                        "Annotations and descriptions are not imported.", "Acknowledge.");
                default -> unknown(decisionId, child);
            }
        }
        Table t = new Table(decisionId, decision, table, hitPolicy, inputs, outputs, rules);
        t.convertible = !blockedByDecision;
        t.analyse();
    }

    /** Working state of one decision table analysis. */
    private final class Table {
        final String decisionId;
        final XElement decision;
        final XElement table;
        final String hitPolicy;
        final List<Column> inputs;
        final List<Column> outputs;
        final List<XElement> rules;
        boolean convertible = true;

        Table(String decisionId, XElement decision, XElement table, String hitPolicy, List<Column> inputs, List<Column> outputs,
                List<XElement> rules) {
            this.decisionId = decisionId;
            this.decision = decision;
            this.table = table;
            this.hitPolicy = hitPolicy;
            this.inputs = inputs;
            this.outputs = outputs;
            this.rules = rules;
        }

        private void block(XElement el, String kind, String code, String explanation, String suggestion) {
            convertible = false;
            add(decisionId, el, kind, Report.BLOCKER, code, explanation, suggestion);
        }

        void analyse() {
            if (!SUPPORTED_HIT_POLICIES.contains(hitPolicy)) {
                String aggregation = table.attr("aggregation");
                block(table, "decisionTable", "DMN_HIT_POLICY_NOT_SUPPORTED", "The hit policy " + hitPolicy
                        + (aggregation == null ? "" : " (aggregation " + aggregation + ")") + " returns more than one result, "
                        + "orders results or picks by priority. Only UNIQUE and FIRST convert.",
                        "Rewrite the table with hit policy UNIQUE (exclusive rules) or FIRST (ordered rules).");
                for (Column c : inputs) {
                    block(c.element(), "input", "DMN_HIT_POLICY_NOT_SUPPORTED", "Inside a table with an unsupported hit policy.",
                            "Resolve the hit policy first.");
                }
                for (Column c : outputs) {
                    block(c.element(), "output", "DMN_HIT_POLICY_NOT_SUPPORTED", "Inside a table with an unsupported hit policy.",
                            "Resolve the hit policy first.");
                }
                for (XElement r : rules) {
                    block(r, "rule", "DMN_HIT_POLICY_NOT_SUPPORTED", "Inside a table with an unsupported hit policy.",
                            "Resolve the hit policy first.");
                }
                decisionItem();
                return;
            }
            if (inputs.isEmpty() || outputs.isEmpty() || rules.isEmpty()) {
                block(table, "decisionTable", "DMN_NO_CONDITIONAL_RULE", "The table needs at least one input column, one output "
                        + "column and one rule.", "Complete the table in the source model.");
                decisionItem();
                return;
            }
            if (outputs.size() > 10) {
                block(table, "decisionTable", "DMN_TOO_MANY_OUTPUTS", "The table has " + outputs.size() + " output columns; a "
                        + "canonical result has at most 10.", "Split the table.");
            }
            // Cells per rule.
            List<List<String>> inputCells = new ArrayList<>();
            List<List<String>> outputCells = new ArrayList<>();
            boolean shape = true;
            for (XElement rule : rules) {
                List<XElement> in = rule.children(ns, "inputEntry");
                List<XElement> out = rule.children(ns, "outputEntry");
                if (in.size() != inputs.size() || out.size() != outputs.size()) {
                    shape = false;
                    block(rule, "rule", "DMN_RULE_SHAPE_INVALID", "The rule has " + in.size() + " input and " + out.size()
                            + " output entries but the table has " + inputs.size() + " input and " + outputs.size()
                            + " output columns.", "Fix the rule in the source model.");
                    inputCells.add(List.of());
                    outputCells.add(List.of());
                    continue;
                }
                inputCells.add(in.stream().map(e -> e.child(ns, "text") == null ? "" : e.child(ns, "text").text()).toList());
                outputCells.add(out.stream().map(e -> e.child(ns, "text") == null ? "" : e.child(ns, "text").text()).toList());
                for (XElement entry : concat(in, out)) {
                    String language = entry.attr("expressionLanguage");
                    if (language != null && !language.toLowerCase(Locale.ROOT).contains("feel")) {
                        block(entry, entry.name(), "DMN_ENTRY_NOT_SUPPORTED", "The entry is written in the script language '"
                                + language + "', which is never executed by Orqix.", "Rewrite it as a literal FEEL entry.");
                    }
                }
            }
            if (!shape) {
                decisionItem();
                return;
            }
            // Which input column is tested?
            List<Integer> used = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                final int column = i;
                if (inputCells.stream().anyMatch(cells -> !DmnEntries.isWildcard(cells.get(column)))) {
                    used.add(i);
                }
            }
            if (used.size() != 1) {
                block(table, "decisionTable", used.isEmpty() ? "DMN_NO_CONDITIONAL_RULE" : "DMN_MULTIPLE_INPUTS_NOT_SUPPORTED",
                        used.isEmpty() ? "No rule tests an input (every entry is - or empty), so the table is one fixed result."
                                : "Rules test " + used.size() + " input columns. A canonical row holds one comparison, so a table "
                                + "tests one field; combining inputs needs separate tables or a rewrite.",
                        used.isEmpty() ? "Add a conditional rule." : "Split the table so each tests one input.");
                for (int i : used) {
                    block(inputs.get(i).element(), "input", "DMN_MULTIPLE_INPUTS_NOT_SUPPORTED",
                            "This column is tested together with other input columns.", "Split the table.");
                }
                for (int i = 0; i < inputs.size(); i++) {
                    if (!used.contains(i)) {
                        add(decisionId, inputs.get(i).element(), "input", Report.INFO, "DMN_INPUT_UNUSED",
                                "No rule tests this column, so it is not imported.", "Acknowledge.");
                    }
                }
                for (XElement r : rules) {
                    block(r, "rule", "DMN_MULTIPLE_INPUTS_NOT_SUPPORTED", "Not converted because the table tests more than one input.",
                            "Split the table.");
                }
                for (Column c : outputs) {
                    add(decisionId, c.element(), "output", Report.INFO, "ARTIFACT_NOT_IMPORTED",
                            "Not converted with the table.", "Acknowledge.");
                }
                decisionItem();
                return;
            }
            int usedIndex = used.get(0);
            Column input = inputs.get(usedIndex);
            for (int i = 0; i < inputs.size(); i++) {
                if (i != usedIndex) {
                    add(decisionId, inputs.get(i).element(), "input", Report.INFO, "DMN_INPUT_UNUSED",
                            "No rule tests this column (every entry is - or empty), so it is not imported. The result does not "
                                    + "depend on it.",
                            "Acknowledge.");
                }
            }
            // Input column: field name and type.
            String expression = input.expression() == null ? "" : input.expression().strip();
            String inputKey = PLAIN_IDENTIFIER.matcher(expression).matches() ? Analyzer.fieldKey(expression) : null;
            if (inputKey == null) {
                block(input.element(), "input", "DMN_INPUT_EXPRESSION_NOT_SUPPORTED", "The input expression '" + expression
                        + "' is not a plain variable name, so it cannot become a request field. Computed or property-access "
                        + "expressions are never executed.", "Compute the value in the workflow or before the request.");
            }
            final int column = usedIndex;
            String inputType = columnType(input, inputCells.stream().map(c -> c.get(column)).toList(), "input");
            // Output columns.
            List<DmnTable.Output> tableOutputs = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>();
            for (int i = 0; i < outputs.size(); i++) {
                Column out = outputs.get(i);
                final int outColumn = i;
                String raw = out.name() != null ? out.name() : out.label() != null ? out.label() : outputs.size() == 1 ? "result" : null;
                String key = raw == null ? null : Analyzer.fieldKey(raw);
                if (key == null || !keys.add(key)) {
                    block(out.element(), "output", "DMN_OUTPUT_NAME_INVALID", "The output name '" + raw + "' cannot be used as a "
                            + "unique result field name (letters and digits, starting with a letter).",
                            "Give each output column a simple, unique name.");
                    continue;
                }
                String type = columnType(out, outputCells.stream().map(c -> c.get(outColumn)).toList(), "output");
                if (type == null) {
                    continue;
                }
                tableOutputs.add(new DmnTable.Output(key, type, DmnTable.limit(out.label() != null ? out.label() : key, 100)));
                if (out.allowedValues() != null && !out.allowedValues().isBlank()) {
                    add(decisionId, out.element(), "output", Report.INFO, "DMN_OUTPUT_VALUES_NOT_IMPORTED",
                            "The allowed output values (" + out.allowedValues() + ") are not enforced; every rule sets a literal.",
                            "Acknowledge.");
                }
            }
            if (inputType == null || inputKey == null || tableOutputs.size() != outputs.size()) {
                convertible = false;
            }
            // Allowed input values become the choices of a text field.
            List<String> allowed = null;
            Set<Object> universe = "boolean".equals(inputType) ? new LinkedHashSet<>(List.of(Boolean.TRUE, Boolean.FALSE)) : null;
            if ("string".equals(inputType) && input.allowedValues() != null && !input.allowedValues().isBlank()) {
                try {
                    Region parsed = DmnEntries.parseInput(input.allowedValues(), "string", null);
                    if (!parsed.isComplement() && !parsed.members().isEmpty() && parsed.members().size() <= 50) {
                        allowed = parsed.members().stream().map(String::valueOf).toList();
                        universe = new LinkedHashSet<>(parsed.members());
                    } else {
                        throw new DmnEntries.EntryProblem("DMN_ENTRY_NOT_SUPPORTED", "not a list of up to 50 text values");
                    }
                } catch (DmnEntries.EntryProblem e) {
                    add(decisionId, input.element(), "input", Report.INFO, "DMN_INPUT_VALUES_NOT_IMPORTED",
                            "The allowed input values (" + input.allowedValues() + ") are not enforced as choices: "
                                    + e.getMessage(), "Acknowledge.");
                }
            } else if (input.allowedValues() != null && !input.allowedValues().isBlank()) {
                add(decisionId, input.element(), "input", Report.INFO, "DMN_INPUT_VALUES_NOT_IMPORTED",
                        "The allowed input values (" + input.allowedValues() + ") are not enforced; the request field accepts any "
                                + inputType + " value.", "Acknowledge.");
            }
            // Rules.
            List<DmnTable.Rule> tableRules = new ArrayList<>();
            boolean rulesOk = convertible;
            for (int r = 0; r < rules.size(); r++) {
                XElement rule = rules.get(r);
                List<String> problems = new ArrayList<>();
                Region region = null;
                Map<String, Object> outputValues = new LinkedHashMap<>();
                if (inputType != null) {
                    try {
                        region = DmnEntries.parseInput(inputCells.get(r).get(column), inputType, universe);
                    } catch (DmnEntries.EntryProblem e) {
                        problems.add(e.code() + "|" + e.getMessage());
                    }
                }
                for (int o = 0; o < tableOutputs.size(); o++) {
                    DmnTable.Output out = tableOutputs.get(o);
                    try {
                        outputValues.put(out.key(), DmnEntries.parseOutput(outputCells.get(r).get(o), out.type()));
                    } catch (DmnEntries.EntryProblem e) {
                        problems.add(e.code() + "|" + e.getMessage());
                    }
                }
                if (!problems.isEmpty()) {
                    rulesOk = false;
                    for (String problem : problems) {
                        String[] parts = problem.split("\\|", 2);
                        block(rule, "rule", parts[0], "Rule " + (r + 1) + ": " + parts[1],
                                "Rewrite the entry as a literal test or value, or rebuild the rule as a workflow step.");
                    }
                    continue;
                }
                if (region != null) {
                    tableRules.add(new DmnTable.Rule(idOf(rule), r, inputCells.get(r).get(column), outputCells.get(r), outputValues,
                            region));
                }
            }
            if (!rulesOk || !convertible) {
                convertible = false;
                decisionItem();
                return;
            }
            DmnTable converted = new DmnTable(decisionId, labelOf(decision), hitPolicy, inputKey, inputType,
                    input.label() != null ? input.label() : inputKey, allowed, tableOutputs, tableRules);
            DmnTable.Plan plan = converted.plan();
            if (!plan.convertible()) {
                for (DmnTable.Problem problem : plan.problems()) {
                    XElement where = problem.ruleId() == null ? table : rules.stream().filter(r -> idOf(r).equals(problem.ruleId()))
                            .findFirst().orElse(table);
                    block(where, where == table ? "decisionTable" : "rule", problem.code(), problem.message(),
                            "Rewrite the table, or rebuild this logic in the studio.");
                }
                decisionItem();
                return;
            }
            // Convertible: dispositions and the generated behaviour check.
            List<Report.TestCase> cases = DmnSelfCheck.run(converted, json);
            if (!DmnSelfCheck.allPassed(cases)) {
                analysis.testCases().addAll(cases);
                block(decision, "decision", "DMN_SELF_CHECK_FAILED", cases.stream().filter(c -> !"passed".equals(c.status())).count()
                        + " generated boundary case(s) gave a different answer in the converted table than the source rules. "
                        + "The table is not converted.", "Report this table to the Orqix team; do not use an approximation.");
                decisionItem();
                return;
            }
            analysis.testCases().addAll(cases);
            analysis.tables().put(decisionId, converted);
            emitConverted(converted, plan, input, inputs, usedIndex);
        }

        private boolean hasItemFor(XElement element) {
            return items.stream().anyMatch(o -> o.item().issue().sourceElementId().equals(idOf(element))
                    && !"SETTING_NOT_IMPORTED".equals(o.item().issue().reasonCode()));
        }

        /** Closes a table that cannot be converted: every column and rule still gets a disposition, then the decision. */
        private void decisionItem() {
            if (convertible) {
                return;
            }
            for (Column column : concat(inputs, outputs)) {
                if (!hasItemFor(column.element())) {
                    add(decisionId, column.element(), column.element().name(), Report.INFO, "DMN_COLUMN_NOT_CONVERTED",
                            "Not converted because the table has blockers.", "Acknowledge after resolving the table.");
                }
            }
            for (XElement rule : rules) {
                if (!hasItemFor(rule)) {
                    add(decisionId, rule, "rule", Report.BLOCKER, "DMN_TABLE_NOT_CONVERTIBLE",
                            "Not converted because the table has blockers.", "Resolve the table first.");
                }
            }
            if (!hasItemFor(table)) {
                add(decisionId, table, "decisionTable", Report.BLOCKER, "DMN_TABLE_NOT_CONVERTIBLE",
                        "The table cannot be converted; the reasons are reported on its rules and columns.",
                        "Resolve the blockers of its rules and columns.");
            }
            add(decisionId, decision, "decision", Report.BLOCKER, "DMN_DECISION_NOT_CONVERTIBLE",
                    "The decision table cannot be converted; the reasons are reported on its table, columns and rules.",
                    "Resolve the blockers, or rebuild the table in the studio.");
        }

        private void emitConverted(DmnTable converted, DmnTable.Plan plan, Column input, List<Column> inputs, int usedIndex) {
            add(decisionId, decision, "decision", Report.REVIEW, "DMN_DECISION_CONVERTED",
                    "Becomes a decision table with " + plan.rows().size() + " row(s) on the request field '" + converted.inputKey()
                            + "' and the results " + String.join(", ", converted.outputs().stream().map(DmnTable.Output::key).toList())
                            + ". Rows are made mutually exclusive" + ("FIRST".equals(hitPolicy) ? " (the rule order of hit policy "
                            + "FIRST is folded into exclusive conditions)" : "") + "; values that match no row get the default result"
                            + (plan.defaultUnreachable() ? ", which no value can reach because the rules cover every value" : " of rule "
                            + (ruleNumber(converted, plan.defaultRuleId())))
                            + ". Generated boundary cases were run through the compiler's table evaluation (see testCases).",
                    "Compare the table with the source decision on real samples before relying on it.");
            add(decisionId, table, "decisionTable", Report.REVIEW, "DMN_HIT_POLICY_MAPPED",
                    "Hit policy " + hitPolicy + " is mapped to a UNIQUE canonical table. " + ("UNIQUE".equals(hitPolicy)
                            ? "The rules were proven not to overlap." : "Rules shadowed by earlier rules are dropped (listed on them)."),
                    "Check the rule order and the default result.");
            for (int i = 0; i < inputs.size(); i++) {
                if (i == usedIndex) {
                    add(decisionId, input.element(), "input", Report.REVIEW, "DMN_INPUT_MAPPED", "Becomes the request field '"
                            + converted.inputKey() + "' of type " + converted.inputType() + (input.typeRef() == null
                            ? ", inferred from the rule entries because the column declares no type" : "")
                            + (converted.inputEnum() == null ? "" : " with the choices " + converted.inputEnum())
                            + ". The requester or an earlier step must supply it.",
                            "Check the field name, type and where its value comes from.");
                    analysis.assumptions().add("Decision '" + decisionId + "': input expression '" + input.expression().strip()
                            + "' becomes the " + converted.inputType() + " field '" + converted.inputKey() + "'.");
                }
            }
            for (DmnTable.Output out : converted.outputs()) {
                Column column = outputs.get(converted.outputs().indexOf(out));
                add(decisionId, column.element(), "output", Report.SUPPORTED, "DMN_OUTPUT_MAPPED", "Becomes the result '" + out.key()
                        + "' of type " + out.type() + ".", null);
            }
            Set<String> shadowed = Set.copyOf(plan.shadowedRules());
            for (DmnTable.Rule rule : converted.rules()) {
                XElement element = rules.get(rule.index());
                if (shadowed.contains(rule.id())) {
                    add(decisionId, element, "rule", Report.INFO, "DMN_RULE_NEVER_FIRES",
                            "Rule " + (rule.index() + 1) + " (" + rule.inputText().strip() + ") never fires because earlier rules "
                                    + "already accept every value it accepts. It is not converted.",
                            "Acknowledge.");
                    continue;
                }
                List<String> rows = plan.rows().stream().filter(r -> r.ruleId().equals(rule.id()))
                        .map(r -> converted.inputKey() + " " + r.op().symbol() + " " + shown(r.value())).toList();
                boolean isDefault = rule.id().equals(plan.defaultRuleId());
                String result = converted.outputs().stream().map(o -> o.key() + " = " + shown(rule.outputs().get(o.key())))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                add(decisionId, element, "rule", Report.REVIEW, "DMN_RULE_CONVERTED",
                        "Rule " + (rule.index() + 1) + ": input '" + (DmnEntries.isWildcard(rule.inputText()) ? "-" : rule.inputText().strip())
                                + "' gives " + result + ". Becomes "
                                + (isDefault ? "the default result" + (rows.isEmpty() ? "" : " and the rows " + rows)
                                : rows.size() == 1 ? "the row " + rows.get(0) : "the rows " + rows) + ".",
                        "Check this rule at and around its thresholds.");
            }
        }

        private String ruleNumber(DmnTable converted, String ruleId) {
            return converted.rules().stream().filter(r -> r.id().equals(ruleId)).findFirst().map(r -> String.valueOf(r.index() + 1))
                    .orElse("?");
        }

        /** Text of a column type: the declared typeRef mapped to a canonical type, or one inferred from the entries. */
        private String columnType(Column column, List<String> entries, String kind) {
            String declared = column.typeRef();
            String type;
            if (declared != null) {
                String simple = declared.contains(":") ? declared.substring(declared.indexOf(':') + 1) : declared;
                type = switch (simple.toLowerCase(Locale.ROOT)) {
                    case "string" -> "string";
                    case "boolean" -> "boolean";
                    case "integer", "long" -> "integer";
                    case "double", "number" -> "number";
                    default -> null;
                };
                if (type == null) {
                    block(column.element(), kind, "DMN_TYPE_NOT_SUPPORTED", "The type '" + declared + "' is not supported. Use "
                            + "string, boolean, integer, long, double or number.", "Change the column type in the source model.");
                }
                return type;
            }
            Set<String> kinds = new LinkedHashSet<>();
            for (String entry : entries) {
                String entryKind = DmnEntries.inferKind(entry);
                if (entryKind != null) {
                    kinds.add(entryKind);
                } else if (!DmnEntries.isWildcard(entry)) {
                    kinds.add("?");
                }
            }
            if (kinds.size() != 1 || kinds.contains("?")) {
                block(column.element(), kind, "DMN_TYPE_NOT_SUPPORTED", "The column declares no type and its entries do not agree "
                        + "on one literal type.", "Declare the column type (typeRef) in the source model.");
                return null;
            }
            return kinds.iterator().next();
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static String shown(Object value) {
        return value instanceof java.math.BigDecimal n ? n.toPlainString() : value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }

    /** Camunda extension attributes (historyTimeToLive, versionTag, inputVariable) are engine settings, not imported. */
    private void settings(String decisionId, XElement element, String kind) {
        List<String> names = new ArrayList<>();
        for (String key : element.attributes().keySet()) {
            if (key.startsWith("{" + CAMUNDA_DMN + "}") || key.startsWith("{" + Namespaces.CAMUNDA7 + "}")) {
                names.add("camunda:" + key.substring(key.indexOf('}') + 1));
            }
        }
        if (!names.isEmpty()) {
            add(decisionId, element, kind, Report.INFO, "SETTING_NOT_IMPORTED",
                    "Engine settings (" + String.join(", ", names) + ") are not imported.", "Acknowledge.");
        }
    }

    private void extensions(String decisionId, XElement extensionElements) {
        for (XElement child : extensionElements.children()) {
            add(decisionId, child, child.name(), Report.INFO, "EXTENSION_NOT_IMPORTED",
                    "The extension element " + child.label() + " is not imported.", "Acknowledge.");
        }
    }

    private void unknown(String decisionId, XElement element) {
        add(decisionId, element, element.name(), Report.BLOCKER, "UNKNOWN_ELEMENT",
                "The element " + element.label() + " is not known to the importer, so it cannot be converted safely.",
                "Remove it from the source model or rebuild what it does in the studio.");
    }

    private void add(String decisionId, XElement el, String kind, String severity, String code, String explanation, String suggestion) {
        String resolution = Report.SUPPORTED.equals(severity) ? Report.NOT_NEEDED : Report.OPEN;
        Report.Issue issue = new Report.Issue(idOf(el), blankToNull(el.attr("name")) == null ? blankToNull(el.attr("label"))
                : el.attr("name"), kind, severity, code, explanation, suggestion, null, resolution, pathOf(el));
        items.add(new Ordered(el.ordinal(), sequence++, new DmnAnalysis.Item(issue, decisionId)));
    }

    private static String idOf(XElement e) {
        String id = e.attr("id");
        if (id != null && !id.isBlank()) {
            return id;
        }
        XElement parent = e.parent();
        return (parent == null ? "" : idOf(parent) + "/") + e.name() + "#" + e.ordinal();
    }

    private static String labelOf(XElement e) {
        String name = blankToNull(e.attr("name"));
        return name != null ? name : e.attr("id") != null ? e.attr("id") : e.name();
    }

    private static String pathOf(XElement el) {
        Deque<String> parts = new java.util.ArrayDeque<>();
        for (XElement e = el; e != null; e = e.parent()) {
            String id = e.attr("id");
            String qualified = DMN_NAMESPACE.matcher(e.ns()).matches() ? e.name() : Namespaces.prefixed(e.ns(), e.name());
            parts.push(qualified + (id == null ? "" : "[" + id + "]"));
        }
        return String.join("/", parts);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
