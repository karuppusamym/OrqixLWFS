package com.orqix.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Effect journal (S2 section 6, S5 section 6.1). {@link #begin} and {@link #finish} run in their own transactions
 * (REQUIRES_NEW) and touch only {@code effect_journal} (TX-3), so they can never wait on a lock held by the suspended
 * job transaction, and their records survive its rollback.
 */
@Service
public class EffectJournal {

    public static final String PENDING = "pending";
    public static final String IN_FLIGHT = "inFlight";
    public static final String SUCCEEDED = "succeeded";
    public static final String RETRYABLE_FAILURE = "retryableFailure";
    public static final String PERMANENT_FAILURE = "permanentFailure";
    public static final String UNKNOWN = "unknown";
    public static final String NOT_APPLIED = "notApplied";

    public record Effect(UUID id, UUID workspaceId, UUID runId, String nodeId, int occurrence, String effectKey,
            String connectionKey, int connectionVersion, String operation, String effectClass, String requestHash,
            String state, int attempts, String leaseOwner, Instant leaseUntil, Integer lastHttpStatus,
            String lastErrorCode, String lastErrorDetail, JsonNode result, String remoteReference, JsonNode resolution,
            Instant createdAt, Instant updatedAt) {
    }

    /** Identity and request of one effect occurrence. */
    public record EffectRequest(UUID workspaceId, UUID runId, String nodeId, int occurrence, String effectKey,
            String connectionKey, int connectionVersion, String operation, String effectClass, String requestHash) {
    }

    /** Decision taken in transaction A. */
    public enum BeginAction {
        /** Call the remote system (lease acquired, attempts incremented). */
        PROCEED,
        /** Already succeeded: reuse the stored result without calling. */
        ALREADY_SUCCEEDED,
        /** Outcome unknown and unreconciled: must not run. */
        UNKNOWN,
        /** Another executor holds an unexpired lease. */
        BUSY,
        /** Same effect key with a different request body: a defect. */
        HASH_MISMATCH,
        /** Binding missing/disabled or egress denied at dispatch: marked permanentFailure. */
        POLICY_DENIED
    }

    public record BeginResult(BeginAction action, Effect effect) {
    }

    private static final String COLUMNS = """
            id, workspace_id, run_id, node_id, occurrence, effect_key, connection_key, connection_version, operation,
            effect_class, request_hash, state, attempts, lease_owner, lease_until, last_http_status, last_error_code,
            last_error_detail, result::text AS result, remote_reference, resolution::text AS resolution, created_at,
            updated_at
            """;

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public EffectJournal(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Transaction A: register the effect if absent, lock it and decide (S5 section 6.1 step 2).
     *
     * @param policyDenial null when the binding and egress checks passed; otherwise the redacted reason
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(EffectRequest request, String leaseOwner, Duration leaseDuration, String policyDenial) {
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO effect_journal (id, workspace_id, run_id, node_id, occurrence, effect_key, connection_key,
                                            connection_version, operation, effect_class, request_hash, state, attempts,
                                            created_at, updated_at)
                VALUES (:id, :ws, :run, :node, :occ, :key, :conn, :connVersion, :op, :class, :hash, 'pending', 0, :now, :now)
                ON CONFLICT (effect_key) DO NOTHING
                """)
                .param("id", Ids.newId()).param("ws", request.workspaceId()).param("run", request.runId())
                .param("node", request.nodeId()).param("occ", request.occurrence()).param("key", request.effectKey())
                .param("conn", request.connectionKey()).param("connVersion", request.connectionVersion())
                .param("op", request.operation()).param("class", request.effectClass())
                .param("hash", request.requestHash()).param("now", Sql.ts(now))
                .update();
        Effect effect = jdbc.sql("SELECT " + COLUMNS + " FROM effect_journal WHERE workspace_id = :ws AND effect_key = :key FOR UPDATE")
                .param("ws", request.workspaceId()).param("key", request.effectKey())
                .query(this::map).single();
        switch (effect.state()) {
            case SUCCEEDED -> {
                return new BeginResult(BeginAction.ALREADY_SUCCEEDED, effect);
            }
            case UNKNOWN -> {
                return new BeginResult(BeginAction.UNKNOWN, effect);
            }
            case IN_FLIGHT -> {
                if (effect.leaseUntil() != null && effect.leaseUntil().isAfter(now)) {
                    return new BeginResult(BeginAction.BUSY, effect);
                }
                if ("nonIdempotent".equals(effect.effectClass())) {
                    // A previous attempt crashed after possibly sending: never resend blindly.
                    Effect updated = update(effect.id(), request.workspaceId(), UNKNOWN, null, "LEASE_EXPIRED",
                            "A previous attempt ended without a recorded outcome", null, null, now);
                    return new BeginResult(BeginAction.UNKNOWN, updated);
                }
                // readOnly / idempotentWithKey: resend with the same key.
            }
            default -> {
                // pending, retryableFailure, permanentFailure, notApplied: continue
            }
        }
        if (!effect.requestHash().equals(request.requestHash())) {
            return new BeginResult(BeginAction.HASH_MISMATCH, effect);
        }
        if (policyDenial != null) {
            Effect updated = update(effect.id(), request.workspaceId(), PERMANENT_FAILURE, null,
                    "CONNECTION_UNAVAILABLE", policyDenial, null, null, now);
            return new BeginResult(BeginAction.POLICY_DENIED, updated);
        }
        jdbc.sql("""
                UPDATE effect_journal SET state = 'inFlight', lease_owner = :owner, lease_until = :until,
                       attempts = attempts + 1, updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("owner", leaseOwner).param("until", Sql.ts(now.plus(leaseDuration))).param("now", Sql.ts(now))
                .param("ws", request.workspaceId()).param("id", effect.id())
                .update();
        return new BeginResult(BeginAction.PROCEED, find(request.workspaceId(), effect.id()).orElseThrow());
    }

    /**
     * Agent write tools (IN04, S75): a later attempt of the same write slot may carry different model-written text. The
     * stored request is replaced only while nothing can have been delivered for the slot (pending, retryable or
     * permanent failure, or reconciled as not applied); a succeeded, in-flight or unknown entry is never touched, so
     * {@link #begin} then reuses, waits or refuses exactly as for any other effect.
     *
     * @return true when the stored request now equals {@code requestHash}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean replaceUnappliedRequest(UUID workspaceId, String effectKey, String requestHash) {
        jdbc.sql("""
                UPDATE effect_journal SET request_hash = :hash, updated_at = :now
                WHERE workspace_id = :ws AND effect_key = :key AND request_hash <> :hash
                  AND state IN ('pending', 'retryableFailure', 'permanentFailure', 'notApplied')
                """)
                .param("hash", requestHash).param("now", Sql.ts(clock.instant())).param("ws", workspaceId)
                .param("key", effectKey).update();
        return jdbc.sql("SELECT count(*) FROM effect_journal WHERE workspace_id = :ws AND effect_key = :key AND request_hash = :hash")
                .param("ws", workspaceId).param("key", effectKey).param("hash", requestHash).query(Integer.class)
                .single() > 0;
    }

    /** Transaction B: record the classified outcome of one attempt and release the lease. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Effect finish(UUID workspaceId, UUID effectId, String state, Integer httpStatus, String errorCode,
            String detail, JsonNode result, String remoteReference) {
        return update(effectId, workspaceId, state, httpStatus, errorCode, detail, result, remoteReference,
                clock.instant());
    }

    /** Operator reconciliation inside the caller's (audited) command transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Effect reconcile(UUID workspaceId, UUID effectId, String state, JsonNode result, String remoteReference,
            JsonNode resolution) {
        jdbc.sql("""
                UPDATE effect_journal SET state = :state, result = CAST(:result AS jsonb), remote_reference = :ref,
                       resolution = CAST(:resolution AS jsonb), lease_owner = NULL, lease_until = NULL, updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("state", state).param("result", result == null ? null : json.write(result))
                .param("ref", remoteReference).param("resolution", json.write(resolution))
                .param("now", Sql.ts(clock.instant())).param("ws", workspaceId).param("id", effectId)
                .update();
        return find(workspaceId, effectId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Effect> find(UUID workspaceId, UUID effectId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM effect_journal WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", effectId).query(this::map).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Effect> lock(UUID workspaceId, UUID effectId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM effect_journal WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", workspaceId).param("id", effectId).query(this::map).optional();
    }

    @Transactional(readOnly = true)
    public List<Effect> forRun(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM effect_journal WHERE workspace_id = :ws AND run_id = :run ORDER BY created_at, id")
                .param("ws", workspaceId).param("run", runId).query(this::map).list();
    }

    private Effect update(UUID effectId, UUID workspaceId, String state, Integer httpStatus, String errorCode,
            String detail, JsonNode result, String remoteReference, Instant now) {
        jdbc.sql("""
                UPDATE effect_journal SET state = :state, lease_owner = NULL, lease_until = NULL,
                       last_http_status = :status, last_error_code = :code, last_error_detail = :detail,
                       result = COALESCE(CAST(:result AS jsonb), result),
                       remote_reference = COALESCE(:ref, remote_reference), updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("state", state).param("status", httpStatus).param("code", errorCode)
                .param("detail", detail == null ? null : (detail.length() > 2000 ? detail.substring(0, 2000) : detail))
                .param("result", result == null ? null : json.write(result)).param("ref", remoteReference)
                .param("now", Sql.ts(now)).param("ws", workspaceId).param("id", effectId)
                .update();
        return find(workspaceId, effectId).orElseThrow();
    }

    private Effect map(ResultSet rs, int n) throws SQLException {
        String result = rs.getString("result");
        String resolution = rs.getString("resolution");
        return new Effect(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                rs.getString("node_id"), rs.getInt("occurrence"), rs.getString("effect_key"),
                rs.getString("connection_key"), rs.getInt("connection_version"), rs.getString("operation"),
                rs.getString("effect_class"), rs.getString("request_hash").trim(), rs.getString("state"),
                rs.getInt("attempts"), rs.getString("lease_owner"), Sql.instant(rs, "lease_until"),
                (Integer) rs.getObject("last_http_status"), rs.getString("last_error_code"),
                rs.getString("last_error_detail"), result == null ? null : json.parse(result),
                rs.getString("remote_reference"), resolution == null ? null : json.parse(resolution),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }
}
