"""End-to-end purchase scenarios against the Docker stack (app :8080, Keycloak :8180, procurement stub :8095)."""
import sys
import time
import uuid

import requests

from orqix_client import Client

STUB = "http://localhost:8095"
results = []


def check(name, condition, detail=""):
    results.append((name, bool(condition)))
    print(f"  [{'PASS' if condition else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))


def stub_orders():
    return requests.get(f"{STUB}/__orders", timeout=10).json()["orders"]


def wait_run(client, ws, run_id, until, timeout=120):
    deadline = time.time() + timeout
    run = None
    while time.time() < deadline:
        run = client.get(f"/workspaces/{ws}/runs/{run_id}").json()
        if until(run):
            return run
        time.sleep(1.5)
    return run


def task_for(client, ws, run_id, view="claimable"):
    r = client.get(f"/workspaces/{ws}/tasks", view=view, limit=100)
    if r.status_code != 200:  # e.g. a requester without task.act gets 403: nothing visible
        return None
    return next((t for t in r.json()["items"] if t["runId"] == run_id), None)


def claim_and_decide(client, ws, run_id, action, reason=None):
    task = task_for(client, ws, run_id)
    if not task:
        return None, None
    claimed = client.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]})
    body = {"expectedVersion": claimed.json()["version"], "action": action}
    if reason:
        body["reason"] = reason
    decided = client.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", body, idem=True)
    return claimed, decided


def start(client, ws, wf_id, amount, title):
    return client.post(f"/workspaces/{ws}/runs", {"workflowId": wf_id, "input": {
        "title": title, "purpose": "End-to-end check", "costCenter": "OPS", "amount": amount}}, idem=True)


def main():
    rhea, mani, fin, paul, olga, fred = (Client(u) for u in (
        "rhea.requester", "mani.manager", "fin.finance", "paul.publisher", "olga.operator", "fred.facilities"))
    ws = next(w["id"] for w in rhea.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    wf = next(w for w in paul.get(f"/workspaces/{ws}/workflows").json()["items"] if w["key"] == "purchase-approval")
    if not wf.get("activeRelease"):
        rev = paul.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()["revision"]
        paul.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish", {"draftRevision": rev}, idem=True)
    requests.post(f"{STUB}/__reset", timeout=10)
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)

    print("Scenario 1: USD 1,800 approved by manager -> purchase order -> Fulfilled")
    r = start(rhea, ws, wf["id"], 1800, "Laptop")
    check("start returns 201", r.status_code == 201, str(r.status_code))
    run = r.json()
    check("run waits at Review request for managers", run["lifecycle"] == "waiting"
          and run["currentSteps"] and run["currentSteps"][0]["nodeId"] == "manager", str(run.get("currentSteps")))
    check("requester does not see own task", task_for(rhea, ws, run["id"]) is None)
    _, decided = claim_and_decide(mani, ws, run["id"], "approve")
    check("manager decision 201", decided is not None and decided.status_code == 201,
          str(decided.status_code if decided is not None else "no task"))
    run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention"))
    check("run completed as Fulfilled", run["lifecycle"] == "completed" and run["outcome"] == "Fulfilled",
          f"{run['lifecycle']} {run.get('outcome')}")
    check("procurementId in output", bool((run.get("output") or {}).get("procurementId")), str(run.get("output")))
    check("exactly one stub order", len(stub_orders()) == 1, str(len(stub_orders())))
    notes = rhea.get(f"/workspaces/{ws}/me/notifications").json()
    check("requester notified", any(n.get("runId") == run["id"] for n in notes["items"]))
    events = rhea.get(f"/workspaces/{ws}/runs/{run['id']}/events").json()["items"]
    seqs = [e["seq"] for e in events]
    check("event sequence gap-free", seqs == list(range(1, len(seqs) + 1)), " ".join(e["type"] for e in events))
    check("other workspace cannot read the run (404)",
          fred.get(f"/workspaces/{ws}/runs/{run['id']}").status_code == 404)

    print("Scenario 2: manager rejects (reason required)")
    run = start(rhea, ws, wf["id"], 900, "Chair").json()
    _, no_reason = claim_and_decide(mani, ws, run["id"], "reject")
    check("reject without reason -> 422 REASON_REQUIRED", no_reason.status_code == 422
          and no_reason.json().get("code") == "REASON_REQUIRED", str(no_reason.status_code))
    task = task_for(mani, ws, run["id"], view="assigned")
    rej = mani.post(f"/workspaces/{ws}/tasks/{task['id']}/decision",
                    {"expectedVersion": task["version"], "action": "reject", "reason": "Use spare stock"}, idem=True)
    check("reject with reason 201", rej.status_code == 201, str(rej.status_code))
    run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] == "completed")
    check("completed as Rejected, no new order", run["outcome"] == "Rejected" and len(stub_orders()) == 1,
          f"{run.get('outcome')} orders={len(stub_orders())}")

    print("Scenario 3: USD 5,000 routes to finance, finance approves")
    run = start(rhea, ws, wf["id"], 5000, "Server").json()
    claim_and_decide(mani, ws, run["id"], "approve")
    run = wait_run(rhea, ws, run["id"], lambda x: x["currentSteps"] and x["currentSteps"][0]["nodeId"] == "finance")
    check("5,000 waits at Finance review", run["currentSteps"] and run["currentSteps"][0]["nodeId"] == "finance")
    claim_and_decide(fin, ws, run["id"], "approve")
    run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] == "completed")
    check("Fulfilled after finance, 2 orders total", run.get("outcome") == "Fulfilled" and len(stub_orders()) == 2,
          f"{run.get('outcome')} orders={len(stub_orders())}")

    print("Scenario 4: USD 4,999.99 skips finance")
    run = start(rhea, ws, wf["id"], 4999.99, "Desk").json()
    claim_and_decide(mani, ws, run["id"], "approve")
    run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] == "completed")
    visited = [e["nodeId"] for e in rhea.get(f"/workspaces/{ws}/runs/{run['id']}/events").json()["items"]]
    check("4,999.99 never visits finance", "finance" not in visited and run.get("outcome") == "Fulfilled")

    print("Scenario 5: manager cannot approve own request")
    own = start(mani, ws, wf["id"], 300, "Headset").json()
    check("own task not claimable", task_for(mani, ws, own["id"]) is None)
    check("another manager can claim it", task_for(Client("max.manager"), ws, own["id"]) is not None)

    print("Scenario 6: idempotent start replay")
    key = str(uuid.uuid4())
    body = {"workflowId": wf["id"], "input": {"title": "Mouse", "purpose": "x", "costCenter": "IT", "amount": 25}}
    first = rhea.post(f"/workspaces/{ws}/runs", body, key=key)
    again = rhea.post(f"/workspaces/{ws}/runs", body, key=key)
    body["input"]["amount"] = 26
    clash = rhea.post(f"/workspaces/{ws}/runs", body, key=key)
    check("replay returns same run (200, replayed)", again.status_code == 200 and again.json()["id"] == first.json()["id"]
          and again.headers.get("Idempotent-Replayed") == "true")
    check("same key, different body -> 409", clash.status_code == 409
          and clash.json().get("code") == "IDEMPOTENCY_KEY_REUSED")
    bad = rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf["id"], "input": {
        "title": "x", "purpose": "x", "costCenter": "FIN", "amount": 1800.071}}, idem=True)
    check("invalid input -> 422 with field errors", bad.status_code == 422
          and {e["pointer"] for e in bad.json().get("fieldErrors", [])} >= {"/input/costCenter", "/input/amount"}
          or {e["pointer"] for e in bad.json().get("fieldErrors", [])} >= {"/costCenter", "/amount"},
          str(bad.json().get("fieldErrors")))

    if "--faults" in sys.argv:
        print("Scenario 7: procurement fails twice (500) then succeeds -> retried, one order")
        requests.post(f"{STUB}/__reset", timeout=10)
        requests.post(f"{STUB}/__faults", json={"mode": "status500", "times": 2}, timeout=10)
        run = start(rhea, ws, wf["id"], 120, "Cable").json()
        claim_and_decide(mani, ws, run["id"], "approve")
        run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention"), timeout=150)
        effects = olga.get(f"/workspaces/{ws}/runs/{run['id']}").json().get("effects", [])
        check("completed after retries, exactly one order", run["lifecycle"] == "completed" and len(stub_orders()) == 1,
              f"{run['lifecycle']} orders={len(stub_orders())} attempts={[e['attempts'] for e in effects]}")

        print("Scenario 8: procurement commits then times out -> retry with same key, still one order")
        requests.post(f"{STUB}/__reset", timeout=10)
        requests.post(f"{STUB}/__faults", json={"mode": "commitThenTimeout", "times": 1}, timeout=10)
        run = start(rhea, ws, wf["id"], 130, "Adapter").json()
        claim_and_decide(mani, ws, run["id"], "approve")
        run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention"), timeout=180)
        check("completed with exactly one order despite timeout", run["lifecycle"] == "completed"
              and len(stub_orders()) == 1, f"{run['lifecycle']} orders={len(stub_orders())}")

        print("Scenario 9: procurement rejects (400) -> needs attention -> operator retry after fix")
        requests.post(f"{STUB}/__reset", timeout=10)
        requests.post(f"{STUB}/__faults", json={"mode": "status400", "times": 1}, timeout=10)
        run = start(rhea, ws, wf["id"], 140, "Dock").json()
        claim_and_decide(mani, ws, run["id"], "approve")
        run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] == "needsAttention", timeout=60)
        check("needs attention: EFFECT_REJECTED", run["lifecycle"] == "needsAttention"
              and (run.get("attention") or {}).get("reason") == "EFFECT_REJECTED", str(run.get("attention")))
        retry = olga.post(f"/workspaces/{ws}/runs/{run['id']}/retry",
                          {"reason": "Fault cleared at the procurement service", "expectedVersion": run["version"]},
                          idem=True)
        check("operator retry accepted", retry.status_code == 200, f"{retry.status_code} {retry.text[:200]}")
        run = wait_run(rhea, ws, run["id"], lambda x: x["lifecycle"] == "completed", timeout=90)
        check("completed after operator retry", run["lifecycle"] == "completed" and len(stub_orders()) == 1)
        requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)

    passed = sum(1 for _, ok in results if ok)
    print(f"\nRESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
