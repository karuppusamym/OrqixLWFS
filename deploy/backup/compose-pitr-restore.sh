#!/usr/bin/env bash
# Point-in-time restore of the Compose PostgreSQL into a SCRATCH server (CG-P03, deploy/backup/README.md). Starts a
# separate postgres container from the newest base backup taken before the target, replays archived WAL up to the
# target time, promotes it and prints what it holds. The running stack is not touched; use the result to inspect or
# to copy data back (pg_dump from the scratch server), or follow the runbook to replace the live database.
#
#   deploy/backup/compose-pitr-restore.sh --target "2026-11-09 14:05:00+00"   # stop just before that time
#   deploy/backup/compose-pitr-restore.sh                                     # replay all archived WAL
#   options: --base base-20261109T120000Z   --keep (leave the scratch container running)   --port 55432
#   COMPOSE_PROJECT=orqix-roles ...         # volume <project>_orqix-backups (default project "orqix")
set -euo pipefail
project="${COMPOSE_PROJECT:-orqix}"
volume="${project}_orqix-backups"
image="${POSTGRES_IMAGE:-postgres:18-alpine}"
target=""; base=""; keep=false; port=55432
while (( $# )); do
  case "$1" in
    --target) target="${2:?}"; shift ;;
    --base) base="${2:?}"; shift ;;
    --keep) keep=true ;;
    --port) port="${2:?}"; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
docker volume inspect "$volume" >/dev/null || { echo "volume $volume not found (backup overlay not used?)" >&2; exit 1; }
name="orqix-pitr-$(date -u +%Y%m%d%H%M%S)"
started=$(date +%s)

# Recovery settings: replay from the WAL archive, stop at the target (or at the end of the archive), then promote.
recovery="restore_command = 'cp /backups/wal/%f %p'
recovery_target_action = 'promote'
archive_mode = off"
[[ -n "$target" ]] && recovery+="
recovery_target_time = '$target'
recovery_target_inclusive = false"

docker run -d --name "$name" -p "127.0.0.1:$port:5432" -v "$volume:/backups:ro" -e PGDATA=/var/lib/postgresql/pitr \
  -e BASE="$base" -e RECOVERY="$recovery" --entrypoint /bin/sh "$image" -ceu '
    base_dir="${BASE:+/backups/base/$BASE}"
    [ -n "$base_dir" ] || base_dir="$(ls -1d /backups/base/base-* | grep -v partial | sort | tail -n 1)"
    [ -f "$base_dir/base.tar.gz" ] || { echo "no base backup found" >&2; exit 1; }
    ( cd "$base_dir" && sha256sum -c SHA256SUMS >/dev/null ) || { echo "base backup checksum mismatch" >&2; exit 1; }
    echo "base backup $base_dir" >&2
    mkdir -p "$PGDATA" && chmod 700 "$PGDATA"
    tar xzf "$base_dir/base.tar.gz" -C "$PGDATA"
    mkdir -p "$PGDATA/pg_wal" && tar xzf "$base_dir/pg_wal.tar.gz" -C "$PGDATA/pg_wal"
    printf "%s\n" "$RECOVERY" >> "$PGDATA/postgresql.auto.conf"
    touch "$PGDATA/recovery.signal"
    chown -R postgres:postgres "$PGDATA"
    exec gosu postgres postgres -D "$PGDATA" -c listen_addresses="*"' >/dev/null

cleanup() { $keep || docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
echo "scratch server $name (127.0.0.1:$port) replaying WAL${target:+ up to $target}" >&2
for _ in $(seq 1 600); do
  state="$(docker exec "$name" psql -U postgres -qAtX -c 'SELECT pg_is_in_recovery()' 2>/dev/null || true)"
  [[ "$state" == "f" ]] && break
  docker inspect -f '{{.State.Running}}' "$name" | grep -q true || { docker logs --tail 30 "$name" >&2; exit 1; }
  sleep 1
done
[[ "$state" == "f" ]] || { echo "recovery did not finish in 10 minutes" >&2; docker logs --tail 30 "$name" >&2; exit 1; }
docker exec "$name" psql -U postgres -d orqix -qAtX -c "
  SELECT 'requests=' || count(*) || ' newest_request_utc=' || coalesce(to_char(max(created_at) AT TIME ZONE 'UTC',
         'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), '-') FROM orqix.workflow_run;
  SELECT 'events=' || count(*) FROM orqix.run_event;
  SELECT 'effects_to_reconcile=' || count(*) FROM orqix.effect_journal WHERE state IN ('inFlight', 'unknown');"
echo "recovered in $(( $(date +%s) - started )) s" >&2
$keep && echo "kept: docker rm -f $name when done (psql -h 127.0.0.1 -p $port -U postgres orqix)" >&2
