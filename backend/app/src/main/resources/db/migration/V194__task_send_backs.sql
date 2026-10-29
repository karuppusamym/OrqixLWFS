-- UI02 / S116: a task holder returns a request to its requester for the fields the step declares correctable. At most
-- one send-back is open per task; while it is open the task is neither decided nor corrected by the holder. The row is
-- never deleted, and only its closing columns change.
CREATE TABLE task_send_back (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    run_id uuid NOT NULL,
    task_id text NOT NULL,
    node_id text NOT NULL,
    requester_id uuid NOT NULL REFERENCES subject (id),
    fields jsonb NOT NULL CHECK (jsonb_typeof(fields) = 'array' AND jsonb_array_length(fields) BETWEEN 1 AND 10),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    status text NOT NULL CHECK (status IN ('open', 'answered', 'withdrawn')),
    sent_by uuid NOT NULL REFERENCES subject (id),
    sent_at timestamptz NOT NULL DEFAULT now(),
    closed_by uuid REFERENCES subject (id),
    closed_at timestamptz,
    note text CHECK (note IS NULL OR length(note) <= 500),
    CHECK ((status = 'open') = (closed_at IS NULL))
);
CREATE UNIQUE INDEX task_send_back_one_open ON task_send_back (workspace_id, task_id) WHERE status = 'open';
CREATE INDEX task_send_back_run ON task_send_back (workspace_id, run_id, sent_at DESC);
GRANT SELECT, INSERT ON task_send_back TO ${app_role};
GRANT UPDATE (status, closed_by, closed_at, note) ON task_send_back TO ${app_role};
