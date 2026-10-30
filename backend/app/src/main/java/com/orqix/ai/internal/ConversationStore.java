package com.orqix.ai.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/** The saved Ask AI conversation of a workflow draft ({@code ai_draft_conversation}, V205, S124). Text only. */
@Component
public class ConversationStore {

    public record Saved(JsonNode turns, UUID updatedBy, Instant updatedAt, int version) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public ConversationStore(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public Optional<Saved> find(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("SELECT turns::text AS turns, updated_by, updated_at, version FROM ai_draft_conversation"
                        + " WHERE workspace_id = :ws AND workflow_id = :wf")
                .param("ws", workspaceId).param("wf", workflowId)
                .query((rs, n) -> new Saved(json.parse(rs.getString("turns")), Sql.uuid(rs, "updated_by"),
                        Sql.instant(rs, "updated_at"), rs.getInt("version")))
                .optional();
    }

    public Saved save(UUID workspaceId, UUID workflowId, JsonNode turns, UUID subjectId) {
        Instant now = clock.instant();
        int version = jdbc.sql("""
                INSERT INTO ai_draft_conversation (workflow_id, workspace_id, turns, updated_by, updated_at)
                VALUES (:wf, :ws, CAST(:turns AS jsonb), :by, :at)
                ON CONFLICT (workflow_id) DO UPDATE SET turns = EXCLUDED.turns, updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at, version = ai_draft_conversation.version + 1
                RETURNING version""")
                .param("wf", workflowId).param("ws", workspaceId).param("turns", json.write(turns))
                .param("by", subjectId).param("at", Sql.ts(now))
                .query(Integer.class).single();
        return new Saved(turns, subjectId, now, version);
    }

    public boolean delete(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("DELETE FROM ai_draft_conversation WHERE workspace_id = :ws AND workflow_id = :wf")
                .param("ws", workspaceId).param("wf", workflowId).update() > 0;
    }
}
