package com.orqix.governance;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.web.Sql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * CG-I06 (docs/build/S151): verifies the audit hash chain of a workspace (or the platform chain). The database
 * recomputes every hash with the same function the insert trigger uses; this class walks the chain in order and
 * reports the first broken link: a hash that does not match the row, a previous hash that does not match the
 * previous row, a missing position, or a head that does not match the last row.
 */
@Service
public class AuditChains {

    private static final Logger LOG = LoggerFactory.getLogger(AuditChains.class);
    static final String ZERO = "0".repeat(64);
    private static final int BATCH = 2000;
    public static final String PLATFORM = "platform";

    /** The outcome of one verification. {@code reason}: hashMismatch, previousHashMismatch, missingEvent, headMismatch. */
    public record Result(String chainKey, Instant checkedAt, long checked, boolean intact, Long brokenSeq,
            UUID brokenEventId, String reason, long headSeq, String headHash) {
    }

    public record ResultList(List<Result> items) {
    }

    private record Row(long seq, UUID id, String prevHash, String hash, String expected) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Clock clock;
    private final MeterRegistry meters;

    public AuditChains(JdbcClient jdbc, PolicyService policy, AuditWriter audit, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        this.meters = meters;
    }

    /** Verifies the workspace chain now (audit.read), records the result and audits the check. */
    @Transactional
    public Result verifyWorkspace(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AUDIT_READ, "verifyAuditChain", true);
        Result result = record(verify(workspaceId.toString()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chainKey", result.chainKey());
        metadata.put("checked", result.checked());
        metadata.put("intact", result.intact());
        metadata.put("brokenSeq", result.brokenSeq());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "audit.chain-verified", "auditChain", workspaceId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return result;
    }

    /** The latest recorded checks of the workspace chain, newest first (audit.read). */
    @Transactional(readOnly = true)
    public ResultList latest(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AUDIT_READ, "listAuditChainChecks", false);
        Head head = head(workspaceId.toString());
        return new ResultList(jdbc.sql("""
                SELECT chain_key, checked_at, checked, intact, broken_seq, broken_event, reason
                FROM audit_chain_check WHERE chain_key = :key ORDER BY checked_at DESC, id DESC LIMIT 20
                """)
                .param("key", workspaceId.toString())
                .query((rs, n) -> new Result(rs.getString("chain_key"), Sql.instant(rs, "checked_at"),
                        rs.getLong("checked"), rs.getBoolean("intact"), (Long) rs.getObject("broken_seq"),
                        Sql.uuid(rs, "broken_event"), rs.getString("reason"), head.seq(), head.hash()))
                .list());
    }

    /** Verifies every chain (scheduled job); a broken chain is logged and counted, never repaired. */
    @Transactional
    public List<Result> verifyAll() {
        List<Result> results = new ArrayList<>();
        for (String key : jdbc.sql("SELECT chain_key FROM audit_chain_head ORDER BY chain_key").query(String.class)
                .list()) {
            results.add(record(verify(key)));
        }
        return results;
    }

    private Result record(Result result) {
        jdbc.sql("""
                INSERT INTO audit_chain_check (id, chain_key, checked_at, checked, intact, broken_seq, broken_event, reason)
                VALUES (:id, :key, :at, :checked, :intact, :seq, :event, :reason)
                """)
                .param("id", Ids.newId()).param("key", result.chainKey()).param("at", Sql.ts(result.checkedAt()))
                .param("checked", result.checked()).param("intact", result.intact()).param("seq", result.brokenSeq())
                .param("event", result.brokenEventId()).param("reason", result.reason()).update();
        if (!result.intact()) {
            Counter.builder("orqix_audit_chain_broken").register(meters).increment();
            LOG.error("Audit chain {} is broken at position {} ({}).", result.chainKey(), result.brokenSeq(),
                    result.reason());
        }
        return result;
    }

    private record Head(long seq, String hash) {
    }

    private Head head(String key) {
        return jdbc.sql("SELECT seq, hash FROM audit_chain_head WHERE chain_key = :key").param("key", key)
                .query((rs, n) -> new Head(rs.getLong("seq"), rs.getString("hash"))).optional()
                .orElse(new Head(0, ZERO));
    }

    /** Walks the chain {@code key} (a workspace ID or {@value #PLATFORM}) and returns the first broken link. */
    @Transactional(readOnly = true)
    public Result verify(String key) {
        Instant now = clock.instant();
        Head head = head(key);
        long expectedSeq = 1;
        String previous = ZERO;
        long checked = 0;
        while (true) {
            List<Row> rows = jdbc.sql("""
                    SELECT chain_seq, id, prev_hash, hash,
                           orqix_audit_hash(prev_hash, chain_seq, id, workspace_id, actor_type, actor_subject_id, action,
                                            resource_type, resource_id, outcome, correlation_id, occurred_at, metadata)
                               AS expected
                    FROM audit_event
                    WHERE COALESCE(workspace_id::text, 'platform') = :key AND chain_seq >= :from AND chain_seq <= :upTo
                    ORDER BY chain_seq LIMIT :limit
                    """)
                    // Events written after the head was read are left for the next check.
                    .param("key", key).param("from", expectedSeq).param("upTo", head.seq()).param("limit", BATCH)
                    .query((rs, n) -> new Row(rs.getLong("chain_seq"), Sql.uuid(rs, "id"), rs.getString("prev_hash"),
                            rs.getString("hash"), rs.getString("expected")))
                    .list();
            for (Row row : rows) {
                if (row.seq() != expectedSeq) {
                    return new Result(key, now, checked, false, expectedSeq, null, "missingEvent", head.seq(),
                            head.hash());
                }
                if (!previous.equals(row.prevHash())) {
                    return new Result(key, now, checked, false, row.seq(), row.id(), "previousHashMismatch",
                            head.seq(), head.hash());
                }
                if (!row.hash().equals(row.expected())) {
                    return new Result(key, now, checked, false, row.seq(), row.id(), "hashMismatch", head.seq(),
                            head.hash());
                }
                previous = row.hash();
                expectedSeq++;
                checked++;
            }
            if (rows.size() < BATCH) {
                break;
            }
        }
        if (head.seq() != checked || !head.hash().equals(previous)) {
            // Events after the last one found were removed, or the head was changed.
            return new Result(key, now, checked, false, checked + 1, null, "headMismatch", head.seq(), head.hash());
        }
        return new Result(key, now, checked, true, null, null, null, head.seq(), head.hash());
    }
}
