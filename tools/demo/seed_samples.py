"""Sample workflows and executions for a clean live demo, created through the public API only.

    ORQIX_BASE_URL=http://localhost:8080 ORQIX_STUB_URL=http://localhost:8095 python tools/demo/seed_samples.py

Run it against a stack that already holds the standard seed (see seed_demo.py; this script does not reset anything).
It imports, converts and publishes four clearly named samples and starts requests on them, leaving some open for
the presenter. It is idempotent: a sample whose workflow key already exists is not imported again, and a request whose
reference (business key) already exists on that workflow is not started again, so a re-run only fills gaps.

  Sample - Pega XPDL: amount routing     (key sample-pega-xpdl-routing)       medium.xpdl
  Sample - Pega XPDL: manager review     (key sample-pega-xpdl-review)        review-branch.xpdl, one request left OPEN
  Sample - Pega Blueprint (synthetic PDF) (key sample-pega-blueprint-purchase) synthetic-blueprint.pdf, one request left OPEN
  Sample - Camunda 7 purchase            (key sample-camunda-purchase)        camunda7-purchase.bpmn

Dev-realm users only; passwords are read from the realm file by orqix_client and never printed.
"""
import copy
import json
import os
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))
from orqix_client import BASE, Client  # noqa: E402

EXAMPLES = ROOT / "examples" / "migration"
STUB = os.environ.get("ORQIX_STUB_URL", "http://localhost:8095").rstrip("/")
_clients = {}


def who(user):
    if user not in _clients:
        _clients[user] = Client(user)
    return _clients[user]


def log(message):
    print(f"  {message}", flush=True)


def need(response, ok=(200, 201), what=""):
    if response.status_code not in ok:
        raise RuntimeError(f"{what}: HTTP {response.status_code} {response.text[:400]}")
    return response.json()


def workspace_id():
    me = need(who("rhea.requester").get("/me"), what="me")
    return next(w["id"] for w in me["workspaces"] if w["key"] == "purchasing")


def find_workflow(ws, key):
    items = need(who("dana.designer").get(f"/workspaces/{ws}/workflows", query=key, limit=100), what="list workflows")["items"]
    return next((w for w in items if w["key"] == key), None)


# ---- import / convert / publish -------------------------------------------------------------------------------


def upload_text(ws, file_name, source_format):
    body = {"fileName": Path(file_name).name, "sourceFormat": source_format, "content": (EXAMPLES / file_name).read_text(encoding="utf-8")}
    return need(who("dana.designer").post(f"/workspaces/{ws}/migrations", body), what=f"upload {file_name}")


def convert(ws, job_id, request):
    return need(who("dana.designer").post(f"/workspaces/{ws}/migrations/{job_id}/convert", request), what="convert")


def publish(ws, converted):
    wf_id = converted["workflow"]["id"]
    body = {"draftRevision": converted["draft"]["revision"], "acknowledgeWarnings": True}
    need(who("paul.publisher").post(f"/workspaces/{ws}/workflows/{wf_id}/publish", body, idem=True), what="publish")
    return wf_id


def input_schema(extra_required=(), cost_centres=("OPS", "IT", "HR")):
    return {"type": "object", "required": ["title", "costCenter", "amount", *extra_required], "additionalProperties": False,
            "properties": {
                "title": {"type": "string", "title": "What are you buying?", "minLength": 1, "maxLength": 120},
                "costCenter": {"type": "string", "enum": list(cost_centres), "title": "Cost center"},
                "amount": {"type": "number", "title": "Amount (USD)", "minimum": 0.01, "maximum": 1000000, "multipleOf": 0.01}}}


def import_xpdl(ws, key, name, file_name, extra):
    job = upload_text(ws, file_name, "pegaXpdl")
    request = {"workflowKey": key, "name": name, "acknowledgeInfo": True, "acknowledgeReview": True, "inputSchema": input_schema(), **extra}
    return publish(ws, convert(ws, job["id"], request))


def import_camunda(ws, key, name):
    job = upload_text(ws, "camunda7-purchase.bpmn", "bpmn")
    service = {"procure": {"connectionRef": "procurement", "definitionVersion": 1, "operation": "createOrder",
                           "inputMapping": {"title": "/input/title", "amount": "/input/amount", "costCenter": "/input/costCenter"},
                           "resultMapping": {"id": "/id", "status": "/status"}, "timeoutSeconds": 10}}
    request = {"workflowKey": key, "name": name, "acknowledgeInfo": True, "acknowledgeReview": True,
               "groupMappings": {"source-managers": "managers", "source-finance": "finance-approvers"},
               "serviceTaskMappings": service, "inputSchema": input_schema()}
    return publish(ws, convert(ws, job["id"], request))


def import_blueprint(ws, key, name):
    dana = who("dana.designer")
    pdf = EXAMPLES / "pega-blueprint" / "synthetic-blueprint.pdf"
    with open(pdf, "rb") as handle:
        r = dana.s.post(f"{BASE}/api/v1/workspaces/{ws}/migrations/blueprints", files={"file": (pdf.name, handle, "application/pdf")},
                        headers=dana._headers(), timeout=120)
    job = need(r, what="upload blueprint")
    view = need(dana.get(f"/workspaces/{ws}/migrations/{job['id']}/blueprint"), what="read transcription")
    t = copy.deepcopy(view["transcription"])
    # The same reviewed edits as the browser rehearsal: Finance SLA unit -> hours, purchase order integration excluded with a
    # reason, everything else marked reviewed (a proposed element becomes mapped).
    for sla in t.get("slas", []):
        if sla["id"] == "sla-finance-approval":
            sla["unit"] = "hours"
    for integration in t.get("integrations", []):
        integration["disposition"] = "excluded"
        integration["dispositionReason"] = "Purchase order export is out of scope for this workflow (sample)"
    for lst in ("stages", "roles", "fields", "steps", "decisions", "slas", "outcomes"):
        for element in t.get(lst, []):
            if element.get("disposition") == "proposed":
                element["disposition"] = "mapped"
    if t.get("caseType", {}).get("disposition") == "proposed":
        t["caseType"]["disposition"] = "mapped"
    saved = need(dana.put(f"/workspaces/{ws}/migrations/{job['id']}/blueprint",
                          {"expectedRevision": view["revision"], "transcription": t}), what="save transcription")
    request = {"workflowKey": key, "name": name, "acknowledgeInfo": True, "acknowledgeReview": True,
               "groupMappings": {"role-line-manager": "managers", "role-finance-officer": "finance-approvers"},
               "transcriptionSha256": saved["transcriptionSha256"]}
    return publish(ws, convert(ws, job["id"], request))


# ---- runs ------------------------------------------------------------------------------------------------------


def existing_reference(ws, wf_id, business_key):
    r = who("olga.operator").get(f"/workspaces/{ws}/runs", workflowId=wf_id, businessKey=business_key, limit=5)
    if r.status_code != 200:
        return None
    return next((x for x in r.json()["items"] if x.get("businessKey") == business_key), None)


def find_task(user, ws, run_id):
    for view in ("claimable", "assigned"):
        cursor = None
        while True:
            params = {"view": view, "limit": 100}
            if cursor:
                params["cursor"] = cursor
            r = who(user).get(f"/workspaces/{ws}/tasks", **params)
            if r.status_code != 200:
                break
            page = r.json()
            hit = next((t for t in page["items"] if t["runId"] == run_id), None)
            if hit:
                return hit
            cursor = page.get("nextCursor")
            if not cursor:
                break
    return None


def settle(ws, run_id, want_task_for=None, timeout=60):
    """Wait until the run has stopped moving: finished, or (when want_task_for is set) that user has its task."""
    deadline = time.time() + timeout
    run = None
    while time.time() < deadline:
        run = who("rhea.requester").get(f"/workspaces/{ws}/runs/{run_id}").json()
        if run["lifecycle"] != "running":
            return run
        if want_task_for and find_task(want_task_for, ws, run_id):
            return run
        time.sleep(1)
    return run


def decide(ws, user, run_id, action, reason=None, comment=None):
    task = None
    for _ in range(40):
        task = find_task(user, ws, run_id)
        if task:
            break
        time.sleep(0.5)
    if not task:
        raise RuntimeError(f"{user} has no task for run {run_id}")
    client = who(user)
    if task["status"] == "open" and not task.get("assignee"):
        task = need(client.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]}), what="claim")
    body = {"expectedVersion": task["version"], "action": action}
    if reason:
        body["reason"] = reason
    if comment:
        body["comment"] = comment
    need(client.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", body, idem=True), what=f"{action} by {user}")


def describe(run):
    text = run.get("outcome") or ""
    return f"{run['lifecycle']}{' / ' + str(text) if text else ''}"


def start(ws, wf_id, spec, summary):
    """spec: dict(ref, input, steps=[(user, action, comment/reason)], open_for=user|None). Returns a summary row."""
    label = spec["ref"]
    found = existing_reference(ws, wf_id, label)
    if found:
        summary.append((spec["sample"], label, found.get("reference", "?"), "exists, skipped"))
        return
    r = who("rhea.requester").post(f"/workspaces/{ws}/runs", {"workflowId": wf_id, "businessKey": label, "input": spec["input"]}, idem=True)
    run = need(r, what=f"start {label}")
    for user, action, text in spec.get("steps", []):
        if action == "approve":
            decide(ws, user, run["id"], "approve", comment=text)
        else:
            decide(ws, user, run["id"], "reject", reason=text)
    final = settle(ws, run["id"], want_task_for=spec.get("open_for"))
    state = describe(final)
    if spec.get("open_for"):
        state = f"OPEN, waiting for {spec['open_for']} ({final['lifecycle']})"
    summary.append((spec["sample"], label, run.get("reference", run["id"][:8]), state))
    log(f"{run.get('reference', run['id'][:8])}  {label:<34} {state}")


def purchase(title, centre, amount):
    return {"title": title, "costCenter": centre, "amount": amount}


def main():
    ws = workspace_id()
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)  # procurement stub answers normally (same call seed_demo makes)
    summary, workflows = [], []

    samples = [
        ("sample-pega-xpdl-routing", "Sample - Pega XPDL: amount routing",
         lambda: import_xpdl(ws, "sample-pega-xpdl-routing", "Sample - Pega XPDL: amount routing", "pega-xpdl/medium.xpdl", {}),
         [dict(ref="Routing: office chairs (4,999.99)", input=purchase("Office chairs for the new team area", "OPS", 4999.99)),
          dict(ref="Routing: network switches (5,000)", input=purchase("Network switches, branch rollout", "IT", 5000)),
          dict(ref="Routing: laptops (5,000.01)", input=purchase("Laptops for the analytics hires", "HR", 5000.01))]),
        ("sample-pega-xpdl-review", "Sample - Pega XPDL: manager review",
         lambda: import_xpdl(ws, "sample-pega-xpdl-review", "Sample - Pega XPDL: manager review", "pega-xpdl/review-branch.xpdl", {
             "groupMappings": {"Managers": "managers"},
             "approvalRoutes": {"review": {"approvedFlowId": "approvedPath", "rejectedFlowId": "rejectedPath"}}}),
         [dict(ref="Review: standing desks (approved)", input=purchase("Standing desks, third floor", "IT", 5000), steps=[("mani.manager", "approve", "Approved for the Q4 plan.")]),
          dict(ref="Review: espresso machine (rejected)", input=purchase("Espresso machine for the lounge", "HR", 5000), steps=[("mani.manager", "reject", "Not in this quarter's budget.")]),
          dict(ref="Review: monitors (open)", input=purchase("27-inch monitors for the design team", "OPS", 5000), open_for="mani.manager")]),
        ("sample-pega-blueprint-purchase", "Sample - Pega Blueprint (synthetic PDF)",
         lambda: import_blueprint(ws, "sample-pega-blueprint-purchase", "Sample - Pega Blueprint (synthetic PDF)"),
         [dict(ref="Blueprint: lab equipment (6,000)", input={"itemDescription": "Lab equipment, calibration bench", "costCenter": "Lab", "amount": 6000},
               steps=[("mani.manager", "approve", "Approved."), ("fin.finance", "approve", "Funds available.")]),
          dict(ref="Blueprint: safety signage (4,999.99)", input={"itemDescription": "Safety signage for the warehouse", "costCenter": "Warehouse", "amount": 4999.99},
               steps=[("mani.manager", "approve", "Approved.")]),
          dict(ref="Blueprint: patio heaters (rejected)", input={"itemDescription": "Patio heaters for the courtyard", "costCenter": "Facilities", "amount": 6000},
               steps=[("mani.manager", "reject", "Not justified for this budget.")]),
          dict(ref="Blueprint: forklift battery (open)", input={"itemDescription": "Replacement forklift battery", "costCenter": "Logistics", "amount": 7200}, open_for="mani.manager")]),
        ("sample-camunda-purchase", "Sample - Camunda 7 purchase",
         lambda: import_camunda(ws, "sample-camunda-purchase", "Sample - Camunda 7 purchase"),
         [dict(ref="Camunda: desk lamps (4,999.99)", input=purchase("Desk lamps for the support floor", "HR", 4999.99), steps=[("mani.manager", "approve", "Fine.")]),
          dict(ref="Camunda: storage array (5,000)", input=purchase("Storage array for the backup site", "IT", 5000),
               steps=[("mani.manager", "approve", "Approved."), ("fin.finance", "approve", "Within the capex plan.")])]),
    ]

    print(f"Seeding sample workflows into {BASE} ...")
    for key, name, importer, runs in samples:
        wf = find_workflow(ws, key)
        if wf and not wf.get("activeRelease"):
            # A previous run stopped between convert and publish: publish that draft instead of importing again.
            rev = need(who("paul.publisher").get(f"/workspaces/{ws}/workflows/{wf['id']}/draft"), what="draft")["revision"]
            wf_id = publish(ws, {"workflow": {"id": wf["id"]}, "draft": {"revision": rev}})
            state = "existing draft published"
        elif wf:
            wf_id, state = wf["id"], "exists, skipped"
        else:
            wf_id, state = importer(), "imported and published"
        workflows.append((key, name, state))
        log(f"{key}: {state}")
        for spec in runs:
            spec["sample"] = key
            start(ws, wf_id, spec, summary)

    print()
    print(f"{'Workflow key':<32} {'Workflow':<42} Status")
    for row in workflows:
        print(f"{row[0]:<32} {row[1]:<42} {row[2]}")
    print()
    print(f"{'Sample':<32} {'Request':<36} {'Ref':<10} State")
    for row in summary:
        print(f"{row[0]:<32} {row[1]:<36} {row[2]:<10} {row[3]}")
    print()
    print("Open items: sign in as mani.manager for the manager steps (passwords are in deploy/compose/keycloak/orqix-dev-realm.json).")


if __name__ == "__main__":
    main()
