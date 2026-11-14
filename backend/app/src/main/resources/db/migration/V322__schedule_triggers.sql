-- Scheduled starts (docs/build/S139, CG-E02): a schedule starts the active release of one workflow on a cron or RRULE
-- in a time zone, optionally only inside a working calendar's hours, with a fixed input, as the person who set it up.
CREATE TABLE schedule_trigger (
    id                uuid PRIMARY KEY,
    workspace_id      uuid NOT NULL REFERENCES workspace (id),
    workflow_id       uuid NOT NULL,
    name              text NOT NULL CHECK (length(name) BETWEEN 1 AND 80 AND name = btrim(name)),
    kind              text NOT NULL CHECK (kind IN ('cron', 'rrule')),
    expression        text NOT NULL CHECK (length(expression) BETWEEN 1 AND 300),
    zone              text NOT NULL CHECK (length(zone) BETWEEN 1 AND 64),
    starts_at         timestamptz NOT NULL,
    ends_at           timestamptz,
    calendar_key      text,
    calendar_version  integer CHECK (calendar_version IS NULL OR calendar_version >= 1),
    input             jsonb NOT NULL DEFAULT '{}'::jsonb,
    missed_policy     text NOT NULL CHECK (missed_policy IN ('catchUpOnce', 'skip')),
    overlap_policy    text NOT NULL CHECK (overlap_policy IN ('allow', 'skip')),
    enabled           boolean NOT NULL DEFAULT true,
    run_as            uuid NOT NULL REFERENCES subject (id),
    next_fire_at      timestamptz,
    last_fire_at      timestamptz,
    last_outcome      text CHECK (last_outcome IS NULL OR last_outcome IN ('started', 'skipped', 'failed')),
    last_run_id       uuid,
    version           integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_by        uuid NOT NULL REFERENCES subject (id),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    CHECK ((calendar_key IS NULL) = (calendar_version IS NULL)),
    UNIQUE (workspace_id, id)
);
CREATE INDEX schedule_trigger_due ON schedule_trigger (next_fire_at) WHERE enabled AND next_fire_at IS NOT NULL;
CREATE INDEX schedule_trigger_workflow ON schedule_trigger (workspace_id, workflow_id);

-- One row per planned start: the unique (schedule, fire time) makes a start happen at most once even when the
-- scheduler changes leader or retries; the run start itself also has an idempotency key.
CREATE TABLE schedule_fire (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL,
    schedule_id   uuid NOT NULL REFERENCES schedule_trigger (id) ON DELETE CASCADE,
    fire_at       timestamptz NOT NULL,
    fired_at      timestamptz NOT NULL,
    outcome       text NOT NULL CHECK (outcome IN ('started', 'skipped', 'failed')),
    reason        text CHECK (reason IS NULL OR length(reason) <= 300),
    missed        integer NOT NULL DEFAULT 0 CHECK (missed >= 0),
    run_id        uuid,
    UNIQUE (schedule_id, fire_at)
);
CREATE INDEX schedule_fire_recent ON schedule_fire (schedule_id, fire_at DESC);

-- Leader election for the scheduler: one row per job; the pod holding an unexpired lease runs it.
CREATE TABLE scheduler_lease (
    name         text PRIMARY KEY CHECK (length(name) BETWEEN 1 AND 64),
    owner        text,
    lease_until  timestamptz,
    fence        bigint NOT NULL DEFAULT 0
);
INSERT INTO scheduler_lease (name) VALUES ('schedules');

GRANT SELECT, INSERT, UPDATE, DELETE ON schedule_trigger TO ${app_role};
GRANT SELECT, INSERT, DELETE ON schedule_fire TO ${app_role};
GRANT SELECT, UPDATE ON scheduler_lease TO ${app_role};
