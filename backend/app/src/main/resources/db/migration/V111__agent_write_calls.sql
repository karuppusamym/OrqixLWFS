-- IN04 (docs/build/S75): one row per write-tool use slot of an agent execution. The outcome lives in effect_journal
-- (state succeeded / permanentFailure / unknown / notApplied), linked by the stable effect key
-- sha256(workspace|run|agent node:write:tool:n|occurrence). This row holds who approved and who requested, a digest and
-- size of the model's arguments (never the arguments), and the operator reconciliation of an unknown outcome
-- (version-checked, with a reason). The row is written before the effect is registered, so an attempt that ends without
-- an outcome can always be found and reconciled.

CREATE TABLE agent_write_call (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    run_id              uuid NOT NULL,
    agent_effect_id     uuid NOT NULL,
    node_id             text NOT NULL,
    effect_key          text NOT NULL UNIQUE,
    connection_key      text NOT NULL,
    connection_version  integer NOT NULL,
    operation           text NOT NULL,
    tool_name           text NOT NULL,
    call_index          integer NOT NULL CHECK (call_index BETWEEN 1 AND 3),
    adapter_key         text NOT NULL,
    kind                text NOT NULL CHECK (kind IN ('email', 'teams')),
    approval_id         uuid NOT NULL,
    approved_by         uuid NOT NULL REFERENCES subject (id),
    requested_by        uuid NOT NULL REFERENCES subject (id),
    argument_sha256     char(64) NOT NULL,
    argument_chars      integer NOT NULL CHECK (argument_chars >= 0),
    version             integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    reconciled_outcome  text CHECK (reconciled_outcome IN ('succeeded', 'notApplied')),
    reconciled_by       uuid REFERENCES subject (id),
    reconciled_at       timestamptz,
    reconcile_reason    text CHECK (reconcile_reason IS NULL OR char_length(reconcile_reason) BETWEEN 1 AND 500),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (agent_effect_id, tool_name, call_index),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    FOREIGN KEY (workspace_id, agent_effect_id) REFERENCES effect_journal (workspace_id, id),
    FOREIGN KEY (workspace_id, approval_id) REFERENCES agent_write_tool_approval (workspace_id, id),
    -- Separation of duties: the person who approved the tool never triggers a write through it.
    CONSTRAINT agent_write_call_separation CHECK (approved_by <> requested_by),
    CONSTRAINT agent_write_call_reconciled CHECK (
        (reconciled_outcome IS NULL) = (reconciled_by IS NULL)
        AND (reconciled_outcome IS NULL) = (reconciled_at IS NULL)
        AND (reconciled_outcome IS NULL) = (reconcile_reason IS NULL))
);
CREATE INDEX agent_write_call_ws ON agent_write_call (workspace_id, created_at DESC);
CREATE INDEX agent_write_call_run ON agent_write_call (workspace_id, run_id);
GRANT SELECT, INSERT, UPDATE ON agent_write_call TO ${app_role};
