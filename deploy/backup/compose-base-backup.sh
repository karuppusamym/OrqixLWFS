#!/usr/bin/env bash
# Base backup of the Compose PostgreSQL for point-in-time restore (CG-P03, deploy/backup/README.md). Needs the backup
# overlay (docker-compose.backup.yml: WAL archiving to the orqix-backups volume). Runs pg_basebackup inside the postgres
# container over the local socket (the image trusts local replication), tar + gzip, with the WAL needed to make it
# consistent. Take one at least weekly: a point-in-time restore replays archived WAL from the newest base backup
# before the target time.
#
#   deploy/backup/compose-base-backup.sh                  # project "orqix"
#   COMPOSE_PROJECT=orqix-roles deploy/backup/compose-base-backup.sh
#   BASE_BACKUP_KEEP=4                                    # base backups kept (default 4); older ones are deleted
set -euo pipefail
cd "$(dirname "$0")/../compose"
compose=(docker compose ${COMPOSE_PROJECT:+-p "$COMPOSE_PROJECT"} -f docker-compose.yml -f docker-compose.backup.yml)
keep="${BASE_BACKUP_KEEP:-4}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="/backups/base/base-$stamp"
started=$(date +%s)
"${compose[@]}" exec -T -u postgres postgres sh -ceu "
  pg_basebackup -U postgres -D '$target.partial' -Ft -z -X stream --checkpoint=fast --label 'orqix-$stamp'
  ( cd '$target.partial' && sha256sum *.tar.gz backup_manifest > SHA256SUMS )
  mv '$target.partial' '$target'
  ls -1d /backups/base/base-* | grep -v partial | sort | head -n -$keep | xargs -r rm -rf
"
echo "base backup $target ($(( $(date +%s) - started )) s)"
