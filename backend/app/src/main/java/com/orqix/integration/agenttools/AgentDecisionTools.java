package com.orqix.integration.agenttools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.DecisionTables;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared decision tables as agent tools (CG-M02, S135): a pinned version of a workspace decision table, evaluated with
 * the canonical semantics. Deterministic, cheap and without network. The tool's arguments are the fields the table's
 * conditions read (named by the last segment of the field reference, typed from the condition values); the result
 * goes back to the model as data.
 */
@Component
public class AgentDecisionTools {

    /** A loaded table: its content, the argument schema the model gets and argument name to field reference. */
    public record Table(String key, int version, String content, ObjectNode argumentSchema, Map<String, String> fields) {
    }

    private final JdbcClient jdbc;
    private final Json json;

    public AgentDecisionTools(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Table> table(UUID workspaceId, String key, int version) {
        return jdbc.sql("""
                SELECT v.content::text FROM decision_asset a
                JOIN decision_asset_version v ON v.asset_id = a.id AND v.workspace_id = a.workspace_id
                WHERE a.workspace_id = :ws AND a.key = :key AND v.version = :version
                """).param("ws", workspaceId).param("key", key).param("version", version).query(String.class)
                .optional().map(content -> describe(key, version, content));
    }

    private Table describe(String key, int version, String content) {
        JsonNode table = json.parse(content);
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (JsonNode row : table.path("rows")) {
            collect(row.path("when"), fields, types);
        }
        ObjectNode schema = json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        fields.forEach((name, ref) -> {
            properties.putObject(name).put("type", types.getOrDefault(name, "string"));
            required.add(name);
        });
        schema.put("additionalProperties", false);
        return new Table(key, version, content, schema, fields);
    }

    private void collect(JsonNode when, Map<String, String> fields, Map<String, String> types) {
        if (when.has("terms")) {
            when.path("terms").forEach(t -> collect(t, fields, types));
            return;
        }
        String ref = when.path("fieldRef").asString("");
        if (ref.isEmpty() || ref.startsWith("$")) {
            return;
        }
        String name = ref.substring(ref.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_]", "_");
        fields.putIfAbsent(name, ref);
        JsonNode value = when.path("value");
        if (value.isArray() && !value.isEmpty()) {
            value = value.get(0);
        }
        String type = value.isNumber() ? "number" : value.isBoolean() ? "boolean" : "string";
        types.putIfAbsent(name, type);
    }

    /**
     * Evaluates the table with the model's (already schema-validated) arguments.
     *
     * @return the result fields and whether the default applied
     * @throws IllegalArgumentException when a value is missing or mistyped
     */
    public ObjectNode evaluate(Table table, ObjectNode arguments) {
        Map<String, Object> values = new HashMap<>();
        values.put("$asOf", LocalDate.now());
        table.fields().forEach((name, ref) -> {
            JsonNode value = arguments.path(name);
            if (value.isMissingNode() || value.isNull()) {
                throw new IllegalArgumentException(name + " is required");
            }
            values.put(ref, value.isNumber() ? (Object) new BigDecimal(value.asString())
                    : value.isBoolean() ? (Object) value.asBoolean() : value.asString());
        });
        DecisionEvaluator.Result result = DecisionTables.evaluate(table.content(), values);
        ObjectNode out = json.object();
        ObjectNode output = out.putObject("result");
        result.output().forEach((k, v) -> output.set(k, json.parse(json.write(v))));
        out.put("usedDefault", result.usedDefault());
        return out;
    }
}
