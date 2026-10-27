package com.orqix.integration.adapters;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.EgressPolicy;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Outbound adapter definitions and the local-stub test send (S47). Reading needs {@code connection.read}; creating and
 * sending need a fresh {@code connection.manage}. Secrets are referenced by name only. Nothing here can reach a remote
 * system: {@link AdapterTransport} is the local stub.
 */
@Service
public class AdapterService {

    public record CreateRequest(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String key,
            @NotNull @Pattern(regexp = "email|teams|jdbc|kafka") String kind,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef,
            @NotNull JsonNode config) {
    }

    public record SendRequest(@NotNull JsonNode payload) {
    }

    /** Kind and key never change; {@code expectedVersion} is the version the editor loaded (S73). */
    public record UpdateRequest(@NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef,
            @NotNull JsonNode config, @NotNull Boolean enabled, @NotNull @Min(1) Integer expectedVersion) {
    }

    public record View(UUID id, String key, String kind, String name, String secretRef, JsonNode config, boolean enabled,
            int version, Instant createdAt, Instant updatedAt) {
    }

    public record ViewList(List<View> items) {
    }

    public record SendResult(String adapter, String kind, String outcome, JsonNode detail) {
    }

    private final PolicyService policy;
    private final EgressPolicy egress;
    private final AdapterTransport transport;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public AdapterService(PolicyService policy, EgressPolicy egress, AdapterTransport transport, AuditWriter audit,
            JdbcClient jdbc, Json json, Clock clock) {
        this.policy = policy;
        this.egress = egress;
        this.transport = transport;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ViewList list(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_READ, "listAdapters", false);
        boolean manager = access.has(Permission.CONNECTION_MANAGE);
        return new ViewList(jdbc.sql("""
                SELECT id, key, kind, name, secret_ref, config::text AS config, enabled, version, created_at, updated_at
                FROM adapter_config WHERE workspace_id = :ws ORDER BY key
                """).param("ws", workspaceId).query((rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("key"),
                rs.getString("kind"), rs.getString("name"), manager ? rs.getString("secret_ref") : null,
                json.parse(rs.getString("config")), rs.getBoolean("enabled"), rs.getInt("version"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"))).list());
    }

    @Transactional
    public View create(Caller caller, UUID workspaceId, CreateRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "createAdapter", true);
        com.orqix.integration.SecretNamespaces.refuseReserved(request.secretRef(), "/secretRef");
        AdapterRules.Kind kind = AdapterRules.Kind.of(request.kind());
        ObjectNode config;
        try {
            config = AdapterRules.validateConfig(kind, request.config(), this::checkEgress);
        } catch (AdapterRules.Invalid e) {
            throw invalid(e);
        }
        if (request.secretRef() == null) {
            throw invalid(new AdapterRules.Invalid("/secretRef", "Name the secret that holds the credentials or address."));
        }
        UUID id = Ids.newId();
        Instant now = clock.instant();
        try {
            jdbc.sql("""
                    INSERT INTO adapter_config (id, workspace_id, key, kind, name, secret_ref, config, enabled, created_by, created_at)
                    VALUES (:id, :ws, :key, :kind, :name, :ref, CAST(:config AS jsonb), true, :by, :now)
                    """).param("id", id).param("ws", workspaceId).param("key", request.key()).param("kind", kind.value())
                    .param("name", request.name().strip()).param("ref", request.secretRef()).param("config", json.write(config))
                    .param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "An adapter with this key already exists.");
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "adapter.created",
                "adapter", request.key(), AuditWriter.Outcome.SUCCESS, Map.of("kind", kind.value(), "key", request.key())));
        return new View(id, request.key(), kind.value(), request.name().strip(), request.secretRef(), config, true, 1, now,
                null);
    }

    /**
     * Edits or disables an adapter (S73). The configuration is validated exactly like on creation. Releases are not
     * re-checked here: every later send reads the adapter again, so a narrowed allowlist or a disabled adapter stops the
     * sends it no longer allows (the request then needs attention), and a widened allowlist adds no recipient or topic
     * because those are fixed in each step.
     */
    @Transactional
    public View update(Caller caller, UUID workspaceId, String key, UpdateRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "updateAdapter", true);
        com.orqix.integration.SecretNamespaces.refuseReserved(request.secretRef(), "/secretRef");
        record Current(UUID id, String kind, int version, Instant createdAt) {
        }
        Current current = jdbc.sql("""
                SELECT id, kind, version, created_at FROM adapter_config WHERE workspace_id = :ws AND key = :key FOR UPDATE
                """).param("ws", workspaceId).param("key", key)
                .query((rs, n) -> new Current(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getInt("version"),
                        Sql.instant(rs, "created_at"))).optional().orElseThrow(ApiException::notFound);
        if (current.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The adapter changed since you loaded it (now version " + current.version() + ").");
        }
        AdapterRules.Kind kind = AdapterRules.Kind.of(current.kind());
        ObjectNode config;
        try {
            config = AdapterRules.validateConfig(kind, request.config(), this::checkEgress);
        } catch (AdapterRules.Invalid e) {
            throw invalid(e);
        }
        Instant now = clock.instant();
        int version = current.version() + 1;
        jdbc.sql("""
                UPDATE adapter_config SET name = :name, secret_ref = :ref, config = CAST(:config AS jsonb), enabled = :enabled,
                       version = :version, updated_by = :by, updated_at = :now
                WHERE id = :id AND workspace_id = :ws
                """).param("name", request.name().strip()).param("ref", request.secretRef())
                .param("config", json.write(config)).param("enabled", request.enabled()).param("version", version)
                .param("by", caller.subjectId()).param("now", Sql.ts(now)).param("id", current.id()).param("ws", workspaceId)
                .update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "adapter.updated",
                "adapter", key, AuditWriter.Outcome.SUCCESS, Map.of("kind", kind.value(), "key", key, "version", version,
                        "enabled", request.enabled())));
        return new View(current.id(), key, kind.value(), request.name().strip(), request.secretRef(), config,
                request.enabled(), version, current.createdAt(), now);
    }

    /** Validates a request against the stored rules and hands it to the local stub; never contacts a remote system. */
    @Transactional
    public SendResult testSend(Caller caller, UUID workspaceId, String key, SendRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "testAdapterSend", true);
        var row = jdbc.sql("SELECT kind, config::text AS config, enabled FROM adapter_config WHERE workspace_id = :ws AND key = :key")
                .param("ws", workspaceId).param("key", key)
                .query((rs, n) -> Map.entry(rs.getString("kind") + "|" + rs.getBoolean("enabled"), rs.getString("config")))
                .optional().orElseThrow(ApiException::notFound);
        String[] head = row.getKey().split("\\|");
        if (!Boolean.parseBoolean(head[1])) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The adapter is disabled.");
        }
        AdapterRules.Kind kind = AdapterRules.Kind.of(head[0]);
        JsonNode config = json.parse(row.getValue());
        ObjectNode payload;
        try {
            payload = AdapterRules.validateSend(kind, config, request.payload());
        } catch (AdapterRules.Invalid e) {
            throw invalid(e);
        }
        AdapterTransport.Result result = transport.execute(kind, workspaceId, key, config, payload);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "adapter.test-sent",
                "adapter", key, AuditWriter.Outcome.SUCCESS, Map.of("kind", kind.value(), "key", key, "outcome", result.outcome())));
        return new SendResult(key, kind.value(), result.outcome(), result.detail());
    }

    private void checkEgress(String baseUrl) {
        try {
            egress.check(baseUrl);
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new AdapterRules.Invalid("/config/baseUrl", e.getMessage());
        }
    }

    static ApiException invalid(AdapterRules.Invalid e) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, e.getMessage())
                .withFieldErrors(List.of(new ApiException.FieldError(e.pointer(), "invalid", e.getMessage())));
    }
}
