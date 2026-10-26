package com.orqix.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
import com.orqix.catalog.internal.CatalogStore;
import com.orqix.catalog.internal.WorkflowService;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Public catalog API for authoring drafts from a complete canonical definition produced outside the studio: AI draft
 * generation (S10 section 3) and BPMN import (S11). Delegates to the catalog store and {@link WorkflowService}; the
 * returned views are serialized exactly like the catalog endpoints ({@code Workflow}, {@code WorkflowDraft},
 * {@code ValidationReport}) so the catalog DTOs stay internal to the module.
 *
 * <p>Nothing here publishes: callers create draft revision 1 and a person reviews and publishes it as usual.
 */
@Service
public class DraftAuthoring {

    /** Origin of a programmatically authored draft; recorded as {@code source} in the {@code workflow.created} audit. */
    public enum Source {
        AI("ai", "generateAiDraft"),
        IMPORT("camunda-import", "convertMigration"),
        PEGA_IMPORT("pega-manual-import", "convertMigration"),
        PEGA_XPDL_IMPORT("pega-xpdl-import", "convertMigration"),
        PEGA_BLUEPRINT_IMPORT("pega-blueprint-import", "convertMigration");

        private final String value;
        private final String operationId;

        Source(String value, String operationId) {
            this.value = value;
            this.operationId = operationId;
        }

        public String value() {
            return value;
        }

        /** OpenAPI operation used in denial metrics. */
        public String operationId() {
            return operationId;
        }
    }

    /**
     * A workflow to create from a complete definition.
     *
     * @param key           workflow key ({@code ^[a-z][a-z0-9-]{0,62}[a-z0-9]$})
     * @param name          workflow name (1-200 characters)
     * @param description   optional description (at most 2000 characters)
     * @param definition    canonical document (schemaVersion "0.1"); {@code workflowKey} and {@code name} are overwritten,
     *                      {@code uiLayout} is generated when absent or incomplete. Not modified by the call.
     * @param source        origin, recorded in the audit
     * @param makeKeyUnique when the key is taken, append "-2", "-3", ... instead of failing with DUPLICATE_KEY
     */
    public record NewDraft(String key, String name, String description, ObjectNode definition, Source source,
            boolean makeKeyUnique) {
    }

    /**
     * Created (or re-read) workflow with its draft revision.
     *
     * @param workflow OpenAPI {@code Workflow}
     * @param draft    OpenAPI {@code WorkflowDraft} of {@code revision}
     * @param report   OpenAPI {@code ValidationReport} of {@code revision}
     */
    public record Created(UUID workflowId, int revision, String key, JsonNode workflow, JsonNode draft, JsonNode report) {
    }

    /**
     * A canonical definition read for another module (for example to explain it).
     *
     * @param revision      draft revision, or null when the active release was read
     * @param releaseNumber release number, or null when a draft revision was read
     */
    public record Definition(UUID workflowId, Integer revision, Integer releaseNumber, JsonNode definition) {
    }

    private static final String KEY_PATTERN = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";
    private static final int MAX_KEY_ATTEMPTS = 50;

    private final CatalogStore store;
    private final WorkflowService workflows;
    private final ReleaseQueries releases;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final OrqixProperties properties;
    private final Json json;

    public DraftAuthoring(CatalogStore store, WorkflowService workflows, ReleaseQueries releases, PolicyService policy,
            SubjectService subjects, AuditWriter audit, OrqixProperties properties, Json json) {
        this.store = store;
        this.workflows = workflows;
        this.releases = releases;
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Creates the workflow with draft revision 1 = {@code request.definition()} in the caller's transaction (or a new
     * one). Requires {@code workflow.edit}. Audits {@code workflow.created} with {key, source}.
     *
     * @throws ApiException DUPLICATE_KEY (key taken and not {@code makeKeyUnique}), MALFORMED_REQUEST (bad key, name or
     *                      schemaVersion; too deep), PAYLOAD_TOO_LARGE (over the draft size limit)
     */
    @Transactional
    public Created createFromDefinition(Caller caller, UUID workspaceId, NewDraft request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, request.source().operationId(), false);
        if (request.key() == null || !request.key().matches(KEY_PATTERN)) {
            throw ApiException.malformed("The workflow key is not valid.");
        }
        if (request.name() == null || request.name().isBlank() || request.name().length() > 200) {
            throw ApiException.malformed("The workflow name must be 1-200 characters.");
        }
        if (request.description() != null && request.description().length() > 2000) {
            throw ApiException.malformed("The description must be at most 2000 characters.");
        }
        ObjectNode definition = request.definition().deepCopy();
        if (!"0.1".equals(definition.path("schemaVersion").asString(null))) {
            throw ApiException.malformed("Unsupported schemaVersion; expected \"0.1\".");
        }
        if (Json.depth(definition) > properties.limits().jsonMaxDepth()) {
            throw ApiException.malformed("The definition is nested deeper than " + properties.limits().jsonMaxDepth()
                    + " levels.");
        }
        UUID id = Ids.newId();
        String key = null;
        for (int attempt = 1; attempt <= (request.makeKeyUnique() ? MAX_KEY_ATTEMPTS : 1); attempt++) {
            String candidate = attempt == 1 ? request.key() : suffixed(request.key(), attempt);
            if (store.insertWorkflow(id, workspaceId, candidate, request.name(), request.description(),
                    caller.subjectId())) {
                key = candidate;
                break;
            }
        }
        if (key == null) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A workflow with this key already exists.");
        }
        definition.put("workflowKey", key);
        definition.put("name", request.name());
        ensureLayout(definition);
        String definitionJson = json.write(definition);
        if (definitionJson.getBytes(StandardCharsets.UTF_8).length > properties.limits().draftMaxBytes()) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The definition exceeds 256 KiB.");
        }
        store.insertDraft(workspaceId, id, 1, definitionJson, "0.1", caller.subjectId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("source", request.source().value());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.created",
                "workflow", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return view(caller, workspaceId, id, 1);
    }

    /** Views of an existing workflow and one of its draft revisions (used for idempotent replays). Needs workflow.read. */
    @Transactional(readOnly = true)
    public Created view(Caller caller, UUID workspaceId, UUID workflowId, int revision) {
        CatalogDtos.WorkflowView workflow = workflows.getWorkflow(caller, workspaceId, workflowId);
        CatalogStore.DraftRow row = store.draft(workspaceId, workflowId, revision).orElseThrow(ApiException::notFound);
        CatalogDtos.WorkflowDraft draft = new CatalogDtos.WorkflowDraft(row.workflowId(), row.revision(), row.savedAt(),
                subjects.ref(row.savedBy()), json.parse(row.definitionJson()));
        CatalogDtos.ValidationReport report = workflows.validateDraft(caller, workspaceId, workflowId, revision);
        return new Created(workflowId, revision, workflow.key(), json.toTree(workflow), json.toTree(draft),
                json.toTree(report));
    }

    /**
     * Canonical definition of a draft revision, or of the active release when {@code revision} is null. Needs
     * {@code workflow.read}; 404 when the workflow or revision is not visible, NO_ACTIVE_RELEASE when there is none.
     */
    @Transactional(readOnly = true)
    public Definition definition(Caller caller, UUID workspaceId, UUID workflowId, Integer revision) {
        CatalogDtos.WorkflowView workflow = workflows.getWorkflow(caller, workspaceId, workflowId);
        if (revision != null) {
            CatalogStore.DraftRow row = store.draft(workspaceId, workflowId, revision).orElseThrow(ApiException::notFound);
            return new Definition(workflowId, revision, null, json.parse(row.definitionJson()));
        }
        if (workflow.activeRelease() == null) {
            throw new ApiException(ProblemCode.NO_ACTIVE_RELEASE);
        }
        ReleaseQueries.ReleaseView release = releases.find(workspaceId, workflow.activeRelease().id())
                .orElseThrow(ApiException::notFound);
        return new Definition(workflowId, null, release.releaseNumber(), release.definition());
    }

    // ---------------------------------------------------------------- layout

    private static final int X0 = 80;
    private static final int Y0 = 200;
    private static final int COLUMN = 220;
    private static final int ROW = 160;

    /**
     * Adds {@code uiLayout} (layered left-to-right layout by longest path from the start node) when the definition
     * has none or when positions are missing for some node; positions of unknown nodes are pruned. Presentation only:
     * the semantic hash ignores {@code uiLayout}.
     */
    public static void ensureLayout(ObjectNode definition) {
        List<String> nodeIds = new ArrayList<>();
        Map<String, String> types = new HashMap<>();
        for (JsonNode node : definition.path("nodes")) {
            String id = node.path("id").asString(null);
            if (id != null && !types.containsKey(id)) {
                nodeIds.add(id);
                types.put(id, node.path("type").asString(""));
            }
        }
        JsonNode existing = definition.path("uiLayout").path("positions");
        if (existing.isObject()) {
            Set<String> known = new HashSet<>(nodeIds);
            boolean complete = nodeIds.stream().allMatch(existing::has);
            boolean clean = existing.propertyNames().stream().allMatch(known::contains);
            if (complete && clean) {
                return;
            }
            if (complete) {
                ObjectNode positions = (ObjectNode) existing;
                positions.propertyNames().stream().filter(name -> !known.contains(name)).toList()
                        .forEach(positions::remove);
                return;
            }
        }
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        nodeIds.forEach(id -> {
            outgoing.put(id, new ArrayList<>());
            incoming.put(id, new ArrayList<>());
        });
        for (JsonNode transition : definition.path("transitions")) {
            String from = transition.path("from").asString(null);
            String to = transition.path("to").asString(null);
            if (outgoing.containsKey(from) && incoming.containsKey(to) && !from.equals(to)) {
                outgoing.get(from).add(to);
                incoming.get(to).add(from);
            }
        }
        Map<String, Integer> layer = layers(nodeIds, types, outgoing, incoming);
        Map<Integer, List<String>> byLayer = new java.util.TreeMap<>();
        nodeIds.forEach(id -> byLayer.computeIfAbsent(layer.get(id), l -> new ArrayList<>()).add(id));
        Map<String, Integer> row = new HashMap<>();
        for (List<String> ids : byLayer.values()) {
            // Barycenter of already placed predecessors keeps the main path straight; ties keep document order.
            List<String> ordered = new ArrayList<>(ids);
            Map<String, Double> weight = new HashMap<>();
            for (String id : ordered) {
                weight.put(id, incoming.get(id).stream().filter(row::containsKey).mapToInt(row::get).average()
                        .orElse(0));
            }
            ordered.sort(Comparator.comparingDouble(weight::get));
            Set<Integer> used = new HashSet<>();
            for (String id : ordered) {
                int preferred = (int) Math.round(weight.get(id));
                int candidate = preferred;
                for (int delta = 0; used.contains(candidate); delta++) {
                    candidate = preferred + (delta % 2 == 0 ? delta / 2 + 1 : -(delta / 2 + 1));
                    if (candidate < 0) {
                        candidate = preferred + delta + 1;
                    }
                }
                used.add(candidate);
                row.put(id, candidate);
            }
        }
        ObjectNode layout = definition.putObject("uiLayout");
        ObjectNode positions = layout.putObject("positions");
        for (String id : nodeIds) {
            ObjectNode position = positions.putObject(id);
            position.put("x", X0 + layer.get(id) * COLUMN);
            position.put("y", Y0 + row.get(id) * ROW);
        }
        ObjectNode viewport = layout.putObject("viewport");
        viewport.put("x", 0);
        viewport.put("y", 0);
        viewport.put("zoom", 0.8);
    }

    /** Longest-path layer from the start node(s); nodes on cycles or unreachable nodes follow the last layer. */
    private static Map<String, Integer> layers(List<String> nodeIds, Map<String, String> types,
            Map<String, List<String>> outgoing, Map<String, List<String>> incoming) {
        Map<String, Integer> indegree = new HashMap<>();
        nodeIds.forEach(id -> indegree.put(id, incoming.get(id).size()));
        Deque<String> ready = new ArrayDeque<>();
        nodeIds.stream().filter(id -> "start".equals(types.get(id))).forEach(ready::add);
        nodeIds.stream().filter(id -> indegree.get(id) == 0 && !"start".equals(types.get(id))).forEach(ready::add);
        Map<String, Integer> layer = new HashMap<>();
        Set<String> done = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            if (!done.add(id)) {
                continue;
            }
            int own = layer.getOrDefault(id, 0);
            layer.put(id, own);
            for (String next : outgoing.get(id)) {
                layer.merge(next, own + 1, Math::max);
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.addLast(next);
                }
            }
        }
        int last = layer.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        for (String id : nodeIds) {
            if (!done.contains(id)) {
                layer.put(id, ++last);
            }
        }
        return layer;
    }

    private static String suffixed(String key, int attempt) {
        String suffix = "-" + attempt;
        String base = key.length() + suffix.length() > 64 ? key.substring(0, 64 - suffix.length()) : key;
        while (base.endsWith("-")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + suffix;
    }
}
