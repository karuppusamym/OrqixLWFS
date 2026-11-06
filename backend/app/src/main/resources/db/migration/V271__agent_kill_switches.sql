-- CG-M03 (docs/build/S135): per-agent and per-server kill switches. An active switch on an agent connection stops new
-- executions and the next turn of running loops; on an MCP server connection it refuses every call to its tools.
-- Switching is a connection.manage action with a reason, audited; the row keeps the latest state and a version.

CREATE TABLE agent_kill_switch (
    workspace_id    uuid NOT NULL REFERENCES workspace (id),
    scope           text NOT NULL CHECK (scope IN ('agent', 'server')),
    connection_key  text NOT NULL CHECK (connection_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    active          boolean NOT NULL,
    reason          text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    changed_by      uuid NOT NULL REFERENCES subject (id),
    changed_at      timestamptz NOT NULL DEFAULT now(),
    version         integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    PRIMARY KEY (workspace_id, scope, connection_key)
);
GRANT SELECT, INSERT, UPDATE ON agent_kill_switch TO ${app_role};
