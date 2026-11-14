-- Analytics read model (docs/build/S145, doc 29 section 9.7, D-CG6): reporting tables fed from the run timeline through
-- an outbox, so heatmaps (CG-N01), KPI targets (CG-N03) and human work analytics (CG-N05) never aggregate the
-- operational tables. Fact tables have no foreign keys to runs on purpose: run retention (CG-O07) may purge runs, and
-- the projector then removes their facts when it sees the run is gone.

-- One row per timeline event that changes what the read model shows, written in the transaction of the event (so it
-- is never lost and never present for a rolled-back change). The projector claims rows per run, projects that run
-- again from its timeline (idempotent) and deletes exactly the rows it claimed. Backfill inserts one row per run.
CREATE TABLE analytics_outbox (
    id               bigserial PRIMARY KEY,
    workspace_id     uuid NOT NULL,
    run_id           uuid NOT NULL,
    reason           text NOT NULL CHECK (reason IN ('event', 'backfill')),
    event_type       text CHECK (event_type IS NULL OR length(event_type) <= 64),
    created_at       timestamptz NOT NULL,
    attempts         integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at  timestamptz NOT NULL,
    last_error       text CHECK (last_error IS NULL OR length(last_error) <= 300),
    dead             boolean NOT NULL DEFAULT false
);
CREATE INDEX analytics_outbox_due ON analytics_outbox (next_attempt_at, id) WHERE NOT dead;
CREATE INDEX analytics_outbox_run ON analytics_outbox (run_id);
CREATE INDEX analytics_outbox_workspace ON analytics_outbox (workspace_id) WHERE NOT dead;

-- One row per run: when it started and ended, its cycle time and whether people worked on it.
CREATE TABLE analytics_run (
    run_id             uuid PRIMARY KEY,
    workspace_id       uuid NOT NULL,
    workflow_id        uuid NOT NULL,
    release_id         uuid NOT NULL,
    lifecycle          text NOT NULL CHECK (length(lifecycle) BETWEEN 1 AND 32),
    outcome_kind       text CHECK (outcome_kind IS NULL OR length(outcome_kind) <= 32),
    started_at         timestamptz NOT NULL,
    ended_at           timestamptz,
    cycle_seconds      double precision CHECK (cycle_seconds IS NULL OR cycle_seconds >= 0),
    human_tasks        integer NOT NULL DEFAULT 0 CHECK (human_tasks >= 0),
    automated          boolean NOT NULL DEFAULT false,
    history_truncated  boolean NOT NULL DEFAULT false,
    projected_seq      bigint NOT NULL DEFAULT 0,
    projected_at       timestamptz NOT NULL
);
CREATE INDEX analytics_run_started ON analytics_run (workspace_id, workflow_id, started_at);
CREATE INDEX analytics_run_ended ON analytics_run (workspace_id, workflow_id, ended_at) WHERE ended_at IS NOT NULL;
CREATE INDEX analytics_run_projected ON analytics_run (workspace_id, projected_at DESC);

-- One row per finished visit of a step (a step visited twice has two rows), keyed by the run's start time so a
-- heatmap for a release and a period reads one index range.
CREATE TABLE analytics_step_visit (
    run_id            uuid NOT NULL,
    ordinal           integer NOT NULL CHECK (ordinal >= 0),
    workspace_id      uuid NOT NULL,
    workflow_id       uuid NOT NULL,
    release_id        uuid NOT NULL,
    run_started_at    timestamptz NOT NULL,
    node_id           text NOT NULL CHECK (length(node_id) BETWEEN 1 AND 200),
    kind              text NOT NULL CHECK (length(kind) BETWEEN 1 AND 64),
    entered_at        timestamptz NOT NULL,
    left_at           timestamptz NOT NULL,
    duration_seconds  double precision NOT NULL CHECK (duration_seconds >= 0),
    outcome           text CHECK (outcome IS NULL OR length(outcome) <= 64),
    PRIMARY KEY (run_id, ordinal)
);
CREATE INDEX analytics_step_visit_window ON analytics_step_visit (workspace_id, workflow_id, run_started_at);

-- One row per connection a run used (a connection used again in a loop is still one row).
CREATE TABLE analytics_route (
    run_id          uuid NOT NULL,
    transition_id   text NOT NULL CHECK (length(transition_id) BETWEEN 1 AND 300),
    workspace_id    uuid NOT NULL,
    workflow_id     uuid NOT NULL,
    release_id      uuid NOT NULL,
    run_started_at  timestamptz NOT NULL,
    PRIMARY KEY (run_id, transition_id)
);
CREATE INDEX analytics_route_window ON analytics_route (workspace_id, workflow_id, run_started_at);

-- One row per human task: waiting (nobody assigned) versus working (assigned) time, reassignments and SLA result.
CREATE TABLE analytics_task (
    task_id          text PRIMARY KEY CHECK (length(task_id) BETWEEN 1 AND 200),
    run_id           uuid NOT NULL,
    workspace_id     uuid NOT NULL,
    workflow_id      uuid NOT NULL,
    release_id       uuid NOT NULL,
    node_id          text NOT NULL CHECK (length(node_id) BETWEEN 1 AND 200),
    label            text NOT NULL CHECK (length(label) BETWEEN 1 AND 300),
    group_key        text NOT NULL CHECK (length(group_key) BETWEEN 1 AND 200),
    status           text NOT NULL CHECK (status IN ('open', 'completed', 'cancelled')),
    outcome          text CHECK (outcome IS NULL OR outcome IN ('decided', 'timedOut', 'cancelled')),
    assigned         boolean NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL,
    ended_at         timestamptz,
    due_at           timestamptz,
    waiting_seconds  double precision CHECK (waiting_seconds IS NULL OR waiting_seconds >= 0),
    working_seconds  double precision CHECK (working_seconds IS NULL OR working_seconds >= 0),
    claims           integer NOT NULL DEFAULT 0 CHECK (claims >= 0),
    releases         integer NOT NULL DEFAULT 0 CHECK (releases >= 0),
    reassignments    integer NOT NULL DEFAULT 0 CHECK (reassignments >= 0),
    sla              text CHECK (sla IS NULL OR sla IN ('met', 'missed')),
    approximate      boolean NOT NULL DEFAULT false,
    CHECK ((status = 'open') = (ended_at IS NULL)),
    CHECK ((status = 'open') = (outcome IS NULL))
);
CREATE INDEX analytics_task_run ON analytics_task (run_id);
CREATE INDEX analytics_task_ended ON analytics_task (workspace_id, ended_at) WHERE ended_at IS NOT NULL;
CREATE INDEX analytics_task_open ON analytics_task (workspace_id, group_key) WHERE status = 'open';

-- KPI targets per workflow (CG-N03). Every target is optional; delay cost turns time over target into business value.
CREATE TABLE analytics_kpi_target (
    workspace_id          uuid NOT NULL,
    workflow_id           uuid NOT NULL,
    cycle_p50_seconds     bigint CHECK (cycle_p50_seconds IS NULL OR cycle_p50_seconds BETWEEN 1 AND 31622400),
    cycle_p95_seconds     bigint CHECK (cycle_p95_seconds IS NULL OR cycle_p95_seconds BETWEEN 1 AND 31622400),
    automation_rate       numeric(5, 4) CHECK (automation_rate IS NULL OR automation_rate BETWEEN 0 AND 1),
    sla_attainment        numeric(5, 4) CHECK (sla_attainment IS NULL OR sla_attainment BETWEEN 0 AND 1),
    delay_cost_per_hour   numeric(14, 2) CHECK (delay_cost_per_hour IS NULL OR delay_cost_per_hour >= 0),
    currency              text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    version               integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by            uuid NOT NULL REFERENCES subject (id),
    updated_at            timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, workflow_id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    CHECK (cycle_p50_seconds IS NULL OR cycle_p95_seconds IS NULL OR cycle_p50_seconds <= cycle_p95_seconds),
    CHECK ((delay_cost_per_hour IS NULL) OR (currency IS NOT NULL))
);

-- Last evaluated state per workflow and measure, so the alert hooks hear only about changes.
CREATE TABLE analytics_kpi_state (
    workspace_id  uuid NOT NULL,
    workflow_id   uuid NOT NULL,
    measure       text NOT NULL CHECK (measure IN ('cycleP50', 'cycleP95', 'automationRate', 'slaAttainment')),
    status        text NOT NULL CHECK (status IN ('onTarget', 'offTarget', 'insufficientData')),
    actual        double precision,
    target        double precision NOT NULL,
    samples       integer NOT NULL CHECK (samples >= 0),
    evaluated_at  timestamptz NOT NULL,
    changed_at    timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, workflow_id, measure),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES analytics_kpi_target (workspace_id, workflow_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, UPDATE, DELETE ON analytics_outbox TO ${app_role};
GRANT USAGE, SELECT ON SEQUENCE analytics_outbox_id_seq TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON analytics_run, analytics_step_visit, analytics_route, analytics_task TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON analytics_kpi_target, analytics_kpi_state TO ${app_role};
