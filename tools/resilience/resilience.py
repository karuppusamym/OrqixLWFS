"""Resilience scenarios against the local Compose stack and real Keycloak/PostgreSQL.

T-RES-01 (restart) is below; T-RES-03/04/05 and T-SEC-09 live in scenarios.py.

Only the development 'orqix' service is stopped; other projects and the database are never stopped.
No passwords or response bodies are printed. A finally block restarts the service on failure.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

import requests

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))
from orqix_client import Client  # noqa: E402

COMPOSE = ["docker", "compose", "-f", str(ROOT / "deploy" / "compose" / "docker-compose.yml"),
           "--project-name", "orqix", "--profile", "app"]
READINESS = "http://localhost:8081/actuator/health/readiness"


def require(condition, message):
    if not condition:
        raise AssertionError(message)
    print("PASS: " + message, flush=True)


def response(result, expected, operation):
    if result.status_code != expected:
        raise AssertionError(f"{operation}: expected {expected}, received {result.status_code}")
    return result.json()


def compose(*args):
    return subprocess.run([*COMPOSE, *args], check=True, capture_output=True, text=True, timeout=120)


def ready(timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = requests.get(READINESS, timeout=3)
            if result.status_code == 200 and result.json().get("status") == "UP":
                return
        except (requests.RequestException, ValueError):
            pass
        time.sleep(1)
    raise AssertionError("application did not become ready before the deadline")


def page_items(client, path, **filters):
    cursor = None
    while True:
        params = {**filters, "limit": 100}
        if cursor:
            params["cursor"] = cursor
        page = response(client.get(path, **params), 200, "read list page")
        yield from page["items"]
        cursor = page.get("nextCursor")
        if not cursor:
            break


def restart():
    # Explicit label checks prevent accidentally touching an unrelated application's container.
    container = compose("ps", "-q", "orqix").stdout.strip()
    if not container:
        raise AssertionError("local Orqix application service is not running")
    inspected = subprocess.run(["docker", "inspect", container], check=True, capture_output=True,
                               text=True, timeout=30)
    labels = json.loads(inspected.stdout)[0]["Config"]["Labels"]
    require(labels.get("com.docker.compose.project") == "orqix"
            and labels.get("com.docker.compose.service") == "orqix", "target is the local Orqix app service")
    ready()
    designer, publisher, requester, manager, operator = [Client(u) for u in
        ("dana.designer", "paul.publisher", "rhea.requester", "mani.manager", "olga.operator")]
    ws = next(w["id"] for w in requester.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    key = "restart-" + uuid.uuid4().hex[:10]
    workflow = response(designer.post(f"/workspaces/{ws}/workflows", {
        "key": key, "name": "Restart acceptance " + key, "templateId": "purchase-approval"}), 201, "create workflow")
    wf = workflow["id"]
    release = response(publisher.post(f"/workspaces/{ws}/workflows/{wf}/publish", {
        "draftRevision": 1, "acknowledgeWarnings": True}, idem=True), 201, "publish workflow")
    started = response(requester.post(f"/workspaces/{ws}/runs", {
        "workflowId": wf, "input": {"title": "Restart persistence check", "purpose": "T-RES-01",
                                   "costCenter": "OPS", "amount": 1800}}, idem=True), 201, "start run")
    run_id = started["id"]
    before = response(operator.get(f"/workspaces/{ws}/runs/{run_id}"), 200, "read initial run")
    task = next(t for t in before["tasks"] if t["nodeId"] == "manager" and t["status"] == "open")
    claimed = response(manager.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {
        "expectedVersion": task["version"]}), 200, "claim task")
    require(any(t["id"] == task["id"] for t in page_items(manager, f"/workspaces/{ws}/tasks", view="assigned")),
            "claimed task appears in assigned inbox before shutdown")
    stopped = False
    shutdown_started = time.monotonic()
    try:
        stopped = True  # also restore if a stop times out after reaching Docker
        compose("stop", "-t", "30", "orqix")
        require(not compose("ps", "--status", "running", "-q", "orqix").stdout.strip(),
                "application process stopped gracefully")
        shutdown_ms = round((time.monotonic() - shutdown_started) * 1000)
        startup_started = time.monotonic()
        compose("start", "orqix")
        ready()
        stopped = False
        startup_ms = round((time.monotonic() - startup_started) * 1000)
        # Existing JDBC sessions are used first: persisted login and CSRF state must survive too.
        after = response(operator.get(f"/workspaces/{ws}/runs/{run_id}"), 200, "read run after restart")
        task_after = response(manager.get(f"/workspaces/{ws}/tasks/{task['id']}"), 200, "read claimed task after restart")
        require(after["lifecycle"] == "waiting", "waiting run survives restart")
        require(after["release"]["id"] == release["id"], "run stays pinned to its published release")
        require(task_after["version"] == claimed["version"] and task_after["assignee"] == claimed["assignee"],
                "claim and task version survive restart")
        require(any(t["id"] == task["id"] for t in page_items(manager, f"/workspaces/{ws}/tasks", view="assigned")),
                "assigned inbox entry survives restart")
        response(manager.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", {
            "expectedVersion": task_after["version"], "action": "approve", "comment": "Verified after restart"},
            idem=True), 201, "approve persisted task")
        deadline = time.monotonic() + 120
        done = None
        while time.monotonic() < deadline:
            done = response(operator.get(f"/workspaces/{ws}/runs/{run_id}"), 200, "read completion")
            if done["lifecycle"] in ("completed", "needsAttention"):
                break
            time.sleep(1)
        require(done["lifecycle"] == "completed" and done.get("outcome") == "Fulfilled",
                "decision completes on the restarted application")
        require(len(done.get("effects", [])) == 1 and done["effects"][0]["state"] == "succeeded"
                and done["effects"][0]["attempts"] == 1, "exactly one successful effect and one attempt")
        events = []
        after_seq = 0
        while True:
            page = response(requester.get(f"/workspaces/{ws}/runs/{run_id}/events", limit=100,
                                          after=after_seq), 200, "read completion timeline")
            events.extend(page["items"])
            if not page.get("hasMore"):
                break
            if not page["items"]:
                raise AssertionError("event pagination did not advance")
            after_seq = page["items"][-1]["seq"]
        require(sum(e["type"] == "RUN_COMPLETED" for e in events) == 1, "exactly one completion event")
        print(json.dumps({"test": "T-RES-01", "shutdownMs": shutdown_ms, "startupMs": startup_ms,
                          "runId": run_id, "result": "passed"}), flush=True)
    finally:
        if stopped:
            compose("start", "orqix")
            ready()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=["restart", "killnode", "shutdown", "dboutage", "revocation"],
                        help="restart: T-RES-01; killnode: T-RES-03; dboutage: T-RES-04; shutdown: T-RES-05; "
                             "revocation: T-SEC-09 (the last four need the resilience overlay, see README)")
    parser.add_argument("--victim", choices=["orqix", "orqix-2"], default="orqix-2",
                        help="instance that is killed (killnode) or stopped (shutdown)")
    parser.add_argument("--outage-seconds", type=int, default=60, help="PostgreSQL downtime for dboutage")
    args = parser.parse_args()
    if args.scenario == "restart":
        restart()
        return 0
    import scenarios  # needs the overlay stack; imported lazily so that `restart` keeps working on the plain stack
    if args.scenario == "killnode":
        return scenarios.killnode(args.victim)
    if args.scenario == "shutdown":
        return scenarios.shutdown(args.victim)
    if args.scenario == "dboutage":
        return scenarios.dboutage(args.outage_seconds)
    return scenarios.revocation()


if __name__ == "__main__":
    sys.exit(main())
