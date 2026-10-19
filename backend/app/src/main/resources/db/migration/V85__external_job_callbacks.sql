-- A durable handoff retains the existing fence and never redispatches work automatically on timeout.
CREATE TABLE external_job_callback (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    job_id uuid NOT NULL REFERENCES external_job(id),
    fence integer NOT NULL CHECK (fence > 0),
    worker_id text NOT NULL CHECK (length(worker_id) BETWEEN 1 AND 100),
    subject_id uuid NOT NULL REFERENCES subject(id),
    deadline timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (job_id, fence),
    CHECK (deadline > created_at AND deadline <= created_at + interval '86400 seconds')
);
GRANT SELECT, INSERT ON external_job_callback TO ${app_role};
