-- CG-O05 (docs/build/S142): when each external worker last fetched jobs of a topic, for the job dashboard.
-- Written at most once a minute per worker and topic by fetchExternalJobs; never holds job data.
CREATE TABLE external_worker_seen (
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    topic        text NOT NULL CHECK (topic ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    worker_id    text NOT NULL CHECK (char_length(worker_id) BETWEEN 1 AND 100),
    last_seen_at timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, topic, worker_id)
);
CREATE INDEX external_worker_seen_recent ON external_worker_seen (workspace_id, last_seen_at DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON external_worker_seen TO ${app_role};
