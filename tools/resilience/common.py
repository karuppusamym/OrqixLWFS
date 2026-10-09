"""Shared helpers of the Docker resilience scenarios (T-RES-03/04/05, T-SEC-09).

Everything here talks to the local Compose project "orqix" only: containers are resolved through Compose labels, and
no password or response body is printed. Sessions come from tools/api-e2e/orqix_client.py (real Keycloak login).
"""
import json
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))
from orqix_client import Client  # noqa: E402

COMPOSE_DIR = ROOT / "deploy" / "compose"
EVIDENCE = ROOT / "evidence" / "resilience"
STUB = "http://localhost:8095"
OVERLAY = ["docker", "compose", "-f", str(COMPOSE_DIR / "docker-compose.yml"),
           "-f", str(COMPOSE_DIR / "docker-compose.resilience.yml"), "--project-name", "orqix", "--profile", "app"]

INSTANCES = {
    "orqix": {"api": "http://localhost:8080", "mgmt": "http://localhost:8081"},
    "orqix-2": {"api": "http://localhost:8082", "mgmt": "http://localhost:8083"},
}


def now():
    return datetime.now(timezone.utc)


def iso(moment=None):
    return (moment or now()).isoformat(timespec="milliseconds")


def sh(args, timeout=180, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True, timeout=timeout)


def compose(*args, timeout=180, check=True):
    return sh([*OVERLAY, *args], timeout=timeout, check=check)


def container(service):
    cid = compose("ps", "-a", "-q", service).stdout.strip()
    if not cid:
        raise AssertionError(f"service {service} has no container")
    labels = json.loads(sh(["docker", "inspect", cid]).stdout)[0]["Config"]["Labels"]
    if labels.get("com.docker.compose.project") != "orqix" or labels.get("com.docker.compose.service") != service:
        raise AssertionError(f"container {cid[:12]} is not the orqix/{service} service")
    return cid


def hostname(service):
    """Container hostname = lock owner and lease owner of that instance (orqix.runtime.lock-owner = ${HOSTNAME})."""
    return sh(["docker", "inspect", "-f", "{{.Config.Hostname}}", container(service)]).stdout.strip()


def container_state(service):
    return json.loads(sh(["docker", "inspect", container(service)]).stdout)[0]["State"]


def psql(sql, timeout=60):
    """Rows of a query as lists of strings (superuser through the container's local socket; read-only use)."""
    out = sh(["docker", "exec", container("postgres"), "psql", "-U", "postgres", "-d", "orqix", "-At", "-F", "|",
              "-c", sql], timeout=timeout).stdout.strip()
    return [line.split("|") for line in out.splitlines() if line]


def ready(service, timeout=150):
    """Waits for readiness of an instance; returns seconds waited."""
    started = time.monotonic()
    url = INSTANCES[service]["mgmt"] + "/actuator/health/readiness"
    while time.monotonic() - started < timeout:
        try:
            r = requests.get(url, timeout=3)
            if r.status_code == 200 and r.json().get("status") == "UP":
                return time.monotonic() - started
        except (requests.RequestException, ValueError):
            pass
        time.sleep(0.5)
    raise AssertionError(f"{service} not ready within {timeout} s")


def ensure_stack(second_instance=True):
    """Both instances up and ready (starts the second one from the overlay when needed)."""
    services = ["postgres", "orqix"] + (["orqix-2"] if second_instance else [])
    compose("up", "-d", "--no-build", "--no-recreate", *services, timeout=300)
    for service in services[1:]:
        ready(service)


def stub_reset():
    requests.post(f"{STUB}/__reset", timeout=10).raise_for_status()


def stub_fault(mode, times=None, delay_ms=None):
    body = {"mode": mode}
    if times is not None:
        body["times"] = times
    if delay_ms is not None:
        body["delayMs"] = delay_ms
    requests.post(f"{STUB}/__faults", json=body, timeout=10).raise_for_status()


def stub_orders():
    return requests.get(f"{STUB}/__orders", timeout=10).json()


class Api:
    """A signed-in session that can address any instance (sessions are in the database, no sticky routing)."""

    def __init__(self, username):
        self.username = username
        self.client = Client(username)

    def get(self, base, path, timeout=45, **params):
        return self.client.s.get(f"{base}/api/v1{path}", params=params, headers=self.client._headers(),
                                 timeout=timeout)

    def post(self, base, path, body=None, idem=False, key=None, timeout=60):
        k = key or (str(uuid.uuid4()) if idem else None)
        return self.client.s.post(f"{base}/api/v1{path}", json=body or {}, headers=self.client._headers(k),
                                  timeout=timeout)

    def put(self, base, path, body, if_match=None, timeout=45):
        return self.client.s.put(f"{base}/api/v1{path}", json=body, headers=self.client._headers(if_match=if_match),
                                 timeout=timeout)

    def delete(self, base, path, timeout=45):
        return self.client.s.delete(f"{base}/api/v1{path}", headers=self.client._headers(), timeout=timeout)


def ok(result, expected, what):
    if result.status_code != expected:
        raise AssertionError(f"{what}: expected {expected}, got {result.status_code}")
    return result.json() if result.content else {}


def purchasing_workspace(api, base):
    return next(w["id"] for w in ok(api.get(base, "/me"), 200, "me")["workspaces"] if w["key"] == "purchasing")


def publish_purchase(designer, publisher, base, ws, prefix, http_timeout=None):
    """New workflow from the purchase template (HTTP timeout optionally patched), published; returns the id."""
    key = f"{prefix}-{uuid.uuid4().hex[:8]}"
    wf = ok(designer.post(base, f"/workspaces/{ws}/workflows", {
        "key": key, "name": f"{prefix} {key}", "templateId": "purchase-approval"}), 201, "create workflow")["id"]
    draft = ok(designer.get(base, f"/workspaces/{ws}/workflows/{wf}/draft"), 200, "read draft")
    revision = draft["revision"]
    if http_timeout is not None:
        definition = draft["definition"]
        for node in definition["nodes"]:
            if node["type"] == "http":
                node["config"]["timeoutSeconds"] = http_timeout
        saved = ok(designer.put(base, f"/workspaces/{ws}/workflows/{wf}/draft", {"definition": definition},
                                if_match=f'"r{revision}"'), 200, "save draft")
        revision = saved["revision"]
    ok(publisher.post(base, f"/workspaces/{ws}/workflows/{wf}/publish", {
        "draftRevision": revision, "acknowledgeWarnings": True}, idem=True), 201, "publish")
    return wf


def start_run(api, base, ws, wf, amount=1800, title="Resilience check"):
    return ok(api.post(base, f"/workspaces/{ws}/runs", {"workflowId": wf, "input": {
        "title": title, "purpose": "resilience", "costCenter": "OPS", "amount": amount}}, idem=True), 201, "start")


def open_task(api, base, ws, run_id, node="manager"):
    run = ok(api.get(base, f"/workspaces/{ws}/runs/{run_id}"), 200, "run")
    return next((t for t in run["tasks"] if t["nodeId"] == node and t["status"] == "open"), None)


def claim_and_approve(api, base, ws, task):
    """Claim and approve through one instance; returns (claim status, decision status)."""
    claimed = api.post(base, f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]})
    if claimed.status_code != 200:
        return claimed.status_code, None
    decided = api.post(base, f"/workspaces/{ws}/tasks/{task['id']}/decision", {
        "expectedVersion": claimed.json()["version"], "action": "approve"}, idem=True)
    return 200, decided.status_code


def wait_run(api, base, ws, run_id, until, timeout=240, interval=1.0):
    deadline = time.monotonic() + timeout
    run = None
    while time.monotonic() < deadline:
        try:
            r = api.get(base, f"/workspaces/{ws}/runs/{run_id}", timeout=15)
            if r.status_code == 200:
                run = r.json()
                if until(run):
                    return run
        except requests.RequestException:
            pass
        time.sleep(interval)
    return run


def run_events(api, base, ws, run_id):
    events, after = [], 0
    while True:
        page = ok(api.get(base, f"/workspaces/{ws}/runs/{run_id}/events", limit=100, after=after), 200, "events")
        events.extend(page["items"])
        if not page.get("hasMore") or not page["items"]:
            return events
        after = page["items"][-1]["seq"]


class Report:
    """Collects checks and measurements of one scenario and writes evidence/resilience/<test>.json."""

    def __init__(self, test, description):
        self.test = test
        self.data = {"test": test, "description": description, "startedAt": iso(), "checks": [], "measurements": {}}
        self.failed = False
        print(f"=== {test}: {description}", flush=True)

    def check(self, condition, message):
        self.data["checks"].append({"check": message, "result": "pass" if condition else "fail"})
        print(("PASS: " if condition else "FAIL: ") + message, flush=True)
        if not condition:
            self.failed = True
        return bool(condition)

    def measure(self, name, value):
        self.data["measurements"][name] = value
        print(f"  {name}: {value}", flush=True)

    def finish(self):
        self.data["finishedAt"] = iso()
        self.data["result"] = "failed" if self.failed else "passed"
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        path = EVIDENCE / f"{self.test}.json"
        path.write_text(json.dumps(self.data, indent=2), encoding="utf-8")
        print(f"RESULT {self.test}: {self.data['result']} ({path.relative_to(ROOT)})", flush=True)
        return 1 if self.failed else 0
