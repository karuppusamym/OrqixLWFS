-- CG-M01 (docs/build/S135): tool catalog pins of registered MCP servers (connection kind mcpServer) and the drift the
-- runtime observed against them. A snapshot is taken by an administrator per workspace, because bindings (and so the
-- server behind a key) are workspace-scoped. It pins the inputSchema of every allow-listed tool by SHA-256 of its
-- canonical JSON; the runtime refuses a tool whose live schema differs (TOOL_SCHEMA_CHANGED) or that has no pin.
-- The server's own tool description is never stored: only the administrator-written one in the definition is offered.

CREATE TABLE mcp_tool_snapshot (
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    connection_key      text NOT NULL,
    definition_version  integer NOT NULL CHECK (definition_version >= 1),
    tool_name           text NOT NULL CHECK (char_length(tool_name) BETWEEN 1 AND 64),
    input_schema        jsonb NOT NULL CHECK (jsonb_typeof(input_schema) = 'object'),
    schema_sha256       char(64) NOT NULL CHECK (schema_sha256 ~ '^[0-9a-f]{64}$'),
    taken_by            uuid NOT NULL REFERENCES subject (id),
    taken_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_key, definition_version, tool_name)
);
GRANT SELECT, INSERT, DELETE ON mcp_tool_snapshot TO ${app_role};

-- One open row per (server, tool, kind, observed hash); repeated observations only raise the counter. Resolved when a
-- new snapshot pins the tool again.
CREATE TABLE mcp_tool_drift (
    id               uuid PRIMARY KEY,
    workspace_id     uuid NOT NULL REFERENCES workspace (id),
    connection_key   text NOT NULL,
    tool_name        text NOT NULL CHECK (char_length(tool_name) BETWEEN 1 AND 64),
    kind             text NOT NULL CHECK (kind IN ('changed', 'missing', 'unpinned')),
    pinned_sha256    char(64),
    observed_sha256  char(64),
    occurrences      integer NOT NULL DEFAULT 1 CHECK (occurrences >= 1),
    first_seen_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    resolved_at      timestamptz
);
CREATE UNIQUE INDEX mcp_tool_drift_open ON mcp_tool_drift (workspace_id, connection_key, tool_name, kind,
    coalesce(observed_sha256, '')) WHERE resolved_at IS NULL;
CREATE INDEX mcp_tool_drift_ws ON mcp_tool_drift (workspace_id, last_seen_at DESC);
GRANT SELECT, INSERT, UPDATE ON mcp_tool_drift TO ${app_role};
