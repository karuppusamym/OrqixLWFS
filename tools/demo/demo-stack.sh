#!/usr/bin/env bash
# Stand up (or restart) an ISOLATED Orqix demo stack from the current checkout: one command, repeatable.
#
#   tools/demo/demo-stack.sh up   <tag> [port-base]   build the images if missing (REBUILD=1 forces), (re)create the
#                                                      containers, wait until healthy, register the redirect URI
#   tools/demo/demo-stack.sh seed <tag> [port-base]   add the demo requests (safe to repeat)
#   tools/demo/demo-stack.sh start|stop <tag>         restart / stop the containers, keeping the database volume
#   tools/demo/demo-stack.sh down <tag>               remove containers AND this stack's database volume (asks nothing: be sure)
#
# Example:  tools/demo/demo-stack.sh up demo-1026 38000   ->  app http://localhost:38080, Keycloak http://localhost:38180
#
# It uses its own Compose project name (orqix-<tag>), its own volume, its own image tags (orqix/orqix:<tag> and
# orqix/procurement-stub:<tag>) and its own host ports, so it cannot disturb any other stack on the machine; it refuses to
# start when a port is taken. DEVELOPMENT ONLY: it uses the documented dev credentials of deploy/compose and the dev realm.
set -euo pipefail

ACTION="${1:?usage: demo-stack.sh up|seed|start|stop|down <tag> [port-base]}"
TAG="${2:?give a tag such as demo-1026}"
BASE="${3:-38000}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="$ROOT/deploy/compose"
PROJECT="orqix-$TAG"
APP_PORT=$((BASE + 80))
MGMT_PORT=$((BASE + 81))
KC_PORT=$((BASE + 180))
STUB_PORT=$((BASE + 95))
PG_PORT=$((BASE + 432))

case "$TAG" in *[!a-z0-9.-]*) echo "tag may only contain lowercase letters, digits, dots and dashes" >&2; exit 2 ;; esac

export ORQIX_IMAGE="orqix/orqix:$TAG"
export ORQIX_HOST_PORT="$APP_PORT" ORQIX_MANAGEMENT_HOST_PORT="$MGMT_PORT" KEYCLOAK_HOST_PORT="$KC_PORT"
export STUB_HOST_PORT="$STUB_PORT" POSTGRES_HOST_PORT="$PG_PORT"

# The override lives in the git-ignored evidence/ folder (not /tmp: the Windows docker binary cannot open Git Bash temp paths).
OVERRIDE_DIR="$ROOT/evidence/demo-stack-$TAG"
mkdir -p "$OVERRIDE_DIR"
# The shared compose file tags the stub image orqix/procurement-stub:dev; give this stack its own tag so another stack's
# image is never replaced.
cat > "$OVERRIDE_DIR/override.yml" <<EOF
services:
  procurement-stub:
    image: orqix/procurement-stub:$TAG
EOF

OVERRIDE_FILE="$OVERRIDE_DIR/override.yml"
if command -v cygpath >/dev/null 2>&1; then OVERRIDE_FILE="$(cygpath -m "$OVERRIDE_FILE")"; fi

# EXTRA_COMPOSE=<file> adds one more Compose file (for example tools/demo/integration.override.yml: Kafka and a webhook sink).
EXTRA_ARGS=()
if [ -n "${EXTRA_COMPOSE:-}" ]; then
  EXTRA_FILE="$(cd "$(dirname "$EXTRA_COMPOSE")" && pwd)/$(basename "$EXTRA_COMPOSE")"
  if command -v cygpath >/dev/null 2>&1; then EXTRA_FILE="$(cygpath -m "$EXTRA_FILE")"; fi
  EXTRA_ARGS=(-f "$EXTRA_FILE")
fi
export HOOKSINK_HOST_PORT="$((BASE + 99))"

dc() { (cd "$COMPOSE_DIR" && MSYS_NO_PATHCONV=1 docker compose -p "$PROJECT" -f docker-compose.yml -f "$OVERRIDE_FILE" ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} --profile app "$@"); }

port_free() {
  python - "$1" <<'PY'
import socket, sys
s = socket.socket()
s.settimeout(1)
busy = s.connect_ex(("127.0.0.1", int(sys.argv[1]))) == 0
s.close()
sys.exit(1 if busy else 0)
PY
}

wait_for() { # url, label, seconds
  local url="$1" label="$2" limit="$3" waited=0
  until curl -sf -m 3 -o /dev/null "$url"; do
    waited=$((waited + 5))
    if [ "$waited" -ge "$limit" ]; then echo "TIMEOUT waiting for $label ($url)" >&2; return 1; fi
    sleep 5
  done
  echo "ready: $label"
}

register_redirect() {
  # The dev realm only knows http://localhost:8080; add this stack's app URL to the orqix-web client through the
  # Keycloak admin API. The repository's realm file is not changed.
  python - "$KC_PORT" "$APP_PORT" <<'PY'
import json, sys, urllib.parse, urllib.request
kc, app = sys.argv[1], sys.argv[2]
base = f"http://localhost:{kc}"
def call(method, path, data=None, headers=None, raw=False):
    req = urllib.request.Request(base + path, method=method, headers=headers or {})
    if data is not None:
        body = data if raw else json.dumps(data).encode()
        req.data = body
    with urllib.request.urlopen(req, timeout=90) as r:
        text = r.read()
        return json.loads(text) if text else None
import time
def retry(fn, attempts=6):
    for i in range(attempts):
        try:
            return fn()
        except Exception as exc:  # Keycloak is slow for a while after start; try again before giving up
            if i == attempts - 1:
                raise
            print(f"Keycloak admin API not ready ({type(exc).__name__}); retrying", file=sys.stderr)
            time.sleep(10)
form = urllib.parse.urlencode({"client_id": "admin-cli", "grant_type": "password", "username": "admin", "password": "admin-dev-only"}).encode()
token = retry(lambda: call("POST", "/realms/master/protocol/openid-connect/token", form, {"Content-Type": "application/x-www-form-urlencoded"}, raw=True))["access_token"]
auth = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
clients = retry(lambda: call("GET", "/admin/realms/orqix-dev/clients?clientId=orqix-web", headers=auth))
client = clients[0]
changed = False
redirect = f"http://localhost:{app}/login/oauth2/code/orqix"
if redirect not in client.get("redirectUris", []):
    client["redirectUris"] = client.get("redirectUris", []) + [redirect]; changed = True
origin = f"http://localhost:{app}"
if origin not in client.get("webOrigins", []):
    client["webOrigins"] = client.get("webOrigins", []) + [origin]; changed = True
attrs = client.setdefault("attributes", {})
logout = attrs.get("post.logout.redirect.uris", "")
entry = f"http://localhost:{app}/*"
if entry not in logout.split("##"):
    attrs["post.logout.redirect.uris"] = (logout + "##" + entry) if logout else entry; changed = True
if changed:
    retry(lambda: call("PUT", f"/admin/realms/orqix-dev/clients/{client['id']}", client, auth))
print("redirect URI registered" if changed else "redirect URI already registered")
PY
}

case "$ACTION" in
  up)
    for p in "$APP_PORT" "$MGMT_PORT" "$KC_PORT" "$STUB_PORT" "$PG_PORT"; do
      if ! port_free "$p"; then
        # A port in use by THIS project is fine (re-running up); anything else is a conflict.
        owner="$(docker ps --filter "publish=$p" --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null | head -1)"
        if [ "$owner" != "$PROJECT" ]; then echo "port $p is in use by '${owner:-another program}'; pick another port base" >&2; exit 3; fi
      fi
    done
    if [ "${REBUILD:-}" = 1 ] || ! docker image inspect "$ORQIX_IMAGE" >/dev/null 2>&1         || ! docker image inspect "orqix/procurement-stub:$TAG" >/dev/null 2>&1; then
      echo "building images for $PROJECT from the current checkout (several minutes)"
      dc build
    else
      echo "images for $TAG already exist; reusing them (REBUILD=1 forces a rebuild from the current checkout)"
    fi
    dc up -d --remove-orphans
    wait_for "http://localhost:$KC_PORT/realms/orqix-dev/.well-known/openid-configuration" "Keycloak" 300
    register_redirect
    wait_for "http://localhost:$MGMT_PORT/actuator/health" "Orqix app" 300
    echo
    echo "Demo stack '$PROJECT' is up:"
    echo "  app        http://localhost:$APP_PORT"
    echo "  keycloak   http://localhost:$KC_PORT"
    echo "  stub       http://localhost:$STUB_PORT"
    echo "  next:      tools/demo/demo-stack.sh seed $TAG $BASE   (demo requests), then follow docs/demo-guide.md"
    echo "  users and dev passwords: deploy/compose/keycloak/orqix-dev-realm.json"
    ;;
  seed)
    ORQIX_BASE_URL="http://localhost:$APP_PORT" ORQIX_STUB_URL="http://localhost:$STUB_PORT" python "$ROOT/tools/demo/seed_demo.py"
    ;;
  start)
    dc start
    wait_for "http://localhost:$MGMT_PORT/actuator/health" "Orqix app" 300
    ;;
  stop)
    dc stop
    ;;
  down)
    dc down -v
    ;;
  *)
    echo "unknown action '$ACTION'" >&2; exit 2 ;;
esac
