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

    /** The DMN {@code expressionLanguage} of an entry written as an Orqix expression (S146). */
    public static final String ORQIX_EXPRESSION_LANGUAGE = DmnExport.ORQIX_EXPRESSION_LANGUAGE;

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
        // S146: formula entries must at least parse; their types are checked in the workflow that uses the table.
        com.orqix.compiler.internal.eval.ExpressionTable.fields(read(tableJson, "table"));
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

    /**
     * Evaluates a table that may use Orqix expression entries (S146).
     *
     * @param typesJson the pinned types of its expressions (as the BPMN decision task carries them), or null to infer
     *                  them from the values
     */
    public static DecisionEvaluator.Result evaluate(String tableJson, java.util.Map<String, Object> values,
            String typesJson) {
        return com.orqix.compiler.internal.eval.DefaultDecisionEvaluator.evaluate(read(tableJson, "table"), values,
                typesJson == null ? null : StrictJson.parse(typesJson));
    }

    /** @return true when a row of the table has an input or output entry written as an Orqix expression (S146) */
    public static boolean usesExpressions(String tableJson) {
        return read(tableJson, "table").usesExpressions();
    }

    /**
     * @return every field the table reads, sorted: the tested columns and, for expression entries, the fields their
     *         expressions name
     * @throws IllegalArgumentException when the table is malformed or an expression has a syntax problem
     */
    public static java.util.SortedSet<String> fields(String tableJson) {
        return new java.util.TreeSet<>(com.orqix.compiler.internal.eval.ExpressionTable.fields(read(tableJson, "table")));
    }

    /**
     * The variables of the deployed engine DMN of a table that uses expressions (S146), besides {@code asOf}: the
     * compared fields as {@code in<n>}, each input entry expression's result as boolean {@code x<k>} (no value is
     * false) and each output entry expression's value as {@code y<k>} (null when its row did not match).
     *
     * @param values   the field values the canonical evaluation used
     * @param result   that evaluation
     */
    public static java.util.Map<String, Object> engineVariables(String tableJson, java.util.Map<String, Object> values,
            DecisionEvaluator.Result result) {
        return DmnGenerator.engineVariables(read(tableJson, "table"), values, result);
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
