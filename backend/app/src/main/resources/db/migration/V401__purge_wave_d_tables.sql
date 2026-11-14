-- Merge of Wave D (8 November 2026): orqix_purge_run (V371, S144) did not know the request-linked tables added by
-- bulk operations and search (V351, V352), instance migration and moves (V360), the analytics read model (V380) and
-- document extraction (V390). Purging a request now also deletes those rows. Analytics facts of a purged request are
-- deleted too (decision recorded in docs/build/S144): a purge must be complete, the archived evidence pack carries the
-- request id, and long-term KPIs belong in a later id-free rollup. Pending analytics outbox rows are removed so the
-- projector cannot recreate facts; it already deletes facts of a request that no longer exists.
CREATE OR REPLACE FUNCTION orqix_purge_run(p_ws uuid, p_run uuid) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = orqix, pg_temp AS $$
DECLARE
    v_run     workflow_run%ROWTYPE;
    v_counts  jsonb := '{}'::jsonb;
    v_n       bigint;
    v_step    text[];
    v_steps   text[][] := ARRAY[
        -- Wave D merge (V401): tables added by S142, S143, S145 and S146 after S144 wrote this function.
        ['run_search_value', 'workspace_id = $1 AND run_id = $2'],
        ['bulk_operation_item', 'workspace_id = $1 AND run_id = $2'],
        ['run_migration_batch_item', 'workspace_id = $1 AND run_id = $2'],
        ['run_move', 'workspace_id = $1 AND run_id = $2'],
        ['ai_document_verification', 'workspace_id = $1 AND run_id = $2'],
        ['analytics_outbox', 'workspace_id = $1 AND run_id = $2'],
        ['analytics_step_visit', 'workspace_id = $1 AND run_id = $2'],
        ['analytics_route', 'workspace_id = $1 AND run_id = $2'],
        ['analytics_task', 'workspace_id = $1 AND run_id = $2'],
        ['analytics_run', 'workspace_id = $1 AND run_id = $2'],
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
