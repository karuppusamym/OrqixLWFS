package com.orqix.integration.agentwrite;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.AgentWriteTools;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.ConnectionDefinition.WriteTool;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EffectDispatcher;
import com.orqix.integration.EffectJournal;
import com.orqix.integration.adapters.AdapterRules;
import com.orqix.integration.adapters.AdapterTransport;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Approved write-capable agent tools (IN04, docs/build/S75): the approval register, the runtime gate and journaled
 * delivery, and operator reconciliation of unknown outcomes.
 *
 * <ul>
 * <li><b>Authorization.</b> A tool declared in an agent connection version, or an enabled adapter, authorizes nothing.
 * An agent may use a write tool only while an {@code active} approval exists for exactly the run's workflow key, the
 * connection key, version and definition hash, the operation and the tool name. Approvals are created and revoked with
 * the grant-only permission {@code agent.approve-write-tool} (revocation also by {@code connection.manage}); every
 * check reads the database again, so a revocation stops the next write.</li>
 * <li><b>Separation of duties.</b> Always on: the approver of a tool never triggers a write through it. A run started by
 * the approver is refused before the model is asked, again before each write, and by a database CHECK.</li>
 * <li><b>Targets.</b> Recipients come from the definition only (the model writes subject/body or title/text) and are
 * checked against the adapter's current allowlist before every send. Delivery is the local stub transport.</li>
 * <li><b>Effects.</b> Every use is its own {@code nonIdempotent} effect-journal entry with the stable key
 * {@code sha256(workspace|run|node:write:tool:n|occurrence)}. A recorded success is reused, never resent; an attempt
 * without a recorded outcome becomes {@code unknown} and is never sent again until an operator records what happened at
 * the target ({@code succeeded} or {@code notApplied}) with a reason and the version they saw. Nothing is inferred.</li>
 * <li><b>Privacy.</b> The model's arguments are never stored, logged or audited: the journal keeps the stub's
 * {@code {outcome, detail}}, the call row a SHA-256 digest and the character count.</li>
 * </ul>
 */
@Service
public class AgentWriteService implements AgentWriteTools {

    private static final Logger log = LoggerFactory.getLogger(AgentWriteService.class);

    /** The stub answers at once; the lease only has to cover the journal writes. */
    static final Duration LEASE = Duration.ofSeconds(45);

    /** Longest optional approval expiry (S85). No expiry stays the default. */
    public static final int MAX_EXPIRY_DAYS = 90;

    // ------------------------------------------------------------------ API types

    public record ApproveRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String workflowKey,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String connectionKey,
            @NotNull @Min(1) Integer connectionVersion,
            @NotBlank @Size(max = 100) String operation,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,47}$") String toolName,
            @NotNull @Size(max = 500) String reason,
            @Min(1) @Max(MAX_EXPIRY_DAYS) Integer expiresInDays) {
    }

    public record RevokeRequest(@NotNull @Size(max = 500) String reason, @NotNull @Min(1) Integer expectedVersion) {
    }

    public record ReconcileRequest(@NotNull @Pattern(regexp = "succeeded|notApplied") String outcome,
            @NotNull @Size(max = 500) String reason, @Size(max = 200) String remoteReference,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    public record SubjectRef(UUID id, String displayName) {
    }

    /** A write tool as declared in an agent connection version. */
    public record DeclaredTool(String connectionKey, int connectionVersion, String connectionName, String operation,
            String operationLabel, String toolName, String label, String description, String adapterKey, String kind,
            List<String> to, int maxCalls) {
    }

    public record Approval(UUID id, String workflowKey, String connectionKey, int connectionVersion, String operation,
            String toolName, String adapterKey, String status, SubjectRef approvedBy, Instant approvedAt,
            String approvalReason, SubjectRef revokedBy, Instant revokedAt, String revokeReason, Instant expiresAt,
            int version) {
    }

    public record Overview(List<DeclaredTool> tools, List<Approval> approvals, boolean canApprove, boolean canRevoke) {
    }

    /** One write slot; {@code state} is the effect journal state, null while not yet registered. */
    public record WriteCall(UUID id, UUID runId, String nodeId, String connectionKey, int connectionVersion,
            String operation, String toolName, int callIndex, String adapterKey, String kind, UUID approvalId,
            SubjectRef approvedBy, SubjectRef requestedBy, int argumentChars, UUID effectId, String state, int attempts,
            String lastErrorCode, String reconciledOutcome, SubjectRef reconciledBy, Instant reconciledAt,
            String reconcileReason, int version, Instant createdAt, Instant updatedAt) {
    }

    public record WriteCallList(List<WriteCall> items, boolean canReconcile) {
    }

    private record AdapterRow(String kind, JsonNode config, boolean enabled, int version) {
    }

    private final PolicyService policy;
    private final ConnectionService connections;
    private final EffectJournal journal;
    private final AdapterTransport transport;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final TransactionTemplate independentTx;

    public AgentWriteService(PolicyService policy, ConnectionService connections, EffectJournal journal,
            AdapterTransport transport, AuditWriter audit, JdbcClient jdbc, Json json, Clock clock,
            PlatformTransactionManager transactions) {
        this.policy = policy;
        this.connections = connections;
        this.journal = journal;
        this.transport = transport;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.independentTx = new TransactionTemplate(transactions);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ================================================================== approvals (API)

    /** Declared write tools and approvals: {@code connection.read}, {@code run.operate} or the approval grant. */
    @Transactional(readOnly = true)
    public Overview overview(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.CONNECTION_READ) && !access.has(Permission.RUN_OPERATE)
                && !access.has(Permission.AGENT_APPROVE_WRITE_TOOL)) {
            policy.require(access, Permission.CONNECTION_READ, "listAgentWriteTools", false);
        }
        List<DeclaredTool> tools = new ArrayList<>();
        for (ConnectionService.DefinitionRecord record : connections.definitions(workspaceId)) {
            ConnectionDefinition definition = record.definition();
            if (!definition.isAgent()) {
                continue;
            }
            for (ConnectionDefinition.Operation operation : definition.operations().values()) {
                if (operation.agent() == null) {
                    continue;
                }
                for (WriteTool tool : operation.agent().writeTools()) {
                    tools.add(new DeclaredTool(record.key(), record.version(), definition.name(), operation.name(),
                            operation.label(), tool.name(), tool.label(), tool.description(), tool.adapter(), tool.kind(),
                            tool.to(), tool.maxCalls()));
                }
            }
        }
        return new Overview(tools, approvals(workspaceId, null), access.has(Permission.AGENT_APPROVE_WRITE_TOOL),
                access.has(Permission.AGENT_APPROVE_WRITE_TOOL) || access.has(Permission.CONNECTION_MANAGE));
    }

    /** Approves one write tool for one workflow (fresh {@code agent.approve-write-tool}; reason required). */
    @Transactional
    public Approval approve(Caller caller, UUID workspaceId, ApproveRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.AGENT_APPROVE_WRITE_TOOL, "approveAgentWriteTool", true);
        String reason = request.reason().strip();
        if (reason.isEmpty()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why this agent may use the write tool.");
        }
        int workflows = jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id = :ws AND key = :key")
                .param("ws", workspaceId).param("key", request.workflowKey()).query(Integer.class).single();
        if (workflows == 0) {
            throw invalid("/workflowKey", "There is no workflow with this key in the workspace.");
        }
        ConnectionService.DefinitionRecord record = connections
                .definition(workspaceId, request.connectionKey(), request.connectionVersion())
                .orElseThrow(() -> invalid("/connectionVersion", "This connection version is not registered."));
        ConnectionDefinition.Operation operation = record.definition().isAgent()
                ? record.definition().operations().get(request.operation()) : null;
        if (operation == null || operation.agent() == null) {
            throw invalid("/operation", "This connection version has no such agent operation.");
        }
        WriteTool tool = operation.agent().writeTool(request.toolName());
        if (tool == null) {
            throw invalid("/toolName", "This agent operation declares no write tool with this name.");
        }
        UUID id = Ids.newId();
        // PostgreSQL keeps microseconds (rounding); truncating first makes the stored, returned and audited expiry the
        // same instant (S85 review: the audit used to carry the unrounded nanoseconds).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = request.expiresInDays() == null ? null : now.plus(Duration.ofDays(request.expiresInDays()));
        // An expired approval authorizes nothing; mark it so the one-active index lets the tool be approved again.
        jdbc.sql("""
                UPDATE agent_write_tool_approval SET status = 'expired', version = version + 1
                WHERE workspace_id = :ws AND workflow_key = :wf AND connection_key = :ck AND connection_version = :cv
                  AND operation = :op AND tool_name = :tool AND status = 'active' AND expires_at IS NOT NULL
                  AND expires_at <= :now
                """).param("ws", workspaceId).param("wf", request.workflowKey()).param("ck", request.connectionKey())
                .param("cv", request.connectionVersion()).param("op", request.operation()).param("tool", tool.name())
                .param("now", Sql.ts(now)).update();
        try {
            jdbc.sql("""
                    INSERT INTO agent_write_tool_approval (id, workspace_id, workflow_key, connection_key, connection_version,
                        definition_hash, operation, tool_name, adapter_key, status, approved_by, approved_at, approval_reason,
                        expires_at)
                    VALUES (:id, :ws, :wf, :ck, :cv, :hash, :op, :tool, :adapter, 'active', :by, :now, :reason, :expires)
                    """).param("id", id).param("ws", workspaceId).param("wf", request.workflowKey())
                    .param("ck", request.connectionKey()).param("cv", request.connectionVersion())
                    .param("hash", record.definitionHash()).param("op", request.operation()).param("tool", tool.name())
                    .param("adapter", tool.adapter()).param("by", caller.subjectId()).param("now", Sql.ts(now))
                    .param("reason", reason).param("expires", expiresAt == null ? null : Sql.ts(expiresAt)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY,
                    "This write tool is already approved for this workflow and connection version.");
        }
        Map<String, Object> metadata = approvalMetadata(request.workflowKey(), request.connectionKey(),
                request.connectionVersion(), request.operation(), tool.name(), tool.adapter());
        metadata.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "agent.write-tool.approved", "agentWriteToolApproval", id.toString(), AuditWriter.Outcome.SUCCESS,
                metadata));
        return approvals(workspaceId, id).get(0);
    }

    /** Revokes an approval (fresh approval grant or {@code connection.manage}; reason; version-checked). */
    @Transactional
    public Approval revoke(Caller caller, UUID workspaceId, UUID approvalId, RevokeRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        if (!access.has(Permission.CONNECTION_MANAGE)) {
            policy.require(access, Permission.AGENT_APPROVE_WRITE_TOOL, "revokeAgentWriteTool", true);
        }
        String reason = request.reason().strip();
        if (reason.isEmpty()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why the approval is revoked.");
        }
        record Current(String status, int version, String workflowKey, String connectionKey, int connectionVersion,
                String operation, String toolName, String adapterKey, Instant expiresAt) {
        }
        Current current = jdbc.sql("""
                SELECT status, version, workflow_key, connection_key, connection_version, operation, tool_name, adapter_key,
                       expires_at
                FROM agent_write_tool_approval WHERE workspace_id = :ws AND id = :id FOR UPDATE
                """).param("ws", workspaceId).param("id", approvalId)
                .query((rs, n) -> new Current(rs.getString("status"), rs.getInt("version"), rs.getString("workflow_key"),
                        rs.getString("connection_key"), rs.getInt("connection_version"), rs.getString("operation"),
                        rs.getString("tool_name"), rs.getString("adapter_key"), Sql.instant(rs, "expires_at")))
                .optional().orElseThrow(ApiException::notFound);
        if (current.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The approval changed since you loaded it (now version " + current.version() + ").");
        }
        if ("expired".equals(current.status()) || expired(current.expiresAt(), clock.instant())) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The approval has already expired; it authorizes nothing.");
        }
        if (!"active".equals(current.status())) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The approval is already revoked.");
        }
        jdbc.sql("""
                UPDATE agent_write_tool_approval SET status = 'revoked', revoked_by = :by, revoked_at = :now,
                       revoke_reason = :reason, version = version + 1
                WHERE workspace_id = :ws AND id = :id
                """).param("by", caller.subjectId()).param("now", Sql.ts(clock.instant())).param("reason", reason)
                .param("ws", workspaceId).param("id", approvalId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "agent.write-tool.revoked", "agentWriteToolApproval", approvalId.toString(), AuditWriter.Outcome.SUCCESS,
                approvalMetadata(current.workflowKey(), current.connectionKey(), current.connectionVersion(),
                        current.operation(), current.toolName(), current.adapterKey())));
        return approvals(workspaceId, approvalId).get(0);
    }

    /** An approval with an expiry authorizes nothing from that instant on. */
    static boolean expired(Instant expiresAt, Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    private static Map<String, Object> approvalMetadata(String workflowKey, String connectionKey, int connectionVersion,
            String operation, String toolName, String adapterKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowKey", workflowKey);
        metadata.put("connectionKey", connectionKey);
        metadata.put("connectionVersion", connectionVersion);
        metadata.put("operation", operation);
        metadata.put("toolName", toolName);
        metadata.put("adapterKey", adapterKey);
        return metadata;
    }

    private List<Approval> approvals(UUID workspaceId, UUID only) {
        return jdbc.sql("""
                SELECT a.id, a.workflow_key, a.connection_key, a.connection_version, a.operation, a.tool_name, a.adapter_key,
                       CASE WHEN a.status = 'active' AND a.expires_at IS NOT NULL AND a.expires_at <= :now THEN 'expired'
                            ELSE a.status END AS shown_status,
                       a.approved_by, ab.display_name AS approved_name, a.approved_at, a.approval_reason,
                       a.revoked_by, rb.display_name AS revoked_name, a.revoked_at, a.revoke_reason, a.expires_at, a.version
                FROM agent_write_tool_approval a
                JOIN subject ab ON ab.id = a.approved_by
                LEFT JOIN subject rb ON rb.id = a.revoked_by
                WHERE a.workspace_id = :ws AND (CAST(:only AS uuid) IS NULL OR a.id = CAST(:only AS uuid))
                ORDER BY shown_status, a.approved_at DESC
                LIMIT 500
                """).param("ws", workspaceId).param("only", only).param("now", Sql.ts(clock.instant()))
                .query((rs, n) -> new Approval(rs.getObject("id", UUID.class), rs.getString("workflow_key"),
                        rs.getString("connection_key"), rs.getInt("connection_version"), rs.getString("operation"),
                        rs.getString("tool_name"), rs.getString("adapter_key"), rs.getString("shown_status"),
                        new SubjectRef(rs.getObject("approved_by", UUID.class), rs.getString("approved_name")),
                        Sql.instant(rs, "approved_at"), rs.getString("approval_reason"),
                        rs.getObject("revoked_by") == null ? null
                                : new SubjectRef(rs.getObject("revoked_by", UUID.class), rs.getString("revoked_name")),
                        Sql.instant(rs, "revoked_at"), rs.getString("revoke_reason"), Sql.instant(rs, "expires_at"),
                        rs.getInt("version")))
                .list();
    }

    // ================================================================== write calls and reconciliation (API)

    /** Write slots, newest first ({@code run.operate} or the approval grant); {@code state=unknown} filters. */
    @Transactional(readOnly = true)
    public WriteCallList calls(Caller caller, UUID workspaceId, String state) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.AGENT_APPROVE_WRITE_TOOL)) {
            policy.require(access, Permission.RUN_OPERATE, "listAgentWriteCalls", false);
        }
        if (state != null && !List.of("unknown", "succeeded", "permanentFailure", "notApplied", "inFlight", "pending",
                "retryableFailure").contains(state)) {
            throw invalid("/state", "Unknown state filter.");
        }
        return new WriteCallList(calls(workspaceId, null, state), access.has(Permission.RUN_OPERATE));
    }

    /**
     * Records what an operator found at the target for an {@code unknown} write: {@code succeeded} (it was delivered;
     * reused on retry, never resent) or {@code notApplied} (it was not; an explicit operator retry of the request may
     * send it again). Fresh {@code run.operate}; reason required; {@code expectedVersion} must match. The request stays
     * where it is: retrying it remains a separate, deliberate operator action.
     */
    @Transactional
    public WriteCall reconcile(Caller caller, UUID workspaceId, UUID callId, ReconcileRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "reconcileAgentWrite", true);
        String reason = request.reason().strip();
        if (reason.isEmpty()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say what you checked at the target.");
        }
        record Row(int version, String effectKey, String reconciled) {
        }
        Row row = jdbc.sql("""
                SELECT version, effect_key, reconciled_outcome FROM agent_write_call
                WHERE workspace_id = :ws AND id = :id FOR UPDATE
                """).param("ws", workspaceId).param("id", callId)
                .query((rs, n) -> new Row(rs.getInt("version"), rs.getString("effect_key"),
                        rs.getString("reconciled_outcome")))
                .optional().orElseThrow(ApiException::notFound);
        if (row.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The write changed since you loaded it (now version " + row.version() + ").");
        }
        UUID effectId = jdbc.sql("SELECT id FROM effect_journal WHERE workspace_id = :ws AND effect_key = :key")
                .param("ws", workspaceId).param("key", row.effectKey()).query(UUID.class).optional().orElse(null);
        EffectJournal.Effect effect = effectId == null ? null : journal.lock(workspaceId, effectId).orElse(null);
        if (effect == null || !EffectJournal.UNKNOWN.equals(effect.state())) {
            throw new ApiException(ProblemCode.EFFECT_STATE_CONFLICT,
                    "Only a write whose outcome is unknown can be reconciled.");
        }
        Instant now = clock.instant();
        ObjectNode resolution = json.object();
        resolution.put("kind", "succeeded".equals(request.outcome()) ? "confirmedApplied" : "confirmedNotApplied");
        resolution.put("by", caller.subjectId().toString());
        resolution.put("at", now.toString());
        resolution.put("note", reason);
        if (request.remoteReference() != null && !request.remoteReference().isBlank()) {
            resolution.put("remoteReference", request.remoteReference().strip());
        }
        String reference = request.remoteReference() == null || request.remoteReference().isBlank() ? null
                : request.remoteReference().strip();
        if ("succeeded".equals(request.outcome())) {
            ObjectNode result = json.object();
            result.put("outcome", "confirmed");
            journal.reconcile(workspaceId, effectId, EffectJournal.SUCCEEDED, result, reference, resolution);
        } else {
            journal.reconcile(workspaceId, effectId, EffectJournal.NOT_APPLIED, null, reference, resolution);
        }
        jdbc.sql("""
                UPDATE agent_write_call SET reconciled_outcome = :outcome, reconciled_by = :by, reconciled_at = :now,
                       reconcile_reason = :reason, version = version + 1, updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """).param("outcome", request.outcome()).param("by", caller.subjectId()).param("now", Sql.ts(now))
                .param("reason", reason).param("ws", workspaceId).param("id", callId).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("effectId", effectId.toString());
        metadata.put("runId", effect.runId().toString());
        metadata.put("outcome", request.outcome());
        metadata.put("remoteReference", reference);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "agent.write.reconciled", "agentWriteCall", callId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return calls(workspaceId, callId, null).get(0);
    }

    private List<WriteCall> calls(UUID workspaceId, UUID only, String state) {
        return jdbc.sql("""
                SELECT c.id, c.run_id, c.node_id, c.connection_key, c.connection_version, c.operation, c.tool_name,
                       c.call_index, c.adapter_key, c.kind, c.approval_id, c.approved_by, ab.display_name AS approved_name,
                       c.requested_by, qb.display_name AS requested_name, c.argument_chars, e.id AS effect_id, e.state,
                       COALESCE(e.attempts, 0) AS attempts, e.last_error_code, c.reconciled_outcome, c.reconciled_by,
                       cb.display_name AS reconciled_name, c.reconciled_at, c.reconcile_reason, c.version, c.created_at,
                       c.updated_at
                FROM agent_write_call c
                JOIN subject ab ON ab.id = c.approved_by
                JOIN subject qb ON qb.id = c.requested_by
                LEFT JOIN subject cb ON cb.id = c.reconciled_by
                LEFT JOIN effect_journal e ON e.workspace_id = c.workspace_id AND e.effect_key = c.effect_key
                WHERE c.workspace_id = :ws AND (CAST(:only AS uuid) IS NULL OR c.id = CAST(:only AS uuid))
                  AND (CAST(:state AS text) IS NULL OR e.state = CAST(:state AS text))
                ORDER BY c.created_at DESC
                LIMIT 200
                """).param("ws", workspaceId).param("only", only).param("state", state)
                .query((rs, n) -> new WriteCall(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getString("node_id"), rs.getString("connection_key"), rs.getInt("connection_version"),
                        rs.getString("operation"), rs.getString("tool_name"), rs.getInt("call_index"),
                        rs.getString("adapter_key"), rs.getString("kind"), rs.getObject("approval_id", UUID.class),
                        new SubjectRef(rs.getObject("approved_by", UUID.class), rs.getString("approved_name")),
                        new SubjectRef(rs.getObject("requested_by", UUID.class), rs.getString("requested_name")),
                        rs.getInt("argument_chars"), rs.getObject("effect_id", UUID.class), rs.getString("state"),
                        rs.getInt("attempts"), rs.getString("last_error_code"), rs.getString("reconciled_outcome"),
                        rs.getObject("reconciled_by") == null ? null
                                : new SubjectRef(rs.getObject("reconciled_by", UUID.class),
                                        rs.getString("reconciled_name")),
                        Sql.instant(rs, "reconciled_at"), rs.getString("reconcile_reason"), rs.getInt("version"),
                        Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at")))
                .list();
    }

    // ================================================================== runtime port

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalFact> activeApprovals(UUID workspaceId) {
        return jdbc.sql("""
                SELECT workflow_key, connection_key, connection_version, operation, tool_name
                FROM agent_write_tool_approval WHERE workspace_id = :ws AND status = 'active'
                  AND (expires_at IS NULL OR expires_at > :now)
                """).param("ws", workspaceId).param("now", Sql.ts(clock.instant()))
                .query((rs, n) -> new ApprovalFact(rs.getString("workflow_key"), rs.getString("connection_key"),
                        rs.getInt("connection_version"), rs.getString("operation"), rs.getString("tool_name")))
                .list();
    }

    @Override
    public Authorization authorize(Scope scope, WriteTool tool) {
        record Found(UUID id, UUID approvedBy, String hash, Instant expiresAt) {
        }
        Optional<Found> found = jdbc.sql("""
                SELECT a.id, a.approved_by, a.definition_hash, a.expires_at
                FROM agent_write_tool_approval a
                JOIN workflow_run r ON r.workspace_id = a.workspace_id AND r.id = :run
                JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id AND w.key = a.workflow_key
                WHERE a.workspace_id = :ws AND a.status = 'active' AND a.connection_key = :ck
                  AND a.connection_version = :cv AND a.operation = :op AND a.tool_name = :tool
                """).param("run", scope.runId()).param("ws", scope.workspaceId())
                .param("ck", scope.definition().key()).param("cv", scope.definition().version())
                .param("op", scope.operation()).param("tool", tool.name())
                .query((rs, n) -> new Found(rs.getObject("id", UUID.class), rs.getObject("approved_by", UUID.class),
                        rs.getString("definition_hash").trim(), Sql.instant(rs, "expires_at")))
                .optional();
        if (found.isEmpty() || !found.get().hash().equals(scope.definition().definitionHash())) {
            return new Authorization(null, null, "WRITE_NOT_APPROVED");
        }
        if (expired(found.get().expiresAt(), clock.instant())) {
            // Exactly like a missing approval (fail closed), with its own visible reason.
            return new Authorization(null, null, "WRITE_APPROVAL_EXPIRED");
        }
        if (found.get().approvedBy().equals(scope.requestedBy())) {
            return new Authorization(found.get().id(), found.get().approvedBy(), "WRITE_SELF_APPROVAL");
        }
        return new Authorization(found.get().id(), found.get().approvedBy(), null);
    }

    @Override
    public WriteResult write(Scope scope, WriteTool tool, int callIndex, ObjectNode arguments) {
        Authorization authorization = authorize(scope, tool);
        if (!authorization.allowed()) {
            auditWrite(scope, tool, null, null, authorization.refusal(), 0);
            return new WriteResult(Outcome.REFUSED, null, authorization.refusal(), null);
        }
        UUID ws = scope.workspaceId();
        EffectJournal.Effect agentEffect = journal.find(ws, scope.agentEffectId())
                .orElseThrow(() -> new IllegalStateException("The agent step has no journal entry"));
        String writeNode = scope.nodeId() + ":write:" + tool.name() + ":" + callIndex;
        String effectKey = EffectDispatcher.effectKey(ws, scope.runId(), writeNode, agentEffect.occurrence());
        String argumentDigest = json.canonicalSha256(arguments);
        int argumentChars = json.write(arguments).length();

        // Targets come from the definition only; the model's arguments fill the message text.
        ObjectNode payload = json.object();
        if ("email".equals(tool.kind())) {
            ArrayNode to = payload.putArray("to");
            tool.to().forEach(to::add);
            payload.put("subject", arguments.path("subject").asString());
            payload.put("body", arguments.path("body").asString());
        } else {
            if (arguments.has("title")) {
                payload.put("title", arguments.path("title").asString());
            }
            payload.put("text", arguments.path("text").asString());
        }
        String requestHash = json.canonicalSha256(payload);

        // The slot row exists before the effect is registered, so an attempt without an outcome is always reconcilable.
        independentTx.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO agent_write_call (id, workspace_id, run_id, agent_effect_id, node_id, effect_key, connection_key,
                    connection_version, operation, tool_name, call_index, adapter_key, kind, approval_id, approved_by,
                    requested_by, argument_sha256, argument_chars, created_at, updated_at)
                VALUES (:id, :ws, :run, :agent, :node, :key, :ck, :cv, :op, :tool, :n, :adapter, :kind, :approval, :approvedBy,
                    :requestedBy, :digest, :chars, :now, :now)
                ON CONFLICT (effect_key) DO NOTHING
                """).param("id", Ids.newId()).param("ws", ws).param("run", scope.runId())
                .param("agent", scope.agentEffectId()).param("node", writeNode).param("key", effectKey)
                .param("ck", scope.definition().key()).param("cv", scope.definition().version())
                .param("op", scope.operation()).param("tool", tool.name()).param("n", callIndex)
                .param("adapter", tool.adapter()).param("kind", tool.kind()).param("approval", authorization.approvalId())
                .param("approvedBy", authorization.approvedBy()).param("requestedBy", scope.requestedBy())
                .param("digest", argumentDigest).param("chars", argumentChars).param("now", Sql.ts(clock.instant()))
                .update());
        journal.replaceUnappliedRequest(ws, effectKey, requestHash);

        AdapterRow adapter = jdbc.sql("""
                SELECT kind, config::text AS config, enabled, version FROM adapter_config WHERE workspace_id = :ws AND key = :key
                """).param("ws", ws).param("key", tool.adapter())
                .query((rs, n) -> new AdapterRow(rs.getString("kind"), json.parse(rs.getString("config")),
                        rs.getBoolean("enabled"), rs.getInt("version")))
                .optional().orElse(null);
        String denial = null;
        ObjectNode normalized = null;
        if (adapter == null) {
            denial = "The adapter " + tool.adapter() + " no longer exists";
        } else if (!adapter.enabled()) {
            denial = "The adapter " + tool.adapter() + " is disabled";
        } else if (!adapter.kind().equals(tool.kind())) {
            denial = "The adapter " + tool.adapter() + " is not a " + tool.kind() + " adapter";
        } else {
            try {
                normalized = AdapterRules.validateSend(AdapterRules.Kind.of(tool.kind()), adapter.config(), payload);
            } catch (AdapterRules.Invalid e) {
                // The pointer and rule only; the message itself is never recorded.
                denial = "The adapter's current allowlist refuses this message (" + e.pointer() + ")";
            }
        }

        EffectJournal.EffectRequest request = new EffectJournal.EffectRequest(ws, scope.runId(), writeNode,
                agentEffect.occurrence(), effectKey, tool.adapter(), adapter == null ? 1 : adapter.version(),
                "agentWrite." + tool.kind(), "nonIdempotent", requestHash);
        EffectJournal.BeginResult begin = journal.begin(request, "agent-write:" + scope.agentEffectId(), LEASE, denial);
        EffectJournal.Effect entry = begin.effect();
        switch (begin.action()) {
            case ALREADY_SUCCEEDED -> {
                auditWrite(scope, tool, entry.id(), authorization.approvalId(), "reused", argumentChars);
                return new WriteResult(Outcome.REUSED, entry.id(), null, null);
            }
            case UNKNOWN -> {
                auditWrite(scope, tool, entry.id(), authorization.approvalId(), "unknownUnreconciled", argumentChars);
                return new WriteResult(Outcome.UNKNOWN, entry.id(), "WRITE_OUTCOME_UNKNOWN", null);
            }
            case BUSY -> {
                return new WriteResult(Outcome.BUSY, entry.id(), "BUSY", null);
            }
            case HASH_MISMATCH -> {
                auditWrite(scope, tool, entry.id(), authorization.approvalId(), "requestChanged", argumentChars);
                return new WriteResult(Outcome.REFUSED, entry.id(), "WRITE_REQUEST_CHANGED",
                        "an earlier attempt of this write is still recorded with a different message.");
            }
            case POLICY_DENIED -> {
                auditWrite(scope, tool, entry.id(), authorization.approvalId(), "refusedByAdapter", argumentChars);
                return new WriteResult(Outcome.REFUSED, entry.id(), "WRITE_REFUSED", denial + ".");
            }
            case PROCEED -> {
                // continue below
            }
        }
        // This attempt's facts (a re-approval after a revocation is recorded; the argument digest of what is sent now).
        independentTx.executeWithoutResult(status -> jdbc.sql("""
                UPDATE agent_write_call SET approval_id = :approval, approved_by = :approvedBy, argument_sha256 = :digest,
                       argument_chars = :chars, version = version + 1, updated_at = :now
                WHERE workspace_id = :ws AND effect_key = :key
                """).param("approval", authorization.approvalId()).param("approvedBy", authorization.approvedBy())
                .param("digest", argumentDigest).param("chars", argumentChars).param("now", Sql.ts(clock.instant()))
                .param("ws", ws).param("key", effectKey).update());
        // Approval fence (S85): the approval row is read again under FOR SHARE in one transaction that also performs the
        // send. A revocation (which takes FOR UPDATE on the same row) that committed before this point is seen here and
        // nothing is sent; one that arrives during the send waits until the send is over and applies to the next write.
        // An expiry is evaluated at the instant the lock is taken. lock_timeout (10s) bounds how long either side waits.
        AtomicReference<AdapterTransport.Result> delivered = new AtomicReference<>();
        AtomicReference<String> fenceRefusal = new AtomicReference<>();
        AtomicBoolean sending = new AtomicBoolean();
        RuntimeException failure = null;
        ObjectNode send = normalized;
        JsonNode adapterConfig = adapter.config();
        try {
            independentTx.executeWithoutResult(status -> {
                String refusal = fence(scope, authorization.approvalId());
                if (refusal != null) {
                    fenceRefusal.set(refusal);
                    return;
                }
                sending.set(true);
                delivered.set(transport.execute(AdapterRules.Kind.of(tool.kind()), ws, tool.adapter(), adapterConfig,
                        send));
            });
        } catch (RuntimeException e) {
            failure = e;
        }
        if (delivered.get() == null && fenceRefusal.get() != null) {
            String refusal = fenceRefusal.get();
            journal.finish(ws, entry.id(), EffectJournal.PERMANENT_FAILURE, null, refusal,
                    "The approval was revoked or expired before the message was sent; nothing was sent", null, null);
            auditWrite(scope, tool, entry.id(), authorization.approvalId(), refusal, argumentChars);
            return new WriteResult(Outcome.REFUSED, entry.id(), refusal, null);
        }
        if (delivered.get() == null && !sending.get()) {
            // The fence itself failed (for example lock_timeout behind a revocation): the transport was never called.
            log.warn("Agent write approval check failed: {}",
                    failure == null ? "no result" : failure.getClass().getSimpleName());
            journal.finish(ws, entry.id(), EffectJournal.RETRYABLE_FAILURE, null, "WRITE_NOT_SENT",
                    "The approval could not be checked again; nothing was sent", null, null);
            auditWrite(scope, tool, entry.id(), authorization.approvalId(), "notSent", argumentChars);
            return new WriteResult(Outcome.REFUSED, entry.id(), "WRITE_NOT_SENT",
                    "the approval could not be checked again just before sending.");
        }
        if (delivered.get() == null) {
            // We cannot tell whether it left: unknown, never repeated automatically.
            log.warn("Agent write transport failed: {}",
                    failure == null ? "no result" : failure.getClass().getSimpleName());
            journal.finish(ws, entry.id(), EffectJournal.UNKNOWN, null, "ADAPTER_FAILED",
                    "The adapter transport failed; the outcome is unknown", null, null);
            auditWrite(scope, tool, entry.id(), authorization.approvalId(), "unknown", argumentChars);
            return new WriteResult(Outcome.UNKNOWN, entry.id(), "WRITE_OUTCOME_UNKNOWN", null);
        }
        // Delivered (even if the fence transaction failed to commit afterwards: the transport accepted the message).
        ObjectNode result = json.object();
        result.put("outcome", delivered.get().outcome());
        result.set("detail", delivered.get().detail());
        journal.finish(ws, entry.id(), EffectJournal.SUCCEEDED, null, null, null, result, null);
        auditWrite(scope, tool, entry.id(), authorization.approvalId(), "succeeded", argumentChars);
        return new WriteResult(Outcome.SUCCEEDED, entry.id(), null, null);
    }

    /**
     * Re-reads the approval that authorized this write under {@code FOR SHARE}, inside the transaction that sends. Must
     * run in a transaction; the lock is held until that transaction ends. Under READ COMMITTED a revocation committed
     * while this statement waited is seen (the row is re-read after the lock is granted).
     *
     * @return null when the same approval is still active and unexpired; otherwise the refusal code
     */
    private String fence(Scope scope, UUID approvalId) {
        record Row(String status, Instant expiresAt) {
        }
        Optional<Row> row = jdbc.sql("""
                SELECT status, expires_at FROM agent_write_tool_approval
                WHERE workspace_id = :ws AND id = :id FOR SHARE
                """).param("ws", scope.workspaceId()).param("id", approvalId)
                .query((rs, n) -> new Row(rs.getString("status"), Sql.instant(rs, "expires_at"))).optional();
        if (row.isEmpty() || "revoked".equals(row.get().status())) {
            return "WRITE_NOT_APPROVED";
        }
        if ("expired".equals(row.get().status()) || expired(row.get().expiresAt(), clock.instant())) {
            return "WRITE_APPROVAL_EXPIRED";
        }
        return "active".equals(row.get().status()) ? null : "WRITE_NOT_APPROVED";
    }

    /** System audit entry with ids, names and sizes only; survives a rollback of the job transaction. */
    private void auditWrite(Scope scope, WriteTool tool, UUID effectId, UUID approvalId, String outcome,
            int argumentChars) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", scope.runId().toString());
        metadata.put("agentEffectId", scope.agentEffectId().toString());
        metadata.put("effectId", effectId == null ? null : effectId.toString());
        metadata.put("connectionKey", scope.definition().key());
        metadata.put("connectionVersion", scope.definition().version());
        metadata.put("operation", scope.operation());
        metadata.put("toolName", tool.name());
        metadata.put("adapterKey", tool.adapter());
        metadata.put("approvalId", approvalId == null ? null : approvalId.toString());
        metadata.put("outcome", outcome);
        metadata.put("argumentChars", argumentChars);
        boolean success = "succeeded".equals(outcome) || "reused".equals(outcome);
        try {
            independentTx.executeWithoutResult(status -> audit.write(new AuditWriter.Entry(scope.workspaceId(),
                    AuditWriter.ActorType.SYSTEM, null, "agent.write", "agentWriteTool", tool.name(),
                    success ? AuditWriter.Outcome.SUCCESS
                            : outcome.startsWith("WRITE_") ? AuditWriter.Outcome.DENIED : AuditWriter.Outcome.FAILED,
                    metadata)));
        } catch (RuntimeException e) {
            log.warn("Agent write could not be audited: {}", e.getClass().getSimpleName());
        }
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }
}
