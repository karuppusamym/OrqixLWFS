package com.orqix.compiler;

import java.util.List;
import java.util.Map;

/**
 * Canonical (non-engine) evaluation of a decision table, used by the draft "Test this rule" endpoint. A parity test
 * must show identical results to the compiled DMN executed by Flowable (S4 section 7).
 */
public interface DecisionEvaluator {

    /**
     * @param values fieldRef (for example "/input/amount") to BigDecimal, String or Boolean (for expression entries a
     *               date may be YYYY-MM-DD text or a LocalDate); reserved keys {@code $asOf} (dated rows) and
     *               {@code $now} (the instant {@code today()} of an expression entry uses, an Instant or ISO text)
     * @throws IllegalArgumentException when the decision key is unknown or a needed value is missing or mistyped
     */
    Result evaluate(String definitionJson, String decisionKey, Map<String, Object> values);

    /**
     * Like {@link #evaluate(String, String, Map)}; {@code typesJson} holds the pinned types of the table's expression
     * entries ({@code {"input": {..}, "steps": {..}}}, S146); null infers them from the given values.
     */
    default Result evaluate(String definitionJson, String decisionKey, Map<String, Object> values, String typesJson) {
        return evaluate(definitionJson, decisionKey, values);
    }

    /**
     * @param matchedRow  zero-based index of the row whose results apply, or null when the default applied; under
     *                    COLLECT the first matching row
     * @param matchedRows every row that applies, in table order: one row for the single-result hit policies, all
     *                    matching rows under COLLECT (whose results are combined), none when the default applied
     * @param expressions the evaluated expression entries (S146), empty for a table without expressions
     */
    record Result(Integer matchedRow, boolean usedDefault, Map<String, Object> output, List<Integer> matchedRows,
                  List<Entry> expressions) {
        public Result(Integer matchedRow, boolean usedDefault, Map<String, Object> output, List<Integer> matchedRows) {
            this(matchedRow, usedDefault, output, matchedRows, List.of());
        }

        public Result(Integer matchedRow, boolean usedDefault, Map<String, Object> output) {
            this(matchedRow, usedDefault, output, matchedRow == null ? List.of() : List.of(matchedRow));
        }
    }

    /**
     * One evaluated expression entry (S146). Every input entry of every row is evaluated; output entries are evaluated
     * for the rows that match.
     *
     * @param row        zero-based row index
     * @param term       zero-based term index within the row for an input entry; -1 for an output entry
     * @param kind       {@code input} (a test in the column {@code target}, a field reference) or {@code output} (the
     *                   result {@code target})
     * @param expression the Orqix expression text
     * @param value      true, false or null for an input entry (null does not match); the computed value (BigDecimal,
     *                   String or Boolean) for an output entry
     */
    record Entry(int row, int term, String kind, String target, String expression, Object value) {
    }
}
