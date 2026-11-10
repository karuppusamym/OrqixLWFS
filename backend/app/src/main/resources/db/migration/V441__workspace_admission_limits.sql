-- CG-P05 (docs/build/S150): admission control per workspace. The deployment sets default limits for new requests and
-- inbound messages per minute (orqix.admission.*); a platform administrator may override them for one workspace.
-- NULL keeps the deployment default for that kind; 0 means no limit for this workspace.
CREATE TABLE workspace_admission_limit (
    workspace_id         uuid PRIMARY KEY REFERENCES workspace (id) ON DELETE CASCADE,
    starts_per_minute    integer CHECK (starts_per_minute IS NULL OR starts_per_minute BETWEEN 0 AND 100000),
    messages_per_minute  integer CHECK (messages_per_minute IS NULL OR messages_per_minute BETWEEN 0 AND 100000),
    revision             integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
    updated_by           uuid NOT NULL REFERENCES subject (id),
    updated_at           timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON workspace_admission_limit TO ${app_role};

-- The message limit counts the messages a workspace received in the last minute; no existing index starts with
-- (workspace_id, received_at) for all statuses. Starts are counted on workflow_run_ws_created (V1).
CREATE INDEX inbound_message_ws_received ON inbound_message (workspace_id, received_at);
