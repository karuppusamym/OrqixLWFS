#!/usr/bin/env bash
# Online backup of everything the kit stores, into one folder with SHA-256 checksums.
#
#   scripts/backup.sh                    # backups/<project>-<UTC stamp>/
#   scripts/backup.sh --label pre-upgrade
#   scripts/backup.sh --volume-snapshot  # also a cold copy of the PostgreSQL volume (stops the stack for a moment)
#   scripts/backup.sh --no-config        # leave out .env and secrets/ (then keep them safe some other way)
#
# What a backup set holds:
#   orqix.dump        pg_dump -Fc of database "orqix": all Orqix data, INCLUDING request files/attachments, generated
#                     documents and UI extension files (stored as bytea in PostgreSQL), audit trail, run history,
#                     Flowable engine state and sessions. There is no other application volume.
#   keycloak.dump     pg_dump -Fc of database "keycloak" (bundled IdP: realm, users, credentials hashes, clients).
#   globals.sql       role definitions (pg_dumpall --globals-only), for reference; restore recreates roles from .env.
#   config.tar.gz     .env as "env" (every secret), secrets/ (connection secret files), realm file, proxy template.
#   pgdata.tar.gz     only with --volume-snapshot: the raw volume, stack stopped.
#   MANIFEST          image tags and digests, schema version, row counts used to verify a restore.
#   SHA256SUMS        checksums of all of the above; verified before the script reports success and by restore.sh.
#
# The set contains secrets and personal data: keep BACKUP_DIR encrypted and inside the jurisdiction.
source "$(dirname "$0")/common.sh"
require_env_file

LABEL=""; SNAPSHOT=false; CONFIG=true
while (( $# )); do
  case "$1" in
    --label) LABEL="-${2:?--label needs a value}"; shift ;;
    --volume-snapshot) SNAPSHOT=true ;;
    --no-config) CONFIG=false ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done

[[ "$(health_of postgres)" == "healthy" ]] || die "postgres is not running/healthy; start the stack first"

root="$(abs_path "$(env_get BACKUP_DIR ./backups)")"
name="$(project)-$(utc_stamp)${LABEL}"
dir="$root/$name"
mkdir -p "$dir"
chmod 700 "$dir" 2>/dev/null || true
log "backup set $dir"

dump_db() {  # dump_db DB FILE
  dc exec -T postgres pg_dump -U postgres -d "$1" -Fc --no-password > "$2.partial"
  mv "$2.partial" "$2"
  # Prove the archive is readable (catalog listing) before trusting it.
  dc exec -T postgres pg_restore -l < "$2" > /dev/null || die "pg_restore cannot read $2"
  log "  $1 -> $(basename "$2") ($(wc -c < "$2" | tr -d ' ') bytes)"
}

dump_db orqix "$dir/orqix.dump"
if psql_su -d postgres -c "SELECT 1 FROM pg_database WHERE datname='keycloak'" | grep -q 1; then
  dump_db keycloak "$dir/keycloak.dump"
fi
dc exec -T postgres pg_dumpall -U postgres --globals-only > "$dir/globals.sql"

if $CONFIG; then
  stage="$(mktemp -d)"
  mkdir -p "$stage/keycloak" "$stage/proxy"
  cp "$ENV_FILE" "$stage/env"
  cp -r "$KIT_DIR/secrets" "$stage/secrets"
  cp "$KIT_DIR/keycloak/orqix-realm.json" "$stage/keycloak/"
  cp "$KIT_DIR/proxy/default.conf.template" "$stage/proxy/"
  # Written to stdout: GNU tar would read an archive name like "C:/..." (Git Bash) as a remote host.
  ( cd "$stage" && tar czf - . ) > "$dir/config.tar.gz"
  rm -rf "$stage"
  log "  configuration -> config.tar.gz (contains secrets)"
fi

{
  echo "created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "project=$(project)"
  echo "public_url=$(env_get ORQIX_PUBLIC_URL)"
  for k in ORQIX_IMAGE POSTGRES_IMAGE KEYCLOAK_IMAGE PROXY_IMAGE; do
    img="$(env_get "$k")"
    [[ -n "$img" ]] || continue
    echo "$(echo "$k" | tr 'A-Z' 'a-z')=$img"
    echo "$(echo "$k" | tr 'A-Z' 'a-z')_id=$(docker image inspect -f '{{.Id}}' "$img" 2>/dev/null || echo unknown)"
  done
  echo "schema_version=$(schema_version)"
  echo "postgres_server=$(psql_su -d orqix -c 'SHOW server_version')"
  # Row counts for restore verification (restore.sh compares them).
  for t in $VERIFY_TABLES; do
    c="$(psql_su -d orqix -c "SELECT count(*) FROM $t" 2>/dev/null || echo n/a)"
    echo "count.$t=$c"
  done
} > "$dir/MANIFEST"

if $SNAPSHOT; then
  vol="$(project)_pgdata"
  log "  volume snapshot of $vol: stopping the stack"
  dc stop proxy orqix keycloak postgres >/dev/null 2>&1 || dc stop proxy orqix postgres
  docker run --rm --network none -v "$vol:/source:ro" -v "$dir:/backup" "$(env_get POSTGRES_IMAGE postgres:18-alpine)" \
    tar czf /backup/pgdata.tar.gz -C /source .
  dc up -d --pull never >/dev/null
  wait_healthy orqix 420 || warn "Orqix not healthy after the snapshot restart"
  log "  volume -> pgdata.tar.gz"
fi

( cd "$dir" && sha256sum -- * | grep -v ' SHA256SUMS$' > SHA256SUMS && sha256sum -c --quiet SHA256SUMS ) \
  || die "checksum verification failed for $dir"
log "checksums verified: $(wc -l < "$dir/SHA256SUMS" | tr -d ' ') files"

days="$(env_get BACKUP_RETENTION_DAYS 14)"
if [[ "$days" =~ ^[0-9]+$ ]] && (( days > 0 )); then
  find "$root" -mindepth 1 -maxdepth 1 -type d -name "$(project)-*" -mtime +"$days" -print -exec rm -rf {} + \
    | sed 's/^/  pruned /' || true
fi
echo "$dir"
