package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.catalog.git.GitException;
import com.orqix.catalog.git.GitHttp;
import com.orqix.catalog.git.GitProvider;
import com.orqix.compiler.CompilationException;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.CompiledWorkflow;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.GitCredentials;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-S01 Git sync (docs/build/S147). A workspace has at most one Git connection. People export a draft to a review
 * branch and pull request ({@code workflow.export}), import a branch or commit as a new draft after reviewing the
 * differences ({@code workflow.edit}), and publish only from a commit that is the approved head (or merge commit) of
 * its pull request and whose source files have the content hash of the draft being published ({@code workflow.publish}).
 * The release manifest and evidence pack record the commit, the hash and the approvals. The Git token is resolved
 * through the secret providers at each call and never stored; no binding, secret or credential-like member is pushed
 * or pulled. Every push, pull and publish is audited and recorded in {@code git_sync_event}.
 */
@Service
public class GitSyncService {

    static final String BRANCH = "^(?!.*\\.\\.)(?!.*//)(?!.*\\.lock$)(?!.*/$)[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$";
    static final String COMMIT = "^[0-9a-f]{40}([0-9a-f]{24})?$";
    static final String BASE_PATH = "^[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*$";
    private static final java.util.regex.Pattern BRANCH_PATTERN = java.util.regex.Pattern.compile(BRANCH);
    private static final java.util.regex.Pattern BASE_PATH_PATTERN = java.util.regex.Pattern.compile(BASE_PATH);
    private static final java.util.regex.Pattern REPOSITORY = java.util.regex.Pattern
            .compile("^[A-Za-z0-9][A-Za-z0-9._ -]*(/[A-Za-z0-9][A-Za-z0-9._ -]*){1,3}$");

    // ------------------------------------------------------------------ API shapes

    public record ConnectionView(String provider, String apiUrl, String repository, String baseBranch, String basePath,
            String credentialRef, boolean enabled, int version, SubjectService.SubjectRef updatedBy, Instant updatedAt) {
    }

    public record ConnectionState(boolean configured, ConnectionView connection, List<String> providers) {
    }

    public record SaveConnection(@NotBlank String provider, @Size(max = 300) String apiUrl,
            @NotBlank @Size(min = 3, max = 200) String repository, @Size(max = 100) String baseBranch,
            @Size(max = 100) String basePath, @NotBlank @Size(max = 400) String credentialRef, Boolean enabled,
            @Min(0) int expectedVersion) {
    }

    public record DeleteConnection(@Min(1) int expectedVersion) {
    }

    public record CheckResult(boolean reachable, String defaultBranch, boolean baseBranchExists, String message) {
    }

    public record PushRequest(@NotNull @Min(1) Integer draftRevision, @Size(max = 100) @Pattern(regexp = BRANCH) String branch,
            @Size(max = 200) String title, @Size(max = 2000) String description) {
    }

    public record PullRequestView(String id, String url, String state, String sourceBranch, String headCommit,
            List<String> approvedBy) {
        static PullRequestView of(GitProvider.PullRequest pr) {
            return pr == null ? null : new PullRequestView(pr.id(), pr.url(), pr.state(), pr.sourceBranch(),
                    pr.headCommit(), pr.approvedBy());
        }
    }

    public record PushResult(String branch, String commit, boolean unchanged, String contentHash, int draftRevision,
            List<String> changedFiles, PullRequestView pullRequest) {
    }

    public record ImportPreview(String branch, String commit, String contentHash, int currentDraftRevision,
            String draftContentHash, boolean identical, boolean testsIncluded, List<GitLayout.Change> changes,
            List<CatalogDtos.Issue> issues) {
    }

    public record ImportRequest(@NotBlank @Size(max = 100) @Pattern(regexp = BRANCH) String branch,
            @NotBlank @Pattern(regexp = COMMIT) String commit, @NotNull @Min(1) Integer expectedDraftRevision,
            @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9._-]{1,40}$") String pullRequest) {
    }

    public record ImportResult(int draftRevision, boolean unchanged, String commit, String contentHash,
            boolean testsChanged, int testSuiteVersion, List<GitLayout.Change> changes) {
    }

    public record PublishRequest(@NotNull @Min(1) Integer draftRevision, @NotBlank @Pattern(regexp = COMMIT) String commit,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9._-]{1,40}$") String pullRequest,
            Boolean acknowledgeWarnings, Boolean activate, @Size(max = 1000) String releaseNote, Boolean asBundle) {
    }

    public record GitSource(String provider, String repository, String branch, String commit, String contentHash,
            String pullRequest, String pullRequestUrl, String reviewState, List<String> approvedBy, Instant verifiedAt) {
    }

    public record PublishResult(CatalogDtos.ReleaseView release, GitSource gitSource,
            ReleaseBundleService.BundleView bundle, boolean replayed) {
    }

    public record SyncEvent(UUID id, String direction, String branch, String commit, String contentHash,
            Integer draftRevision, UUID releaseId, String pullRequest, String pullRequestUrl,
            SubjectService.SubjectRef actor, Instant occurredAt) {
    }

    private record Conn(UUID workspaceId, String provider, String apiUrl, String repository, String baseBranch,
            String basePath, String credentialRef, boolean enabled, int version, UUID updatedBy, Instant updatedAt) {
    }

    /** Source files read from a commit, normalized. */
    private record Incoming(ObjectNode definition, List<TestSuiteService.Case> cases, boolean testsIncluded,
            String contentHash) {
    }

    private final JdbcClient jdbc;
    private final CatalogStore store;
    private final WorkflowService workflows;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final TestSuiteService tests;
    private final ReleaseService releases;
    private final ReleaseBundleService bundles;
    private final ConnectionService connections;
    private final GitCredentials credentials;
    private final EgressPolicy egress;
    private final Map<String, GitProvider> providers;
    private final AuditWriter audit;
    private final OrqixProperties properties;
    private final Json json;
    private final Clock clock;
    private final GitHttp http = new GitHttp(Duration.ofSeconds(15));

    public GitSyncService(JdbcClient jdbc, CatalogStore store, WorkflowService workflows, PolicyService policy,
            SubjectService subjects, WorkflowCompiler compiler, CompileContextFactory contexts, TestSuiteService tests,
            ReleaseService releases, ReleaseBundleService bundles, ConnectionService connections,
            GitCredentials credentials, EgressPolicy egress, List<GitProvider> providers, AuditWriter audit,
            OrqixProperties properties, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.workflows = workflows;
        this.policy = policy;
        this.subjects = subjects;
        this.compiler = compiler;
        this.contexts = contexts;
        this.tests = tests;
        this.releases = releases;
        this.bundles = bundles;
        this.connections = connections;
        this.credentials = credentials;
        this.egress = egress;
        this.providers = providers.stream().collect(Collectors.toMap(GitProvider::kind, Function.identity(),
                (a, b) -> a, TreeMap::new));
        this.audit = audit;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ connection

    @Transactional(readOnly = true)
    public ConnectionState connection(Caller caller, UUID ws) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.CONNECTION_READ, "getGitConnection", false);
        boolean manager = access.has(Permission.CONNECTION_MANAGE);
        return new ConnectionState(load(ws).isPresent(), load(ws).map(c -> view(c, manager)).orElse(null),
                List.copyOf(providers.keySet()));
    }

    @Transactional
    public ConnectionState save(Caller caller, UUID ws, SaveConnection request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.CONNECTION_MANAGE, "saveGitConnection", true);
        GitProvider provider = providers.get(request.provider());
        if (provider == null) {
            throw field("/provider", "enum", "Choose one of " + String.join(", ", providers.keySet()) + ".");
        }
        String repository = request.repository().strip();
        if (!REPOSITORY.matcher(repository).matches() || !provider.acceptsRepository(repository)) {
            throw field("/repository", "pattern", "Use the form " + provider.repositoryShape() + ".");
        }
        String apiUrl = request.apiUrl() == null || request.apiUrl().isBlank() ? provider.defaultApiUrl()
                : GitHttpUrl.strip(request.apiUrl());
        try {
            egress.check(apiUrl);
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new ApiException(ProblemCode.EGRESS_NOT_ALLOWED, e.getMessage())
                    .withFieldErrors(List.of(new ApiException.FieldError("/apiUrl", "egress", e.getMessage())));
        }
        String baseBranch = request.baseBranch() == null || request.baseBranch().isBlank() ? "main" : request.baseBranch();
        if (!BRANCH_PATTERN.matcher(baseBranch).matches()) {
            throw field("/baseBranch", "pattern", "Use a branch name such as main.");
        }
        String basePath = request.basePath() == null || request.basePath().isBlank() ? "orqix" : request.basePath();
        if (!BASE_PATH_PATTERN.matcher(basePath).matches() || basePath.length() > 100) {
            throw field("/basePath", "pattern", "Use lower-case folder names separated by /, for example orqix.");
        }
        if (!GitCredentials.wellFormed(request.credentialRef())) {
            throw field("/credentialRef", "pattern", "Use a secret reference (a mounted secret name or vault:, aws-sm:,"
                    + " azure-kv:, gcp-sm:). Never paste the token itself.");
        }
        GitCredentials.refuseReserved(request.credentialRef(), "/credentialRef");
        boolean enabled = request.enabled() == null || request.enabled();
        Optional<Conn> existing = jdbc.sql("SELECT * FROM git_connection WHERE workspace_id = :ws FOR UPDATE")
                .param("ws", ws).query(this::map).optional();
        int current = existing.map(Conn::version).orElse(0);
        if (current != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The Git connection changed since you loaded it.")
                    .with("currentVersion", current);
        }
        int version = current + 1;
        if (existing.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO git_connection (workspace_id, provider, api_url, repository, base_branch, base_path,
                        credential_ref, enabled, version, updated_by, updated_at)
                    VALUES (:ws, :provider, :api, :repo, :branch, :path, :ref, :enabled, 1, :by, :at)
                    """).param("ws", ws).param("provider", provider.kind()).param("api", apiUrl).param("repo", repository)
                    .param("branch", baseBranch).param("path", basePath).param("ref", request.credentialRef())
                    .param("enabled", enabled).param("by", caller.subjectId()).param("at", Sql.ts(clock.instant())).update();
        } else {
            jdbc.sql("""
                    UPDATE git_connection SET provider = :provider, api_url = :api, repository = :repo, base_branch = :branch,
                        base_path = :path, credential_ref = :ref, enabled = :enabled, version = :version, updated_by = :by,
                        updated_at = :at
                    WHERE workspace_id = :ws AND version = :expected
                    """).param("ws", ws).param("provider", provider.kind()).param("api", apiUrl).param("repo", repository)
                    .param("branch", baseBranch).param("path", basePath).param("ref", request.credentialRef())
                    .param("enabled", enabled).param("version", version).param("expected", current)
                    .param("by", caller.subjectId()).param("at", Sql.ts(clock.instant())).update();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider.kind());
        metadata.put("repository", repository);
        metadata.put("baseBranch", baseBranch);
        metadata.put("basePath", basePath);
        metadata.put("enabled", enabled);
        metadata.put("version", version);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "git.connection.saved",
                "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new ConnectionState(true, view(load(ws).orElseThrow(), true), List.copyOf(providers.keySet()));
    }

    @Transactional
    public void delete(Caller caller, UUID ws, int expectedVersion) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.CONNECTION_MANAGE, "deleteGitConnection", true);
        Conn conn = jdbc.sql("SELECT * FROM git_connection WHERE workspace_id = :ws FOR UPDATE").param("ws", ws)
                .query(this::map).optional().orElseThrow(ApiException::notFound);
        if (conn.version() != expectedVersion) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The Git connection changed since you loaded it.")
                    .with("currentVersion", conn.version());
        }
        jdbc.sql("DELETE FROM git_connection WHERE workspace_id = :ws").param("ws", ws).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "git.connection.deleted",
                "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("provider", conn.provider(), "repository", conn.repository())));
    }

    @Transactional
    public CheckResult check(Caller caller, UUID ws) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.CONNECTION_MANAGE, "checkGitConnection", true);
        Conn conn = load(ws).orElseThrow(ApiException::notFound);
        CheckResult result;
        try {
            GitProvider provider = provider(conn);
            GitProvider.Remote remote = remote(conn);
            String defaultBranch = provider.defaultBranch(remote);
            boolean exists = provider.branchHead(remote, conn.baseBranch()).isPresent();
            result = new CheckResult(true, defaultBranch, exists, exists ? "Connected." : "Connected, but the branch "
                    + conn.baseBranch() + " does not exist.");
        } catch (GitException e) {
            result = new CheckResult(false, null, false, e.getMessage());
        } catch (ApiException e) {
            result = new CheckResult(false, null, false, e.getMessage());
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "git.connection.checked",
                "workspace", ws.toString(), result.reachable() ? AuditWriter.Outcome.SUCCESS : AuditWriter.Outcome.FAILED,
                Map.of("provider", conn.provider(), "reachable", result.reachable(), "baseBranchExists",
                        result.baseBranchExists())));
        return result;
    }

    // ------------------------------------------------------------------ push

    @Transactional
    public PushResult push(Caller caller, UUID ws, UUID wf, PushRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "pushWorkflowToGit");
        // Definitions leave the workspace, as for a release export.
        policy.require(visible.access(), Permission.WORKFLOW_EXPORT, "pushWorkflowToGit", true);
        Conn conn = active(ws);
        CatalogStore.DraftRow draft = draft(ws, wf, request.draftRevision());
        String branch = request.branch() == null || request.branch().isBlank() ? "orqix/" + visible.workflow().key()
                : request.branch();
        if (branch.equals(conn.baseBranch())) {
            throw field("/branch", "reviewBranch", "Push to a review branch; changes reach " + conn.baseBranch()
                    + " only through a reviewed pull request.");
        }
        JsonNode definition = json.parse(draft.definitionJson());
        TestSuiteService.Suite suite = tests.latest(ws, wf);
        Map<String, JsonNode> sources = GitLayout.sources(definition, casesNode(suite.cases()));
        String hash = GitLayout.contentHash(json, sources);
        String folder = GitLayout.folder(conn.basePath(), visible.workflow().key());
        Map<String, byte[]> files = render(visible.access(), visible.workflow(), draft, sources, hash);

        GitProvider provider = provider(conn);
        GitProvider.Remote remote = remote(conn);
        GitLayout.guard(files, remote.token());
        try {
            String baseHead = provider.branchHead(remote, conn.baseBranch()).orElseThrow(() -> new ApiException(
                    ProblemCode.VALIDATION_FAILED, "The base branch " + conn.baseBranch() + " does not exist in "
                            + conn.repository() + "."));
            Optional<String> head = provider.branchHead(remote, branch);
            if (head.isEmpty()) {
                provider.createBranch(remote, branch, baseHead);
            }
            String parent = head.orElse(baseHead);
            Set<String> existing = provider.paths(remote, parent, folder);
            Map<String, byte[]> current = provider.read(remote, parent, existing);
            Map<String, byte[]> upserts = new TreeMap<>();
            Map<String, byte[]> absolute = new TreeMap<>();
            files.forEach((relative, bytes) -> absolute.put(folder + "/" + relative, bytes));
            absolute.forEach((path, bytes) -> {
                byte[] before = current.get(path);
                if (before == null || !java.util.Arrays.equals(before, bytes)) {
                    upserts.put(path, bytes);
                }
            });
            Set<String> deletes = new TreeSet<>(existing);
            deletes.removeAll(absolute.keySet());
            boolean unchanged = upserts.isEmpty() && deletes.isEmpty();
            String commit = parent;
            if (!unchanged) {
                String message = "Orqix: " + visible.workflow().name() + " draft revision " + draft.revision() + "\n\n"
                        + "Orqix-Workflow: " + visible.workflow().key() + "\nOrqix-Content-Hash: " + hash + "\n";
                commit = provider.commit(remote, branch, parent, upserts, deletes, existing, message);
            }
            GitProvider.PullRequest pr = provider.findOpenPullRequest(remote, branch, conn.baseBranch()).orElse(null);
            if (pr == null) {
                String title = request.title() == null || request.title().isBlank()
                        ? "Orqix: " + visible.workflow().name() + " (draft revision " + draft.revision() + ")"
                        : request.title();
                String description = (request.description() == null ? "" : request.description().strip() + "\n\n")
                        + "Exported by Orqix from draft revision " + draft.revision() + " of " + visible.workflow().key()
                        + ".\n\nContent hash of the source files (workflow.json, tests.json): `" + hash + "`.\n\n"
                        + "Files under generated/ are for review only; Orqix rewrites them on every push and ignores them"
                        + " on import. Orqix publishes only from an approved commit whose content hash matches the draft.";
                pr = provider.openPullRequest(remote, branch, conn.baseBranch(), title, description);
            }
            List<String> changed = new ArrayList<>(upserts.keySet());
            changed.addAll(deletes);
            record(ws, wf, "push", conn, branch, commit, hash, draft.revision(), null, pr, Map.of("changedFiles",
                    changed.size(), "deletedFiles", deletes.size()), caller.subjectId());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("draftRevision", draft.revision());
            metadata.put("branch", branch);
            metadata.put("commit", commit);
            metadata.put("contentHash", hash);
            metadata.put("pullRequest", pr.id());
            metadata.put("changedFiles", changed.size());
            metadata.put("unchanged", unchanged);
            audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "git.pushed", "workflow",
                    wf.toString(), AuditWriter.Outcome.SUCCESS, metadata));
            return new PushResult(branch, commit, unchanged, hash, draft.revision(), List.copyOf(changed),
                    PullRequestView.of(pr));
        } catch (GitException e) {
            throw translate(e);
        }
    }

    /** The full file set of one draft, relative to the workflow folder. */
    private Map<String, byte[]> render(WorkspaceAccess access, CatalogStore.WorkflowRow workflow,
            CatalogStore.DraftRow draft, Map<String, JsonNode> sources, String hash) {
        CompileContext context = contexts.create(access.workspace(), draft.definitionJson());
        CompiledWorkflow compiled;
        try {
            compiled = compiler.compile(draft.definitionJson(), context);
        } catch (CompilationException e) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "Fix the blocking issues before exporting to Git, so the"
                    + " generated BPMN matches what Orqix would publish.").with("issues", WorkflowService.issues(e.report().issues()));
        }
        UUID ws = access.workspace().id();
        Map<String, byte[]> files = new TreeMap<>();
        sources.forEach((path, node) -> {
            GitLayout.guardJson(path, node);
            files.put(path, GitLayout.pretty(json, node));
        });
        files.put(GitLayout.GENERATED + "process.bpmn20.xml", compiled.bpmnXml().getBytes(StandardCharsets.UTF_8));
        new TreeMap<>(compiled.dmnResources()).forEach((name, xml) -> files.put(GitLayout.GENERATED + "decisions/" + name,
                xml.getBytes(StandardCharsets.UTF_8)));
        JsonNode definition = sources.get(GitLayout.WORKFLOW);
        ObjectNode references = json.object().put("formatVersion", GitLayout.FORMAT)
                .put("note", "Generated for review. Bindings, base URLs and secrets are never exported.");
        ArrayNode decisions = references.putArray("ruleTables");
        compiled.decisions().forEach(d -> decisions.addObject().put("key", d.key()).put("version", d.version()));
        ArrayNode connectionRefs = references.putArray("connections");
        for (var c : compiled.connections()) {
            connectionRefs.addObject().put("key", c.key()).put("definitionVersion", c.definitionVersion())
                    .put("definitionHash", c.definitionHash());
            connections.definition(ws, c.key(), c.definitionVersion()).ifPresent(d -> {
                JsonNode document = json.toTree(d.definition().document());
                GitLayout.guardJson("connections/" + c.key(), document);
                files.put(GitLayout.GENERATED + "connections/" + c.key() + "-v" + c.definitionVersion() + ".json",
                        GitLayout.pretty(json, document));
            });
        }
        ArrayNode children = references.putArray("childWorkflows");
        compiled.children().forEach(c -> children.addObject().put("workflowKey", c.workflowKey())
                .put("releaseNumber", c.releaseNumber()).put("semanticHash", c.semanticHash()));
        ArrayNode groups = references.putArray("groups");
        compiled.groups().forEach(groups::add);
        references.set("sharedAssets", definition.path("dependencies").path("assets").isArray()
                ? definition.path("dependencies").path("assets").deepCopy() : json.mapper().createArrayNode());
        for (JsonNode asset : definition.path("dependencies").path("assets")) {
            String kind = asset.path("kind").asString("");
            String table = switch (kind) {
                case SharedAssets.DECISION -> "decision";
                case SharedAssets.FORM -> "form";
                default -> null;
            };
            if (table == null) {
                continue;
            }
            jdbc.sql("SELECT v.content::text FROM " + table + "_asset a JOIN " + table + "_asset_version v ON v.asset_id = a.id"
                    + " WHERE a.workspace_id = :ws AND a.key = :key AND v.version = :v")
                    .param("ws", ws).param("key", asset.path("key").asString("")).param("v", asset.path("version").asInt())
                    .query(String.class).optional().ifPresent(content -> {
                        JsonNode node = json.parse(content);
                        GitLayout.guardJson("assets/" + kind, node);
                        files.put(GitLayout.GENERATED + "assets/" + kind + "/" + asset.path("key").asString() + "-v"
                                + asset.path("version").asInt() + ".json", GitLayout.pretty(json, node));
                    });
        }
        ArrayNode views = references.putArray("views");
        for (ReleaseBundleService.ViewPin pin : ReleaseBundleService.viewPins(definition)) {
            views.addObject().put("id", pin.id()).put("version", pin.version()).put("use", pin.use());
            jdbc.sql("SELECT definition::text FROM application_view_version WHERE workspace_id = :ws AND view_id = :id AND version = :v")
                    .param("ws", ws).param("id", UUID.fromString(pin.id())).param("v", pin.version()).query(String.class)
                    .optional().ifPresent(text -> files.put(GitLayout.GENERATED + "views/" + pin.id() + "-v" + pin.version()
                            + ".json", GitLayout.pretty(json, json.parse(text))));
        }
        files.put(GitLayout.GENERATED + "references.json", GitLayout.pretty(json, references));
        ObjectNode sync = json.object().put("formatVersion", GitLayout.FORMAT).put("workflowKey", workflow.key())
                .put("workflowName", workflow.name()).put("draftRevision", draft.revision()).put("contentHash", hash)
                .put("compilerVersion", compiled.compilerVersion()).put("semanticHash", compiled.semanticHash());
        ObjectNode hashes = sync.putObject("sourceFiles");
        GitLayout.fileHashes(json, sources).forEach(hashes::put);
        sync.put("hashRule", "SHA-256 over sorted lines 'path NUL sha256(RFC 8785 canonical JSON) LF' of the source files;"
                + " workflow.json excludes provenance.");
        files.put(GitLayout.SYNC, GitLayout.pretty(json, sync));
        if (files.size() > GitLayout.MAX_FILES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The export has more than " + GitLayout.MAX_FILES + " files.");
        }
        return files;
    }

    // ------------------------------------------------------------------ pull (import)

    @Transactional(readOnly = true)
    public ImportPreview preview(Caller caller, UUID ws, UUID wf, String branch, String commit) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "previewGitImport");
        policy.require(visible.access(), Permission.WORKFLOW_EDIT, "previewGitImport", false);
        Conn conn = active(ws);
        if (branch == null || !BRANCH_PATTERN.matcher(branch).matches()) {
            throw field("/branch", "pattern", "Name the branch to import from.");
        }
        if (commit != null && !commit.matches(COMMIT)) {
            throw field("/commit", "pattern", "Use a full commit id.");
        }
        GitProvider provider = provider(conn);
        GitProvider.Remote remote = remote(conn);
        try {
            String at = commit != null ? commit : provider.branchHead(remote, branch).orElseThrow(() -> new ApiException(
                    ProblemCode.VALIDATION_FAILED, "The branch " + branch + " does not exist."));
            Incoming incoming = incoming(provider, remote, conn, visible.workflow(), at);
            CatalogStore.DraftRow draft = store.draft(ws, wf, visible.workflow().draftRevision()).orElseThrow();
            TestSuiteService.Suite suite = tests.latest(ws, wf);
            JsonNode currentDefinition = GitLayout.sourceDefinition(json.parse(draft.definitionJson()));
            String draftHash = GitLayout.contentHash(json, GitLayout.sources(currentDefinition, casesNode(suite.cases())));
            List<GitLayout.Change> changes = GitLayout.diff(currentDefinition, incoming.definition(),
                    casesNode(suite.cases()), incoming.testsIncluded() ? casesNode(incoming.cases()) : casesNode(suite.cases()));
            String definitionJson = json.write(incoming.definition());
            ValidationReport report = compiler.validate(definitionJson, contexts.create(visible.access().workspace(),
                    definitionJson));
            return new ImportPreview(branch, at, incoming.contentHash(), draft.revision(), draftHash,
                    draftHash.equals(incoming.contentHash()), incoming.testsIncluded(), changes,
                    WorkflowService.issues(report.issues()));
        } catch (GitException e) {
            throw translate(e);
        }
    }

    @Transactional
    public ImportResult importDraft(Caller caller, UUID ws, UUID wf, ImportRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "importWorkflowFromGit");
        policy.require(visible.access(), Permission.WORKFLOW_EDIT, "importWorkflowFromGit", true);
        Conn conn = active(ws);
        GitProvider provider = provider(conn);
        GitProvider.Remote remote = remote(conn);
        Incoming incoming;
        try {
            incoming = incoming(provider, remote, conn, visible.workflow(), request.commit());
        } catch (GitException e) {
            throw translate(e);
        }
        CatalogStore.WorkflowRow workflow = store.lockWorkflow(ws, wf).orElseThrow(ApiException::notFound);
        if (workflow.draftRevision() != request.expectedDraftRevision()) {
            throw new ApiException(ProblemCode.STALE_REVISION, "The draft was saved by someone else.")
                    .with("currentRevision", workflow.draftRevision());
        }
        CatalogStore.DraftRow draft = store.draft(ws, wf, workflow.draftRevision()).orElseThrow();
        TestSuiteService.Suite suite = tests.latest(ws, wf);
        JsonNode currentDefinition = GitLayout.sourceDefinition(json.parse(draft.definitionJson()));
        if (!incoming.testsIncluded() && !suite.cases().isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "tests.json is missing from the commit, but this workflow"
                    + " has shared publication tests. Removing them through Git is not allowed; change them in Studio.");
        }
        List<GitLayout.Change> changes = GitLayout.diff(currentDefinition, incoming.definition(), casesNode(suite.cases()),
                incoming.testsIncluded() ? casesNode(incoming.cases()) : casesNode(suite.cases()));
        boolean definitionChanged = !Json.canonicalSha256(json.write(currentDefinition))
                .equals(Json.canonicalSha256(json.write(incoming.definition())));
        boolean testsChanged = incoming.testsIncluded() && !json.toTree(incoming.cases()).equals(json.toTree(suite.cases()));
        int revision = workflow.draftRevision();
        if (definitionChanged) {
            ObjectNode definition = incoming.definition().deepCopy();
            ObjectNode provenance = definition.putObject("provenance").put("gitCommit", request.commit());
            if (request.pullRequest() != null && !request.pullRequest().isBlank()) {
                provenance.put("reviewReference", "Pull request " + request.pullRequest() + " in " + conn.repository());
            }
            String definitionJson = json.write(definition);
            if (definitionJson.getBytes(StandardCharsets.UTF_8).length > properties.limits().draftMaxBytes()) {
                throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The definition exceeds 256 KiB.");
            }
            if (!store.advanceDraft(ws, wf, revision)) {
                throw new ApiException(ProblemCode.STALE_REVISION, "The draft was saved by someone else.");
            }
            revision++;
            store.insertDraft(ws, wf, revision, definitionJson, "0.1", caller.subjectId());
            audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.draft.saved",
                    "workflow", wf.toString(), AuditWriter.Outcome.SUCCESS, Map.of("revision", revision, "source", "git")));
        }
        int suiteVersion = suite.version();
        if (testsChanged) {
            suiteVersion = tests.save(caller, ws, wf, new TestSuiteService.Save(suite.version(), incoming.cases())).version();
        }
        boolean unchanged = !definitionChanged && !testsChanged;
        record(ws, wf, "pull", conn, request.branch(), request.commit(), incoming.contentHash(), revision, null, null,
                Map.of("changes", changes.size(), "testsChanged", testsChanged), caller.subjectId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("branch", request.branch());
        metadata.put("commit", request.commit());
        metadata.put("contentHash", incoming.contentHash());
        metadata.put("draftRevision", revision);
        metadata.put("unchanged", unchanged);
        metadata.put("testsChanged", testsChanged);
        metadata.put("changes", changes.size());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "git.pulled", "workflow",
                wf.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new ImportResult(revision, unchanged, request.commit(), incoming.contentHash(), testsChanged, suiteVersion,
                changes);
    }

    /** Reads and checks the source files of one workflow at a commit. */
    private Incoming incoming(GitProvider provider, GitProvider.Remote remote, Conn conn,
            CatalogStore.WorkflowRow workflow, String commit) {
        String folder = GitLayout.folder(conn.basePath(), workflow.key());
        Set<String> wanted = new TreeSet<>();
        Set<String> present = provider.paths(remote, commit, folder);
        if (present.size() > GitLayout.MAX_FILES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The workflow folder has more than " + GitLayout.MAX_FILES + " files.");
        }
        for (String source : List.of(GitLayout.WORKFLOW, GitLayout.TESTS)) {
            if (present.contains(folder + "/" + source)) {
                wanted.add(folder + "/" + source);
            }
        }
        if (!wanted.contains(folder + "/" + GitLayout.WORKFLOW)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The commit has no " + folder + "/" + GitLayout.WORKFLOW + ".");
        }
        Map<String, byte[]> files = provider.read(remote, commit, wanted);
        files.forEach((path, bytes) -> {
            if (bytes.length > GitLayout.MAX_SOURCE_BYTES) {
                throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, path + " is larger than 512 KiB.");
            }
        });
        GitLayout.guard(files, remote.token());
        JsonNode definition = parse(files.get(folder + "/" + GitLayout.WORKFLOW), GitLayout.WORKFLOW);
        if (!definition.isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, GitLayout.WORKFLOW + " must hold a JSON object.");
        }
        if (Json.depth(definition) > properties.limits().jsonMaxDepth()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, GitLayout.WORKFLOW + " is nested too deeply.");
        }
        if (!"0.1".equals(definition.path("schemaVersion").asString(null))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unsupported schemaVersion in " + GitLayout.WORKFLOW
                    + "; expected \"0.1\".");
        }
        if (!workflow.key().equals(definition.path("workflowKey").asString(null))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, GitLayout.WORKFLOW + " is for workflow "
                    + definition.path("workflowKey").asString("(none)") + ", not " + workflow.key() + ".");
        }
        GitLayout.guardJson(GitLayout.WORKFLOW, definition);
        ObjectNode source = GitLayout.sourceDefinition(definition);
        boolean testsIncluded = files.containsKey(folder + "/" + GitLayout.TESTS);
        List<TestSuiteService.Case> cases = List.of();
        if (testsIncluded) {
            JsonNode testsFile = parse(files.get(folder + "/" + GitLayout.TESTS), GitLayout.TESTS);
            GitLayout.guardJson(GitLayout.TESTS, testsFile);
            cases = cases(testsFile);
        }
        String hash = GitLayout.contentHash(json, GitLayout.sources(source, casesNode(cases)));
        return new Incoming(source, cases, testsIncluded, hash);
    }

    private JsonNode parse(byte[] bytes, String name) {
        try {
            return json.parse(bytes);
        } catch (RuntimeException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, name + " is not valid JSON.");
        }
    }

    private static final Set<String> CASE_MEMBERS = Set.of("id", "name", "input", "scenario", "expect", "assertions");

    /** Strict reading of tests.json: a formatVersion and an array of cases with the Studio's members only. */
    private List<TestSuiteService.Case> cases(JsonNode file) {
        if (!file.isObject() || !file.path("cases").isArray()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "tests.json must be {\"formatVersion\": \"1\", \"cases\": [...]}.");
        }
        List<TestSuiteService.Case> cases = new ArrayList<>();
        for (JsonNode c : file.path("cases")) {
            for (String name : c.propertyNames()) {
                if (!CASE_MEMBERS.contains(name)) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "tests.json: unknown member " + name + " in a case.");
                }
            }
            if (!c.path("id").isString() || !c.path("name").isString() || !c.path("expect").isString()
                    || !c.path("input").isObject()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "tests.json: every case needs id, name, input and expect.");
            }
            cases.add(new TestSuiteService.Case(c.path("id").asString(), c.path("name").asString(), c.path("input"),
                    c.hasNonNull("scenario") ? c.path("scenario") : null, c.path("expect").asString(),
                    c.hasNonNull("assertions") ? c.path("assertions") : null));
        }
        if (cases.size() > 20) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "tests.json: at most 20 shared test cases.");
        }
        return cases;
    }

    private JsonNode casesNode(List<TestSuiteService.Case> cases) {
        return json.toTree(cases);
    }

    // ------------------------------------------------------------------ publish from a reviewed commit

    @Transactional
    public PublishResult publish(Caller caller, UUID ws, UUID wf, String idempotencyKey, PublishRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "publishFromGit");
        policy.require(visible.access(), Permission.WORKFLOW_PUBLISH, "publishFromGit", true);
        Conn conn = active(ws);
        GitProvider provider = provider(conn);
        GitProvider.Remote remote = remote(conn);
        GitProvider.PullRequest pr;
        Incoming incoming;
        try {
            try {
                pr = provider.pullRequest(remote, request.pullRequest());
            } catch (GitException e) {
                if (e.notFound()) {
                    throw field("/pullRequest", "exists", "Pull request " + request.pullRequest() + " does not exist in "
                            + conn.repository() + ".");
                }
                throw e;
            }
            boolean reviewed = !pr.approvedBy().isEmpty() && (("open".equals(pr.state()) && pr.isHead(request.commit()))
                    || ("merged".equals(pr.state()) && (pr.isHead(request.commit()) || pr.isMerge(request.commit()))));
            if (!reviewed) {
                throw new ApiException(ProblemCode.GIT_REVIEW_REQUIRED, "Publish from the approved head commit of an open"
                        + " pull request, or from a merged pull request. Pull request " + pr.id() + " is " + pr.state()
                        + " with " + pr.approvedBy().size() + " approval(s) on its head commit.")
                        .with("pullRequestState", pr.state()).with("approvals", pr.approvedBy().size())
                        .with("headCommit", pr.headCommit());
            }
            incoming = incoming(provider, remote, conn, visible.workflow(), request.commit());
        } catch (GitException e) {
            throw translate(e);
        }
        CatalogStore.DraftRow draft = draft(ws, wf, request.draftRevision());
        String draftHash = GitLayout.contentHash(json, GitLayout.sources(json.parse(draft.definitionJson()),
                casesNode(tests.latest(ws, wf).cases())));
        if (!draftHash.equals(incoming.contentHash())) {
            throw new ApiException(ProblemCode.GIT_CONTENT_MISMATCH, "The reviewed commit does not match draft revision "
                    + draft.revision() + ". Import the commit as a draft first, or push the draft and review it again.")
                    .with("commitContentHash", incoming.contentHash()).with("draftContentHash", draftHash);
        }
        Instant now = clock.instant();
        String branch = pr.sourceBranch() == null || pr.sourceBranch().isBlank() ? conn.baseBranch() : pr.sourceBranch();
        GitSource source = new GitSource(conn.provider(), conn.repository(), branch, request.commit(), incoming.contentHash(),
                pr.id(), pr.url(), pr.state(), pr.approvedBy(), now);
        CatalogDtos.PublishRequest publish = new CatalogDtos.PublishRequest(request.draftRevision(),
                request.acknowledgeWarnings(), request.activate(), request.releaseNote());
        Map<String, JsonNode> extras = Map.of("gitSource", json.toTree(source));
        ReleaseService.Published published;
        ReleaseBundleService.BundleView bundle = null;
        boolean asBundle = Boolean.TRUE.equals(request.asBundle());
        if (asBundle) {
            ReleaseBundleService.Published p = bundles.publish(caller, ws, wf, idempotencyKey, publish, extras);
            published = p.published();
            bundle = p.bundle();
        } else {
            published = releases.publish(caller, ws, wf, idempotencyKey, publish, extras);
        }
        JsonNode recorded = published.release().manifest() == null ? null : published.release().manifest().path("gitSource");
        GitSource result = recorded == null || recorded.isMissingNode() ? source : json.convert(recorded, GitSource.class);
        if (!published.replayed()) {
            record(ws, wf, "publish", conn, branch, request.commit(), incoming.contentHash(), draft.revision(),
                    published.release().id(), pr, Map.of("approvals", pr.approvedBy().size()), caller.subjectId());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("releaseNumber", published.release().releaseNumber());
            metadata.put("draftRevision", draft.revision());
            metadata.put("commit", request.commit());
            metadata.put("contentHash", incoming.contentHash());
            metadata.put("pullRequest", pr.id());
            metadata.put("reviewState", pr.state());
            metadata.put("approvals", pr.approvedBy().size());
            metadata.put("bundle", asBundle);
            audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "git.published",
                    "release", published.release().id().toString(), AuditWriter.Outcome.SUCCESS, metadata));
        }
        return new PublishResult(published.release(), result, bundle, published.replayed());
    }

    // ------------------------------------------------------------------ history

    @Transactional(readOnly = true)
    public List<SyncEvent> events(Caller caller, UUID ws, UUID wf) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "listGitSyncEvents");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "listGitSyncEvents", false);
        record Row(UUID id, String direction, String branch, String commit, String hash, Integer revision, UUID release,
                String pr, String prUrl, UUID actor, Instant at) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT id, direction, branch, commit_sha, content_hash, draft_revision, release_id, pull_request,
                       pull_request_url, actor, occurred_at
                FROM git_sync_event WHERE workspace_id = :ws AND workflow_id = :wf ORDER BY occurred_at DESC, id DESC LIMIT 50
                """).param("ws", ws).param("wf", wf).query((rs, n) -> new Row(Sql.uuid(rs, "id"), rs.getString("direction"),
                rs.getString("branch"), rs.getString("commit_sha"), rs.getString("content_hash").trim(),
                (Integer) rs.getObject("draft_revision"), Sql.uuid(rs, "release_id"), rs.getString("pull_request"),
                rs.getString("pull_request_url"), Sql.uuid(rs, "actor"), Sql.instant(rs, "occurred_at"))).list();
        return rows.stream().map(r -> new SyncEvent(r.id(), r.direction(), r.branch(), r.commit(), r.hash(), r.revision(),
                r.release(), r.pr(), r.prUrl(), subjects.ref(r.actor()), r.at())).toList();
    }

    // ------------------------------------------------------------------ helpers

    private void record(UUID ws, UUID wf, String direction, Conn conn, String branch, String commit, String hash,
            Integer revision, UUID releaseId, GitProvider.PullRequest pr, Map<String, ?> details, UUID actor) {
        jdbc.sql("""
                INSERT INTO git_sync_event (id, workspace_id, workflow_id, direction, provider, repository, branch, commit_sha,
                    content_hash, draft_revision, release_id, pull_request, pull_request_url, details, actor, occurred_at)
                VALUES (:id, :ws, :wf, :direction, :provider, :repo, :branch, :commit, :hash, :revision, :release, :pr,
                        :prUrl, CAST(:details AS jsonb), :actor, :at)
                """).param("id", Ids.newId()).param("ws", ws).param("wf", wf).param("direction", direction)
                .param("provider", conn.provider()).param("repo", conn.repository()).param("branch", branch)
                .param("commit", commit).param("hash", hash).param("revision", revision).param("release", releaseId)
                .param("pr", pr == null ? null : pr.id())
                .param("prUrl", pr == null || pr.url() == null ? null : pr.url().length() > 500 ? null : pr.url())
                .param("details", json.write(details)).param("actor", actor).param("at", Sql.ts(clock.instant())).update();
    }

    private CatalogStore.DraftRow draft(UUID ws, UUID wf, int revision) {
        return store.draft(ws, wf, revision).orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED,
                "The draft revision does not exist.").withFieldErrors(List.of(new ApiException.FieldError(
                        "/draftRevision", "minimum", "Unknown draft revision"))));
    }

    private Optional<Conn> load(UUID ws) {
        return jdbc.sql("SELECT * FROM git_connection WHERE workspace_id = :ws").param("ws", ws).query(this::map).optional();
    }

    private Conn active(UUID ws) {
        Conn conn = load(ws).orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED,
                "This workspace has no Git connection. A connection manager sets one up in Settings."));
        if (!conn.enabled()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The Git connection of this workspace is switched off.");
        }
        return conn;
    }

    private Conn map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Conn(Sql.uuid(rs, "workspace_id"), rs.getString("provider"), rs.getString("api_url"),
                rs.getString("repository"), rs.getString("base_branch"), rs.getString("base_path"),
                rs.getString("credential_ref"), rs.getBoolean("enabled"), rs.getInt("version"), Sql.uuid(rs, "updated_by"),
                Sql.instant(rs, "updated_at"));
    }

    private ConnectionView view(Conn c, boolean manager) {
        return new ConnectionView(c.provider(), c.apiUrl(), c.repository(), c.baseBranch(), c.basePath(),
                manager ? c.credentialRef() : null, c.enabled(), c.version(), subjects.ref(c.updatedBy()), c.updatedAt());
    }

    private GitProvider provider(Conn conn) {
        GitProvider provider = providers.get(conn.provider());
        if (provider == null) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The Git provider " + conn.provider()
                    + " is not available in this installation.");
        }
        return provider;
    }

    private GitProvider.Remote remote(Conn conn) {
        try {
            egress.check(conn.apiUrl());
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new ApiException(ProblemCode.EGRESS_NOT_ALLOWED, e.getMessage());
        }
        String token = credentials.resolve(conn.credentialRef(), conn.workspaceId()).orElseThrow(() -> new ApiException(
                ProblemCode.DEPENDENCY_UNAVAILABLE, "The Git credential could not be resolved; nothing was sent or saved."));
        return new GitProvider.Remote(conn.apiUrl(), conn.repository(), token, http);
    }

    private static ApiException translate(GitException e) {
        if (e.conflict()) {
            return new ApiException(ProblemCode.GIT_BRANCH_MOVED, e.getMessage() + " Nothing was saved; try again.");
        }
        return new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, e.getMessage() + " Nothing was saved.")
                .with("providerStatus", e.status());
    }

    private static ApiException field(String pointer, String code, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, code, message)));
    }

    /** API URL normalization: no trailing slash. */
    static final class GitHttpUrl {
        private GitHttpUrl() {
        }

        static String strip(String url) {
            String u = url.strip();
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }
    }
}
