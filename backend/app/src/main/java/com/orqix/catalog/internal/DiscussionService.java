package com.orqix.catalog.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import com.orqix.notification.NotificationService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * UI03 (docs/build/S53): threaded discussion on one step or connection of a workflow draft. Anyone who can read the
 * workflow can read the threads; posting and resolving need {@code workflow.edit} or {@code workflow.publish} (so a
 * reviewer can comment without being able to change the draft). A thread has one level of replies. Comments are notes:
 * they never change the draft, grant access or block publishing. Comment text is never copied into audit metadata.
 *
 * <p>CMP01 (docs/build/S87): a comment may mention up to {@link #MAX_MENTIONS} people. A mention is kept only when the
 * person exists, is enabled, is not the author, can read this workflow's discussion in this workspace today (fresh
 * access, never a cached session) and appears as {@code @Display Name} in the text. Each kept mention gives that person
 * exactly one in-app notification for the comment (dedup key per comment and person). The notification names the
 * author, the element and the workflow but never contains the comment text. Anything else in the request is ignored
 * silently, so the request cannot be used to probe who has access.
 */
@Service
public class DiscussionService {

    public static final int MAX_THREADS_PER_WORKFLOW = 500;
    public static final int MAX_REPLIES_PER_THREAD = 100;
    public static final int MAX_MENTIONS = 10;
    /** Mention suggestions returned per request, and how many enabled people are examined to find them. */
    public static final int MAX_CANDIDATES = 10;
    static final int CANDIDATE_SCAN = 100;

    public record Comment(UUID id, String elementId, UUID parentId, String body, UUID authorId, String authorName,
            Instant createdAt, boolean resolved, Instant resolvedAt, String resolvedByName,
            List<SubjectService.SubjectRef> mentions) {
    }

    public record Thread(Comment root, List<Comment> replies) {
    }

    public record Discussion(List<Thread> threads, Map<String, Integer> openByElement, boolean canPost) {
    }

    public record PostRequest(@NotBlank @Size(max = 128) String elementId, @NotBlank @Size(max = 2000) String body,
            UUID parentId, @Size(max = MAX_MENTIONS) List<UUID> mentions) {
    }

    public record ResolveRequest(@NotNull Boolean resolved) {
    }

    public record Candidates(List<SubjectService.SubjectRef> items) {
    }

    private final JdbcClient jdbc;
    private final WorkflowService workflows;
    private final CatalogStore store;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Json json;
    private final SubjectService subjects;
    private final NotificationService notifications;

    public DiscussionService(JdbcClient jdbc, WorkflowService workflows, CatalogStore store, PolicyService policy,
            AuditWriter audit, Json json, SubjectService subjects, NotificationService notifications) {
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.store = store;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.subjects = subjects;
        this.notifications = notifications;
    }

    private static boolean mayPost(WorkspaceAccess access) {
        return access.has(Permission.WORKFLOW_EDIT) || access.has(Permission.WORKFLOW_PUBLISH);
    }

    /** The same rule as {@link #list}: the person can see the workspace and holds workflow.read there. */
    private static boolean mayRead(WorkspaceAccess access) {
        return access.visible() && access.has(Permission.WORKFLOW_READ);
    }

    private record Row(UUID id, String elementId, UUID parentId, String body, UUID authorId, String authorName,
            Instant createdAt, Instant resolvedAt, String resolvedByName) {
    }

    private List<Row> rows(UUID ws, UUID wf, String elementId) {
        return jdbc.sql("""
                SELECT c.id, c.element_id, c.parent_id, c.body, c.author_subject_id, a.display_name AS author_name,
                       c.created_at, c.resolved_at, r.display_name AS resolved_by_name
                FROM workflow_element_comment c
                JOIN subject a ON a.id = c.author_subject_id
                LEFT JOIN subject r ON r.id = c.resolved_by
                WHERE c.workspace_id = :ws AND c.workflow_id = :wf AND (CAST(:el AS text) IS NULL OR c.element_id = :el)
                ORDER BY c.created_at, c.id
                """).param("ws", ws).param("wf", wf).param("el", elementId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("element_id"),
                        rs.getObject("parent_id", UUID.class), rs.getString("body"),
                        rs.getObject("author_subject_id", UUID.class), rs.getString("author_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                        rs.getString("resolved_by_name")))
                .list();
    }

    /** Mentioned people of every comment of the workflow, by comment id (current display names). */
    private Map<UUID, List<SubjectService.SubjectRef>> mentions(UUID ws, UUID wf) {
        Map<UUID, List<SubjectService.SubjectRef>> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.comment_id, m.subject_id, s.display_name
                FROM workflow_comment_mention m JOIN subject s ON s.id = m.subject_id
                WHERE m.workspace_id = :ws AND m.workflow_id = :wf
                ORDER BY m.comment_id, s.display_name, m.subject_id
                """).param("ws", ws).param("wf", wf)
                .query((rs, n) -> result.computeIfAbsent(rs.getObject("comment_id", UUID.class), k -> new ArrayList<>())
                        .add(new SubjectService.SubjectRef(rs.getObject("subject_id", UUID.class), rs.getString("display_name"))))
                .list();
        return result;
    }

    private static Comment comment(Row r, Map<UUID, List<SubjectService.SubjectRef>> mentions) {
        return new Comment(r.id(), r.elementId(), r.parentId(), r.body(), r.authorId(), r.authorName(), r.createdAt(),
                r.resolvedAt() != null, r.resolvedAt(), r.resolvedByName(), mentions.getOrDefault(r.id(), List.of()));
    }

    private Comment one(UUID ws, UUID wf, String elementId, UUID id) {
        Map<UUID, List<SubjectService.SubjectRef>> mentions = mentions(ws, wf);
        return rows(ws, wf, elementId).stream().filter(r -> r.id().equals(id)).map(r -> comment(r, mentions))
                .findFirst().orElseThrow();
    }

    private Discussion assemble(UUID ws, UUID wf, String elementId, boolean canPost) {
        List<Row> all = rows(ws, wf, elementId);
        Map<UUID, List<SubjectService.SubjectRef>> mentions = mentions(ws, wf);
        Map<UUID, List<Comment>> replies = new LinkedHashMap<>();
        for (Row r : all) {
            if (r.parentId() != null) {
                replies.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(comment(r, mentions));
            }
        }
        List<Thread> threads = new ArrayList<>();
        for (Row r : all) {
            if (r.parentId() == null) {
                threads.add(new Thread(comment(r, mentions), replies.getOrDefault(r.id(), List.of())));
            }
        }
        Map<String, Integer> open = new LinkedHashMap<>();
        for (Row r : rows(ws, wf, null)) {
            if (r.parentId() == null && r.resolvedAt() == null) {
                open.merge(r.elementId(), 1, Integer::sum);
            }
        }
        return new Discussion(threads, open, canPost);
    }

    @Transactional(readOnly = true)
    public Discussion list(Caller caller, UUID ws, UUID wf, String elementId) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "listWorkflowDiscussion");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "listWorkflowDiscussion", false);
        if (elementId != null && (elementId.isBlank() || elementId.length() > 128)) {
            throw ApiException.malformed("elementId must be 1 to 128 characters.");
        }
        return assemble(ws, wf, elementId, mayPost(visible.access()));
    }

    /** A step ("step" and its label) or connection ("connection") of the draft, or null when it is not there. */
    record Element(String kind, String label) {
    }

    private Element element(UUID ws, UUID wf, int revision, String elementId) {
        CatalogStore.DraftRow draft = store.draft(ws, wf, revision).orElse(null);
        if (draft == null) {
            return null;
        }
        JsonNode def = json.parse(draft.definitionJson());
        for (JsonNode item : def.path("nodes")) {
            if (elementId.equals(item.path("id").asString(null))) {
                return new Element("step", item.path("label").asString(""));
            }
        }
        for (JsonNode item : def.path("transitions")) {
            if (elementId.equals(item.path("id").asString(null))) {
                return new Element("connection", "");
            }
        }
        return null;
    }

    private WorkspaceAccess requirePoster(Caller caller, UUID ws, UUID wf, String operation) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, operation);
        policy.require(visible.access(), Permission.WORKFLOW_READ, operation, false);
        if (!mayPost(visible.access())) {
            policy.require(visible.access(), Permission.WORKFLOW_EDIT, operation, true);
        }
        return visible.access();
    }

    /** Fresh access of another person (stored IdP groups of their latest sign-in), never a cached session. */
    private boolean personMayRead(SubjectService.Subject subject, UUID ws) {
        if (subject.disabled()) {
            return false;
        }
        Caller other = new Caller(subject.id(), subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(subject.id()), false);
        return policy.freshAccess(other, ws).map(DiscussionService::mayRead).orElse(false);
    }

    /**
     * CMP01: people the caller may mention on this workflow: enabled people, other than the caller, who can read this
     * workflow's discussion in this workspace. Only people who may post can ask (the list is a composer aid). At most
     * {@link #MAX_CANDIDATES}, chosen among the first {@link #CANDIDATE_SCAN} enabled people whose name matches.
     */
    @Transactional(readOnly = true)
    public Candidates mentionCandidates(Caller caller, UUID ws, UUID wf, String query) {
        requirePoster(caller, ws, wf, "listWorkflowMentionCandidates");
        String needle = query == null ? "" : query.strip();
        if (needle.length() > 100) {
            throw ApiException.malformed("q must be at most 100 characters.");
        }
        List<SubjectService.SubjectRef> items = new ArrayList<>();
        for (SubjectService.Subject s : subjects.searchByName(needle, CANDIDATE_SCAN)) {
            if (s.id().equals(caller.subjectId()) || !personMayRead(s, ws)) {
                continue;
            }
            items.add(new SubjectService.SubjectRef(s.id(), s.displayName()));
            if (items.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return new Candidates(items);
    }

    @Transactional
    public Comment post(Caller caller, UUID ws, UUID wf, PostRequest request) {
        requirePoster(caller, ws, wf, "postWorkflowComment");
        CatalogStore.WorkflowRow row = store.lockWorkflow(ws, wf).orElseThrow(ApiException::notFound);
        String body = request.body().trim();
        String elementId = request.elementId().trim();
        if (body.isEmpty() || elementId.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Write a comment.");
        }
        Element element = element(ws, wf, row.draftRevision(), elementId);
        if (element == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "That step or connection is not in the current draft.");
        }
        UUID id = Ids.newId();
        if (request.parentId() != null) {
            Row parent = rows(ws, wf, null).stream().filter(r -> r.id().equals(request.parentId())).findFirst()
                    .orElseThrow(ApiException::notFound);
            if (parent.parentId() != null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Reply to the first comment of the thread.");
            }
            if (!parent.elementId().equals(elementId)) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "The reply must be on the same step or connection.");
            }
            int replies = jdbc.sql("SELECT count(*) FROM workflow_element_comment WHERE workflow_id = :wf AND parent_id = :p")
                    .param("wf", wf).param("p", request.parentId()).query(Integer.class).single();
            if (replies >= MAX_REPLIES_PER_THREAD) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "A thread can have at most " + MAX_REPLIES_PER_THREAD + " replies.");
            }
        } else {
            int threads = jdbc.sql("SELECT count(*) FROM workflow_element_comment WHERE workflow_id = :wf AND parent_id IS NULL")
                    .param("wf", wf).query(Integer.class).single();
            if (threads >= MAX_THREADS_PER_WORKFLOW) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "A workflow can have at most " + MAX_THREADS_PER_WORKFLOW + " threads.");
            }
        }
        jdbc.sql("""
                INSERT INTO workflow_element_comment (id, workspace_id, workflow_id, element_id, parent_id, body, author_subject_id)
                VALUES (:id, :ws, :wf, :el, :parent, :body, :by)
                """).param("id", id).param("ws", ws).param("wf", wf).param("el", elementId)
                .param("parent", request.parentId()).param("body", body).param("by", caller.subjectId()).update();

        List<UUID> requested = request.mentions() == null ? List.of()
                : request.mentions().stream().filter(Objects::nonNull).distinct().toList();
        Set<UUID> kept = new LinkedHashSet<>();
        int notified = 0;
        String lowerBody = body.toLowerCase(Locale.ROOT);
        String message = mentionMessage(caller.displayName(), element, row.name());
        for (UUID subjectId : requested) {
            if (subjectId.equals(caller.subjectId())) {
                continue; // a self-mention is neither stored nor notified
            }
            SubjectService.Subject subject = subjects.find(subjectId).orElse(null);
            if (subject == null || !lowerBody.contains("@" + subject.displayName().toLowerCase(Locale.ROOT))
                    || !personMayRead(subject, ws)) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO workflow_comment_mention (workspace_id, workflow_id, comment_id, subject_id)
                    VALUES (:ws, :wf, :comment, :subject) ON CONFLICT DO NOTHING
                    """).param("ws", ws).param("wf", wf).param("comment", id).param("subject", subjectId).update();
            kept.add(subjectId);
            if (notifications.createMention(ws, subjectId, wf, elementId, id, "discussion-mention:" + id + ":" + subjectId,
                    message)) {
                notified++;
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("commentId", id.toString());
        metadata.put("reply", request.parentId() != null);
        if (!requested.isEmpty()) {
            metadata.put("mentionCount", kept.size());
            metadata.put("mentionsIgnored", requested.size() - kept.size());
            metadata.put("notified", notified);
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.comment.created",
                "workflow", wf.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return one(ws, wf, elementId, id);
    }

    /**
     * The notification text: who, where and which workflow; never the comment text. Labels are shortened so a long
     * label cannot fill the 500-character message.
     */
    static String mentionMessage(String author, Element element, String workflowName) {
        String who = shorten(author == null || author.isBlank() ? "Someone" : author, 80);
        String where = "step".equals(element.kind()) && !element.label().isBlank()
                ? "the step \"" + shorten(element.label(), 80) + "\""
                : "step".equals(element.kind()) ? "a step" : "a connection";
        return who + " mentioned you in the discussion on " + where + " of \"" + shorten(workflowName, 120) + "\".";
    }

    private static String shorten(String value, int max) {
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    /** Resolve or reopen a thread (its first comment). Repeating the same state is a no-op. Never notifies anyone. */
    @Transactional
    public Comment resolve(Caller caller, UUID ws, UUID wf, UUID commentId, ResolveRequest request) {
        requirePoster(caller, ws, wf, "resolveWorkflowThread");
        store.lockWorkflow(ws, wf).orElseThrow(ApiException::notFound);
        Row target = rows(ws, wf, null).stream().filter(r -> r.id().equals(commentId)).findFirst()
                .orElseThrow(ApiException::notFound);
        if (target.parentId() != null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Only a whole thread can be resolved.");
        }
        boolean wasResolved = target.resolvedAt() != null;
        if (wasResolved != request.resolved()) {
            jdbc.sql("""
                    UPDATE workflow_element_comment SET resolved_at = CASE WHEN :r THEN now() END,
                        resolved_by = CASE WHEN :r THEN CAST(:by AS uuid) END
                    WHERE workflow_id = :wf AND id = :id
                    """).param("r", request.resolved()).param("by", caller.subjectId()).param("wf", wf).param("id", commentId)
                    .update();
            audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                    request.resolved() ? "workflow.comment.resolved" : "workflow.comment.reopened", "workflow",
                    wf.toString(), AuditWriter.Outcome.SUCCESS, Map.of("commentId", commentId.toString())));
        }
        return one(ws, wf, target.elementId(), commentId);
    }
}
