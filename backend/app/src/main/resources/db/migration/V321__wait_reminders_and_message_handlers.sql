-- Reminders while waiting (docs/build/S139, CG-W06): one row per wait occurrence of a step with whileWaiting.remind.
-- Each reminder is one effect-journal send (node <step>__remind, occurrence = reminder number), so a retry or a second
-- pod never sends two copies. The row keeps no message text and no recipient; both are rendered at send time from the
-- pinned release and the request.
CREATE TABLE wait_reminder (
    id              uuid PRIMARY KEY,
    workspace_id    uuid NOT NULL,
    run_id          uuid NOT NULL,
    node_id         text NOT NULL,
    -- The current step's "since": a later visit of the same step is a new occurrence with its own row.
    wait_since      timestamptz NOT NULL,
    adapter_key     text NOT NULL,
    status          text NOT NULL CHECK (status IN ('waiting', 'sending', 'done', 'stopped', 'refused', 'failed')),
    every_seconds   bigint NOT NULL CHECK (every_seconds BETWEEN 3600 AND 2592000),
    max_times       integer NOT NULL CHECK (max_times BETWEEN 1 AND 10),
    -- Reminder numbers already used by earlier visits of this step (stable effect ids across visits).
    first_ordinal   integer NOT NULL DEFAULT 1 CHECK (first_ordinal >= 1),
    sent            integer NOT NULL DEFAULT 0 CHECK (sent >= 0),
    failures        integer NOT NULL DEFAULT 0 CHECK (failures >= 0),
    next_at         timestamptz NOT NULL,
    last_sent_at    timestamptz,
    last_outcome    text CHECK (last_outcome IS NULL OR length(last_outcome) <= 300),
    lease_owner     text,
    lease_until     timestamptz,
    fence           bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    ended_at        timestamptz,
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    UNIQUE (run_id, node_id, wait_since)
);
CREATE INDEX wait_reminder_due ON wait_reminder (next_at) WHERE status = 'waiting';
CREATE INDEX wait_reminder_run ON wait_reminder (workspace_id, run_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON wait_reminder TO ${app_role};

-- Message handlers (CG-E08): a handler's subscription is armed when the request starts and has no waiting step.
-- Merge note: new column with a CHECK on the shared message_subscription table (S16).
ALTER TABLE message_subscription ADD COLUMN kind text NOT NULL DEFAULT 'wait' CHECK (kind IN ('wait', 'handler'));
-- A side handler (runs alongside) may be started by several messages: its subscription stays open.
