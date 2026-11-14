-- S129 (CG-W01, CG-W03): one row per "Keep checking until..." step occurrence (kind check) or safety-net check of a
-- message wait (kind fallback). The poll scheduler in the worker role claims due rows with FOR UPDATE SKIP LOCKED,
-- takes a lease and raises the fence, performs the read-only check through the effect journal, then records the
-- outcome only while it still holds that fence. Full response bodies are never stored: only the HTTP status, an
-- outcome code, a digest of the mapped result and up to three shown values. The mapped request values (request) and
-- condition values (context) are cleared when the row ends.
CREATE TABLE poll_wait (
    id                     uuid PRIMARY KEY,
    workspace_id           uuid NOT NULL REFERENCES workspace (id),
    run_id                 uuid NOT NULL,
    node_id                text NOT NULL,
    occurrence             integer NOT NULL DEFAULT 1 CHECK (occurrence >= 1),
    kind                   text NOT NULL CHECK (kind IN ('check', 'fallback')),
    connection_key         text NOT NULL,
    connection_version     integer NOT NULL CHECK (connection_version >= 1),
    operation              text NOT NULL,
    timeout_seconds        integer NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 30),
    request                jsonb NOT NULL DEFAULT '{}'::jsonb,
    context                jsonb NOT NULL DEFAULT '{}'::jsonb,
    state                  text NOT NULL CHECK (state IN ('waiting', 'checking', 'met', 'failed', 'timedOut',
                                                          'cancelled', 'attention')),
    checks_done            integer NOT NULL DEFAULT 0 CHECK (checks_done >= 0),
    max_checks             integer NOT NULL CHECK (max_checks BETWEEN 1 AND 1000),
    consecutive_errors     integer NOT NULL DEFAULT 0 CHECK (consecutive_errors >= 0),
    started_at             timestamptz NOT NULL,
    next_check_at          timestamptz,
    deadline_at            timestamptz NOT NULL,
    authored_deadline_at   timestamptz NOT NULL,
    deadline_extensions    integer NOT NULL DEFAULT 0 CHECK (deadline_extensions >= 0),
    final_check_done       boolean NOT NULL DEFAULT false,
    last_checked_at        timestamptz,
    last_http_status       integer,
    last_outcome           text CHECK (last_outcome IS NULL OR length(last_outcome) <= 40),
    last_result_digest     char(64),
    shown                  jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_manual_check_at   timestamptz,
    lease_owner            text,
    lease_until            timestamptz,
    fence                  integer NOT NULL DEFAULT 0 CHECK (fence >= 0),
    version                integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    ended_at               timestamptz,
    end_reason             text CHECK (end_reason IS NULL OR length(end_reason) <= 60),
    created_at             timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    UNIQUE (workspace_id, run_id, node_id, occurrence),
    CONSTRAINT poll_wait_lease_complete CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT poll_wait_open_has_next CHECK (state NOT IN ('waiting', 'checking') OR next_check_at IS NOT NULL),
    CONSTRAINT poll_wait_deadline_order CHECK (deadline_at >= started_at AND authored_deadline_at >= started_at)
);
-- The scheduler's claim: open rows whose next check is due (or whose lease ran out while checking).
CREATE INDEX poll_wait_due ON poll_wait (next_check_at) WHERE state IN ('waiting', 'checking');
CREATE INDEX poll_wait_by_run ON poll_wait (workspace_id, run_id);

GRANT SELECT, INSERT, UPDATE ON poll_wait TO ${app_role};
