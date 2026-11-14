-- CG-M05 (docs/build/S135): opt-in conversation trace. The default stays metadata only (S13): without an enabled
-- setting nothing below is written. When a workspace enables it, turns and tool calls are kept with arguments, results
-- and model text after redaction (built-in rules plus workspace patterns and field names), for retention_days
-- (default 7, at most 30), then deleted by the hourly sweep. Rows link to the run and the step (node_id) so the run
-- diagram can open the trace of a step.

CREATE TABLE agent_trace_setting (
    workspace_id    uuid PRIMARY KEY REFERENCES workspace (id),
    enabled         boolean NOT NULL,
    retention_days  integer NOT NULL DEFAULT 7 CHECK (retention_days BETWEEN 1 AND 30),
    redact_fields   jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(redact_fields) = 'array'),
    redact_patterns jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(redact_patterns) = 'array'),
    changed_by      uuid NOT NULL REFERENCES subject (id),
    changed_at      timestamptz NOT NULL DEFAULT now(),
    version         integer NOT NULL DEFAULT 1 CHECK (version >= 1)
);
GRANT SELECT, INSERT, UPDATE ON agent_trace_setting TO ${app_role};

CREATE TABLE agent_trace_event (
    id              uuid PRIMARY KEY,
    workspace_id    uuid NOT NULL REFERENCES workspace (id),
    run_id          uuid NOT NULL,
    node_id         text NOT NULL,
    effect_id       uuid,
    seq             integer NOT NULL CHECK (seq >= 1),
    kind            text NOT NULL CHECK (kind IN ('input', 'turn', 'toolCall', 'wait', 'answer', 'end')),
    tool_name       text,
    model           text,
    outcome         text,
    duration_ms     bigint CHECK (duration_ms >= 0),
    cost_usd        numeric(18, 10) CHECK (cost_usd >= 0),
    content         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id)
);
CREATE INDEX agent_trace_event_run ON agent_trace_event (workspace_id, run_id, node_id, seq);
CREATE INDEX agent_trace_event_expiry ON agent_trace_event (expires_at);
GRANT SELECT, INSERT, DELETE ON agent_trace_event TO ${app_role};

-- Dashboard reads (CG-M05) aggregate recent agent calls per workspace.
CREATE INDEX IF NOT EXISTS agent_call_ws_created ON agent_call (workspace_id, created_at DESC);
