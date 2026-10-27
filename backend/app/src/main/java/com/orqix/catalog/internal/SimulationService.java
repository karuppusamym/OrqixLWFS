package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.SimulationAssertions;
import com.orqix.compiler.SimulationContext;
import com.orqix.compiler.SimulationResult;
import com.orqix.compiler.WorkflowSimulator;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Test this workflow": a dry run of a draft revision or of the active release (S4 section 13, S7 section 5). Read
 * only: nothing is stored except the audit event, which carries counts and never the sample request or the scenario.
 * Needs {@code workflow.read}. The simulator is the compiler module's pure evaluator, never the engine.
 */
@Service
public class SimulationService {

    /** Largest sample request plus scenario, in UTF-8 bytes. */
    static final int MAX_BYTES = 64 * 1024;
    /** Child workflows are looked up at most this many levels below the tested workflow (S12 section 4). */
    private static final int CHILD_LEVELS = 3;

    private final WorkflowService workflows;
    private final CatalogStore store;
    private final PolicyService policy;
    private final CompileContextFactory contexts;
    private final WorkflowSimulator simulator;
    private final SimulationLimiter limiter;
    private final AuditWriter audit;
    private final Clock clock;
    private final Json json;

    public SimulationService(WorkflowService workflows, CatalogStore store, PolicyService policy,
            CompileContextFactory contexts, WorkflowSimulator simulator, SimulationLimiter limiter, AuditWriter audit,
            Clock clock, Json json) {
        this.workflows = workflows;
        this.store = store;
        this.policy = policy;
        this.contexts = contexts;
        this.simulator = simulator;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    /** What a dry run walks: one definition (a draft revision or the active release) and its workspace facts. */
    record Source(String definitionJson, Integer revision, Integer releaseNumber, SimulationContext context) {

        ObjectNode json(Json json) {
            ObjectNode source = json.object();
            if (revision != null) {
                source.put("revision", revision);
                source.putNull("releaseNumber");
            } else {
                source.putNull("revision");
                source.put("releaseNumber", releaseNumber);
            }
            return source;
        }
    }

    /** One finished dry run: the response body and the simulator's counts for the audit. */
    record CaseRun(ObjectNode body, SimulationResult result) {
    }

    @Transactional
    public JsonNode simulate(Caller caller, UUID workspaceId, UUID workflowId, CatalogDtos.SimulateRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "simulateWorkflow");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "simulateWorkflow", false);
        checkShape(request.input(), request.scenario(), request.assertions(), "");
        limiter.check(caller.subjectId());
        Source source = source(visible, workspaceId, workflowId, request.revision(), request.useActiveRelease());
        CaseRun run = runCase(source, request.input(), request.scenario(), request.assertions(), "");
        SimulationResult result = run.result();
        Map<String, Object> metadata = sourceMetadata(source);
        metadata.put("steps", result.steps());
        metadata.put("warnings", result.warnings());
        metadata.put("status", result.status().apiValue());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.simulated", "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return run.body();
    }

    /**
     * STU03 "Run all": up to 20 cases against the same definition in one action, each with its assertions. Counts as
     * one dry run for the rate limit; one audit event with counts only. A case whose sample request or settings do not
     * fit is reported as invalid; blockers in the workflow fail the whole call (422 WORKFLOW_INVALID). No trace is
     * returned: a case is opened step by step with {@link #simulate}.
     */
    @Transactional
    public JsonNode simulateCases(Caller caller, UUID workspaceId, UUID workflowId,
            CatalogDtos.SimulateCasesRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "simulateWorkflowCases");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "simulateWorkflowCases", false);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < request.cases().size(); i++) {
            CatalogDtos.SimulateCase c = request.cases().get(i);
            if (!ids.add(c.id())) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Every case needs its own ID.")
                        .withFieldErrors(List.of(new ApiException.FieldError("/cases/" + i + "/id", "unique",
                                "This ID is used by another case.")));
            }
            checkShape(c.input(), c.scenario(), c.assertions(), "/cases/" + i);
        }
        limiter.check(caller.subjectId());
        Source source = source(visible, workspaceId, workflowId, request.revision(), request.useActiveRelease());
        List<CaseOutcome> outcomes = new ArrayList<>();
        for (CatalogDtos.SimulateCase c : request.cases()) {
            outcomes.add(outcome(c.id(), source, c.input(), c.scenario(), c.assertions()));
        }
        int passed = (int) outcomes.stream().filter(CaseOutcome::passed).count();
        Map<String, Object> metadata = sourceMetadata(source);
        metadata.put("cases", outcomes.size());
        metadata.put("passed", passed);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.simulated", "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        ObjectNode body = json.object();
        body.set("source", source.json(json));
        body.put("passed", passed);
        body.put("total", outcomes.size());
        var results = body.putArray("results");
        outcomes.forEach(o -> results.add(o.json()));
        return body;
    }

    /** The result of one case of a suite: passed, the outcome, the assertion results, or why it could not run. */
    record CaseOutcome(String id, String status, boolean passed, JsonNode outcome, JsonNode assertions, String problem,
                       List<ApiException.FieldError> fieldErrors) {

        ObjectNode json() {
            ObjectNode o = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            o.put("id", id);
            o.put("status", status);
            o.put("passed", passed);
            if (outcome != null) {
                o.set("outcome", outcome);
            }
            if (assertions != null) {
                o.set("assertions", assertions);
            }
            if (problem != null) {
                o.put("problem", problem);
                var errors = o.putArray("fieldErrors");
                for (ApiException.FieldError e : fieldErrors) {
                    errors.addObject().put("pointer", e.pointer()).put("code", e.code()).put("message", e.message());
                }
            }
            return o;
        }
    }

    /** Runs one case for a suite. Blocking problems of the workflow are rethrown; anything else is per case. */
    CaseOutcome outcome(String id, Source source, JsonNode input, JsonNode scenario, JsonNode assertions) {
        try {
            ObjectNode body = runCase(source, input, scenario, assertions, "").body();
            JsonNode o = body.path("outcome");
            ObjectNode outcome = json.object();
            outcome.put("status", o.path("status").asString());
            outcome.set("businessOutcome", o.path("businessOutcome"));
            outcome.set("outcomeKind", o.path("outcomeKind"));
            outcome.put("summary", o.path("summary").asString());
            JsonNode checked = body.get("assertions");
            boolean passed = checked == null || checked.path("passed").asBoolean();
            return new CaseOutcome(id, o.path("status").asString(), passed, outcome, checked, null, List.of());
        } catch (ApiException e) {
            if (e.code() != ProblemCode.VALIDATION_FAILED) {
                throw e;
            }
            List<ApiException.FieldError> errors = new ArrayList<>();
            if (e.extensions().get("fieldErrors") instanceof List<?> list) {
                list.stream().filter(ApiException.FieldError.class::isInstance)
                        .map(ApiException.FieldError.class::cast).limit(20).forEach(errors::add);
            }
            return new CaseOutcome(id, "invalid", false, null, null, e.detail(), errors);
        }
    }

    /** Shape and size of one case, before anything is loaded or counted. */
    void checkShape(JsonNode input, JsonNode scenarioOrNull, JsonNode assertions, String at) {
        JsonNode scenario = scenarioOrNull == null ? json.object() : scenarioOrNull;
        if (input == null || !input.isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The sample request must be an object.")
                    .withFieldErrors(List.of(new ApiException.FieldError(at + "/input", "type",
                            "Give the answers to the request form as an object.")));
        }
        if (!scenario.isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The test settings must be an object.")
                    .withFieldErrors(List.of(new ApiException.FieldError(at + "/scenario", "type",
                            "Give the test settings as an object.")));
        }
        int bytes = json.write(input).getBytes(StandardCharsets.UTF_8).length
                + json.write(scenario).getBytes(StandardCharsets.UTF_8).length
                + (assertions == null ? 0 : json.write(assertions).getBytes(StandardCharsets.UTF_8).length);
        if (bytes > MAX_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The sample request and test settings exceed 64 KiB.");
        }
        if (Json.depth(input) > 16 || Json.depth(scenario) > 16 || (assertions != null && Json.depth(assertions) > 16)) {
            throw ApiException.malformed("The sample request or the test settings are nested too deeply.");
        }
        try {
            SimulationAssertions.validate(assertions);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The assertions are not valid.")
                    .withFieldErrors(List.of(new ApiException.FieldError(at + "/assertions", "type", e.getMessage())));
        }
    }

    /** Loads the definition to test: the given (or current) draft revision, or the active release. */
    Source source(WorkflowService.Visible visible, UUID workspaceId, UUID workflowId, Integer requestedRevision,
                  Boolean useActiveRelease) {
        String definitionJson;
        Integer revision = null;
        Integer releaseNumber = null;
        if (Boolean.TRUE.equals(useActiveRelease)) {
            if (visible.workflow().activeReleaseId() == null) {
                throw new ApiException(ProblemCode.NO_ACTIVE_RELEASE, "This workflow has not been published yet.");
            }
            CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, visible.workflow().activeReleaseId())
                    .orElseThrow(ApiException::notFound);
            definitionJson = release.definitionJson();
            releaseNumber = release.releaseNumber();
        } else {
            revision = requestedRevision == null ? visible.workflow().draftRevision() : requestedRevision;
            definitionJson = store.draft(workspaceId, workflowId, revision).orElseThrow(ApiException::notFound)
                    .definitionJson();
        }
        CompileContext workspace = contexts.create(visible.access().workspace(), definitionJson);
        Map<String, String> children = childDefinitions(workspaceId, definitionJson);
        SimulationContext context = new SimulationContext(workspace, children::get,
                LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        return new Source(definitionJson, revision, releaseNumber, context);
    }

    /**
     * The dry run itself, with the assertions evaluated over its result. Pure: no audit, no limiter, nothing stored.
     * The simulator never calls a connection, adapter, worker or agent; mocks in the scenario are answers it reads.
     */
    CaseRun runCase(Source source, JsonNode input, JsonNode scenarioOrNull, JsonNode assertions, String at) {
        JsonNode scenario = scenarioOrNull == null ? json.object() : scenarioOrNull;
        SimulationResult result;
        try {
            result = simulator.simulate(source.definitionJson(), json.write(input), json.write(scenario),
                    source.context());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The test settings are not valid.")
                    .withFieldErrors(List.of(new ApiException.FieldError(at + "/scenario", "type", e.getMessage())));
        }
        switch (result.status()) {
            case INVALID_INPUT -> throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some values are not valid.")
                    .withFieldErrors(result.inputErrors().stream()
                            .map(e -> new ApiException.FieldError(at + "/input" + e.pointer(), e.code(), e.message()))
                            .toList());
            case CANNOT_RUN -> throw new ApiException(ProblemCode.WORKFLOW_INVALID,
                    "Fix the blocking issues before testing this workflow.")
                    .with("issues", WorkflowService.issues(result.blockers()));
            default -> {
            }
        }
        ObjectNode body = (ObjectNode) json.parse(result.json());
        body.set("source", source.json(json));
        if (assertions != null && !assertions.isNull()) {
            body.set("assertions", json.parse(SimulationAssertions.evaluate(result.json(), json.write(assertions))));
        }
        return new CaseRun(body, result);
    }

    private static Map<String, Object> sourceMetadata(Source source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source.revision() != null) {
            metadata.put("revision", source.revision());
        } else {
            metadata.put("releaseNumber", source.releaseNumber());
        }
        return metadata;
    }

    /** The active release definitions of every workflow the definition starts, directly or through its children. */
    private Map<String, String> childDefinitions(UUID workspaceId, String definitionJson) {
        Map<String, String> found = new LinkedHashMap<>();
        Set<String> pending = referenced(definitionJson);
        for (int level = 0; level < CHILD_LEVELS && !pending.isEmpty() && found.size() < 25; level++) {
            Set<String> next = new LinkedHashSet<>();
            for (CatalogStore.CallableRow row : store.callable(workspaceId, pending.stream().limit(25 - found.size()).toList())) {
                if (row.definitionJson() != null && found.putIfAbsent(row.key(), row.definitionJson()) == null) {
                    next.addAll(referenced(row.definitionJson()));
                }
            }
            next.removeAll(found.keySet());
            pending = next;
        }
        return found;
    }

    private Set<String> referenced(String definitionJson) {
        Set<String> keys = new LinkedHashSet<>();
        List<JsonNode> nodes = new ArrayList<>();
        try {
            json.parse(definitionJson).path("nodes").forEach(nodes::add);
        } catch (RuntimeException e) {
            return keys;
        }
        for (JsonNode node : nodes) {
            if ("startWorkflow".equals(node.path("type").asString(null)) && node.path("config").path("workflowRef").isString()) {
                keys.add(node.path("config").path("workflowRef").asString());
            }
        }
        return keys;
    }
}
