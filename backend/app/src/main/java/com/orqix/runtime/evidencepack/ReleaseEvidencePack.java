package com.orqix.runtime.evidencepack;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Control evidence pack for one published release (docs/27 section 3, Create): release identity and checksums, who
 * authored, reviewed and published it and when, the publication test results, acknowledged warnings, the change
 * summary from the previous release, promotion requests and the release's audit history. Read-only over immutable
 * release rows and append-only records; the export itself is audited.
 */
@Service
public class ReleaseEvidencePack {

    static final String PAYLOAD_POLICY = "Control metadata only. No request values, credentials, connection secrets, "
            + "template bodies, test inputs or free-text notes. The full definition is in the release export bundle.";

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final EvidenceRenderer renderer;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;

    public ReleaseEvidencePack(JdbcClient jdbc, PolicyService policy, SubjectService subjects,
            EvidenceRenderer renderer, AuditWriter audit, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.subjects = subjects;
        this.renderer = renderer;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    private record ReleaseRow(UUID id, UUID workflowId, String workflowKey, String workflowName, UUID activeReleaseId,
            int releaseNumber, int draftRevision, JsonNode definition, String schemaVersion, String sourceHash,
            String semanticHash, String compilerVersion, JsonNode manifest, JsonNode warnings,
            String processDefinitionId, String releaseNote, UUID publishedBy, Instant publishedAt, UUID draftSavedBy,
            Instant draftSavedAt) {
    }

    @Transactional
    public EvidenceRenderer.Rendered export(Caller caller, UUID ws, UUID workflowId, UUID releaseId, String format) {
        EvidenceRenderer.requireFormat(format);
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        ReleaseRow release = release(ws, workflowId, releaseId).orElseThrow(ApiException::notFound);
        // Who may see a release (catalog getRelease) may read its evidence. audit.read adds only workflows the
        // caller may start; it never opens a workflow the caller cannot otherwise see.
        if (!access.has(Permission.WORKFLOW_READ)) {
            if (!access.canStart(workflowId)) {
                throw ApiException.notFound();
            }
            policy.require(access, Permission.AUDIT_READ, "exportReleaseEvidencePack", true);
        }
        ObjectNode pack = build(caller, access, release);
        EvidenceDocument document = layout(pack);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "release.evidence-pack-exported", "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("format", format, "releaseNumber", release.releaseNumber())));
        return renderer.render(format, release.workflowKey() + "-r" + release.releaseNumber() + "-evidence-pack", pack,
                document);
    }

    private ObjectNode build(Caller caller, WorkspaceAccess access, ReleaseRow release) {
        UUID ws = access.workspace().id();
        Set<UUID> people = new LinkedHashSet<>();
        UUID author = uuid(release.manifest().path("authorSubjectId")).orElse(release.draftSavedBy());
        UUID reviewer = uuid(release.manifest().path("reviewerSubjectId")).orElse(release.publishedBy());
        people.add(caller.subjectId());
        people.add(author);
        people.add(reviewer);
        people.add(release.publishedBy());

        ObjectNode pack = json.object();
        pack.put("formatVersion", "1").put("kind", "release").put("generatedAt", clock.instant().toString());
        ObjectNode workspace = pack.putObject("workspace");
        workspace.put("id", ws.toString()).put("key", access.workspace().key()).put("name", access.workspace().name());

        ObjectNode identity = pack.putObject("release");
        identity.put("id", release.id().toString()).put("workflowId", release.workflowId().toString())
                .put("workflowKey", release.workflowKey()).put("workflowName", release.workflowName())
                .put("releaseNumber", release.releaseNumber()).put("draftRevision", release.draftRevision())
                .put("publishedAt", release.publishedAt().toString())
                .put("active", release.id().equals(release.activeReleaseId()))
                .put("schemaVersion", release.schemaVersion()).put("compilerVersion", release.compilerVersion())
                .put("sourceHash", release.sourceHash()).put("semanticHash", release.semanticHash())
                .put("processDefinitionId", release.processDefinitionId());
        if (release.manifest().hasNonNull("bpmnHash")) {
            identity.put("bpmnHash", release.manifest().path("bpmnHash").asString());
        }
        if (release.manifest().hasNonNull("acceptanceProfile")) {
            identity.put("acceptanceProfile", release.manifest().path("acceptanceProfile").asString());
        }
        if (release.manifest().has("provenance")) {
            identity.set("provenance", release.manifest().path("provenance").deepCopy());
        }
        identity.put("hasReleaseNote", release.releaseNote() != null && !release.releaseNote().isBlank());

        ArrayNode artifacts = pack.putArray("artifacts");
        jdbc.sql("SELECT name, media_type, sha256, octet_length(content) AS size FROM release_artifact WHERE release_id = :id ORDER BY name")
                .param("id", release.id())
                .query((rs, n) -> artifacts.addObject().put("name", rs.getString("name"))
                        .put("mediaType", rs.getString("media_type")).put("sha256", rs.getString("sha256").trim())
                        .put("sizeBytes", rs.getLong("size")))
                .list();
        ArrayNode references = pack.putArray("pinnedReferences");
        for (JsonNode d : release.manifest().path("decisions")) {
            references.addObject().put("type", "Rule table").put("key", d.path("key").asString())
                    .put("version", d.path("version").asString());
        }
        for (JsonNode c : release.manifest().path("connections")) {
            references.addObject().put("type", "Connection").put("key", c.path("key").asString())
                    .put("version", c.path("definitionVersion").asString()).put("hash", c.path("definitionHash").asString(null));
        }
        for (JsonNode w : release.manifest().path("workflows")) {
            references.addObject().put("type", "Child workflow").put("key", w.path("workflowKey").asString())
                    .put("version", "release " + w.path("releaseNumber").asString()).put("hash", w.path("semanticHash").asString(null));
        }
        for (JsonNode a : release.manifest().path("assets")) {
            references.addObject().put("type", "Shared asset").put("key", a.path("key").asString(a.path("id").asString("")))
                    .put("version", a.path("version").asString(""));
        }

        // Approvals: author (draft), reviewer, publisher; plus promotion requests for this release.
        ArrayNode approvals = pack.putArray("approvals");
        approvals.addObject().put("role", "Author").put("action", "Saved draft revision " + release.draftRevision())
                .put("subjectId", author.toString())
                .put("at", release.draftSavedAt() == null ? null : release.draftSavedAt().toString());
        approvals.addObject().put("role", "Reviewer").put("action", "Reviewed changes, tests and warnings")
                .put("subjectId", reviewer.toString())
                .put("at", release.manifest().path("reviewedAt").asString(release.publishedAt().toString()));
        approvals.addObject().put("role", "Publisher").put("action", "Published release " + release.releaseNumber())
                .put("subjectId", release.publishedBy().toString()).put("at", release.publishedAt().toString());
        ObjectNode controls = pack.putObject("controls");
        controls.put("independentReview", !author.equals(release.publishedBy()));
        controls.put("tier1Profile", "tier1".equals(release.manifest().path("acceptanceProfile").asString("")));

        ArrayNode promotions = pack.putArray("promotions");
        jdbc.sql("""
                SELECT p.status, w.key AS target, p.requested_by, p.requested_at, p.decided_by, p.decided_at
                FROM promotion_request p JOIN workspace w ON w.id = p.target_workspace_id
                WHERE p.source_workspace_id = :ws AND p.source_release_id = :id ORDER BY p.requested_at, p.id LIMIT 200
                """).param("ws", ws).param("id", release.id()).query((rs, n) -> {
                    UUID by = Sql.uuid(rs, "requested_by");
                    UUID decided = Sql.uuid(rs, "decided_by");
                    people.add(by);
                    if (decided != null) {
                        people.add(decided);
                    }
                    Instant decidedAt = Sql.instant(rs, "decided_at");
                    return promotions.addObject().put("targetWorkspace", rs.getString("target"))
                            .put("status", rs.getString("status")).put("requestedBy", by.toString())
                            .put("requestedAt", Sql.instant(rs, "requested_at").toString())
                            .put("decidedBy", decided == null ? null : decided.toString())
                            .put("decidedAt", decidedAt == null ? null : decidedAt.toString());
                }).list();

        pack.set("tests", tests(ws, release, people));

        ArrayNode warnings = pack.putArray("warnings");
        for (JsonNode w : release.warnings()) {
            warnings.addObject().put("code", w.path("code").asString()).put("severity", w.path("severity").asString())
                    .put("message", w.path("message").asString()).put("nodeId", w.path("nodeId").asString(null));
        }
        Boolean acknowledged = jdbc.sql("""
                SELECT (metadata->>'warningsAcknowledged')::boolean FROM audit_event
                WHERE workspace_id = :ws AND action = 'workflow.published' AND resource_type = 'release'
                  AND resource_id = :id AND outcome = 'success' ORDER BY occurred_at LIMIT 1
                """).param("ws", ws).param("id", release.id().toString()).query(Boolean.class).optional().orElse(null);
        if (acknowledged != null) {
            controls.put("warningsAcknowledged", acknowledged);
        }

        pack.set("diff", diff(ws, release));
        pack.set("history", history(ws, release, people));

        people.remove(null);
        Map<UUID, String> names = subjects.displayNames(people);
        pack.set("generatedBy", subject(caller.subjectId(), names));
        for (JsonNode approval : approvals) {
            ((ObjectNode) approval).set("subject", subject(UUID.fromString(approval.path("subjectId").asString()), names));
            ((ObjectNode) approval).remove("subjectId");
        }
        for (JsonNode promotion : promotions) {
            ObjectNode p = (ObjectNode) promotion;
            p.set("requestedBy", subject(UUID.fromString(p.path("requestedBy").asString()), names));
            if (p.hasNonNull("decidedBy")) {
                p.set("decidedBy", subject(UUID.fromString(p.path("decidedBy").asString()), names));
            }
        }
        if (pack.path("tests").hasNonNull("testedBy")) {
            ((ObjectNode) pack.path("tests")).set("testedBy",
                    subject(UUID.fromString(pack.path("tests").path("testedBy").asString()), names));
        }
        for (JsonNode item : pack.path("history")) {
            if (item.hasNonNull("actorId")) {
                ((ObjectNode) item).set("actor", subject(UUID.fromString(item.path("actorId").asString()), names));
            }
            ((ObjectNode) item).remove("actorId");
        }
        pack.put("payloadPolicy", PAYLOAD_POLICY);
        return seal(json, pack);
    }

    private ObjectNode tests(UUID ws, ReleaseRow release, Set<UUID> people) {
        ObjectNode tests = json.object();
        record Result(int suiteVersion, boolean passed, JsonNode results, UUID testedBy, Instant testedAt) {
        }
        // The latest result for the published draft revision; results recorded before publication come first.
        Optional<Result> result = jdbc.sql("""
                SELECT suite_version, passed, results::text AS results, tested_by, tested_at FROM workflow_test_result
                WHERE workspace_id = :ws AND workflow_id = :wf AND draft_revision = :rev
                ORDER BY (tested_at <= :published) DESC, tested_at DESC LIMIT 1
                """).param("ws", ws).param("wf", release.workflowId()).param("rev", release.draftRevision())
                .param("published", Sql.ts(release.publishedAt()))
                .query((rs, n) -> new Result(rs.getInt("suite_version"), rs.getBoolean("passed"),
                        json.parse(rs.getString("results")), Sql.uuid(rs, "tested_by"), Sql.instant(rs, "tested_at")))
                .optional();
        if (result.isEmpty()) {
            boolean suite = jdbc.sql("""
                    SELECT count(*) > 0 FROM workflow_test_suite WHERE workspace_id = :ws AND workflow_id = :wf
                      AND jsonb_array_length(cases) > 0 AND created_at <= :published
                    """).param("ws", ws).param("wf", release.workflowId()).param("published", Sql.ts(release.publishedAt()))
                    .query(Boolean.class).single();
            tests.put("status", suite ? "notRecorded" : "notConfigured");
            tests.put("summary", suite ? "A test suite existed but no result is recorded for this draft revision."
                    : "No publication test suite was configured for this workflow when it was published.");
            tests.putArray("results");
            return tests;
        }
        Result r = result.get();
        Map<String, String> caseNames = new HashMap<>();
        jdbc.sql("SELECT cases::text FROM workflow_test_suite WHERE workspace_id = :ws AND workflow_id = :wf AND version = :v")
                .param("ws", ws).param("wf", release.workflowId()).param("v", r.suiteVersion()).query(String.class)
                .optional().ifPresent(text -> json.parse(text).forEach(c -> caseNames.put(c.path("id").asString(),
                        c.path("name").asString(""))));
        int passed = 0;
        ArrayNode results = json.mapper().createArrayNode();
        for (JsonNode c : r.results()) {
            boolean ok = c.path("passed").asBoolean();
            passed += ok ? 1 : 0;
            String id = c.path("id").asString();
            results.addObject().put("caseId", id).put("name", caseNames.getOrDefault(id, id)).put("passed", ok)
                    .put("status", c.path("status").asString(""));
        }
        people.add(r.testedBy());
        tests.put("status", r.passed() ? "passed" : "failed").put("suiteVersion", r.suiteVersion())
                .put("draftRevision", release.draftRevision()).put("cases", results.size()).put("passedCases", passed)
                .put("testedBy", r.testedBy().toString()).put("testedAt", r.testedAt().toString())
                .put("summary", passed + " of " + results.size() + " test cases passed against draft revision "
                        + release.draftRevision() + " (suite version " + r.suiteVersion() + ").");
        tests.set("results", results);
        return tests;
    }

    private ObjectNode diff(UUID ws, ReleaseRow release) {
        ObjectNode diff = json.object();
        record Previous(UUID id, int number, String semanticHash, JsonNode definition) {
        }
        Optional<Previous> previous = jdbc.sql("""
                SELECT id, release_number, semantic_hash, definition::text AS definition FROM workflow_release
                WHERE workspace_id = :ws AND workflow_id = :wf AND release_number < :n
                ORDER BY release_number DESC LIMIT 1
                """).param("ws", ws).param("wf", release.workflowId()).param("n", release.releaseNumber())
                .query((rs, n) -> new Previous(Sql.uuid(rs, "id"), rs.getInt("release_number"),
                        rs.getString("semantic_hash").trim(), json.parse(rs.getString("definition"))))
                .optional();
        ArrayNode changes = json.mapper().createArrayNode();
        if (previous.isEmpty()) {
            diff.put("summary", "First release of this workflow: every step, route and rule is new ("
                    + release.definition().path("nodes").size() + " steps, "
                    + release.definition().path("transitions").size() + " routes, "
                    + release.definition().path("decisions").size() + " rule tables).");
            diff.set("changes", changes);
            return diff;
        }
        Previous p = previous.get();
        List<ReleaseDiff.Change> found = new ArrayList<>(ReleaseDiff.compare(p.definition(), release.definition()));
        for (ReleaseDiff.Change change : found) {
            changes.addObject().put("kind", change.kind()).put("area", change.area()).put("title", change.title());
        }
        long added = found.stream().filter(c -> "added".equals(c.kind())).count();
        long removed = found.stream().filter(c -> "removed".equals(c.kind())).count();
        diff.put("previousReleaseId", p.id().toString()).put("previousReleaseNumber", p.number())
                .put("previousSemanticHash", p.semanticHash())
                .put("sameBehavior", p.semanticHash().equals(release.semanticHash()))
                .put("added", added).put("removed", removed).put("changed", found.size() - added - removed)
                .put("summary", found.isEmpty()
                        ? "No behavioral change from release " + p.number() + " (layout or notes only)."
                        : found.size() + " changes from release " + p.number() + ": " + added + " added, " + removed
                                + " removed, " + (found.size() - added - removed) + " changed.");
        diff.set("changes", changes);
        return diff;
    }

    private ArrayNode history(UUID ws, ReleaseRow release, Set<UUID> people) {
        ArrayNode history = json.mapper().createArrayNode();
        String number = Integer.toString(release.releaseNumber());
        List<JsonNode> rows = jdbc.sql("""
                SELECT occurred_at, actor_type, actor_subject_id, action, outcome, metadata::text AS metadata FROM audit_event
                WHERE workspace_id = :ws AND (
                    (resource_type = 'release' AND resource_id = :id)
                 OR (resource_type = 'workflow' AND resource_id = :wf
                     AND action IN ('workflow.active-release.changed', 'workflow.canary.set', 'workflow.canary.cleared')
                     AND :n IN (metadata->>'toReleaseNumber', metadata->>'fromReleaseNumber',
                                metadata->>'canaryReleaseNumber', metadata->>'activeReleaseNumber'))
                 OR (resource_type = 'workflow' AND resource_id = :wf AND action = 'workflow.tests.run'
                     AND metadata->>'revision' = :rev))
                ORDER BY occurred_at, id LIMIT 1001
                """).param("ws", ws).param("id", release.id().toString()).param("wf", release.workflowId().toString())
                .param("n", number).param("rev", Integer.toString(release.draftRevision()))
                .query((rs, n) -> {
                    ObjectNode item = json.object().put("at", Sql.instant(rs, "occurred_at").toString())
                            .put("action", rs.getString("action")).put("outcome", rs.getString("outcome"))
                            .put("actorType", rs.getString("actor_type"));
                    UUID actor = Sql.uuid(rs, "actor_subject_id");
                    if (actor != null) {
                        people.add(actor);
                        item.put("actorId", actor.toString());
                    }
                    item.set("details", json.parse(rs.getString("metadata")));
                    return (JsonNode) item;
                }).list();
        if (rows.size() > 1000) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,
                    "More than 1000 audit records for this release; use the controlled audit extraction process.");
        }
        rows.forEach(history::add);
        return history;
    }

    static EvidenceDocument layout(ObjectNode pack) {
        JsonNode release = pack.path("release");
        String title = "Release evidence pack: " + release.path("workflowName").asString() + ", release "
                + release.path("releaseNumber").asInt();
        List<EvidenceDocument.Section> sections = new ArrayList<>();
        sections.add(EvidenceDocument.fields("Release identity",
                "Checksums are SHA-256. The semantic hash identifies the behavior; it is unchanged by layout-only edits.",
                pack, "release.workflowName", "Workflow", "release.workflowKey", "Workflow key", "release.releaseNumber",
                "Release number", "release.id", "Release id", "release.draftRevision", "From draft revision",
                "release.publishedAt", "Published", "release.active", "Active for new requests",
                "release.acceptanceProfile", "Acceptance profile", "release.semanticHash", "Semantic hash",
                "release.sourceHash", "Source hash", "release.bpmnHash", "BPMN hash", "release.compilerVersion",
                "Compiler version", "release.schemaVersion", "Definition schema", "release.processDefinitionId",
                "Engine definition", "release.provenance", "Provenance", "workspace.name", "Workspace",
                "generatedAt", "Pack generated", "generatedBy", "Generated by"));
        sections.add(EvidenceDocument.table("Approvals and separation of duties",
                "Independent review: " + EvidenceDocument.text(pack.path("controls").path("independentReview"))
                        + ". Warnings acknowledged at publication: "
                        + EvidenceDocument.text(pack.path("controls").path("warningsAcknowledged")) + ".",
                pack.path("approvals"), "No approvals recorded.", "role", "Role", "subject", "Person", "action", "What",
                "at", "When"));
        sections.add(EvidenceDocument.table("Publication tests", pack.path("tests").path("summary").asString(),
                pack.path("tests").path("results"), "No test case results.", "name", "Test case", "caseId", "Case id",
                "passed", "Passed", "status", "Status"));
        if (pack.path("tests").hasNonNull("testedBy")) {
            sections.add(EvidenceDocument.fields("Test run", null, pack.path("tests"), "status", "Result",
                    "suiteVersion", "Suite version", "testedBy", "Run by", "testedAt", "Run at"));
        }
        sections.add(EvidenceDocument.table("Changes from the previous release", pack.path("diff").path("summary").asString(),
                pack.path("diff").path("changes"), "No changes listed.", "kind", "Change", "area", "Area", "title",
                "Detail"));
        sections.add(EvidenceDocument.table("Warnings acknowledged at publication", null, pack.path("warnings"),
                "No warnings.", "severity", "Severity", "code", "Code", "message", "Message", "nodeId", "Step"));
        sections.add(EvidenceDocument.table("Release artifacts", null, pack.path("artifacts"), "No artifacts.", "name",
                "File", "mediaType", "Type", "sizeBytes", "Bytes", "sha256", "SHA-256"));
        sections.add(EvidenceDocument.table("Pinned references", "Versions this release is bound to; later changes to them do not affect it.",
                pack.path("pinnedReferences"), "No pinned rule tables, connections, child workflows or shared assets.",
                "type", "Type", "key", "Key", "version", "Version", "hash", "Hash"));
        sections.add(EvidenceDocument.table("Promotion requests", null, pack.path("promotions"),
                "No promotion requests for this release.", "targetWorkspace", "Target workspace", "status", "Status",
                "requestedBy", "Requested by", "requestedAt", "Requested", "decidedBy", "Decided by", "decidedAt", "Decided"));
        sections.add(EvidenceDocument.table("Release audit history", null, pack.path("history"), "No audit records.",
                "at", "When", "action", "Action", "actor", "Actor", "outcome", "Outcome", "details", "Details"));
        return new EvidenceDocument(title, "Workspace " + pack.path("workspace").path("name").asString()
                + ". " + PAYLOAD_POLICY, List.copyOf(sections), integrity(pack));
    }

    // ------------------------------------------------------------------ shared helpers

    /** Adds the integrity block: SHA-256 over the RFC 8785 canonical JSON of the pack without that block. */
    static ObjectNode seal(Json json, ObjectNode pack) {
        String digest = json.canonicalSha256(pack);
        pack.putObject("integrity").put("algorithm", "SHA-256").put("digest", digest)
                .put("covers", "RFC 8785 canonical JSON of this pack without the integrity member");
        return pack;
    }

    static String integrity(ObjectNode pack) {
        return "Content digest (SHA-256 over the RFC 8785 canonical JSON of this pack without its integrity member): "
                + pack.path("integrity").path("digest").asString()
                + ". The JSON format of the same export carries the data this digest covers.";
    }

    static ObjectNode subject(UUID id, Map<UUID, String> names) {
        ObjectNode node = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("id", id.toString()).put("displayName", names.getOrDefault(id, id.toString()));
        return node;
    }

    private static Optional<UUID> uuid(JsonNode value) {
        try {
            return value.isString() ? Optional.of(UUID.fromString(value.asString())) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<ReleaseRow> release(UUID ws, UUID workflowId, UUID releaseId) {
        return jdbc.sql("""
                SELECT r.id, r.workflow_id, w.key, w.name, w.active_release_id, r.release_number, r.draft_revision,
                       r.definition::text AS definition, r.schema_version, r.source_hash, r.semantic_hash,
                       r.compiler_version, r.manifest::text AS manifest, r.warnings::text AS warnings,
                       r.process_definition_id, r.release_note, r.published_by, r.published_at,
                       d.saved_by AS draft_saved_by, d.saved_at AS draft_saved_at
                FROM workflow_release r
                JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                LEFT JOIN workflow_draft_revision d ON d.workflow_id = r.workflow_id AND d.revision = r.draft_revision
                WHERE r.workspace_id = :ws AND r.workflow_id = :wf AND r.id = :id
                """).param("ws", ws).param("wf", workflowId).param("id", releaseId)
                .query((rs, n) -> new ReleaseRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "workflow_id"), rs.getString("key"),
                        rs.getString("name"), Sql.uuid(rs, "active_release_id"), rs.getInt("release_number"),
                        rs.getInt("draft_revision"), json.parse(rs.getString("definition")),
                        rs.getString("schema_version"), rs.getString("source_hash").trim(),
                        rs.getString("semantic_hash").trim(), rs.getString("compiler_version"),
                        json.parse(rs.getString("manifest")), json.parse(rs.getString("warnings")),
                        rs.getString("process_definition_id"), rs.getString("release_note"),
                        Sql.uuid(rs, "published_by"), Sql.instant(rs, "published_at"), Sql.uuid(rs, "draft_saved_by"),
                        Sql.instant(rs, "draft_saved_at")))
                .optional();
    }
}
