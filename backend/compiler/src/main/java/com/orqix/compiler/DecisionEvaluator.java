package com.orqix.compiler;

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

    /** @param matchedRow zero-based row index, or null when the default applied */
    record Result(Integer matchedRow, boolean usedDefault, Map<String, Object> output) {
    }
}
