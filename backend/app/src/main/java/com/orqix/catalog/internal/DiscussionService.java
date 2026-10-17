package com.orqix.catalog.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.orqix.identity.WorkspaceAccess;
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
 */
@Service
public class DiscussionService {

    public static final int MAX_THREADS_PER_WORKFLOW = 500;
    public static final int MAX_REPLIES_PER_THREAD = 100;

    public record Comment(UUID id, String elementId, UUID parentId, String body, UUID authorId, String authorName,
            Instant createdAt, boolean resolved, Instant resolvedAt, String resolvedByName) {
    }

    public record Thread(Comment root, List<Comment> replies) {
    }

    public record Discussion(List<Thread> threads, Map<String, Integer> openByElement, boolean canPost) {
    }

    public record PostRequest(@NotBlank @Size(max = 128) String elementId, @NotBlank @Size(max = 2000) String body,
            UUID parentId) {
    }

    public record ResolveRequest(@NotNull Boolean resolved) {
    }

    private final JdbcClient jdbc;
    private final WorkflowService workflows;
    private final CatalogStore store;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Json json;

    public DiscussionService(JdbcClient jdbc, WorkflowService workflows, CatalogStore store, PolicyService policy,
            AuditWriter audit, Json json) {
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.store = store;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
    }

    private static boolean mayPost(WorkspaceAccess access) {
        return access.has(Permission.WORKFLOW_EDIT) || access.has(Permission.WORKFLOW_PUBLISH);
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

    private static Comment comment(Row r) {
        return new Comment(r.id(), r.elementId(), r.parentId(), r.body(), r.authorId(), r.authorName(), r.createdAt(),
                r.resolvedAt() != null, r.resolvedAt(), r.resolvedByName());
    }

    private Discussion assemble(UUID ws, UUID wf, String elementId, boolean canPost) {
        List<Row> all = rows(ws, wf, elementId);
        Map<UUID, List<Comment>> replies = new LinkedHashMap<>();
        for (Row r : all) {
            if (r.parentId() != null) {
                replies.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(comment(r));
            }
        }
        List<Thread> threads = new ArrayList<>();
        for (Row r : all) {
            if (r.parentId() == null) {
                threads.add(new Thread(comment(r), replies.getOrDefault(r.id(), List.of())));
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

    private boolean elementExists(UUID ws, UUID wf, int revision, String elementId) {
        CatalogStore.DraftRow draft = store.draft(ws, wf, revision).orElse(null);
        if (draft == null) {
            return false;
        }
        JsonNode def = json.parse(draft.definitionJson());
        for (String list : new String[] { "nodes", "transitions" }) {
            for (JsonNode item : def.path(list)) {
                if (elementId.equals(item.path("id").asString(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void requirePoster(Caller caller, UUID ws, UUID wf, String operation) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, operation);
        policy.require(visible.access(), Permission.WORKFLOW_READ, operation, false);
        if (!mayPost(visible.access())) {
            policy.require(visible.access(), Permission.WORKFLOW_EDIT, operation, true);
        }
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
        if (!elementExists(ws, wf, row.draftRevision(), elementId)) {
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
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.comment.created",
                "workflow", wf.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("commentId", id.toString(), "reply", request.parentId() != null)));
        return rows(ws, wf, elementId).stream().filter(r -> r.id().equals(id)).map(DiscussionService::comment).findFirst()
                .orElseThrow();
    }

    /** Resolve or reopen a thread (its first comment). Repeating the same state is a no-op. */
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
        return rows(ws, wf, target.elementId()).stream().filter(r -> r.id().equals(commentId))
                .map(DiscussionService::comment).findFirst().orElseThrow();
    }
}
