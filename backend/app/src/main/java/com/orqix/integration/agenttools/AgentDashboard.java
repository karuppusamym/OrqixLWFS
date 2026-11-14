package com.orqix.integration.agenttools;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.web.Sql;

/**
 * Agent control plane (CG-M05, S135): calls, cost, failure codes and p95 duration per agent, refused tool calls (a
 * security signal), schema-change refusals, active kill switches, durable loops and open human requests. Facts only:
 * nothing here is a prompt, an argument or a result.
 */
@Service
public class AgentDashboard {

    public record AgentStats(String connectionKey, int calls, int succeeded, int failed, BigDecimal costUsd,
            Long p95DurationMs, Map<String, Integer> failureCodes) {
    }

    /**
     * @param aiTaskAgreement agreement of AI tasks with people; null until AI task decisions (CG-A03) record it
     */
    public record Dashboard(int days, Instant from, List<AgentStats> agents, Map<String, Integer> refusedToolCalls,
            List<McpToolPins.Drift> schemaRefusals, List<AgentKillSwitches.Switch> killSwitches,
            Map<String, Integer> loops, int openRequests, Double aiTaskAgreement) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final PolicyService policy;
    private final McpToolPins pins;
    private final AgentKillSwitches switches;

    public AgentDashboard(JdbcClient jdbc, Clock clock, PolicyService policy, McpToolPins pins,
            AgentKillSwitches switches) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.policy = policy;
        this.pins = pins;
        this.switches = switches;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(Caller caller, UUID workspaceId, Integer days) {
        WorkspaceAccess access = policy.access(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        if (!access.has(Permission.RUN_OPERATE) && !access.has(Permission.CONNECTION_READ)) {
            policy.require(access, Permission.RUN_OPERATE, "getAgentDashboard", false);
        }
        int window = days == null ? 7 : Math.max(1, Math.min(90, days));
        Instant from = clock.instant().minus(Duration.ofDays(window));
        List<AgentStats> agents = jdbc.sql("""
                SELECT connection_key, count(*) AS calls,
                    count(*) FILTER (WHERE outcome = 'succeeded') AS succeeded,
                    count(*) FILTER (WHERE outcome <> 'succeeded') AS failed,
                    coalesce(sum(cost_usd), 0) AS cost,
                    percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95
                FROM agent_call WHERE workspace_id = :ws AND created_at >= :from
                GROUP BY connection_key ORDER BY connection_key
                """).param("ws", workspaceId).param("from", Sql.ts(from))
                .query((rs, n) -> new AgentStats(rs.getString("connection_key"), rs.getInt("calls"),
                        rs.getInt("succeeded"), rs.getInt("failed"), rs.getBigDecimal("cost"),
                        rs.getObject("p95") == null ? null : Math.round(rs.getDouble("p95")),
                        failureCodes(workspaceId, rs.getString("connection_key"), from)))
                .list();
        Map<String, Integer> refused = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT t ->> 'outcome' AS outcome, count(*) AS n
                FROM agent_call c, jsonb_array_elements(c.tool_calls) t
                WHERE c.workspace_id = :ws AND c.created_at >= :from
                  AND (t ->> 'outcome' LIKE 'refused%' OR t ->> 'outcome' IN ('invalidArguments', 'declined'))
                GROUP BY 1 ORDER BY 2 DESC
                """).param("ws", workspaceId).param("from", Sql.ts(from))
                .query((rs, n) -> Map.entry(rs.getString("outcome"), rs.getInt("n"))).list()
                .forEach(e -> refused.put(e.getKey(), e.getValue()));
        int open = jdbc.sql("SELECT count(*) FROM agent_human_request WHERE workspace_id = :ws AND status = 'open'")
                .param("ws", workspaceId).query(Integer.class).single();
        return new Dashboard(window, from, agents, refused, pins.openDrift(workspaceId, null),
                switches.all(workspaceId).stream().filter(AgentKillSwitches.Switch::active).toList(),
                loopCounts(workspaceId), open, null);
    }

    private Map<String, Integer> loopCounts(UUID workspaceId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM agent_loop WHERE workspace_id = :ws GROUP BY status ORDER BY status")
                .param("ws", workspaceId).query((rs, n) -> Map.entry(rs.getString("status"), rs.getInt("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private Map<String, Integer> failureCodes(UUID workspaceId, String key, Instant from) {
        Map<String, Integer> codes = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT error_code, count(*) AS n FROM agent_call
                WHERE workspace_id = :ws AND connection_key = :key AND created_at >= :from AND error_code IS NOT NULL
                GROUP BY error_code ORDER BY 2 DESC LIMIT 10
                """).param("ws", workspaceId).param("key", key).param("from", Sql.ts(from))
                .query((rs, n) -> Map.entry(rs.getString("error_code"), rs.getInt("n"))).list()
                .forEach(e -> codes.put(e.getKey(), e.getValue()));
        return codes;
    }
}
