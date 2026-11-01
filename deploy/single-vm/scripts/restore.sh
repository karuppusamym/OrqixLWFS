#!/usr/bin/env bash
# Restore a backup set made by scripts/backup.sh. Works on a running stack and on a host whose volume is gone.
#
#   scripts/restore.sh backups/orqix-20261030T120000Z            # asks for confirmation
#   scripts/restore.sh <set> --yes                                # no prompt
#   scripts/restore.sh <set> --with-config                        # also put back .env and secrets/ from the set
#   scripts/restore.sh <set> --image-from-backup                  # set ORQIX_IMAGE to the image the backup was made with
#                                                                 # (rollback after an upgrade)
#
# Steps: verify SHA256SUMS -> stop orqix, keycloak, proxy -> start postgres (an empty volume is initialised with the
# roles from .env) -> drop and re-create databases orqix and keycloak from the dumps -> start the stack (the migration
# job is a no-op for the same image) -> compare row counts with the MANIFEST -> health check.
#
# After restoring production data: a restore can roll back Orqix's record of work that remote systems already did.
# Before users resume, check runs that were in flight at backup time (docs/admin-guide.md, "Restore from backup").
source "$(dirname "$0")/common.sh"
require_env_file

SET="${1-}"; [[ -n "$SET" && "$SET" != --* ]] || die "usage: $0 <backup-set-dir> [--yes] [--with-config] [--image-from-backup]"
shift
YES=false; WITH_CONFIG=false; IMAGE_FROM_BACKUP=false
for a in "$@"; do
  case "$a" in
    --yes) YES=true ;;
    --with-config) WITH_CONFIG=true ;;
    --image-from-backup) IMAGE_FROM_BACKUP=true ;;
    *) die "unknown option $a" ;;
  esac
done
SET="$(cd "$(abs_path "$SET")" && native_pwd)" || die "backup set not found"
[[ -f "$SET/orqix.dump" && -f "$SET/SHA256SUMS" ]] || die "$SET is not a backup set (orqix.dump, SHA256SUMS)"

log "verifying checksums of $SET"
( cd "$SET" && sha256sum -c --quiet SHA256SUMS ) || die "checksum mismatch: the backup set is damaged, not restoring"
log "checksums OK"
grep -E '^(created_utc|orqix_image|schema_version)=' "$SET/MANIFEST" | sed 's/^/  /'

if ! $YES; then
  read -r -p "This REPLACES all data of project $(project) with the backup. Type the project name to continue: " ans
  [[ "$ans" == "$(project)" ]] || die "aborted"
fi

if $WITH_CONFIG; then
  [[ -f "$SET/config.tar.gz" ]] || die "the set has no config.tar.gz"
  cp "$ENV_FILE" "$ENV_FILE.before-restore-$(utc_stamp)" 2>/dev/null || true
  tmp="$(mktemp -d)"; ( cd "$tmp" && tar xzf - < "$SET/config.tar.gz" )
  cp "$tmp/env" "$ENV_FILE"; chmod 600 "$ENV_FILE" 2>/dev/null || true
  rm -rf "$KIT_DIR/secrets"; cp -r "$tmp/secrets" "$KIT_DIR/secrets"; rm -rf "$tmp"
  log "configuration restored (.env, secrets/); previous .env kept as .env.before-restore-*"
fi
if $IMAGE_FROM_BACKUP; then
  img="$(grep -E '^orqix_image=' "$SET/MANIFEST" | cut -d= -f2-)"
  [[ -n "$img" ]] || die "MANIFEST has no orqix_image"
  docker image inspect "$img" >/dev/null 2>&1 || die "image $img is not loaded on this host (scripts/load-images.sh)"
  env_set ORQIX_IMAGE "$img"
  log "ORQIX_IMAGE set to $img"
fi

log "stopping application containers"
dc stop proxy orqix >/dev/null 2>&1 || true
keycloak_enabled && { dc stop keycloak >/dev/null 2>&1 || true; }
dc up -d --pull never postgres
wait_healthy postgres 180 || die "postgres did not start"

restore_db() {  # restore_db DB FILE
  log "restoring database $1"
  psql_su -d postgres -c "DROP DATABASE IF EXISTS $1 WITH (FORCE)" >/dev/null
  # --create re-creates the database with its original owner and database-level grants from the dump.
  dc exec -T postgres pg_restore -U postgres -d postgres --create --exit-on-error < "$2"
}
restore_db orqix "$SET/orqix.dump"
if [[ -f "$SET/keycloak.dump" ]]; then
  restore_db keycloak "$SET/keycloak.dump"
fi
# Role passwords are not part of the database dumps: re-apply the ones in .env (a fresh volume already has them).
psql_su -d postgres \
  -v m="$(env_get ORQIX_MIGRATOR_PASSWORD)" -v a="$(env_get ORQIX_APP_PASSWORD)" -v k="$(env_get KEYCLOAK_DB_PASSWORD)" >/dev/null <<'SQL'
ALTER ROLE orqix_migrator PASSWORD :'m';
ALTER ROLE orqix_app PASSWORD :'a';
ALTER ROLE keycloak PASSWORD :'k';
SQL

log "verifying row counts against the MANIFEST"
mismatch=0
checked=0
# The list is read on fd 3: docker exec in the loop body would otherwise consume it from stdin.
while IFS='=' read -r key expected <&3; do
  t="${key#count.}"
  actual="$(psql_su -d orqix -c "SELECT count(*) FROM $t" < /dev/null 2>/dev/null || echo n/a)"
  checked=$((checked + 1))
  if [[ "$actual" == "$expected" ]]; then printf '  %-36s %s\n' "$t" "$actual"
  else printf '  %-36s %s (backup: %s) MISMATCH\n' "$t" "$actual" "$expected"; mismatch=1; fi
done 3< <(grep -E '^count\.' "$SET/MANIFEST")
expected_n="$(grep -cE '^count\.' "$SET/MANIFEST" || true)"
(( checked == expected_n )) || die "checked $checked of $expected_n tables"
(( mismatch == 0 )) || die "restored data does not match the backup manifest"

log "starting the stack"
dc up -d --pull never
keycloak_enabled && { wait_healthy keycloak 420 || die "Keycloak did not become healthy"; }
wait_healthy orqix 420 || die "Orqix did not become healthy after the restore"
wait_healthy proxy 120 || die "proxy did not become healthy"
"$KIT_DIR/scripts/health.sh"
log "restore complete: $(basename "$SET"), schema version $(schema_version)"
