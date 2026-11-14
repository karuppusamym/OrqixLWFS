package com.orqix.integration.health;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.integration.IntegrationStatus;
import com.orqix.shared.web.Sql;

/** {@link IntegrationStatus} from the S131 connection health and the S133 package triggers (docs/build/S142). */
@Service
public class IntegrationStatusService implements IntegrationStatus {

    private final ConnectionHealthService health;
    private final JdbcClient jdbc;
    private final Clock clock;

    public IntegrationStatusService(ConnectionHealthService health, JdbcClient jdbc, Clock clock) {
        this.health = health;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectionVolume> connections(UUID workspaceId) {
        List<ConnectionVolume> result = new ArrayList<>();
        for (ConnectionHealthService.HealthView v : health.views(workspaceId)) {
            result.add(new ConnectionVolume(v.connectionKey(), v.name(), v.bound(), v.lastHour().calls(),
                    v.lastHour().successRate(), v.lastDay().calls(), v.lastDay().calls() - v.lastDay().succeeded(),
                    v.lastError() == null ? null : v.lastError().errorCode(), v.circuit().state(), v.notices().size()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerLag> packageTriggers(UUID workspaceId) {
        return jdbc.sql("""
                SELECT t.id, t.package_id, t.trigger_key, t.kind, t.enabled, t.last_run_at, t.last_success_at,
                       t.last_lag_seconds, t.last_error_code, t.next_run_at,
                       (SELECT count(*) FROM package_trigger_record r WHERE r.trigger_id = t.id AND r.status = 'dead'
                          AND r.received_at > :day) AS dead
                FROM package_trigger t WHERE t.workspace_id = :ws
                ORDER BY t.package_id, t.trigger_key LIMIT 200
                """)
                .param("ws", workspaceId).param("day", Sql.ts(clock.instant().minus(Duration.ofHours(24))))
                .query((rs, n) -> new TriggerLag(Sql.uuid(rs, "id"), rs.getString("package_id"),
                        rs.getString("trigger_key"), rs.getString("kind"), rs.getBoolean("enabled"),
                        Sql.instant(rs, "last_run_at"), Sql.instant(rs, "last_success_at"),
                        rs.getObject("last_lag_seconds") == null ? null : rs.getLong("last_lag_seconds"),
                        rs.getString("last_error_code"), rs.getInt("dead"), Sql.instant(rs, "next_run_at")))
                .list();
    }
}
