CREATE TABLE stage_mail (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    run_id uuid NOT NULL,
    task_id text,
    node_id text,
    event_kind text NOT NULL,
    dedup_key text NOT NULL UNIQUE,
    recipient_subject_id uuid REFERENCES subject(id),
    template jsonb NOT NULL,
    values_json jsonb NOT NULL,
    status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','providerAccepted','failed','suppressed','blocked')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    failure_code text,
    FOREIGN KEY(workspace_id, run_id) REFERENCES workflow_run(workspace_id,id),
    FOREIGN KEY(workspace_id, task_id) REFERENCES task_context(workspace_id,engine_task_id)
);
CREATE INDEX stage_mail_pending ON stage_mail(next_attempt_at,id) WHERE status='queued';
CREATE INDEX stage_mail_run ON stage_mail(workspace_id,run_id,created_at);

CREATE TABLE task_sla_breach (
    workspace_id uuid NOT NULL,
    task_id text NOT NULL,
    run_id uuid NOT NULL,
    due_at timestamptz NOT NULL,
    detected_at timestamptz NOT NULL,
    PRIMARY KEY(workspace_id,task_id),
    FOREIGN KEY(workspace_id,task_id) REFERENCES task_context(workspace_id,engine_task_id),
    FOREIGN KEY(workspace_id,run_id) REFERENCES workflow_run(workspace_id,id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON stage_mail TO ${app_role};
GRANT SELECT, INSERT ON task_sla_breach TO ${app_role};

ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read') OR workflow_id IS NULL);
