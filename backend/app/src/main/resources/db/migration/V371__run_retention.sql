-- CG-O07 / CG-P04 (docs/build/S144): run retention and archiving. A workflow's retention policy keeps ended requests
-- for N days; then a sweep archives each one (evidence pack plus exit-export-style data, checksummed) to object storage,
-- verifies the stored copy and only then purges the operational rows through orqix_purge_run. Engine history
-- (ACT_HI_*) of purged requests is deleted afterwards in bounded batches through the Flowable API. A legal hold on a
-- request or a workflow blocks archiving-and-purge. The audit log is never purged here.
CREATE TABLE retention_policy (
    workspace_id     uuid NOT NULL,
    workflow_id      uuid NOT NULL,
    keep_days        integer NOT NULL CHECK (keep_days BETWEEN 1 AND 3650),
    include_run_data boolean NOT NULL DEFAULT true,
    include_files    boolean NOT NULL DEFAULT true,
    enabled          boolean NOT NULL DEFAULT true,
    version          integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by       uuid NOT NULL REFERENCES subject (id),
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, workflow_id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id)
);

-- Exactly one target. run_id has no foreign key on purpose: a released hold must not block the later purge, and the
-- service checks that the request exists when the hold is placed.
CREATE TABLE legal_hold (
    id             uuid PRIMARY KEY,
    workspace_id   uuid NOT NULL REFERENCES workspace (id),
    workflow_id    uuid,
    run_id         uuid,
    reason         text NOT NULL CHECK (length(reason) BETWEEN 3 AND 500),
    placed_by      uuid NOT NULL REFERENCES subject (id),
    placed_at      timestamptz NOT NULL,
    released_by    uuid REFERENCES subject (id),
    released_at    timestamptz,
    release_reason text CHECK (release_reason IS NULL OR length(release_reason) BETWEEN 3 AND 500),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    CONSTRAINT legal_hold_one_target CHECK ((workflow_id IS NULL) <> (run_id IS NULL)),
    CONSTRAINT legal_hold_released CHECK ((released_at IS NULL) = (released_by IS NULL)
        AND (released_at IS NULL) = (release_reason IS NULL))
);
CREATE UNIQUE INDEX legal_hold_one_active_run ON legal_hold (workspace_id, run_id)
    WHERE released_at IS NULL AND run_id IS NOT NULL;
CREATE UNIQUE INDEX legal_hold_one_active_workflow ON legal_hold (workspace_id, workflow_id)
    WHERE released_at IS NULL AND workflow_id IS NOT NULL;
CREATE INDEX legal_hold_by_workspace ON legal_hold (workspace_id, placed_at DESC);

CREATE TABLE retention_sweep (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    mode         text NOT NULL CHECK (mode IN ('dryRun', 'purge')),
    trigger      text NOT NULL CHECK (trigger IN ('manual', 'scheduled')),
    requested_by uuid REFERENCES subject (id),
    status       text NOT NULL CHECK (status IN ('running', 'completed', 'failed')),
    started_at   timestamptz NOT NULL,
    finished_at  timestamptz,
    eligible     integer NOT NULL DEFAULT 0 CHECK (eligible >= 0),
    archived     integer NOT NULL DEFAULT 0 CHECK (archived >= 0),
    purged       integer NOT NULL DEFAULT 0 CHECK (purged >= 0),
    held         integer NOT NULL DEFAULT 0 CHECK (held >= 0),
    skipped      integer NOT NULL DEFAULT 0 CHECK (skipped >= 0),
    failed       integer NOT NULL DEFAULT 0 CHECK (failed >= 0),
    detail       jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT retention_sweep_requester CHECK ((trigger = 'manual') = (requested_by IS NOT NULL)),
    CONSTRAINT retention_sweep_finished CHECK ((status = 'running') = (finished_at IS NULL))
);
CREATE INDEX retention_sweep_by_workspace ON retention_sweep (workspace_id, started_at DESC, id DESC);
CREATE UNIQUE INDEX retention_sweep_one_running ON retention_sweep (workspace_id) WHERE status = 'running';

-- The archive record outlives the request: no foreign key to workflow_run.
CREATE TABLE run_archive (
    id                         uuid PRIMARY KEY,
    workspace_id               uuid NOT NULL REFERENCES workspace (id),
    run_id                     uuid NOT NULL,
    run_reference              text NOT NULL,
    workflow_id                uuid NOT NULL,
    root_run_id                uuid NOT NULL,
    sweep_id                   uuid REFERENCES retention_sweep (id),
    lifecycle                  text NOT NULL,
    ended_at                   timestamptz NOT NULL,
    store                      text NOT NULL CHECK (store IN ('filesystem', 's3')),
    object_key                 text NOT NULL CHECK (length(object_key) BETWEEN 1 AND 500),
    sha256                     text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    bytes                      bigint NOT NULL CHECK (bytes > 0),
    status                     text NOT NULL CHECK (status IN ('archived', 'purged')),
    engine_process_instance_id text,
    history_status             text NOT NULL CHECK (history_status IN ('none', 'pending', 'purged')),
    archived_at                timestamptz NOT NULL,
    purged_at                  timestamptz,
    history_purged_at          timestamptz,
    purge_counts               jsonb,
    UNIQUE (workspace_id, run_id),
    CONSTRAINT run_archive_purged CHECK ((status = 'purged') = (purged_at IS NOT NULL)),
    CONSTRAINT run_archive_history CHECK ((history_status = 'purged') = (history_purged_at IS NOT NULL))
);
CREATE INDEX run_archive_by_workspace ON run_archive (workspace_id, archived_at DESC, id DESC);
CREATE INDEX run_archive_by_reference ON run_archive (workspace_id, run_reference);
CREATE INDEX run_archive_history_due ON run_archive (purged_at) WHERE status = 'purged' AND history_status = 'pending';

GRANT SELECT, INSERT, UPDATE, DELETE ON retention_policy TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON legal_hold TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON retention_sweep TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON run_archive TO ${app_role};

-- Purges the operational rows of one ended request that was archived. Runs as the table owner (the runtime role has no
-- DELETE on append-only tables such as run_event and task_decision) and re-checks every guard itself: request ended,
-- an archive record exists, no active legal hold on it, an ancestor or its workflow, no remaining child request, not
-- part of a case, no AI evaluation candidate. Returns the deleted (or detached) row count per table.
-- Merge note: a new table that references workflow_run, task_context or effect_journal must be added here
-- (RetentionPurgeCoverageTest fails otherwise).
CREATE FUNCTION orqix_purge_run(p_ws uuid, p_run uuid) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = orqix, pg_temp AS $$
DECLARE
    v_run     workflow_run%ROWTYPE;
    v_counts  jsonb := '{}'::jsonb;
    v_n       bigint;
    v_step    text[];
    v_steps   text[][] := ARRAY[
        ['agent_human_request', 'workspace_id = $1 AND run_id = $2'],
        ['agent_write_call', 'workspace_id = $1 AND run_id = $2'],
        ['agent_call', 'workspace_id = $1 AND (run_id = $2 OR effect_id IN (SELECT id FROM effect_journal WHERE workspace_id = $1 AND run_id = $2))'],
        ['agent_loop', 'workspace_id = $1 AND run_id = $2'],
        ['agent_trace_event', 'workspace_id = $1 AND run_id = $2'],
        ['ai_decision_record', 'run_id = $2'],
        ['ai_turn_state', 'job_id IN (SELECT id FROM ai_task_job WHERE run_id = $2)'],
        ['ai_task_job', 'run_id = $2'],
        ['callback_wait', 'workspace_id = $1 AND run_id = $2'],
        ['guest_response_file', 'guest_response_id IN (SELECT id FROM guest_response WHERE workspace_id = $1 AND run_id = $2)'],
        ['guest_response', 'workspace_id = $1 AND run_id = $2'],
        ['external_job_callback', 'job_id IN (SELECT id FROM external_job WHERE run_id = $2)'],
        ['external_job', 'run_id = $2'],
        ['message_subscription', 'workspace_id = $1 AND run_id = $2'],
        ['notification', 'workspace_id = $1 AND run_id = $2'],
        ['wait_state', 'workspace_id = $1 AND run_id = $2'],
        ['poll_wait', 'workspace_id = $1 AND run_id = $2'],
        ['attachment_ocr_result', 'attachment_id IN (SELECT id FROM run_attachment WHERE workspace_id = $1 AND run_id = $2)'],
        ['run_attachment_content', 'attachment_id IN (SELECT id FROM run_attachment WHERE workspace_id = $1 AND run_id = $2)'],
        ['run_attachment', 'workspace_id = $1 AND run_id = $2'],
        ['run_comment', 'workspace_id = $1 AND run_id = $2'],
        ['run_consistency_scan', 'workspace_id = $1 AND run_id = $2'],
        ['run_stage', 'workspace_id = $1 AND run_id = $2'],
        ['run_suspension', 'run_id = $2'],
        ['stage_mail', 'workspace_id = $1 AND run_id = $2'],
        ['step_item_result', 'workspace_id = $1 AND run_id = $2'],
        ['task_action_confirmation', 'workspace_id = $1 AND task_id IN (SELECT engine_task_id FROM task_context WHERE workspace_id = $1 AND run_id = $2)'],
        ['task_action_link', 'workspace_id = $1 AND task_id IN (SELECT engine_task_id FROM task_context WHERE workspace_id = $1 AND run_id = $2)'],
        ['task_action_message', 'workspace_id = $1 AND task_id IN (SELECT engine_task_id FROM task_context WHERE workspace_id = $1 AND run_id = $2)'],
        ['task_escalation', 'workspace_id = $1 AND task_id IN (SELECT engine_task_id FROM task_context WHERE workspace_id = $1 AND run_id = $2)'],
        ['task_reminder', 'workspace_id = $1 AND task_id IN (SELECT engine_task_id FROM task_context WHERE workspace_id = $1 AND run_id = $2)'],
        ['task_send_back', 'workspace_id = $1 AND run_id = $2'],
        ['task_sla_breach', 'workspace_id = $1 AND run_id = $2'],
        ['task_decision', 'workspace_id = $1 AND run_id = $2'],
        ['task_context', 'workspace_id = $1 AND run_id = $2'],
        ['wait_reminder', 'workspace_id = $1 AND run_id = $2'],
        ['run_compensation', 'workspace_id = $1 AND run_id = $2'],
        ['effect_journal', 'workspace_id = $1 AND run_id = $2'],
        ['webhook_delivery', 'workspace_id = $1 AND run_id = $2'],
        ['run_event', 'workspace_id = $1 AND run_id = $2']];
    -- Records kept for deduplication or schedule history: only their link to the request is removed.
    v_detach  text[][] := ARRAY[
        ['inbound_event', 'run_id'], ['kafka_inbound_record', 'run_id'], ['package_trigger_record', 'run_id'],
        ['schedule_fire', 'run_id'], ['schedule_trigger', 'last_run_id']];
BEGIN
    SELECT * INTO v_run FROM workflow_run WHERE workspace_id = p_ws AND id = p_run FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'retention:not_found' USING ERRCODE = 'P0002';
    END IF;
    IF v_run.lifecycle NOT IN ('completed', 'cancelled') OR v_run.ended_at IS NULL THEN
        RAISE EXCEPTION 'retention:not_ended' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM run_archive WHERE workspace_id = p_ws AND run_id = p_run AND status = 'archived') THEN
        RAISE EXCEPTION 'retention:not_archived' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (WITH RECURSIVE chain AS (
                   SELECT id, parent_run_id, workflow_id FROM workflow_run WHERE workspace_id = p_ws AND id = p_run
                   UNION ALL
                   SELECT r.id, r.parent_run_id, r.workflow_id FROM workflow_run r
                   JOIN chain c ON r.workspace_id = p_ws AND r.id = c.parent_run_id)
               SELECT 1 FROM legal_hold h JOIN chain c ON h.run_id = c.id OR h.workflow_id = c.workflow_id
               WHERE h.workspace_id = p_ws AND h.released_at IS NULL) THEN
        RAISE EXCEPTION 'retention:legal_hold' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM workflow_run WHERE workspace_id = p_ws AND parent_run_id = p_run) THEN
        RAISE EXCEPTION 'retention:has_children' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM case_workflow_task WHERE workspace_id = p_ws AND run_id = p_run) THEN
        RAISE EXCEPTION 'retention:case_linked' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM ai_eval_candidate c JOIN ai_decision_record d ON d.id = c.decision_id
               WHERE d.run_id = p_run) THEN
        RAISE EXCEPTION 'retention:ai_evaluation_candidate' USING ERRCODE = 'P0001';
    END IF;

    FOREACH v_step SLICE 1 IN ARRAY v_steps LOOP
        EXECUTE format('DELETE FROM %I WHERE %s', v_step[1], v_step[2]) USING p_ws, p_run;
        GET DIAGNOSTICS v_n = ROW_COUNT;
        IF v_n > 0 THEN
            v_counts := v_counts || jsonb_build_object(v_step[1], v_n);
        END IF;
    END LOOP;
    FOREACH v_step SLICE 1 IN ARRAY v_detach LOOP
        EXECUTE format('UPDATE %I SET %I = NULL WHERE workspace_id = $1 AND %I = $2', v_step[1], v_step[2], v_step[2])
            USING p_ws, p_run;
        GET DIAGNOSTICS v_n = ROW_COUNT;
        IF v_n > 0 THEN
            v_counts := v_counts || jsonb_build_object(v_step[1] || '.detached', v_n);
        END IF;
    END LOOP;
    DELETE FROM workflow_run WHERE workspace_id = p_ws AND id = p_run;
    v_counts := v_counts || jsonb_build_object('workflow_run', 1);
    RETURN v_counts;
END;
$$;
REVOKE ALL ON FUNCTION orqix_purge_run(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION orqix_purge_run(uuid, uuid) TO ${app_role};
