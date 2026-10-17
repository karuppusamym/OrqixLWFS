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

    @Transactional
    public JsonNode simulate(Caller caller, UUID workspaceId, UUID workflowId, CatalogDtos.SimulateRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "simulateWorkflow");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "simulateWorkflow", false);
        JsonNode scenario = request.scenario() == null ? json.object() : request.scenario();
        if (!request.input().isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The sample request must be an object.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/input", "type",
                            "Give the answers to the request form as an object.")));
        }
        if (!scenario.isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The test settings must be an object.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/scenario", "type",
                            "Give the test settings as an object.")));
        }
        String inputText = json.write(request.input());
        String scenarioText = json.write(scenario);
        if (inputText.getBytes(StandardCharsets.UTF_8).length + scenarioText.getBytes(StandardCharsets.UTF_8).length
                > MAX_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The sample request and test settings exceed 64 KiB.");
        }
        if (Json.depth(request.input()) > 16 || Json.depth(scenario) > 16) {
            throw ApiException.malformed("The sample request or the test settings are nested too deeply.");
        }
        limiter.check(caller.subjectId());

        String definitionJson;
        Integer revision = null;
        Integer releaseNumber = null;
        if (Boolean.TRUE.equals(request.useActiveRelease())) {
            if (visible.workflow().activeReleaseId() == null) {
                throw new ApiException(ProblemCode.NO_ACTIVE_RELEASE, "This workflow has not been published yet.");
            }
            CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, visible.workflow().activeReleaseId())
                    .orElseThrow(ApiException::notFound);
            definitionJson = release.definitionJson();
            releaseNumber = release.releaseNumber();
        } else {
            revision = request.revision() == null ? visible.workflow().draftRevision() : request.revision();
            definitionJson = store.draft(workspaceId, workflowId, revision).orElseThrow(ApiException::notFound)
                    .definitionJson();
        }

        CompileContext workspace = contexts.create(visible.access().workspace(), definitionJson);
        Map<String, String> children = childDefinitions(workspaceId, definitionJson);
        SimulationContext context = new SimulationContext(workspace, children::get,
                LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        SimulationResult result;
        try {
            result = simulator.simulate(definitionJson, inputText, scenarioText, context);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The test settings are not valid.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/scenario", "type", e.getMessage())));
        }
        switch (result.status()) {
            case INVALID_INPUT -> throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some values are not valid.")
                    .withFieldErrors(result.inputErrors().stream()
                            .map(e -> new ApiException.FieldError("/input" + e.pointer(), e.code(), e.message()))
                            .toList());
            case CANNOT_RUN -> throw new ApiException(ProblemCode.WORKFLOW_INVALID,
                    "Fix the blocking issues before testing this workflow.")
                    .with("issues", WorkflowService.issues(result.blockers()));
            default -> {
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (revision != null) {
            metadata.put("revision", revision);
        } else {
            metadata.put("releaseNumber", releaseNumber);
        }
        metadata.put("steps", result.steps());
        metadata.put("warnings", result.warnings());
        metadata.put("status", result.status().apiValue());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.simulated", "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS, metadata));

        ObjectNode body = (ObjectNode) json.parse(result.json());
        ObjectNode source = json.object();
        if (revision != null) {
            source.put("revision", revision);
            source.putNull("releaseNumber");
        } else {
            source.putNull("revision");
            source.put("releaseNumber", releaseNumber);
        }
        body.set("source", source);
        return body;
    }

    /** Suite execution supplies its own transaction and records expected validation failures per case. */
    public JsonNode simulateSavedCase(Caller caller, UUID workspaceId, UUID workflowId,
            CatalogDtos.SimulateRequest request) {
        return simulate(caller, workspaceId, workflowId, request);
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
