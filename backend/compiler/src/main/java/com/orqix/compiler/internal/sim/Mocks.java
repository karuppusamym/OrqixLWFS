package com.orqix.compiler.internal.sim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import com.orqix.compiler.internal.model.Node;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mock answers of integration points in a dry run (STU03, docs/build/S96). A test can say what a call, an adapter, an
 * external worker or a message answers; nothing is ever sent. Each mock is checked against what the step declares, the
 * same way the runtime would check a real answer: a call's body against the operation's response schema (JSON Schema
 * 2020-12, the validator the runtime uses), a worker result and a message payload against the declared fields.
 * Twin: frontend/src/mocks/simulatorMocks.ts.
 */
final class Mocks {

    /** 30 days: long enough to pass any message or call time limit. */
    static final int MAX_DELAY_SECONDS = 2_592_000;
    static final int MAX_PROBLEMS = 10;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER)
                    .build()).schemaLoader(loader -> loader.fetchRemoteResources(false)));

    private Mocks() {
    }

    /** A full HTTP answer: the status, the body (validated against the response schema) and how long it takes. */
    record CallMock(Integer status, JsonNode body, Integer delaySeconds) {
    }

    /** @return the full mock of a call, or null when the settings use only the legacy failure or partial result */
    static CallMock call(String id, JsonNode s) {
        if (!s.isObject() || !(s.has("status") || s.has("body") || s.has("delaySeconds"))) {
            return null;
        }
        Integer status = null;
        if (s.has("status")) {
            JsonNode v = s.get("status");
            if (!v.isIntegralNumber() || v.asInt() < 200 || v.asInt() > 599) {
                throw new IllegalArgumentException("calls." + id + ".status must be an HTTP status from 200 to 599");
            }
            status = v.asInt();
        }
        JsonNode body = s.get("body");
        if (body != null && !body.isObject() && !body.isArray()) {
            throw new IllegalArgumentException("calls." + id + ".body must be a JSON object or array");
        }
        return new CallMock(status, body, delay("calls." + id, s));
    }

    static Integer delay(String where, JsonNode s) {
        if (!s.has("delaySeconds")) {
            return null;
        }
        JsonNode v = s.get("delaySeconds");
        if (!v.isIntegralNumber() || v.asLong() < 0 || v.asLong() > MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException(where + ".delaySeconds must be a whole number of seconds from 0 to "
                    + MAX_DELAY_SECONDS);
        }
        return v.asInt();
    }

    /**
     * Problems of a body against a response schema, as {at, keyword} sorted by place: {@code at} is the JSON pointer of
     * the value ("" for the whole answer), with the property appended for required and additionalProperties.
     */
    static ArrayNode schemaProblems(JsonNode schema, JsonNode body) {
        ObjectNode copy = (ObjectNode) schema.deepCopy();
        copy.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        Schema compiled = REGISTRY.getSchema(copy);
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> found = new ArrayList<>();
        for (Error e : compiled.validate(body)) {
            String at = e.getInstanceLocation().toString();
            String keyword = e.getKeyword() == null ? "schema" : e.getKeyword();
            if (("required".equals(keyword) || "additionalProperties".equals(keyword)) && e.getProperty() != null) {
                at = at + "/" + e.getProperty().replace("~", "~0").replace("/", "~1");
            }
            if (seen.add(at + "|" + keyword)) {
                found.add(new String[] {at, keyword});
            }
        }
        found.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        ArrayNode problems = F.arrayNode();
        for (String[] p : found.subList(0, Math.min(MAX_PROBLEMS, found.size()))) {
            problems.addObject().put("at", p[0]).put("keyword", p[1]);
        }
        return problems;
    }

    /**
     * Checks values given for declared fields (a worker result or a message payload): only declared fields, each of the
     * declared type. Returns the first problem in field order, or null.
     */
    static String fieldProblem(List<Node.PayloadField> fields, JsonNode given) {
        Set<String> declared = new LinkedHashSet<>();
        fields.forEach(f -> declared.add(f.name()));
        List<String> names = new ArrayList<>(given.propertyNames());
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            if (!declared.contains(name)) {
                return Text.quote(name) + " is not a declared field";
            }
        }
        for (Node.PayloadField f : fields) {
            JsonNode v = given.get(f.name());
            if (v == null || v.isNull()) {
                continue;
            }
            boolean ok = switch (f.type()) {
                case NUMBER -> v.isNumber();
                case INTEGER -> v.isNumber() && Text.normalize(v.decimalValue()).scale() <= 0;
                case BOOLEAN -> v.isBoolean();
                case DATE -> v.isString() && v.asString().matches("\\d{4}-\\d{2}-\\d{2}");
                default -> v.isString();
            };
            if (!ok) {
                return Text.quote(f.name()) + " must be " + typeWord(f.type());
            }
        }
        return null;
    }

    private static String typeWord(com.orqix.compiler.internal.model.Enums.FieldKind kind) {
        return switch (kind) {
            case NUMBER -> "a number";
            case INTEGER -> "a whole number";
            case BOOLEAN -> "true or false";
            case DATE -> "a date (YYYY-MM-DD)";
            default -> "text";
        };
    }

    /** The value of a given field: numbers as BigDecimal, booleans, otherwise text. */
    static Object plainField(JsonNode v) {
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        return v.asString();
    }

    /** The sample value of a declared field. */
    static Object sampleField(Node.PayloadField field) {
        return switch (field.type()) {
            case NUMBER, INTEGER -> BigDecimal.ONE;
            case BOOLEAN -> Boolean.FALSE;
            case DATE -> "2026-01-15";
            default -> "sample-" + field.name();
        };
    }
}
