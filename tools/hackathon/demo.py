"""Synthetic local-stack acceptance. Uses only dev accounts and the local SMTP sink.

This exercises runtime behavior, not genuine PEGA equivalence or reviewed Git provenance.
Run after starting the hackathon Compose overlay; writes a redacted evidence manifest.
"""
import argparse
import json
import subprocess
import os
import sys
import time
import uuid
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/api-e2e"))
from orqix_client import Client  # noqa: E402


def require(response, *statuses):
    if response.status_code not in statuses:
        try:
            reason = response.json().get("code", "unexpected response")
        except ValueError:
            reason = "non-JSON response"
        raise AssertionError(f"{response.request.method} {response.request.path_url}: {response.status_code}, {reason}")
    return response.json() if response.content else None


def wait_for(call, predicate, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = call()
        if predicate(value):
            return value
        time.sleep(2)
    raise AssertionError("Timed out waiting for the local acceptance condition")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restart-container", help="Optional restart of this fixture's dedicated Compose application container")
    args = parser.parse_args()
    from urllib.parse import urlparse
    target = urlparse(os.environ.get("ORQIX_BASE_URL", "http://localhost:8080"))
    if target.hostname not in {"localhost", "127.0.0.1"}:
        raise SystemExit("This fixture accepts only an explicitly local development stack.")
    clients = {name: Client(name) for name in ["dana.designer", "paul.publisher", "rhea.requester", "mani.manager", "olga.operator", "wade.admin"]}
    designer, publisher, requester, manager, operator, admin = [clients[n] for n in clients]
    ws = next(w["id"] for w in require(designer.get("/me"), 200)["workspaces"] if w["key"] == "purchasing")
    prefix = f"/workspaces/{ws}"
    definition = json.loads((ROOT / "examples/hackathon/tier1-approval.json").read_text(encoding="utf-8"))
    key = "hackathon-demo-" + uuid.uuid4().hex[:8]
    definition["workflowKey"] = key
    definition["provenance"] = {"gitCommit": "0" * 40, "reviewReference": "SYNTHETIC LOCAL RUNTIME TEST ONLY; Git/source qualification excluded"}
    calendar = definition["nodes"][1]["config"]["calendar"]
    calendar.update(zone="UTC", start="00:00", end="23:59", weekdays=list(range(1, 8)), holidays=[])
    definition["nodes"][1]["config"]["dueAfterMinutes"] = 1
    workflow = require(designer.post(prefix + "/workflows", {"key": key, "name": "Synthetic hackathon runtime demo"}), 201)["id"]
    require(designer.put(prefix + f"/workflows/{workflow}/draft", {"definition": definition}, if_match='"r1"'), 200)
    release = require(publisher.post(prefix + f"/workflows/{workflow}/publish", {"draftRevision": 2, "acknowledgeWarnings": True}, idem=True), 201)
    print("Published synthetic definition through an independent publisher.", flush=True)

    def start(label):
        return require(requester.post(prefix + "/runs", {"workflowId": workflow, "input": {"title": label, "amount": 10}}, idem=True), 201)["id"]

    def detail(run):
        return require(operator.get(prefix + f"/runs/{run}"), 200)

    def task(run):
        return next(t for t in detail(run)["tasks"] if t["status"] == "open")

    approved = start("Synthetic approval")
    t = task(approved)
    claimed = require(manager.post(prefix + f"/tasks/{t['id']}/claim", {"expectedVersion": t["version"]}), 200)
    decision = {"expectedVersion": claimed["version"], "action": "approve"}
    idem = str(uuid.uuid4())
    require(manager.post(prefix + f"/tasks/{t['id']}/decision", decision, key=idem), 201)
    require(manager.post(prefix + f"/tasks/{t['id']}/decision", decision, key=idem), 200)
    require(manager.post(prefix + f"/tasks/{t['id']}/decision", {**decision, "action": "reject", "reason": "Changed retry"}, key=idem), 409)
    assert detail(approved)["lifecycle"] == "completed"
    print("Approval and exact decision replay passed; changed replay was refused.", flush=True)

    overdue = start("Synthetic overdue approval")
    breach = wait_for(lambda: require(operator.get(prefix + f"/runs/{overdue}/events"), 200),
                      lambda result: any(e["type"] == "TASK_SLA_BREACHED" for e in result["items"]), seconds=125)
    assert sum(e["type"] == "TASK_SLA_BREACHED" for e in breach["items"]) == 1
    print("Unassigned task produced one durable SLA breach.", flush=True)
    recovery_seconds = None
    if args.restart_container:
        project = subprocess.check_output(["docker", "inspect", "--format", '{{ index .Config.Labels "com.docker.compose.project" }}', args.restart_container], text=True).strip()
        if not project.startswith("orqix-hackathon-"):
            raise AssertionError("Refusing to restart anything outside the dedicated hackathon fixture project")
        began = time.monotonic()
        subprocess.run(["docker", "restart", args.restart_container], check=True, stdout=subprocess.DEVNULL)
        def recovered():
            try:
                result = operator.get(prefix + f"/runs/{overdue}")
                return result.json() if result.status_code == 200 else None
            except requests.RequestException:
                return None
        restored = wait_for(recovered, lambda r: r is not None, seconds=120)
        recovery_seconds = round(time.monotonic() - began, 2)
        assert any(t["status"] == "open" for t in restored["tasks"])
        after_restart = require(operator.get(prefix + f"/runs/{overdue}/events"), 200)
        assert sum(e["type"] == "TASK_SLA_BREACHED" for e in after_restart["items"]) == 1
        print(f"Restart preserved the open task and single breach; API recovery {recovery_seconds}s.", flush=True)
    before = detail(overdue)
    require(operator.post(prefix + f"/runs/{overdue}/cancel", {"expectedVersion": before["version"], "reason": "Synthetic closure acceptance"}, idem=True), 200)
    assert not any(t["status"] == "open" for t in detail(overdue)["tasks"])
    states = wait_for(lambda: require(operator.get(prefix + f"/runs/{overdue}/mail"), 200),
                      lambda r: all(m["status"] != "queued" for m in r["items"]))
    assert any(m["status"] == "providerAccepted" for m in states["items"])
    print("Case closure and actual SMTP provider acceptance passed.", flush=True)

    subject = require(operator.get("/me"), 200)["subject"]["id"]
    grant = require(admin.post(prefix + "/access/grants", {"permission": "audit.read", "principal": {"type": "subject", "value": subject}}), 201)
    try:
        evidence = require(operator.get(prefix + f"/runs/{overdue}/evidence"), 200)
        assert evidence["manifest"]["eventCount"] == len(evidence["events"])
        require(requester.get(prefix + f"/runs/{overdue}/evidence"), 403)
    finally:
        # Reversible fixture-only grant; restore the default local operator permissions.
        response = admin.s.delete(f"{target.geturl().rstrip('/')}/api/v1{prefix}/access/grants/{grant['id']}", headers=admin._headers(), timeout=30)
        assert response.status_code == 204
    capture = requests.get(os.environ.get("MAIL_CAPTURE_URL", "http://localhost:1080") + "/messages", timeout=10).json()
    references = {detail(approved)["reference"], detail(overdue)["reference"]}
    messages = [m for m in capture["messages"] if any(ref in m["subject"] for ref in references)]
    assert messages and all("{{" not in m["subject"] and "{{" not in m["body"] for m in messages)
    report = {"kind": "synthetic-local-runtime-acceptance", "workspaceId": ws, "workflowId": workflow, "releaseId": release["id"],
              "runs": {"approved": approved, "overdueThenCancelled": overdue}, "smtpCaptured": len(messages), "restartRecoverySeconds": recovery_seconds,
              "mailStatuses": sorted({m["status"] for m in states["items"]}), "auditCount": evidence["manifest"]["auditCount"],
              "excludedClaims": ["genuine source equivalence", "reviewed Git source qualification", "enterprise compliance", "production capacity"]}
    output = ROOT / "evidence/hackathon/local-demo.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes((json.dumps(report, indent=2) + "\n").encode())
    print("Synthetic local acceptance passed; evidence saved without credentials or message bodies.", flush=True)


if __name__ == "__main__":
    main()
