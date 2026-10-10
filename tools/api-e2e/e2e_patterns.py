"""Demo 4 end to end: parent -> child -> result -> continue (scenarios 15-17), on the Docker stack.

  15  New hire onboarding: IT and Facilities approve in parallel (different groups), the request waits on its timer,
      starts a purchase-approval child (pinned to the release published with the parent), the child is approved and
      the parent continues with its outcome and mapped result. The child is a real run with its own ORQ reference,
      a link back to the parent and exactly one purchase order in the stub.
  16  Release pinning and cancellation: republishing the child while a parent waits does not change the child it
      starts; cancelling the parent cancels the active child and its task; no order is created.
  17  A rejection in one parallel branch ends the whole request and cancels the other branch's task.

Needs the dev seed groups it-desk and facilities-team (backed by the dev realm's managers and finance groups). The first
timer is the real one-minute wait of the template, so the script takes about two to three minutes.

    python tools/api-e2e/e2e_patterns.py
"""
import sys
import time
import uuid

import requests

import e2e_scenarios as base
from e2e_scenarios import STUB, check, stub_orders, task_for, wait_run
from orqix_client import Client

ONBOARDING = {"employeeName": "Ada Lovelace", "role": "Engineer", "department": "IT",
              "equipment": "Laptop and docking station", "equipmentBudget": 1800,
              "justification": "New starter needs a workstation."}


def workspace(client):
    return next(w["id"] for w in client.get("/me").json()["workspaces"] if w["key"] == "purchasing")


def find_workflow(client, ws, key):
    cursor = None
    while True:
        params = {"cursor": cursor} if cursor else {}
        page = client.get(f"/workspaces/{ws}/workflows", **params).json()
        for item in page["items"]:
            if item["key"] == key:
                return item
        cursor = page.get("nextCursor")
        if not cursor:
            return None


def publish(designer, publisher, ws, wf_id, mutate=None):
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf_id}/draft").json()
    revision = draft["revision"]
    if mutate:
        mutate(draft["definition"])
        revision = designer.put(f"/workspaces/{ws}/workflows/{wf_id}/draft", {"definition": draft["definition"]},
                                if_match=f'"r{revision}"').json()["revision"]
    return publisher.post(f"/workspaces/{ws}/workflows/{wf_id}/publish",
                          {"draftRevision": revision, "acknowledgeWarnings": True}, idem=True)


def ensure_purchase(designer, publisher, ws):
    purchase = find_workflow(designer, ws, "purchase-approval")
    if purchase is None:
        purchase = designer.post(f"/workspaces/{ws}/workflows", {"key": "purchase-approval", "name": "Purchase approval",
                                                                   "templateId": "purchase-approval"}).json()
    if not purchase.get("activeRelease"):
        r = publish(designer, publisher, ws, purchase["id"])
        check("purchase-approval published for the child step", r.status_code == 201, str(r.status_code))
    return purchase["id"]


def new_onboarding(designer, publisher, ws, prefix):
    key = f"{prefix}-{uuid.uuid4().hex[:8]}"
    wf = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": f"Onboarding {key}",
                                                         "templateId": "new-hire-onboarding"}).json()
    r = publish(designer, publisher, ws, wf["id"])
    check(f"{prefix}: onboarding published (child pinned)", r.status_code == 201, f"{r.status_code} {r.text[:200]}")
    return wf["id"], r.json()


def start_onboarding(rhea, ws, wf_id):
    r = rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf_id, "input": ONBOARDING}, idem=True)
    check("onboarding started", r.status_code == 201, f"{r.status_code} {r.text[:200]}")
    return r.json()


def decide_node(client, ws, run_id, node_id, action, reason=None):
    tasks = client.get(f"/workspaces/{ws}/tasks", view="claimable", limit=100).json()["items"]
    task = next((t for t in tasks if t["runId"] == run_id and t["nodeId"] == node_id), None)
    if not task:
        return None
    claimed = client.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]})
    body = {"expectedVersion": claimed.json()["version"], "action": action}
    if reason:
        body["reason"] = reason
    return client.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", body, idem=True)


def children(client, ws, run_id):
    return client.get(f"/workspaces/{ws}/runs/{run_id}").json().get("children", [])


def wait_child(client, ws, run_id, timeout=150):
    deadline = time.time() + timeout
    while time.time() < deadline:
        kids = children(client, ws, run_id)
        if kids:
            return kids[0]
        time.sleep(2)
    return None


def main():
    rhea, mani, fin, paul, olga, dana = (Client(u) for u in (
        "rhea.requester", "mani.manager", "fin.finance", "paul.publisher", "olga.operator", "dana.designer"))
    ws = workspace(rhea)
    requests.post(f"{STUB}/__reset", timeout=10)
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)
    purchase_id = ensure_purchase(dana, paul, ws)

    print("Scenario 15: parallel approvals, timer, child purchase, result mapping, continue")
    onboarding, release = new_onboarding(dana, paul, ws, "e2e-onboarding")
    pin = release["manifest"]["workflows"]
    check("the child release is pinned in the manifest", len(pin) == 1 and pin[0]["workflowKey"] == "purchase-approval"
          and pin[0]["nodeId"] == "orderEquipment", str(pin))
    run = start_onboarding(rhea, ws, onboarding)
    detail = rhea.get(f"/workspaces/{ws}/runs/{run['id']}").json()
    open_steps = sorted(s["nodeId"] for s in detail["currentSteps"])
    check("both approvals are open at the same time", open_steps == ["facilities", "it"], str(open_steps))
    check("IT task is for the IT desk, Facilities for the Facilities team", sorted(
        s["candidateGroup"] for s in detail["currentSteps"]) == ["facilities-team", "it-desk"])
    check("each approver sees the task of their own group", task_for(mani, ws, run["id"]) is not None
          and task_for(fin, ws, run["id"]) is not None)
    first = decide_node(mani, ws, run["id"], "it", "approve")
    check("IT approves", first is not None and first.status_code == 201, str(first and first.status_code))
    mid = rhea.get(f"/workspaces/{ws}/runs/{run['id']}").json()
    check("the join still waits for Facilities", [s["nodeId"] for s in mid["currentSteps"]] == ["facilities"]
          and mid["lifecycle"] == "waiting", str(mid["currentSteps"]))
    second = decide_node(fin, ws, run["id"], "facilities", "approve")
    check("Facilities approves", second is not None and second.status_code == 201)
    waiting = wait_run(rhea, ws, run["id"], lambda r: any(s["kind"] == "wait" for s in r["currentSteps"]), 30)
    wait_step = next((s for s in waiting["currentSteps"] if s["kind"] == "wait"), None)
    check("the run waits on the timer with a due time", wait_step is not None and wait_step.get("dueAt"),
          str(waiting["currentSteps"]))
    kid = wait_child(rhea, ws, run["id"])
    check("the timer ended and the purchase started as its own run", kid is not None)
    if kid is None:
        return finish()
    check("the child pins the release published with the parent", kid["release"]["releaseNumber"] == pin[0]["releaseNumber"]
          and kid["release"]["id"] == pin[0]["releaseId"])
    child_detail = rhea.get(f"/workspaces/{ws}/runs/{kid['id']}").json()
    parent_detail = rhea.get(f"/workspaces/{ws}/runs/{run['id']}").json()
    check("the child has its own ORQ reference and a link to the parent",
          child_detail["reference"].startswith("ORQ-") and child_detail["reference"] != parent_detail["reference"]
          and child_detail["parentRun"]["id"] == run["id"]
          and child_detail["parentRun"]["reference"] == parent_detail["reference"], str(child_detail.get("parentRun")))
    check("the parent shows 'waiting' on the child", any(s["kind"] == "child" for s in parent_detail["currentSteps"]))
    check("no order before the child is approved", stub_orders() == [])
    decided = decide_node(mani, ws, kid["id"], "manager", "approve")
    check("the child's manager approves", decided is not None and decided.status_code == 201)
    done = wait_run(rhea, ws, run["id"], lambda r: r["lifecycle"] in base_done(), 60)
    check("the parent continued and finished Ready", done["lifecycle"] == "completed" and done["outcome"] == "Ready",
          f"{done['lifecycle']} {done.get('outcome')}")
    check("exactly one purchase order exists", len(stub_orders()) == 1, str(len(stub_orders())))
    check("the child's result is mapped to the parent's output", bool(done["output"]) and
          done["output"].get("equipmentOrderId") is not None, str(done["output"]))
    events = rhea.get(f"/workspaces/{ws}/runs/{run['id']}/events", limit=200).json()["items"]
    types = [e["type"] for e in events]
    check("the parent timeline shows the child start and finish", "CHILD_STARTED" in types and "CHILD_ENDED" in types
          and types.index("CHILD_STARTED") < types.index("CHILD_ENDED"))
    ended = next(e for e in events if e["type"] == "CHILD_ENDED")
    check("the finish event names the outcome", ended["data"].get("outcome") == "Fulfilled"
          and ended["data"].get("childReference") == child_detail["reference"], str(ended["data"]))
    seqs = [e["seq"] for e in events]
    check("the timeline has no gaps", seqs == list(range(1, len(seqs) + 1)))

    print("Scenario 16: republishing the child does not change a waiting parent; cancelling the parent cancels the child")
    requests.post(f"{STUB}/__reset", timeout=10)
    run2 = start_onboarding(rhea, ws, onboarding)
    decide_node(mani, ws, run2["id"], "it", "approve")
    decide_node(fin, ws, run2["id"], "facilities", "approve")
    republished = publish(dana, paul, ws, purchase_id, lambda d: d.update({"name": "Purchase approval (republished)"}))
    check("the child is republished while the parent waits", republished.status_code == 201,
          f"{republished.status_code}")
    kid2 = wait_child(rhea, ws, run2["id"])
    check("the parent still starts the release it was published with", kid2 is not None
          and kid2["release"]["id"] == pin[0]["releaseId"], str(kid2 and kid2["release"]))
    if kid2 is not None:
        version = rhea.get(f"/workspaces/{ws}/runs/{run2['id']}").json()["version"]
        cancel = olga.post(f"/workspaces/{ws}/runs/{run2['id']}/cancel", {"reason": "Hire withdrawn",
                                                                             "expectedVersion": version}, idem=True)
        check("operator cancels the parent", cancel.status_code == 200 and cancel.json()["lifecycle"] == "cancelled",
              f"{cancel.status_code} {cancel.text[:120]}")
        after = rhea.get(f"/workspaces/{ws}/runs/{kid2['id']}").json()
        check("the child was cancelled with its parent", after["lifecycle"] == "cancelled", after["lifecycle"])
        check("the child's task is gone from the inbox", task_for(mani, ws, kid2["id"]) is None)
    check("no order was created", stub_orders() == [])

    print("Scenario 17: a rejection in one branch ends the whole request")
    run3 = start_onboarding(rhea, ws, onboarding)
    rejected = decide_node(fin, ws, run3["id"], "facilities", "reject", "No desk is free")
    check("Facilities rejects with a reason", rejected is not None and rejected.status_code == 201)
    end = wait_run(rhea, ws, run3["id"], lambda r: r["lifecycle"] in base_done(), 30)
    check("the request ended as Rejected", end["lifecycle"] == "completed" and end["outcomeKind"] == "rejected",
          f"{end['lifecycle']} {end.get('outcomeKind')}")
    check("the IT approval was cancelled with it", task_for(mani, ws, run3["id"]) is None
          and all(t["status"] != "open" for t in end["tasks"]), str([t["status"] for t in end["tasks"]]))
    return finish()


def base_done():
    return ("completed", "needsAttention", "cancelled")


def finish():
    results = base.results
    passed = sum(1 for _, ok in results if ok)
    print(f"\nRESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
