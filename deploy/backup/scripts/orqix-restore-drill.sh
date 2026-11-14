#!/bin/sh
# Orqix restore drill (CG-P03, T-RES-06; runbook deploy/backup/README.md). Restores a backup set into a NEW scratch
# database on the same or another PostgreSQL server, verifies it against the set's MANIFEST, records the result and
# drops the scratch database. Production data is never touched: the scratch name is generated and must not exist.
#
#   orqix-restore-drill.sh                     # newest set in $BACKUP_DIR
#   orqix-restore-drill.sh /backups/orqix-...  # a given set
#   DRILL_KEEP=true                            # keep the scratch database for inspection (drop it yourself)
#
# Connection: libpq variables; PGDATABASE is only used to connect for CREATE/DROP DATABASE (default postgres). The
# user needs CREATEDB. Restores with --no-owner --no-privileges, so the target server needs no Orqix roles.
# Checks: checksums; every MANIFEST count equals the restored count; the sample request is identical; the restore
# time is recorded. Writes $BACKUP_DIR/drills/drill-<stamp>.json and exits 1 on any mismatch.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
KEEP="${DRILL_KEEP:-false}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=verify-queries.sh
. "$SCRIPT_DIR/verify-queries.sh"

log() { printf '%s drill: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

set_dir="${1:-}"
if [ -z "$set_dir" ]; then
  set_dir="$(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name 'orqix-*' ! -name '*.partial' | sort | tail -n 1)"
fi
[ -n "$set_dir" ] && [ -f "$set_dir/orqix.dump" ] && [ -f "$set_dir/MANIFEST" ] || die "no backup set found (${set_dir:-$BACKUP_DIR})"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
scratch="orqix_drill_$(date -u +%Y%m%d%H%M%S)"
admin_db="${DRILL_ADMIN_DATABASE:-postgres}"
work="$(mktemp -d)"
created=false
cleanup() {
  if $created && [ "$KEEP" != "true" ]; then
    psql -qAtX -d "$admin_db" -c "DROP DATABASE IF EXISTS \"$scratch\" WITH (FORCE)" >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT INT TERM

log "set $set_dir -> scratch database $scratch on ${PGHOST:-local socket}"
( cd "$set_dir" && sha256sum -c SHA256SUMS > /dev/null ) || die "checksum mismatch: the set is damaged"

t0="$(date +%s)"
[ -z "$(psql -qAtX -d "$admin_db" -c "SELECT 1 FROM pg_database WHERE datname = '$scratch'")" ] \
  || die "scratch database $scratch already exists"
psql -qAtX -d "$admin_db" -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$scratch\"" >/dev/null
created=true
pg_restore --no-owner --no-privileges --exit-on-error -d "$scratch" "$set_dir/orqix.dump" 2> "$work/restore.err" \
  || die "pg_restore failed: $(tail -n 5 "$work/restore.err")"
t1="$(date +%s)"
log "restored in $((t1 - t0)) s"

verify_sql | psql -qAtX -v ON_ERROR_STOP=1 -d "$scratch" > "$work/restored" || die "verification queries failed"
t2="$(date +%s)"

checked=0; failed=0
: > "$work/mismatches"
while IFS='=' read -r key expected; do
  case "$key" in
    count.*|sample.request) ;;
    *) continue ;;
  esac
  checked=$((checked + 1))
  actual="$(grep "^$key=" "$work/restored" | head -n 1 | cut -d= -f2-)"
  if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then
    failed=$((failed + 1))
    printf '%s expected=%s restored=%s\n' "$key" "$expected" "${actual:-missing}" >> "$work/mismatches"
  fi
done < "$set_dir/MANIFEST"
# Every table of VERIFY_TABLES plus the sample request: a MANIFEST with fewer lines cannot prove a restore.
expected_checks=$(( $(echo $VERIFY_TABLES | wc -w) + 1 ))
[ "$checked" -ge "$expected_checks" ] || die "the MANIFEST has $checked of $expected_checks verification lines"

reconcile="$(grep '^effects.to_reconcile=' "$work/restored" | cut -d= -f2)"
runs="$(grep '^count.orqix.workflow_run=' "$work/restored" | cut -d= -f2)"
tasks="$(grep '^count.flowable.act_ru_task=' "$work/restored" | cut -d= -f2)"
releases="$(grep '^count.orqix.workflow_release=' "$work/restored" | cut -d= -f2)"
result=passed; [ "$failed" -eq 0 ] || result=failed

mkdir -p "$BACKUP_DIR/drills"
report="$BACKUP_DIR/drills/drill-$stamp.json"
mismatch_json="$(sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/' "$work/mismatches" | paste -sd, -)"
cat > "$report" <<EOF
{
  "drill": "orqix-restore/1",
  "startedUtc": "$stamp",
  "backupSet": "$(basename "$set_dir")",
  "backupCreatedUtc": "$(grep '^created_utc=' "$set_dir/MANIFEST" | cut -d= -f2)",
  "dumpBytes": $(wc -c < "$set_dir/orqix.dump" | tr -d ' '),
  "scratchDatabase": "$scratch",
  "scratchKept": $([ "$KEEP" = "true" ] && echo true || echo false),
  "restoreSeconds": $((t1 - t0)),
  "verifySeconds": $((t2 - t1)),
  "checks": $checked,
  "mismatches": [${mismatch_json}],
  "requests": "${runs:-0}",
  "openEngineTasks": "${tasks:-0}",
  "releases": "${releases:-0}",
  "effectsToReconcileBeforeResuming": ${reconcile:-0},
  "result": "$result"
}
EOF
log "$checked checks, $failed mismatches, report $report"
[ "$failed" -eq 0 ] || { cat "$work/mismatches" >&2; exit 1; }
cat "$report"
