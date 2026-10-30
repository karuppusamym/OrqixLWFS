package com.orqix.compiler.internal.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.Enums.Route;

/**
 * Typed canonical workflow definition (pipeline stage 4). Built only from documents that passed the JSON Schema, so
 * structural presence is guaranteed. Every map is sorted by key (UTF-16 order, as in RFC 8785) so that generated
 * artifacts depend only on the canonical form, never on member order in the source text.
 *
 * @param inputUi  may be null
 * @param uiLayout may be null
 */
public record WorkflowDefinition(
        String schemaVersion,
        String workflowKey,
        String name,
        FormSchema inputSchema,
        InputUi inputUi,
        FormSchema outputSchema,
        List<Node> nodes,
        List<Transition> transitions,
        List<DecisionTable> decisions,
        Execution execution,
        String selfApproval,
        List<Dependency> dependencies,
        Map<String, OutputEntry> outputMapping,
        Layout uiLayout,
        List<Stage> stages,
        List<Subprocess> subprocesses) {

    /**
     * A scope over existing steps (S59). An embedded subprocess is a section entered through one entry step and left
     * by the ordinary paths of its members, with an optional time limit ({@code timeoutAfter} to {@code onTimeout}) and
     * error route ({@code errorTo}, catching {@code errorCode}, or any code when null). An event subprocess is a handler
     * started by {@code triggerType} (error, signal or timer) and is {@code interrupting} or not.
     */
    public record Subprocess(int index, String id, String label, boolean event, List<String> nodes,
                             String timeoutAfter, String onTimeout, String errorTo, String errorCode,
                             String triggerType, String triggerErrorCode, String signal, String after,
                             boolean interrupting) {
        public String path() {
            return "/subprocesses/" + index;
        }

        public boolean embedded() {
            return !event;
        }
    }

    /**
     * A named group of steps used to show progress and monitor a time limit (S17). It adds no behavior to the
     * generated process. {@code sla} may be null.
     */
    public record Stage(int index, String id, String label, List<String> nodes, String sla) {
        public String path() {
            return "/stages/" + index;
        }
    }

    /** @param label may be null; @param condition non-null only for {@code on: condition} */
    public record Transition(int index, String id, String from, String to, Route on, String label,
                             Condition condition) {
        public String path() {
            return "/transitions/" + index;
        }
    }

    /**
     * A structured comparison; {@code operator} is never null after schema validation. {@code value} is the literal of
     * a scalar comparison and null for the decision-only set operators: {@code list} holds the values of {@code in}
     * and {@code range} the bounds of {@code between} (docs/build/S121).
     */
    public record Condition(String fieldRef, Operator operator, Literal value, String group, List<Condition> terms,
                            List<Literal> list, Range range) {
        public Condition(String fieldRef, Operator operator, Literal value) { this(fieldRef, operator, value, null, List.of(), List.of(), null); }
        public Condition(String fieldRef, Operator operator, Literal value, String group, List<Condition> terms) { this(fieldRef, operator, value, group, terms, List.of(), null); }
        public boolean compound() { return group != null; }
        /** A group whose terms are alternatives (OR). Conjunctions are {@code group == "all"}. */
        public boolean isAny() { return "any".equals(group); }

        /** @return true when the condition, or one of its terms, uses {@code in} or {@code between} */
        public boolean usesSet() {
            return compound() ? terms.stream().anyMatch(Condition::usesSet) : operator != null && operator.set();
        }

        /** @return every literal the comparison holds: the value, the list members or the two range bounds */
        public List<Literal> literals() {
            if (operator == Operator.IN) return list;
            if (operator == Operator.BETWEEN) return List.of(range.from(), range.to());
            return value == null ? List.of() : List.of(value);
        }

        /**
         * @return the right-hand side as {@link com.orqix.compiler.ConditionMatcher} takes it: a BigDecimal, String or
         *         Boolean, a list of them for {@code in}, or a {@link com.orqix.compiler.ConditionMatcher.Range}
         */
        public Object operand() {
            if (operator == Operator.IN) return list.stream().map(Literal::value).toList();
            if (operator == Operator.BETWEEN) return new com.orqix.compiler.ConditionMatcher.Range(range.from().number(),
                    range.includeFrom(), range.to().number(), range.includeTo());
            return value.value();
        }
    }

    /** Number range of {@code between}; both bounds are present and {@code from < to}. */
    public record Range(Literal.Num from, boolean includeFrom, Literal.Num to, boolean includeTo) {
    }

    /**
     * @param defaultOutput sorted by key
     * @param aggregation   SUM, MIN, MAX or COUNT for COLLECT, otherwise null
     * @param priority      the output and its value order for PRIORITY, otherwise null
     */
    public record DecisionTable(int index, String key, int version, String hitPolicy, List<Row> rows,
                                Map<String, Literal> defaultOutput, String aggregation, Priority priority) {
        public static final String UNIQUE = "UNIQUE";
        public static final String FIRST = "FIRST";
        public static final String ANY = "ANY";
        public static final String PRIORITY = "PRIORITY";
        public static final String COLLECT = "COLLECT";
        public static final List<String> HIT_POLICIES = List.of(UNIQUE, FIRST, ANY, PRIORITY, COLLECT);
        public static final List<String> AGGREGATIONS = List.of("SUM", "MIN", "MAX", "COUNT");

        public DecisionTable(int index, String key, int version, String hitPolicy, List<Row> rows,
                             Map<String, Literal> defaultOutput) {
            this(index, key, version, hitPolicy, rows, defaultOutput, null, null);
        }

        public String path() {
            return "/decisions/" + index;
        }

        public boolean first() {
            return FIRST.equals(hitPolicy);
        }

        /** @return true for COLLECT: every matching row counts and the outputs are aggregated */
        public boolean collect() {
            return COLLECT.equals(hitPolicy);
        }

        /**
         * Rows in the order the deployed DMN tries them: the table order, except under PRIORITY, where rows are sorted
         * by the rank of their priority output (stable, so equal ranks keep the table order).
         */
        public List<Row> evaluationOrder() {
            if (!PRIORITY.equals(hitPolicy) || priority == null) return rows;
            List<Row> sorted = new java.util.ArrayList<>(rows);
            sorted.sort(java.util.Comparator.comparingInt(r -> priority.rank(r.then().get(priority.output()))));
            return List.copyOf(sorted);
        }

        /**
         * PRIORITY: the output whose value decides between matching rows, and its values from the highest priority
         * to the lowest.
         */
        public record Priority(String output, List<Literal> order) {
            /** @return the position of the value in the order (lower wins), or the order size when absent */
            public int rank(Literal value) {
                for (int i = 0; i < order.size(); i++) {
                    if (value != null && order.get(i).typeName().equals(value.typeName())
                            && com.orqix.compiler.ConditionMatcher.matches("eq", value.value(), order.get(i).value())) {
                        return i;
                    }
                }
                return order.size();
            }
        }

        /**
         * @param then sorted by key
         * @param effective null when the row always applies (docs/build/S41)
         */
        public record Row(int index, Condition when, Map<String, Literal> then, Window effective) {
            public Row(int index, Condition when, Map<String, Literal> then) {
                this(index, when, then, null);
            }

            /** True when the row applies on {@code asOf}. */
            public boolean appliesOn(java.time.LocalDate asOf) {
                return effective == null || effective.contains(asOf);
            }
        }

        /** Inclusive UTC date window of a row; a null bound is open. */
        public record Window(java.time.LocalDate from, java.time.LocalDate to) {
            public boolean contains(java.time.LocalDate day) {
                return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
            }

            public boolean overlaps(Window other) {
                if (other == null) return true;
                boolean beforeOther = to != null && other.from != null && to.isBefore(other.from);
                boolean afterOther = from != null && other.to != null && from.isAfter(other.to);
                return !beforeOther && !afterOther;
            }

            /** Days as yyyymmdd, the number the compiled DMN compares; null for an open bound. */
            public static Integer number(java.time.LocalDate day) {
                return day == null ? null : day.getYear() * 10000 + day.getMonthValue() * 100 + day.getDayOfMonth();
            }
        }

        public boolean dated() {
            return rows.stream().anyMatch(r -> r.effective() != null);
        }
    }

    /** Approved JSON Schema subset for request and result forms. {@code properties} sorted by key. */
    public record FormSchema(String path, List<String> required, Map<String, Field> properties) {

        /** All numeric/length members may be null. {@code enumValues} is null when absent. */
        public record Field(String key, FieldKind type, String title, List<String> enumValues, Integer minLength,
                            Integer maxLength, BigDecimal minimum, BigDecimal maximum, BigDecimal multipleOf,
                            Field items, Integer minItems, Integer maxItems, String formatMinimum,
                            String formatMaximum, boolean incidentEditable, List<String> rowRequired,
                            Map<String, Field> rowFields) {
            public Field(String key, FieldKind type, String title, List<String> enumValues, Integer minLength,
                         Integer maxLength, BigDecimal minimum, BigDecimal maximum, BigDecimal multipleOf,
                         Field items, Integer minItems, Integer maxItems, String formatMinimum,
                         String formatMaximum, boolean incidentEditable) {
                this(key, type, title, enumValues, minLength, maxLength, minimum, maximum, multipleOf, items, minItems,
                        maxItems, formatMinimum, formatMaximum, incidentEditable, List.of(), Map.of());
            }

            /** True for a list whose items are rows of several scalar fields (S35); {@code items.rowFields} are the columns. */
            public boolean rows() {
                return type == FieldKind.ARRAY && items != null && items.type == FieldKind.OBJECT;
            }
        }
    }

    /**
     * Presentation hints; {@code widgets} sorted by key. {@code visibleWhen} and {@code requiredWhen} map a field to its
     * condition (S35); {@code sections} are the titled groups of {@code layout}; {@code helpAbove} is true when
     * descriptions show above the control.
     */
    public record InputUi(List<String> order, Map<String, String> widgets, Map<String, Condition> visibleWhen,
                          Map<String, Condition> requiredWhen, List<Section> sections, boolean helpAbove) {
        public InputUi(List<String> order, Map<String, String> widgets) {
            this(order, widgets, Map.of(), Map.of(), List.of(), false);
        }

        /** A titled group of fields; {@code condition} may be null. */
        public record Section(int index, String id, String title, int columns, List<String> fields,
                              Condition condition) {
        }
    }

    public record Execution(int maxConcurrentRuns, int maxAttempts, int retryDelaySeconds) {
    }

    /** Declared connection dependency. */
    public record Dependency(int index, String key, int definitionVersion) {
        public String path() {
            return "/dependencies/connections/" + index;
        }
    }

    public record OutputEntry(String key, String fieldRef, boolean required) {
    }

    /** Canvas layout; {@code positions} sorted by node id. */
    public record Layout(Map<String, Position> positions) {
    }

    public record Position(BigDecimal x, BigDecimal y) {
    }
}
