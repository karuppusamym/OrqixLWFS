package com.orqix.shared.idempotency;

import com.orqix.shared.web.Sql;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Idempotency-Key handling (S3 section 5), always inside the command's transaction. The caller authorizes with
 * current permissions first, then claims the key; a replay never bypasses a revocation.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final OrqixProperties properties;

    public IdempotencyService(JdbcClient jdbc, Json json, Clock clock, OrqixProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.properties = properties;
    }

    /** Result of claiming a key. {@code replay} is set when an identical earlier command completed. */
    public record Claim(UUID workspaceId, UUID principalId, String operation, String key, Replay replay) {

        public boolean isReplay() {
            return replay != null;
        }
    }

    public record Replay(String resourceType, String resourceId, int responseStatus) {
    }

    /** Validates the header value: 400 IDEMPOTENCY_KEY_REQUIRED when absent, MALFORMED_REQUEST when malformed. */
    public static String requireKey(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!KEY.matcher(headerValue).matches()) {
            throw ApiException.malformed("Idempotency-Key must be 8-128 characters of [A-Za-z0-9._:-].");
        }
        return headerValue;
    }

    /** {@code SHA-256(JCS({operation, pathParams, body}))}. */
    public String requestHash(String operation, Map<String, String> pathParams, JsonNode body) {
        ObjectNode node = json.object();
        node.put("operation", operation);
        ObjectNode params = node.putObject("pathParams");
        pathParams.forEach(params::put);
        node.set("body", body == null ? json.object() : body);
        return json.canonicalSha256(node);
    }

    public String requestHash(String operation, Map<String, String> pathParams, Object body) {
        return requestHash(operation, pathParams, body == null ? null : json.toTree(body));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(UUID workspaceId, UUID principalId, String operation, String key, String requestHash) {
        Instant now = clock.instant();
        Instant expires = now.plus(properties.idempotency().retention());
        for (int attempt = 0; attempt < 2; attempt++) {
            int inserted = jdbc.sql("""
                    INSERT INTO idempotency_record (workspace_id, principal_subject_id, operation, idem_key, request_hash,
                                                    created_at, expires_at)
                    VALUES (:ws, :principal, :operation, :key, :hash, :now, :expires)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("ws", workspaceId).param("principal", principalId).param("operation", operation)
                    .param("key", key).param("hash", requestHash)
                    .param("now", Sql.ts(now)).param("expires", Sql.ts(expires))
                    .update();
            if (inserted == 1) {
                return new Claim(workspaceId, principalId, operation, key, null);
            }
            Optional<Existing> existing = jdbc.sql("""
                    SELECT request_hash, resource_type, resource_id, response_status, expires_at
                    FROM idempotency_record
                    WHERE workspace_id = :ws AND principal_subject_id = :principal AND operation = :operation
                      AND idem_key = :key
                    FOR UPDATE
                    """)
                    .param("ws", workspaceId).param("principal", principalId).param("operation", operation)
                    .param("key", key)
                    .query((rs, n) -> new Existing(rs.getString(1).trim(), rs.getString(2), rs.getString(3),
                            (Integer) rs.getObject(4), Sql.instant(rs, "expires_at")))
                    .optional();
            if (existing.isEmpty()) {
                continue; // deleted concurrently; insert again
            }
            Existing row = existing.get();
            if (!row.expiresAt().isAfter(now)) {
                // Expired keys are ignored, not replayed (S2 section 9).
                jdbc.sql("""
                        DELETE FROM idempotency_record
                        WHERE workspace_id = :ws AND principal_subject_id = :principal AND operation = :operation
                          AND idem_key = :key
                        """)
                        .param("ws", workspaceId).param("principal", principalId).param("operation", operation)
                        .param("key", key).update();
                continue;
            }
            if (!row.requestHash().equals(requestHash)) {
                throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used with a different request.");
            }
            if (row.resourceId() == null || row.responseStatus() == null) {
                throw new IllegalStateException("Idempotency record without completion");
            }
            return new Claim(workspaceId, principalId, operation, key,
                    new Replay(row.resourceType(), row.resourceId(), row.responseStatus()));
        }
        throw new IllegalStateException("Could not claim idempotency key");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Claim claim, String resourceType, String resourceId, int responseStatus) {
        if (claim.isReplay()) {
            return;
        }
        int updated = jdbc.sql("""
                UPDATE idempotency_record SET resource_type = :type, resource_id = :id, response_status = :status
                WHERE workspace_id = :ws AND principal_subject_id = :principal AND operation = :operation
                  AND idem_key = :key
                """)
                .param("type", resourceType).param("id", resourceId).param("status", responseStatus)
                .param("ws", claim.workspaceId()).param("principal", claim.principalId())
                .param("operation", claim.operation()).param("key", claim.key())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record disappeared");
        }
    }

    private record Existing(String requestHash, String resourceType, String resourceId, Integer responseStatus,
            Instant expiresAt) {
    }
}
