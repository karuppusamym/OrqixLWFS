-- CG-M03 (docs/build/S135): durable agent loops. One row per agent step occurrence of a run whose operation declares
-- agent.loop. Each model turn runs as its own engine job; between turns the conversation lives only in state_cipher,
-- AES-GCM encrypted with the mounted key ring and bound to workspace, run and loop (AAD), and is cleared when the loop
-- ends or state_expires_at passes. Counters, cost and the wait are plain facts.
-- agent_human_request: a task for a person raised by a loop (ask a person, approval before a tool, MCP elicitation).
-- The question, the proposed arguments and the answer are encrypted the same way and cleared when the loop ends.

CREATE TABLE agent_loop (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    run_id              uuid NOT NULL,
    node_id             text NOT NULL,
    occurrence          integer NOT NULL CHECK (occurrence >= 1),
    effect_id           uuid NOT NULL,
    connection_key      text NOT NULL,
    connection_version  integer NOT NULL CHECK (connection_version >= 1),
    operation           text NOT NULL,
    status              text NOT NULL CHECK (status IN ('running', 'waiting', 'succeeded', 'failed', 'stopped')),
    turns               integer NOT NULL DEFAULT 0 CHECK (turns >= 0),
    tool_calls          integer NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    cost_usd            numeric(18, 10) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    budget_usd          numeric(18, 10) NOT NULL CHECK (budget_usd > 0),
    started_at          timestamptz NOT NULL DEFAULT now(),
    deadline_at         timestamptz NOT NULL,
    wait_kind           text CHECK (wait_kind IN ('ask', 'approval', 'elicitation', 'workflow')),
    wait_ref            uuid,
    wait_label          text CHECK (wait_label IS NULL OR char_length(wait_label) <= 80),
    child_pending       boolean NOT NULL DEFAULT false,
    state_key_id        text,
    state_cipher        text,
    state_expires_at    timestamptz,
    error_code          text CHECK (error_code IS NULL OR char_length(error_code) <= 64),
    version             integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, run_id, node_id, occurrence),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    FOREIGN KEY (workspace_id, effect_id) REFERENCES effect_journal (workspace_id, id),
    CONSTRAINT agent_loop_state CHECK ((state_cipher IS NULL) = (state_key_id IS NULL)),
    CONSTRAINT agent_loop_wait CHECK ((wait_kind IS NULL) = (status <> 'waiting'))
);
CREATE INDEX agent_loop_waiting ON agent_loop (wait_ref) WHERE status = 'waiting';
CREATE INDEX agent_loop_child_pending ON agent_loop (updated_at) WHERE child_pending;
CREATE INDEX agent_loop_state_expiry ON agent_loop (state_expires_at) WHERE state_cipher IS NOT NULL;
CREATE INDEX agent_loop_ws ON agent_loop (workspace_id, started_at DESC);
GRANT SELECT, INSERT, UPDATE ON agent_loop TO ${app_role};

CREATE TABLE agent_human_request (
    id              uuid PRIMARY KEY,
    workspace_id    uuid NOT NULL REFERENCES workspace (id),
    loop_id         uuid NOT NULL,
    run_id          uuid NOT NULL,
    node_id         text NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('ask', 'approval', 'elicitation')),
    tool_name       text NOT NULL,
    tool_label      text NOT NULL CHECK (char_length(tool_label) BETWEEN 1 AND 80),
    group_key       text NOT NULL,
    requested_by    uuid NOT NULL REFERENCES subject (id),
    status          text NOT NULL CHECK (status IN ('open', 'answered', 'approved', 'rejected', 'cancelled', 'expired')),
    answer_schema   jsonb,
    detail_key_id   text,
    detail_cipher   text,
    answer_key_id   text,
    answer_cipher   text,
    decided_by      uuid REFERENCES subject (id),
    decided_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    version         integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    FOREIGN KEY (workspace_id, loop_id) REFERENCES agent_loop (workspace_id, id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    -- Separation of duties: nobody approves a tool call of an agent working on their own request.
    CONSTRAINT agent_human_request_separation CHECK (kind <> 'approval' OR decided_by IS NULL
        OR decided_by <> requested_by),
    CONSTRAINT agent_human_request_decided CHECK ((decided_by IS NULL) = (decided_at IS NULL))
);
CREATE INDEX agent_human_request_open ON agent_human_request (workspace_id, group_key, created_at) WHERE status = 'open';
CREATE INDEX agent_human_request_loop ON agent_human_request (loop_id);
GRANT SELECT, INSERT, UPDATE ON agent_human_request TO ${app_role};
