-- S129 (CG-W08): what a request is waiting for, one row per open wait, uniform across wait kinds (approval, timer,
-- message, callback, poll, external job, child request, signal). Kept in step with the run's current steps in the
-- same transaction as every run projection change, and enriched by the poll scheduler (next check, checks made).
-- A row is deleted when its wait ends. It holds plain-language subjects and references, never request data.
CREATE TABLE wait_state (
    workspace_id     uuid NOT NULL REFERENCES workspace (id),
    run_id           uuid NOT NULL,
    node_id          text NOT NULL,
    kind             text NOT NULL CHECK (kind IN ('approval', 'timer', 'message', 'callback', 'poll', 'externalJob',
                                                   'childRequest', 'signal')),
    label            text NOT NULL CHECK (length(label) <= 200),
    subject          text NOT NULL CHECK (length(subject) <= 300),
    since            timestamptz NOT NULL,
    next_check_at    timestamptz,
    due_at           timestamptz,
    deadline_at      timestamptz,
    attempts         integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    correlation_ref  text CHECK (correlation_ref IS NULL OR length(correlation_ref) <= 200),
    connection_key   text,
    poll_wait_id     uuid REFERENCES poll_wait (id),
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, run_id, node_id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id)
);
-- Runs filter "waiting on connection X for longer than Y" and Insights "time spent waiting on outside systems".
CREATE INDEX wait_state_by_connection ON wait_state (workspace_id, connection_key, since) WHERE connection_key IS NOT NULL;
CREATE INDEX wait_state_by_kind ON wait_state (workspace_id, kind, since);

GRANT SELECT, INSERT, UPDATE, DELETE ON wait_state TO ${app_role};
