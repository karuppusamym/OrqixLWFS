"""Wait until a date end to end (scenario 18), on the Docker stack.

  18  Start-date onboarding (examples/start-date-onboarding.json): a Date question on the request form, a manager's
      approval, then a wait that ends on the date at a UTC time.
        a  A wait that really ends: the date is today and the time of day (atTime) is about two minutes from now, so the
           real timer runs; the step reports its due moment in UTC and the run does not finish before it.
        b  A date in the past ends the wait at once (no timer to wait for).
        c  The form refuses a date that does not exist, text, a missing date, and a date more than 366 days ahead
           (field error on /input/startDate); the last allowed day is accepted (and cancelled afterwards).
        d  A wait on a field that is not a date is a blocker in the check, in business language.

The first part takes about two to three minutes (the real timer). Needs the dev seed group `managers`.

    python tools/api-e2e/e2e_dates.py
"""
import json
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import e2e_scenarios as base
from e2e_scenarios import check, wait_run
from orqix_client import Client

SAMPLE = Path(__file__).resolve().parents[2] / "examples" / "start-date-onboarding.json"


def workspace(client):
    return next(w["id"] for w in client.get("/me").json()["workspaces"] if w["key"] == "purchasing")


def utc_now():
    return datetime.now(timezone.utc)


def done_states():
    return ("completed", "needsAttention", "cancelled")


def new_workflow(designer, publisher, ws, mutate=None, publish=True):
    key = f"e2e-dates-{uuid.uuid4().hex[:8]}"
    definition = json.loads(SAMPLE.read_text(encoding="utf-8"))
    definition["workflowKey"] = key
    if mutate:
        mutate(definition)
    created = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": f"Start date {key}"})
    check("workflow created", created.status_code == 201, f"{created.status_code} {created.text[:200]}")
    wf = created.json()
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()
    saved = designer.put(f"/workspaces/{ws}/workflows/{wf['id']}/draft", {"definition": definition},
                         if_match=f'"r{draft["revision"]}"')
    check("the draft with a date wait is saved", saved.status_code == 200, f"{saved.status_code} {saved.text[:200]}")
    revision = saved.json()["revision"]
    if not publish:
        return wf["id"], revision
    published = publisher.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish",
                               {"draftRevision": revision, "acknowledgeWarnings": True}, idem=True)
    check("the workflow is published", published.status_code == 201, f"{published.status_code} {published.text[:300]}")
    return wf["id"], published.json()


def start(rhea, ws, wf_id, start_date, expect=201):
    body = {"workflowId": wf_id, "input": {"employeeName": "Ada Lovelace"}}
    if start_date is not None:
        body["input"]["startDate"] = start_date
    return rhea.post(f"/workspaces/{ws}/runs", body, idem=True)


def confirm(mani, ws, run_id):
    tasks = mani.get(f"/workspaces/{ws}/tasks", view="claimable", limit=100).json()["items"]
    task = next((t for t in tasks if t["runId"] == run_id), None)
    if task is None:
        return None
    claimed = mani.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]})
    return mani.post(f"/workspaces/{ws}/tasks/{task['id']}/decision",
                     {"expectedVersion": claimed.json()["version"], "action": "approve"}, idem=True)


def events(client, ws, run_id):
    items, after = [], 0
    while True:
        page = client.get(f"/workspaces/{ws}/runs/{run_id}/events", after=after, limit=100).json()
        items.extend(page["items"])
        if not page["hasMore"]:
            return items
        after = page["nextAfter"]


def main():
    rhea, mani, paul, olga, dana = (Client(u) for u in (
        "rhea.requester", "mani.manager", "paul.publisher", "olga.operator", "dana.designer"))
    ws = workspace(rhea)

    print("Scenario 18a: a wait that really ends on its date and time (UTC)")
    due = (utc_now() + timedelta(minutes=2, seconds=30)).replace(second=0, microsecond=0)
    date, time_of_day = due.strftime("%Y-%m-%d"), due.strftime("%H:%M")

    def set_time(d):
        next(n for n in d["nodes"] if n["id"] == "untilStart")["config"]["atTime"] = time_of_day

    wf, release = new_workflow(dana, paul, ws, set_time)
    run = start(rhea, ws, wf, date).json()
    started = rhea.get(f"/workspaces/{ws}/runs/{run['id']}")
    check("the request starts and waits for the manager", started.status_code == 200
          and started.json()["lifecycle"] == "waiting", started.text[:200])
    approved = confirm(mani, ws, run["id"])
    check("the manager confirms", approved is not None and approved.status_code == 201,
          str(approved and approved.status_code))
    waiting = wait_run(rhea, ws, run["id"], lambda r: any(s["kind"] == "wait" for s in r["currentSteps"]), 30)
    step = next((s for s in waiting["currentSteps"] if s["kind"] == "wait"), None)
    expected_due = f"{date}T{time_of_day}:00Z"
    check("the wait reports its due moment: the date at the time of day, in UTC",
          step is not None and datetime.fromisoformat(step["dueAt"].replace("Z", "+00:00")) == due, str(step))
    check("the run is waiting, not running", waiting["lifecycle"] == "waiting", waiting["lifecycle"])
    time.sleep(5)
    still = rhea.get(f"/workspaces/{ws}/runs/{run['id']}").json()
    check("the wait does not end early", still["lifecycle"] == "waiting" and utc_now() < due, still["lifecycle"])
    end = wait_run(rhea, ws, run["id"], lambda r: r["lifecycle"] in done_states(), 240)
    check("the request finishes after the due moment", end["lifecycle"] == "completed"
          and end["outcome"] == "First day reached" and utc_now() >= due - timedelta(seconds=2),
          f"{end['lifecycle']} {end.get('outcome')}")
    log = events(rhea, ws, run["id"])
    types = [e["type"] for e in log]
    check("the timeline has one WAIT_STARTED before one WAIT_ENDED", types.count("WAIT_STARTED") == 1
          and types.count("WAIT_ENDED") == 1 and types.index("WAIT_STARTED") < types.index("WAIT_ENDED"), str(types))
    started_event = next(e for e in log if e["type"] == "WAIT_STARTED")
    check("WAIT_STARTED names the end of the wait", started_event["data"].get("until", "").startswith(expected_due[:16]),
          str(started_event["data"]))
    check("the timeline has no gaps", [e["seq"] for e in log] == list(range(1, len(log) + 1)))

    print("Scenario 18b: a date in the past ends the wait at once")
    yesterday = (utc_now() - timedelta(days=1)).strftime("%Y-%m-%d")
    run2 = start(rhea, ws, wf, yesterday).json()
    check("the request with a past date is accepted", run2.get("id") is not None, str(run2))
    confirm(mani, ws, run2["id"])
    t0 = time.time()
    end2 = wait_run(rhea, ws, run2["id"], lambda r: r["lifecycle"] in done_states(), 60)
    check("it finishes without waiting", end2["lifecycle"] == "completed" and time.time() - t0 < 45,
          f"{end2['lifecycle']} after {time.time() - t0:.0f}s")
    types2 = [e["type"] for e in events(rhea, ws, run2["id"])]
    check("the wait started and ended", "WAIT_STARTED" in types2 and "WAIT_ENDED" in types2, str(types2))

    print("Scenario 18c: the form refuses what a wait cannot use")
    far = (utc_now() + timedelta(days=400)).strftime("%Y-%m-%d")
    for label, value, code in [("a day that does not exist", "2026-02-30", "format"), ("text", "tomorrow", "format"),
                               ("a date and time", "2026-10-14T09:00:00Z", "format"), ("more than 366 days ahead", far, "formatMaximum"),
                               ("no date", None, "required")]:
        r = start(rhea, ws, wf, value)
        fields = r.json().get("fieldErrors", []) if r.status_code == 422 else []
        check(f"refused: {label}", r.status_code == 422 and any(
            f["pointer"] == "/input/startDate" and f["code"] == code for f in fields), f"{r.status_code} {r.text[:200]}")
    last = (utc_now() + timedelta(days=366)).strftime("%Y-%m-%d")
    r = start(rhea, ws, wf, last)
    check("the last allowed day (366 days ahead) is accepted", r.status_code == 201, f"{r.status_code} {r.text[:200]}")
    if r.status_code == 201:
        version = rhea.get(f"/workspaces/{ws}/runs/{r.json()['id']}").json()["version"]
        cancel = olga.post(f"/workspaces/{ws}/runs/{r.json()['id']}/cancel",
                           {"reason": "Test run", "expectedVersion": version}, idem=True)
        check("the long-running test request is cancelled", cancel.status_code == 200, str(cancel.status_code))

    print("Scenario 18d: a wait on something that is not a date is a blocker")

    def wrong_field(d):
        next(n for n in d["nodes"] if n["id"] == "untilStart")["config"] = {"untilField": "/input/employeeName"}

    bad, revision = new_workflow(dana, paul, ws, wrong_field, publish=False)
    report = dana.post(f"/workspaces/{ws}/workflows/{bad}/validate", {"revision": revision}).json()
    issue = next((i for i in report["issues"] if i["code"] == "WF_WAIT_FIELD_TYPE"), None)
    check("the check reports WF_WAIT_FIELD_TYPE in plain words", issue is not None and "not a date" in issue["message"]
          and issue["severity"] == "blocker", str(report["issues"]))
    refused = paul.post(f"/workspaces/{ws}/workflows/{bad}/publish",
                        {"draftRevision": revision, "acknowledgeWarnings": True}, idem=True)
    check("publishing is refused", refused.status_code == 422, str(refused.status_code))

    results = base.results
    passed = sum(1 for _, ok in results if ok)
    print(f"\nRESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
