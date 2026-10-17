-- EX01: jobs for external workers (docs/build/S43). A step of kind externalTask publishes one job; a worker with the
-- workspace-wide permission job.work leases it, does the work elsewhere and completes it. Each lease carries a fence that
-- grows with every lock, so a worker whose lease was lost can never change the job again.
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start', 'connection.manage', 'message.send', 'document.browse', 'job.work'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage', 'message.send', 'document.browse', 'job.work') OR workflow_id IS NULL);

CREATE TABLE external_job (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    run_id uuid NOT NULL REFERENCES workflow_run(id),
    node_id text NOT NULL,
    topic text NOT NULL CHECK (topic ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    -- The mapped values the worker receives; cleared once the job ends so request data is not kept here.
    input jsonb NOT NULL CHECK (jsonb_typeof(input) = 'object'),
    status text NOT NULL CHECK (status IN ('available', 'locked', 'completed', 'failed', 'cancelled')),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    lease_seconds integer NOT NULL CHECK (lease_seconds BETWEEN 30 AND 3600),
    retry_delay_seconds integer NOT NULL CHECK (retry_delay_seconds >= 0),
    fence integer NOT NULL DEFAULT 0 CHECK (fence >= 0),
    worker_id text CHECK (worker_id IS NULL OR length(worker_id) BETWEEN 1 AND 100),
    lease_expires_at timestamptz,
    available_at timestamptz NOT NULL,
    last_error text CHECK (last_error IS NULL OR length(last_error) <= 500),
    created_at timestamptz NOT NULL,
    ended_at timestamptz,
    CONSTRAINT external_job_locked_has_lease CHECK (status <> 'locked' OR (worker_id IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT external_job_ended CHECK ((status IN ('completed', 'failed', 'cancelled')) = (ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX external_job_one_open_per_step ON external_job (run_id, node_id) WHERE status IN ('available', 'locked');
CREATE INDEX external_job_queue ON external_job (workspace_id, topic, available_at) WHERE status = 'available';
CREATE INDEX external_job_leases ON external_job (lease_expires_at) WHERE status = 'locked';
GRANT SELECT, INSERT, UPDATE ON external_job TO ${app_role};
