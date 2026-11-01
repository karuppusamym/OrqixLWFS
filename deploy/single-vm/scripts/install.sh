#!/usr/bin/env bash
# First installation (and safe re-run) of the single-VM kit.
#
#   scripts/install.sh --generate-secrets   # create .env from .env.example with random secrets (only empty values)
#   scripts/install.sh                      # preflight, start the stack, wait until healthy
#
# Options: --generate-secrets, --skip-preflight. Images must already be loaded (scripts/load-images.sh) or pullable.
source "$(dirname "$0")/common.sh"

GEN=false; SKIP_PREFLIGHT=false
for a in "$@"; do
  case "$a" in
    --generate-secrets) GEN=true ;;
    --skip-preflight) SKIP_PREFLIGHT=true ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown option $a" ;;
  esac
done

rand() { openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-"${1:-40}"; }

if $GEN; then
  command -v openssl >/dev/null 2>&1 || die "openssl is needed to generate secrets"
  if [[ ! -f "$ENV_FILE" ]]; then
    cp "$KIT_DIR/.env.example" "$ENV_FILE"
    log "created $ENV_FILE from .env.example"
  fi
  chmod 600 "$ENV_FILE" 2>/dev/null || true
  for k in ORQIX_OIDC_CLIENT_SECRET KEYCLOAK_ADMIN_PASSWORD POSTGRES_SUPERUSER_PASSWORD ORQIX_MIGRATOR_PASSWORD \
           ORQIX_APP_PASSWORD KEYCLOAK_DB_PASSWORD ORQIX_CURSOR_SECRET; do
    if [[ -z "$(env_get "$k")" ]]; then
      len=40; [[ "$k" == ORQIX_CURSOR_SECRET ]] && len=64
      env_set "$k" "$(rand "$len")"
      log "generated $k"
    fi
  done
  log "edit ORQIX_PUBLIC_URL, ORQIX_IMAGE and the ports in $ENV_FILE, put tls.crt/tls.key in certs/, then run scripts/install.sh"
  exit 0
fi

require_env_file
if ! $SKIP_PREFLIGHT; then
  if [[ -n "$(container_of orqix)" ]]; then "$KIT_DIR/scripts/preflight.sh" --running
  else "$KIT_DIR/scripts/preflight.sh"; fi
fi

mkdir -p "$(abs_path "$(env_get BACKUP_DIR ./backups)")"
log "starting project $(project) with $(env_get ORQIX_IMAGE)"
# --pull never: an air-gapped host must not try to reach a registry; load images first.
dc up -d --pull never
keycloak_enabled && { wait_healthy keycloak 420 || die "Keycloak did not become healthy: $(basename "$0") logs: docker compose logs keycloak"; }
mig="$(container_of orqix-migrate)"
if [[ -n "$mig" ]]; then
  code="$(docker inspect -f '{{.State.ExitCode}}' "$mig")"
  [[ "$code" == "0" ]] || warn "migration job exit code $code"
fi
wait_healthy orqix 420 || die "Orqix did not become healthy; see: docker compose -p $(project) logs orqix-migrate orqix"
wait_healthy proxy 120 || die "proxy did not become healthy; see logs of service proxy"
"$KIT_DIR/scripts/health.sh"
log "schema version $(schema_version)"
log "open $(env_get ORQIX_PUBLIC_URL)/  (first user: scripts/add-user.sh)"
