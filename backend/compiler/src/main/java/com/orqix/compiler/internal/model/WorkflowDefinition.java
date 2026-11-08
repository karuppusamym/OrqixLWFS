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
        List<Subprocess> subprocesses,
        WaitExtras waits) {

    public WorkflowDefinition(String schemaVersion, String workflowKey, String name, FormSchema inputSchema,
            InputUi inputUi, FormSchema outputSchema, List<Node> nodes, List<Transition> transitions,
            List<DecisionTable> decisions, Execution execution, String selfApproval, List<Dependency> dependencies,
            Map<String, OutputEntry> outputMapping, Layout uiLayout, List<Stage> stages, List<Subprocess> subprocesses) {
        this(schemaVersion, workflowKey, name, inputSchema, inputUi, outputSchema, nodes, transitions, decisions,
                execution, selfApproval, dependencies, outputMapping, uiLayout, stages, subprocesses, WaitExtras.NONE);
    }

    /**
     * A scope over existing steps (S59). An embedded subprocess is a section entered through one entry step and left
     * by the ordinary paths of its members, with an optional time limit ({@code timeoutAfter} to {@code onTimeout}) and
     * error route ({@code errorTo}, catching {@code errorCode}, or any code when null). An event subprocess is a handler
     * started by {@code triggerType} (error, signal or timer) and is {@code interrupting} or not.
     */
    public record Subprocess(int index, String id, String label, boolean event, List<String> nodes,
                             String timeoutAfter, String onTimeout, String errorTo, String errorCode,
                             String triggerType, String triggerErrorCode, String signal, String after,
                             boolean interrupting, String message, String correlationField,
                             List<Node.PayloadField> payload) {
        public Subprocess(int index, String id, String label, boolean event, List<String> nodes,
                String timeoutAfter, String onTimeout, String errorTo, String errorCode, String triggerType,
                String triggerErrorCode, String signal, String after, boolean interrupting) {
            this(index, id, label, event, nodes, timeoutAfter, onTimeout, errorTo, errorCode, triggerType,
                    triggerErrorCode, signal, after, interrupting, null, null, List.of());
        }

        /** @return true for a message handler (CG-E08, S139): a handler started by a message for this request */
        public boolean messageHandler() {
            return event && "message".equals(triggerType);
        }

        /** @return the declared message value with this name, or null */
        public Node.PayloadField field(String name) {
            return payload.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        }

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
                             Condition condition, String aiLabel, Integer maxTraversals, String branch) {
        public Transition(int index, String id, String from, String to, Route on, String label, Condition condition) {
            this(index, id, from, to, on, label, condition, null);
        }

        public Transition(int index, String id, String from, String to, Route on, String label, Condition condition,
                          String aiLabel) {
            this(index, id, from, to, on, label, condition, aiLabel, null, null);
        }

        /** @return true for a declared bounded loop path (CG-E01, S138) */
        public boolean bounded() {
            return maxTraversals != null;
        }

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
                            List<Literal> list, Range range, String expression) {
        public Condition(String fieldRef, Operator operator, Literal value, String group, List<Condition> terms,
                List<Literal> list, Range range) { this(fieldRef, operator, value, group, terms, list, range, null); }
        /** A condition written as an Orqix expression (S137). */
        public static Condition ofExpression(String expression) { return new Condition(null, null, null, null, List.of(), List.of(), null, expression); }
        /** @return true for a condition written as an Orqix expression (S137) */
        public boolean isExpression() { return expression != null; }
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
                            Map<String, Field> rowFields, tools.jackson.databind.JsonNode json) {
            /** Without the field's JSON (rules that need format details such as lookup or timeZone see none). */
            public Field(String key, FieldKind type, String title, List<String> enumValues, Integer minLength,
                         Integer maxLength, BigDecimal minimum, BigDecimal maximum, BigDecimal multipleOf,
                         Field items, Integer minItems, Integer maxItems, String formatMinimum,
                         String formatMaximum, boolean incidentEditable, List<String> rowRequired,
                         Map<String, Field> rowFields) {
                this(key, type, title, enumValues, minLength, maxLength, minimum, maximum, multipleOf, items, minItems,
                        maxItems, formatMinimum, formatMaximum, incidentEditable, rowRequired, rowFields, null);
            }

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
                          Map<String, Condition> requiredWhen, List<Section> sections, boolean helpAbove,
                          Map<String, String> computed, List<FormRule> rules) {
        public InputUi(List<String> order, Map<String, String> widgets) {
            this(order, widgets, Map.of(), Map.of(), List.of(), false);
        }

        public InputUi(List<String> order, Map<String, String> widgets, Map<String, Condition> visibleWhen,
                Map<String, Condition> requiredWhen, List<Section> sections, boolean helpAbove) {
            this(order, widgets, visibleWhen, requiredWhen, sections, helpAbove, Map.of(), List.of());
        }

        /** A cross-field rule (CG-F04, S137): broken when {@code check} is false; {@code fields} may be empty. */
        public record FormRule(int index, String id, String check, String message, List<String> fields) {
        }

        /** A titled group of fields; {@code condition} may be null. */
        public record Section(int index, String id, String title, int columns, List<String> fields,
                              Condition condition) {
        }
    }

    /**
     * Workflow-wide execution limits. {@code stepRetry} holds the step-level retry policies (CG-W04, S131) by step id,
     * sorted by id; a step without one uses {@code maxAttempts} and {@code retryDelaySeconds}.
     */
    public record Execution(int maxConcurrentRuns, int maxAttempts, int retryDelaySeconds,
                            Map<String, StepRetry> stepRetry, Map<String, StepForEach> forEach) {
        public Execution(int maxConcurrentRuns, int maxAttempts, int retryDelaySeconds) {
            this(maxConcurrentRuns, maxAttempts, retryDelaySeconds, Map.of(), Map.of());
        }

        public Execution(int maxConcurrentRuns, int maxAttempts, int retryDelaySeconds,
                         Map<String, StepRetry> stepRetry) {
            this(maxConcurrentRuns, maxAttempts, retryDelaySeconds, stepRetry, Map.of());
        }

        /** @return the step's for-each policy (CG-E06, S138), or null when it runs once */
        public StepForEach forEachOf(String stepId) {
            return forEach.get(stepId);
        }

        /** @return the step's own policy, or null when it uses the workflow defaults */
        public StepRetry retryOf(String stepId) {
            return stepRetry.get(stepId);
        }

        /** Total attempts of the step: its own policy, else the workflow default. */
        public int attemptsOf(String stepId) {
            StepRetry r = retryOf(stepId);
            return r == null ? maxAttempts : r.maxAttempts();
        }

        /** First delay of the step in seconds: its own policy, else the workflow default. */
        public int delaySecondsOf(String stepId) {
            StepRetry r = retryOf(stepId);
            return r == null ? retryDelaySeconds : (int) Math.max(0, r.delaySeconds());
        }
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
