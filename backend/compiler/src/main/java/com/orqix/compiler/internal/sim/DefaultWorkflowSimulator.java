package com.orqix.compiler.internal.sim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.Compilers;
import com.orqix.compiler.FormValidator;
import com.orqix.compiler.SimulationContext;
import com.orqix.compiler.SimulationResult;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.Severity;
import com.orqix.compiler.WorkflowSimulator;
import com.orqix.compiler.internal.DefaultWorkflowCompiler;
import com.orqix.compiler.internal.Pipeline;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.schema.SchemaValidation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Default {@link WorkflowSimulator}: stages 1 to 7 of the compiler pipeline decide whether the definition can be run
 * at all, then {@link SimulationRun} walks it. See that class for the semantics.
 */
public final class DefaultWorkflowSimulator implements WorkflowSimulator {

    /** Blocking rules that only say the workspace is not set up yet: the dry run still works and reports them as warnings. */
    static final Set<String> TOLERATED = Set.of("WF_GROUP_UNBOUND", "WF_CONNECTION_UNKNOWN", "WF_CONNECTION_UNBOUND",
            "WF_CHILD_UNKNOWN", "WF_CHILD_NOT_PUBLISHED", "WF_SELF_APPROVAL_NOT_ALLOWED");

    /** Child workflows are simulated at most this many levels below the tested workflow (S12 section 4). */
    static final int MAX_CHILD_DEPTH = 3;

    /** Shared across nested occurrences; bounds the complete evaluation, not each child separately. */
    static final class Budget {
        int nodes;
        int children;
        boolean visit() { return ++nodes <= 1000; }
        boolean child() { return ++children <= 25; }
    }

    private final Pipeline pipeline;

    public DefaultWorkflowSimulator() {
        this.pipeline = new Pipeline(DefaultWorkflowCompiler.VERSION, new SchemaValidation());
    }

    @Override
    public SimulationResult simulate(String definitionJson, String inputJson, String scenarioJson,
                                     SimulationContext context) {
        return run(definitionJson, inputJson, scenarioJson, context == null
                ? new SimulationContext(null, null, null) : context, 0, new Budget());
    }

    SimulationResult run(String definitionJson, String inputJson, String scenarioJson, SimulationContext context,
                         int depth, Budget budget) {
        if (scenarioJson != null && scenarioJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024)
            throw new IllegalArgumentException("The scenario exceeds 64 KB");
        JsonNode scenario;
        if (scenarioJson == null || scenarioJson.isBlank()) {
            scenario = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        } else {
            try {
                scenario = StrictJson.parse(scenarioJson);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The scenario is not valid JSON", e);
            }
        }
        if (!scenario.isObject()) {
            throw new IllegalArgumentException("The scenario must be an object");
        }
        validateScenario(scenario,0);
        Pipeline.Outcome outcome = pipeline.run(definitionJson, context.workspace());
        ValidationReport report = outcome.report();
        List<ValidationIssue> blockers = new ArrayList<>();
        List<ValidationIssue> tolerated = new ArrayList<>();
        for (ValidationIssue issue : report.issues()) {
            if (issue.severity() == Severity.BLOCKER) {
                (TOLERATED.contains(issue.code()) ? tolerated : blockers).add(issue);
            }
        }
        if (outcome.definition() == null || !blockers.isEmpty()) {
            return new SimulationResult(SimulationResult.Status.CANNOT_RUN, List.of(), blockers, 0, 0, null);
        }
        JsonNode root = StrictJson.parse(definitionJson);
        JsonNode input;
        try {
            input = StrictJson.parse(inputJson == null ? "{}" : inputJson);
        } catch (RuntimeException e) {
            return new SimulationResult(SimulationResult.Status.INVALID_INPUT, List.of(new FormValidator.FieldError("",
                    "type", "The sample request must be an object with the form answers.")), List.of(), 0, 0, null);
        }
        List<FormValidator.FieldError> errors = Compilers.formValidator().validate(
                StrictJson.write(root.path("inputSchema")), StrictJson.write(input));
        if (!errors.isEmpty()) {
            return new SimulationResult(SimulationResult.Status.INVALID_INPUT, errors, List.of(), 0, 0, null);
        }
        SimulationRun sim = new SimulationRun(this, outcome, definitionJson, input, scenario, context, depth, report,
                tolerated, budget);
        ObjectNode result = sim.execute();
        SimulationResult.Status status = "finished".equals(result.path("outcome").path("status").asString())
                ? SimulationResult.Status.FINISHED : SimulationResult.Status.NEEDS_ATTENTION;
        return new SimulationResult(status, List.of(), List.of(), result.path("trace").size(),
                result.path("warnings").size(), StrictJson.write(result));
    }

    private static void validateScenario(JsonNode scenario, int level) {
        if (!scenario.isObject() || scenario.size()>20 || level>MAX_CHILD_DEPTH)
            throw new IllegalArgumentException("Scenario settings exceed their bounded shape");
        for(String group:List.of("approvals","calls","children","messages","childScenarios")) {
            JsonNode entries=scenario.path(group);
            if (entries.isMissingNode()) continue;
            if (!entries.isObject() || entries.size()>100)
                throw new IllegalArgumentException(group + " must contain at most 100 step settings");
            if ("childScenarios".equals(group)) entries.properties().forEach(e -> validateScenario(e.getValue(),level+1));
            if ("children".equals(group)) entries.properties().forEach(e -> {
                JsonNode outcomes=e.getValue().path("outcomes");
                if (!outcomes.isMissingNode() && (!outcomes.isArray() || outcomes.size()>25))
                    throw new IllegalArgumentException("Child outcomes are bounded to 25 occurrences");
            });
        }
    }

    static Object plain(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(plain(n)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.properties().forEach(e -> map.put(e.getKey(), plain(e.getValue())));
        return map;
    }

    static JsonNode json(Object value) {
        var f = tools.jackson.databind.node.JsonNodeFactory.instance;
        if (value == null) {
            return f.nullNode();
        }
        if (value instanceof BigDecimal d) {
            BigDecimal n = Text.normalize(d);
            if (n.scale() == 0 && n.precision() <= 17) {
                return f.numberNode(n.longValueExact());
            }
            return f.numberNode(n);
        }
        if (value instanceof Number n) {
            return json(new BigDecimal(n.toString()));
        }
        if (value instanceof Boolean b) {
            return f.booleanNode(b);
        }
        if (value instanceof List<?> list) {
            var array = f.arrayNode();
            list.forEach(v -> array.add(json(v)));
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = f.objectNode();
            map.forEach((k, v) -> object.set(String.valueOf(k), json(v)));
            return object;
        }
        return f.stringNode(String.valueOf(value));
    }
}
