package com.orqix.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonValue;
import com.orqix.audit.AuditWriter;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Workspace feature switches (docs/build/S127; contracts/openapi.yaml {@code WorkspaceFeature}, {@code x-orqix-feature}).
 * A workspace administrator ({@code workspace.manage-access}) switches a product module off or on; every module is on
 * until switched. A switched-off module refuses new authoring and new starts with 409 {@code FEATURE_DISABLED}; reads
 * stay available and work already running keeps running. Read on every gated request without a cache, so a change
 * applies on all replicas at once. Each change is audited ({@code workspace.feature.updated}).
 */
@Service
public class WorkspaceFeatures {

    /** The switchable modules, in the order the API lists them. */
    public enum Feature {
        AI("ai"), CASES("cases"), MIGRATION("migration"), AGENTS("agents"), APPS("apps"), KAFKA("kafka");

        private final String value;

        Feature(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        public static Feature of(String value) {
            for (Feature f : values()) {
                if (f.value.equals(value)) {
                    return f;
                }
            }
            throw new ApiException(ProblemCode.MALFORMED_REQUEST, "Unknown feature " + value
                    + ". Use ai, cases, migration, agents, apps or kafka.");
        }
    }

    /** A gated operation: its HTTP method and contract path (below /api/v1) and the modules that must be on. */
    public record Gate(String method, String path, Set<Feature> features) {

        /** Route key with path variable names dropped, so Spring's and the contract's templates compare equal. */
        public String route() {
            return method + " " + path.replaceAll("\\{[^}]*}", "{}");
        }
    }

    private static Map.Entry<String, Gate> gate(String operationId, String method, String path, Feature... features) {
        return Map.entry(operationId, new Gate(method, path, EnumSet.copyOf(List.of(features))));
    }

    /**
     * Operations refused whole while one of their modules is off, by operationId (x-orqix-feature without
     * x-orqix-feature-when). {@code FeatureGateInterceptor} matches them by method and route before the controller runs.
     */
    public static final Map<String, Gate> GATED_OPERATIONS = Map.ofEntries(
            gate("generateAiDraft", "POST", "/workspaces/{workspaceId}/ai/drafts", Feature.AI),
            gate("explainWorkflow", "POST", "/workspaces/{workspaceId}/workflows/{workflowId}/explain", Feature.AI),
            gate("suggestFormChanges", "POST", "/workspaces/{workspaceId}/workflows/{workflowId}/ai/form-suggestions", Feature.AI),
            gate("proposeDraftEdits", "POST", "/workspaces/{workspaceId}/workflows/{workflowId}/ai/draft-edits", Feature.AI),
            gate("proposeBlueprintTranscription", "POST", "/workspaces/{workspaceId}/migrations/{migrationId}/blueprint/ai-proposal", Feature.MIGRATION, Feature.AI),
            gate("createCaseDefinition", "POST", "/workspaces/{workspaceId}/cases/definitions", Feature.CASES),
            gate("startCaseInstance", "POST", "/workspaces/{workspaceId}/cases/definitions/{definitionId}/instances", Feature.CASES),
            gate("convertPegaCaseDefinition", "POST", "/workspaces/{workspaceId}/migrations/{migrationId}/case-definition", Feature.MIGRATION, Feature.CASES),
            gate("uploadMigration", "POST", "/workspaces/{workspaceId}/migrations", Feature.MIGRATION),
            gate("convertMigration", "POST", "/workspaces/{workspaceId}/migrations/{migrationId}/convert", Feature.MIGRATION),
            gate("uploadBlueprintMigration", "POST", "/workspaces/{workspaceId}/migrations/blueprints", Feature.MIGRATION),
            gate("saveBlueprintTranscription", "PUT", "/workspaces/{workspaceId}/migrations/{migrationId}/blueprint", Feature.MIGRATION),
            gate("uploadMigrationProject", "POST", "/workspaces/{workspaceId}/migrations/projects", Feature.MIGRATION),
            gate("convertMigrationProjectNext", "POST", "/workspaces/{workspaceId}/migrations/projects/{projectId}/convert-next", Feature.MIGRATION),
            gate("approveAgentWriteTool", "POST", "/workspaces/{workspaceId}/agent-write-tools/approvals", Feature.AGENTS),
            gate("assignAppPage", "PUT", "/workspaces/{workspaceId}/app-pages/{slug}", Feature.APPS),
            gate("runAppPageChain", "POST", "/workspaces/{workspaceId}/app-pages/{slug}/chains/{chainId}/run", Feature.APPS),
            gate("createUiExtension", "POST", "/workspaces/{workspaceId}/ui-extensions", Feature.APPS),
            gate("submitUiExtensionVersion", "POST", "/workspaces/{workspaceId}/ui-extensions/{extensionId}/versions", Feature.APPS),
            gate("decideUiExtensionVersion", "POST", "/workspaces/{workspaceId}/ui-extensions/{extensionId}/versions/{version}/decision", Feature.APPS),
            gate("createKafkaTrigger", "POST", "/workspaces/{workspaceId}/kafka-triggers", Feature.KAFKA),
            gate("updateKafkaTrigger", "PUT", "/workspaces/{workspaceId}/kafka-triggers/{kafkaTriggerId}", Feature.KAFKA),
            gate("sendKafkaTestMessage", "POST", "/workspaces/{workspaceId}/kafka-triggers/{kafkaTriggerId}/test-message", Feature.KAFKA));

    /** {@link #GATED_OPERATIONS} by {@link Gate#route()}. */
    public static final Map<String, Set<Feature>> GATED_ROUTES = GATED_OPERATIONS.values().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Gate::route, Gate::features));

    /**
     * Operations refused only for one kind of resource ({@code x-orqix-feature-when}); their services call
     * {@link #require} once they know the kind: agent connections, Kafka adapters and Kafka-sourced inbound triggers.
     */
    public static final Map<String, Feature> CONDITIONAL_OPERATIONS = Map.of(
            "registerConnectionVersion", Feature.AGENTS,
            "putConnectionBinding", Feature.AGENTS,
            "testConnection", Feature.AGENTS,
            "createAdapter", Feature.KAFKA,
            "updateAdapter", Feature.KAFKA,
            "testAdapterSend", Feature.KAFKA,
            "createInboundTrigger", Feature.KAFKA,
            "updateInboundTrigger", Feature.KAFKA,
            "receiveInboundEvent", Feature.KAFKA);

    public record SubjectRef(UUID id, String displayName) {
    }

    public record State(Feature feature, boolean enabled, Instant updatedAt, SubjectRef updatedBy) {
    }

    public record StateList(List<State> items) {
    }

    public record SetRequest(@jakarta.validation.constraints.NotNull Boolean enabled) {
    }

    private static final Map<Feature, String> NAMES = new EnumMap<>(Map.of(
            Feature.AI, "AI assistance",
            Feature.CASES, "Cases",
            Feature.MIGRATION, "Migration and import",
            Feature.AGENTS, "Agents and tools",
            Feature.APPS, "Apps and extensions",
            Feature.KAFKA, "Kafka triggers and adapters"));

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Clock clock;

    public WorkspaceFeatures(JdbcClient jdbc, PolicyService policy, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** Plain name of a module, for messages. */
    public static String displayName(Feature feature) {
        return NAMES.get(feature);
    }

    /** Modules switched off in the workspace (empty when everything is on). */
    public Set<Feature> disabled(UUID workspaceId) {
        return disabled(List.of(workspaceId)).getOrDefault(workspaceId, EnumSet.noneOf(Feature.class));
    }

    /** Switched-off modules of several workspaces in one query; workspaces with everything on are absent. */
    public Map<UUID, Set<Feature>> disabled(Collection<UUID> workspaceIds) {
        Map<UUID, Set<Feature>> result = new HashMap<>();
        if (workspaceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT workspace_id, feature FROM workspace_feature WHERE workspace_id IN (:ws) AND NOT enabled")
                .param("ws", List.copyOf(workspaceIds))
                .query((rs, n) -> Map.entry(Sql.uuid(rs, "workspace_id"), Feature.of(rs.getString("feature"))))
                .list()
                .forEach(e -> result.computeIfAbsent(e.getKey(), k -> EnumSet.noneOf(Feature.class)).add(e.getValue()));
        return result;
    }

    public boolean enabled(UUID workspaceId, Feature feature) {
        return !disabled(workspaceId).contains(feature);
    }

    /** 409 FEATURE_DISABLED when the module is off. Call after the 404 visibility check. */
    public void require(UUID workspaceId, Feature feature) {
        if (!enabled(workspaceId, feature)) {
            throw refused(feature);
        }
    }

    public static ApiException refused(Feature feature) {
        return new ApiException(ProblemCode.FEATURE_DISABLED, displayName(feature)
                + " is switched off in this workspace. A workspace administrator can switch it on again in Settings,"
                + " Features. Work that is already running is not affected.").with("feature", feature.value());
    }

    /** getWorkspaceFeatures: any member (the menu needs it). */
    @Transactional(readOnly = true)
    public StateList list(Caller caller, UUID workspaceId) {
        policy.requireVisible(caller, workspaceId);
        return read(workspaceId);
    }

    /** setWorkspaceFeature: workspace.manage-access, audited; setting the current value again writes nothing. */
    @Transactional
    public StateList set(Caller caller, UUID workspaceId, String feature, boolean enabled) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, "setWorkspaceFeature", true);
        Feature parsed = Feature.of(feature);
        // Serialize changes of one workspace (two admins switching at once both get audited in order).
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", workspaceId).query(UUID.class).optional();
        boolean current = enabled(workspaceId, parsed);
        if (current != enabled) {
            jdbc.sql("""
                    INSERT INTO workspace_feature (workspace_id, feature, enabled, updated_by, updated_at)
                    VALUES (:ws, :feature, :enabled, :by, :now)
                    ON CONFLICT (workspace_id, feature) DO UPDATE
                       SET enabled = :enabled, updated_by = :by, updated_at = :now
                    """).param("ws", workspaceId).param("feature", parsed.value()).param("enabled", enabled)
                    .param("by", caller.subjectId()).param("now", Sql.ts(clock.instant())).update();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("feature", parsed.value());
            metadata.put("enabled", enabled);
            audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                    "workspace.feature.updated", "workspace", workspaceId.toString(), AuditWriter.Outcome.SUCCESS,
                    metadata));
        }
        return read(workspaceId);
    }

    private StateList read(UUID workspaceId) {
        Map<Feature, State> stored = new EnumMap<>(Feature.class);
        jdbc.sql("""
                SELECT f.feature, f.enabled, f.updated_at, f.updated_by, s.display_name
                FROM workspace_feature f LEFT JOIN subject s ON s.id = f.updated_by
                WHERE f.workspace_id = :ws
                """).param("ws", workspaceId)
                .query((rs, n) -> {
                    Feature f = Feature.of(rs.getString("feature"));
                    UUID by = Sql.uuid(rs, "updated_by");
                    return new State(f, rs.getBoolean("enabled"), Sql.instant(rs, "updated_at"),
                            by == null ? null : new SubjectRef(by, rs.getString("display_name")));
                }).list().forEach(s -> stored.put(s.feature(), s));
        List<State> items = new ArrayList<>();
        for (Feature f : Feature.values()) {
            items.add(stored.getOrDefault(f, new State(f, true, null, null)));
        }
        return new StateList(items);
    }
}
