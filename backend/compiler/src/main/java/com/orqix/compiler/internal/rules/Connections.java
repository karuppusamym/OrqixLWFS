package com.orqix.compiler.internal.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.CompileContext.ConnectionVersion;
import com.orqix.compiler.internal.json.JsonPointers;
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

    /** @return request fields of an operation by name (sorted); empty when it has no request body */
    public static Map<String, RequestField> requestFields(JsonNode operation) {
        Map<String, RequestField> fields = new LinkedHashMap<>();
        JsonNode schema = operation.get("requestSchema");
        if (schema == null) {
            return fields;
        }
        List<String> names = new ArrayList<>(schema.get("properties").propertyNames());
        Collections.sort(names);
        for (String name : names) {
            JsonNode f = schema.get("properties").get(name);
            fields.put(name, new RequestField(FieldKind.fromJson(f.get("type").stringValue()), enumOf(f)));
        }
        return fields;
    }

    /** @return required request field names in declaration order */
    public static List<String> requiredRequestFields(JsonNode operation) {
        List<String> required = new ArrayList<>();
        JsonNode schema = operation.get("requestSchema");
        if (schema != null && schema.has("required")) {
            schema.get("required").forEach(r -> required.add(r.stringValue()));
        }
        return required;
    }

    /** @return the scalar at the pointer in the operation's response schema, or null when it does not resolve */
    public static ResponseScalar responseScalar(JsonNode operation, String pointer) {
        List<String> tokens;
        try {
            tokens = JsonPointers.parse(pointer);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (tokens.isEmpty()) {
            return null;
        }
        JsonNode schema = operation.get("responseSchema");
        boolean allRequired = true;
        for (String token : tokens) {
            if (schema == null || !"object".equals(schema.path("type").stringValue(null))) {
                return null;
            }
            JsonNode next = schema.path("properties").get(token);
            if (next == null) {
                return null;
            }
            boolean required = false;
            for (JsonNode r : schema.path("required")) {
                if (token.equals(r.stringValue())) {
                    required = true;
                }
            }
            allRequired &= required;
            schema = next;
        }
        FieldKind kind = FieldKind.fromJson(schema.path("type").stringValue(""));
        return kind == null ? null : new ResponseScalar(kind, enumOf(schema), allRequired);
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
