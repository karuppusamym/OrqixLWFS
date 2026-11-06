package com.orqix.integration.packages;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.compiler.ConnectionContracts;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ConnectorPackageTemplates;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connector package lifecycle (CG-C04) and the internal catalog (CG-C10), docs/build/S133 sections 4 and 6.
 *
 * <ol>
 * <li><b>Import</b> ({@code connector.manage}): safe unzip, checksums, signature against the trusted publisher keys
 * (an unsigned or untrusted package needs an audited override reason; a broken signature is always refused), full
 * validation. Nothing is active.</li>
 * <li><b>Review</b>: hosts, operations by effect class, data leaving Orqix, auth schemes, triggers, the comparison with
 * the version installed here and where the package's connections are used.</li>
 * <li><b>Approve</b> ({@code connector.approve}, grant-only): a different person than the importer approves exactly the
 * archive hash they reviewed.</li>
 * <li><b>Install</b> ({@code connector.manage}): the connections become ordinary versioned connection assets of the
 * workspace; never bindings or credentials. An install of a newer version is the upgrade.</li>
 * <li><b>Retire</b> ({@code connector.manage}): blocks new installs and new use here; pinned releases keep working.</li>
 * </ol>
 * Package versions belong to the organization: any workspace of it can install an approved version.
 */
@Service
public class ConnectorPackageService implements ConnectorPackageTemplates {

    public record Subject(UUID id, String displayName) {
    }

    public record Summary(UUID id, String packageId, String name, String version, String publisher, String keyId,
            String sha256, String signatureStatus, String status, Subject importedBy, Instant importedAt,
            Subject decidedBy, Instant decidedAt, String decisionNote, String installStatus, int revision) {
    }

    public record PackageList(List<Summary> items, boolean canManage, boolean canApprove) {
    }

    public record Change(String operation, String change, boolean breaking) {
    }

    /** Comparison with the version installed in this workspace (null installedVersion: nothing installed). */
    public record Comparison(String installedVersion, List<String> addedOperations, List<String> removedOperations,
            List<Change> changes, boolean compatible) {
    }

    public record Usage(UUID workflowId, String workflowKey, String workflowName, String connectionKey,
            Integer draftVersion, Integer releaseVersion, boolean outdated) {
    }

    public record Detail(Summary summary, JsonNode manifest, JsonNode review, Comparison comparison, List<Usage> whereUsed,
            String unsignedReason, String retireReason, boolean canManage, boolean canApprove, boolean canDecide) {
    }

    public record ImportRequest(String archive, String unsignedReason) {
    }

    public record DecisionRequest(String decision, String sha256, String note, Integer expectedRevision) {
    }

    public record RetireRequest(String reason, Integer expectedRevision) {
    }

    public record InstalledConnection(String key, int version, boolean reused) {
    }

    public record InstallResult(String packageId, String version, String previousVersion,
            List<InstalledConnection> connections, List<String> hostsToAllow) {
    }

    public record CatalogVersion(UUID id, String version, String status, String signatureStatus, Instant importedAt) {
    }

    public record CatalogInstall(UUID workspaceId, String workspaceName, String version, String status) {
    }

    public record OutdatedPin(UUID workspaceId, String workspaceName, UUID workflowId, String workflowName,
            String connectionKey, String pinnedVersion, String latestVersion, boolean retired, String pinnedIn) {
    }

    public record CatalogEntry(String packageId, String name, String publisher, String latestVersion,
            List<CatalogVersion> versions, List<CatalogInstall> installs, List<OutdatedPin> outdatedPins) {
    }

    public record Catalog(List<CatalogEntry> items) {
    }

    public record PaletteItem(String kind, String label, String description, String packageId, String packageName,
            String packageVersion, UUID packageVersionId, String connectionKey, int connectionVersion, String operation,
            String effectClass, String templateKey, JsonNode step, JsonNode requestFields) {
    }

    public record Palette(List<PaletteItem> items) {
    }

    private static final Comparator<String> SEMVER = Comparator.comparingInt((String v) -> part(v, 0))
            .thenComparingInt(v -> part(v, 1)).thenComparingInt(v -> part(v, 2));

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final Json json;
    private final PackageValidator validator;
    private final PackageSettings settings;
    private final Map<String, PackageArchive.Archive> archives = new ConcurrentHashMap<>();

    public ConnectorPackageService(JdbcClient jdbc, PolicyService policy, SubjectService subjects, AuditWriter audit,
            Json json, PackageValidator validator, PackageSettings settings) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
        this.json = json;
        this.validator = validator;
        this.settings = settings;
    }

    // ------------------------------------------------------------------------------------------------- access

    private WorkspaceAccess reader(Caller caller, UUID ws, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        if (!access.has(Permission.CONNECTOR_MANAGE) && !access.has(Permission.CONNECTOR_APPROVE)
                && !access.has(Permission.CONNECTION_READ)) {
            throw policy.deny(ws, caller.subjectId(), "connector.manage", operationId, false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        return access;
    }

    private record Row(UUID id, UUID organizationId, String packageId, String version, String name, String publisher,
            String keyId, String sha256, String manifest, String review, String signatureStatus, String unsignedReason,
            String status, UUID importedWorkspace, UUID importedBy, Instant importedAt, UUID decidedBy, Instant decidedAt,
            String decisionNote, String retireReason, int revision) {
    }

    private static final String COLUMNS = columns("");

    /** Column list of {@link Row}, each prefixed with the table alias (empty for none). */
    private static String columns(String alias) {
        String a = alias.isEmpty() ? "" : alias + ".";
        return String.join(", ", List.of(a + "id", a + "organization_id", a + "package_id", a + "version", a + "name",
                a + "publisher_name", a + "publisher_key_id", a + "sha256", a + "manifest::text AS manifest",
                a + "review::text AS review", a + "signature_status", a + "unsigned_reason", a + "status",
                a + "imported_workspace_id", a + "imported_by", a + "imported_at", a + "decided_by", a + "decided_at",
                a + "decision_note", a + "retire_reason", a + "revision"));
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "organization_id"), rs.getString("package_id"),
                rs.getString("version"), rs.getString("name"), rs.getString("publisher_name"),
                rs.getString("publisher_key_id"), rs.getString("sha256"), rs.getString("manifest"), rs.getString("review"),
                rs.getString("signature_status"), rs.getString("unsigned_reason"), rs.getString("status"),
                Sql.uuid(rs, "imported_workspace_id"), Sql.uuid(rs, "imported_by"), Sql.instant(rs, "imported_at"),
                rs.getObject("decided_by", UUID.class), Sql.instant(rs, "decided_at"), rs.getString("decision_note"),
                rs.getString("retire_reason"), rs.getInt("revision"));
    }

    private Row find(WorkspaceAccess access, UUID id, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_package_version WHERE id = :id AND organization_id = :org"
                + (lock ? " FOR UPDATE" : "")).param("id", id).param("org", access.workspace().organizationId())
                .query((rs, n) -> row(rs)).optional().orElseThrow(ApiException::notFound);
    }

    // ------------------------------------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public PackageList list(Caller caller, UUID ws) {
        WorkspaceAccess access = reader(caller, ws, "listConnectorPackages");
        List<Row> rows = jdbc.sql("SELECT " + COLUMNS + " FROM connector_package_version WHERE organization_id = :org"
                + " ORDER BY package_id, imported_at").param("org", access.workspace().organizationId())
                .query((rs, n) -> row(rs)).list();
        Map<UUID, String> installs = installStates(ws);
        Map<UUID, String> names = names(rows);
        List<Summary> items = new ArrayList<>(rows.stream().map(r -> summary(r, names, installs.get(r.id()))).toList());
        items.sort(Comparator.comparing(Summary::packageId).thenComparing(Summary::version, SEMVER.reversed()));
        return new PackageList(items, access.has(Permission.CONNECTOR_MANAGE), access.has(Permission.CONNECTOR_APPROVE));
    }

    @Transactional(readOnly = true)
    public Detail get(Caller caller, UUID ws, UUID id) {
        WorkspaceAccess access = reader(caller, ws, "getConnectorPackage");
        Row row = find(access, id, false);
        return detail(access, ws, row);
    }

    private Detail detail(WorkspaceAccess access, UUID ws, Row row) {
        Map<UUID, String> names = names(List.of(row));
        Summary summary = summary(row, names, installStates(ws).get(row.id()));
        PackageValidator.Validated contents = contents(row);
        Comparison comparison = compare(ws, row, contents);
        List<Usage> usage = whereUsed(ws, row.packageId(), contents.connections().keySet(), row.id());
        boolean canApprove = access.has(Permission.CONNECTOR_APPROVE);
        return new Detail(summary, json.parse(row.manifest()), json.parse(row.review()), comparison, usage,
                row.unsignedReason(), row.retireReason(), access.has(Permission.CONNECTOR_MANAGE), canApprove,
                canApprove && "pending".equals(row.status()) && !row.importedBy().equals(access.subjectId()));
    }

    // ------------------------------------------------------------------------------------------------- import

    @Transactional
    public Detail importPackage(Caller caller, UUID ws, ImportRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.CONNECTOR_MANAGE, "importConnectorPackage", true);
        if (request == null || request.archive() == null || request.archive().isBlank()) {
            throw invalid("/archive", "required", "Upload a .orqpkg file.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.archive().strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.malformed("archive must be the package file in base64.");
        }
        String reason = request.unsignedReason() == null || request.unsignedReason().isBlank() ? null
                : request.unsignedReason().strip();
        if (reason != null && reason.length() > 500) {
            throw invalid("/unsignedReason", "maxLength", "The reason can be at most 500 characters.");
        }
        PackageArchive.Archive archive;
        try {
            archive = PackageArchive.read(bytes);
        } catch (PackageArchive.Invalid e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The package cannot be imported.")
                    .withFieldErrors(List.of(new ApiException.FieldError(e.path() == null ? "/archive"
                            : "/files/" + e.path(), e.code(), e.getMessage())));
        }
        PackageArchive.Verification signature = PackageArchive.verify(archive, settings);
        if (signature.state() == PackageArchive.SignatureState.INVALID) {
            throw invalid("/files/" + PackageArchive.SIGNATURE, "PKG_SIGNATURE_INVALID",
                    "The signature does not match the package. It was changed after signing or is not a valid signature.");
        }
        boolean verified = signature.state() == PackageArchive.SignatureState.VERIFIED;
        if (!verified && reason == null) {
            throw invalid("/unsignedReason", "PKG_UNSIGNED", signature.state() == PackageArchive.SignatureState.UNSIGNED
                    ? "The package is not signed. Importing it needs a reason (the override is audited)."
                    : "The package is signed with a key this deployment does not trust. Importing it needs a reason"
                            + " (the override is audited), or trust the publisher's key in the deployment settings.");
        }
        if (verified && reason != null) {
            throw invalid("/unsignedReason", "PKG_SIGNED", "The package is signed with a trusted key; no override is needed.");
        }
        PackageValidator.Validated validated;
        try {
            validated = validator.validate(archive);
        } catch (PackageValidator.Failed e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The package is not valid.")
                    .withFieldErrors(e.problems().stream().limit(50).map(p -> new ApiException.FieldError(
                            "/files/" + p.file() + p.pointer(), p.code(), p.message())).toList());
        }
        JsonNode manifest = validated.manifest();
        if (verified && !manifest.path("publisher").path("keyId").asString().equals(signature.keyId())) {
            throw invalid("/files/" + PackageArchive.SIGNATURE, "PKG_SIGNATURE_INVALID",
                    "The package is signed with another key than its publisher's.");
        }
        UUID id = Ids.newId();
        String packageId = manifest.path("id").asString();
        String version = manifest.path("version").asString();
        try {
            jdbc.sql("""
                    INSERT INTO connector_package_version (id, organization_id, package_id, version, name, publisher_name,
                        publisher_key_id, sha256, archive, manifest, review, signature_status, unsigned_reason, status,
                        imported_workspace_id, imported_by)
                    VALUES (:id, :org, :pkg, :version, :name, :publisher, :key, :sha, :archive, CAST(:manifest AS jsonb),
                            CAST(:review AS jsonb), :sig, :reason, 'pending', :ws, :by)
                    """).param("id", id).param("org", access.workspace().organizationId()).param("pkg", packageId)
                    .param("version", version).param("name", manifest.path("name").asString())
                    .param("publisher", manifest.path("publisher").path("name").asString())
                    .param("key", manifest.path("publisher").path("keyId").asString()).param("sha", archive.sha256())
                    .param("archive", bytes).param("manifest", json.write(manifest))
                    .param("review", json.write(validated.review())).param("sig", verified ? "verified" : "unsigned")
                    .param("reason", verified ? null : reason).param("ws", ws).param("by", caller.subjectId()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "Version " + version + " of " + packageId
                    + " was already imported in this organization. Versions are immutable.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packageId", packageId);
        metadata.put("version", version);
        metadata.put("sha256", archive.sha256());
        metadata.put("signatureStatus", verified ? "verified" : "unsigned");
        metadata.put("unsignedOverride", !verified);
        metadata.put("keyId", signature.keyId());
        write(caller, ws, "connector.package-imported", id, metadata);
        return detail(access, ws, find(access, id, false));
    }

    // ------------------------------------------------------------------------------------------------- decide

    @Transactional
    public Detail decide(Caller caller, UUID ws, UUID id, DecisionRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        Row row = find(access, id, true);
        policy.require(access, Permission.CONNECTOR_APPROVE, "decideConnectorPackage", true);
        if (request == null || !"approve".equals(request.decision()) && !"reject".equals(request.decision())) {
            throw invalid("/decision", "enum", "Choose approve or reject.");
        }
        String note = request.note() == null ? "" : request.note().strip();
        if (note.isEmpty() || note.length() > 500) {
            throw invalid("/note", "required", "Give a note of 1 to 500 characters.");
        }
        if (request.expectedRevision() == null || request.expectedRevision() != row.revision()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The package changed. Reload before deciding.")
                    .with("currentRevision", row.revision());
        }
        if (!row.sha256().equals(request.sha256())) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The hash does not match this package version. Approve exactly the package you reviewed.");
        }
        if (row.importedBy().equals(caller.subjectId())) {
            throw new ApiException(ProblemCode.FORBIDDEN, "The person who imported a package cannot decide it.");
        }
        if (!"pending".equals(row.status())) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "This package version is already "
                    + row.status() + ".");
        }
        String status = "approve".equals(request.decision()) ? "approved" : "rejected";
        jdbc.sql("""
                UPDATE connector_package_version SET status = :status, decided_by = :by, decided_at = now(),
                       decision_note = :note, revision = revision + 1 WHERE id = :id
                """).param("status", status).param("by", caller.subjectId()).param("note", note).param("id", id).update();
        write(caller, ws, "connector.package-decided", id, Map.of("packageId", row.packageId(), "version", row.version(),
                "sha256", row.sha256(), "decision", request.decision()));
        return detail(access, ws, find(access, id, false));
    }

    // ------------------------------------------------------------------------------------------------- install

    @Transactional
    public InstallResult install(Caller caller, UUID ws, UUID id) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        Row row = find(access, id, true);
        policy.require(access, Permission.CONNECTOR_MANAGE, "installConnectorPackage", true);
        if (!"approved".equals(row.status())) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "Only an approved package version can be"
                    + " installed (this one is " + row.status() + ").");
        }
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).single();
        record Active(UUID versionId, String version) {
        }
        Optional<Active> active = jdbc.sql("""
                SELECT package_version_id, version FROM connector_package_install
                WHERE workspace_id = :ws AND package_id = :pkg AND status = 'active'
                """).param("ws", ws).param("pkg", row.packageId())
                .query((rs, n) -> new Active(Sql.uuid(rs, "package_version_id"), rs.getString("version"))).optional();
        if (active.isPresent() && active.get().versionId().equals(id)) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "This version is already installed here.");
        }
        boolean previouslyInstalled = jdbc.sql("""
                SELECT count(*) FROM connector_package_install WHERE workspace_id = :ws AND package_version_id = :id
                """).param("ws", ws).param("id", id).query(Integer.class).single() > 0;
        if (previouslyInstalled) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "This version was installed here before"
                    + " and replaced; install a newer version instead.");
        }
        PackageValidator.Validated contents = contents(row);
        Set<String> ownKeys = new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT c.connection_key FROM connector_package_connection c
                JOIN connector_package_version v ON v.id = c.package_version_id
                WHERE c.workspace_id = :ws AND v.package_id = :pkg
                """).param("ws", ws).param("pkg", row.packageId()).query(String.class).list());
        List<InstalledConnection> installed = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : contents.connections().entrySet()) {
            String key = e.getKey();
            record Latest(int version, String definition) {
            }
            Optional<Latest> latest = jdbc.sql("""
                    SELECT version, definition::text AS definition FROM connection_definition
                    WHERE workspace_id = :ws AND key = :key ORDER BY version DESC LIMIT 1
                    """).param("ws", ws).param("key", key)
                    .query((rs, n) -> new Latest(rs.getInt("version"), rs.getString("definition"))).optional();
            if (latest.isPresent() && !ownKeys.contains(key)) {
                throw invalid("/connections/" + key, "PKG_CONNECTION_KEY_TAKEN", "The connection " + key + " already"
                        + " exists in this workspace and does not come from this package.");
            }
            ObjectNode definition = (ObjectNode) e.getValue().deepCopy();
            int version;
            boolean reused = false;
            if (latest.isPresent() && sameIgnoringVersion(json.parse(latest.get().definition()), definition)) {
                version = latest.get().version();
                reused = true;
            } else {
                version = latest.map(l -> l.version() + 1).orElse(1);
                definition.put("version", version);
                String text = json.write(definition);
                jdbc.sql("""
                        INSERT INTO connection_definition (id, workspace_id, key, version, definition, definition_hash, created_by)
                        VALUES (:id, :ws, :key, :version, CAST(:definition AS jsonb), :hash, :by)
                        """).param("id", Ids.newId()).param("ws", ws).param("key", key).param("version", version)
                        .param("definition", text).param("hash", Json.canonicalSha256(text))
                        .param("by", caller.subjectId()).update();
                audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                        "connection.version.created", "connection", key, AuditWriter.Outcome.SUCCESS,
                        Map.of("key", key, "version", version, "definitionHash", Json.canonicalSha256(text))));
            }
            jdbc.sql("""
                    INSERT INTO connector_package_connection (workspace_id, package_version_id, connection_key, connection_version)
                    VALUES (:ws, :id, :key, :version)
                    """).param("ws", ws).param("id", id).param("key", key).param("version", version).update();
            // A key archived by an earlier retirement is in use again.
            jdbc.sql("UPDATE connection_asset SET archived_at = NULL, revision = revision + 1, updated_by = :by,"
                    + " updated_at = now() WHERE workspace_id = :ws AND connection_key = :key AND archived_at IS NOT NULL")
                    .param("by", caller.subjectId()).param("ws", ws).param("key", key).update();
            installed.add(new InstalledConnection(key, version, reused));
        }
        active.ifPresent(a -> jdbc.sql("""
                UPDATE connector_package_install SET status = 'superseded'
                WHERE workspace_id = :ws AND package_version_id = :id
                """).param("ws", ws).param("id", a.versionId()).update());
        jdbc.sql("""
                INSERT INTO connector_package_install (workspace_id, package_version_id, package_id, version, status, installed_by)
                VALUES (:ws, :id, :pkg, :version, 'active', :by)
                """).param("ws", ws).param("id", id).param("pkg", row.packageId()).param("version", row.version())
                .param("by", caller.subjectId()).update();
        // Enabled package triggers follow the installed version's trigger files.
        jdbc.sql("""
                UPDATE package_trigger SET package_version_id = :id, version = version + 1, updated_at = now()
                WHERE workspace_id = :ws AND package_id = :pkg
                """).param("id", id).param("ws", ws).param("pkg", row.packageId()).update();
        for (Map.Entry<String, JsonNode> t : contents.triggers().entrySet()) {
            jdbc.sql("UPDATE package_trigger SET definition = CAST(:def AS jsonb) WHERE workspace_id = :ws"
                    + " AND package_id = :pkg AND trigger_key = :key").param("def", json.write(t.getValue()))
                    .param("ws", ws).param("pkg", row.packageId()).param("key", t.getKey()).update();
        }
        // A trigger the new version no longer declares is paused, never left running on an old definition.
        for (String key : jdbc.sql("SELECT trigger_key FROM package_trigger WHERE workspace_id = :ws AND package_id = :pkg")
                .param("ws", ws).param("pkg", row.packageId()).query(String.class).list()) {
            if (!contents.triggers().containsKey(key)) {
                jdbc.sql("UPDATE package_trigger SET enabled = false WHERE workspace_id = :ws AND package_id = :pkg"
                        + " AND trigger_key = :key").param("ws", ws).param("pkg", row.packageId()).param("key", key)
                        .update();
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packageId", row.packageId());
        metadata.put("version", row.version());
        metadata.put("previousVersion", active.map(Active::version).orElse(null));
        metadata.put("connections", installed.size());
        write(caller, ws, "connector.package-installed", id, metadata);
        List<String> hosts = new ArrayList<>();
        contents.manifest().path("hosts").forEach(h -> hosts.add(h.path("pattern").asString()));
        return new InstallResult(row.packageId(), row.version(), active.map(Active::version).orElse(null), installed, hosts);
    }

    private boolean sameIgnoringVersion(JsonNode a, JsonNode b) {
        ObjectNode x = (ObjectNode) a.deepCopy();
        ObjectNode y = (ObjectNode) b.deepCopy();
        x.remove("version");
        y.remove("version");
        return json.canonicalSha256(x).equals(json.canonicalSha256(y));
    }

    // ------------------------------------------------------------------------------------------------- retire

    @Transactional
    public Detail retire(Caller caller, UUID ws, UUID id, RetireRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        Row row = find(access, id, true);
        policy.require(access, Permission.CONNECTOR_MANAGE, "retireConnectorPackage", true);
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Give a reason of 1 to 500 characters.");
        }
        if (request.expectedRevision() == null || request.expectedRevision() != row.revision()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The package changed. Reload first.")
                    .with("currentRevision", row.revision());
        }
        if ("retired".equals(row.status())) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "This package version is already retired.");
        }
        jdbc.sql("""
                UPDATE connector_package_version SET status = 'retired', retired_by = :by, retired_at = now(),
                       retire_reason = :reason, revision = revision + 1,
                       decided_by = coalesce(decided_by, :by), decided_at = coalesce(decided_at, now())
                WHERE id = :id
                """).param("by", caller.subjectId()).param("reason", reason).param("id", id).update();
        // Here, where the caller manages connectors, the retired version's connections take no new use: the keys are
        // archived unless a newer install of the package uses them. Pinned releases keep working (archiving never
        // touches bindings or releases). Other workspaces see the retirement in the catalog.
        boolean activeHere = jdbc.sql("""
                UPDATE connector_package_install SET status = 'retired'
                WHERE workspace_id = :ws AND package_version_id = :id AND status = 'active'
                """).param("ws", ws).param("id", id).update() == 1;
        if (activeHere) {
            for (String key : jdbc.sql("SELECT connection_key FROM connector_package_connection WHERE workspace_id = :ws"
                    + " AND package_version_id = :id").param("ws", ws).param("id", id).query(String.class).list()) {
                jdbc.sql("""
                        INSERT INTO connection_asset (workspace_id, connection_key, owner_subject_id, archived_at, updated_by)
                        VALUES (:ws, :key, :by, now(), :by)
                        ON CONFLICT (workspace_id, connection_key) DO UPDATE
                        SET archived_at = now(), revision = connection_asset.revision + 1, updated_by = :by, updated_at = now()
                        """).param("ws", ws).param("key", key).param("by", caller.subjectId()).update();
            }
        }
        int affected = jdbc.sql("SELECT count(*) FROM connector_package_install WHERE package_version_id = :id")
                .param("id", id).query(Integer.class).single();
        write(caller, ws, "connector.package-retired", id, Map.of("packageId", row.packageId(), "version", row.version(),
                "workspacesAffected", affected));
        return detail(access, ws, find(access, id, false));
    }

    // ------------------------------------------------------------------------------------------------- catalog

    @Transactional(readOnly = true)
    public Catalog catalog(Caller caller, UUID ws) {
        WorkspaceAccess access = reader(caller, ws, "getConnectorCatalog");
        UUID org = access.workspace().organizationId();
        List<Row> rows = jdbc.sql("SELECT " + COLUMNS + " FROM connector_package_version WHERE organization_id = :org")
                .param("org", org).query((rs, n) -> row(rs)).list();
        record Install(UUID versionId, UUID workspaceId, String workspaceName, String version, String status) {
        }
        List<Install> installs = jdbc.sql("""
                SELECT i.package_version_id, i.workspace_id, w.name, i.version, i.status FROM connector_package_install i
                JOIN workspace w ON w.id = i.workspace_id WHERE w.organization_id = :org AND i.status <> 'superseded'
                ORDER BY w.name
                """).param("org", org).query((rs, n) -> new Install(Sql.uuid(rs, "package_version_id"),
                        Sql.uuid(rs, "workspace_id"), rs.getString("name"), rs.getString("version"), rs.getString("status")))
                .list();
        record Pin(UUID workspaceId, String workspaceName, UUID workflowId, String workflowName, String connectionKey,
                String packageId, String version, String status, String pinnedIn) {
        }
        String pinned = """
                SELECT w.workspace_id, ws.name AS workspace_name, w.id AS workflow_id, w.name AS workflow_name,
                       dep.key AS connection_key, v.package_id, v.version, v.status, '%s' AS pinned_in
                FROM workflow w
                JOIN workspace ws ON ws.id = w.workspace_id
                JOIN %s
                CROSS JOIN LATERAL jsonb_to_recordset(coalesce(d.definition -> 'dependencies' -> 'connections', '[]'::jsonb))
                     AS dep(key text, "definitionVersion" int)
                JOIN connector_package_connection c ON c.workspace_id = w.workspace_id AND c.connection_key = dep.key
                     AND c.connection_version = dep."definitionVersion"
                JOIN connector_package_version v ON v.id = c.package_version_id
                WHERE ws.organization_id = :org AND w.archived_at IS NULL
                """;
        String sql = pinned.formatted("release", "workflow_release d ON d.id = w.active_release_id")
                + " UNION ALL " + pinned.formatted("draft",
                        "workflow_draft_revision d ON d.workflow_id = w.id AND d.revision = w.draft_revision");
        List<Pin> pins = jdbc.sql(sql).param("org", org).query((rs, n) -> new Pin(Sql.uuid(rs, "workspace_id"),
                rs.getString("workspace_name"), Sql.uuid(rs, "workflow_id"), rs.getString("workflow_name"),
                rs.getString("connection_key"), rs.getString("package_id"), rs.getString("version"),
                rs.getString("status"), rs.getString("pinned_in"))).list();
        Map<String, List<Row>> byPackage = new LinkedHashMap<>();
        rows.stream().sorted(Comparator.comparing(Row::packageId)).forEach(r ->
                byPackage.computeIfAbsent(r.packageId(), k -> new ArrayList<>()).add(r));
        List<CatalogEntry> items = new ArrayList<>();
        byPackage.forEach((packageId, versions) -> {
            versions.sort(Comparator.comparing(Row::version, SEMVER.reversed()));
            String latest = versions.stream().filter(v -> "approved".equals(v.status())).map(Row::version).findFirst()
                    .orElse(null);
            Set<UUID> ids = new LinkedHashSet<>(versions.stream().map(Row::id).toList());
            List<CatalogInstall> inst = installs.stream().filter(i -> ids.contains(i.versionId()))
                    .map(i -> new CatalogInstall(i.workspaceId(), i.workspaceName(), i.version(), i.status())).toList();
            List<OutdatedPin> outdated = pins.stream().filter(p -> p.packageId().equals(packageId))
                    .filter(p -> "retired".equals(p.status()) || latest != null && !latest.equals(p.version()))
                    .map(p -> new OutdatedPin(p.workspaceId(), p.workspaceName(), p.workflowId(), p.workflowName(),
                            p.connectionKey(), p.version(), latest, "retired".equals(p.status()), p.pinnedIn()))
                    .distinct().toList();
            Row newest = versions.get(0);
            items.add(new CatalogEntry(packageId, newest.name(), newest.publisher(), latest,
                    versions.stream().map(v -> new CatalogVersion(v.id(), v.version(), v.status(), v.signatureStatus(),
                            v.importedAt())).toList(), inst, outdated));
        });
        return new Catalog(items);
    }

    // ------------------------------------------------------------------------------------------------- palette

    @Transactional(readOnly = true)
    public Palette palette(Caller caller, UUID ws, String q) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_READ, "searchConnectorPalette", false);
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (needle.length() > 100) {
            throw invalid("/q", "maxLength", "Search for at most 100 characters.");
        }
        List<Row> rows = jdbc.sql("SELECT " + columns("v")
                + " FROM connector_package_version v JOIN connector_package_install i ON i.package_version_id = v.id"
                + " WHERE i.workspace_id = :ws AND i.status = 'active' AND v.status = 'approved'")
                .param("ws", ws).query((rs, n) -> row(rs)).list();
        List<PaletteItem> items = new ArrayList<>();
        for (Row row : rows) {
            PackageValidator.Validated contents = contents(row);
            Map<String, Integer> versions = new LinkedHashMap<>();
            jdbc.sql("SELECT connection_key, connection_version FROM connector_package_connection WHERE workspace_id = :ws"
                    + " AND package_version_id = :id").param("ws", ws).param("id", row.id())
                    .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2))).list()
                    .forEach(e -> versions.put(e.getKey(), e.getValue()));
            contents.connections().forEach((key, definition) -> definition.path("operations").properties().forEach(op -> {
                String label = op.getValue().path("label").asString() + " · " + row.name();
                if (matches(needle, label, op.getKey(), row.name())) {
                    items.add(new PaletteItem("operation", label, op.getValue().path("description").asString(null),
                            row.packageId(), row.name(), row.version(), row.id(), key, versions.getOrDefault(key, 0),
                            op.getKey(), op.getValue().path("effectClass").asString(), null, null, null));
                }
            }));
            contents.stepTemplates().forEach((key, template) -> {
                String label = template.path("label").asString() + " · " + row.name();
                String connection = template.path("connection").asString();
                if (matches(needle, label, template.path("operation").asString(), row.name())) {
                    JsonNode op = contents.connections().get(connection).path("operations")
                            .path(template.path("operation").asString());
                    items.add(new PaletteItem("stepTemplate", label, template.path("description").asString(null),
                            row.packageId(), row.name(), row.version(), row.id(), connection,
                            versions.getOrDefault(connection, 0), template.path("operation").asString(),
                            op.path("effectClass").asString(), key, template.path("step"), template.path("requestFields")));
                }
            });
        }
        items.sort(Comparator.comparing(PaletteItem::label));
        return new Palette(items.size() > 100 ? items.subList(0, 100) : items);
    }

    private static boolean matches(String needle, String... texts) {
        if (needle.isEmpty()) {
            return true;
        }
        for (String text : texts) {
            if (text != null && text.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------- templates port

    @Override
    @Transactional(readOnly = true)
    public Optional<Starter> starterWorkflow(UUID workspaceId, UUID packageVersionId, String templateKey) {
        Optional<Row> row = jdbc.sql("SELECT " + COLUMNS + " FROM connector_package_version WHERE id = :id AND status = 'approved'"
                + " AND EXISTS (SELECT 1 FROM connector_package_install i WHERE i.package_version_id = :id"
                + " AND i.workspace_id = :ws AND i.status = 'active')").param("id", packageVersionId)
                .param("ws", workspaceId).query((rs, n) -> row(rs)).optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        PackageValidator.Validated contents = contents(row.get());
        JsonNode template = contents.workflows().get(templateKey);
        if (template == null) {
            return Optional.empty();
        }
        ObjectNode workflow = (ObjectNode) template.path("workflow").deepCopy();
        Map<String, Integer> versions = new LinkedHashMap<>();
        jdbc.sql("SELECT connection_key, connection_version FROM connector_package_connection WHERE workspace_id = :ws"
                + " AND package_version_id = :id").param("ws", workspaceId).param("id", packageVersionId)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2))).list()
                .forEach(e -> versions.put(e.getKey(), e.getValue()));
        for (JsonNode dep : workflow.path("dependencies").path("connections")) {
            Integer installed = versions.get(dep.path("key").asString());
            if (installed != null) {
                ((ObjectNode) dep).put("definitionVersion", installed);
            }
        }
        return Optional.of(new Starter(json.write(workflow), row.get().packageId(), row.get().version()));
    }

    // ------------------------------------------------------------------------------------------------- bindings

    /**
     * Base URL rule for bindings of package connections (CG-C04 Bind): the host must match one of the package's host
     * patterns, or be a deployment-listed development host.
     *
     * @return null when allowed or when the connection does not come from an installed package, else the patterns
     */
    @Transactional(readOnly = true)
    public List<String> refusedBindingHost(UUID ws, String connectionKey, String baseUrl) {
        List<String> manifests = jdbc.sql("""
                SELECT v.manifest::text FROM connector_package_connection c
                JOIN connector_package_install i ON i.workspace_id = c.workspace_id AND i.package_version_id = c.package_version_id
                JOIN connector_package_version v ON v.id = c.package_version_id
                WHERE c.workspace_id = :ws AND c.connection_key = :key AND i.status = 'active'
                """).param("ws", ws).param("key", connectionKey).query(String.class).list();
        if (manifests.isEmpty()) {
            return null;
        }
        java.net.URI uri = java.net.URI.create(baseUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
        if (settings.developmentHosts().contains(host + ":" + port)) {
            return null;
        }
        List<String> patterns = new ArrayList<>();
        for (String manifest : manifests) {
            for (JsonNode h : json.parse(manifest).path("hosts")) {
                String pattern = h.path("pattern").asString();
                patterns.add(pattern);
                if (hostMatches(pattern, uri.getScheme(), host, port)) {
                    return null;
                }
            }
        }
        return patterns;
    }

    static boolean hostMatches(String pattern, String scheme, String host, int port) {
        if (!"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String rest = pattern.substring("https://".length());
        int expectedPort = 443;
        int colon = rest.lastIndexOf(':');
        if (colon > 0) {
            expectedPort = Integer.parseInt(rest.substring(colon + 1));
            rest = rest.substring(0, colon);
        }
        if (port != expectedPort) {
            return false;
        }
        if (rest.startsWith("*.")) {
            String suffix = rest.substring(1);
            String label = host.endsWith(suffix) ? host.substring(0, host.length() - suffix.length()) : null;
            return label != null && label.matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
        }
        return rest.equals(host);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    PackageValidator.Validated contents(Row row) {
        PackageArchive.Archive archive = archives.computeIfAbsent(row.sha256(), sha -> PackageArchive.read(
                jdbc.sql("SELECT archive FROM connector_package_version WHERE id = :id").param("id", row.id())
                        .query(byte[].class).single()));
        return validator.contentsOf(archive, json.parse(row.manifest()), json.parse(row.review()));
    }

    /** @return the archive's content (already validated at import) for a package version id of the organization */
    PackageValidator.Validated contents(UUID organizationId, UUID packageVersionId) {
        Row row = jdbc.sql("SELECT " + COLUMNS + " FROM connector_package_version WHERE id = :id AND organization_id = :org")
                .param("id", packageVersionId).param("org", organizationId).query((rs, n) -> row(rs)).optional()
                .orElseThrow(ApiException::notFound);
        return contents(row);
    }

    private Comparison compare(UUID ws, Row row, PackageValidator.Validated contents) {
        record Installed(UUID id, String version) {
        }
        Optional<Installed> installed = jdbc.sql("""
                SELECT package_version_id, version FROM connector_package_install
                WHERE workspace_id = :ws AND package_id = :pkg AND status = 'active'
                """).param("ws", ws).param("pkg", row.packageId())
                .query((rs, n) -> new Installed(Sql.uuid(rs, "package_version_id"), rs.getString("version"))).optional();
        if (installed.isEmpty() || installed.get().id().equals(row.id())) {
            return new Comparison(installed.map(Installed::version).orElse(null), List.of(), List.of(), List.of(), true);
        }
        PackageValidator.Validated old = contents(contentsRow(installed.get().id()));
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(old.connections().keySet());
        keys.addAll(contents.connections().keySet());
        for (String key : keys) {
            JsonNode before = old.connections().get(key);
            JsonNode after = contents.connections().get(key);
            JsonNode beforeOps = before == null ? json.object() : before.path("operations");
            JsonNode afterOps = after == null ? json.object() : after.path("operations");
            for (String op : beforeOps.propertyNames()) {
                if (!afterOps.has(op)) {
                    removed.add(key + "." + op);
                }
            }
            for (String op : afterOps.propertyNames()) {
                if (!beforeOps.has(op)) {
                    added.add(key + "." + op);
                    continue;
                }
                operationChanges(key + "." + op, beforeOps.get(op), afterOps.get(op), changes);
            }
        }
        for (String trigger : old.triggers().keySet()) {
            if (!contents.triggers().containsKey(trigger)) {
                changes.add(new Change("trigger " + trigger, "The trigger is removed.", true));
            }
        }
        boolean compatible = removed.isEmpty() && changes.stream().noneMatch(Change::breaking);
        return new Comparison(installed.get().version(), added, removed, changes, compatible);
    }

    private static void operationChanges(String name, JsonNode before, JsonNode after, List<Change> out) {
        if (!before.path("effectClass").asString().equals(after.path("effectClass").asString())) {
            out.add(new Change(name, "Effect class changes from " + before.path("effectClass").asString() + " to "
                    + after.path("effectClass").asString() + ".", true));
        }
        Map<String, JsonNode> oldInputs = ConnectionContracts.inputs(before);
        Map<String, JsonNode> newInputs = ConnectionContracts.inputs(after);
        for (String input : oldInputs.keySet()) {
            if (!newInputs.containsKey(input)) {
                out.add(new Change(name, "The input " + input + " is removed.", true));
            }
        }
        Set<String> oldRequired = ConnectionContracts.requiredInputs(before);
        for (String input : ConnectionContracts.requiredInputs(after)) {
            if (!oldRequired.contains(input)) {
                out.add(new Change(name, "The input " + input + " is now required.", true));
            }
        }
        for (String input : newInputs.keySet()) {
            if (!oldInputs.containsKey(input) && !ConnectionContracts.requiredInputs(after).contains(input)) {
                out.add(new Change(name, "New optional input " + input + ".", false));
            }
        }
        List<String> oldFields = leaves(before.path("responseSchema"), "");
        List<String> newFields = leaves(after.path("responseSchema"), "");
        for (String field : oldFields) {
            if (!newFields.contains(field)) {
                out.add(new Change(name, "The response no longer has " + field + ".", true));
            }
        }
        if (!before.path("path").asString().equals(after.path("path").asString())
                || !before.path("method").asString().equals(after.path("method").asString())) {
            out.add(new Change(name, "The address changes.", false));
        }
    }

    private static List<String> leaves(JsonNode schema, String at) {
        List<String> out = new ArrayList<>();
        if ("object".equals(schema.path("type").asString(""))) {
            for (String p : schema.path("properties").propertyNames()) {
                out.addAll(leaves(schema.path("properties").get(p), at + "/" + p));
            }
        } else if ("array".equals(schema.path("type").asString(""))) {
            out.addAll(leaves(schema.path("items"), at + "/[*]"));
        } else if (!at.isEmpty()) {
            out.add(at);
        }
        return out;
    }

    private Row contentsRow(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_package_version WHERE id = :id").param("id", id)
                .query((rs, n) -> row(rs)).single();
    }

    /** Workflows of this workspace whose draft or active release pins one of the package's connection keys. */
    private List<Usage> whereUsed(UUID ws, String packageId, Set<String> keys, UUID versionId) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> thisVersion = new LinkedHashMap<>();
        jdbc.sql("SELECT connection_key, connection_version FROM connector_package_connection WHERE workspace_id = :ws"
                + " AND package_version_id = :id").param("ws", ws).param("id", versionId)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2))).list()
                .forEach(e -> thisVersion.put(e.getKey(), e.getValue()));
        List<Usage> out = new ArrayList<>();
        for (String key : keys) {
            String path = "$.dependencies.connections[*] ? (@.key == $key).definitionVersion";
            String pinned = "(SELECT min(v::text::int) FROM jsonb_path_query(%s, '" + path + "', CAST(:vars AS jsonb)) v)";
            jdbc.sql("SELECT * FROM (SELECT w.id, w.key, w.name, " + pinned.formatted("d.definition") + " AS draft_version, "
                    + pinned.formatted("r.definition") + " AS release_version " + """
                    FROM workflow w
                    JOIN workflow_draft_revision d ON d.workflow_id = w.id AND d.revision = w.draft_revision
                    LEFT JOIN workflow_release r ON r.id = w.active_release_id
                    WHERE w.workspace_id = :ws AND w.archived_at IS NULL) u
                    WHERE u.draft_version IS NOT NULL OR u.release_version IS NOT NULL ORDER BY lower(u.name), u.id
                    """).param("ws", ws).param("vars", json.write(Map.of("key", key))).query((rs, n) -> {
                        Integer draft = (Integer) rs.getObject("draft_version");
                        Integer release = (Integer) rs.getObject("release_version");
                        Integer target = thisVersion.get(key);
                        boolean outdated = target != null && (draft != null && draft < target
                                || release != null && release < target);
                        return new Usage(Sql.uuid(rs, "id"), rs.getString("key"), rs.getString("name"), key, draft,
                                release, outdated);
                    }).list().forEach(out::add);
        }
        return out;
    }

    private Map<UUID, String> installStates(UUID ws) {
        Map<UUID, String> states = new LinkedHashMap<>();
        jdbc.sql("SELECT package_version_id, status FROM connector_package_install WHERE workspace_id = :ws")
                .param("ws", ws).query((rs, n) -> Map.entry(Sql.uuid(rs, "package_version_id"), rs.getString("status")))
                .list().forEach(e -> states.put(e.getKey(), e.getValue()));
        return states;
    }

    private Map<UUID, String> names(List<Row> rows) {
        List<UUID> ids = new ArrayList<>();
        rows.forEach(r -> {
            ids.add(r.importedBy());
            if (r.decidedBy() != null) {
                ids.add(r.decidedBy());
            }
        });
        return subjects.displayNames(ids.stream().distinct().toList());
    }

    private static Summary summary(Row r, Map<UUID, String> names, String installStatus) {
        return new Summary(r.id(), r.packageId(), r.name(), r.version(), r.publisher(), r.keyId(), r.sha256(),
                r.signatureStatus(), r.status(), new Subject(r.importedBy(), names.get(r.importedBy())), r.importedAt(),
                r.decidedBy() == null ? null : new Subject(r.decidedBy(), names.get(r.decidedBy())), r.decidedAt(),
                r.decisionNote(), installStatus, r.revision());
    }

    private void write(Caller caller, UUID ws, String action, UUID id, Map<String, ?> metadata) {
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), action,
                "connectorPackage", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }

    static ApiException invalid(String pointer, String code, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, code, message)));
    }

    private static int part(String version, int index) {
        try {
            return Integer.parseInt(version.split("\\.")[index]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

}
