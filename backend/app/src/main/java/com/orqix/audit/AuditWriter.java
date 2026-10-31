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
            Map.entry("notification.attention", Set.of("reason")),
            Map.entry("notification.delivery", Set.of("runId","status","attempts")),
            Map.entry("run.evidence-exported", Set.of("auditCount","eventCount")),
            Map.entry("task.sla-breached", Set.of("runId","dueAt","releaseId")),
            Map.entry("comment.created", Set.of("runId")),
            Map.entry("attachment.added", Set.of("runId", "attachmentId", "sizeBytes", "contentType", "scanStatus", "staged")),
            Map.entry("attachment.removed", Set.of("runId", "attachmentId")),
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
            // S127: workspace feature switches.
            Map.entry("workspace.feature.updated", Set.of("feature", "enabled")),
            Map.entry("workflow.created", Set.of("key", "templateId", "source")),
            Map.entry("case.definition.created", Set.of("version")),
            // S126: case type drafts; the key and draft version only, never the model or its fields.
            Map.entry("case.draft.saved", Set.of("key", "version")),
            Map.entry("case.draft.deleted", Set.of("key")),
            Map.entry("case.instance.started", Set.of("definitionId", "version")),
            Map.entry("case.data.updated", Set.of("version", "fields")),
            Map.entry("case.lifecycle.changed", Set.of("action", "version")),
            Map.entry("case.planitem.changed", Set.of("action", "planItemId", "version")),
            Map.entry("case.workflow.completed", Set.of("planItemId", "runId", "version")),
            Map.entry("case.task.completed", Set.of("taskId", "version")),
            Map.entry("case.instance.terminated", Set.of("version")),
            Map.entry("view.created", Set.of("revision")),
            Map.entry("view.copied", Set.of("revision")),
            Map.entry("view.draft.saved", Set.of("revision")),
            Map.entry("view.published", Set.of("version")),
            Map.entry("view.worklist-assigned", Set.of("revision")),
            Map.entry("page.route-assigned", Set.of("slug", "revision", "version")),
            Map.entry("page.chain.run", Set.of("slug", "chainId", "version", "completed", "steps", "compensated")),
            Map.entry("task.sent-back", Set.of("taskId", "nodeId", "fields")),
            Map.entry("task.send-back.withdrawn", Set.of("taskId", "nodeId")),
            Map.entry("task.send-back.answered", Set.of("taskId", "fields")),
            Map.entry("page.draft.saved", Set.of("slug", "componentId")),
            Map.entry("extension.created", Set.of("name")),
            Map.entry("extension.version.submitted", Set.of("version", "sha256")),
            Map.entry("extension.version.decided", Set.of("version", "decision")),
            Map.entry("view.pins-migrated", Set.of("targetVersion", "items")),
            Map.entry("workflow.draft.saved", Set.of("revision", "source")),
            Map.entry("workflow.published", Set.of("releaseNumber", "draftRevision", "semanticHash", "warningsAcknowledged")),
            Map.entry("workflow.active-release.changed", Set.of("fromReleaseNumber", "toReleaseNumber")),
            Map.entry("workflow.exported", Set.of("releaseNumber", "format", "revision")),
            Map.entry("workflow.canary.set", Set.of("activeReleaseNumber", "canaryReleaseNumber", "percent")),
            Map.entry("workflow.canary.cleared", Set.of("canaryReleaseNumber")),
            Map.entry("workspace.environment.changed", Set.of("from", "to", "requireApproval")),
            Map.entry("release.promotion.requested", Set.of("releaseNumber", "targetWorkspaceId", "requestId")),
            Map.entry("release.promotion.approval-requested", Set.of("releaseNumber", "sourceWorkspaceId", "requestId", "approversNotified")),
            Map.entry("release.promotion.approved", Set.of("releaseNumber", "requestId", "requestedBy", "draftRevision")),
            Map.entry("release.promotion.rejected", Set.of("releaseNumber", "requestId", "requestedBy")),
            Map.entry("release.promotion.cancelled", Set.of("releaseNumber", "requestId")),
            Map.entry("release.promoted", Set.of("releaseNumber", "targetWorkspaceId", "targetWorkflowId", "draftRevision", "createdWorkflow", "requestId")),
            Map.entry("release.promotion.received", Set.of("releaseNumber", "sourceWorkspaceId", "targetWorkflowId", "draftRevision", "createdWorkflow", "requestId", "approvedBy")),
            Map.entry("run.started", Set.of("workflowKey", "releaseNumber")),
            Map.entry("task.claimed", Set.of("taskId", "nodeId")),
            Map.entry("task.released", Set.of("taskId", "nodeId")),
            Map.entry("task.reassigned", Set.of("taskId", "toSubjectId", "fromSubjectId")),
            Map.entry("task.delegated", Set.of("taskId", "toSubjectId", "fromSubjectId")),
            Map.entry("task.priority-changed", Set.of("taskId", "from", "to")),
            Map.entry("task.decided", Set.of("taskId", "nodeId", "action", "policyFlags")),
            Map.entry("run.cancelled", Set.of("closurePolicy","cancelledTaskIds")),
            Map.entry("task.input-corrected", Set.of("taskId", "nodeId", "fields")),
            Map.entry("externaljob.completed", Set.of("topic", "nodeId", "attempt")),
            Map.entry("externaljob.callback-registered", Set.of("nodeId", "attempt")),
            Map.entry("externaljob.failed", Set.of("topic", "nodeId", "attempt")),
            Map.entry("run.suspended", Set.of()),
            Map.entry("run.signal-sent", Set.of("signal", "delivered")),
            Map.entry("run.resumed", Set.of()),
            Map.entry("run.projection-repaired", Set.of("restored", "removed")),
            Map.entry("run.migrated", Set.of("fromRelease", "toRelease")),
            Map.entry("message.received", Set.of("name", "matched", "buffered")),
            Map.entry("run.retry-requested", Set.of("effectId")),
            Map.entry("effect.reconciled", Set.of("effectId", "resolution", "remoteReference")),
            Map.entry("connection.version.created", Set.of("key", "version", "definitionHash")),
            Map.entry("connection.binding.updated", Set.of("key", "host", "authType", "secretRef", "enabled", "revision")),
            Map.entry("connection.oauth.started", Set.of("key")),
            Map.entry("connection.oauth.connected", Set.of("key")),
            Map.entry("connection.oauth.disconnected", Set.of("key")),
            Map.entry("connection.tested", Set.of("key", "operation", "outcome")),
            Map.entry("ai.draft.generated", Set.of("profile", "model", "promptVersion", "attempts", "escalated",
                    "inputTokens", "outputTokens", "costUsd", "durationMs", "outcome", "workflowId")),
            Map.entry("ai.workflow.explained", Set.of("profile", "model", "promptVersion", "revision", "releaseNumber",
                    "inputTokens", "outputTokens", "costUsd", "durationMs", "outcome")),
            Map.entry("ai.form.suggested", Set.of("profile", "model", "promptVersion", "attempts", "inputTokens",
                    "outputTokens", "costUsd", "durationMs", "outcome", "changesSuggested", "changesRejected")),
            // Conversational authoring (STU02, docs/build/S95): counts only, never the instruction, turns or draft.
            Map.entry("ai.draft.edits.proposed", Set.of("profile", "model", "promptVersion", "attempts", "inputTokens",
                    "outputTokens", "costUsd", "durationMs", "outcome", "revision", "mode", "turns",
                    "changesSuggested", "changesRejected", "operationsSuggested", "questionsAsked")),
            // S124: clearing the saved Ask AI conversation; the text is never audited.
            Map.entry("ai.conversation.cleared", Set.of("workflowId")),
            Map.entry("migration.uploaded", Set.of("migrationId", "sourceSystem", "sourceSha256", "adapterVersion",
                    "elementCount", "blockerCount", "actionCount", "pageCount", "transcriptionSha256")),
            // Pega Blueprint PDF (docs/build/S91): ids, counts and hashes only; never page text, prompts or answers.
            Map.entry("migration.blueprint.saved", Set.of("migrationId", "revision", "transcriptionSha256", "elementCount", "openFindings")),
            Map.entry("migration.blueprint.ai.proposed", Set.of("migrationId", "profile", "model", "elementCount")),
            Map.entry("migration.project.created", Set.of("fileCount", "issueCount")),
            Map.entry("migration.converted", Set.of("migrationId", "workflowId", "workflowKey")),
            Map.entry("migration.case.converted", Set.of("migrationId", "caseDefinitionId", "caseKey", "sourceSha256")),
            Map.entry("migration.policy.changed", Set.of("required", "version")),
            Map.entry("migration.review.approved", Set.of("revision", "definitionHash", "reviewVersion")),
            Map.entry("migration.verification.acknowledged", Set.of("migrationId", "workflowId", "items", "remaining")),
            Map.entry("migration.ledger.exported", Set.of("migrationId", "format", "elements")),
            Map.entry("conversation.message", Set.of("stage", "version")),
            Map.entry("workflow.simulated", Set.of("revision", "releaseNumber", "steps", "warnings", "status", "cases", "passed")),
            Map.entry("workflow.tests.saved", Set.of("suiteVersion", "cases")),
            Map.entry("document.template.created", Set.of("version", "placeholders")),
            Map.entry("document.template.versioned", Set.of("version", "placeholders")),
            Map.entry("document.template.archived", Set.of()),
            Map.entry("document.template.restored", Set.of()),
            Map.entry("shared.decision.created", Set.of("kind", "version", "rows")),
            Map.entry("shared.decision.versioned", Set.of("kind", "version", "rows")),
            Map.entry("shared.form.created", Set.of("kind", "version", "questions")),
            Map.entry("shared.form.versioned", Set.of("kind", "version", "questions")),
            Map.entry("shared.asset.owner.changed", Set.of("kind", "from", "to")),
            Map.entry("shared.asset.archived", Set.of("kind")),
            Map.entry("shared.asset.restored", Set.of("kind")),
            Map.entry("attachment.quarantined", Set.of("runId", "attachmentId")),
            Map.entry("attachment.released", Set.of("runId", "attachmentId")),
            Map.entry("attachment.purged", Set.of("runId", "attachmentId", "retentionDays")),
            Map.entry("attachment.retention.updated", Set.of("retentionDays", "version")),
            Map.entry("attachment.ocr.requested", Set.of("runId", "attachmentId", "provider", "status")),
            Map.entry("workflow.folder.created", Set.of()),
            Map.entry("workflow.folder.renamed", Set.of()),
            Map.entry("workflow.folder.deleted", Set.of("unfiledWorkflows")),
            Map.entry("workflow.folder.assigned", Set.of("folderId")),
            Map.entry("workflow.owner.changed", Set.of("from", "to")),
            Map.entry("workflow.archived", Set.of("key", "reasonLength")),
            Map.entry("workflow.restored", Set.of("key", "reasonLength")),
            Map.entry("workflow.comment.created", Set.of("commentId", "reply", "mentionCount", "mentionsIgnored", "notified")),
            Map.entry("workflow.comment.resolved", Set.of("commentId")),
            Map.entry("workflow.comment.reopened", Set.of("commentId")),
            Map.entry("workflow.tests.run", Set.of("suiteVersion", "revision", "cases", "passed")),
            Map.entry("run.input-corrected", Set.of("fields", "version")),
            Map.entry("document.source.updated", Set.of("revision", "audienceCount", "enabled")),
            Map.entry("connection.openapi-reviewed", Set.of("operations", "selected")),
            Map.entry("adapter.created", Set.of("kind", "key")),
            Map.entry("adapter.test-sent", Set.of("kind", "key", "outcome")),
            Map.entry("inbound.trigger-created", Set.of("source", "key", "workflowId")),
            Map.entry("inbound.received", Set.of("source", "key", "sizeBytes")),
            Map.entry("inbound.event-started", Set.of("runId")),
            Map.entry("adapter.updated", Set.of("kind", "key", "version", "enabled")),
            Map.entry("inbound.trigger-updated", Set.of("source", "key", "version", "enabled")),
            Map.entry("inbound.events-purged", Set.of("olderThanDays", "includeUnstarted", "deleted")),
            // S98/S99 (EX10, IN05): ids, counts and codes only - never a token, its hash, a key or a payload value.
            Map.entry("callback.token-issued", Set.of("waitId", "runId", "nodeId", "validForSeconds")),
            Map.entry("callback.token-revoked", Set.of("waitId", "runId", "replaced")),
            Map.entry("callback.token-expired", Set.of("waitId", "runId")),
            Map.entry("callback.resumed", Set.of("waitId", "runId", "fieldCount", "channel")),
            Map.entry("callback.waits-purged", Set.of("count", "retentionSeconds")),
            Map.entry("webhook.subscription-created", Set.of("events", "enabled")),
            Map.entry("webhook.subscription-updated", Set.of("events", "enabled", "version")),
            Map.entry("webhook.subscription-deleted", Set.of("deliveries")),
            Map.entry("webhook.delivery-replayed", Set.of("subscriptionId", "eventType", "attempts")),
            Map.entry("webhook.delivery-dead", Set.of("subscriptionId", "eventType", "attempts", "error", "statusCode")),
            Map.entry("kafka.trigger-created", Set.of("key", "action", "cluster")),
            Map.entry("kafka.trigger-updated", Set.of("key", "version", "enabled")),
            Map.entry("kafka.trigger-deleted", Set.of("key", "records", "deadLetters")),
            Map.entry("kafka.record-dead", Set.of("triggerId", "partition", "offset", "error")),
            Map.entry("kafka.test-message", Set.of("triggerId", "outcome")),
            Map.entry("agent.call", Set.of("key", "operation", "profile", "model", "outcome", "toolCalls", "inputTokens",
                    "outputTokens", "costUsd", "durationMs", "effectId")),
            // IN04 (S75): ids, names and sizes only; never the model's arguments or a reason text.
            Map.entry("agent.write-tool.approved", Set.of("workflowKey", "connectionKey", "connectionVersion", "operation",
                    "toolName", "adapterKey", "expiresAt")),
            Map.entry("agent.write-tool.revoked", Set.of("workflowKey", "connectionKey", "connectionVersion", "operation",
                    "toolName", "adapterKey")),
            Map.entry("agent.write", Set.of("runId", "agentEffectId", "effectId", "connectionKey", "connectionVersion",
                    "operation", "toolName", "adapterKey", "approvalId", "outcome", "argumentChars")),
            Map.entry("agent.write.reconciled", Set.of("effectId", "runId", "outcome", "remoteReference")),
            // IN04 hardening (S85): retention of agent write records, as for files (DOC08).
            Map.entry("agent.write.retention.updated", Set.of("retentionDays", "version")),
            Map.entry("agent.write.purged", Set.of("runId", "writeId", "retentionDays")));

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
