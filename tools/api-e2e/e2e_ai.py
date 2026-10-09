"""Demo 7 end to end: prompt -> AI draft -> edit -> publish -> execute (scenarios 30 and 31).

  30  Describe an expense-claim workflow in words (economy profile, real provider, at most USD 0.05), poll the
      generation, resolve any missing configuration through the API, edit the draft, publish as paul.publisher,
      start a run as rhea.requester, approve it, and assert it completes
  31  Async semantics on the job of scenario 30: idempotent replay (200), key reuse (409), visibility
      (other workspace 404, requester 403) and request validation, at no extra provider cost

Needs the Docker stack (app :8080, Keycloak :8180, procurement stub :8095) and an OPENROUTER_API_KEY configured for the
app. When AI is not enabled the script prints why and exits 0 (skipped); nothing is spent. The key itself is never read
or printed here: only the app talks to the provider.

    python tools/api-e2e/e2e_ai.py [--max-cost 0.05] [--profile economy]
"""
import argparse
import sys
import time
import uuid

import requests

import e2e_scenarios as base
from e2e_scenarios import STUB, check, claim_and_decide, stub_orders, wait_run
from orqix_client import Client

DESCRIPTION = (
    "Employees submit an expense claim with a title, a business purpose, a cost center (OPS, IT or HR) and an amount "
    "in USD. Their manager approves every claim (use the group managers). Claims of USD 1,000 or more also need "
    "approval from finance (use the group finance-approvers). When all approvals are given, create the purchase order "
    "in the procurement system with the title, amount and cost center, and tell the requester. If anyone rejects, "
    "tell the requester."
)
TERMINAL = ("completed", "needsAttention", "cancelled")


def wait_generation(client, ws, job_id, timeout=170):
    deadline = time.time() + timeout
    job = None
    while time.time() < deadline:
        job = client.get(f"/workspaces/{ws}/ai/generations/{job_id}").json()
        if job.get("status") in ("succeeded", "failed"):
            return job
        time.sleep(2)
    return job


def sample_input(schema):
    """A valid request for the generated form: every required field gets a value that satisfies its constraints."""
    props = schema.get("properties", {})
    values = {}
    for key, spec in props.items():
        if key not in schema.get("required", []):
            continue
        kind = spec.get("type")
        if "enum" in spec:
            values[key] = spec["enum"][0]
        elif kind == "string":
            text = f"E2E {key}"
            values[key] = text.ljust(spec.get("minLength", 0), "x")[: spec.get("maxLength", 200)]
        elif kind == "number":
            value = 250 if "amount" in key.lower() else max(spec.get("minimum", 0), 1)
            values[key] = min(value, spec.get("maximum", value))
        elif kind == "integer":
            values[key] = max(spec.get("minimum", 0), 1)
        elif kind == "boolean":
            values[key] = True
    return values


def resolve_group_blockers(designer, ws, wf_id):
    """Missing configuration through the API: approvals that name a group the workspace lacks use an existing one."""
    for _ in range(4):
        draft = designer.get(f"/workspaces/{ws}/workflows/{wf_id}/draft").json()
        report = designer.post(f"/workspaces/{ws}/workflows/{wf_id}/validate", {"revision": draft["revision"]}).json()
        blockers = [i for i in report["issues"] if i["severity"] == "blocker"]
        if not blockers:
            return draft, report
        unbound = [i for i in blockers if i["code"] == "WF_GROUP_UNBOUND" and i.get("nodeId")]
        if len(unbound) != len(blockers):
            return draft, report
        for issue in unbound:
            node = next(n for n in draft["definition"]["nodes"] if n["id"] == issue["nodeId"])
            finance = "financ" in (node["id"] + node["label"]).lower()
            node["config"]["candidateGroupRef"] = "finance-approvers" if finance else "managers"
        designer.put(f"/workspaces/{ws}/workflows/{wf_id}/draft", {"definition": draft["definition"]},
                     if_match=f'"r{draft["revision"]}"')
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf_id}/draft").json()
    return draft, designer.post(f"/workspaces/{ws}/workflows/{wf_id}/validate", {"revision": draft["revision"]}).json()


def approve_until_done(ws, run_id, approvers, timeout=150):
    deadline = time.time() + timeout
    run = None
    while time.time() < deadline:
        run = approvers[0].get(f"/workspaces/{ws}/runs/{run_id}").json()
        if run["lifecycle"] in TERMINAL:
            return run
        for approver in approvers:
            _, decided = claim_and_decide(approver, ws, run_id, "approve")
            if decided is not None:
                break
        time.sleep(1.5)
    return run


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-cost", type=float, default=0.05, help="fail when the generation cost more (USD)")
    parser.add_argument("--profile", default="economy")
    args = parser.parse_args()

    dana, paul, rhea, mani, fin, fred = (Client(u) for u in (
        "dana.designer", "paul.publisher", "rhea.requester", "mani.manager", "fin.finance", "fred.facilities"))
    ws = next(w["id"] for w in dana.get("/me").json()["workspaces"] if w["key"] == "purchasing")

    status = dana.get(f"/workspaces/{ws}/ai/status").json()
    if not status.get("enabled"):
        print(f"SKIP scenarios 30-31: AI is not enabled here ({status.get('reason')}). Set OPENROUTER_API_KEY for the "
              "app and restart it to run Demo 7 against the real provider.")
        return 0
    profile = next((p for p in status["profiles"] if p["id"] == args.profile), None)
    if not profile:
        print(f"SKIP scenarios 30-31: profile '{args.profile}' is not available ({[p['id'] for p in status['profiles']]})")
        return 0
    requests.post(f"{STUB}/__reset", timeout=10)
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)

    print("Scenario 30: Demo 7, prompt -> AI draft -> edit -> publish -> execute")
    request = {"description": DESCRIPTION, "name": f"E2E expense claim {uuid.uuid4().hex[:6]}", "profile": args.profile}
    key = str(uuid.uuid4())
    started = time.time()
    accepted = dana.post(f"/workspaces/{ws}/ai/drafts", request, key=key)
    check("POST /ai/drafts -> 202 with a Location to poll", accepted.status_code == 202
          and accepted.headers.get("Location", "").endswith(f"/ai/generations/{accepted.json().get('id')}"),
          f"{accepted.status_code} {accepted.text[:160]}")
    job = wait_generation(dana, ws, accepted.json()["id"])
    elapsed = time.time() - started
    check("the generation succeeded", job.get("status") == "succeeded",
          f"{job.get('status')} in {elapsed:.0f}s {str(job.get('problem'))[:200]}")
    if job.get("status") != "succeeded":
        return finish(1)
    result = job["result"]
    generation = result["generation"]
    wf_id = result["workflow"]["id"]
    print(f"    model={generation['model']} attempts={generation['attempts']} cost={generation.get('costUsd')} "
          f"time={elapsed:.0f}s workflow={result['workflow']['key']}")
    check(f"cost within USD {args.max_cost}", generation.get("costUsd") is not None and generation["costUsd"] <= args.max_cost,
          str(generation.get("costUsd")))
    check("draft revision 1 created, nothing published", result["draft"]["revision"] == 1
          and result["workflow"].get("activeRelease") is None)
    check("self-approval denied in the draft", result["draft"]["definition"]["security"]["selfApproval"] == "deny")
    check("review notes present (assumptions and missing configuration are lists)",
          isinstance(result["assumptions"], list) and isinstance(result["missingConfiguration"], list))

    draft, report = resolve_group_blockers(dana, ws, wf_id)
    blockers = [f"{i['code']}@{i.get('nodeId')}" for i in report["issues"] if i["severity"] == "blocker"]
    check("missing configuration resolved through the API; the draft passes validation", report["publishable"], str(blockers))
    if not report["publishable"]:
        return finish(1)

    definition = draft["definition"]
    definition["metadata"]["description"] = (definition["metadata"].get("description", "") + " Reviewed by e2e.")[:2000]
    edited = dana.put(f"/workspaces/{ws}/workflows/{wf_id}/draft", {"definition": definition}, if_match=f'"r{draft["revision"]}"')
    check("the designer edits the draft (If-Match) -> a new revision", edited.status_code == 200
          and edited.json()["revision"] == draft["revision"] + 1, f"{edited.status_code} {edited.text[:120]}")
    revision = edited.json()["revision"]

    explained = dana.post(f"/workspaces/{ws}/workflows/{wf_id}/explain", {"revision": revision})
    check("Explain answers in plain language", explained.status_code == 200 and len(explained.json().get("summary", "")) > 40,
          f"{explained.status_code} {explained.text[:120]}")

    published = paul.post(f"/workspaces/{ws}/workflows/{wf_id}/publish", {"draftRevision": revision, "acknowledgeWarnings": True}, idem=True)
    check("paul.publisher publishes the AI draft -> 201", published.status_code == 201, f"{published.status_code} {published.text[:160]}")
    if published.status_code != 201:
        return finish(1)

    form = rhea.get(f"/workspaces/{ws}/workflows/{wf_id}/start-form")
    check("the requester sees the generated request form", form.status_code == 200, str(form.status_code))
    values = sample_input(form.json()["inputSchema"])
    started_run = rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf_id, "input": values}, idem=True)
    check("rhea.requester starts a run -> 201", started_run.status_code == 201, f"{started_run.status_code} {started_run.text[:200]}")
    if started_run.status_code != 201:
        return finish(1)
    run_id = started_run.json()["id"]
    final = approve_until_done(ws, run_id, [mani, fin])
    check("approvals given; the run completes", final and final["lifecycle"] == "completed",
          f"{final and final['lifecycle']} {final and final.get('attention') or ''}")
    uses_stub = any(n["type"] == "http" for n in definition["nodes"])
    orders = stub_orders()
    check("exactly one purchase order when the draft calls procurement, none otherwise",
          len(orders) == (1 if uses_stub else 0), f"{len(orders)} orders, http step: {uses_stub}")
    print(f"    run {run_id} outcome: {(final or {}).get('outcome')}")

    print("Scenario 31: async semantics of the generation (no further provider calls)")
    replay = dana.post(f"/workspaces/{ws}/ai/drafts", request, key=key)
    check("replay with the same key -> 200 Idempotent-Replayed with the same job", replay.status_code == 200
          and replay.headers.get("Idempotent-Replayed") == "true" and replay.json()["id"] == accepted.json()["id"]
          and replay.json()["status"] == "succeeded", f"{replay.status_code}")
    reused = dana.post(f"/workspaces/{ws}/ai/drafts", {**request, "description": request["description"] + " More."}, key=key)
    check("same key, different body -> 409 IDEMPOTENCY_KEY_REUSED", reused.status_code == 409
          and reused.json().get("code") == "IDEMPOTENCY_KEY_REUSED", str(reused.status_code))
    check("missing Idempotency-Key -> 400", dana.post(f"/workspaces/{ws}/ai/drafts", request).status_code == 400)
    check("too short description -> 400 with field errors", dana.post(f"/workspaces/{ws}/ai/drafts", {"description": "short"},
                                                                       idem=True).status_code == 400)
    job_path = f"/workspaces/{ws}/ai/generations/{accepted.json()['id']}"
    check("another workspace's user gets 404", fred.get(job_path).status_code == 404)
    check("a requester without workflow access gets 403", rhea.get(job_path).status_code == 403)
    check("an unknown generation -> 404", dana.get(f"/workspaces/{ws}/ai/generations/{uuid.uuid4()}").status_code == 404)
    return finish(0)


def finish(early):
    results = base.results
    passed = sum(1 for _, ok in results if ok)
    print(f"\nRESULT: {passed}/{len(results)} checks passed")
    return 0 if early == 0 and passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
