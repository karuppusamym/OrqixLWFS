package com.orqix.runtime.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * CG-P05 admission control per workspace (docs/build/S150): at most N new requests and M inbound messages per sliding
 * minute. Every start (REST, Kafka and package triggers, webhooks through package triggers, MCP, schedules, pages and
 * conversations) passes {@link RunService#start}, and every message {@link MessageService#send}; both ask here first,
 * so the limit is the same whatever the door. A refusal is 429 {@code RATE_LIMITED} with {@code Retry-After}; Kafka and
 * package triggers already treat that code as "try again later", so their records are delayed, never dead-lettered.
 *
 * <p>The count is the committed rows of the last 60 seconds (workflow_run.created_at, inbound_message.received_at), so
 * it holds across pods without a shared counter or an extra write on the start path. Starts that are still uncommitted
 * are not seen: under concurrency a workspace may exceed the limit by the number of starts in flight (decision S150:
 * this protects the platform, it is not billing). Child requests a workflow creates count as requests of the workspace.
 *
 * <p>Refusals are counted ({@code orqix_admission_rejected_total}) and audited at most once per workspace, kind and
 * minute on each pod ({@code admission.limit-exceeded}, in its own transaction, since the refused call rolls back).
 */
@Service
public class AdmissionControl {

    public enum Kind {
        START("starts"), MESSAGE("messages");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Stored override; null fields keep the deployment default. */
    public record Override(Integer startsPerMinute, Integer messagesPerMinute, int revision, UUID updatedBy,
            Instant updatedAt) {
    }

    /** Effective limits of one workspace; 0 = no limit. */
    public record Limits(int startsPerMinute, int messagesPerMinute) {
        int of(Kind kind) {
            return kind == Kind.START ? startsPerMinute : messagesPerMinute;
        }
    }

    static final long WINDOW_MILLIS = 60_000;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AdmissionSettings settings;
    private final AuditWriter audit;
    private final PlatformMetrics metrics;
    private final Map<UUID, CachedLimits> limits = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAudit = new ConcurrentHashMap<>();

    private record CachedLimits(long readAt, Limits limits) {
    }

    public AdmissionControl(JdbcClient jdbc, Clock clock, AdmissionSettings settings, AuditWriter audit,
            PlatformMetrics metrics) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.audit = audit;
        this.metrics = metrics;
    }

    public AdmissionSettings settings() {
        return settings;
    }

    /** @throws ApiException RATE_LIMITED with Retry-After when the workspace started its limit in the last minute */
    public void admitStart(UUID workspaceId, UUID subjectId) {
        admit(Kind.START, workspaceId, subjectId);
    }

    /** @throws ApiException RATE_LIMITED with Retry-After when the workspace received its limit in the last minute */
    public void admitMessage(UUID workspaceId, UUID subjectId) {
        admit(Kind.MESSAGE, workspaceId, subjectId);
    }

    private void admit(Kind kind, UUID workspaceId, UUID subjectId) {
        if (!settings.enabled()) {
            return;
        }
        int limit = limits(workspaceId).of(kind);
        if (limit <= 0) {
            return;
        }
        Instant now = clock.instant();
        Instant since = now.minusMillis(WINDOW_MILLIS);
        // At most `limit` rows of the window, newest first: the count saturates at the limit, and the oldest of them
        // must leave the window before another one fits.
        String sql = kind == Kind.START
                ? "SELECT created_at FROM workflow_run WHERE workspace_id = :ws AND created_at > :since"
                        + " ORDER BY created_at DESC LIMIT :limit"
                : "SELECT received_at FROM inbound_message WHERE workspace_id = :ws AND received_at > :since"
                        + " ORDER BY received_at DESC LIMIT :limit";
        List<Timestamp> recent = jdbc.sql(sql).param("ws", workspaceId).param("since", Sql.ts(since))
                .param("limit", limit).query(Timestamp.class).list();
        if (recent.size() < limit) {
            return;
        }
        long oldest = recent.get(recent.size() - 1).getTime();
        long waitMillis = oldest + WINDOW_MILLIS - now.toEpochMilli();
        long retryAfter = Math.max(1, Math.min(60, (waitMillis + 999) / 1000));
        metrics.admissionRejected(kind.name().toLowerCase(Locale.ROOT));
        auditOncePerMinute(kind, workspaceId, subjectId, limit, now);
        throw new ApiException(ProblemCode.RATE_LIMITED, kind == Kind.START
                ? "This workspace started " + limit + " requests in the last minute, its limit. Try again shortly."
                : "This workspace received " + limit + " messages in the last minute, its limit. Try again shortly.")
                .withHeader("Retry-After", String.valueOf(retryAfter));
    }

    private void auditOncePerMinute(Kind kind, UUID workspaceId, UUID subjectId, int limit, Instant now) {
        String key = workspaceId + "/" + kind;
        long at = now.toEpochMilli();
        Long previous = lastAudit.get(key);
        if (previous != null && at - previous < WINDOW_MILLIS) {
            return;
        }
        lastAudit.put(key, at);
        if (lastAudit.size() > 10_000) {
            lastAudit.entrySet().removeIf(e -> at - e.getValue() >= WINDOW_MILLIS);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", kind.label());
        metadata.put("limitPerMinute", limit);
        try {
            audit.writeSeparately(new AuditWriter.Entry(workspaceId, subjectId == null ? AuditWriter.ActorType.SYSTEM
                    : AuditWriter.ActorType.USER, subjectId, "admission.limit-exceeded", "workspace",
                    workspaceId.toString(), AuditWriter.Outcome.DENIED, metadata));
        } catch (RuntimeException e) {
            // The refusal stands even when its audit row cannot be written (for example a full connection pool).
            lastAudit.remove(key);
        }
    }

    // ------------------------------------------------------------------ limits

    /** Effective limits: the workspace override where set, else the deployment default. */
    public Limits limits(UUID workspaceId) {
        long now = clock.millis();
        CachedLimits hit = limits.get(workspaceId);
        if (hit != null && now - hit.readAt() < settings.limitCacheTtl().toMillis()) {
            return hit.limits();
        }
        Limits effective = effective(override(workspaceId).orElse(null));
        if (limits.size() > 50_000) {
            limits.clear();
        }
        limits.put(workspaceId, new CachedLimits(now, effective));
        return effective;
    }

    public Limits effective(Override o) {
        return new Limits(o == null || o.startsPerMinute() == null ? settings.startsPerMinute() : o.startsPerMinute(),
                o == null || o.messagesPerMinute() == null ? settings.messagesPerMinute() : o.messagesPerMinute());
    }

    @Transactional(readOnly = true)
    public Optional<Override> override(UUID workspaceId) {
        return jdbc.sql("""
                SELECT starts_per_minute, messages_per_minute, revision, updated_by, updated_at
                FROM workspace_admission_limit WHERE workspace_id = :ws
                """).param("ws", workspaceId).query((rs, n) -> new Override(
                        (Integer) rs.getObject("starts_per_minute"), (Integer) rs.getObject("messages_per_minute"),
                        rs.getInt("revision"), (UUID) rs.getObject("updated_by"), Sql.instant(rs, "updated_at")))
                .optional();
    }

    /** Workspaces with an override, newest change first (at most 500: overrides are exceptions). */
    @Transactional(readOnly = true)
    public List<Map.Entry<UUID, Override>> overrides() {
        return jdbc.sql("""
                SELECT workspace_id, starts_per_minute, messages_per_minute, revision, updated_by, updated_at
                FROM workspace_admission_limit ORDER BY updated_at DESC, workspace_id LIMIT 500
                """).query((rs, n) -> Map.entry((UUID) rs.getObject("workspace_id"), new Override(
                        (Integer) rs.getObject("starts_per_minute"), (Integer) rs.getObject("messages_per_minute"),
                        rs.getInt("revision"), (UUID) rs.getObject("updated_by"), Sql.instant(rs, "updated_at"))))
                .list();
    }

    /**
     * Sets or clears (both null) the workspace's override; audited as {@code platform.admission-updated}.
     *
     * @return the stored override, empty when cleared
     */
    @Transactional
    public Optional<Override> update(UUID workspaceId, UUID actor, Integer startsPerMinute, Integer messagesPerMinute) {
        requireWorkspace(workspaceId);
        Instant now = clock.instant();
        Optional<Override> previous = override(workspaceId);
        boolean cleared = startsPerMinute == null && messagesPerMinute == null;
        if (cleared) {
            jdbc.sql("DELETE FROM workspace_admission_limit WHERE workspace_id = :ws").param("ws", workspaceId).update();
        } else {
            jdbc.sql("""
                    INSERT INTO workspace_admission_limit (workspace_id, starts_per_minute, messages_per_minute, revision,
                                                           updated_by, updated_at)
                    VALUES (:ws, :starts, :messages, 1, :by, :now)
                    ON CONFLICT (workspace_id) DO UPDATE SET starts_per_minute = EXCLUDED.starts_per_minute,
                        messages_per_minute = EXCLUDED.messages_per_minute,
                        revision = workspace_admission_limit.revision + 1, updated_by = EXCLUDED.updated_by,
                        updated_at = EXCLUDED.updated_at
                    """).param("ws", workspaceId).param("starts", startsPerMinute).param("messages", messagesPerMinute)
                    .param("by", actor).param("now", Sql.ts(now)).update();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("startsPerMinute", startsPerMinute);
        metadata.put("messagesPerMinute", messagesPerMinute);
        metadata.put("previousStartsPerMinute", previous.map(Override::startsPerMinute).orElse(null));
        metadata.put("previousMessagesPerMinute", previous.map(Override::messagesPerMinute).orElse(null));
        metadata.put("cleared", cleared);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, actor, "platform.admission-updated",
                "workspace", workspaceId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        limits.remove(workspaceId);
        return override(workspaceId);
    }

    /** @throws ApiException NOT_FOUND when no such workspace exists */
    public void requireWorkspace(UUID workspaceId) {
        if (jdbc.sql("SELECT 1 FROM workspace WHERE id = :ws").param("ws", workspaceId).query(Integer.class).optional()
                .isEmpty()) {
            throw ApiException.notFound();
        }
    }

    /** Tests start without cached limits or audit throttling. */
    public void reset() {
        limits.clear();
        lastAudit.clear();
    }
}
