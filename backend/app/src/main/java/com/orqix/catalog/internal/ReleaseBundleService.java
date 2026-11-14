package com.orqix.catalog.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-S02 release bundles (docs/build/S147). Publishing a bundle publishes the workflow release and, in the same
 * transaction, records every component the release pins: its rule tables, request form, shared decisions and forms,
 * document templates, working calendars, views, connection definitions, child workflows, AI skill versions and the
 * publication test suite, each with its version and SHA-256. The <b>bundle hash</b> covers type, portable key and
 * SHA-256 of each component (not workspace-local ids or version numbers), so the same content has the same hash in
 * every stage.
 * <p>
 * Promotion moves a bundle into a later-stage workspace of the same organization as a unit (S54 rules): shared
 * decisions, forms and views are reused when the target already has the same content, otherwise created or given a new
 * version there; the workflow arrives as a draft whose pins point at the target's versions; the test suite is copied.
 * Connections, document templates, calendars, child workflows and groups are never copied: the target must already
 * have them (bindings and secrets stay with each environment). Everything happens in one transaction; any blocker
 * leaves the target unchanged. The target then publishes the bundle through its own gates.
 */
@Service
public class ReleaseBundleService {

    /** One pinned component. {@code ref} is a workspace-local id (a view id, a release id) and is not hashed. */
    public record Component(String type, String key, String name, String version, String sha256, String ref) {
    }

    public record Source(UUID bundleId, String bundleHash, UUID workspaceId, String workspaceKey, boolean sameContent) {
    }

    public record BundleView(UUID id, UUID workflowId, UUID releaseId, int releaseNumber, String bundleHash,
            List<Component> components, Source source, SubjectService.SubjectRef publishedBy, Instant publishedAt) {
    }

    public record BundleList(List<BundleView> items) {
    }

    public record Published(ReleaseService.Published published, BundleView bundle) {
    }

    public record PublishResult(CatalogDtos.ReleaseView release, BundleView bundle, boolean replayed) {
    }

    public record PromoteRequest(@NotNull UUID targetWorkspaceId, Integer expectedDraftRevision) {
    }

    /**
     * What one component becomes in the target. {@code action}: reuse (the target has the same content), create,
     * newVersion, copy (carried inside the workflow draft or its test suite), ready (checked, already there) or
     * blocked ({@code problem} says why).
     */
    public record PlanItem(String type, String key, String name, String sourceVersion, String action,
            String targetVersion, String problem) {
    }

    public record PromotionPlan(boolean promotable, String bundleHash, String sourceStage, String targetStage,
            boolean approvalRequired, PromotionService.TargetWorkflow targetWorkflow, List<PlanItem> items,
            List<CatalogDtos.Issue> issues) {
    }

    public record BundlePromotion(UUID id, String bundleHash, UUID targetWorkspaceId, UUID targetWorkflowId,
            int draftRevision, boolean createdWorkflow, List<PlanItem> items) {
    }

    /** A view pinned by a workflow: the request form view or an approval step's view. */
    record ViewPin(String id, int version, String use) {
    }

    private record BundleRow(UUID id, UUID workspaceId, UUID workflowId, UUID releaseId, String hash,
            List<Component> components, UUID sourceBundleId, String sourceBundleHash, UUID sourceWorkspaceId,
            UUID publishedBy, Instant publishedAt) {
    }

    private final JdbcClient jdbc;
    private final CatalogStore store;
    private final WorkflowService workflows;
    private final ReleaseService releases;
    private final PromotionService promotions;
    private final SharedAssets assets;
    private final ApplicationViews views;
    private final TestSuiteService tests;
    private final AssetReferenceCheck assetCheck;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;

    public ReleaseBundleService(JdbcClient jdbc, CatalogStore store, WorkflowService workflows, ReleaseService releases,
            PromotionService promotions, SharedAssets assets, ApplicationViews views, TestSuiteService tests,
            AssetReferenceCheck assetCheck, WorkflowCompiler compiler, CompileContextFactory contexts,
            PolicyService policy, SubjectService subjects, AuditWriter audit, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.workflows = workflows;
        this.releases = releases;
        this.promotions = promotions;
        this.assets = assets;
        this.views = views;
        this.tests = tests;
        this.assetCheck = assetCheck;
        this.compiler = compiler;
        this.contexts = contexts;
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ publish

    @Transactional
    public PublishResult publish(Caller caller, UUID ws, UUID wf, String idempotencyKey,
            CatalogDtos.PublishRequest request) {
        Published p = publish(caller, ws, wf, idempotencyKey, request, Map.of());
        return new PublishResult(p.published().release(), p.bundle(), p.published().replayed());
    }

    /** Publishes the release and records its bundle in one transaction; {@code manifestExtras} go to the release. */
    @Transactional
    public Published publish(Caller caller, UUID ws, UUID wf, String idempotencyKey, CatalogDtos.PublishRequest request,
            Map<String, JsonNode> manifestExtras) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "publishReleaseBundle");
        policy.require(visible.access(), Permission.WORKFLOW_PUBLISH, "publishReleaseBundle", true);
        CatalogStore.DraftRow draft = store.draft(ws, wf, request.draftRevision()).orElse(null);
        if (draft != null) {
            List<String> missing = new ArrayList<>();
            for (ViewPin pin : viewPins(json.parse(draft.definitionJson()))) {
                if (viewVersion(ws, pin).isEmpty()) {
                    missing.add(pin.use() + ": view " + pin.id() + " version " + pin.version());
                }
            }
            if (!missing.isEmpty()) {
                throw new ApiException(ProblemCode.WORKFLOW_INVALID, "A bundle pins only published views; these are"
                        + " missing: " + String.join("; ", missing) + ".");
            }
        }
        ReleaseService.Published published = releases.publish(caller, ws, wf, idempotencyKey, request, manifestExtras);
        UUID releaseId = published.release().id();
        Optional<BundleRow> existing = row("WHERE b.workspace_id = :ws AND b.release_id = :id", ws, releaseId);
        if (existing.isPresent()) {
            return new Published(published, view(existing.get()));
        }
        CatalogStore.ReleaseRow release = store.release(ws, wf, releaseId).orElseThrow();
        List<Component> components = components(ws, visible.workflow(), release);
        String hash = bundleHash(components);
        record Origin(UUID bundle, String hash, UUID workspace) {
        }
        Optional<Origin> origin = jdbc.sql("""
                SELECT source_bundle_id, source_bundle_hash, source_workspace_id FROM release_bundle_promotion
                WHERE target_workspace_id = :ws AND target_workflow_id = :wf AND target_draft_revision = :rev
                ORDER BY promoted_at DESC, id DESC LIMIT 1
                """).param("ws", ws).param("wf", wf).param("rev", release.draftRevision())
                .query((rs, n) -> new Origin(Sql.uuid(rs, "source_bundle_id"), rs.getString("source_bundle_hash").trim(),
                        Sql.uuid(rs, "source_workspace_id"))).optional();
        UUID id = Ids.newId();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO release_bundle (id, workspace_id, workflow_id, release_id, bundle_hash, components,
                    source_bundle_id, source_bundle_hash, source_workspace_id, published_by, published_at)
                VALUES (:id, :ws, :wf, :release, :hash, CAST(:components AS jsonb), :sb, :sh, :sws, :by, :at)
                """).param("id", id).param("ws", ws).param("wf", wf).param("release", releaseId).param("hash", hash)
                .param("components", json.write(components)).param("sb", origin.map(Origin::bundle).orElse(null))
                .param("sh", origin.map(Origin::hash).orElse(null)).param("sws", origin.map(Origin::workspace).orElse(null))
                .param("by", caller.subjectId()).param("at", Sql.ts(now)).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("releaseNumber", release.releaseNumber());
        metadata.put("bundleHash", hash);
        metadata.put("components", components.size());
        metadata.put("sourceBundleHash", origin.map(Origin::hash).orElse(null));
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "release.bundle.published",
                "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new Published(published, view(row("WHERE b.id = :id", ws, id).orElseThrow()));
    }

    /** The pinned components of a published release, in a stable order. */
    List<Component> components(UUID ws, CatalogStore.WorkflowRow workflow, CatalogStore.ReleaseRow release) {
        JsonNode definition = json.parse(release.definitionJson());
        JsonNode manifest = json.parse(release.manifestJson());
        String releaseLabel = "release " + release.releaseNumber();
        List<Component> out = new ArrayList<>();
        out.add(new Component("workflow", workflow.key(), workflow.name(), releaseLabel, portableHash(definition),
                release.id().toString()));
        for (JsonNode table : definition.path("decisions")) {
            out.add(new Component("ruleTable", table.path("key").asString(""), table.path("name").asString(null),
                    releaseLabel, json.canonicalSha256(table), null));
        }
        ObjectNode form = json.object();
        form.set("inputSchema", definition.path("inputSchema").deepCopy());
        if (definition.has("inputUi")) {
            ObjectNode ui = (ObjectNode) definition.path("inputUi").deepCopy();
            ui.remove("viewRef");
            form.set("inputUi", ui);
        }
        out.add(new Component("requestForm", "request", "Request form", releaseLabel, json.canonicalSha256(form), null));
        for (JsonNode asset : manifest.path("assets")) {
            String kind = asset.path("kind").asString("");
            String key = asset.path("key").asString("");
            int version = asset.path("version").asInt();
            String type = switch (kind) {
                case SharedAssets.DECISION -> "sharedDecision";
                case SharedAssets.FORM -> "sharedForm";
                case SharedAssets.TEMPLATE -> "documentTemplate";
                case "calendar" -> "calendar";
                default -> null;
            };
            if (type == null) {
                continue;
            }
            out.add(new Component(type, key, assetName(ws, kind, key), Integer.toString(version),
                    assetSha(ws, kind, key, version).orElse(null), null));
        }
        for (ViewPin pin : viewPins(definition)) {
            var version = viewVersion(ws, pin);
            out.add(new Component("view", version.map(v -> v.name()).orElse(pin.id()), pin.use(),
                    Integer.toString(pin.version()), version.map(v -> json.canonicalSha256(v.definition())).orElse(null),
                    pin.id()));
        }
        for (JsonNode c : manifest.path("connections")) {
            out.add(new Component("connection", c.path("key").asString(""), null,
                    Integer.toString(c.path("definitionVersion").asInt()), c.path("definitionHash").asString(null), null));
        }
        for (JsonNode w : manifest.path("workflows")) {
            out.add(new Component("childWorkflow", w.path("workflowKey").asString(""), null,
                    "release " + w.path("releaseNumber").asInt(), w.path("semanticHash").asString(null),
                    w.path("releaseId").asString(null)));
        }
        for (JsonNode s : manifest.path("aiSkills")) {
            out.add(new Component("aiSkill", s.path("skillKey").asString(""), s.path("nodeId").asString(null),
                    s.path("version").asString(""), s.path("contentHash").asString(null), null));
        }
        TestSuiteService.Suite suite = suiteAt(ws, workflow.id(), release.publishedAt());
        if (!suite.cases().isEmpty()) {
            out.add(new Component("testSuite", workflow.key(), suite.cases().size() + " cases",
                    Integer.toString(suite.version()), json.canonicalSha256(json.toTree(suite.cases())), null));
        }
        return List.copyOf(out);
    }

    /** The suite that was current when the release was published (the one its publish gate checked). */
    private TestSuiteService.Suite suiteAt(UUID ws, UUID wf, Instant at) {
        return jdbc.sql("""
                SELECT version, cases::text FROM workflow_test_suite WHERE workspace_id = :ws AND workflow_id = :wf
                  AND created_at <= :at ORDER BY version DESC LIMIT 1
                """).param("ws", ws).param("wf", wf).param("at", Sql.ts(at)).query((rs, n) -> {
                    List<TestSuiteService.Case> cases = new ArrayList<>();
                    for (JsonNode c : json.parse(rs.getString(2))) {
                        cases.add(new TestSuiteService.Case(c.path("id").asString(), c.path("name").asString(),
                                c.path("input"), c.hasNonNull("scenario") ? c.path("scenario") : null,
                                c.path("expect").asString(), c.hasNonNull("assertions") ? c.path("assertions") : null));
                    }
                    return new TestSuiteService.Suite(rs.getInt(1), List.copyOf(cases));
                }).optional().orElse(new TestSuiteService.Suite(0, List.of()));
    }

    /** SHA-256 over sorted lines {@code type NUL key NUL sha256 LF}: portable across workspaces. */
    static String bundleHash(List<Component> components) {
        TreeSet<String> lines = new TreeSet<>();
        for (Component c : components) {
            lines.add(c.type() + '\0' + c.key() + '\0' + (c.sha256() == null ? "" : c.sha256()) + '\n');
        }
        return Json.sha256Hex(String.join("", lines));
    }

    /** The definition without workspace-local ids and version numbers of its pins, and without provenance. */
    String portableHash(JsonNode definition) {
        ObjectNode copy = (ObjectNode) definition.deepCopy();
        copy.remove("provenance");
        JsonNode ui = copy.path("inputUi");
        if (ui.isObject() && ui.has("viewRef")) {
            ((ObjectNode) ui).remove("viewRef");
        }
        for (JsonNode node : copy.path("nodes")) {
            JsonNode config = node.path("config");
            if (config.isObject() && config.path("viewBinding").isObject()) {
                ((ObjectNode) config.path("viewBinding")).remove("id");
                ((ObjectNode) config.path("viewBinding")).remove("version");
            }
        }
        for (JsonNode asset : copy.path("dependencies").path("assets")) {
            if (asset.isObject()) {
                ((ObjectNode) asset).remove("version");
            }
        }
        return json.canonicalSha256(copy);
    }

    static List<ViewPin> viewPins(JsonNode definition) {
        List<ViewPin> pins = new ArrayList<>();
        JsonNode ref = definition.path("inputUi").path("viewRef");
        if (ref.path("id").isString()) {
            pins.add(new ViewPin(ref.path("id").asString(), ref.path("version").asInt(), "Request form"));
        }
        for (JsonNode node : definition.path("nodes")) {
            JsonNode binding = node.path("config").path("viewBinding");
            if (binding.path("id").isString()) {
                pins.add(new ViewPin(binding.path("id").asString(), binding.path("version").asInt(),
                        "Approval step " + node.path("id").asString("")));
            }
        }
        return pins;
    }

    private record ViewVersion(String name, String kind, JsonNode definition) {
    }

    private Optional<ViewVersion> viewVersion(UUID ws, ViewPin pin) {
        UUID id;
        try {
            id = UUID.fromString(pin.id());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT a.name, a.kind, v.definition::text AS definition FROM application_view_version v
                JOIN application_view a ON a.workspace_id = v.workspace_id AND a.id = v.view_id
                WHERE v.workspace_id = :ws AND v.view_id = :id AND v.version = :v
                """).param("ws", ws).param("id", id).param("v", pin.version())
                .query((rs, n) -> new ViewVersion(rs.getString("name"), rs.getString("kind"),
                        json.parse(rs.getString("definition")))).optional();
    }

    private static String assetTable(String kind) {
        return switch (kind) {
            case SharedAssets.DECISION -> "decision_asset";
            case SharedAssets.FORM -> "form_asset";
            case SharedAssets.TEMPLATE -> "document_template";
            case "calendar" -> "working_calendar";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String versionTable(String kind) {
        return switch (kind) {
            case SharedAssets.DECISION -> "decision_asset_version v ON v.asset_id = a.id";
            case SharedAssets.FORM -> "form_asset_version v ON v.asset_id = a.id";
            case SharedAssets.TEMPLATE -> "document_template_version v ON v.template_id = a.id";
            case "calendar" -> "working_calendar_version v ON v.calendar_id = a.id";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private Optional<String> assetSha(UUID ws, String kind, String key, int version) {
        return jdbc.sql("SELECT v.sha256 FROM " + assetTable(kind) + " a JOIN " + versionTable(kind)
                + " WHERE a.workspace_id = :ws AND a.key = :key AND v.version = :v").param("ws", ws).param("key", key)
                .param("v", version).query(String.class).optional().map(String::trim);
    }

    private String assetName(UUID ws, String kind, String key) {
        return jdbc.sql("SELECT name FROM " + assetTable(kind) + " WHERE workspace_id = :ws AND key = :key")
                .param("ws", ws).param("key", key).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public BundleList list(Caller caller, UUID ws, UUID wf) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "listReleaseBundles");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "listReleaseBundles", false);
        List<BundleRow> rows = jdbc.sql(SELECT + " WHERE b.workspace_id = :ws AND b.workflow_id = :wf"
                + " ORDER BY b.published_at DESC, b.id DESC LIMIT 50").param("ws", ws).param("wf", wf).query(this::map).list();
        return new BundleList(rows.stream().map(this::view).toList());
    }

    @Transactional(readOnly = true)
    public BundleView forRelease(Caller caller, UUID ws, UUID wf, UUID releaseId) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "getReleaseBundle");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "getReleaseBundle", false);
        return row("WHERE b.workspace_id = :ws AND b.release_id = :id", ws, releaseId)
                .filter(b -> b.workflowId().equals(wf)).map(this::view).orElseThrow(ApiException::notFound);
    }

    private static final String SELECT = """
            SELECT b.id, b.workspace_id, b.workflow_id, b.release_id, b.bundle_hash, b.components::text AS components,
                   b.source_bundle_id, b.source_bundle_hash, b.source_workspace_id, b.published_by, b.published_at
            FROM release_bundle b""";

    private Optional<BundleRow> row(String where, UUID ws, UUID id) {
        return jdbc.sql(SELECT + " " + where).param("ws", ws).param("id", id).query(this::map).optional();
    }

    private BundleRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        List<Component> components = new ArrayList<>();
        for (JsonNode c : json.parse(rs.getString("components"))) {
            components.add(json.convert(c, Component.class));
        }
        String sourceHash = rs.getString("source_bundle_hash");
        return new BundleRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "workflow_id"),
                Sql.uuid(rs, "release_id"), rs.getString("bundle_hash").trim(), List.copyOf(components),
                Sql.uuid(rs, "source_bundle_id"), sourceHash == null ? null : sourceHash.trim(),
                Sql.uuid(rs, "source_workspace_id"), Sql.uuid(rs, "published_by"), Sql.instant(rs, "published_at"));
    }

    private BundleView view(BundleRow row) {
        int releaseNumber = jdbc.sql("SELECT release_number FROM workflow_release WHERE workspace_id = :ws AND id = :id")
                .param("ws", row.workspaceId()).param("id", row.releaseId()).query(Integer.class).single();
        Source source = null;
        if (row.sourceBundleId() != null) {
            String key = jdbc.sql("SELECT key FROM workspace WHERE id = :id").param("id", row.sourceWorkspaceId())
                    .query(String.class).optional().orElse(null);
            source = new Source(row.sourceBundleId(), row.sourceBundleHash(), row.sourceWorkspaceId(), key,
                    row.hash().equals(row.sourceBundleHash()));
        }
        return new BundleView(row.id(), row.workflowId(), row.releaseId(), releaseNumber, row.hash(), row.components(),
                source, subjects.ref(row.publishedBy()), row.publishedAt());
    }

    // ------------------------------------------------------------------ promotion

    private record Prepared(BundleRow bundle, CatalogStore.ReleaseRow release, CatalogStore.WorkflowRow workflow,
            WorkspaceAccess source, WorkspaceAccess target, PromotionService.Report report, PromotionPlan plan) {
    }

    @Transactional(readOnly = true)
    public PromotionPlan check(Caller caller, UUID ws, UUID bundleId, PromoteRequest request) {
        return prepare(caller, ws, bundleId, request, "checkBundlePromotion").plan();
    }

    private Prepared prepare(Caller caller, UUID ws, UUID bundleId, PromoteRequest request, String operation) {
        WorkspaceAccess sourceAccess = policy.requireVisible(caller, ws);
        BundleRow bundle = row("WHERE b.workspace_id = :ws AND b.id = :id", ws, bundleId).orElseThrow(ApiException::notFound);
        WorkflowService.Visible visible = workflows.visible(caller, ws, bundle.workflowId(), operation);
        CatalogStore.ReleaseRow release = store.release(ws, bundle.workflowId(), bundle.releaseId()).orElseThrow();
        // The S54 rules: workflow.export here, workflow.edit there, same organization, a strictly later stage.
        PromotionService.Report report = promotions.check(caller, ws, bundle.workflowId(), bundle.releaseId(),
                new PromotionService.PromoteRequest(request.targetWorkspaceId(), null));
        WorkspaceAccess target = policy.requireVisible(caller, request.targetWorkspaceId());
        UUID tws = target.workspace().id();
        boolean canPublishViews = target.has(Permission.WORKFLOW_PUBLISH);
        Map<String, String> connectionStatus = new LinkedHashMap<>();
        report.connections().forEach(c -> connectionStatus.put(c.key(), c.status()));
        Map<String, Boolean> childPublished = new LinkedHashMap<>();
        report.workflows().forEach(w -> childPublished.put(w.workflowKey(), w.publishedInTarget()));
        List<PlanItem> items = new ArrayList<>();
        for (Component c : bundle.components()) {
            items.add(switch (c.type()) {
                case "workflow" -> new PlanItem(c.type(), c.key(), c.name(), c.version(),
                        report.targetWorkflow().exists() ? "newVersion" : "create",
                        report.targetWorkflow().exists() ? "draft revision " + (report.targetWorkflow().draftRevision() + 1)
                                : "draft revision 1", null);
                case "ruleTable", "requestForm", "testSuite" -> new PlanItem(c.type(), c.key(), c.name(), c.version(),
                        "copy", null, null);
                case "sharedDecision", "sharedForm" -> sharedPlan(tws, c);
                case "documentTemplate", "calendar" -> referencedPlan(tws, c);
                case "view" -> viewPlan(ws, tws, c, canPublishViews);
                case "connection" -> {
                    String status = connectionStatus.getOrDefault(c.key(), "definitionMissing");
                    yield new PlanItem(c.type(), c.key(), c.name(), c.version(), "ready".equals(status) ? "ready" : "blocked",
                            "ready".equals(status) ? c.version() : null, "ready".equals(status) ? null : switch (status) {
                                case "bindingMissing" -> "The target has no enabled binding for this connection.";
                                case "definitionDiffers" -> "The target's definition version " + c.version() + " differs.";
                                default -> "The target has no definition version " + c.version() + " of this connection.";
                            });
                }
                case "childWorkflow" -> Boolean.TRUE.equals(childPublished.get(c.key()))
                        ? new PlanItem(c.type(), c.key(), c.name(), c.version(), "ready", "active release", null)
                        : new PlanItem(c.type(), c.key(), c.name(), c.version(), "blocked", null,
                                "Publish this workflow in the target first.");
                default -> new PlanItem(c.type(), c.key(), c.name(), c.version(), "ready", null,
                        null); // AI skills: the target's compiler run checks the pinned version
            });
        }
        boolean blocked = items.stream().anyMatch(i -> "blocked".equals(i.action()));
        PromotionPlan plan = new PromotionPlan(!blocked && report.compatible() && !report.approvalRequired(),
                bundle.hash(), report.sourceStage(), report.targetStage(), report.approvalRequired(),
                report.targetWorkflow(), List.copyOf(items), report.issues());
        return new Prepared(bundle, release, visible.workflow(), sourceAccess, target, report, plan);
    }

    private PlanItem sharedPlan(UUID target, Component c) {
        String kind = "sharedDecision".equals(c.type()) ? SharedAssets.DECISION : SharedAssets.FORM;
        record Target(int latest, boolean archived, Integer same) {
        }
        Optional<Target> found = jdbc.sql("SELECT a.latest_version, a.archived_at IS NOT NULL AS archived,"
                + " (SELECT max(v.version) FROM " + versionTable(kind).replace(" ON v.asset_id = a.id", "")
                + " WHERE v.asset_id = a.id AND v.sha256 = :sha) AS same FROM " + assetTable(kind) + " a"
                + " WHERE a.workspace_id = :ws AND a.key = :key").param("ws", target).param("key", c.key())
                .param("sha", c.sha256() == null ? "" : c.sha256())
                .query((rs, n) -> new Target(rs.getInt(1), rs.getBoolean(2), (Integer) rs.getObject(3))).optional();
        if (found.isPresent()) {
            Target t = found.get();
            if (t.same() != null) {
                return new PlanItem(c.type(), c.key(), c.name(), c.version(), "reuse", Integer.toString(t.same()), null);
            }
            if (t.archived()) {
                return new PlanItem(c.type(), c.key(), c.name(), c.version(), "blocked", null,
                        "This shared asset is archived in the target; restore it first.");
            }
            return new PlanItem(c.type(), c.key(), c.name(), c.version(), "newVersion", Integer.toString(t.latest() + 1), null);
        }
        boolean nameTaken = c.name() != null && jdbc.sql("SELECT count(*) > 0 FROM " + assetTable(kind)
                + " WHERE workspace_id = :ws AND lower(name) = lower(:name)").param("ws", target).param("name", c.name())
                .query(Boolean.class).single();
        if (nameTaken) {
            return new PlanItem(c.type(), c.key(), c.name(), c.version(), "blocked", null,
                    "Another shared asset in the target already has the name " + c.name() + ".");
        }
        return new PlanItem(c.type(), c.key(), c.name(), c.version(), "create", "1", null);
    }

    private PlanItem referencedPlan(UUID target, Component c) {
        String kind = "calendar".equals(c.type()) ? "calendar" : SharedAssets.TEMPLATE;
        Integer same = jdbc.sql("SELECT max(v.version) FROM " + assetTable(kind) + " a JOIN " + versionTable(kind)
                + " WHERE a.workspace_id = :ws AND a.key = :key AND v.sha256 = :sha AND a.archived_at IS NULL")
                .param("ws", target).param("key", c.key()).param("sha", c.sha256() == null ? "" : c.sha256())
                .query(Integer.class).optional().orElse(null);
        return same == null
                ? new PlanItem(c.type(), c.key(), c.name(), c.version(), "blocked", null,
                        "The target needs this " + ("calendar".equals(kind) ? "working calendar" : "document template")
                                + " with the same content; its owner adds it there.")
                : new PlanItem(c.type(), c.key(), c.name(), c.version(), "reuse", Integer.toString(same), null);
    }

    private PlanItem viewPlan(UUID source, UUID target, Component c, boolean canPublish) {
        String kind = jdbc.sql("""
                SELECT a.kind FROM application_view a WHERE a.workspace_id = :ws AND a.id = :id
                """).param("ws", source).param("id", UUID.fromString(c.ref())).query(String.class).optional().orElse("request");
        record Match(UUID id, int version) {
        }
        List<Match> matches = new ArrayList<>();
        jdbc.sql("""
                SELECT a.id, v.version, v.definition::text AS definition FROM application_view a
                JOIN application_view_version v ON v.workspace_id = a.workspace_id AND v.view_id = a.id
                WHERE a.workspace_id = :ws AND lower(a.name) = lower(:name) AND a.kind = :kind ORDER BY v.version DESC
                """).param("ws", target).param("name", c.key()).param("kind", kind).query((rs, n) -> {
                    if (json.canonicalSha256(json.parse(rs.getString("definition"))).equals(c.sha256())) {
                        matches.add(new Match(Sql.uuid(rs, "id"), rs.getInt("version")));
                    }
                    return null;
                }).list();
        if (!matches.isEmpty()) {
            return new PlanItem(c.type(), c.key(), c.name(), c.version(), "reuse", Integer.toString(matches.getFirst().version()),
                    null);
        }
        record Existing(UUID id, String kind, int revision, Integer publishedRevision, Integer published) {
        }
        Optional<Existing> existing = jdbc.sql("""
                SELECT id, kind, draft_revision, published_draft_revision, published_version FROM application_view
                WHERE workspace_id = :ws AND lower(name) = lower(:name)
                """).param("ws", target).param("name", c.key()).query((rs, n) -> new Existing(Sql.uuid(rs, "id"),
                rs.getString("kind"), rs.getInt("draft_revision"), (Integer) rs.getObject("published_draft_revision"),
                (Integer) rs.getObject("published_version"))).optional();
        if (!canPublish) {
            return new PlanItem(c.type(), c.key(), c.name(), c.version(), "blocked", null,
                    "Publishing this view in the target needs workflow.publish there.");
        }
        if (existing.isPresent()) {
            Existing e = existing.get();
            if (!e.kind().equals(kind)) {
                return new PlanItem(c.type(), c.key(), c.name(), c.version(), "blocked", null,
                        "The target has a view with this name of another kind.");
            }
            if (e.publishedRevision() == null || e.publishedRevision() != e.revision()) {
                return new PlanItem(c.type(), c.key(), c.name(), c.version(), "blocked", null,
                        "The target's view with this name has unpublished changes; publish or discard them first.");
            }
            return new PlanItem(c.type(), c.key(), c.name(), c.version(), "newVersion",
                    Integer.toString((e.published() == null ? 0 : e.published()) + 1), null);
        }
        return new PlanItem(c.type(), c.key(), c.name(), c.version(), "create", "1", null);
    }

    @Transactional
    public BundlePromotion promote(Caller caller, UUID ws, UUID bundleId, PromoteRequest request) {
        Prepared p = prepare(caller, ws, bundleId, request, "promoteReleaseBundle");
        PromotionPlan plan = p.plan();
        if (plan.approvalRequired()) {
            throw new ApiException(ProblemCode.PROMOTION_APPROVAL_REQUIRED, "The target requires promotion approval;"
                    + " bundles are promoted only into stages without an approval gate. Request a release promotion instead.");
        }
        if (!plan.promotable()) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The bundle cannot be promoted: resolve the blocked items"
                    + " and findings in the target.").with("items", plan.items()).with("issues", plan.issues());
        }
        UUID target = request.targetWorkspaceId();
        ObjectNode definition = (ObjectNode) json.parse(p.release().definitionJson()).deepCopy();
        definition.remove("provenance");
        Map<String, Integer> assetVersions = new LinkedHashMap<>();
        Map<String, ViewPin> viewTargets = new LinkedHashMap<>();
        String note = "Promoted from " + p.source().workspace().key() + " (bundle " + p.bundle().hash().substring(0, 12) + ")";
        for (int i = 0; i < plan.items().size(); i++) {
            PlanItem item = plan.items().get(i);
            Component c = p.bundle().components().get(i);
            switch (c.type()) {
                case "sharedDecision", "sharedForm" -> assetVersions.put(
                        ("sharedDecision".equals(c.type()) ? SharedAssets.DECISION : SharedAssets.FORM) + "|" + c.key(),
                        promoteAsset(caller, ws, target, c, item, note));
                case "documentTemplate", "calendar" -> assetVersions.put(("calendar".equals(c.type()) ? "calendar"
                        : SharedAssets.TEMPLATE) + "|" + c.key(), Integer.parseInt(item.targetVersion()));
                case "view" -> viewTargets.put(c.ref() + "|" + c.version(), promoteView(caller, ws, target, c, item));
                default -> {
                }
            }
        }
        for (JsonNode asset : definition.path("dependencies").path("assets")) {
            Integer version = assetVersions.get(asset.path("kind").asString("") + "|" + asset.path("key").asString(""));
            if (version != null) {
                ((ObjectNode) asset).put("version", version);
            }
        }
        rewriteView(definition.path("inputUi").path("viewRef"), viewTargets);
        for (JsonNode node : definition.path("nodes")) {
            rewriteView(node.path("config").path("viewBinding"), viewTargets);
        }
        String definitionJson = json.write(definition);

        // The draft, as an S54 promotion saves it.
        boolean created = !plan.targetWorkflow().exists();
        UUID targetWorkflow;
        int revision;
        if (created) {
            if (request.expectedDraftRevision() != null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "The target workflow does not exist yet, so there is"
                        + " no draft revision to expect.");
            }
            targetWorkflow = Ids.newId();
            if (!store.insertWorkflow(targetWorkflow, target, p.workflow().key(), p.workflow().name(),
                    p.workflow().description(), caller.subjectId())) {
                throw new ApiException(ProblemCode.DUPLICATE_KEY, "A workflow with this key already exists in the target.");
            }
            revision = 1;
        } else {
            targetWorkflow = plan.targetWorkflow().id();
            CatalogStore.WorkflowRow existing = store.lockWorkflow(target, targetWorkflow).orElseThrow(ApiException::notFound);
            if (request.expectedDraftRevision() == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "The target already has this workflow; send"
                        + " expectedDraftRevision (" + existing.draftRevision() + ").");
            }
            if (request.expectedDraftRevision() != existing.draftRevision()
                    || !store.advanceDraft(target, targetWorkflow, existing.draftRevision())) {
                throw new ApiException(ProblemCode.STALE_REVISION, "The target draft was saved by someone else.")
                        .with("currentRevision", existing.draftRevision());
            }
            revision = existing.draftRevision() + 1;
        }
        store.insertDraft(target, targetWorkflow, revision, definitionJson, "0.1", caller.subjectId());
        TestSuiteService.Suite sourceSuite = suiteAt(ws, p.workflow().id(), p.release().publishedAt());
        if (!sourceSuite.cases().isEmpty()) {
            TestSuiteService.Suite targetSuite = tests.latest(target, targetWorkflow);
            if (!json.toTree(targetSuite.cases()).equals(json.toTree(sourceSuite.cases()))) {
                tests.save(caller, target, targetWorkflow, new TestSuiteService.Save(targetSuite.version(), sourceSuite.cases()));
            }
        }
        // The authoritative check: the target's compiler and shared-asset check on the rewritten draft.
        ValidationReport report = compiler.validate(definitionJson, contexts.create(p.target().workspace(), definitionJson));
        List<ValidationIssue> assetIssues = assetCheck.issues(target, definitionJson);
        if (!report.publishable() || AssetReferenceCheck.hasBlocker(assetIssues)) {
            List<ValidationIssue> all = new ArrayList<>(report.issues());
            all.addAll(assetIssues);
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The promoted draft would not be publishable in the target;"
                    + " nothing was changed there.").with("issues", WorkflowService.issues(all));
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO release_bundle_promotion (id, organization_id, source_workspace_id, source_bundle_id,
                    source_bundle_hash, target_workspace_id, target_workflow_id, target_draft_revision, created_target,
                    components, promoted_by, promoted_at)
                VALUES (:id, :org, :sws, :bundle, :hash, :tws, :twf, :rev, :created, CAST(:items AS jsonb), :by, :at)
                """).param("id", id).param("org", p.source().workspace().organizationId()).param("sws", ws)
                .param("bundle", bundleId).param("hash", p.bundle().hash()).param("tws", target).param("twf", targetWorkflow)
                .param("rev", revision).param("created", created).param("items", json.write(plan.items()))
                .param("by", caller.subjectId()).param("at", Sql.ts(clock.instant())).update();
        Map<String, Object> sourceMeta = new LinkedHashMap<>();
        sourceMeta.put("bundleHash", p.bundle().hash());
        sourceMeta.put("targetWorkspaceId", target.toString());
        sourceMeta.put("targetWorkflowId", targetWorkflow.toString());
        sourceMeta.put("draftRevision", revision);
        sourceMeta.put("createdWorkflow", created);
        sourceMeta.put("components", plan.items().size());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "release.bundle.promoted",
                "release", p.release().id().toString(), AuditWriter.Outcome.SUCCESS, sourceMeta));
        Map<String, Object> targetMeta = new LinkedHashMap<>();
        targetMeta.put("bundleHash", p.bundle().hash());
        targetMeta.put("sourceWorkspaceId", ws.toString());
        targetMeta.put("targetWorkflowId", targetWorkflow.toString());
        targetMeta.put("draftRevision", revision);
        targetMeta.put("createdWorkflow", created);
        targetMeta.put("components", plan.items().size());
        audit.write(new AuditWriter.Entry(target, AuditWriter.ActorType.USER, caller.subjectId(), "release.bundle.received",
                "workflow", targetWorkflow.toString(), AuditWriter.Outcome.SUCCESS, targetMeta));
        return new BundlePromotion(id, p.bundle().hash(), target, targetWorkflow, revision, created, plan.items());
    }

    private int promoteAsset(Caller caller, UUID source, UUID target, Component c, PlanItem item, String note) {
        String kind = "sharedDecision".equals(c.type()) ? SharedAssets.DECISION : SharedAssets.FORM;
        if ("reuse".equals(item.action())) {
            return Integer.parseInt(item.targetVersion());
        }
        record Asset(String name, String description, JsonNode content) {
        }
        Asset asset = jdbc.sql("SELECT a.name, a.description, v.content::text AS content FROM " + assetTable(kind) + " a JOIN "
                + versionTable(kind) + " WHERE a.workspace_id = :ws AND a.key = :key AND v.version = :v")
                .param("ws", source).param("key", c.key()).param("v", Integer.parseInt(c.version()))
                .query((rs, n) -> new Asset(rs.getString("name"), rs.getString("description"),
                        json.parse(rs.getString("content")))).optional().orElseThrow(() -> new ApiException(
                                ProblemCode.WORKFLOW_INVALID, "The shared asset " + c.key() + " version " + c.version()
                                        + " no longer exists in the source."));
        SharedAssets.Detail detail;
        if ("create".equals(item.action())) {
            detail = SharedAssets.DECISION.equals(kind)
                    ? assets.createDecision(caller, target, new SharedAssets.CreateDecisionRequest(c.key(), asset.name(),
                            asset.description(), asset.content(), note))
                    : assets.createForm(caller, target, new SharedAssets.CreateFormRequest(c.key(), asset.name(),
                            asset.description(), asset.content(), note));
        } else {
            int latest = Integer.parseInt(item.targetVersion()) - 1;
            detail = SharedAssets.DECISION.equals(kind)
                    ? assets.addDecisionVersion(caller, target, c.key(), new SharedAssets.DecisionVersionRequest(latest,
                            asset.content(), note))
                    : assets.addFormVersion(caller, target, c.key(), new SharedAssets.FormVersionRequest(latest,
                            asset.content(), note));
        }
        return detail.asset().latestVersion();
    }

    private ViewPin promoteView(Caller caller, UUID source, UUID target, Component c, PlanItem item) {
        if ("reuse".equals(item.action())) {
            UUID id = jdbc.sql("""
                    SELECT a.id FROM application_view a JOIN application_view_version v
                      ON v.workspace_id = a.workspace_id AND v.view_id = a.id
                    WHERE a.workspace_id = :ws AND lower(a.name) = lower(:name) AND v.version = :v ORDER BY a.id LIMIT 1
                    """).param("ws", target).param("name", c.key()).param("v", Integer.parseInt(item.targetVersion()))
                    .query(UUID.class).single();
            return new ViewPin(id.toString(), Integer.parseInt(item.targetVersion()), c.name());
        }
        record SourceView(String name, String description, JsonNode definition) {
        }
        SourceView view = jdbc.sql("""
                SELECT name, description, definition::text AS definition FROM application_view_version
                WHERE workspace_id = :ws AND view_id = :id AND version = :v
                """).param("ws", source).param("id", UUID.fromString(c.ref())).param("v", Integer.parseInt(c.version()))
                .query((rs, n) -> new SourceView(rs.getString("name"), rs.getString("description"),
                        json.parse(rs.getString("definition")))).single();
        ApplicationViews.Detail saved;
        if ("create".equals(item.action())) {
            saved = views.create(caller, target, new ApplicationViews.Create(view.name(), view.description(),
                    view.definition()));
        } else {
            UUID id = jdbc.sql("SELECT id FROM application_view WHERE workspace_id = :ws AND lower(name) = lower(:name)")
                    .param("ws", target).param("name", c.key()).query(UUID.class).single();
            ApplicationViews.Detail current = views.get(caller, target, id);
            saved = views.save(caller, target, id, new ApplicationViews.Save(current.draftRevision(), view.name(),
                    view.description(), view.definition()));
        }
        ApplicationViews.Detail published = views.publish(caller, target, saved.id(),
                new ApplicationViews.Publish(saved.draftRevision()));
        return new ViewPin(published.id().toString(), published.publishedVersion(), c.name());
    }

    private static void rewriteView(JsonNode pin, Map<String, ViewPin> targets) {
        if (!pin.isObject() || !pin.path("id").isString()) {
            return;
        }
        ViewPin target = targets.get(pin.path("id").asString() + "|" + pin.path("version").asInt());
        if (target != null) {
            ((ObjectNode) pin).put("id", target.id()).put("version", target.version());
        }
    }
}
