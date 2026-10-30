package com.orqix.compiler;

import java.util.List;

import com.orqix.compiler.internal.dmn.DmnExport;
import com.orqix.compiler.internal.dmn.DmnGenerator;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.ModelReader;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decision table helpers for the application (docs/build/S121): standard DMN download, the structure check of a table
 * read from elsewhere, and the order in which the deployed DMN holds the rows. A table is the JSON of one
 * {@code decisions[]} entry; {@code key} and {@code version} may be missing (shared decision content).
 */
public final class DecisionTables {

    private DecisionTables() {
    }

    /**
     * @param key  the decision id written into the file
     * @param name the decision name shown by DMN tools, or null for the key
     * @return a standard DMN 1.3 document
     * @throws IllegalArgumentException when the table is malformed
     */
    public static String exportDmn(String tableJson, String key, String name) {
        return DmnExport.export(read(tableJson, key), name);
    }

    /**
     * Checks the structure the compiler reads (conditions, operators, hit policy and its options); field references and
     * overlaps are checked when a workflow that uses the table is validated.
     *
     * @throws IllegalArgumentException with the first problem
     */
    public static void check(String tableJson) {
        read(tableJson, "table");
    }

    /**
     * @return for each rule of the deployed DMN (1-based position), the zero-based index of the canonical row it comes
     *         from; the trailing default rule is not listed
     */
    public static List<Integer> ruleOwners(String tableJson) {
        return DmnGenerator.ruleOwners(read(tableJson, "table"));
    }

    /** Evaluates the table with the canonical semantics (see {@link DecisionEvaluator}). */
    public static DecisionEvaluator.Result evaluate(String tableJson, java.util.Map<String, Object> values) {
        return com.orqix.compiler.internal.eval.DefaultDecisionEvaluator.evaluate(read(tableJson, "table"), values);
    }

    private static DecisionTable read(String tableJson, String key) {
        JsonNode node = StrictJson.parse(tableJson);
        if (!node.isObject()) {
            throw new IllegalArgumentException("decision table must be an object");
        }
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        if (!copy.has("key")) {
            copy.put("key", key);
        }
        if (!copy.has("version")) {
            copy.put("version", 1);
        }
        if (!copy.has("hitPolicy")) {
            copy.put("hitPolicy", "UNIQUE");
        }
        return ModelReader.readDecisionTable(0, copy);
    }
}
