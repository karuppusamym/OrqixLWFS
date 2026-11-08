-- S143 (CG-O02, CG-O03): batches of mapped release migrations and guarded moves of a request between steps.

-- A batch moves the listed requests of one release to another with the same step mapping. Items are processed one by
-- one in their own transactions; each keeps its own result and refusal reasons.
CREATE TABLE run_migration_batch (
    id                 uuid PRIMARY KEY,
    workspace_id       uuid NOT NULL REFERENCES workspace (id),
    workflow_id        uuid NOT NULL,
    source_release_id  uuid NOT NULL REFERENCES workflow_release (id),
    target_release_id  uuid NOT NULL REFERENCES workflow_release (id),
    mappings           jsonb NOT NULL DEFAULT '[]'::jsonb,
    reason             text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    suspend_during     boolean NOT NULL DEFAULT false,
    state              text NOT NULL CHECK (state IN ('running', 'completed')),
    item_count         integer NOT NULL CHECK (item_count BETWEEN 1 AND 100),
    migrated_count     integer NOT NULL DEFAULT 0 CHECK (migrated_count >= 0),
    refused_count      integer NOT NULL DEFAULT 0 CHECK (refused_count >= 0),
    failed_count       integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    created_by         uuid NOT NULL REFERENCES subject (id),
    created_at         timestamptz NOT NULL,
    finished_at        timestamptz,
    CONSTRAINT run_migration_batch_releases_differ CHECK (source_release_id <> target_release_id)
);
CREATE INDEX run_migration_batch_by_workspace ON run_migration_batch (workspace_id, created_at DESC);

CREATE TABLE run_migration_batch_item (
    batch_id      uuid NOT NULL REFERENCES run_migration_batch (id),
    run_id        uuid NOT NULL,
    workspace_id  uuid NOT NULL,
    position      integer NOT NULL CHECK (position >= 0),
    state         text NOT NULL CHECK (state IN ('pending', 'migrated', 'refused', 'failed')),
    findings      jsonb NOT NULL DEFAULT '[]'::jsonb,
    message       text CHECK (message IS NULL OR length(message) <= 500),
    finished_at   timestamptz,
    PRIMARY KEY (batch_id, run_id),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id)
);
CREATE INDEX run_migration_batch_item_by_run ON run_migration_batch_item (workspace_id, run_id);

-- A guarded move (move, skip or re-run). In a production or regulated workspace it waits for a second operator; the
-- request version it was planned against must still hold when it runs.
CREATE TABLE run_move (
    id                 uuid PRIMARY KEY,
    workspace_id       uuid NOT NULL REFERENCES workspace (id),
    run_id             uuid NOT NULL,
    kind               text NOT NULL CHECK (kind IN ('move', 'skip', 'rerun')),
    from_node_id       text NOT NULL CHECK (length(from_node_id) BETWEEN 1 AND 200),
    to_node_id         text NOT NULL CHECK (length(to_node_id) BETWEEN 1 AND 200),
    reason             text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    expected_version   integer NOT NULL CHECK (expected_version >= 1),
    plan               jsonb NOT NULL,
    approval_required  boolean NOT NULL,
    state              text NOT NULL CHECK (state IN ('pendingApproval', 'executed', 'rejected', 'withdrawn', 'stale',
                                                      'expired')),
    requested_by       uuid NOT NULL REFERENCES subject (id),
    requested_at       timestamptz NOT NULL,
    expires_at         timestamptz,
    decided_by         uuid REFERENCES subject (id),
    decided_at         timestamptz,
    decision_note      text CHECK (decision_note IS NULL OR length(decision_note) <= 500),
    executed_at        timestamptz,
    version            integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    CONSTRAINT run_move_second_person CHECK (decided_by IS NULL OR NOT approval_required OR decided_by <> requested_by
                                             OR state IN ('withdrawn', 'stale', 'expired')),
    CONSTRAINT run_move_executed_complete CHECK ((state = 'executed') = (executed_at IS NOT NULL))
);
CREATE INDEX run_move_by_run ON run_move (workspace_id, run_id, requested_at DESC);
-- At most one move waits for a second operator per request.
CREATE UNIQUE INDEX run_move_one_pending ON run_move (workspace_id, run_id) WHERE state = 'pendingApproval';

GRANT SELECT, INSERT, UPDATE ON run_migration_batch, run_migration_batch_item, run_move TO ${app_role};
