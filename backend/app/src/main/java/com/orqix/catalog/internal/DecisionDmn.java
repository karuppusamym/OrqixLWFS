package com.orqix.catalog.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads one decision table of a standard DMN 1.1 to 1.5 file into an Orqix decision table (docs/build/S121). Pure and
 * thread-safe; the XML is parsed by {@link DmnXml} (no DTDs, entities or external resources, bounded size).
 * <p>
 * The reader is strict: what it cannot represent exactly is a problem with its place in the file, never approximated.
 * It takes the hit policies UNIQUE, FIRST, ANY, PRIORITY (the priority order of one output's {@code outputValues}) and
 * COLLECT with SUM, MIN, MAX or COUNT; FEEL unary tests that are literals, comparisons with a literal ({@code < 5}),
 * ranges ({@code [1..5)}), lists ({@code "a","b"}) and {@code not(...)} of one literal or comparison; literal outputs;
 * input expressions that name a field ({@code amount}, {@code input.amount}, {@code steps.check.score}); and the
 * {@code submissionDate} date column that {@link com.orqix.compiler.DecisionTables#exportDmn} writes for effective
 * dates. The default is each output's {@code defaultOutputEntry} or, under FIRST, a last rule that tests nothing.
 * Orqix's own export reads back to the same meaning.
 */
public final class DecisionDmn {

    /** Largest accepted file. */
    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_ROWS = 50;
    public static final int MAX_INPUTS = 30;
    public static final int MAX_OUTPUTS = 10;
    public static final int MAX_PROBLEMS = 50;
    private static final Pattern DMN_NAMESPACE = Pattern.compile("^https?://www\\.omg\\.org/spec/DMN/(\\d{8})/(?:MODEL/?|dmn\\.xsd)$");
    private static final Pattern FIELD_KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Pattern FIELD_REF = Pattern.compile(
            "^/(input/[a-z][A-Za-z0-9]{0,63}(/count|/sum/[a-z][A-Za-z0-9]{0,63})?|steps/(?!.*__)[A-Za-z][A-Za-z0-9_-]{0,63}/[a-z][A-Za-z0-9]{0,63})$");
    private static final Pattern NUMBER = Pattern.compile("^-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,17})?$");
    private static final Pattern STRING = Pattern.compile("^\"([^\"\\\\]*)\"$");
    private static final Pattern SAFE_TEXT = Pattern.compile("^[A-Za-z0-9 _.,:/-]{0,100}$");
    private static final Pattern COMPARISON = Pattern.compile("^(<=|>=|<|>|==|!=)\\s*(.+)$");
    private static final Pattern RANGE = Pattern.compile("^([\\[(\\]])\\s*(.+?)\\s*\\.\\.\\s*(.+?)\\s*([\\])\\[])$");
    private static final Pattern DATE = Pattern.compile("^date\\(\\s*\"(\\d{4}-\\d{2}-\\d{2})\"\\s*\\)$");
    private static final Set<String> NUMBER_TYPES = Set.of("number", "integer", "long", "double", "decimal", "int");
    /** Input expression of the effective-window column (docs/build/S41, S121). */
    public static final String SUBMISSION_DATE = "submissionDate";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private DecisionDmn() {
    }

    /** One problem: where in the file (decision, rule, entry) and what is wrong. */
    public record Problem(String where, String message) {
    }

    /** A decision of the file. {@code table} is false when it is not a decision table. */
    public record Choice(String id, String name, boolean table) {
    }

    /**
     * @param decisions every decision of the file
     * @param decisionId the decision read, or null when the file has several and none was chosen
     * @param table hitPolicy, optional aggregation and priority, rows and default; null when no decision was chosen
     * @param notes parts of the file that carry no meaning for Orqix and were left out (annotations, labels, layout)
     */
    public record Result(List<Choice> decisions, String decisionId, String name, ObjectNode table, List<String> notes) {
    }

    /** The file cannot be read; {@code decisions} lists the decisions when the file itself is well formed. */
    public static final class Invalid extends RuntimeException {
        private final List<Problem> problems;
        private final transient List<Choice> decisions;

        Invalid(List<Problem> problems, List<Choice> decisions) {
            super(problems.isEmpty() ? "The DMN file cannot be read." : problems.get(0).message());
            this.problems = List.copyOf(problems);
            this.decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }

        public List<Problem> problems() {
            return problems;
        }

        public List<Choice> decisions() {
            return decisions;
        }
    }

    /**
     * @param decisionId the decision to read; may be null when the file has exactly one decision table
     * @throws Invalid with every problem found (at most {@link #MAX_PROBLEMS})
     */
    public static Result read(String xml, String decisionId) {
        DmnXml.Element root;
        try {
            root = DmnXml.parse(xml, MAX_BYTES, 10_000, 32);
        } catch (DmnXml.Unreadable e) {
            throw new Invalid(List.of(new Problem("file", e.getMessage())), null);
        }
        Matcher ns = DMN_NAMESPACE.matcher(root.ns());
        if (!"definitions".equals(root.name()) || !ns.matches()) {
            throw new Invalid(List.of(new Problem("file", "This is not a DMN file. The root element must be definitions in"
                    + " a DMN namespace (https://www.omg.org/spec/DMN/...).")), null);
        }
        List<Choice> choices = new ArrayList<>();
        Map<String, DmnXml.Element> byId = new LinkedHashMap<>();
        for (DmnXml.Element d : root.children(root.ns(), "decision")) {
            String id = blankToNull(d.attr("id"));
            if (id == null) {
                continue;
            }
            byId.putIfAbsent(id, d);
            choices.add(new Choice(id, name(d, id), d.child(root.ns(), "decisionTable") != null));
        }
        if (choices.isEmpty()) {
            throw new Invalid(List.of(new Problem("file", "The file has no decision.")), choices);
        }
        String chosen = decisionId;
        if (chosen == null || chosen.isBlank()) {
            List<Choice> tables = choices.stream().filter(Choice::table).toList();
            if (choices.size() == 1 || tables.size() == 1 && choices.size() == tables.size()) {
                chosen = (tables.isEmpty() ? choices : tables).get(0).id();
            } else {
                return new Result(choices, null, null, null, List.of());
            }
        }
        DmnXml.Element decision = byId.get(chosen);
        if (decision == null) {
            throw new Invalid(List.of(new Problem("file", "The file has no decision with the id \"" + shorten(chosen) + "\".")),
                    choices);
        }
        Reader reader = new Reader(root.ns(), "decision " + chosen);
        ObjectNode table = reader.decision(decision);
        if (!reader.problems.isEmpty()) {
            throw new Invalid(reader.problems, choices);
        }
        return new Result(choices, chosen, name(decision, chosen), table, List.copyOf(reader.notes));
    }

    // ----------------------------------------------------------------------------------------------------------------

    /** A column of the table. {@code fieldRef} is null for the submission date column. */
    private record Column(String fieldRef, String type, boolean date) {
    }

    private static final class Reader {
        final String ns;
        final String where;
        final List<Problem> problems = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        Reader(String ns, String where) {
            this.ns = ns;
            this.where = where;
        }

        void problem(String place, String message) {
            if (problems.size() < MAX_PROBLEMS) {
                problems.add(new Problem(where + (place == null ? "" : ", " + place), message));
            }
        }

        void note(String text) {
            if (!notes.contains(text)) {
                notes.add(text);
            }
        }

        ObjectNode decision(DmnXml.Element decision) {
            DmnXml.Element table = null;
            for (DmnXml.Element child : decision.children()) {
                if (!ns.equals(child.ns())) {
                    note("Extension elements are not imported.");
                    continue;
                }
                switch (child.name()) {
                    case "decisionTable" -> table = child;
                    case "informationRequirement" -> {
                        if (child.child(ns, "requiredDecision") != null) {
                            problem(null, "This decision uses the result of another decision. Chained decisions are"
                                    + " not imported; import each decision and chain them with workflow steps.");
                        }
                    }
                    case "knowledgeRequirement" -> problem(null, "This decision calls a business knowledge model, which"
                            + " is not imported. Write its logic as rules of the table.");
                    case "description", "documentation", "question", "allowedAnswers", "variable", "authorityRequirement",
                            "extensionElements" -> note("Descriptions, questions and requirement documentation are not imported.");
                    default -> problem(null, "A decision with a " + child.name() + " is not imported; only decision tables are.");
                }
            }
            if (table == null) {
                problem(null, "The decision has no decision table.");
                return null;
            }
            return table(table);
        }

        ObjectNode table(DmnXml.Element t) {
            ObjectNode out = F.objectNode();
            String policy = t.attr("hitPolicy") == null ? "UNIQUE" : t.attr("hitPolicy").strip().toUpperCase(Locale.ROOT);
            String aggregation = t.attr("aggregation") == null ? null : t.attr("aggregation").strip().toUpperCase(Locale.ROOT);
            switch (policy) {
                case "UNIQUE", "FIRST", "ANY", "PRIORITY" -> {
                    if (aggregation != null) problem("decisionTable", "Only COLLECT tables have an aggregation.");
                }
                case "COLLECT" -> {
                    if (aggregation == null) {
                        problem("decisionTable", "A COLLECT table without an aggregation returns a list of results, which"
                                + " Orqix does not hold. Add aggregation SUM, MIN, MAX or COUNT.");
                    } else if (!Set.of("SUM", "MIN", "MAX", "COUNT").contains(aggregation)) {
                        problem("decisionTable", "The aggregation must be SUM, MIN, MAX or COUNT.");
                    }
                }
                default -> problem("decisionTable", "The hit policy " + shorten(policy) + " returns results in an order;"
                        + " Orqix holds UNIQUE, FIRST, ANY, PRIORITY and COLLECT.");
            }
            out.put("hitPolicy", policy);
            if ("COLLECT".equals(policy) && aggregation != null) out.put("aggregation", aggregation);

            List<Column> inputs = new ArrayList<>();
            List<String> outputNames = new ArrayList<>();
            List<String> outputTypes = new ArrayList<>();
            List<String> defaults = new ArrayList<>();
            List<List<String>> outputValues = new ArrayList<>();
            List<DmnXml.Element> rules = new ArrayList<>();
            for (DmnXml.Element child : t.children()) {
                if (!ns.equals(child.ns())) {
                    note("Extension elements are not imported.");
                    continue;
                }
                switch (child.name()) {
                    case "input" -> inputs.add(input(child, inputs.size() + 1));
                    case "output" -> {
                        int n = outputNames.size() + 1;
                        String name = blankToNull(child.attr("name"));
                        if (name == null || !FIELD_KEY.matcher(name).matches()) {
                            problem("output " + n, "Each output needs a name that starts with a lowercase letter and has"
                                    + " letters and digits only (it becomes the result field).");
                        }
                        outputNames.add(name);
                        outputTypes.add(type(child.attr("typeRef"), "output " + n, false));
                        DmnXml.Element def = child.child(ns, "defaultOutputEntry");
                        defaults.add(def == null ? null : text(def));
                        DmnXml.Element values = child.child(ns, "outputValues");
                        outputValues.add(values == null ? null : split(text(values), "output " + n));
                    }
                    case "rule" -> rules.add(child);
                    case "annotation" -> note("Annotation columns are not imported.");
                    case "description", "extensionElements" -> note("Descriptions are not imported.");
                    default -> problem("decisionTable", "The element " + child.name() + " is not part of a decision table Orqix reads.");
                }
            }
            if (inputs.size() > MAX_INPUTS) problem("decisionTable", "At most " + MAX_INPUTS + " input columns are allowed.");
            if (outputNames.isEmpty() || outputNames.size() > MAX_OUTPUTS)
                problem("decisionTable", "The table must have 1 to " + MAX_OUTPUTS + " output columns.");
            if (inputs.stream().filter(Column::date).count() > 1)
                problem("decisionTable", "Only one submissionDate column is allowed.");
            if (inputs.stream().filter(c -> !c.date() && c.fieldRef() != null).map(Column::fieldRef).distinct().count()
                    != inputs.stream().filter(c -> !c.date() && c.fieldRef() != null).count())
                problem("decisionTable", "Two input columns read the same field; use one column per field.");
            if (!problems.isEmpty()) return out;

            // Priority: the one output with an ordered list of values.
            if ("PRIORITY".equals(policy)) {
                List<Integer> ranked = new ArrayList<>();
                for (int i = 0; i < outputValues.size(); i++) if (outputValues.get(i) != null) ranked.add(i);
                if (ranked.size() != 1) {
                    problem("decisionTable", "A PRIORITY table needs outputValues (the values from the highest to the"
                            + " lowest priority) on exactly one output.");
                } else {
                    int i = ranked.get(0);
                    ArrayNode order = F.arrayNode();
                    for (String v : outputValues.get(i)) {
                        JsonNode literal = literal(v, outputTypes.get(i), "output " + (i + 1) + " outputValues");
                        if (literal != null) order.add(literal);
                    }
                    ObjectNode priority = out.putObject("priority");
                    priority.put("output", outputNames.get(i));
                    priority.set("order", order);
                }
            } else if (outputValues.stream().anyMatch(v -> v != null)) {
                note("Allowed output values are not imported; they only matter for PRIORITY tables.");
            }

            ArrayNode rows = F.arrayNode();
            ObjectNode defaultRow = null;
            for (int r = 0; r < rules.size(); r++) {
                DmnXml.Element rule = rules.get(r);
                String place = "rule " + (r + 1);
                List<DmnXml.Element> ins = rule.children(ns, "inputEntry");
                List<DmnXml.Element> outs = rule.children(ns, "outputEntry");
                if (!rule.children(ns, "annotationEntry").isEmpty()) note("Rule annotations are not imported.");
                if (ins.size() != inputs.size() || outs.size() != outputNames.size()) {
                    problem(place, "The rule has " + ins.size() + " input and " + outs.size() + " output entries, but the"
                            + " table has " + inputs.size() + " input and " + outputNames.size() + " output columns.");
                    continue;
                }
                ObjectNode then = F.objectNode();
                for (int o = 0; o < outs.size(); o++) {
                    JsonNode value = literal(text(outs.get(o)), outputTypes.get(o), place + ", output " + (o + 1));
                    if (value != null && outputNames.get(o) != null) then.set(outputNames.get(o), value);
                }
                List<ObjectNode> cells = new ArrayList<>();
                List<List<ObjectNode>> lists = new ArrayList<>();
                ObjectNode window = null;
                boolean bad = false;
                for (int i = 0; i < ins.size(); i++) {
                    Column column = inputs.get(i);
                    String entry = text(ins.get(i)).strip();
                    if (entry.isEmpty() || entry.equals("-")) continue;
                    String cellPlace = place + ", input " + (i + 1);
                    if (column.date()) {
                        window = window(entry, cellPlace);
                        bad |= window == null;
                        continue;
                    }
                    List<ObjectNode> alternatives = cell(entry, column, cellPlace);
                    if (alternatives == null) {
                        bad = true;
                    } else if (alternatives.size() == 1) {
                        cells.add(alternatives.get(0));
                    } else {
                        lists.add(alternatives);
                    }
                }
                if (bad) continue;
                ObjectNode when;
                if (cells.isEmpty() && lists.isEmpty()) {
                    if (r == rules.size() - 1 && window == null && "FIRST".equals(policy)) {
                        defaultRow = then;
                        continue;
                    }
                    problem(place, "The rule tests nothing, so it always applies. Orqix rows need a condition; give the"
                            + " outputs a defaultOutputEntry instead" + ("FIRST".equals(policy) ? " or make it the last rule." : "."));
                    continue;
                } else if (lists.isEmpty()) {
                    when = cells.size() == 1 ? cells.get(0) : group("all", cells);
                } else if (lists.size() == 1 && cells.isEmpty()) {
                    if (lists.get(0).size() > 8) {
                        problem(place, "A list of comparisons in one entry may have at most 8 parts.");
                        continue;
                    }
                    when = group("any", lists.get(0));
                } else {
                    problem(place, "The rule combines a list of comparisons in one entry with other entries. Orqix rows"
                            + " cannot nest an OR inside an AND; split the rule into one rule per comparison.");
                    continue;
                }
                if (when.has("all") && when.path("all").size() > 8) {
                    problem(place, "A rule may test at most 8 fields.");
                    continue;
                }
                ObjectNode row = F.objectNode();
                row.set("when", when);
                row.set("then", then);
                if (window != null) row.set("effective", window);
                rows.add(row);
            }
            if (rows.size() > MAX_ROWS) problem("decisionTable", "At most " + MAX_ROWS + " rules are allowed.");
            if (rows.isEmpty() && problems.isEmpty()) problem("decisionTable", "The table has no rule with a condition.");

            ObjectNode def = F.objectNode();
            if (defaultRow != null) {
                def = defaultRow;
                if (defaults.stream().anyMatch(d -> d != null)) {
                    note("The last rule always applies, so it is the default; the defaultOutputEntry values are not used.");
                }
            } else if (defaults.stream().allMatch(d -> d != null && !d.isBlank())) {
                for (int o = 0; o < outputNames.size(); o++) {
                    JsonNode value = literal(defaults.get(o), outputTypes.get(o), "output " + (o + 1) + " defaultOutputEntry");
                    if (value != null && outputNames.get(o) != null) def.set(outputNames.get(o), value);
                }
            } else {
                problem("decisionTable", "Orqix needs a result for when no rule applies. Give every output a"
                        + " defaultOutputEntry" + ("FIRST".equals(policy) ? ", or end a FIRST table with a rule that tests nothing." : "."));
            }
            out.set("rows", rows);
            out.set("default", def);
            return out;
        }

        Column input(DmnXml.Element input, int n) {
            String place = "input " + n;
            DmnXml.Element expression = input.child(ns, "inputExpression");
            DmnXml.Element text = expression == null ? null : expression.child(ns, "text");
            if (input.child(ns, "inputValues") != null) note("Allowed input values are not imported; use the choices of the request form.");
            if (text == null || text.text().isBlank()) {
                problem(place, "The input column has no input expression.");
                return new Column(null, "string", false);
            }
            String name = text.text().strip();
            String typeRef = expression.attr("typeRef") == null ? "" : expression.attr("typeRef").strip();
            if (name.equals(SUBMISSION_DATE)) {
                if (!typeRef.equalsIgnoreCase("date")) problem(place, "The submissionDate column must have typeRef date.");
                return new Column(null, "date", true);
            }
            String fieldRef = fieldRef(name);
            if (fieldRef == null) {
                problem(place, "The input expression \"" + shorten(name) + "\" is not a field name. Use a request field such"
                        + " as amount, or a step result such as steps.check.score; expressions are not imported.");
            }
            return new Column(fieldRef, type(typeRef, place, true), false);
        }

        /** {@code string}, {@code number} or {@code boolean}. */
        String type(String typeRef, String place, boolean input) {
            String t = typeRef == null ? "" : typeRef.strip().toLowerCase(Locale.ROOT);
            if (t.startsWith("feel:")) t = t.substring(5);
            if (t.equals("string")) return "string";
            if (t.equals("boolean")) return "boolean";
            if (NUMBER_TYPES.contains(t)) return "number";
            problem(place, t.isEmpty() ? "The " + (input ? "input" : "output") + " column needs a typeRef: string, number or boolean."
                    : "The type " + shorten(typeRef) + " is not supported here; use string, number or boolean.");
            return "string";
        }

        /** One input entry as its alternatives (one or more atomic conditions), or null after a problem. */
        List<ObjectNode> cell(String entry, Column column, String place) {
            String text = entry;
            boolean negated = false;
            if (text.startsWith("not(") && text.endsWith(")")) {
                negated = true;
                text = text.substring(4, text.length() - 1).strip();
            }
            List<String> parts = split(text, place);
            if (parts == null) return null;
            List<ObjectNode> atoms = new ArrayList<>();
            for (String part : parts) {
                ObjectNode atom = atom(part, column, place);
                if (atom == null) return null;
                atoms.add(atom);
            }
            if (negated) {
                if (atoms.size() != 1) {
                    problem(place, "not(...) of several values is not imported; Orqix negates one value or comparison.");
                    return null;
                }
                ObjectNode inverted = invert(atoms.get(0));
                if (inverted == null) {
                    problem(place, "not(...) of a range or list is not imported; Orqix negates one value or comparison.");
                    return null;
                }
                return List.of(inverted);
            }
            // Several equalities of one field are one list.
            if (atoms.size() > 1 && atoms.stream().allMatch(a -> "eq".equals(a.path("operator").asString()))
                    && !"boolean".equals(column.type())) {
                if (atoms.size() > 20) {
                    problem(place, "A list may hold at most 20 values.");
                    return null;
                }
                ObjectNode in = F.objectNode();
                in.put("fieldRef", column.fieldRef());
                in.put("operator", "in");
                ArrayNode values = in.putArray("value");
                Set<String> seen = new java.util.HashSet<>();
                for (ObjectNode a : atoms) {
                    JsonNode v = a.get("value");
                    if (seen.add(v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asString())) {
                        values.add(v);
                    }
                }
                return List.of(in);
            }
            return atoms;
        }

        ObjectNode atom(String part, Column column, String place) {
            String p = part.strip();
            ObjectNode atom = F.objectNode();
            atom.put("fieldRef", column.fieldRef());
            Matcher range = RANGE.matcher(p);
            if (range.matches()) {
                if (!"number".equals(column.type())) {
                    problem(place, "A range compares numbers, but the column is " + column.type() + ".");
                    return null;
                }
                BigDecimal from = number(range.group(2)), to = number(range.group(3));
                if (from == null || to == null) {
                    problem(place, "The range \"" + shorten(p) + "\" must have number bounds.");
                    return null;
                }
                if (from.compareTo(to) >= 0) {
                    problem(place, "The range \"" + shorten(p) + "\" must start below its end.");
                    return null;
                }
                atom.put("operator", "between");
                ObjectNode value = atom.putObject("value");
                value.put("from", from);
                value.put("to", to);
                if (!range.group(1).equals("[")) value.put("includeFrom", false);
                if (!range.group(4).equals("]")) value.put("includeTo", false);
                return atom;
            }
            Matcher comparison = COMPARISON.matcher(p);
            String operator = "eq";
            String operand = p;
            if (comparison.matches()) {
                operator = switch (comparison.group(1)) {
                    case "<" -> "lt";
                    case "<=" -> "lte";
                    case ">" -> "gt";
                    case ">=" -> "gte";
                    case "!=" -> "ne";
                    default -> "eq";
                };
                operand = comparison.group(2).strip();
            }
            JsonNode value = literal(operand, column.type(), place);
            if (value == null) return null;
            if (!Set.of("eq", "ne").contains(operator) && !"number".equals(column.type())) {
                problem(place, "The comparison \"" + shorten(p) + "\" orders values, which works on numbers only.");
                return null;
            }
            atom.put("operator", operator);
            atom.set("value", value);
            return atom;
        }

        /** A literal of the column type, or null after a problem. */
        JsonNode literal(String text, String type, String place) {
            String t = text == null ? "" : text.strip();
            switch (type) {
                case "number" -> {
                    BigDecimal n = number(t);
                    if (n == null) {
                        problem(place, "\"" + shorten(t) + "\" is not a number literal. Expressions, functions and other"
                                + " variables are not imported.");
                        return null;
                    }
                    return F.numberNode(n);
                }
                case "boolean" -> {
                    if (t.equals("true") || t.equals("false")) return F.booleanNode(Boolean.parseBoolean(t));
                    problem(place, "\"" + shorten(t) + "\" is not true or false.");
                    return null;
                }
                default -> {
                    Matcher m = STRING.matcher(t);
                    if (!m.matches()) {
                        problem(place, "\"" + shorten(t) + "\" is not a text literal in double quotes. Expressions, functions"
                                + " and other variables are not imported.");
                        return null;
                    }
                    if (!SAFE_TEXT.matcher(m.group(1)).matches()) {
                        problem(place, "The text \"" + shorten(m.group(1)) + "\" may have at most 100 letters, digits, spaces"
                                + " and the characters . , : / - _");
                        return null;
                    }
                    return F.stringNode(m.group(1));
                }
            }
        }

        /** The effective window of the submissionDate column. */
        ObjectNode window(String entry, String place) {
            ObjectNode w = F.objectNode();
            Matcher range = RANGE.matcher(entry);
            if (range.matches() && range.group(1).equals("[") && range.group(4).equals("]")) {
                String from = date(range.group(2)), to = date(range.group(3));
                if (from != null && to != null && from.compareTo(to) <= 0) {
                    w.put("from", from);
                    w.put("to", to);
                    return w;
                }
            }
            Matcher comparison = COMPARISON.matcher(entry);
            if (comparison.matches() && Set.of(">=", "<=").contains(comparison.group(1))) {
                String day = date(comparison.group(2).strip());
                if (day != null) {
                    w.put(comparison.group(1).equals(">=") ? "from" : "to", day);
                    return w;
                }
            }
            problem(place, "The submissionDate entry must be [date(\"YYYY-MM-DD\")..date(\"YYYY-MM-DD\")], >= date(...) or"
                    + " <= date(...): the days a rule applies, both included.");
            return null;
        }

        /** Splits FEEL unary tests at top-level commas (not inside quotes, brackets or parentheses). */
        List<String> split(String text, String place) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"') quoted = !quoted;
                if (!quoted && (c == '(' || c == '[')) depth++;
                if (!quoted && (c == ')' || (c == ']' && depth > 0))) depth--;
                if (c == ',' && !quoted && depth == 0) {
                    parts.add(current.toString().strip());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            parts.add(current.toString().strip());
            if (quoted || parts.stream().anyMatch(String::isEmpty)) {
                problem(place, "The entry \"" + shorten(text) + "\" is not a list of literals and comparisons.");
                return null;
            }
            return parts;
        }
    }

    private static ObjectNode group(String kind, List<ObjectNode> terms) {
        ObjectNode g = F.objectNode();
        ArrayNode list = g.putArray(kind);
        terms.forEach(list::add);
        return g;
    }

    private static ObjectNode invert(ObjectNode atom) {
        String inverted = switch (atom.path("operator").asString()) {
            case "eq" -> "ne";
            case "ne" -> "eq";
            case "lt" -> "gte";
            case "gte" -> "lt";
            case "gt" -> "lte";
            case "lte" -> "gt";
            default -> null;
        };
        if (inverted == null) return null;
        ObjectNode copy = atom.deepCopy();
        copy.put("operator", inverted);
        return copy;
    }

    /** {@code amount} and {@code input.amount} are request fields; other dotted names are paths. */
    static String fieldRef(String name) {
        String ref = name.contains(".") ? "/" + name.replace('.', '/') : "/input/" + name;
        return FIELD_REF.matcher(ref).matches() ? ref : null;
    }

    private static BigDecimal number(String text) {
        String t = text == null ? "" : text.strip();
        return NUMBER.matcher(t).matches() ? new BigDecimal(t) : null;
    }

    private static String date(String text) {
        Matcher m = DATE.matcher(text.strip());
        if (!m.matches()) return null;
        try {
            return java.time.LocalDate.parse(m.group(1)).toString();
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static String text(DmnXml.Element e) {
        DmnXml.Element inner = e.children().stream().filter(c -> "text".equals(c.name())).findFirst().orElse(null);
        return inner == null ? "" : inner.text();
    }

    private static String name(DmnXml.Element decision, String id) {
        String name = blankToNull(decision.attr("name"));
        return name == null ? id : name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String shorten(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ");
        return t.length() > 60 ? t.substring(0, 57) + "..." : t;
    }
}
