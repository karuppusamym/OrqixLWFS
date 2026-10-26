package com.orqix.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.web.Sql;

/**
 * In-app notifications (S5 section 9, BD-18). Delivery is in-app only; creation is idempotent through the dedup key so
 * a retried job never notifies twice.
 */
@Service
public class NotificationService {

    public enum Kind {
        WORKFLOW_MESSAGE("workflowMessage"), RUN_ATTENTION("runAttention"),
        /** CMP01 (docs/build/S87): someone mentioned the recipient in a step or connection discussion. */
        DISCUSSION_MENTION("discussionMention");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public record NotificationRow(UUID id, UUID runId, String runReference, String kind, String message,
            Instant createdAt, Instant readAt, UUID workflowId, String elementId) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true when a new notification was created, false when the dedup key already existed */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean create(UUID workspaceId, UUID recipient, UUID runId, Kind kind, String dedupKey, String message) {
        String text = message.length() > 500 ? message.substring(0, 500) : message;
        return jdbc.sql("""
                INSERT INTO notification (id, workspace_id, recipient_subject_id, run_id, kind, dedup_key, message, created_at)
                VALUES (:id, :ws, :recipient, :run, :kind, :dedup, :message, :at)
                ON CONFLICT (dedup_key) DO NOTHING
                """)
                .param("id", Ids.newId()).param("ws", workspaceId).param("recipient", recipient).param("run", runId)
                .param("kind", kind.value()).param("dedup", dedupKey).param("message", text)
                .param("at", Sql.ts(clock.instant()))
                .update() == 1;
    }

    /**
     * CMP01: a discussion mention. Points at the workflow, element and comment (no run). Idempotent through the dedup
     * key; the caller must have checked that the recipient may read the workflow, and the message must not contain the
     * comment text.
     *
     * @return true when a new notification was created
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean createMention(UUID workspaceId, UUID recipient, UUID workflowId, String elementId, UUID commentId,
            String dedupKey, String message) {
        String text = message.length() > 500 ? message.substring(0, 500) : message;
        return jdbc.sql("""
                INSERT INTO notification (id, workspace_id, recipient_subject_id, kind, dedup_key, message, created_at,
                                          workflow_id, element_id, comment_id)
                VALUES (:id, :ws, :recipient, :kind, :dedup, :message, :at, :wf, :el, :comment)
                ON CONFLICT (dedup_key) DO NOTHING
                """)
                .param("id", Ids.newId()).param("ws", workspaceId).param("recipient", recipient)
                .param("kind", Kind.DISCUSSION_MENTION.value()).param("dedup", dedupKey).param("message", text)
                .param("at", Sql.ts(clock.instant())).param("wf", workflowId).param("el", elementId)
                .param("comment", commentId)
                .update() == 1;
    }

    /** Marks every unread notification of the recipient in the workspace read; returns how many changed. */
    @Transactional
    public int markAllRead(UUID workspaceId, UUID recipient) {
        return jdbc.sql("""
                UPDATE notification SET read_at = :at
                WHERE workspace_id = :ws AND recipient_subject_id = :recipient AND read_at IS NULL
                """)
                .param("at", Sql.ts(clock.instant())).param("ws", workspaceId).param("recipient", recipient).update();
    }

    /** Fetches up to {@code limit + 1} rows after {@code after} (keyset on created_at, id). */
    @Transactional(readOnly = true)
    public List<NotificationRow> list(UUID workspaceId, UUID recipient, boolean unreadOnly, CursorCodec.Position after,
            int limitPlusOne) {
        return jdbc.sql("""
                SELECT n.id, n.run_id, r.reference AS run_reference, n.kind, n.message, n.created_at, n.read_at,
                       n.workflow_id, n.element_id
                FROM notification n
                LEFT JOIN workflow_run r ON r.workspace_id = n.workspace_id AND r.id = n.run_id
                WHERE n.workspace_id = :ws AND n.recipient_subject_id = :recipient
                  AND (:unread = false OR n.read_at IS NULL)
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (n.created_at, n.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("recipient", recipient).param("unread", unreadOnly)
                .param("afterAt", after == null ? null : Sql.ts(after.at()))
                .param("afterId", after == null ? null : UUID.fromString(after.id()))
                .param("limit", limitPlusOne)
                .query((rs, n) -> new NotificationRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "run_id"),
                        rs.getString("run_reference"), rs.getString("kind"), rs.getString("message"),
                        Sql.instant(rs, "created_at"), Sql.instant(rs, "read_at"), Sql.uuid(rs, "workflow_id"),
                        rs.getString("element_id")))
                .list();
    }

    @Transactional(readOnly = true)
    public int unreadCount(UUID workspaceId, UUID recipient) {
        return jdbc.sql("""
                SELECT count(*) FROM notification
                WHERE workspace_id = :ws AND recipient_subject_id = :recipient AND read_at IS NULL
                """)
                .param("ws", workspaceId).param("recipient", recipient).query(Integer.class).single();
    }

    /** @return false when the notification does not exist for this recipient in this workspace */
    @Transactional
    public boolean markRead(UUID workspaceId, UUID recipient, UUID notificationId) {
        Integer exists = jdbc.sql("""
                SELECT 1 FROM notification WHERE workspace_id = :ws AND id = :id AND recipient_subject_id = :recipient
                FOR UPDATE
                """)
                .param("ws", workspaceId).param("id", notificationId).param("recipient", recipient)
                .query(Integer.class).optional().orElse(null);
        if (exists == null) {
            return false;
        }
        jdbc.sql("""
                UPDATE notification SET read_at = :at
                WHERE workspace_id = :ws AND id = :id AND recipient_subject_id = :recipient AND read_at IS NULL
                """)
                .param("at", Sql.ts(clock.instant())).param("ws", workspaceId).param("id", notificationId)
                .param("recipient", recipient).update();
        return true;
    }

    @Transactional(readOnly = true)
    public int countForRun(UUID workspaceId, UUID runId, Kind kind) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE workspace_id = :ws AND run_id = :run AND kind = :kind")
                .param("ws", workspaceId).param("run", runId).param("kind", kind.value()).query(Integer.class).single();
    }
}
