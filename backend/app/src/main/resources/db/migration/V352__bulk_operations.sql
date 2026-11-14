-- CG-O01 (docs/build/S142): bulk operations by filter. A dry run stores the exact matching runs (at most the cap);
-- execution acts only on those items, after the person who ran the dry run confirmed their count and gave a reason.
-- Each item re-checks the filter when it runs, so the set can only shrink, never grow (doc 29 section 4 row 12).
CREATE TABLE bulk_operation (
    id                 uuid PRIMARY KEY,
    workspace_id       uuid NOT NULL REFERENCES workspace (id),
    action             text NOT NULL CHECK (action IN ('retry', 'cancel', 'suspend', 'resume', 'migrate')),
    filter             jsonb NOT NULL CHECK (jsonb_typeof(filter) = 'object'),
    target_release_id  uuid,
    state              text NOT NULL CHECK (state IN ('previewed', 'running', 'paused', 'completed', 'cancelled')),
    matched_count      integer NOT NULL CHECK (matched_count >= 0),
    item_cap           integer NOT NULL CHECK (item_cap BETWEEN 1 AND 10000),
    rate_per_minute    integer NOT NULL CHECK (rate_per_minute BETWEEN 1 AND 600),
    reason             text CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 500),
    stop_reason        text CHECK (stop_reason IS NULL OR char_length(stop_reason) <= 500),
    created_by         uuid NOT NULL REFERENCES subject (id),
    created_at         timestamptz NOT NULL,
    preview_expires_at timestamptz NOT NULL,
    started_at         timestamptz,
    finished_at        timestamptz,
    succeeded          integer NOT NULL DEFAULT 0 CHECK (succeeded >= 0),
    failed             integer NOT NULL DEFAULT 0 CHECK (failed >= 0),
    skipped            integer NOT NULL DEFAULT 0 CHECK (skipped >= 0),
    cancelled_items    integer NOT NULL DEFAULT 0 CHECK (cancelled_items >= 0),
    next_item_at       timestamptz,
    lease_owner        text CHECK (lease_owner IS NULL OR char_length(lease_owner) <= 200),
    lease_until        timestamptz,
    version            integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (workspace_id, id),
    CONSTRAINT bulk_operation_migrate_target CHECK ((action = 'migrate') = (target_release_id IS NOT NULL)),
    CONSTRAINT bulk_operation_reason_when_started CHECK (started_at IS NULL OR reason IS NOT NULL),
    CONSTRAINT bulk_operation_finished CHECK ((state IN ('completed', 'cancelled')) = (finished_at IS NOT NULL))
);
CREATE INDEX bulk_operation_ws_created ON bulk_operation (workspace_id, created_at DESC, id DESC);
CREATE INDEX bulk_operation_due ON bulk_operation (next_item_at) WHERE state = 'running';
GRANT SELECT, INSERT, UPDATE ON bulk_operation TO ${app_role};

CREATE TABLE bulk_operation_item (
    operation_id       uuid NOT NULL,
    workspace_id       uuid NOT NULL,
    run_id             uuid NOT NULL,
    position           integer NOT NULL CHECK (position >= 1),
    reference          text NOT NULL,
    version_at_preview integer NOT NULL,
    state              text NOT NULL DEFAULT 'pending'
                       CHECK (state IN ('pending', 'succeeded', 'failed', 'skipped', 'cancelled')),
    attempts           integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    error_code         text CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    message            text CHECK (message IS NULL OR char_length(message) <= 300),
    finished_at        timestamptz,
    PRIMARY KEY (operation_id, run_id),
    UNIQUE (operation_id, position),
    FOREIGN KEY (workspace_id, operation_id) REFERENCES bulk_operation (workspace_id, id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id)
);
CREATE INDEX bulk_operation_item_pending ON bulk_operation_item (operation_id, position) WHERE state = 'pending';
GRANT SELECT, INSERT, UPDATE ON bulk_operation_item TO ${app_role};
