-- Message events (docs/build/S16): a request that waits for a named message is a row in message_subscription;
-- a message sent through the API is a row in inbound_message (kept only while it may still be consumed).

-- The new workspace-wide permission message.send can be granted like connection.manage.
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start', 'connection.manage', 'message.send'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage', 'message.send') OR workflow_id IS NULL);

CREATE TABLE inbound_message (
    id               uuid PRIMARY KEY,
    workspace_id     uuid NOT NULL REFERENCES workspace (id),
    name             text NOT NULL CHECK (name ~ '^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$' AND length(name) BETWEEN 2 AND 64),
    correlation_key  text NOT NULL CHECK (length(correlation_key) BETWEEN 1 AND 200),
    -- Declared scalar values only; emptied as soon as the message is delivered, expired or discarded.
    payload          jsonb NOT NULL DEFAULT '{}'::jsonb,
    status           text NOT NULL CHECK (status IN ('buffered', 'delivered', 'expired', 'discarded')),
    accepted_response jsonb,
    matched          integer NOT NULL DEFAULT 0 CHECK (matched >= 0),
    sender_subject_id uuid REFERENCES subject (id),
    received_at      timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    closed_at        timestamptz
);
-- Buffered messages waiting for a wait to start, looked up by (workspace, name, key), oldest first.
CREATE INDEX inbound_message_buffered ON inbound_message (workspace_id, name, correlation_key, received_at, id)
    WHERE status = 'buffered';
CREATE INDEX inbound_message_expiry ON inbound_message (expires_at) WHERE status = 'buffered';
CREATE INDEX inbound_message_closed ON inbound_message (closed_at) WHERE status <> 'buffered';

CREATE TABLE message_subscription (
    id                          uuid PRIMARY KEY,
    workspace_id                uuid NOT NULL,
    run_id                      uuid NOT NULL,
    node_id                     text NOT NULL,
    message_name                text NOT NULL,
    correlation_key             text NOT NULL CHECK (length(correlation_key) BETWEEN 1 AND 200),
    engine_execution_id         text NOT NULL,
    status                      text NOT NULL CHECK (status IN ('waiting', 'received', 'timed_out', 'cancelled')),
    message_id                  uuid REFERENCES inbound_message (id) ON DELETE SET NULL,
    created_at                  timestamptz NOT NULL,
    expires_at                  timestamptz,
    closed_at                   timestamptz,
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    UNIQUE (run_id, node_id)
);
CREATE INDEX message_subscription_waiting ON message_subscription (workspace_id, message_name, correlation_key, created_at, id)
    WHERE status = 'waiting';
CREATE INDEX message_subscription_by_message ON message_subscription (message_id) WHERE message_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON inbound_message, message_subscription TO ${app_role};

-- Preserve replay snapshots while their idempotency keys remain live.
CREATE INDEX message_idempotency_resource ON idempotency_record(resource_id,expires_at) WHERE resource_type='message';
