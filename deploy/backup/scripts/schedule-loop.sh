#!/bin/sh
# Compose scheduler for orqix-backup.sh (CG-P03): one backup at start, then one every BACKUP_INTERVAL_SECONDS. A failed
# backup is logged and retried at the next interval; the container keeps running. OpenShift uses a CronJob instead.
set -u
interval="${BACKUP_INTERVAL_SECONDS:-86400}"
mkdir -p "${BACKUP_DIR:-/backups}/wal" "${BACKUP_DIR:-/backups}/base" 2>/dev/null || true
# The first backup waits (up to 30 minutes) for the migration job to have created the schema.
i=0
until [ "$(psql -qAtX -c "SELECT to_regclass('orqix.workflow_run') IS NOT NULL" 2>/dev/null)" = "t" ] || [ "$i" -ge 60 ]; do
  i=$((i + 1))
  sleep 30
done
while :; do
  if /bin/sh "$(dirname "$0")/orqix-backup.sh"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) schedule: backup ok; next in ${interval} s" >&2
  else
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) schedule: backup FAILED; next attempt in ${interval} s" >&2
  fi
  sleep "$interval"
done
