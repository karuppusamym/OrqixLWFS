package com.orqix.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

/** Writes {@code agent_call} rows (V10): metering facts of one agent attempt, never prompts or tool payloads. */
@Component
public class AgentCallLog {

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public AgentCallLog(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Joins the caller's transaction; the executor calls it inside its own independent transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AgentExecutor.Request request, String profile, String model, int turns,
            List<Map<String, Object>> toolCalls, Long inputTokens, Long outputTokens, BigDecimal costUsd,
            long durationMs, String outcome, String errorCode) {
        jdbc.sql("""
                INSERT INTO agent_call (id, workspace_id, effect_id, run_id, node_id, attempt, connection_key,
                    connection_version, operation, profile, model, turns, tool_calls, input_tokens, output_tokens,
                    cost_usd, duration_ms, outcome, error_code, created_at)
                VALUES (:id, :ws, :effect, :run, :node, :attempt, :key, :version, :operation, :profile, :model, :turns,
                    CAST(:tools AS jsonb), :input, :output, :cost, :duration, :outcome, :error, :at)
                """)
                .param("id", Ids.newId()).param("ws", request.workspaceId()).param("effect", request.effectId())
                .param("run", request.runId()).param("node", request.nodeId()).param("attempt", Math.max(1, request.attempt()))
                .param("key", request.definition().key()).param("version", request.definition().version())
                .param("operation", request.operation().name()).param("profile", profile)
                .param("model", model == null || model.isBlank() ? "unknown" : model.length() > 200 ? model.substring(0, 200) : model)
                .param("turns", turns).param("tools", json.write(toolCalls)).param("input", inputTokens)
                .param("output", outputTokens).param("cost", costUsd).param("duration", durationMs)
                .param("outcome", outcome).param("error", errorCode == null ? null
                        : errorCode.length() > 64 ? errorCode.substring(0, 64) : errorCode)
                .param("at", Sql.ts(clock.instant()))
                .update();
    }
}
