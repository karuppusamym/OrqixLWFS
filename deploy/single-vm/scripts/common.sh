# Shared helpers for the single-VM kit scripts. Sourced, not executed.
# shellcheck shell=bash

set -euo pipefail

# Absolute path of the current directory. Git Bash: C:/... form, which native Windows programs (docker.exe) understand.
native_pwd() { pwd -W 2>/dev/null || pwd; }
KIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && native_pwd)"
ENV_FILE="${ENV_FILE:-$KIT_DIR/.env}"
# Git Bash on Windows rewrites arguments that look like paths (/var/lib/...); harmless on Linux.
export MSYS_NO_PATHCONV=1

log()  { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
warn() { printf '%s WARN %s\n' "$(date -u +%H:%M:%SZ)" "$*" >&2; }
die()  { printf '%s ERROR %s\n' "$(date -u +%H:%M:%SZ)" "$*" >&2; exit 1; }

# Value of KEY in the env file (last assignment wins), without sourcing it: values may contain spaces.
env_get() {
  local key="$1" default="${2-}" line
  line="$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
  if [[ -z "$line" ]]; then printf '%s' "$default"; return; fi
  line="${line#*=}"
  line="${line%$'\r'}"
  line="${line#\"}"; line="${line%\"}"
  printf '%s' "${line:-$default}"
}

# Set KEY=VALUE in the env file (replaces the existing line or appends one).
env_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k {print k "=" v; next} {print}' "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"; printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
}

require_env_file() {
  [[ -f "$ENV_FILE" ]] || die "no $ENV_FILE: copy .env.example to .env first (or run scripts/install.sh --generate-secrets)"
}

project() { env_get COMPOSE_PROJECT_NAME orqix; }

# docker compose bound to this kit, its env file and its project name.
dc() {
  COMPOSE_PROFILES="$(env_get COMPOSE_PROFILES)" \
    docker compose --project-directory "$KIT_DIR" --env-file "$ENV_FILE" -f "$KIT_DIR/compose.yaml" \
    -p "$(project)" "$@"
}

keycloak_enabled() { [[ ",$(env_get COMPOSE_PROFILES)," == *",keycloak,"* ]]; }

container_of() { dc ps -q "$1" 2>/dev/null | head -n 1; }

health_of() {
  local id; id="$(container_of "$1")"
  [[ -n "$id" ]] || { echo "absent"; return; }
  docker inspect -f '{{if ne .State.Status "running"}}{{.State.Status}}{{else if .State.Health}}{{.State.Health.Status}}{{else}}running{{end}}' "$id"
}

restarts_of() {
  local id; id="$(container_of "$1")"
  [[ -n "$id" ]] && docker inspect -f '{{.RestartCount}}' "$id" || echo 0
}

# wait_healthy SERVICE [TIMEOUT_SECONDS]
wait_healthy() {
  local svc="$1" timeout="${2:-300}" waited=0 state restarts
  restarts="$(restarts_of "$svc")"
  while (( waited < timeout )); do
    state="$(health_of "$svc")"
    # A container that crashed and was restarted by Docker during the wait is a failed start (crash loop).
    (( $(restarts_of "$svc") > restarts )) && { warn "$svc restarted while starting (crash loop?)"; return 1; }
    [[ "$state" == "healthy" ]] && { log "$svc is healthy (${waited}s)"; return 0; }
    case "$state" in unhealthy|exited|dead|restarting) warn "$svc is $state"; return 1 ;; esac
    sleep 5; waited=$((waited + 5))
  done
  warn "$svc not healthy after ${timeout}s (state: $(health_of "$svc"))"
  return 1
}

psql_su() { dc exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -At "$@"; }

# Highest applied Flyway migration of schema orqix, or "none".
schema_version() {
  psql_su -d orqix -c "SELECT COALESCE(max(installed_rank)::text || ':' || (SELECT version FROM orqix.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1), 'none') FROM orqix.flyway_schema_history WHERE success" 2>/dev/null || echo none
}

# Tables whose row counts are written to a backup's MANIFEST and compared after a restore.
# shellcheck disable=SC2034  # used by backup.sh
VERIFY_TABLES="orqix.workflow orqix.workflow_release orqix.workflow_run orqix.run_event orqix.task_decision orqix.audit_event orqix.run_attachment_content flowable.act_hi_procinst flowable.act_hi_taskinst"

sha256_file() { sha256sum "$1" | awk '{print $1}'; }

utc_stamp() { date -u +%Y%m%dT%H%M%SZ; }

abs_path() {
  case "$1" in
    /*|[A-Za-z]:*) printf '%s' "$1" ;;
    *) printf '%s' "$KIT_DIR/${1#./}" ;;
  esac
}
