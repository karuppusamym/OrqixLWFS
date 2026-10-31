#!/usr/bin/env bash
# Preflight check for the single-VM kit: host resources, Docker, ports, configuration, certificate and images.
# Exit 0 = ready (warnings allowed), 1 = at least one failure. Runs nothing that changes the host.
#
#   scripts/preflight.sh            # full check
#   scripts/preflight.sh --running  # skip the "port is free" checks (the stack is already up, e.g. before an upgrade)
source "$(dirname "$0")/common.sh"

RUNNING=false
[[ "${1-}" == "--running" ]] && RUNNING=true

MIN_CPU=2; REC_CPU=4
MIN_RAM_MB=4096; REC_RAM_MB=8192
MIN_DISK_GB=20; REC_DISK_GB=50
MIN_DOCKER=24.0; MIN_COMPOSE=2.20

fails=0; warns=0
ok()   { printf '  [ OK ] %s\n' "$*"; }
bad()  { printf '  [FAIL] %s\n' "$*"; fails=$((fails + 1)); }
note() { printf '  [WARN] %s\n' "$*"; warns=$((warns + 1)); }
ver_ge() { [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n 1)" == "$2" ]]; }

echo "Host"
cpus="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
if (( cpus < MIN_CPU )); then bad "CPU cores: $cpus (minimum $MIN_CPU)"
elif (( cpus < REC_CPU )); then note "CPU cores: $cpus (recommended $REC_CPU)"
else ok "CPU cores: $cpus"; fi

ram_mb=$(( $(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0) / 1024 ))
if (( ram_mb < MIN_RAM_MB )); then bad "RAM: ${ram_mb} MiB (minimum $MIN_RAM_MB)"
elif (( ram_mb < REC_RAM_MB )); then note "RAM: ${ram_mb} MiB (recommended $REC_RAM_MB)"
else ok "RAM: ${ram_mb} MiB"; fi

disk_check() {  # disk_check LABEL PATH
  local free_gb
  free_gb=$(( $(df -Pk "$2" 2>/dev/null | awk 'NR==2 {print $4}' || echo 0) / 1024 / 1024 ))
  if (( free_gb < MIN_DISK_GB )); then bad "$1 free disk: ${free_gb} GiB at $2 (minimum $MIN_DISK_GB)"
  elif (( free_gb < REC_DISK_GB )); then note "$1 free disk: ${free_gb} GiB at $2 (recommended $REC_DISK_GB)"
  else ok "$1 free disk: ${free_gb} GiB at $2"; fi
}

echo "Docker"
if ! command -v docker >/dev/null 2>&1; then
  bad "docker not found"
else
  dver="$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)"
  if [[ -z "$dver" ]]; then bad "Docker daemon not reachable (is it running, is this user in the docker group?)"
  elif ver_ge "$dver" "$MIN_DOCKER"; then ok "Docker Engine $dver"
  else bad "Docker Engine $dver (minimum $MIN_DOCKER)"; fi
  cver="$(docker compose version --short 2>/dev/null | sed 's/^v//; s/-.*//' || true)"
  if [[ -z "$cver" ]]; then bad "Docker Compose v2 plugin not found (docker compose)"
  elif ver_ge "$cver" "$MIN_COMPOSE"; then ok "Docker Compose $cver"
  else bad "Docker Compose $cver (minimum $MIN_COMPOSE)"; fi
  root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || true)"
  if [[ -n "$root" && -d "$root" ]]; then disk_check "Docker data" "$root"
  else note "Docker data root ${root:-unknown} not visible from this shell (Docker Desktop VM?): check its disk yourself"; fi
fi

echo "Configuration ($ENV_FILE)"
if [[ ! -f "$ENV_FILE" ]]; then
  bad "missing: cp .env.example .env (or scripts/install.sh --generate-secrets)"
else
  perms="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || echo '?')"
  [[ "$perms" == "600" || "$perms" == "400" || "$perms" == "?" ]] && ok ".env permissions $perms" \
    || note ".env permissions $perms: run chmod 600 .env (it holds every secret)"
  for k in ORQIX_IMAGE ORQIX_PUBLIC_URL ORQIX_OIDC_CLIENT_SECRET ORQIX_CURSOR_SECRET POSTGRES_SUPERUSER_PASSWORD \
           ORQIX_MIGRATOR_PASSWORD ORQIX_APP_PASSWORD; do
    v="$(env_get "$k")"
    if [[ -z "$v" ]]; then bad "$k is empty"
    elif [[ "$v" == *REPLACE* ]]; then bad "$k still contains REPLACE"; fi
  done
  if keycloak_enabled; then
    for k in KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASSWORD; do [[ -n "$(env_get "$k")" ]] || bad "$k is empty"; done
  else
    for k in ORQIX_OIDC_ISSUER ORQIX_OIDC_AUTHORIZATION_URI ORQIX_OIDC_TOKEN_URI ORQIX_OIDC_JWK_SET_URI ORQIX_OIDC_USER_INFO_URI; do
      [[ -n "$(env_get "$k")" ]] || bad "$k is empty (external IdP: COMPOSE_PROFILES has no keycloak)"
    done
    [[ -n "$(env_get KEYCLOAK_DB_PASSWORD)" ]] || bad "KEYCLOAK_DB_PASSWORD is empty (still required: the database init creates the role)"
  fi
  url="$(env_get ORQIX_PUBLIC_URL)"
  [[ "$url" == https://* ]] && ok "ORQIX_PUBLIC_URL $url" || bad "ORQIX_PUBLIC_URL must start with https:// ($url)"
  [[ "$url" == */ ]] && bad "ORQIX_PUBLIC_URL must not end with /"
  cs="$(env_get ORQIX_CURSOR_SECRET)"; (( ${#cs} >= 32 )) || bad "ORQIX_CURSOR_SECRET shorter than 32 characters"
  [[ "$(env_get BACKEND_INTERNAL true)" == "true" ]] && ok "backend network is internal (no outbound route)" \
    || note "BACKEND_INTERNAL=false: containers can open outbound connections (allowlist: ORQIX_EGRESS_ALLOWED_HOSTS='$(env_get ORQIX_EGRESS_ALLOWED_HOSTS)')"
  [[ -z "$(env_get OPENROUTER_API_KEY)" ]] && ok "AI assistance off (OPENROUTER_API_KEY empty)" \
    || note "AI assistance ON: prompts are sent to $(env_get ORQIX_AI_BASE_URL https://openrouter.ai/api/v1)"
  disk_check "Backup" "$(abs_path "$(env_get BACKUP_DIR ./backups)")" 2>/dev/null || true

  echo "Ports"
  if $RUNNING; then
    ok "skipped (--running)"
  else
    for spec in "HTTPS:443:0.0.0.0" "HTTP:80:0.0.0.0" "MANAGEMENT:8081:127.0.0.1"; do
      IFS=: read -r n dp db <<< "$spec"
      k="${n}_PORT"; p="$(env_get "$k" "$dp")"; b="$(env_get "${n}_BIND" "$db")"; [[ "$b" == "0.0.0.0" ]] && b=127.0.0.1
      # Something answering a TCP connect on the address means the port is taken.
      if (exec 3<>"/dev/tcp/$b/$p") 2>/dev/null; then bad "$k $p is already in use on $b"
      else ok "$k $p is free"; fi
    done
  fi

  echo "TLS certificate ($(env_get TLS_CERT_DIR ./certs))"
  cdir="$(abs_path "$(env_get TLS_CERT_DIR ./certs)")"
  if [[ ! -f "$cdir/tls.crt" || ! -f "$cdir/tls.key" ]]; then
    bad "need $cdir/tls.crt and $cdir/tls.key (bring your own; scripts/dev-cert.sh for a test certificate)"
  elif command -v openssl >/dev/null 2>&1; then
    host="${url#https://}"; host="${host%%/*}"; host="${host%%:*}"
    if ! openssl x509 -noout < "$cdir/tls.crt" >/dev/null 2>&1; then bad "tls.crt is not a PEM certificate"
    else
      openssl x509 -noout -checkend 0 < "$cdir/tls.crt" >/dev/null 2>&1 && ok "certificate not expired" || bad "certificate expired"
      openssl x509 -noout -checkend 2592000 < "$cdir/tls.crt" >/dev/null 2>&1 || note "certificate expires within 30 days"
      cmod="$(openssl x509 -noout -pubkey < "$cdir/tls.crt" 2>/dev/null | openssl sha256)"
      kmod="$(openssl pkey -pubout < "$cdir/tls.key" 2>/dev/null | openssl sha256)"
      [[ "$cmod" == "$kmod" ]] && ok "private key matches the certificate" || bad "tls.key does not match tls.crt"
      if openssl x509 -noout -text < "$cdir/tls.crt" 2>/dev/null | grep -A1 'Subject Alternative Name' | grep -qiE "DNS:${host}([, ]|$)|IP Address:${host}([, ]|$)"; then
        ok "certificate covers $host"
      else bad "certificate has no subjectAltName for $host"; fi
    fi
  else
    note "openssl not installed: certificate not checked"
  fi

  echo "Images (must be present locally on an air-gapped host: scripts/load-images.sh)"
  imgs=("$(env_get ORQIX_IMAGE)" "$(env_get POSTGRES_IMAGE postgres:18-alpine)" "$(env_get PROXY_IMAGE nginx:1.27-alpine)")
  keycloak_enabled && imgs+=("$(env_get KEYCLOAK_IMAGE quay.io/keycloak/keycloak:26.8.0)")
  for i in "${imgs[@]}"; do
    if docker image inspect "$i" >/dev/null 2>&1; then ok "$i"; else bad "$i not loaded"; fi
  done
fi

echo
if (( fails > 0 )); then echo "Preflight: $fails failure(s), $warns warning(s)"; exit 1; fi
echo "Preflight: ready ($warns warning(s))"
