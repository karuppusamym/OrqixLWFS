package com.orqix.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

/**
 * Connection definitions and bindings: read side used by catalog (compile context, export) and runtime (dispatch).
 * Definitions are immutable and cached; bindings are always read fresh (dispatch-time checks use current data).
 */
@Service
public class ConnectionService {

    public record DefinitionRecord(String key, int version, ConnectionDefinition definition, String definitionHash,
            Instant createdAt, UUID createdBy) {
    }

    /** Workspace binding: base URL, credential reference (never the secret) and enabled flag. */
    public record Binding(UUID workspaceId, String connectionKey, String baseUrl, String authType, String authHeaderName,
            String secretRef, boolean enabled, int revision, UUID updatedBy, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Map<String, DefinitionRecord> cache = new ConcurrentHashMap<>();

    public ConnectionService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Optional<DefinitionRecord> definition(UUID workspaceId, String key, int version) {
        String cacheKey = workspaceId + "|" + key + "|" + version;
        DefinitionRecord cached = cache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<DefinitionRecord> loaded = jdbc.sql("""
                SELECT key, version, definition::text AS definition, definition_hash, created_at, created_by
                FROM connection_definition WHERE workspace_id = :ws AND key = :key AND version = :version
                """)
                .param("ws", workspaceId).param("key", key).param("version", version)
                .query((rs, n) -> toRecord(rs.getString("key"), rs.getInt("version"), rs.getString("definition"),
                        rs.getString("definition_hash").trim(), Sql.instant(rs, "created_at"), Sql.uuid(rs, "created_by")))
                .optional();
        loaded.ifPresent(r -> cache.put(cacheKey, r));
        return loaded;
    }

    /** Every version of every connection in the workspace, ordered by key then version. */
    @Transactional(readOnly = true)
    public List<DefinitionRecord> definitions(UUID workspaceId) {
        return jdbc.sql("""
                SELECT key, version, definition::text AS definition, definition_hash, created_at, created_by
                FROM connection_definition WHERE workspace_id = :ws ORDER BY key, version
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> toRecord(rs.getString("key"), rs.getInt("version"), rs.getString("definition"),
                        rs.getString("definition_hash").trim(), Sql.instant(rs, "created_at"), Sql.uuid(rs, "created_by")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<DefinitionRecord> definitions(UUID workspaceId, String key) {
        return definitions(workspaceId).stream().filter(d -> d.key().equals(key)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Binding> binding(UUID workspaceId, String key) {
        return jdbc.sql("""
                SELECT workspace_id, connection_key, base_url, auth_type, auth_header_name, secret_ref, enabled, revision,
                       updated_by, updated_at
                FROM connection_binding WHERE workspace_id = :ws AND connection_key = :key
                """)
                .param("ws", workspaceId).param("key", key)
                .query((rs, n) -> new Binding(Sql.uuid(rs, "workspace_id"), rs.getString("connection_key"),
                        rs.getString("base_url"), rs.getString("auth_type"), rs.getString("auth_header_name"),
                        rs.getString("secret_ref"), rs.getBoolean("enabled"), rs.getInt("revision"),
                        Sql.uuid(rs, "updated_by"), Sql.instant(rs, "updated_at")))
                .optional();
    }

    @Transactional(readOnly = true)
    public List<Binding> bindings(UUID workspaceId) {
        return jdbc.sql("""
                SELECT workspace_id, connection_key, base_url, auth_type, auth_header_name, secret_ref, enabled, revision,
                       updated_by, updated_at
                FROM connection_binding WHERE workspace_id = :ws ORDER BY connection_key
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> new Binding(Sql.uuid(rs, "workspace_id"), rs.getString("connection_key"),
                        rs.getString("base_url"), rs.getString("auth_type"), rs.getString("auth_header_name"),
                        rs.getString("secret_ref"), rs.getBoolean("enabled"), rs.getInt("revision"),
                        Sql.uuid(rs, "updated_by"), Sql.instant(rs, "updated_at")))
                .list();
    }

    @Transactional(readOnly = true)
    public Set<String> enabledBindings(UUID workspaceId) {
        return Set.copyOf(jdbc.sql("SELECT connection_key FROM connection_binding WHERE workspace_id = :ws AND enabled")
                .param("ws", workspaceId).query(String.class).list());
    }

    private DefinitionRecord toRecord(String key, int version, String definition, String hash, Instant createdAt,
            UUID createdBy) {
        return new DefinitionRecord(key, version, ConnectionDefinition.parse(json.parse(definition), hash), hash,
                createdAt, createdBy);
    }
}
