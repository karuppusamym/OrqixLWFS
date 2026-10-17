"""Operations scenarios 10-14 against the Docker stack (app :8080, Keycloak :8180, procurement stub :8095).

Continues the numbering of e2e_scenarios.py:

  10  Release rollback (T-RUN-07): a rollback moves only new starts; waiting runs keep their release and route
  11  Reconcile confirmedApplied (T-EFF-06): the operator records the order made by hand; no further call to the stub
  12  Reconcile confirmedNotApplied then retry (T-EFF-07): two deliberate steps, then exactly one order
  13  Cancel (FR10): permissions, state, tasks cancelled, nothing sent to the stub
  14  Forged fields and missing CSRF token (T-SEC-04 / T-SEC-05) leave no run behind

Development stack only; passwords come from the dev realm file and are never printed.
    python tools/api-e2e/e2e_operations.py
"""
import sys
import uuid

import requests

import e2e_scenarios as base
from e2e_scenarios import STUB, check, claim_and_decide, start, stub_orders, task_for, wait_run
from orqix_client import BASE, Client

DONE = ("completed", "needsAttention", "cancelled")


def new_workflow(designer, publisher, ws, prefix, mutate=None):
    """Creates a workflow from the purchase template, optionally edits the draft, publishes; returns (id, release)."""
    key = f"{prefix}-{uuid.uuid4().hex[:8]}"
    wf = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": f"{prefix} {key}",
                                                         "templateId": "purchase-approval"}).json()
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()
    revision = draft["revision"]
    if mutate:
        mutate(draft["definition"])
        revision = designer.put(f"/workspaces/{ws}/workflows/{wf['id']}/draft", {"definition": draft["definition"]},
                                if_match=f'"r{revision}"').json()["revision"]
    release = publisher.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish",
                             {"draftRevision": revision, "acknowledgeWarnings": True}, idem=True).json()
    return wf["id"], release


def set_finance_threshold(definition, value):
    definition["decisions"][0]["rows"][0]["when"]["value"] = value


def fault(mode, times=1):
    requests.post(f"{STUB}/__reset", timeout=10)
    requests.post(f"{STUB}/__faults", json={"mode": mode, "times": times}, timeout=10)


def release_number(client, ws, run_id):
    return client.get(f"/workspaces/{ws}/runs/{run_id}").json()["release"]["releaseNumber"]


def visited_finance(client, ws, run_id):
    return any(t["nodeId"] == "finance" for t in client.get(f"/workspaces/{ws}/runs/{run_id}").json()["tasks"])


def main():
    rhea, mani, fin, paul, olga, dana, fred = (Client(u) for u in (
        "rhea.requester", "mani.manager", "fin.finance", "paul.publisher", "olga.operator", "dana.designer",
        "fred.facilities"))
    ws = next(w["id"] for w in rhea.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    requests.post(f"{STUB}/__reset", timeout=10)
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)

    print("Scenario 10: rollback selects the older release for new starts; waiting runs stay on their own release")
    wf, r1 = new_workflow(dana, paul, ws, "e2e-rollback")
    run_a = start(rhea, ws, wf, 6000, "Pinned to release 1").json()
    draft = dana.get(f"/workspaces/{ws}/workflows/{wf}/draft").json()
    set_finance_threshold(draft["definition"], 10000)
    revision = dana.put(f"/workspaces/{ws}/workflows/{wf}/draft", {"definition": draft["definition"]},
                        if_match=f'"r{draft["revision"]}"').json()["revision"]
    r2 = paul.post(f"/workspaces/{ws}/workflows/{wf}/publish", {"draftRevision": revision,
                                                                "acknowledgeWarnings": True}, idem=True).json()
    check("release 2 published and active", r2.get("releaseNumber") == 2, str(r2.get("releaseNumber")))
    run_b = start(rhea, ws, wf, 6000, "Pinned to release 2").json()
    check("new run starts on release 2", release_number(rhea, ws, run_b["id"]) == 2)
    workflow = paul.get(f"/workspaces/{ws}/workflows/{wf}").json()
    stale = paul.post(f"/workspaces/{ws}/workflows/{wf}/active-release", {"releaseId": r1["id"],
                                                                          "expectedVersion": workflow["version"] + 9})
    check("rollback with a stale version -> 409 WORKFLOW_VERSION_CONFLICT",
          stale.status_code == 409 and stale.json().get("code") == "WORKFLOW_VERSION_CONFLICT", str(stale.status_code))
    rollback = paul.post(f"/workspaces/{ws}/workflows/{wf}/active-release", {"releaseId": r1["id"],
                                                                             "expectedVersion": workflow["version"]})
    check("rollback to release 1 accepted", rollback.status_code == 200, f"{rollback.status_code} {rollback.text[:120]}")
    active = paul.get(f"/workspaces/{ws}/workflows/{wf}").json()["activeRelease"]
    check("active release is 1", active and active["releaseNumber"] == 1)
    run_c = start(rhea, ws, wf, 6000, "After rollback").json()
    check("new run after the rollback uses release 1", release_number(rhea, ws, run_c["id"]) == 1)
    check("waiting runs keep their release", release_number(rhea, ws, run_a["id"]) == 1
          and release_number(rhea, ws, run_b["id"]) == 2)
    claim_and_decide(mani, ws, run_b["id"], "approve")
    run_b = wait_run(rhea, ws, run_b["id"], lambda r: r["lifecycle"] in DONE)
    check("release 2 run completes without finance although release 1 is active",
          run_b["lifecycle"] == "completed" and not visited_finance(rhea, ws, run_b["id"]), run_b["lifecycle"])
    claim_and_decide(mani, ws, run_a["id"], "approve")
    wait_run(rhea, ws, run_a["id"], lambda r: r["currentSteps"] and r["currentSteps"][0]["nodeId"] == "finance")
    check("release 1 run still routes 6,000 to finance", visited_finance(rhea, ws, run_a["id"]))
    claim_and_decide(fin, ws, run_a["id"], "approve")
    run_a = wait_run(rhea, ws, run_a["id"], lambda r: r["lifecycle"] in DONE)
    check("release 1 run completes after finance", run_a["lifecycle"] == "completed" and run_a.get("outcome") == "Fulfilled")
    forward = paul.post(f"/workspaces/{ws}/workflows/{wf}/active-release", {
        "releaseId": r2["id"], "expectedVersion": paul.get(f"/workspaces/{ws}/workflows/{wf}").json()["version"]})
    check("roll forward to release 2", forward.status_code == 200
          and release_number(rhea, ws, start(rhea, ws, wf, 6000, "After roll forward").json()["id"]) == 2)

    print("Scenario 11: reconcile confirmedApplied after a rejected call -> run completes, stub is not called again")
    wf, _ = new_workflow(dana, paul, ws, "e2e-reconcile")
    fault("status400")
    run = start(rhea, ws, wf, 220, "Order made by hand").json()
    claim_and_decide(mani, ws, run["id"], "approve")
    run = wait_run(olga, ws, run["id"], lambda r: r["lifecycle"] in DONE, timeout=60)
    effect_id = (run.get("attention") or {}).get("effectId")
    check("needs attention: EFFECT_REJECTED with a permanentFailure effect", run["lifecycle"] == "needsAttention"
          and run["attention"]["reason"] == "EFFECT_REJECTED" and run["effects"][0]["state"] == "permanentFailure",
          str(run.get("attention")))
    body = {"resolution": "confirmedApplied", "evidence": {
        "note": "Order PO-MANUAL-1 was created by hand in the procurement system", "remoteReference": "PO-MANUAL-1"},
        "result": {"procurementId": "PO-MANUAL-1"}}
    path = f"/workspaces/{ws}/runs/{run['id']}/effects/{effect_id}/reconcile"
    wrong = olga.post(path, {**body, "result": {"other": "x"}}, idem=True)
    check("reconcile with the wrong result fields -> 422", wrong.status_code == 422, str(wrong.status_code))
    check("a requester may not reconcile -> 403", rhea.post(path, body, idem=True).status_code == 403)
    key = str(uuid.uuid4())
    reconciled = olga.post(path, body, key=key)
    check("operator reconcile confirmedApplied -> 200, effect succeeded", reconciled.status_code == 200
          and reconciled.json().get("state") == "succeeded", f"{reconciled.status_code} {reconciled.text[:150]}")
    replay = olga.post(path, body, key=key)
    check("same request again is a replay", replay.status_code == 200 and replay.headers.get("Idempotent-Replayed") == "true")
    run = wait_run(olga, ws, run["id"], lambda r: r["lifecycle"] in DONE, timeout=60)
    check("run completed as Fulfilled with the operator's reference", run["lifecycle"] == "completed"
          and run.get("outcome") == "Fulfilled" and run["output"].get("procurementId") == "PO-MANUAL-1", str(run.get("output")))
    check("no order and no further create call at the stub", stub_orders() == [], str(stub_orders()))
    events = [e["type"] for e in rhea.get(f"/workspaces/{ws}/runs/{run['id']}/events").json()["items"]]
    check("timeline has one EFFECT_RECONCILED and one RUN_COMPLETED", events.count("EFFECT_RECONCILED") == 1
          and events.count("RUN_COMPLETED") == 1, " ".join(events))

    print("Scenario 12: reconcile confirmedNotApplied, then retry -> exactly one new order")
    fault("status400")
    run = start(rhea, ws, wf, 230, "Not applied").json()
    claim_and_decide(mani, ws, run["id"], "approve")
    run = wait_run(olga, ws, run["id"], lambda r: r["lifecycle"] in DONE, timeout=60)
    effect_id = (run.get("attention") or {}).get("effectId")
    not_applied = olga.post(f"/workspaces/{ws}/runs/{run['id']}/effects/{effect_id}/reconcile", {
        "resolution": "confirmedNotApplied", "evidence": {"note": "Checked the supplier portal: no order exists"}},
        idem=True)
    check("confirmedNotApplied -> effect notApplied", not_applied.status_code == 200
          and not_applied.json().get("state") == "notApplied", f"{not_applied.status_code} {not_applied.text[:120]}")
    run = olga.get(f"/workspaces/{ws}/runs/{run['id']}").json()
    check("run stays in needsAttention until an explicit retry", run["lifecycle"] == "needsAttention")
    check("no order yet", stub_orders() == [])
    retry = olga.post(f"/workspaces/{ws}/runs/{run['id']}/retry",
                      {"reason": "Confirmed not applied; creating the order now", "expectedVersion": run["version"]},
                      idem=True)
    check("operator retry accepted", retry.status_code == 200, f"{retry.status_code} {retry.text[:120]}")
    run = wait_run(olga, ws, run["id"], lambda r: r["lifecycle"] in DONE, timeout=90)
    check("completed after the retry with exactly one order", run["lifecycle"] == "completed" and len(stub_orders()) == 1,
          f"{run['lifecycle']} orders={len(stub_orders())}")

    print("Scenario 13: cancel (operator only), tasks cancelled, nothing sent to the stub")
    requests.post(f"{STUB}/__reset", timeout=10)
    run = start(rhea, ws, wf, 240, "To be cancelled").json()
    version = rhea.get(f"/workspaces/{ws}/runs/{run['id']}").json()["version"]
    cancel_path = f"/workspaces/{ws}/runs/{run['id']}/cancel"
    check("requester may not cancel -> 403", rhea.post(cancel_path, {"reason": "Changed my mind",
                                                                       "expectedVersion": version}, idem=True).status_code == 403)
    cancelled = olga.post(cancel_path, {"reason": "Requested by the business", "expectedVersion": version}, idem=True)
    check("operator cancel -> 200 cancelled with the task cancelled", cancelled.status_code == 200
          and cancelled.json()["lifecycle"] == "cancelled" and cancelled.json()["tasks"][0]["status"] == "cancelled",
          f"{cancelled.status_code} {cancelled.text[:120]}")
    again = olga.post(cancel_path, {"reason": "Again", "expectedVersion": cancelled.json()["version"]}, idem=True)
    check("cancel of a cancelled run -> 409 RUN_NOT_ACTIVE", again.status_code == 409
          and again.json().get("code") == "RUN_NOT_ACTIVE", str(again.status_code))
    check("cancelled task disappears from the claimable inbox", task_for(mani, ws, run["id"]) is None)
    check("no order was created", stub_orders() == [])

    print("Scenario 14: forged fields and a missing CSRF token are rejected and leave no run behind")
    count_before = len(olga.get(f"/workspaces/{ws}/runs", workflowId=wf, limit=100).json()["items"])
    forged = rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf, "initiator": "someone-else", "input": {
        "title": "Forged", "purpose": "x", "costCenter": "OPS", "amount": 10}}, idem=True)
    check("unknown field 'initiator' -> 4xx problem", forged.status_code in (400, 422)
          and forged.headers.get("Content-Type", "").startswith("application/problem+json"),
          f"{forged.status_code} {forged.json().get('code')}")
    no_csrf = requests.post(f"{BASE}/api/v1/workspaces/{ws}/runs", json={"workflowId": wf, "input": {}},
                            cookies=rhea.s.cookies, headers={"Idempotency-Key": str(uuid.uuid4())}, timeout=30)
    check("missing X-XSRF-TOKEN -> 403 CSRF_INVALID", no_csrf.status_code == 403
          and no_csrf.json().get("code") == "CSRF_INVALID", f"{no_csrf.status_code} {no_csrf.text[:100]}")
    check("a user of another workspace gets 404 for the run list", fred.get(f"/workspaces/{ws}/runs").status_code == 404)
    count_after = len(olga.get(f"/workspaces/{ws}/runs", workflowId=wf, limit=100).json()["items"])
    check("no run was created by the rejected requests", count_after == count_before, f"{count_before} -> {count_after}")

    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)
    results = base.results
    passed = sum(1 for _, ok in results if ok)
    print(f"\nRESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
