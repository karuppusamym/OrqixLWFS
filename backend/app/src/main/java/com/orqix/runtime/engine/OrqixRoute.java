package com.orqix.runtime.engine;

import java.math.BigDecimal;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

import com.orqix.compiler.ConditionMatcher;
import com.orqix.shared.json.Json;

/**
 * Expression bean {@code orqixRoute} (S4 section 6, S5 section 5): approval routing and structured branch conditions.
 * Conditions are read from the flow's {@code orqix:condition} extension, never from user text inside expressions.
 */
@Component("orqixRoute")
public class OrqixRoute {

    private final Json json;

    public OrqixRoute(Json json) {
        this.json = json;
    }

    public boolean approved(DelegateExecution execution, String approvalNodeId) {
        return "approved".equals(decision(execution, approvalNodeId));
    }

    public boolean rejected(DelegateExecution execution, String approvalNodeId) {
        return "rejected".equals(decision(execution, approvalNodeId));
    }

    public boolean condition(DelegateExecution execution, String transitionId) {
        FlowElement element = OrqixModel.flowElement(execution.getProcessDefinitionId(), transitionId);
        if (!(element instanceof SequenceFlow)) {
            throw new IllegalStateException(transitionId + " is not a sequence flow");
        }
        ExtensionElement condition = OrqixModel.requireExtension(element, "condition");
        String fieldRef = OrqixModel.attribute(condition, "fieldRef");
        String operator = OrqixModel.attribute(condition, "operator");
        Object literal = literal(OrqixModel.attribute(condition, "valueType"), OrqixModel.attribute(condition, "value"));
        Object actual = ProcessData.requireScalar(execution, json, fieldRef);
        return ConditionMatcher.matches(operator, actual, literal);
    }

    static Object literal(String valueType, String value) {
        return switch (valueType) {
            case "number", "integer" -> new BigDecimal(value);
            case "boolean" -> Boolean.valueOf(value);
            case "string" -> value;
            default -> throw new IllegalStateException("Unknown valueType " + valueType);
        };
    }

    private String decision(DelegateExecution execution, String approvalNodeId) {
        return ProcessData.step(execution, json, approvalNodeId)
                .map(step -> step.path("decision").asString(null))
                .orElseThrow(() -> new IllegalStateException("Approval " + approvalNodeId + " has no decision"));
    }
}
