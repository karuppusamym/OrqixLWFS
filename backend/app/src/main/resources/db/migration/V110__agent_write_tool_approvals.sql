-- IN04 (docs/build/S75): approved write-capable agent tools.
-- A write tool is declared in an agent connection definition version (agentSpec.writeTools). Declaring it, or having the
-- adapter it uses, authorizes nothing: an agent may only use it while an active approval exists for exactly this
-- workflow key, connection key and version (definition hash), operation and tool name. Approvals are created and
-- revoked by people holding the grant-only, workspace-wide permission agent.approve-write-tool (no role carries it).

ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool') OR workflow_id IS NULL);

CREATE TABLE agent_write_tool_approval (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    workflow_key        text NOT NULL CHECK (workflow_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    connection_key      text NOT NULL CHECK (connection_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    connection_version  integer NOT NULL CHECK (connection_version >= 1),
    definition_hash     char(64) NOT NULL,
    operation           text NOT NULL CHECK (char_length(operation) BETWEEN 1 AND 100),
    tool_name           text NOT NULL CHECK (tool_name ~ '^[a-z][a-z0-9_]{0,47}$'),
    adapter_key         text NOT NULL,
    status              text NOT NULL CHECK (status IN ('active', 'revoked')),
    approved_by         uuid NOT NULL REFERENCES subject (id),
    approved_at         timestamptz NOT NULL,
    approval_reason     text NOT NULL CHECK (char_length(approval_reason) BETWEEN 1 AND 500),
    revoked_by          uuid REFERENCES subject (id),
    revoked_at          timestamptz,
    revoke_reason       text CHECK (revoke_reason IS NULL OR char_length(revoke_reason) BETWEEN 1 AND 500),
    version             integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, connection_key, connection_version)
        REFERENCES connection_definition (workspace_id, key, version),
    CONSTRAINT agent_write_tool_approval_revocation CHECK (
        (status = 'revoked') = (revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);
-- At most one active approval per workflow/connection version/operation/tool.
CREATE UNIQUE INDEX agent_write_tool_approval_one_active
    ON agent_write_tool_approval (workspace_id, workflow_key, connection_key, connection_version, operation, tool_name)
    WHERE status = 'active';
CREATE INDEX agent_write_tool_approval_ws ON agent_write_tool_approval (workspace_id, approved_at DESC);
GRANT SELECT, INSERT, UPDATE ON agent_write_tool_approval TO ${app_role};
