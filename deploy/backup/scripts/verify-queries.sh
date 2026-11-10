#!/bin/sh
# Verification queries shared by orqix-backup.sh (at dump time, in the dump's snapshot) and orqix-restore-drill.sh (on
# the restored copy). Output: key=value lines, one per table count, plus one sample request.
# Sourced, not executed.

# Tables whose counts must match after a restore: definitions, requests and their history, open work, effects, audit,
# stored files and the engine's runtime and history tables (T-RES-06: "pending tasks and releases intact").
VERIFY_TABLES="orqix.workspace orqix.subject orqix.workflow orqix.workflow_release orqix.workflow_run orqix.run_event
orqix.task_decision orqix.task_context orqix.effect_journal orqix.audit_event orqix.idempotency_record
orqix.inbound_message orqix.run_attachment_content orqix.analytics_run orqix.workspace_admission_limit
flowable.act_ru_execution flowable.act_ru_task flowable.act_ru_job flowable.act_ru_timer_job flowable.act_ru_variable
flowable.act_hi_procinst flowable.act_hi_taskinst"

verify_sql() {
  for t in $VERIFY_TABLES; do
    # Tables that do not exist in this schema version are reported as "absent" on both sides.
    echo "SELECT 'count.$t=' || CASE WHEN to_regclass('$t') IS NULL THEN 'absent'"
    echo "  ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM $t', false, true, '')))[1]::text END;"
  done
  # The newest request, described by values that a faithful restore reproduces exactly.
  cat <<'SQL'
SELECT 'sample.request=' || coalesce((
  SELECT r.id || '|' || r.reference || '|' || r.lifecycle || '|' || r.release_id || '|' || r.event_seq
         || '|' || (SELECT count(*) FROM orqix.run_event e WHERE e.run_id = r.id)
         || '|' || (SELECT count(*) FROM orqix.effect_journal j WHERE j.run_id = r.id)
         || '|' || coalesce(md5(r.input::text), '-')
  FROM orqix.workflow_run r ORDER BY r.created_at DESC, r.id DESC LIMIT 1), 'none');
SELECT 'effects.to_reconcile=' || count(*) FROM orqix.effect_journal WHERE state IN ('inFlight', 'unknown');
SQL
}
