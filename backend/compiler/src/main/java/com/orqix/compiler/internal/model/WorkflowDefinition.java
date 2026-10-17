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
        List<Stage> stages) {

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

    /** A structured comparison; {@code operator} is never null after schema validation. */
    public record Condition(String fieldRef, Operator operator, Literal value, String group, List<Condition> terms) {
        public Condition(String fieldRef, Operator operator, Literal value) { this(fieldRef, operator, value, null, List.of()); }
        public boolean compound() { return group != null; }
        /** A group whose terms are alternatives (OR). Conjunctions are {@code group == "all"}. */
        public boolean isAny() { return "any".equals(group); }
    }

    /** @param defaultOutput sorted by key */
    public record DecisionTable(int index, String key, int version, String hitPolicy, List<Row> rows,
                                Map<String, Literal> defaultOutput) {
        public static final String UNIQUE = "UNIQUE";
        public static final String FIRST = "FIRST";

        public String path() {
            return "/decisions/" + index;
        }

        public boolean first() {
            return FIRST.equals(hitPolicy);
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
