"""Acceptance check of the single-VM kit through HTTPS: real Keycloak sign-in form, real API (docs/sovereign-deployment.md §9).

Needs three users made with scripts/add-user.sh, sharing one password (test VMs only):
  ada.admin      groups orqix-platform-admins, svm-admins
  rex.requester  group  svm-requesters
  mia.manager    group  svm-managers

  python tests/backup_restore_check.py before    # workspace + access, publish examples/simple-review.json,
                                                 # run 1 to completion, run 2 left waiting with a file attached
  ... scripts/backup.sh, destroy the volume, scripts/restore.sh <set> ...
  python tests/backup_restore_check.py after     # run 1 and its history unchanged, file of run 2 byte-identical,
                                                 # run 2 approved and completed after the restore

Environment: SVM_URL (default https://localhost:28443), SVM_CA (CA or self-signed certificate file), SVM_PW (password),
SVM_DEFINITION (path of examples/simple-review.json), SVM_STATE (state file, default svm-state.json).
Requires the Python package requests. Exit code 0 = every check passed.
"""
import hashlib
import json
import os
import re
import sys
import time
import uuid
from html import unescape

import requests

BASE = os.environ.get("SVM_URL", "https://localhost:28443").rstrip("/")
STATE = os.environ.get("SVM_STATE", "svm-state.json")


class Client:
    """Browser-equivalent session: authorization code flow through the login form, CSRF header like the SPA."""

    def __init__(self, username):
        self.s = requests.Session()
        self.s.verify = os.environ["SVM_CA"]
        r = self.s.get(f"{BASE}/oauth2/authorization/orqix", timeout=60)
        m = re.search(r'action="([^"]+login-actions/authenticate[^"]*)"', r.text)
        if not m:
            raise SystemExit(f"login form not found ({r.status_code} {r.url})")
        r = self.s.post(unescape(m.group(1)), data={"username": username, "password": os.environ["SVM_PW"]}, timeout=60)
        if self.s.get(f"{BASE}/api/v1/me", timeout=60).status_code != 200:
            raise SystemExit(f"login failed for {username}: ended at {r.url.split('?')[0]}")
        print(f"signed in as {username}")

    def headers(self, idem=None, if_match=None):
        h = {"Accept": "application/json"}
        if self.s.cookies.get("XSRF-TOKEN"):
            h["X-XSRF-TOKEN"] = self.s.cookies.get("XSRF-TOKEN")
        if idem:
            h["Idempotency-Key"] = idem
        if if_match:
            h["If-Match"] = if_match
        return h

    def get(self, path, **params):
        return self.s.get(f"{BASE}/api/v1{path}", params=params, headers=self.headers(), timeout=60)

    def post(self, path, body=None, idem=False):
        return self.s.post(f"{BASE}/api/v1{path}", json=body or {},
                           headers=self.headers(str(uuid.uuid4()) if idem else None), timeout=60)

    def put(self, path, body, if_match=None):
        return self.s.put(f"{BASE}/api/v1{path}", json=body, headers=self.headers(if_match=if_match), timeout=60)


def ok(r, label):
    if r.status_code not in (200, 201):
        raise SystemExit(f"FAIL {label}: {r.status_code} {r.text[:400]}")
    print(f"  ok {label} ({r.status_code})")
    return r.json() if r.content else {}


def retry(call, label, timeout=90):
    """Access changes reach the permission cache within about 50 s."""
    deadline = time.time() + timeout
    while True:
        r = call()
        if r.status_code in (200, 201) or time.time() > deadline:
            return ok(r, label)
        time.sleep(5)


def group(name):
    return {"type": "idpGroup", "value": name}


def wait_end(client, wid, rid):
    for _ in range(60):
        run = client.get(f"/workspaces/{wid}/runs/{rid}").json()
        if run["lifecycle"] not in ("running", "waiting"):
            return run
        time.sleep(2)
    return run


def approve(manager, wid, rid):
    task = None
    for _ in range(30):
        r = manager.get(f"/workspaces/{wid}/tasks", view="claimable", limit=100)
        task = next((t for t in r.json().get("items", []) if t["runId"] == rid), None) if r.status_code == 200 else None
        if task:
            break
        time.sleep(3)
    if not task:
        raise SystemExit("FAIL the manager sees no task")
    claimed = ok(manager.post(f"/workspaces/{wid}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]}), "claim")
    ok(manager.post(f"/workspaces/{wid}/tasks/{task['id']}/decision",
                    {"expectedVersion": claimed["version"], "action": "approve"}, idem=True), "approve")


def summary(client, wid, rid):
    run = client.get(f"/workspaces/{wid}/runs/{rid}").json()
    events = client.get(f"/workspaces/{wid}/runs/{rid}/events", limit=100).json()["items"]
    return {"reference": run["reference"], "lifecycle": run["lifecycle"], "outcome": run["outcome"],
            "endedAt": run["endedAt"], "input": run["input"],
            "events": [[e["seq"], e["type"], e.get("nodeId"), (e.get("actor") or {}).get("displayName"), e["occurredAt"]]
                       for e in events],
            "tasks": [[t["nodeId"], t["status"], (t.get("assignee") or {}).get("displayName")] for t in run["tasks"]]}


def before():
    ada = Client("ada.admin")
    suffix = uuid.uuid4().hex[:6]
    org = ok(ada.post("/admin/organizations", {"key": f"svm-{suffix}", "name": f"Sovereign test {suffix}"}),
             "create organization")
    wid = ok(ada.post("/admin/workspaces", {"organizationId": org["id"], "key": f"ops-{suffix}", "name": "Operations",
                                            "isTest": False, "initialAdmins": [group("svm-admins")]}),
             "create workspace")["id"]
    for role, grp in (("WORKFLOW_DESIGNER", "svm-admins"), ("PUBLISHER", "svm-admins"), ("OPERATOR", "svm-admins"),
                      ("APPROVER", "svm-managers")):
        retry(lambda: ada.post(f"/workspaces/{wid}/access/role-bindings", {"role": role, "principal": group(grp)}),
              f"role {role} -> {grp}")
    ok(ada.post(f"/workspaces/{wid}/access/grants", {"permission": "workflow.start", "principal": group("svm-requesters")}),
       "grant workflow.start -> svm-requesters")
    ok(ada.put(f"/workspaces/{wid}/groups/managers", {"name": "Managers", "members": [group("svm-managers")]}),
       "approval group managers")
    key = f"svm-review-{uuid.uuid4().hex[:6]}"
    wf = retry(lambda: ada.post(f"/workspaces/{wid}/workflows", {"key": key, "name": "Sovereign review",
                                                                  "templateId": "purchase-approval"}), "create workflow")
    draft = ok(ada.get(f"/workspaces/{wid}/workflows/{wf['id']}/draft"), "get draft")
    definition = json.load(open(os.environ["SVM_DEFINITION"], encoding="utf-8"))
    definition["workflowKey"] = key
    saved = ok(ada.put(f"/workspaces/{wid}/workflows/{wf['id']}/draft", {"definition": definition},
                       if_match=f'"r{draft["revision"]}"'), "replace draft with examples/simple-review.json")
    ok(ada.post(f"/workspaces/{wid}/workflows/{wf['id']}/publish",
                {"draftRevision": saved["revision"], "acknowledgeWarnings": True}, idem=True), "publish release 1")

    rex, mia = Client("rex.requester"), Client("mia.manager")
    start = lambda title, amount: retry(lambda: rex.post(f"/workspaces/{wid}/runs", {"workflowId": wf["id"], "input": {
        "title": title, "purpose": "Sovereign kit acceptance check", "costCenter": "IT", "amount": amount}}, idem=True),
        f"start '{title}'")
    run1 = start("Replacement laptop", 1250.50)
    approve(mia, wid, run1["id"])
    end = wait_end(rex, wid, run1["id"])
    if end["lifecycle"] != "completed":
        raise SystemExit(f"FAIL run 1 is {end['lifecycle']}")
    print(f"  {end['reference']} {end['lifecycle']} {end['outcome']}")

    run2 = start("Server rack", 9800)
    content = ("Quotation for server rack\n" + uuid.uuid4().hex * 2000).encode()
    r = rex.s.post(f"{BASE}/api/v1/workspaces/{wid}/runs/{run2['id']}/attachments",
                   headers=rex.headers(str(uuid.uuid4())), files={"file": ("quotation.txt", content, "text/plain")},
                   data={"description": "vendor quote"}, timeout=60)
    att = ok(r, "attach quotation.txt to run 2 (left waiting)")
    state = {"workspaceId": wid, "run1": run1["id"], "run1Summary": summary(ada, wid, run1["id"]),
             "run2": run2["id"], "attachmentId": att["id"], "sha256": hashlib.sha256(content).hexdigest()}
    json.dump(state, open(STATE, "w"), indent=1)
    print(f"  state written to {STATE}")


def after():
    st = json.load(open(STATE))
    wid = st["workspaceId"]
    ada, rex, mia = Client("ada.admin"), Client("rex.requester"), Client("mia.manager")
    failures = 0

    def check(name, cond, detail=""):
        nonlocal failures
        failures += 0 if cond else 1
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}{(' - ' + detail) if detail else ''}")

    now = summary(ada, wid, st["run1"])
    for k, v in st["run1Summary"].items():
        check(f"run 1 {k} unchanged", now[k] == v)
    print("    history:", ", ".join(e[1] for e in now["events"]))
    run2 = rex.get(f"/workspaces/{wid}/runs/{st['run2']}").json()
    check("run 2 still waiting at the review", run2["lifecycle"] == "waiting", run2["lifecycle"])
    body = rex.s.get(f"{BASE}/api/v1/workspaces/{wid}/runs/{st['run2']}/attachments/{st['attachmentId']}/content",
                     headers=rex.headers(), timeout=60)
    check("file of run 2 byte-identical", body.status_code == 200 and hashlib.sha256(body.content).hexdigest() == st["sha256"],
          f"{len(body.content)} bytes")
    approve(mia, wid, st["run2"])
    end = wait_end(rex, wid, st["run2"])
    check("run 2 completes after the restore", end["lifecycle"] == "completed", f"{end['reference']} {end['outcome']}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    {"before": before, "after": after}[sys.argv[1] if len(sys.argv) > 1 else "missing"]()
