package com.orqix.apps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.cases.CaseService;
import com.orqix.catalog.ApplicationPages;
import com.orqix.catalog.ApplicationViewQueries;
import com.orqix.catalog.ViewPins;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Reviewed version migration of view and page pins (UI02 / S114). The review lists every holder of the view with
 * diagnostics against a chosen published version and a hash of that plan. Applying re-reads the plan, refuses it when
 * anything changed since the review, and moves the selected holders in one transaction, each through its own service:
 * workflow and page drafts get a new draft revision, a case gets a new definition version, the worklist and page routes
 * get a new revision. Published workflow releases and page versions never change. Any failure rolls back every item.
 */
@Service
public class PinMigrations {
    public record Item(String key, String holderType, String holderId, String holderName, String location,
            List<Integer> pinnedVersions, Integer expected, String status, List<String> problems, String effect) { }
    public record Inventory(UUID viewId, String viewName, String kind, int latestVersion, int targetVersion,
            String planHash, List<Item> items) { }
    public record Apply(Integer targetVersion, String planHash, List<String> items, String reason) { }
    public record Applied(String key, String holderType, String holderName, String outcome) { }
    public record Migration(UUID id, int targetVersion, String reason, List<Applied> items, UUID appliedBy,
            Instant appliedAt) { }
    public record History(List<Migration> items) { }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final ViewPins pins;
    private final CaseService cases;
    private final AppPages routes;
    private final ApplicationPages pages;
    private final Json json;
    private final AuditWriter audit;

    public PinMigrations(JdbcClient jdbc, PolicyService policy, ViewPins pins, CaseService cases, AppPages routes,
            ApplicationPages pages, Json json, AuditWriter audit) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.pins = pins;
        this.cases = cases;
        this.routes = routes;
        this.pages = pages;
        this.json = json;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Inventory inventory(Caller caller, UUID ws, UUID viewId, Integer targetVersion) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_READ, "listViewPins", false);
        return plan(ws, pins.target(ws, viewId, targetVersion));
    }

    @Transactional
    public Migration apply(Caller caller, UUID ws, UUID viewId, Apply request) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_EDIT, "migrateViewPins", true);
        if (request == null || request.targetVersion() == null || request.planHash() == null || request.items() == null
                || request.items().isEmpty() || request.items().size() > 50 || new HashSet<>(request.items()).size() != request.items().size()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose 1 to 50 distinct pins to update.");
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty() || reason.length() > 500) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give a reason of 1 to 500 characters.");
        ViewPins.Target target = pins.target(ws, viewId, request.targetVersion());
        Inventory plan = plan(ws, target);
        if (!plan.planHash().equals(request.planHash())) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The pins changed since you reviewed them. Review them again before updating.")
                    .with("planHash", plan.planHash());
        }
        Map<String, Item> byKey = new LinkedHashMap<>();
        plan.items().forEach(item -> byKey.put(item.key(), item));
        List<Item> chosen = new ArrayList<>();
        for (String key : request.items()) {
            Item item = byKey.get(key);
            if (item == null || !item.status().equals("ready")) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, (item == null ? key : item.holderName()) + " is not ready to move to version " + target.version() + ".");
            }
            chosen.add(item);
        }
        List<Applied> applied = new ArrayList<>();
        for (Item item : chosen) applied.add(new Applied(item.key(), item.holderType(), item.holderName(), apply(caller, ws, target, item)));
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO application_view_pin_migration (id, workspace_id, view_id, target_version, plan_hash, reason, items, applied_by)
                VALUES (:id, :ws, :view, :version, :hash, :reason, CAST(:items AS jsonb), :by)
                """).param("id", id).param("ws", ws).param("view", viewId).param("version", target.version())
                .param("hash", plan.planHash()).param("reason", reason).param("items", json.write(applied))
                .param("by", caller.subjectId()).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "view.pins-migrated",
                "applicationView", viewId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("targetVersion", target.version(), "items", applied.size())));
        return history(ws, viewId, id).getFirst();
    }

    @Transactional(readOnly = true)
    public History history(Caller caller, UUID ws, UUID viewId) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_READ, "listViewPinMigrations", false);
        pins.target(ws, viewId, null);
        return new History(history(ws, viewId, null));
    }

    private String apply(Caller caller, UUID ws, ViewPins.Target target, Item item) {
        return switch (item.holderType()) {
            case "caseDefinition" -> {
                var created = cases.reviseView(caller, ws, UUID.fromString(item.holderId()), item.expected(),
                        new ApplicationViewQueries.Pin(target.id(), target.version()));
                yield "Published case definition version " + created.version() + ".";
            }
            case "route" -> {
                var route = routes.assign(caller, ws, item.holderId(), new AppPages.Assign(item.expected(),
                        new ApplicationViewQueries.Pin(target.id(), target.version())));
                yield "Pinned as route revision " + route.revision() + ".";
            }
            default -> pins.apply(caller, ws, target, new ViewPins.Use(item.key(), item.holderType(), item.holderId(),
                    item.holderName(), item.location(), item.pinnedVersions(), item.expected(), item.status(),
                    item.problems(), item.effect()));
        };
    }

    private Inventory plan(UUID ws, ViewPins.Target target) {
        List<Item> items = new ArrayList<>();
        for (ViewPins.Use use : pins.uses(ws, target)) {
            items.add(new Item(use.key(), use.holderType(), use.holderId(), use.holderName(), use.location(),
                    use.pinnedVersions(), use.expected(), use.status(), use.problems(), use.effect()));
        }
        if (target.kind().equals("case")) {
            var pinned = new ApplicationViewQueries.Pinned(target.id(), target.version(), target.definition());
            for (CaseService.ViewUse use : cases.viewUses(ws, target.id(), pinned)) {
                List<String> problems = new ArrayList<>();
                String status = status(List.of(use.viewVersion()), target.version(), problems);
                if (status.equals("ready") && use.problem() != null) {
                    problems.add(use.problem());
                    status = "blocked";
                }
                items.add(new Item("case:" + use.definitionId(), "caseDefinition", use.definitionId().toString(),
                        use.name() + " (" + use.key() + ")", "Case data view of version " + use.version(),
                        List.of(use.viewVersion()), use.version(), status, List.copyOf(problems),
                        "Publishes case definition version " + (use.version() + 1) + " with the same model, data and workflow tasks. Existing cases keep their version."));
            }
            for (CaseService.ViewUse use : cases.olderViewUses(ws, target.id())) {
                items.add(new Item("caseVersion:" + use.definitionId(), "caseVersion", use.definitionId().toString(),
                        use.name() + " (" + use.key() + ")", "Case definition version " + use.version() + " (older)",
                        List.of(use.viewVersion()), null, "readOnly", List.of(),
                        "Version " + use.version() + " is not the newest; cases started on it keep its view."));
            }
        }
        if (target.kind().equals("page")) {
            String routable = null;
            try {
                pages.requireRoutable(ws, target.id(), target.version());
            } catch (ApiException e) {
                routable = e.detail() == null ? e.code().title() : e.detail();
            }
            String problem = routable;
            jdbc.sql("""
                    SELECT slug, revision, view_version FROM application_page_route
                    WHERE workspace_id = :ws AND view_id = :id ORDER BY slug
                    """).param("ws", ws).param("id", target.id())
                    .query((rs, n) -> new Object[] { rs.getString(1), rs.getInt(2), rs.getInt(3) }).list().forEach(row -> {
                        List<String> problems = new ArrayList<>();
                        String status = status(List.of((Integer) row[2]), target.version(), problems);
                        if (status.equals("ready") && problem != null) {
                            problems.add(problem);
                            status = "blocked";
                        }
                        items.add(new Item("route:" + row[0], "route", (String) row[0], "/apps/" + row[0], "Page route",
                                List.of((Integer) row[2]), (Integer) row[1], status, List.copyOf(problems),
                                "Pins the route to the new version as revision " + ((Integer) row[1] + 1) + "."));
                    });
        }
        return new Inventory(target.id(), target.name(), target.kind(), target.latest(), target.version(),
                hash(target, items), List.copyOf(items));
    }

    private static String status(List<Integer> pinned, int target, List<String> problems) {
        if (pinned.stream().allMatch(v -> v == target)) return "current";
        if (pinned.stream().anyMatch(v -> v > target)) {
            problems.add("It already uses a later version than " + target + ".");
            return "newer";
        }
        return "ready";
    }

    /** Hash of everything the reviewer saw that applying depends on: holders, their revisions, pins and status. */
    private String hash(ViewPins.Target target, List<Item> items) {
        List<Object> facts = new ArrayList<>();
        facts.add(target.id().toString() + ":" + target.version());
        for (Item item : items) facts.add(List.of(item.key(), String.valueOf(item.expected()), item.pinnedVersions().toString(),
                item.status(), String.join("|", item.problems())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.write(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Migration> history(UUID ws, UUID viewId, UUID only) {
        return jdbc.sql("""
                SELECT id, target_version, reason, items::text AS items, applied_by, applied_at FROM application_view_pin_migration
                WHERE workspace_id = :ws AND view_id = :view AND (CAST(:only AS uuid) IS NULL OR id = :only)
                ORDER BY applied_at DESC, id LIMIT 50
                """).param("ws", ws).param("view", viewId).param("only", only)
                .query((rs, n) -> new Migration(Sql.uuid(rs, "id"), rs.getInt("target_version"), rs.getString("reason"),
                        List.of(json.convert(json.parse(rs.getString("items")), Applied[].class)), Sql.uuid(rs, "applied_by"),
                        Sql.instant(rs, "applied_at"))).list();
    }
}
