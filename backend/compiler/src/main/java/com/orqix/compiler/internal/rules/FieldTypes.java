package com.orqix.compiler.internal.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.ModelReader;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;

import tools.jackson.databind.JsonNode;

/** Resolves field references ({@code /input/k}, {@code /steps/X/k}) to types (S4 section 3). */
public final class FieldTypes {

    /** Outputs of an approval step, in documentation order. */
    public static final List<String> APPROVAL_OUTPUTS = List.of("decision", "reason", "comment", "decidedBy",
            "decidedAt");

    /**
     * Type of a field.
     *
     * @param domain      finite set of possible string values, or null when unbounded
     * @param mayBeAbsent true for approval {@code reason}/{@code comment}
     * @param minimum     inclusive lower bound from the request form, or null
     * @param maximum     inclusive upper bound from the request form, or null
     */
    public record FieldType(FieldKind kind, Set<String> domain, boolean mayBeAbsent, BigDecimal minimum,
                            BigDecimal maximum, FieldType items) {
        public FieldType(FieldKind kind, Set<String> domain, boolean mayBeAbsent, BigDecimal minimum, BigDecimal maximum) {
            this(kind, domain, mayBeAbsent, minimum, maximum, null);
        }
        static FieldType of(FieldKind kind) {
            return new FieldType(kind, null, false, null, null);
        }
    }

    /** Outcome of resolving a field reference. */
    public sealed interface Resolution {
    }

    /** @param producer the step that produces the value, or null for {@code /input/...} */
    public record Resolved(FieldType type, Node producer, String key) implements Resolution {
    }

    /** The reference names something that does not exist; {@code message} explains it. */
    public record Unknown(String message) implements Resolution {
    }

    /** The type cannot be determined because of another reported problem (e.g. unknown decision table). */
    public record Indeterminate() implements Resolution {
    }

    private final WorkflowDefinition definition;
    private final Graph graph;
    private final Connections connections;
    private final CompileContext workspace;
    private final Map<String, DecisionTable> tables = new HashMap<>();

    public FieldTypes(WorkflowDefinition definition, Graph graph, Connections connections,
            CompileContext workspace) {
        this.definition = definition;
        this.graph = graph;
        this.connections = connections;
        this.workspace = workspace;
        for (DecisionTable t : definition.decisions()) {
            tables.putIfAbsent(t.key(), t);
        }
    }

    /** @return the first table with this key, or null */
    public DecisionTable table(String key) {
        return tables.get(key);
    }

    public Resolution resolve(String fieldRef) {
        if (fieldRef.startsWith("/input/")) {
            String key = fieldRef.substring("/input/".length());
            FormSchema.Field field = definition.inputSchema().properties().get(key);
            if (field == null) {
                return new Unknown("The request form has no field \"" + key + "\".");
            }
            Set<String> domain = field.type() == FieldKind.STRING && field.enumValues() != null
                    ? Collections.unmodifiableSet(new TreeSet<>(field.enumValues())) : null;
            return new Resolved(new FieldType(field.type(), domain, false, field.minimum(), field.maximum(),
                    field.items() == null ? null : formType(field.items(), false)), null,
                    key);
        }
        String rest = fieldRef.substring("/steps/".length());
        int slash = rest.indexOf('/');
        String stepId = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        Node producer = graph.node(stepId);
        if (producer == null) {
            return new Unknown("There is no step with the ID \"" + stepId + "\".");
        }
        return switch (producer) {
            case Node.Approval a -> approvalOutput(a, key);
            case Node.Decision d -> decisionOutput(d, key);
            case Node.Http h -> httpOutput(h, key);
            case Node.StartWorkflow w -> startWorkflowOutput(w, key);
            case Node.WaitForMessage m -> messageOutput(m, key);
            default -> new Unknown("\"" + producer.label() + "\" (" + producer.kind().businessLabel()
                    + ") does not produce any data that can be used here.");
        };
    }

    /** Outputs of "Wait for a message": the declared payload fields (S16); a field that is not required may be missing. */
    private Resolution messageOutput(Node.WaitForMessage m, String key) {
        Node.PayloadField field = m.field(key);
        if (field == null) {
            String carried = m.payload().isEmpty() ? "no values"
                    : String.join(", ", m.payload().stream().map(Node.PayloadField::name).toList());
            return new Unknown("\"" + m.label() + "\" does not carry \"" + key + "\". It carries: " + carried + ".");
        }
        return new Resolved(new FieldType(field.type(), null, !field.required(), null, null), m, key);
    }

    private Resolution approvalOutput(Node.Approval a, String key) {
        if (!APPROVAL_OUTPUTS.contains(key)) {
            return new Unknown("\"" + a.label() + "\" does not produce \"" + key + "\". It produces: "
                    + String.join(", ", APPROVAL_OUTPUTS) + ".");
        }
        boolean timeout = a.timeoutAfter() != null;
        if (key.equals("decision")) {
            Set<String> domain = timeout ? Set.of("approved", "rejected", "timedOut") : Set.of("approved", "rejected");
            return new Resolved(new FieldType(FieldKind.STRING, domain, false, null, null), a, key);
        }
        // reason and comment are optional; with a time limit nobody decides on the timedOut path.
        boolean optional = key.equals("reason") || key.equals("comment")
                || (timeout && (key.equals("decidedBy") || key.equals("decidedAt")));
        return new Resolved(new FieldType(FieldKind.STRING, null, optional, null, null), a, key);
    }

    /** Outputs of "Start another workflow": outcome, outcomeKind, reference and the mapped result fields (S12). */
    private Resolution startWorkflowOutput(Node.StartWorkflow w, String key) {
        CompileContext.WorkflowInfo child = workspace == null ? null : workspace.workflows().get(w.workflowRef());
        if (child == null || !child.published()) {
            return new Indeterminate();
        }
        switch (key) {
            case "iterations" -> {
                return new Resolved(FieldType.of(FieldKind.INTEGER), w, key);
            }
            case "limitReached", "allSucceeded" -> {
                return new Resolved(FieldType.of(FieldKind.BOOLEAN), w, key);
            }
            case "outcome" -> {
                return new Resolved(new FieldType(FieldKind.STRING, Collections.unmodifiableSet(
                        new TreeSet<>(child.outcomes())), false, null, null), w, key);
            }
            case "outcomeKind" -> {
                return new Resolved(new FieldType(FieldKind.STRING, Collections.unmodifiableSet(
                        new TreeSet<>(child.outcomeKinds())), false, null, null), w, key);
            }
            case "reference" -> {
                return new Resolved(FieldType.of(FieldKind.STRING), w, key);
            }
            default -> {
                if (w.collectionMapping().containsKey(key)) {
                    String childKey = w.collectionMapping().get(key);
                    FieldType item = Set.of("outcome", "outcomeKind", "reference").contains(childKey)
                            ? FieldType.of(FieldKind.STRING) : null;
                    if (item == null) {
                        FormSchema.Field field = ModelReader.readFormSchema("/outputSchema", StrictJson.parse(child.outputSchemaJson())).properties().get(childKey);
                        if (field == null || field.type() == FieldKind.ARRAY) return new Indeterminate();
                        item = formType(field, false);
                    }
                    return new Resolved(new FieldType(FieldKind.ARRAY, null,
                            !Set.of("outcome", "outcomeKind", "reference").contains(childKey), null, null, item), w, key);
                }
                String childKey = w.resultMapping().get(key);
                if (childKey == null) {
                    Set<String> keys = new TreeSet<>(List.of("outcome", "outcomeKind", "reference", "iterations", "limitReached", "allSucceeded"));
                    keys.addAll(w.resultMapping().keySet());
                    keys.addAll(w.collectionMapping().keySet());
                    return new Unknown("\"" + w.label() + "\" does not produce \"" + key + "\". It produces: "
                            + String.join(", ", keys) + ".");
                }
                FormSchema.Field field = ModelReader.readFormSchema("/outputSchema",
                        StrictJson.parse(child.outputSchemaJson())).properties().get(childKey);
                if (field == null) {
                    return new Indeterminate();
                }
                Set<String> domain = field.type() == FieldKind.STRING && field.enumValues() != null
                        ? Collections.unmodifiableSet(new TreeSet<>(field.enumValues())) : null;
                boolean optional = !child.guaranteedOutputs().contains(childKey);
                return new Resolved(new FieldType(field.type(), domain, optional, null, null,
                        field.items() == null ? null : formType(field.items(), false)), w, key);
            }
        }
    }

    private Resolution decisionOutput(Node.Decision d, String key) {
        DecisionTable table = tables.get(d.decisionRef());
        if (table == null) {
            return new Indeterminate();
        }
        Set<String> keys = outputKeys(table);
        if (!keys.contains(key)) {
            return new Unknown("\"" + d.label() + "\" does not produce \"" + key + "\". It produces: "
                    + String.join(", ", keys) + ".");
        }
        FieldType type = outputType(table, key);
        return type == null ? new Indeterminate() : new Resolved(type, d, key);
    }

    private Resolution httpOutput(Node.Http h, String key) {
        String pointer = h.resultMapping().get(key);
        if (pointer == null) {
            return new Unknown("\"" + h.label() + "\" does not produce \"" + key + "\". It produces: "
                    + String.join(", ", h.resultMapping().keySet()) + ".");
        }
        JsonNode operation = connections.operation(h.connectionRef(), h.operation());
        if (operation == null) {
            return new Indeterminate();
        }
        Connections.ResponseScalar scalar = Connections.responseScalar(operation, pointer);
        if (scalar == null) {
            return new Indeterminate();
        }
        return new Resolved(new FieldType(scalar.kind(), scalar.kind() == FieldKind.STRING ? scalar.enumValues()
                : null, false, null, null), h, key);
    }

    /** @return output keys of all rows and the default, sorted */
    public static Set<String> outputKeys(DecisionTable table) {
        Set<String> keys = new TreeSet<>(table.defaultOutput().keySet());
        for (DecisionTable.Row row : table.rows()) {
            keys.addAll(row.then().keySet());
        }
        return keys;
    }

    /**
     * @return the type shared by every row and the default for {@code key}; strings get the set of literal values as
     *         domain, numbers are {@code integer} when every value is integral; null when the types disagree
     */
    public static FieldType outputType(DecisionTable table, String key) {
        List<Literal> values = new ArrayList<>();
        for (DecisionTable.Row row : table.rows()) {
            Literal v = row.then().get(key);
            if (v != null) {
                values.add(v);
            }
        }
        Literal def = table.defaultOutput().get(key);
        if (def != null) {
            values.add(def);
        }
        Set<String> typeNames = new LinkedHashSet<>();
        values.forEach(v -> typeNames.add(v.typeName()));
        if (typeNames.size() != 1) {
            return null;
        }
        return switch (values.get(0)) {
            case Literal.Str s -> {
                Set<String> domain = new TreeSet<>();
                values.forEach(v -> domain.add(((Literal.Str) v).string()));
                yield new FieldType(FieldKind.STRING, Collections.unmodifiableSet(domain), false, null, null);
            }
            case Literal.Bool b -> FieldType.of(FieldKind.BOOLEAN);
            case Literal.Num n -> FieldType.of(values.stream().allMatch(v -> ((Literal.Num) v).integral())
                    ? FieldKind.INTEGER : FieldKind.NUMBER);
        };
    }

    /**
     * Mapping compatibility (S4 WF_MAPPING_TYPE): number targets accept number and integer, integer targets only
     * integer; an enum target needs a source whose finite domain is a subset of the enum.
     *
     * @return null when compatible, otherwise a business explanation
     */
    public static String incompatibility(FieldType source, FieldKind targetKind, Set<String> targetEnum) {
        boolean kindOk = switch (targetKind) {
            case STRING -> source.kind() == FieldKind.STRING || (source.kind() == FieldKind.DATE && targetEnum == null);
            case DATE -> source.kind() == FieldKind.DATE;
            case NUMBER -> source.kind().numeric();
            case INTEGER -> source.kind() == FieldKind.INTEGER;
            case BOOLEAN -> source.kind() == FieldKind.BOOLEAN;
            case ARRAY, ATTACHMENT -> false;
        };
        if (!kindOk) {
            return "expects " + targetKind.description() + ", but the source is " + source.kind().description();
        }
        if (targetKind == FieldKind.STRING && targetEnum != null
                && (source.domain() == null || !targetEnum.containsAll(source.domain()))) {
            return "accepts only " + String.join(", ", new TreeSet<>(targetEnum))
                    + ", but the source can have other values";
        }
        return null;
    }

    private static FieldType formType(FormSchema.Field field, boolean optional) {
        return new FieldType(field.type(), field.enumValues() == null ? null : new TreeSet<>(field.enumValues()), optional,
                field.minimum(), field.maximum(), field.items() == null ? null : formType(field.items(), false));
    }

    public static String incompatibility(FieldType source, FormSchema.Field target) {
        if (target.type() == FieldKind.ARRAY) {
            if (source.kind() != FieldKind.ARRAY || source.items() == null) return "expects a typed collection";
            return incompatibility(source.items(), target.items());
        }
        return incompatibility(source, target.type(), target.enumValues() == null ? null : new TreeSet<>(target.enumValues()));
    }
}
