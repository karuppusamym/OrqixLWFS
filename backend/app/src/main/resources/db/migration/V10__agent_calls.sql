-- S13 agents and MCP (docs/build/S13-agents-mcp.md section 6). Expand-only.
--
-- (1) Agent executions are metered in ai_generation like every other AI use, so the monthly budget is one number:
--     kind 'agent' is added to the CHECK. Existing outcome values are reused (success, invalidOutput, providerError,
--     credentialsRejected, modelNotAllowed); no new outcome is introduced.
-- (2) agent_call: one row per attempt of an agent effect, linked to the effect journal. Holds facts only: model, number
--     of model turns, tool names with timings and outcomes, tokens and cost. Never a prompt, an input value, a tool
--     argument, a tool result or model output. Append-only for the runtime role.
-- Flyway placeholder: ${app_role} = runtime DML role (e.g. orqix_app).

ALTER TABLE ai_generation DROP CONSTRAINT IF EXISTS ai_generation_kind_check;
ALTER TABLE ai_generation ADD CONSTRAINT ai_generation_kind_check CHECK (kind IN ('draft', 'explain', 'agent'));

CREATE TABLE agent_call (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL,
    effect_id           uuid NOT NULL,
    run_id              uuid NOT NULL,
    node_id             text NOT NULL,
    attempt             integer NOT NULL CHECK (attempt >= 1),
    connection_key      text NOT NULL,
    connection_version  integer NOT NULL CHECK (connection_version >= 1),
    operation           text NOT NULL,
    profile             text NOT NULL CHECK (length(profile) BETWEEN 1 AND 32),
    model               text NOT NULL CHECK (length(model) BETWEEN 1 AND 200),
    turns               integer NOT NULL CHECK (turns >= 0),
    -- [{"name": "...", "durationMs": 120, "outcome": "ok|toolError|invalidArguments|refusedNotAllowed|refusedLimit"}]
    tool_calls          jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(tool_calls) = 'array'),
    input_tokens        bigint CHECK (input_tokens >= 0),
    output_tokens       bigint CHECK (output_tokens >= 0),
    cost_usd            numeric(18, 10) CHECK (cost_usd >= 0),
    duration_ms         bigint NOT NULL CHECK (duration_ms >= 0),
    outcome             text NOT NULL CHECK (outcome IN ('succeeded', 'retryable', 'permanent')),
    error_code          text CHECK (length(error_code) <= 64),
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, effect_id) REFERENCES effect_journal (workspace_id, id)
);
CREATE INDEX agent_call_by_run ON agent_call (workspace_id, run_id, created_at);
CREATE INDEX agent_call_by_effect ON agent_call (effect_id);

GRANT SELECT, INSERT ON agent_call TO ${app_role};
