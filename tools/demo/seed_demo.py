"""Demo data for a live walkthrough: real requests in every interesting state, created through the public API.

Run against the Docker stack (app :8080, Keycloak :8180, procurement stub :8095) after `docker compose --profile app up -d`:

    python tools/demo/seed_demo.py

It publishes the Purchase approval template if needed, then creates requests that end up:
  * waiting for a manager (two, different amounts),
  * waiting for finance (USD 5,000 and above),
  * completed and fulfilled (one purchase order each),
  * rejected by a manager with a reason (no purchase order),
  * rejected by finance,
  * needing attention: the procurement system answers 400 once, an operator can fix and retry.
It is safe to run more than once (it only adds requests) and prints a short summary, never credentials.
"""
import sys
import time
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "api-e2e"))
from orqix_client import Client  # noqa: E402

STUB = "http://localhost:8095"

REQUESTS = [
    # (title, purpose, cost center, amount, requester, journey)
    ("Replacement laptop for onboarding", "New starter in Operations needs a laptop before Monday.", "OPS", 1800, "rhea.requester", "waiting-manager"),
    ("Ergonomic chairs (12)", "Replace chairs that failed the annual safety check.", "HR", 3600, "rhea.requester", "waiting-manager"),
    ("Standing desks for the analytics team", "Team of 8 moving to the third floor; budget approved in Q3 plan.", "IT", 5000, "rhea.requester", "waiting-finance"),
    ("Server rack for the new office", "Required for the branch network rollout.", "IT", 12500, "rhea.requester", "waiting-finance"),
    ("Conference room camera", "Hybrid meetings in the main boardroom.", "OPS", 950, "rhea.requester", "fulfilled"),
    ("Monitor arms (20)", "Desk refresh for the finance floor.", "HR", 1200, "rhea.requester", "fulfilled"),
    ("Premium coffee machine", "Nice to have for the kitchen.", "HR", 2400, "rhea.requester", "rejected-manager"),
    ("Annual design software licences", "Renewal for 15 seats.", "IT", 7800, "rhea.requester", "rejected-finance"),
    ("Label printer", "Warehouse labelling; the old printer is broken.", "OPS", 640, "rhea.requester", "needs-attention"),
]


def log(message):
    print(f"  {message}", flush=True)


def wait_for(client, ws, run_id, until, timeout=90):
    deadline = time.time() + timeout
    run = None
    while time.time() < deadline:
        run = client.get(f"/workspaces/{ws}/runs/{run_id}").json()
        if until(run):
            return run
        time.sleep(1.2)
    return run


def find_task(client, ws, run_id):
    for view in ("claimable", "assigned"):
        r = client.get(f"/workspaces/{ws}/tasks", view=view, limit=100)
        if r.status_code == 200:
            hit = next((t for t in r.json()["items"] if t["runId"] == run_id), None)
            if hit:
                return hit
    return None


def decide(client, ws, run_id, action, reason=None, comment=None):
    task = None
    for _ in range(40):
        task = find_task(client, ws, run_id)
        if task:
            break
        time.sleep(0.5)
    if not task:
        raise RuntimeError(f"no task for run {run_id}")
    if task["status"] == "open" and not task.get("assignee"):
        claimed = client.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]})
        task = claimed.json()
    body = {"expectedVersion": task["version"], "action": action}
    if reason:
        body["reason"] = reason
    if comment:
        body["comment"] = comment
    r = client.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", body, idem=True)
    if r.status_code not in (200, 201):
        raise RuntimeError(f"decision failed: {r.status_code} {r.text[:200]}")


def main():
    clients = {u: Client(u) for u in ("rhea.requester", "mani.manager", "fin.finance", "paul.publisher")}
    rhea = clients["rhea.requester"]
    paul = clients["paul.publisher"]
    ws = next(w["id"] for w in rhea.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    wf = next(w for w in paul.get(f"/workspaces/{ws}/workflows").json()["items"] if w["key"] == "purchase-approval")
    if not wf.get("activeRelease"):
        rev = paul.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()["revision"]
        r = paul.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish", {"draftRevision": rev}, idem=True)
        log(f"published Purchase approval ({r.status_code})")
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)

    print("Creating demo requests ...")
    created = []
    for title, purpose, center, amount, who, journey in REQUESTS:
        client = clients[who]
        if journey == "needs-attention":
            # The procurement system rejects the order once: the run ends up needing attention.
            requests.post(f"{STUB}/__faults", json={"mode": "status400", "times": 1}, timeout=10)
        r = client.post(f"/workspaces/{ws}/runs", {"workflowId": wf["id"], "input": {
            "title": title, "purpose": purpose, "costCenter": center, "amount": amount}}, idem=True)
        if r.status_code != 201:
            raise RuntimeError(f"start failed for '{title}': {r.status_code} {r.text[:200]}")
        run = r.json()
        rid = run["id"]
        if journey in ("fulfilled", "needs-attention", "rejected-finance"):
            decide(clients["mani.manager"], ws, rid, "approve", comment="Looks fine.")
        if journey == "rejected-manager":
            decide(clients["mani.manager"], ws, rid, "reject", reason="Not in this quarter's budget; please resubmit in January.")
        if journey == "rejected-finance":
            wait_for(clients["fin.finance"], ws, rid, lambda x: find_task(clients["fin.finance"], ws, rid) is not None)
            decide(clients["fin.finance"], ws, rid, "reject", reason="Over the quarterly budget for software.")
        if journey == "waiting-finance":
            decide(clients["mani.manager"], ws, rid, "approve", comment="Approved by manager; finance to review.")
        if journey == "needs-attention":
            wait_for(rhea, ws, rid, lambda x: x["lifecycle"] in ("needsAttention", "completed"))
            requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)
        final = wait_for(rhea, ws, rid, lambda x: x["lifecycle"] != "running")
        created.append((run.get("reference", rid[:8]), title, journey, final["lifecycle"]))
        log(f"{run.get('reference', rid[:8])}  {title:<40} {journey:<17} -> {final['lifecycle']}")
        time.sleep(0.4)

    print()
    print("Demo ready. Sign in at http://localhost:8080 as (passwords are in deploy/compose/keycloak/orqix-dev-realm.json):")
    print("  rhea.requester   requests a purchase and tracks it")
    print("  mani.manager     approves or rejects the manager step (two requests are waiting)")
    print("  fin.finance      approves or rejects requests of USD 5,000 or more (two are waiting)")
    print("  olga.operator    reviews the run that needs attention, reconciles or retries it")
    print("  dana.designer    edits workflows; paul.publisher publishes them; wade.admin sees settings")


if __name__ == "__main__":
    main()
