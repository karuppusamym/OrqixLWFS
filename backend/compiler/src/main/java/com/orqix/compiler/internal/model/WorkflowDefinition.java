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
        Layout uiLayout) {

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
    }

    /** @param defaultOutput sorted by key */
    public record DecisionTable(int index, String key, int version, List<Row> rows,
                                Map<String, Literal> defaultOutput) {
        public String path() {
            return "/decisions/" + index;
        }

        /** @param then sorted by key */
        public record Row(int index, Condition when, Map<String, Literal> then) {
        }
    }

    /** Approved JSON Schema subset for request and result forms. {@code properties} sorted by key. */
    public record FormSchema(String path, List<String> required, Map<String, Field> properties) {

        /** All numeric/length members may be null. {@code enumValues} is null when absent. */
        public record Field(String key, FieldKind type, String title, List<String> enumValues, Integer minLength,
                            Integer maxLength, BigDecimal minimum, BigDecimal maximum, BigDecimal multipleOf,
                            Field items, Integer minItems, Integer maxItems) {
        }
    }

    /** Presentation hints; {@code widgets} sorted by key. */
    public record InputUi(List<String> order, Map<String, String> widgets) {
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
