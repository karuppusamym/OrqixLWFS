-- Request attachments and case stages (docs/build/S17).
--
-- run_attachment: metadata of a file. run_id is NULL while the file is "staged": uploaded from the request form before
-- the request exists, owned by the uploader and removed by the cleanup job after expires_at. Starting a request
-- attaches the staged files its form refers to. The bytes live in run_attachment_content so that metadata queries
-- never read them; removing a file deletes its content row and keeps the metadata (soft delete, audited).
CREATE TABLE run_attachment (
    id               uuid PRIMARY KEY,
    workspace_id     uuid NOT NULL REFERENCES workspace(id),
    run_id           uuid,
    field_key        text CHECK (field_key IS NULL OR length(field_key) BETWEEN 1 AND 64),
    uploaded_by      uuid NOT NULL REFERENCES subject(id),
    file_name        text NOT NULL CHECK (length(file_name) BETWEEN 1 AND 120),
    description      text CHECK (description IS NULL OR length(description) <= 200),
    content_type     text NOT NULL CHECK (length(content_type) BETWEEN 3 AND 127),
    size_bytes       bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256           text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    scan_status      text NOT NULL CHECK (scan_status IN ('notScanned', 'clean', 'infected', 'error')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz,
    deleted_at       timestamptz,
    deleted_by       uuid REFERENCES subject(id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run(workspace_id, id),
    CONSTRAINT run_attachment_staged CHECK ((run_id IS NULL) = (expires_at IS NOT NULL)),
    CONSTRAINT run_attachment_deleted CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
);
CREATE INDEX run_attachment_run ON run_attachment(workspace_id, run_id, created_at, id) WHERE run_id IS NOT NULL;
CREATE INDEX run_attachment_staged_owner ON run_attachment(workspace_id, uploaded_by) WHERE run_id IS NULL AND deleted_at IS NULL;
CREATE INDEX run_attachment_staged_expiry ON run_attachment(expires_at) WHERE run_id IS NULL AND deleted_at IS NULL;
CREATE INDEX run_attachment_rate ON run_attachment(uploaded_by, created_at DESC);

CREATE TABLE run_attachment_content (
    attachment_id uuid PRIMARY KEY REFERENCES run_attachment(id),
    content       bytea NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON run_attachment TO ${app_role};
GRANT SELECT, INSERT, DELETE ON run_attachment_content TO ${app_role};
GRANT DELETE ON run_attachment TO ${app_role};

-- run_stage: one row per stage a request has entered (derived from its current steps, see S17 section 4). due_at is
-- entered_at + the stage's time limit; overdue_at records that STAGE_OVERDUE was emitted for the current visit (cycle),
-- which makes the event and its notifications happen once even with several executors.
CREATE TABLE run_stage (
    workspace_id  uuid NOT NULL,
    run_id        uuid NOT NULL,
    stage_id      text NOT NULL,
    cycle         integer NOT NULL DEFAULT 1,
    entered_at    timestamptz NOT NULL,
    due_at        timestamptz,
    completed_at  timestamptz,
    overdue_at    timestamptz,
    PRIMARY KEY (run_id, stage_id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run(workspace_id, id)
);
CREATE INDEX run_stage_due ON run_stage(due_at) WHERE completed_at IS NULL AND overdue_at IS NULL AND due_at IS NOT NULL;
CREATE INDEX run_stage_overdue_run ON run_stage(workspace_id, run_id) WHERE completed_at IS NULL AND due_at IS NOT NULL;
GRANT SELECT, INSERT, UPDATE ON run_stage TO ${app_role};
