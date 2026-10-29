package com.orqix.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.api.CatalogDtos;
import com.orqix.catalog.internal.ApplicationViews;
import com.orqix.catalog.internal.ApprovalViewCheck;
import com.orqix.catalog.internal.RequestViewCheck;
import com.orqix.catalog.internal.WorkflowService;
import com.orqix.catalog.internal.WorklistViews;
import com.orqix.identity.Caller;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins of one published view held by catalog assets (S114): workflow drafts and active releases, the workspace worklist,
 * page drafts and published pages. Each use carries the diagnostics a move to the target version would have. Applying a
 * use goes through the holder's own service (draft revision, worklist revision, page draft revision), so its permission,
 * version check and audit apply unchanged. Published releases and page versions are never changed.
 */
@Service
public class ViewPins {
    /** A published view version to move pins to; {@code latest} is the newest published version. */
    public record Target(UUID id, String kind, String name, int version, int latest, JsonNode definition) { }

    /**
     * One holder of the view. {@code status} is ready, current (already on the target), newer (on a later version),
     * blocked (the target does not fit) or readOnly (an immutable release or published version, shown for review).
     */
    public record Use(String key, String holderType, String holderId, String holderName, String location,
            List<Integer> pinnedVersions, Integer expected, String status, List<String> problems, String effect) { }

    private final JdbcClient jdbc;
    private final Json json;
    private final RequestViewCheck requests;
    private final ApplicationViews views;
    private final WorkflowService workflows;
    private final WorklistViews worklists;

    public ViewPins(JdbcClient jdbc, Json json, RequestViewCheck requests, ApplicationViews views,
            WorkflowService workflows, WorklistViews worklists) {
        this.jdbc = jdbc;
        this.json = json;
        this.requests = requests;
        this.views = views;
        this.workflows = workflows;
        this.worklists = worklists;
    }

    @Transactional(readOnly = true)
    public Target target(UUID ws, UUID viewId, Integer version) {
        var view = jdbc.sql("SELECT kind, name, published_version FROM application_view WHERE workspace_id = :ws AND id = :id")
                .param("ws", ws).param("id", viewId)
                .query((rs, n) -> new Object[] { rs.getString(1), rs.getString(2), rs.getObject(3) }).optional()
                .orElseThrow(ApiException::notFound);
        if (view[2] == null) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Publish this view before moving pins to it.");
        int latest = (Integer) view[2];
        int chosen = version == null ? latest : version;
        String definition = jdbc.sql("""
                SELECT definition::text FROM application_view_version WHERE workspace_id = :ws AND view_id = :id AND version = :version
                """).param("ws", ws).param("id", viewId).param("version", chosen).query(String.class).optional()
                .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a published version of this view."));
        return new Target(viewId, (String) view[0], (String) view[1], chosen, latest, json.parse(definition));
    }

    @Transactional(readOnly = true)
    public List<Use> uses(UUID ws, Target target) {
        List<Use> uses = new ArrayList<>();
        String id = target.id().toString();
        if (target.kind().equals("request") || target.kind().equals("approval")) {
            jdbc.sql("""
                    SELECT w.id, w.name, w.draft_revision, d.definition::text AS definition FROM workflow w
                    JOIN workflow_draft_revision d ON d.workspace_id = w.workspace_id AND d.workflow_id = w.id AND d.revision = w.draft_revision
                    WHERE w.workspace_id = :ws AND w.archived_at IS NULL AND strpos(d.definition::text, :id) > 0
                    ORDER BY lower(w.name), w.id
                    """).param("ws", ws).param("id", id).query((rs, n) -> new Object[] { rs.getObject(1, UUID.class),
                            rs.getString(2), rs.getInt(3), json.parse(rs.getString(4)) }).list()
                    .forEach(row -> workflowDraft(target, (UUID) row[0], (String) row[1], (int) row[2], (JsonNode) row[3], uses));
            jdbc.sql("""
                    SELECT w.id, w.name, r.release_number, r.definition::text AS definition FROM workflow w
                    JOIN workflow_release r ON r.workspace_id = w.workspace_id AND r.id = w.active_release_id
                    WHERE w.workspace_id = :ws AND w.archived_at IS NULL AND strpos(r.definition::text, :id) > 0
                    ORDER BY lower(w.name), w.id
                    """).param("ws", ws).param("id", id).query((rs, n) -> new Object[] { rs.getObject(1, UUID.class),
                            rs.getString(2), rs.getInt(3), json.parse(rs.getString(4)) }).list()
                    .forEach(row -> {
                        var pins = workflowPins(target.id(), (JsonNode) row[3]);
                        if (pins.isEmpty()) return;
                        uses.add(new Use("release:" + row[0], "workflowRelease", row[0].toString(), (String) row[1],
                                "Release " + row[2], versions(pins), null, "readOnly", List.of(),
                                "Release " + row[2] + " keeps its pinned version. Publish a new release from the updated draft to change it."));
                    });
        }
        if (target.kind().equals("worklist")) {
            jdbc.sql("SELECT revision, view_version FROM workspace_worklist_view WHERE workspace_id = :ws AND view_id = :id")
                    .param("ws", ws).param("id", target.id()).query((rs, n) -> new int[] { rs.getInt(1), rs.getInt(2) })
                    .optional().ifPresent(row -> {
                        List<String> problems = new ArrayList<>();
                        try {
                            WorklistViews.validate(target.definition());
                        } catch (ApiException e) {
                            problems.add(message(e));
                        }
                        uses.add(new Use("worklist", "worklist", ws.toString(), "Workspace worklist", "Task list presentation",
                                List.of(row[1]), row[0], status(List.of(row[1]), target.version(), problems), problems,
                                "Assigns the new version as worklist revision " + (row[0] + 1) + "."));
                    });
        }
        if (!target.kind().equals("page")) {
            jdbc.sql("""
                    SELECT id, name, draft_revision, draft_definition::text AS definition FROM application_view
                    WHERE workspace_id = :ws AND kind = 'page' AND id <> :view AND strpos(draft_definition::text, :id) > 0
                    ORDER BY lower(name), id
                    """).param("ws", ws).param("view", target.id()).param("id", id)
                    .query((rs, n) -> new Object[] { rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                            json.parse(rs.getString(4)) }).list()
                    .forEach(row -> pageDraft(ws, target, (UUID) row[0], (String) row[1], (int) row[2], (JsonNode) row[3], uses));
            jdbc.sql("""
                    SELECT a.id, a.name, v.version, v.definition::text AS definition FROM application_view a
                    JOIN application_view_version v ON v.workspace_id = a.workspace_id AND v.view_id = a.id AND v.version = a.published_version
                    WHERE a.workspace_id = :ws AND a.kind = 'page' AND a.id <> :view AND strpos(v.definition::text, :id) > 0
                    ORDER BY lower(a.name), a.id
                    """).param("ws", ws).param("view", target.id()).param("id", id)
                    .query((rs, n) -> new Object[] { rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                            json.parse(rs.getString(4)) }).list()
                    .forEach(row -> {
                        var pins = pagePins(target.id(), (JsonNode) row[3]);
                        if (pins.isEmpty()) return;
                        uses.add(new Use("pageVersion:" + row[0], "pageVersion", row[0].toString(), (String) row[1],
                                "Published version " + row[2], versions(pins), null, "readOnly", List.of(),
                                "Published version " + row[2] + " keeps its pins. Publish the updated page draft to change them."));
                    });
        }
        return uses;
    }

    /** Applies one ready use through its holder's service, which checks the expected revision again. */
    @Transactional
    public String apply(Caller caller, UUID ws, Target target, Use use) {
        switch (use.holderType()) {
            case "workflowDraft" -> {
                UUID workflow = UUID.fromString(use.holderId());
                var draft = jdbc.sql("""
                        SELECT d.definition::text FROM workflow w JOIN workflow_draft_revision d
                        ON d.workspace_id = w.workspace_id AND d.workflow_id = w.id AND d.revision = w.draft_revision
                        WHERE w.workspace_id = :ws AND w.id = :id AND w.draft_revision = :revision
                        """).param("ws", ws).param("id", workflow).param("revision", use.expected()).query(String.class)
                        .optional().orElseThrow(() -> changed(use));
                var saved = workflows.saveDraft(caller, ws, workflow, WorkflowService.etag(use.expected()),
                        new CatalogDtos.SaveDraftRequest(migrateWorkflow(target, json.parse(draft))), "migrateViewPins",
                        "viewPinMigration");
                return "Saved draft revision " + saved.revision() + ".";
            }
            case "worklist" -> {
                var state = worklists.assign(caller, ws, new WorklistViews.Assign(use.expected(),
                        new ApplicationViewQueries.Pin(target.id(), target.version())));
                return "Assigned as worklist revision " + state.revision() + ".";
            }
            case "pageDraft" -> {
                UUID page = UUID.fromString(use.holderId());
                var row = jdbc.sql("""
                        SELECT name, description, draft_definition::text FROM application_view
                        WHERE workspace_id = :ws AND id = :id AND draft_revision = :revision
                        """).param("ws", ws).param("id", page).param("revision", use.expected())
                        .query((rs, n) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3) }).optional()
                        .orElseThrow(() -> changed(use));
                var saved = views.save(caller, ws, page, new ApplicationViews.Save(use.expected(), row[0], row[1],
                        migratePage(target, json.parse(row[2]))));
                return "Saved page draft revision " + saved.draftRevision() + ".";
            }
            default -> throw new ApiException(ProblemCode.VALIDATION_FAILED, use.holderName() + " cannot be changed here.");
        }
    }

    private void workflowDraft(Target target, UUID workflow, String name, int revision, JsonNode draft, List<Use> uses) {
        var pins = workflowPins(target.id(), draft);
        if (pins.isEmpty()) return;
        List<String> problems = new ArrayList<>();
        String status = status(versions(pins), target.version(), problems);
        if (status.equals("ready")) {
            JsonNode migrated = migrateWorkflow(target, draft);
            if (target.kind().equals("request")) problems.addAll(requests.matches(migrated, target.definition()));
            else {
                String problem = ApprovalViewCheck.compatibility(migrated, target.definition());
                if (problem != null) problems.add(problem);
            }
            if (!problems.isEmpty()) status = "blocked";
        }
        uses.add(new Use("workflow:" + workflow, "workflowDraft", workflow.toString(), name,
                String.join(", ", pins.stream().map(Pin::location).toList()), versions(pins), revision, status,
                List.copyOf(problems), "Saves draft revision " + (revision + 1) + ". Publish a release to use it."));
    }

    private void pageDraft(UUID ws, Target target, UUID page, String name, int revision, JsonNode draft, List<Use> uses) {
        var pins = pagePins(target.id(), draft);
        if (pins.isEmpty()) return;
        List<String> problems = new ArrayList<>();
        String status = status(versions(pins), target.version(), problems);
        if (status.equals("ready")) {
            try {
                views.check(ws, migratePage(target, draft));
            } catch (ApiException e) {
                problems.add(message(e));
                status = "blocked";
            }
        }
        uses.add(new Use("page:" + page, "pageDraft", page.toString(), name,
                String.join(", ", pins.stream().map(Pin::location).toList()), versions(pins), revision, status,
                List.copyOf(problems), "Saves page draft revision " + (revision + 1) + ". Publish the page and pin its route to use it."));
    }

    private record Pin(String location, int version) { }

    private List<Pin> workflowPins(UUID view, JsonNode workflow) {
        List<Pin> pins = new ArrayList<>();
        JsonNode ref = workflow.path("inputUi").path("viewRef");
        if (view.toString().equals(ref.path("id").asString(""))) pins.add(new Pin("Request form", ref.path("version").asInt()));
        for (JsonNode node : workflow.path("nodes")) {
            JsonNode binding = node.path("config").path("viewBinding");
            if (view.toString().equals(binding.path("id").asString(""))) {
                pins.add(new Pin("Approval step " + node.path("label").asString(node.path("id").asString()), binding.path("version").asInt()));
            }
        }
        return pins;
    }

    private List<Pin> pagePins(UUID view, JsonNode page) {
        List<Pin> pins = new ArrayList<>();
        for (JsonNode region : page.path("regions")) for (JsonNode component : region.path("components")) {
            JsonNode pin = component.path("view");
            if (view.toString().equals(pin.path("id").asString(""))) {
                pins.add(new Pin("Component " + component.path("id").asString(), pin.path("version").asInt()));
            }
        }
        return pins;
    }

    /** The workflow with every pin of this view moved to the target: requests re-project, approvals copy the view. */
    JsonNode migrateWorkflow(Target target, JsonNode draft) {
        JsonNode next = draft;
        if (target.id().toString().equals(draft.path("inputUi").path("viewRef").path("id").asString(""))) {
            next = requests.project(draft, target.id(), target.version(), target.definition());
        } else {
            next = draft.deepCopy();
        }
        for (JsonNode node : next.path("nodes")) {
            JsonNode binding = node.path("config").path("viewBinding");
            if (target.id().toString().equals(binding.path("id").asString(""))) {
                ObjectNode replaced = ((ObjectNode) node.path("config")).putObject("viewBinding");
                replaced.put("id", target.id().toString()).put("version", target.version()).set("definition", target.definition().deepCopy());
            }
        }
        return next;
    }

    private JsonNode migratePage(Target target, JsonNode page) {
        ObjectNode next = (ObjectNode) page.deepCopy();
        for (JsonNode region : next.path("regions")) for (JsonNode component : region.path("components")) {
            JsonNode pin = component.path("view");
            if (target.id().toString().equals(pin.path("id").asString(""))) ((ObjectNode) pin).put("version", target.version());
        }
        return next;
    }

    private static List<Integer> versions(List<Pin> pins) {
        return pins.stream().map(Pin::version).distinct().sorted().toList();
    }

    static String status(List<Integer> pinned, int target, List<String> problems) {
        if (pinned.stream().allMatch(v -> v == target)) return "current";
        if (pinned.stream().anyMatch(v -> v > target)) {
            problems.add("It already uses a later version than " + target + ".");
            return "newer";
        }
        return problems.isEmpty() ? "ready" : "blocked";
    }

    private static String message(ApiException e) {
        return e.detail() == null ? e.code().title() : e.detail();
    }

    private static ApiException changed(Use use) {
        return new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, use.holderName() + " changed since you reviewed it. Review the pins again.");
    }
}
