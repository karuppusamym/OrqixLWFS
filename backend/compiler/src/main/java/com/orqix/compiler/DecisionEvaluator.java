package com.orqix.compiler;

import java.util.List;
import java.util.Map;

/**
 * Canonical (non-engine) evaluation of a decision table, used by the draft "Test this rule" endpoint. A parity test
 * must show identical results to the compiled DMN executed by Flowable (S4 section 7).
 */
public interface DecisionEvaluator {

    /**
     * @param values fieldRef (for example "/input/amount") to BigDecimal, String or Boolean
     * @throws IllegalArgumentException when the decision key is unknown or a needed value is missing or mistyped
     */
    Result evaluate(String definitionJson, String decisionKey, Map<String, Object> values);

    /**
     * @param matchedRow  zero-based index of the row whose results apply, or null when the default applied; under
     *                    COLLECT the first matching row
     * @param matchedRows every row that applies, in table order: one row for the single-result hit policies, all
     *                    matching rows under COLLECT (whose results are combined), none when the default applied
     */
    record Result(Integer matchedRow, boolean usedDefault, Map<String, Object> output, List<Integer> matchedRows) {
        public Result(Integer matchedRow, boolean usedDefault, Map<String, Object> output) {
            this(matchedRow, usedDefault, output, matchedRow == null ? List.of() : List.of(matchedRow));
        }
    }
}
