#!/usr/bin/env bash
# Upgrade Orqix to a new image: backup first, load or pull the image, run migrations, health check, roll back on failure.
#
#   scripts/upgrade.sh --image registry.internal/orqix/orqix:0.2.0                          # image already loaded
#   scripts/upgrade.sh --image orqix/orqix:0.2.0 --archive /media/usb/orqix-0.2.0.tar       # air-gapped: docker load
#   scripts/upgrade.sh --image orqix/orqix:0.2.0 --pull                                     # connected host
#   add --yes to skip the confirmation, --no-rollback to leave a failed upgrade in place for diagnosis.
#
# Steps
#   1. preflight (--running), image present (load/pull), record the current image and schema version
#   2. backup.sh --label pre-upgrade (mandatory; the upgrade stops if it fails)
#   3. ORQIX_IMAGE := new image in .env (old .env kept as .env.pre-upgrade-<stamp>)
#   4. stop orqix, run the new image's migration job (Flyway + Flowable schema), start orqix, wait for readiness
#   5. health.sh (containers, readiness, HTTPS UI, OIDC discovery)
#   On failure in 4/5: ORQIX_IMAGE is set back; if the schema version changed, the pre-upgrade backup is restored
#   (migrations are forward-only); the old image is started and checked. The backup set path is printed either way.
source "$(dirname "$0")/common.sh"
require_env_file

NEW=""; ARCHIVE=""; PULL=false; YES=false; ROLLBACK=true
while (( $# )); do
  case "$1" in
    --image) NEW="${2:?}"; shift ;;
    --archive) ARCHIVE="${2:?}"; shift ;;
    --pull) PULL=true ;;
    --yes) YES=true ;;
    --no-rollback) ROLLBACK=false ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done
[[ -n "$NEW" ]] || die "--image is required"

OLD="$(env_get ORQIX_IMAGE)"
log "upgrade $(project): $OLD -> $NEW"
"$KIT_DIR/scripts/preflight.sh" --running >/dev/null || { "$KIT_DIR/scripts/preflight.sh" --running; die "preflight failed"; }

if [[ -n "$ARCHIVE" ]]; then
  [[ -f "$ARCHIVE" ]] || die "archive $ARCHIVE not found"
  if [[ -f "$ARCHIVE.sha256" ]]; then
    [[ "$(sha256_file "$ARCHIVE")" == "$(awk '{print $1}' "$ARCHIVE.sha256")" ]] || die "checksum mismatch for $ARCHIVE"
    log "archive checksum OK"
  else
    warn "no $ARCHIVE.sha256 next to the archive: integrity not verified"
  fi
  docker load -i "$ARCHIVE"
elif $PULL; then
  docker pull "$NEW"
fi
docker image inspect "$NEW" >/dev/null 2>&1 || die "image $NEW is not present (use --archive or --pull)"
log "new image id $(docker image inspect -f '{{.Id}}' "$NEW")"

if ! $YES; then
  read -r -p "Upgrade project $(project) to $NEW now? Users are signed out for about two minutes. [y/N] " ans
  [[ "$ans" == y || "$ans" == Y ]] || die "aborted"
fi

v_before="$(schema_version)"
log "schema version before: $v_before"
log "step 2: backup"
SET="$("$KIT_DIR/scripts/backup.sh" --label pre-upgrade | tail -n 1)"
[[ -f "$SET/SHA256SUMS" ]] || die "backup failed; nothing was changed"
log "pre-upgrade backup: $SET"

cp "$ENV_FILE" "$ENV_FILE.pre-upgrade-$(utc_stamp)"
env_set ORQIX_IMAGE "$NEW"

rollback() {
  warn "upgrade failed: $1"
  if ! $ROLLBACK; then
    warn "--no-rollback: left as is. Roll back with: scripts/restore.sh $SET --image-from-backup --yes"
    exit 1
  fi
  env_set ORQIX_IMAGE "$OLD"
  local v_now; v_now="$(schema_version)"
  if [[ "$v_now" != "$v_before" ]]; then
    warn "schema changed ($v_before -> $v_now): restoring the pre-upgrade backup with the old image"
    "$KIT_DIR/scripts/restore.sh" "$SET" --yes --image-from-backup || die "ROLLBACK FAILED; restore manually from $SET"
  else
    warn "schema unchanged: starting the old image $OLD again"
    dc up -d --pull never
    wait_healthy orqix 420 && "$KIT_DIR/scripts/health.sh" || die "ROLLBACK FAILED; restore manually from $SET"
  fi
  die "rolled back to $OLD (backup $SET)"
}

log "step 4: migrations with the new image"
dc stop orqix >/dev/null
if ! dc run --rm --no-deps orqix-migrate; then rollback "migration job failed"; fi
log "schema version after migration: $(schema_version)"
dc up -d --pull never || rollback "docker compose up failed"
wait_healthy orqix 420 || rollback "Orqix not healthy with $NEW"
wait_healthy proxy 120 || rollback "proxy not healthy"
log "step 5: health check"
"$KIT_DIR/scripts/health.sh" || rollback "health check failed"
log "upgrade complete: $NEW (schema $(schema_version)); pre-upgrade backup $SET"
