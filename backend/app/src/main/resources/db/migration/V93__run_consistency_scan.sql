-- EX09 periodic consistency scan (docs/build/S67): the last read-only comparison of an active request with the engine.
-- One row per scanned request. The scan only records what it found (never changes a request); a row whose state is
-- 'inSync' lets the scan skip that request until recheck time, so healthy waiting requests are not re-inspected on
-- every sweep. Rows go away with the request.
CREATE TABLE run_consistency_scan (
    workspace_id    uuid NOT NULL,
    run_id          uuid NOT NULL,
    state           text NOT NULL CHECK (state IN ('inSync', 'projectionDrift', 'strandedToken', 'emptyRequest',
                                                   'engineMissing', 'unsupported')),
    repairable      boolean NOT NULL,
    message         text NOT NULL CHECK (length(message) <= 1000),
    run_version     integer NOT NULL,
    first_seen_at   timestamptz NOT NULL,
    checked_at      timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, run_id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id) ON DELETE CASCADE
);
CREATE INDEX run_consistency_scan_checked ON run_consistency_scan (checked_at);
CREATE INDEX run_consistency_scan_findings ON run_consistency_scan (workspace_id, first_seen_at, run_id)
    WHERE state <> 'inSync';

GRANT SELECT, INSERT, UPDATE, DELETE ON run_consistency_scan TO ${app_role};
