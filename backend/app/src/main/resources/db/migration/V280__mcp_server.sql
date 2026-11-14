-- CG-M04 (docs/build/S136): Orqix as an MCP server.
--
-- mcp_workspace_setting: the per-workspace switch. Unlike the S127 module switches (on until switched off), the MCP
-- server is OFF until a workspace administrator switches it on: no row means off. Read on every MCP call (no cache),
-- so switching off applies on every replica at the next call.
CREATE TABLE mcp_workspace_setting (
    workspace_id uuid        PRIMARY KEY REFERENCES workspace (id),
    enabled      boolean     NOT NULL,
    updated_by   uuid        NOT NULL REFERENCES subject (id),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON mcp_workspace_setting TO ${app_role};

-- mcp_confirmation: a prepared write (start a request, decide a task, send a message) waiting for the person to confirm
-- it, valid 5 minutes. The arguments are kept only until the action is confirmed, declined or expired (then set to
-- NULL) so the confirm call executes exactly what the summary showed; the result is kept so a repeated confirm returns
-- the same answer (idempotent). Rows are purged one day after they expire.
CREATE TABLE mcp_confirmation (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL REFERENCES workspace (id),
    subject_id   uuid        NOT NULL REFERENCES subject (id),
    tool         text        NOT NULL CHECK (tool ~ '^[a-z][a-z0-9_]{0,127}$'),
    action       text        NOT NULL CHECK (action IN ('startRequest', 'decideTask', 'sendMessage')),
    target_id    text,
    arguments    jsonb,
    summary      text        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('pending', 'confirmed', 'declined', 'expired', 'failed')),
    result       jsonb,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    decided_at   timestamptz,
    CONSTRAINT mcp_confirmation_open_has_arguments CHECK (status <> 'pending' OR arguments IS NOT NULL)
);
CREATE INDEX mcp_confirmation_by_subject ON mcp_confirmation (subject_id, created_at DESC);
CREATE INDEX mcp_confirmation_expiry ON mcp_confirmation (expires_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON mcp_confirmation TO ${app_role};

-- Recent MCP calls in Settings read the audit log of the workspace by action.
CREATE INDEX audit_mcp_calls ON audit_event (workspace_id, occurred_at DESC) WHERE action = 'mcp.tool-called';
