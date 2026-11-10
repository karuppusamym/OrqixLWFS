#!/bin/sh
# Orqix logical backup (CG-P03, docs/build/S150, runbook deploy/backup/README.md). POSIX sh: runs in the postgres
# image (Compose backup service, OpenShift CronJob) or on any host with the PostgreSQL 18 client tools.
#
# Connection: the libpq variables PGHOST, PGPORT, PGUSER, PGPASSWORD (or PGPASSFILE), PGDATABASE (default orqix).
# The user needs read access to every table of the schemas orqix and flowable (the migrator role, or a dedicated
# backup role with pg_read_all_data).
#
#   BACKUP_DIR=/backups orqix-backup.sh            # writes $BACKUP_DIR/orqix-<UTC stamp>/
#   BACKUP_RETENTION_DAYS=14                         # sets older than this are deleted after a successful backup
#   BACKUP_LABEL=pre-upgrade                         # appended to the set name
#
# A set holds:
#   orqix.dump   pg_dump -Fc of the whole database: application data (including files stored as bytea), audit trail,
#                engine state and history, sessions. One consistent snapshot.
#   MANIFEST     key=value: time, server version, schema version, and the row counts and one sample request read IN THE
#                SAME SNAPSHOT as the dump, so a restore can be verified exactly (orqix-restore-drill.sh).
#   SHA256SUMS   checksums, verified before success is reported.
# The set contains personal data and secrets of connected systems (sealed values): keep BACKUP_DIR encrypted.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
PGDATABASE="${PGDATABASE:-orqix}"
export PGDATABASE
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
LABEL="${BACKUP_LABEL:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=verify-queries.sh
. "$SCRIPT_DIR/verify-queries.sh"

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="orqix-$stamp${LABEL:+-$LABEL}"
set_dir="$BACKUP_DIR/$name"
work="$(mktemp -d)"
mkdir -p "$set_dir.partial"
chmod 700 "$set_dir.partial" 2>/dev/null || true
holder=""
cleanup() {
  [ -n "$holder" ] && kill "$holder" 2>/dev/null || true
  rm -rf "$work"
  [ -d "$set_dir.partial" ] && rm -rf "$set_dir.partial"
}
trap cleanup EXIT INT TERM

started="$(date +%s)"
log "set $set_dir (database $PGDATABASE on ${PGHOST:-local socket})"

# 1. Export a snapshot from a session that stays open until the dump and the counts are done.
mkfifo "$work/ctl"
psql -qAtX -v ON_ERROR_STOP=1 < "$work/ctl" > "$work/snapshot" 2> "$work/snapshot.err" &
holder=$!
exec 3> "$work/ctl"
printf '%s\n' "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;" "SELECT pg_export_snapshot();" >&3
i=0
while [ ! -s "$work/snapshot" ]; do
  i=$((i + 1))
  [ "$i" -gt 300 ] && die "no snapshot after 30 s: $(cat "$work/snapshot.err")"
  kill -0 "$holder" 2>/dev/null || die "snapshot session ended: $(cat "$work/snapshot.err")"
  sleep 0.1
done
snapshot="$(head -n 1 "$work/snapshot")"
log "snapshot $snapshot"

# 2. Row counts and the sample request, in that snapshot.
{
  echo "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;"
  echo "SET TRANSACTION SNAPSHOT '$snapshot';"
  verify_sql
  echo "COMMIT;"
} | psql -qAtX -v ON_ERROR_STOP=1 > "$work/verify" || die "verification queries failed"
# A value that came back empty would make the MANIFEST useless for a drill.
! grep -qE '^(count\.[a-z_.]+|sample\.request)=$' "$work/verify" || die "a verification query returned no value"
[ "$(grep -c '^count\.' "$work/verify")" -ge 10 ] || die "verification returned too few counts"

# 3. The dump, in the same snapshot.
pg_dump -Fc --snapshot="$snapshot" -f "$set_dir.partial/orqix.dump" || die "pg_dump failed"
pg_restore -l "$set_dir.partial/orqix.dump" > /dev/null || die "pg_restore cannot read the dump"
printf '%s\n' "COMMIT;" >&3
exec 3>&-
wait "$holder" 2>/dev/null || true
holder=""

{
  echo "format=orqix-backup/1"
  echo "created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "database=$PGDATABASE"
  echo "snapshot=$snapshot"
  echo "server_version=$(psql -qAtX -c 'SHOW server_version')"
  echo "schema_version=$(psql -qAtX -c "SELECT max(version::int) FROM orqix.flyway_schema_history WHERE success" 2>/dev/null || echo unknown)"
  cat "$work/verify"
  echo "dump_bytes=$(wc -c < "$set_dir.partial/orqix.dump" | tr -d ' ')"
  echo "elapsed_seconds=$(( $(date +%s) - started ))"
} > "$set_dir.partial/MANIFEST"

( cd "$set_dir.partial" && sha256sum orqix.dump MANIFEST > SHA256SUMS && sha256sum -c SHA256SUMS > /dev/null ) \
  || die "checksum verification failed"
mv "$set_dir.partial" "$set_dir"
log "done: $(grep -c '^count\.' "$set_dir/MANIFEST") tables counted, $(grep '^dump_bytes=' "$set_dir/MANIFEST" | cut -d= -f2) bytes"

case "$RETENTION_DAYS" in
  ''|*[!0-9]*) ;;
  0) ;;
  *) find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name 'orqix-*' -mtime +"$RETENTION_DAYS" \
       -exec rm -rf {} + -exec printf 'pruned %s\n' {} \; >&2 || true ;;
esac
echo "$set_dir"
