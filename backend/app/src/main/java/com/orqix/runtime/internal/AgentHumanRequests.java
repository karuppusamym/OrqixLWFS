package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.OAuthVault;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tasks a durable agent raised for people (CG-M02/CG-M03, S135): a question ("ask a person"), an approval before a
 * tool runs (the proposed arguments), or an MCP elicitation. Members of the named group (with {@code task.act}) see
 * the details and answer; operators ({@code run.operate}) see that the request waits, without the details. An approval
 * is never decided by the person whose request the agent works on. The answer wakes the agent's job.
 */
@Service
public class AgentHumanRequests {

    public record HumanRequest(UUID id, UUID runId, String runReference, String nodeId, String kind, String toolName,
            String toolLabel, String groupKey, String status, Instant createdAt, JsonNode answerSchema,
            JsonNode detail, boolean canAnswer, int version) {
    }

    public record AnswerRequest(@Pattern(regexp = "approve|reject") String decision, JsonNode answer,
            @Size(max = 500) String reason, @NotNull @Min(1) Integer expectedVersion) {
    }

    private record Row(UUID id, UUID runId, String reference, String nodeId, String kind, String toolName,
            String toolLabel, String groupKey, UUID requestedBy, String status, Instant createdAt, String schema,
            String detailKey, String detailCipher, int version, UUID loopId) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final OAuthVault vault;
    private final AgentLoops loops;

    public AgentHumanRequests(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, AuditWriter audit,
            OAuthVault vault, AgentLoops loops) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.vault = vault;
        this.loops = loops;
    }

    @Transactional(readOnly = true)
    public List<HumanRequest> list(Caller caller, UUID workspaceId, String status) {
        WorkspaceAccess access = policy.access(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        boolean operator = access.has(Permission.RUN_OPERATE);
        if (!operator && !access.has(Permission.TASK_ACT)) {
            policy.require(access, Permission.TASK_ACT, "listAgentRequests", false);
        }
        String wanted = status == null || status.isBlank() ? "open" : status;
        return rows(workspaceId, null, wanted).stream()
                .filter(r -> operator || eligible(access, r))
                .map(r -> view(access, r)).toList();
    }

    @Transactional
    public HumanRequest answer(Caller caller, UUID workspaceId, UUID requestId, AnswerRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        Row row = rows(workspaceId, requestId, null).stream().findFirst().orElseThrow(ApiException::notFound);
        if (!eligible(access, row)) {
            if (!access.has(Permission.RUN_OPERATE)) {
                throw ApiException.notFound();
            }
            throw new ApiException(ProblemCode.NOT_ELIGIBLE, "Only members of " + row.groupKey()
                    + " can answer this request.");
        }
        policy.require(access, Permission.TASK_ACT, "answerAgentRequest", true);
        if (!"open".equals(row.status())) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This request was already answered or has ended.");
        }
        if (request.expectedVersion() != row.version()) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT, "The request changed. Reload and try again.");
        }
        String outcome;
        ObjectNode answer = json.object();
        if ("approval".equals(row.kind())) {
            if (request.decision() == null) {
                throw invalid("/decision", "Approve or reject the proposed call.");
            }
            if (access.subjectId().equals(row.requestedBy())) {
                throw new ApiException(ProblemCode.SEPARATION_OF_DUTIES,
                        "You started this request, so someone else must approve what the agent does for it.");
            }
            outcome = "approve".equals(request.decision()) ? "approved" : "rejected";
            if (request.reason() != null && !request.reason().isBlank()) {
                answer.put("reason", request.reason().strip());
            }
        } else {
            if (request.answer() == null || !request.answer().isObject()) {
                throw invalid("/answer", "Fill in the answer form.");
            }
            JsonNode schema = row.schema() == null ? null : json.parse(row.schema());
            if (schema != null && schema.isObject()) {
                ObjectNode copy = (ObjectNode) schema.deepCopy();
                copy.put("$schema", "https://json-schema.org/draft/2020-12/schema");
                var errors = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(copy)
                        .validate(request.answer());
                if (!errors.isEmpty()) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "The answer does not fit the form.")
                            .withFieldErrors(errors.stream().limit(10).map(e -> new ApiException.FieldError(
                                    "/answer" + (e.getInstanceLocation() == null ? "" : e.getInstanceLocation().toString()),
                                    e.getKeyword() == null ? "schema" : e.getKeyword(), e.getMessage())).toList());
                }
            }
            answer = (ObjectNode) request.answer();
            outcome = "answered";
        }
        OAuthVault.Sealed sealed = vault.seal(AgentLoops.requestContext(workspaceId, requestId, "answer"),
                json.write(answer));
        int updated = jdbc.sql("""
                UPDATE agent_human_request SET status = :status, answer_key_id = :keyId, answer_cipher = :cipher,
                    decided_by = :by, decided_at = :at, version = version + 1
                WHERE workspace_id = :ws AND id = :id AND status = 'open' AND version = :version
                """).param("status", outcome).param("keyId", sealed.keyId()).param("cipher", sealed.ciphertext())
                .param("by", access.subjectId()).param("at", Sql.ts(clock.instant())).param("ws", workspaceId)
                .param("id", requestId).param("version", row.version()).update();
        if (updated == 0) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT, "The request changed. Reload and try again.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", requestId.toString());
        metadata.put("runId", row.runId().toString());
        metadata.put("kind", row.kind());
        metadata.put("toolName", row.toolName());
        metadata.put("outcome", outcome);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, access.subjectId(),
                "agent.request.answered", "agentRequest", requestId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        loops.wake(workspaceId, row.loopId());
        return view(access, rows(workspaceId, requestId, null).get(0));
    }

    private boolean eligible(WorkspaceAccess access, Row row) {
        return access.groupKeys().contains(row.groupKey()) && access.has(Permission.TASK_ACT);
    }

    private HumanRequest view(WorkspaceAccess access, Row row) {
        boolean eligible = eligible(access, row);
        JsonNode detail = null;
        if (eligible && row.detailCipher() != null) {
            detail = json.parse(vault.open(AgentLoops.requestContext(access.workspaceId(), row.id(), "detail"),
                    row.detailKey(), row.detailCipher()));
        }
        boolean canAnswer = eligible && "open".equals(row.status())
                && !("approval".equals(row.kind()) && access.subjectId().equals(row.requestedBy()));
        return new HumanRequest(row.id(), row.runId(), row.reference(), row.nodeId(), row.kind(), row.toolName(),
                row.toolLabel(), row.groupKey(), row.status(), row.createdAt(),
                row.schema() == null ? null : json.parse(row.schema()), detail, canAnswer, row.version());
    }

    private List<Row> rows(UUID workspaceId, UUID id, String status) {
        return jdbc.sql("""
                SELECT h.id, h.run_id, r.reference, h.node_id, h.kind, h.tool_name, h.tool_label, h.group_key,
                    h.requested_by, h.status, h.created_at, h.answer_schema::text AS schema, h.detail_key_id,
                    h.detail_cipher, h.version, h.loop_id
                FROM agent_human_request h JOIN workflow_run r ON r.workspace_id = h.workspace_id AND r.id = h.run_id
                WHERE h.workspace_id = :ws AND (CAST(:id AS uuid) IS NULL OR h.id = :id)
                  AND (CAST(:status AS text) IS NULL OR h.status = :status)
                ORDER BY h.created_at DESC LIMIT 200
                """).param("ws", workspaceId).param("id", id).param("status", status)
                .query((rs, n) -> new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "run_id"), rs.getString("reference"),
                        rs.getString("node_id"), rs.getString("kind"), rs.getString("tool_name"),
                        rs.getString("tool_label"), rs.getString("group_key"), Sql.uuid(rs, "requested_by"),
                        rs.getString("status"), Sql.instant(rs, "created_at"), rs.getString("schema"),
                        rs.getString("detail_key_id"), rs.getString("detail_cipher"), rs.getInt("version"),
                        Sql.uuid(rs, "loop_id")))
                .list();
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "required", message)));
    }
}
