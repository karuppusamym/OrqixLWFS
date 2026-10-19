package com.orqix.integration;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/** Narrow requester-facing descriptors and browsing, with a workspace permission and a resource-specific audience. */
@Service
public class DocumentSources {
    public record Principal(String type, String value) {}
    public record Source(UUID id, String label, String connectionKey, int definitionVersion,
            String driveId, String folderId) {}
    public record AdminSource(Source source, String browseOperation, String versionsOperation,
            List<Principal> audience, boolean enabled, int revision) {}
    public record Save(String label, String connectionKey, int definitionVersion, String browseOperation,
            String versionsOperation, String driveId, String folderId, List<Principal> audience, boolean enabled,
            int expectedRevision) {}
    private record Row(Source source, String browse, String versions, JsonNode audience, boolean enabled, int revision) {}
    private final JdbcClient jdbc; private final PolicyService policy; private final ConnectionService connections;
    private final MicrosoftGraphDocuments documents; private final SecretStore secrets; private final Json json;
    private final AuditWriter audit; private final Clock clock;
    public DocumentSources(JdbcClient jdbc, PolicyService policy, ConnectionService connections, MicrosoftGraphDocuments documents,
            SecretStore secrets, Json json, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc; this.policy = policy; this.connections = connections; this.documents = documents;
        this.secrets = secrets; this.json = json; this.audit = audit; this.clock = clock;
    }
    public List<Source> list(Caller caller, UUID workspaceId) {
        requireBrowse(caller, workspaceId);
        return rows(workspaceId).stream().filter(row -> allowed(caller, row)).map(Row::source).toList();
    }
    public List<AdminSource> adminList(Caller caller, UUID workspaceId) {
        policy.require(policy.requireVisible(caller, workspaceId), Permission.WORKSPACE_MANAGE_ACCESS, "listDocumentSourceGrants", false);
        return rows(workspaceId).stream().map(this::admin).toList();
    }
    @Transactional public AdminSource save(Caller caller, UUID workspaceId, UUID id, Save save) {
        policy.require(policy.requireVisible(caller, workspaceId), Permission.WORKSPACE_MANAGE_ACCESS, "putDocumentSourceGrant", true);
        if (save == null || save.label() == null || save.label().isBlank() || save.label().length() > 100
                || save.audience() == null || save.audience().isEmpty() || save.audience().size() > 100 || save.expectedRevision() < 0 || save.expectedRevision() == Integer.MAX_VALUE)
            throw ApiException.malformed("A name, audience and expected revision are required.");
        for (Principal principal : save.audience()) {
            if (principal == null || principal.value() == null || principal.value().isBlank() || principal.value().length() > 256
                    || !("subject".equals(principal.type()) || "idpGroup".equals(principal.type())))
                throw ApiException.malformed("Use subject IDs or IdP groups for the document audience.");
            if ("subject".equals(principal.type())) {
                UUID subject; try { subject = UUID.fromString(principal.value()); } catch (IllegalArgumentException e) { throw ApiException.malformed("Invalid subject ID."); }
                if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM subject WHERE id = :id)").param("id", subject).query(Boolean.class).single())
                    throw ApiException.malformed("The subject does not exist.");
            }
        }
        var definition = connections.definition(workspaceId, save.connectionKey(), save.definitionVersion()).orElseThrow(ApiException::notFound).definition();
        var browse = definition.operations().get(save.browseOperation()); var versions = definition.operations().get(save.versionsOperation());
        if (!definition.isMicrosoftGraph() || browse == null || versions == null || !"/browseFolder".equals(browse.path()) || !"/versions".equals(versions.path())
                || !approved(browse, "driveId", save.driveId()) || !approved(browse, "folderId", save.folderId()) || !approved(versions, "driveId", save.driveId()))
            throw ApiException.malformed("The source must use pinned, approved folder and version-list operations.");
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", workspaceId).query(UUID.class).single();
        var existing = rows(workspaceId).stream().filter(row -> row.source().id().equals(id)).findFirst();
        if (existing.map(Row::revision).orElse(0) != save.expectedRevision()) throw new ApiException(ProblemCode.STALE_REVISION, "Reload the document source before saving.");
        if (existing.isEmpty() && rows(workspaceId).size() >= 50) throw ApiException.malformed("A workspace supports at most 50 document sources.");
        int revision = save.expectedRevision() + 1;
        jdbc.sql("""
                INSERT INTO document_source(id, workspace_id, label, connection_key, definition_version, browse_operation,
                    versions_operation, drive_id, folder_id, audience, enabled, revision, updated_by, updated_at)
                VALUES (:id,:ws,:label,:key,:version,:browse,:versions,:drive,:folder,CAST(:audience AS jsonb),:enabled,:revision,:by,:at)
                ON CONFLICT(id) DO UPDATE SET label=EXCLUDED.label, connection_key=EXCLUDED.connection_key,
                    definition_version=EXCLUDED.definition_version, browse_operation=EXCLUDED.browse_operation,
                    versions_operation=EXCLUDED.versions_operation, drive_id=EXCLUDED.drive_id, folder_id=EXCLUDED.folder_id,
                    audience=EXCLUDED.audience, enabled=EXCLUDED.enabled, revision=EXCLUDED.revision,
                    updated_by=EXCLUDED.updated_by, updated_at=EXCLUDED.updated_at
                WHERE document_source.workspace_id=EXCLUDED.workspace_id
                """).param("id", id).param("ws", workspaceId).param("label", save.label()).param("key", save.connectionKey())
                .param("version", save.definitionVersion()).param("browse", save.browseOperation()).param("versions", save.versionsOperation())
                .param("drive", save.driveId()).param("folder", save.folderId()).param("audience", json.write(save.audience()))
                .param("enabled", save.enabled()).param("revision", revision).param("by", caller.subjectId()).param("at", Sql.ts(clock.instant())).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "document.source.updated", "documentSource", id.toString(),
                AuditWriter.Outcome.SUCCESS, Map.of("revision", revision, "audienceCount", save.audience().size(), "enabled", save.enabled())));
        return admin(rows(workspaceId).stream().filter(row -> row.source().id().equals(id)).findFirst().orElseThrow(ApiException::notFound));
    }
    public JsonNode files(Caller caller, UUID workspaceId, UUID id) {
        Row source = requireSource(caller, workspaceId, id);
        return call(workspaceId, source, source.browse(), json.object().put("driveId", source.source().driveId()).put("folderId", source.source().folderId()));
    }
    public JsonNode versions(Caller caller, UUID workspaceId, UUID id, String itemId) {
        Row source = requireSource(caller, workspaceId, id);
        JsonNode listing = call(workspaceId, source, source.browse(), json.object().put("driveId", source.source().driveId()).put("folderId", source.source().folderId()));
        boolean present = false; for (JsonNode file : json.parse(listing.path("filesJson").asString())) present |= itemId.equals(file.path("itemId").asString());
        if (!present) throw ApiException.notFound();
        return call(workspaceId, source, source.versions(), json.object().put("driveId", source.source().driveId()).put("itemId", itemId));
    }
    private JsonNode call(UUID workspaceId, Row source, String operationName, JsonNode input) {
        var definition = connections.definition(workspaceId, source.source().connectionKey(), source.source().definitionVersion()).orElseThrow(ApiException::notFound).definition();
        var binding = connections.binding(workspaceId, source.source().connectionKey()).orElseThrow(ApiException::notFound);
        if (!binding.enabled() || !("bearerToken".equals(binding.authType()) || ("oauth2ClientCredentials".equals(binding.authType()) || "oauth2AuthorizationCode".equals(binding.authType())))) throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE);
        String secret = secrets.credential(binding.authType(), binding.secretRef(), workspaceId).orElseThrow(() -> new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE));
        try { return documents.read(new HttpExecutor.Target(binding.baseUrl(), binding.authType(), binding.authHeaderName(), binding.secretRef(), workspaceId),
                source.source().connectionKey(), definition.operation(operationName), input, secret, 10); }
        catch (MicrosoftGraphDocuments.Failure e) {
            if (Integer.valueOf(401).equals(e.status()) && ("oauth2ClientCredentials".equals(binding.authType()) || "oauth2AuthorizationCode".equals(binding.authType())))
                secrets.invalidateOAuth(workspaceId, binding.secretRef());
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The approved document source is unavailable.");
        }
    }
    private void requireBrowse(Caller caller, UUID workspaceId) {
        var access = policy.freshAccess(caller, workspaceId).orElseThrow(ApiException::notFound);
        if (!access.visible()) throw ApiException.notFound();
        policy.require(access, Permission.DOCUMENT_BROWSE, "browseDocumentSource", false);
    }
    private Row requireSource(Caller caller, UUID workspaceId, UUID id) {
        requireBrowse(caller, workspaceId);
        return rows(workspaceId).stream().filter(row -> row.source().id().equals(id) && allowed(caller, row)).findFirst().orElseThrow(ApiException::notFound);
    }
    private boolean allowed(Caller caller, Row row) {
        if (!row.enabled()) return false;
        for (JsonNode principal : row.audience()) {
            String type = principal.path("type").asString(), value = principal.path("value").asString();
            if ("subject".equals(type) && caller.subjectId().toString().equals(value) || "idpGroup".equals(type) && caller.groups().contains(value)) return true;
        }
        return false;
    }
    private static boolean approved(ConnectionDefinition.Operation operation, String field, String value) {
        var values = operation.requestSchema().path("properties").path(field).path("enum");
        if (value == null || !values.isArray() || values.isEmpty() || values.size() > 100) return false;
        for (JsonNode candidate : values) if (value.equals(candidate.asString())) return true;
        return false;
    }
    private AdminSource admin(Row row) {
        var principals = new java.util.ArrayList<Principal>();
        for (JsonNode entry : row.audience()) principals.add(new Principal(entry.path("type").asString(), entry.path("value").asString()));
        return new AdminSource(row.source(), row.browse(), row.versions(), principals, row.enabled(), row.revision());
    }
    private List<Row> rows(UUID workspaceId) {
        return jdbc.sql("SELECT * FROM document_source WHERE workspace_id=:ws ORDER BY label,id LIMIT 51").param("ws", workspaceId)
                .query((rs,n) -> new Row(new Source(Sql.uuid(rs,"id"),rs.getString("label"),rs.getString("connection_key"),rs.getInt("definition_version"),rs.getString("drive_id"),rs.getString("folder_id")),
                        rs.getString("browse_operation"),rs.getString("versions_operation"),json.parse(rs.getString("audience")),rs.getBoolean("enabled"),rs.getInt("revision"))).list();
    }
}
