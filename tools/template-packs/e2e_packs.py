"""Regional template packs end to end on a Docker stack (docs/regional-template-packs.md).

For each chosen pack template: create a workflow from the built-in template, publish it, start the sample requests
of examples/fixtures/pack-scenarios.json as the requester, decide every human step with the approvers until the
request ends, and check the business outcome. Approval due dates on a working calendar are checked against that
calendar (working weekday, not a public holiday). Samples that need a time limit to pass are skipped here; the
compiler test covers them with the simulator.

Uses the dev realm's test users (passwords read from the realm file, never printed). wade.admin creates the task
groups the packs use, backed by the managers and finance IdP groups; dana.designer creates, paul.publisher
publishes, rhea.requester starts.

    ORQIX_BASE_URL=http://localhost:38480 python tools/template-packs/e2e_packs.py              # one template per region
    ORQIX_BASE_URL=http://localhost:38480 python tools/template-packs/e2e_packs.py --all        # every template, every sample
"""
import argparse
import datetime as dt
import json
import sys
import time
import uuid
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))
from orqix_client import Client  # noqa: E402

HEADLINE = ["pack-in-loan-exception", "pack-eu-permit", "pack-ke-group-loan", "pack-ng-kyc-refresh",
            "pack-in-vendor-onboarding", "pack-ke-grant"]
APPROVERS = ["mani.manager", "max.manager", "fin.finance"]
MEMBERS = [{"type": "idpGroup", "value": "orqix-purchasing-managers"},
           {"type": "idpGroup", "value": "orqix-purchasing-finance"}]

results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail and not ok else ""))
    return ok


def ensure_groups(admin, ws, keys):
    existing = {g["key"]: g for g in admin.get(f"/workspaces/{ws}/groups").json()["items"]}
    for key in sorted(keys):
        body = {"name": key.replace("-", " ").capitalize(), "members": MEMBERS}
        if key in existing:
            body["expectedRevision"] = existing[key]["revision"]
        r = admin.put(f"/workspaces/{ws}/groups/{key}", body)
        if r.status_code not in (200, 201):
            raise RuntimeError(f"group {key}: {r.status_code} {r.text[:200]}")


def check_due(task, definition):
    node = next(n for n in definition["nodes"] if n["id"] == task["nodeId"])
    cal = node["config"].get("calendar")
    if not cal or not task.get("dueAt"):
        return
    due = dt.datetime.fromisoformat(task["dueAt"].replace("Z", "+00:00")).astimezone(ZoneInfo(cal["zone"]))
    working = due.isoweekday() in cal["weekdays"] and due.date().isoformat() not in cal["holidays"]
    start, end = cal["start"], cal["end"]
    within = start <= due.strftime("%H:%M") <= end
    check(f"    {task['nodeId']} due {due:%a %Y-%m-%d %H:%M} {cal['zone']} is inside working time", working and within)


def drive(clients, ws, run_id, approvals, definition, timeout=180):
    """Decide open tasks of the run until it ends; returns (run, decided node ids)."""
    decided = []
    deadline = time.time() + timeout
    while time.time() < deadline:
        run = clients["rhea.requester"].get(f"/workspaces/{ws}/runs/{run_id}").json()
        if run["lifecycle"] in ("completed", "cancelled", "needsAttention"):
            return run, decided
        acted = False
        for name in APPROVERS:
            c = clients[name]
            items = c.get(f"/workspaces/{ws}/tasks", view="claimable", limit=100).json().get("items", [])
            task = next((t for t in items if t["runId"] == run_id), None)
            if not task:
                continue
            check_due(task, definition)
            claimed = c.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]})
            if claimed.status_code != 200:
                continue
            action = approvals.get(task["nodeId"], {}).get("outcome", "approve")
            body = {"expectedVersion": claimed.json()["version"], "action": action}
            if action == "reject":
                body["reason"] = "Sample rejection reason"
            r = c.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", body, idem=True)
            if r.status_code in (200, 201):
                decided.append(task["nodeId"])
                acted = True
                break
        if not acted:
            time.sleep(1.5)
    return clients["rhea.requester"].get(f"/workspaces/{ws}/runs/{run_id}").json(), decided


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="every pack template and every sample without a timeout")
    args = parser.parse_args()
    fixtures = json.loads((ROOT / "examples" / "fixtures" / "pack-scenarios.json").read_text(encoding="utf-8"))["templates"]
    chosen = sorted(fixtures) if args.all else HEADLINE

    names = ["wade.admin", "dana.designer", "paul.publisher", "rhea.requester"] + APPROVERS
    clients = {n: Client(n) for n in names}
    designer = clients["dana.designer"]
    ws = next(w["id"] for w in designer.get("/me").json()["workspaces"] if w["key"] == "purchasing")

    print("Template picker")
    listed = {t["id"]: t for t in designer.get(f"/workspaces/{ws}/templates").json()["items"]}
    packs = [t for t in listed.values() if t.get("pack")]
    check("the catalog offers every pack template", all(k in listed for k in fixtures), f"{len(packs)} listed")
    check("pack templates carry region and industry", all(t.get("region") and t.get("industry") for t in packs))
    check("regions India, EU, Kenya and Nigeria are offered",
          {"India", "European Union", "Kenya", "Nigeria"} <= {t["region"] for t in packs})
    check("general templates have no region", listed["purchase-approval"].get("region") is None)

    groups = {g for k in chosen for g in listed[k]["requiredGroups"]}
    ensure_groups(clients["wade.admin"], ws, groups)
    print(f"Groups ready: {', '.join(sorted(groups))}")

    regions_done = set()
    for template in chosen:
        print(f"\n{template} ({listed[template]['region']}, {listed[template]['industry']})")
        key = f"{template.removeprefix('pack-')}-{uuid.uuid4().hex[:6]}"
        created = designer.post(f"/workspaces/{ws}/workflows",
                                {"key": key, "name": f"{listed[template]['name']} {key[-6:]}", "templateId": template})
        if not check("created from the template", created.status_code == 201, created.text[:200]):
            continue
        wf = created.json()
        draft = designer.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()
        published = clients["paul.publisher"].post(f"/workspaces/{ws}/workflows/{wf['id']}/publish",
                                                   {"draftRevision": draft["revision"], "acknowledgeWarnings": True},
                                                   idem=True)
        if not check("published", published.status_code == 201, published.text[:300]):
            continue
        definition = draft["definition"]
        samples = fixtures[template] if (args.all or template in HEADLINE) else fixtures[template][:1]
        for sample in samples:
            approvals = (sample.get("scenario") or {}).get("approvals", {})
            if any(a["outcome"] == "timeout" for a in approvals.values()):
                print(f"  (skipped live: {sample['name']} needs a time limit to pass)")
                continue
            start = clients["rhea.requester"].post(f"/workspaces/{ws}/runs", {"workflowId": wf["id"], "input": sample["input"]},
                                                   idem=True)
            if not check(f"{sample['name']}: started", start.status_code == 201, start.text[:300]):
                continue
            run, decided = drive(clients, ws, start.json()["id"], approvals, definition)
            approval_steps = {n["id"] for n in definition["nodes"] if n["type"] == "approval"}
            expected = [v for v in sample["visits"] if v in approval_steps]
            check(f"{sample['name']}: completed as {sample['outcome']}",
                  run["lifecycle"] == "completed" and run["outcome"] == sample["outcome"],
                  f"lifecycle {run['lifecycle']}, outcome {run['outcome']}, decided {decided}")
            check(f"{sample['name']}: decided {', '.join(expected) or 'no human step'}",
                  all(v in decided for v in expected) and not any(s in decided for s in sample["skips"]),
                  f"decided {decided}")
        regions_done.add(listed[template]["region"])

    print(f"\nRegions run to completion: {', '.join(sorted(regions_done))}")
    passed = sum(results)
    print(f"RESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
