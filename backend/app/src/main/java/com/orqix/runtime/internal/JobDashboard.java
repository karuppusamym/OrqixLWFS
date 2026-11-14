package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.IntegrationStatus;
import com.orqix.runtime.api.BulkDtos;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.web.Sql;

/**
 * CG-O05 (docs/build/S142): the integration and job dashboard ({@code run.operate}). External job queues by topic
 * (depth, oldest waiting job, failures, when a worker last asked), open polling checks per connection operation, connector
 * package trigger lag, and, with {@code connection.read}, call volume and errors per connection (the S131 health). Only
 * counts, times and codes: never job input, request data or secrets.
 */
@Service
public class JobDashboard {

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final IntegrationStatus integration;
    private final Clock clock;

    public JobDashboard(JdbcClient jdbc, PolicyService policy, IntegrationStatus integration, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.integration = integration;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BulkDtos.JobDashboard get(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "getJobDashboard", false);
        Instant now = clock.instant();
        return new BulkDtos.JobDashboard(queues(workspaceId, now), polls(workspaceId, now),
                integration.packageTriggers(workspaceId),
                access.has(Permission.CONNECTION_READ) ? integration.connections(workspaceId) : null, now);
    }

    private List<BulkDtos.JobQueue> queues(UUID workspaceId, Instant now) {
        Instant day = now.minus(Duration.ofHours(24));
        Instant hour = now.minus(Duration.ofHours(1));
        Map<String, BulkDtos.JobQueue> byTopic = new TreeMap<>();
        jdbc.sql("""
                SELECT topic,
                       count(*) FILTER (WHERE status = 'available') AS available,
                       count(*) FILTER (WHERE status = 'locked') AS locked,
                       count(*) FILTER (WHERE status = 'failed' AND ended_at > :day) AS failed,
                       min(available_at) FILTER (WHERE status = 'available' AND available_at <= :now) AS oldest
                FROM external_job
                WHERE workspace_id = :ws AND (status IN ('available', 'locked') OR ended_at > :day)
                GROUP BY topic
                """)
                .param("ws", workspaceId).param("day", Sql.ts(day)).param("now", Sql.ts(now))
                .query((rs, n) -> {
                    Instant oldest = Sql.instant(rs, "oldest");
                    String topic = rs.getString("topic");
                    byTopic.put(topic, new BulkDtos.JobQueue(topic, rs.getInt("available"), rs.getInt("locked"),
                            rs.getInt("failed"), oldest, oldest == null ? null : Duration.between(oldest, now).toSeconds(),
                            null, 0));
                    return topic;
                }).list();
        jdbc.sql("""
                SELECT topic, max(last_seen_at) AS last_seen, count(*) FILTER (WHERE last_seen_at > :hour) AS recent
                FROM external_worker_seen WHERE workspace_id = :ws GROUP BY topic
                """)
                .param("ws", workspaceId).param("hour", Sql.ts(hour))
                .query((rs, n) -> {
                    String topic = rs.getString("topic");
                    BulkDtos.JobQueue q = byTopic.getOrDefault(topic,
                            new BulkDtos.JobQueue(topic, 0, 0, 0, null, null, null, 0));
                    byTopic.put(topic, new BulkDtos.JobQueue(topic, q.available(), q.locked(), q.failedLastDay(),
                            q.oldestAvailableAt(), q.oldestAgeSeconds(), Sql.instant(rs, "last_seen"),
                            rs.getInt("recent")));
                    return topic;
                }).list();
        return List.copyOf(byTopic.values());
    }

    private List<BulkDtos.PollCheckSummary> polls(UUID workspaceId, Instant now) {
        return jdbc.sql("""
                SELECT connection_key, operation,
                       count(*) FILTER (WHERE state = 'waiting') AS waiting,
                       count(*) FILTER (WHERE state = 'checking') AS checking,
                       count(*) FILTER (WHERE state = 'attention') AS attention,
                       count(*) FILTER (WHERE state = 'waiting' AND next_check_at < :late) AS late,
                       min(next_check_at) FILTER (WHERE state = 'waiting') AS next_check,
                       max(last_checked_at) AS last_checked
                FROM poll_wait WHERE workspace_id = :ws AND state IN ('waiting', 'checking', 'attention')
                GROUP BY connection_key, operation ORDER BY connection_key, operation LIMIT 200
                """)
                .param("ws", workspaceId).param("late", Sql.ts(now.minus(Duration.ofMinutes(1))))
                .query((rs, n) -> new BulkDtos.PollCheckSummary(rs.getString("connection_key"),
                        rs.getString("operation"), rs.getInt("waiting"), rs.getInt("checking"),
                        rs.getInt("attention"), rs.getInt("late"), Sql.instant(rs, "next_check"),
                        Sql.instant(rs, "last_checked")))
                .list();
    }
}
