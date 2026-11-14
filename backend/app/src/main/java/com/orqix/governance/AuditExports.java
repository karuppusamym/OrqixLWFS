package com.orqix.governance;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ProtectionKeys;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-I06 (docs/build/S151): signed audit exports. An export is one contiguous segment of a workspace's audit chain
 * (events after {@code afterSeq}, at most {@value #MAX} per export) as a compact JWS (ES256) whose payload holds every
 * stored column, including the chain positions and hashes. A verifier checks the signature with the published key
 * ({@link #signingKeys}), recomputes each hash and links segments through {@code firstPrevHash}. Needs audit.read,
 * the permission that already reads audit data in evidence packs.
 */
@Service
public class AuditExports {

    public static final int MAX = 5000;
    public static final String FORMAT = "orqix.audit-export.v1";

    public record Export(String format, UUID workspaceId, long firstSeq, long lastSeq, int eventCount,
            String firstPrevHash, String lastHash, Long nextAfterSeq, String keyId, String algorithm, String jws) {
    }

    public record SigningKeys(List<Map<String, Object>> keys) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final ProtectionKeys keys;
    private final Json json;
    private final Clock clock;

    public AuditExports(JdbcClient jdbc, PolicyService policy, AuditWriter audit, ProtectionKeys keys, Json json,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    /** The public half of the current signing key as a JWK set (empty when signing is not configured). */
    public SigningKeys signingKeys(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AUDIT_READ, "listAuditSigningKeys", false);
        return new SigningKeys(keys.auditSigningKey().map(k -> List.of(k.toPublicJWK().toJSONObject()))
                .orElse(List.of()));
    }

    @Transactional
    public Export export(Caller caller, UUID workspaceId, long afterSeq, Integer limit) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AUDIT_READ, "exportAuditLog", true);
        if (afterSeq < 0) {
            throw ApiException.malformed("afterSeq must be 0 or more.");
        }
        int size = limit == null ? MAX : limit;
        if (size < 1 || size > MAX) {
            throw ApiException.malformed("limit must be between 1 and " + MAX + ".");
        }
        ECKey key = keys.auditSigningKey().orElseThrow(() -> new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE,
                "Signed audit exports are not available: no signing key is configured."));
        ArrayNode events = json.object().arrayNode();
        long[] first = {0};
        long[] last = {0};
        String[] firstPrev = {null};
        String[] lastHash = {null};
        int[] count = {0};
        boolean[] more = {false};
        jdbc.sql("""
                SELECT id, chain_seq, prev_hash, hash, workspace_id, actor_type, actor_subject_id, action, resource_type,
                       resource_id, outcome, correlation_id,
                       to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS occurred_at,
                       metadata::text AS metadata
                FROM audit_event
                WHERE workspace_id = :ws AND chain_seq > :after
                ORDER BY chain_seq LIMIT :limit
                """)
                .param("ws", workspaceId).param("after", afterSeq).param("limit", size + 1)
                .query(rs -> {
                    if (count[0] == size) {
                        more[0] = true;
                        return;
                    }
                    ObjectNode e = events.addObject();
                    long seq = rs.getLong("chain_seq");
                    e.put("chainSeq", seq);
                    e.put("prevHash", rs.getString("prev_hash"));
                    e.put("hash", rs.getString("hash"));
                    e.put("id", rs.getString("id"));
                    e.put("workspaceId", rs.getString("workspace_id"));
                    e.put("actorType", rs.getString("actor_type"));
                    e.put("actorSubjectId", rs.getString("actor_subject_id"));
                    e.put("action", rs.getString("action"));
                    e.put("resourceType", rs.getString("resource_type"));
                    e.put("resourceId", rs.getString("resource_id"));
                    e.put("outcome", rs.getString("outcome"));
                    e.put("correlationId", rs.getString("correlation_id"));
                    e.put("occurredAt", rs.getString("occurred_at"));
                    // The exact stored text, so a verifier can recompute the hash byte for byte.
                    e.put("metadata", rs.getString("metadata"));
                    if (count[0] == 0) {
                        first[0] = seq;
                        firstPrev[0] = rs.getString("prev_hash");
                    }
                    last[0] = seq;
                    lastHash[0] = rs.getString("hash");
                    count[0]++;
                });
        Instant now = clock.instant();
        ObjectNode payload = json.object();
        payload.put("format", FORMAT);
        payload.put("workspaceId", workspaceId.toString());
        payload.put("exportedAt", now.toString());
        payload.put("exportedBy", caller.subjectId().toString());
        payload.put("afterSeq", afterSeq);
        payload.put("firstSeq", first[0]);
        payload.put("lastSeq", last[0]);
        payload.put("firstPrevHash", firstPrev[0]);
        payload.put("lastHash", lastHash[0]);
        payload.put("hashAlgorithm", "sha256 over length-prefixed fields (docs/build/S151)");
        payload.set("events", events);
        String jws;
        try {
            JWSObject object = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID())
                    .type(new JOSEObjectType("orqix-audit-export+jws")).build(), new Payload(json.write(payload)));
            object.sign(new ECDSASigner(key));
            jws = object.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Audit export signing failed", e);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("from", first[0]);
        metadata.put("to", last[0]);
        metadata.put("eventCount", count[0]);
        metadata.put("keyId", key.getKeyID());
        metadata.put("headHash", lastHash[0]);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "audit.exported", "auditChain", workspaceId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new Export(FORMAT, workspaceId, first[0], last[0], count[0], firstPrev[0], lastHash[0],
                more[0] ? last[0] : null, key.getKeyID(), "ES256", jws);
    }
}
