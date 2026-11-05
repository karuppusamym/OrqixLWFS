package com.orqix.compiler.internal.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.ConnectionContracts;
import com.orqix.compiler.CompileContext.ConnectionVersion;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Dependency;
import com.orqix.compiler.internal.schema.SchemaValidation;

import tools.jackson.databind.JsonNode;

/**
 * Connection definitions pinned by {@code dependencies.connections}, resolved against the workspace registry in the
 * compile context. Definitions are re-validated against the connection schema defensively.
 */
public final class Connections {

    /** Resolution state of one declared dependency. */
    public enum Status { OK, NOT_REGISTERED, MALFORMED }

    /** @param definition parsed definition when {@code status == OK}; @param problem reason when MALFORMED */
    public record Pinned(Dependency dependency, Status status, ConnectionVersion version, JsonNode definition,
                         String problem) {
    }

    /** A scalar reached through a response pointer; {@code allRequired} is false if any segment is optional. */
    public record ResponseScalar(FieldKind kind, Set<String> enumValues, boolean allRequired) {
    }

    /** A flat request field of an operation. */
    public record RequestField(FieldKind kind, Set<String> enumValues) {
    }

    private final Map<String, Pinned> byKey = new LinkedHashMap<>();
    private final List<Dependency> duplicates = new ArrayList<>();

    public Connections(WorkflowDefinition definition, CompileContext context, SchemaValidation schemas) {
        for (Dependency dep : definition.dependencies()) {
            if (byKey.containsKey(dep.key())) {
                duplicates.add(dep);
                continue;
            }
            byKey.put(dep.key(), pin(dep, context, schemas));
        }
    }

    private static Pinned pin(Dependency dep, CompileContext context, SchemaValidation schemas) {
        ConnectionVersion version = null;
        for (ConnectionVersion v : context.connectionVersions()) {
            if (v.key().equals(dep.key()) && v.version() == dep.definitionVersion()) {
                version = v;
                break;
            }
        }
        if (version == null) {
            return new Pinned(dep, Status.NOT_REGISTERED, null, null, null);
        }
        JsonNode parsed;
        try {
            parsed = StrictJson.parse(version.definitionJson());
        } catch (IllegalArgumentException e) {
            return new Pinned(dep, Status.MALFORMED, version, null, "it is not valid JSON");
        }
        List<String> problems = schemas.validateConnection(parsed);
        if (!problems.isEmpty()) {
            return new Pinned(dep, Status.MALFORMED, version, null,
                    "it does not match the connection definition format (" + problems.get(0) + ")");
        }
        if (!parsed.get("key").stringValue().equals(dep.key())
                || parsed.get("version").intValue(-1) != dep.definitionVersion()) {
            return new Pinned(dep, Status.MALFORMED, version, null,
                    "its key or version does not match the registration");
        }
        return new Pinned(dep, Status.OK, version, parsed, null);
    }

    /** @return the first declaration of the key, or null when undeclared */
    public Pinned pinned(String key) {
        return byKey.get(key);
    }

    public Map<String, Pinned> all() {
        return Collections.unmodifiableMap(byKey);
    }

    /** @return later declarations of an already declared key */
    public List<Dependency> duplicates() {
        return Collections.unmodifiableList(duplicates);
    }

    /** @return the operation object, or null when the connection is not resolved or has no such operation */
    public JsonNode operation(String connectionKey, String operation) {
        Pinned p = byKey.get(connectionKey);
        if (p == null || p.status() != Status.OK) {
            return null;
        }
        JsonNode op = p.definition().get("operations").get(operation);
        return op == null || !op.isObject() ? null : op;
    }

    /**
     * @return mappable inputs of an operation by name (sorted): request fields and, for v1, path parameters that are
     *         not constants; empty when it has none
     */
    public static Map<String, RequestField> requestFields(JsonNode operation) {
        Map<String, RequestField> fields = new LinkedHashMap<>();
        Map<String, JsonNode> inputs = ConnectionContracts.inputs(operation);
        List<String> names = new ArrayList<>(inputs.keySet());
        Collections.sort(names);
        for (String name : names) {
            JsonNode f = inputs.get(name);
            fields.put(name, new RequestField(FieldKind.fromJson(f.get("type").stringValue(),
                    f.path("format").stringValue(null)), enumOf(f)));
        }
        return fields;
    }

    /** @return required inputs in declaration order (request fields, then non-constant path parameters) */
    public static List<String> requiredRequestFields(JsonNode operation) {
        return new ArrayList<>(ConnectionContracts.requiredInputs(operation));
    }

    /** @return the request field Orqix fills with the reconciliation key (idempotentByLookup), or null */
    public static String reconcileKeyField(JsonNode operation) {
        JsonNode reconcile = operation.get("reconcile");
        return reconcile == null ? null : reconcile.path("keyField").stringValue(null);
    }

    /**
     * @return the scalar at the pointer in the operation's response schema (array items by index, or a declared
     *         response header for {@code /@headers/<Name>}), or null when it does not resolve to a single value
     */
    public static ResponseScalar responseScalar(JsonNode operation, String pointer) {
        ConnectionContracts.ResultPointer parsed = ConnectionContracts.parseResult(pointer);
        if (parsed == null || parsed.projection()) {
            return null;
        }
        JsonNode schema;
        boolean allRequired;
        if (parsed.header() != null) {
            schema = ConnectionContracts.responseHeader(operation, parsed.header());
            allRequired = false;
        } else {
            ConnectionContracts.SchemaAt at = ConnectionContracts.schemaAt(operation.get("responseSchema"),
                    parsed.base());
            if (at == null) {
                return null;
            }
            schema = at.schema();
            allRequired = at.allRequired();
        }
        if (!ConnectionContracts.isScalar(schema)) {
            return null;
        }
        FieldKind kind = FieldKind.fromJson(schema.path("type").stringValue(""), schema.path("format").stringValue(null));
        if (kind == FieldKind.ATTACHMENT) {
            kind = FieldKind.STRING;
        }
        return kind == null ? null : new ResponseScalar(kind, enumOf(schema), allRequired);
    }

    /**
     * A list result ({@code <list>/[*]} rows, or {@code <list>/[*]/<column>} single values).
     *
     * @param itemKind   OBJECT for rows, else the kind of the single values
     * @param enumValues allowed values of single text values, or null
     */
    public record ResponseList(FieldKind itemKind, Set<String> enumValues) {
    }

    /** @return the list a projection pointer selects, or null when it is not a projection or does not resolve */
    public static ResponseList responseList(JsonNode operation, String pointer) {
        ConnectionContracts.ResultPointer parsed = ConnectionContracts.parseResult(pointer);
        if (parsed == null || !parsed.projection()) {
            return null;
        }
        ConnectionContracts.SchemaAt list = ConnectionContracts.schemaAt(operation.get("responseSchema"), parsed.base());
        if (list == null || !"array".equals(list.schema().path("type").stringValue(""))) {
            return null;
        }
        JsonNode items = list.schema().get("items");
        if (parsed.column().isEmpty()) {
            return ConnectionContracts.rowColumns(items).isEmpty() ? null : new ResponseList(FieldKind.OBJECT, null);
        }
        ConnectionContracts.SchemaAt column = ConnectionContracts.schemaAt(items, parsed.column());
        if (column == null || !ConnectionContracts.isScalar(column.schema())) {
            return null;
        }
        FieldKind kind = FieldKind.fromJson(column.schema().path("type").stringValue(""),
                column.schema().path("format").stringValue(null));
        if (kind == null || kind == FieldKind.ATTACHMENT) {
            kind = FieldKind.STRING;
        }
        return new ResponseList(kind, enumOf(column.schema()));
    }

    private static Set<String> enumOf(JsonNode field) {
        if (!field.has("enum")) {
            return null;
        }
        Set<String> values = new TreeSet<>();
        field.get("enum").forEach(v -> values.add(v.stringValue()));
        return Collections.unmodifiableSet(values);
    }
}
