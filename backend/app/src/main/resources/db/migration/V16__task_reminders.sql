CREATE TABLE task_reminder (
    workspace_id uuid NOT NULL,
    task_id text NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    delivered_at timestamptz,
    PRIMARY KEY(workspace_id,task_id),
    FOREIGN KEY(workspace_id,task_id) REFERENCES task_context(workspace_id,engine_task_id)
);
CREATE INDEX task_reminder_due ON task_reminder(next_attempt_at) WHERE delivered_at IS NULL;
GRANT SELECT,INSERT,UPDATE,DELETE ON task_reminder TO ${app_role};
