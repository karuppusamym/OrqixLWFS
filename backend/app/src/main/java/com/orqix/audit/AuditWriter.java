package com.orqix.audit;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.CorrelationId;
import com.orqix.shared.web.Sql;

/**
 * Append-only audit writer (S6 section 7). Success records are written in the command transaction, so a failed audit
 * write fails the command (TX-4). Denials are written in their own transaction because the command rolls back.
 * Metadata is whitelisted per action: anything else is dropped, so request values, reasons and comments never reach
 * the audit log.
 */
@Service
public class AuditWriter {

    public enum ActorType {
        USER("user"), SERVICE("service"), SYSTEM("system");

        private final String value;

        ActorType(String value) {
            this.value = value;
        }
    }

    public enum Outcome {
        SUCCESS("success"), DENIED("denied"), FAILED("failed");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }
    }

    /**
     * One audit record.
     *
     * @param workspaceId  null for platform-level actions
     * @param actorSubject null only for {@link ActorType#SYSTEM}
     */
    public record Entry(UUID workspaceId, ActorType actorType, UUID actorSubject, String action, String resourceType,
            String resourceId, Outcome outcome, Map<String, ?> metadata) {
    }

    private static final Map<String, Set<String>> METADATA_WHITELIST = Map.ofEntries(
            Map.entry("comment.created", Set.of("runId")),
            Map.entry("auth.login", Set.of("method")),
            Map.entry("auth.logout", Set.of("method")),
            Map.entry("auth.denied", Set.of("permission", "operationId")),
            Map.entry("admin.organization.created", Set.of("key", "isTest")),
            Map.entry("admin.workspace.created", Set.of("key", "isTest")),
            Map.entry("access.role-binding.created", Set.of("role", "principalType", "principalValue")),
            Map.entry("access.role-binding.deleted", Set.of("role", "principalType", "principalValue")),
            Map.entry("access.grant.created", Set.of("permission", "workflowId", "principalType", "principalValue")),
            Map.entry("access.grant.deleted", Set.of("permission", "workflowId", "principalType", "principalValue")),
            Map.entry("access.group.updated", Set.of("groupKey", "memberCount", "revision")),
            Map.entry("workflow.created", Set.of("key", "templateId", "source")),
            Map.entry("workflow.draft.saved", Set.of("revision")),
            Map.entry("workflow.published", Set.of("releaseNumber", "draftRevision", "semanticHash", "warningsAcknowledged")),
            Map.entry("workflow.active-release.changed", Set.of("fromReleaseNumber", "toReleaseNumber")),
            Map.entry("workflow.exported", Set.of("releaseNumber")),
            Map.entry("run.started", Set.of("workflowKey", "releaseNumber")),
            Map.entry("task.claimed", Set.of("taskId", "nodeId")),
            Map.entry("task.released", Set.of("taskId", "nodeId")),
            Map.entry("task.reassigned", Set.of("taskId", "toSubjectId")),
            Map.entry("task.decided", Set.of("taskId", "nodeId", "action", "policyFlags")),
            Map.entry("run.cancelled", Set.of()),
            Map.entry("run.retry-requested", Set.of("effectId")),
            Map.entry("effect.reconciled", Set.of("effectId", "resolution", "remoteReference")),
            Map.entry("connection.version.created", Set.of("key", "version", "definitionHash")),
            Map.entry("connection.binding.updated", Set.of("key", "host", "authType", "secretRef", "enabled", "revision")),
            Map.entry("connection.tested", Set.of("key", "operation", "outcome")),
            Map.entry("ai.draft.generated", Set.of("profile", "model", "promptVersion", "attempts", "escalated",
                    "inputTokens", "outputTokens", "costUsd", "durationMs", "outcome", "workflowId")),
            Map.entry("ai.workflow.explained", Set.of("profile", "model", "promptVersion", "revision", "releaseNumber",
                    "inputTokens", "outputTokens", "costUsd", "durationMs", "outcome")),
            Map.entry("migration.uploaded", Set.of("migrationId", "sourceSystem", "sourceSha256", "adapterVersion",
                    "elementCount", "blockerCount", "actionCount")),
            Map.entry("migration.converted", Set.of("migrationId", "workflowId", "workflowKey")),
            Map.entry("conversation.message", Set.of("stage", "version")),
            Map.entry("agent.call", Set.of("key", "operation", "profile", "model", "outcome", "toolCalls", "inputTokens",
                    "outputTokens", "costUsd", "durationMs", "effectId")));

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public AuditWriter(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Writes in the caller's transaction (a failure fails the command). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void write(Entry entry) {
        insert(entry);
    }

    /** Writes a denial in its own transaction; the denied command itself is rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeDenied(Entry entry) {
        insert(entry);
    }

    private void insert(Entry entry) {
        Set<String> allowed = METADATA_WHITELIST.get(entry.action());
        if (allowed == null) {
            throw new IllegalArgumentException("Unknown audit action " + entry.action());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (entry.metadata() != null) {
            entry.metadata().forEach((k, v) -> {
                if (allowed.contains(k) && v != null) {
                    metadata.put(k, v);
                }
            });
        }
        jdbc.sql("""
                INSERT INTO audit_event (id, workspace_id, actor_type, actor_subject_id, action, resource_type, resource_id,
                                         outcome, correlation_id, occurred_at, metadata)
                VALUES (:id, :ws, :actorType, :actor, :action, :resourceType, :resourceId, :outcome, :correlationId,
                        :at, CAST(:metadata AS jsonb))
                """)
                .param("id", Ids.newId())
                .param("ws", entry.workspaceId())
                .param("actorType", entry.actorType().value)
                .param("actor", entry.actorSubject())
                .param("action", entry.action())
                .param("resourceType", entry.resourceType())
                .param("resourceId", entry.resourceId())
                .param("outcome", entry.outcome().value)
                .param("correlationId", CorrelationId.current())
                .param("at", Sql.ts(clock.instant()))
                .param("metadata", json.write(metadata))
                .update();
    }
}
