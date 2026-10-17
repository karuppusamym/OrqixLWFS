package com.orqix.integration.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.integration.MicrosoftGraphDocuments.Failure;
import com.orqix.integration.HttpExecutor.Observation;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.node.ObjectNode;

/** Bounded durable document pins; bytes never change, retries deduplicate, expiry never removes active run pins. */
@Service
public class DocumentSnapshots {
    public static final int MAX_COUNT = 100;
    public static final long MAX_WORKSPACE_BYTES = 100L * 1024 * 1024;
    public record Snapshot(UUID id, ObjectNode metadata, byte[] content, String contentType, Instant expiresAt) { }
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final OrqixProperties properties;

    public DocumentSnapshots(JdbcClient jdbc, Json json, Clock clock, OrqixProperties properties) {
        this.jdbc = jdbc; this.json = json; this.clock = clock; this.properties = properties;
    }

    private static final String LIVE_PIN = """
            EXISTS (SELECT 1 FROM workflow_run r WHERE r.workspace_id = s.workspace_id
              AND r.lifecycle IN ('running','waiting','needsAttention')
              AND (r.input::text LIKE '%' || s.id::text || '%' OR EXISTS (
                SELECT 1 FROM effect_journal e WHERE e.run_id = r.id
                  AND e.result::text LIKE '%' || s.id::text || '%')))
            """;

    @Transactional
    public Snapshot capture(UUID ws, String key, ObjectNode metadata, byte[] bytes, String contentType) {
        if (ws == null) { throw failure("DOCUMENT_WORKSPACE_REQUIRED"); }
        // NO KEY UPDATE is compatible with FK key-share locks and serializes admissions for this workspace.
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR NO KEY UPDATE").param("ws", ws).query(UUID.class).single();
        jdbc.sql("DELETE FROM document_snapshot s WHERE s.workspace_id = :ws AND s.expires_at <= :now AND NOT (" + LIVE_PIN + ")")
                .param("ws", ws).param("now", Sql.ts(clock.instant())).update();
        String hash = Json.sha256Hex(bytes);
        var existing = jdbc.sql("""
                SELECT id FROM document_snapshot WHERE workspace_id=:ws AND connection_key=:key AND drive_id=:drive
                    AND item_id=:item AND etag=:etag AND content_sha256=:hash
                """).param("ws", ws).param("key", key).param("drive", metadata.path("driveId").asString())
                .param("item", metadata.path("itemId").asString()).param("etag", metadata.path("eTag").asString())
                .param("hash", hash).query(UUID.class).optional();
        Instant expires = clock.instant().plus(properties.idempotency().retention());
        if (existing.isPresent()) {
            jdbc.sql("UPDATE document_snapshot SET expires_at = GREATEST(expires_at,:expiry) WHERE id=:id")
                    .param("id", existing.get()).param("expiry", Sql.ts(expires)).update();
            return load(ws, key, existing.get(), metadata.path("driveId").asString(), metadata.path("itemId").asString());
        }
        var usage = jdbc.sql("SELECT count(*), COALESCE(sum(octet_length(content)),0) FROM document_snapshot WHERE workspace_id=:ws")
                .param("ws", ws).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        if (usage[0] >= MAX_COUNT || usage[1] + bytes.length > MAX_WORKSPACE_BYTES) {
            throw failure("DOCUMENT_SNAPSHOT_QUOTA_EXCEEDED");
        }
        UUID id = Ids.newId();
        ObjectNode safe = metadata.deepCopy().put("snapshotId", id.toString()).put("contentSha256", hash)
                .put("pinMode", "snapshot").put("snapshotExpiresAt", expires.toString());
        jdbc.sql("""
                INSERT INTO document_snapshot(id,workspace_id,connection_key,drive_id,item_id,etag,content_sha256,
                    content_type,metadata,content,created_at,expires_at)
                VALUES(:id,:ws,:key,:drive,:item,:etag,:hash,:type,CAST(:metadata AS jsonb),:content,:now,:expiry)
                """).param("id", id).param("ws", ws).param("key", key).param("drive", safe.path("driveId").asString())
                .param("item", safe.path("itemId").asString()).param("etag", safe.path("eTag").asString())
                .param("hash", hash).param("type", contentType).param("metadata", json.write(safe)).param("content", bytes)
                .param("now", Sql.ts(clock.instant())).param("expiry", Sql.ts(expires)).update();
        return new Snapshot(id, safe, bytes, contentType, expires);
    }

    @Transactional(readOnly = true)
    public Snapshot load(UUID ws, String key, UUID id, String drive, String item) {
        if (ws == null) { throw failure("DOCUMENT_WORKSPACE_REQUIRED"); }
        return jdbc.sql("""
                SELECT id,metadata::text,content,content_type,expires_at FROM document_snapshot s
                WHERE s.workspace_id=:ws AND s.connection_key=:key AND s.id=:id AND s.drive_id=:drive AND s.item_id=:item
                  AND (s.expires_at > :now OR
                """ + LIVE_PIN + ")").param("ws", ws).param("key", key).param("id", id).param("drive", drive).param("item", item)
                .param("now", Sql.ts(clock.instant())).query((rs, n) -> {
                    Instant expiry = Sql.instant(rs, "expires_at");
                    ObjectNode metadata = (ObjectNode) json.parse(rs.getString("metadata"));
                    metadata.put("snapshotExpiresAt", expiry.toString());
                    return new Snapshot(UUID.fromString(rs.getString("id")), metadata, rs.getBytes("content"),
                            rs.getString("content_type"), expiry);
                }).optional().orElseThrow(() -> failure("DOCUMENT_SNAPSHOT_NOT_FOUND"));
    }

    private static Failure failure(String code) { return new Failure(code, null, Observation.CLIENT_ERROR); }
}
