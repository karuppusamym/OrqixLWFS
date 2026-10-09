"""T-PERF-01 R0 smoke: virtual users start purchase requests, a manager approves, and everybody polls lists and tasks.

Each virtual user (VU) owns a requester session and a manager session (real Keycloak login, cookies, CSRF header)
and repeats this loop for the whole duration:

  start (POST /runs) -> find the task in the claimable inbox (GET /tasks) -> read it (GET /tasks/{id}) -> claim ->
  approve -> poll the run until it completes (GET /runs/{id}), with a list of runs and the notifications in between ->
  think time

Targets (docs/build/S9 T-PERF-01): p95 of the list and task APIs < 1 s, p95 of start < 2 s, no errors.

  python tools/perf/smoke.py --users 20 --minutes 10

Why Python and not Gatling: the S9 catalog names Gatling for the pilot load (T-PERF-02). Gatling would add a Maven module,
a JVM dependency set and its own OIDC login scripting to the build; the Keycloak form login with PKCE is already scripted
in tools/api-e2e/orqix_client.py. For the R0 smoke (20 users, 10 minutes, one application instance) Python threads are
sufficient and nothing is added to the product or its runtime. The measurement is client-side wall time of each HTTP
call on the same host (loopback), so network latency is not part of it.
"""
import argparse
import json
import random
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))
from orqix_client import BASE, Client  # noqa: E402

STUB = "http://localhost:8095"
LIST_TASK_CATEGORIES = ("list.tasks", "task.get", "list.runs", "run.get", "notifications")


class Recorder:
    def __init__(self):
        self.lock = threading.Lock()
        self.samples = []  # (category, status or error name, latency seconds, wall time)
        self.errors = []

    def add(self, category, status, latency, expected=(200, 201)):
        with self.lock:
            self.samples.append((category, status, latency, time.monotonic()))
            if status not in expected:
                self.errors.append((category, status))


COUNTER_LOCK = threading.Lock()


def bump(counters, name):
    with COUNTER_LOCK:
        counters[name] += 1


def timed(rec, category, call, expected=(200, 201)):
    started = time.perf_counter()
    try:
        response = call()
        status = response.status_code
    except requests.RequestException as e:
        response, status = None, type(e).__name__
    rec.add(category, status, time.perf_counter() - started, expected)
    return response


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p * (len(ordered) - 1))))]


def summarize(samples, categories=None):
    by = {}
    for category, status, latency, _ in samples:
        by.setdefault(category, []).append(latency)
    out = {}
    for category, values in sorted(by.items()):
        if categories and category not in categories:
            continue
        out[category] = {"count": len(values), "p50_ms": round(percentile(values, 0.5) * 1000),
                         "p95_ms": round(percentile(values, 0.95) * 1000),
                         "p99_ms": round(percentile(values, 0.99) * 1000), "max_ms": round(max(values) * 1000)}
    return out


def vu(index, deadline, rec, ctx, counters, stop):
    requester, manager, operator = ctx["requesters"][index], ctx["managers"][index], ctx["operators"][index]
    ws, wf = ctx["ws"], ctx["wf"]
    rnd = random.Random(index)
    time.sleep(index * 0.5)  # ramp up over ten seconds
    while time.monotonic() < deadline and not stop.is_set():
        body = {"workflowId": wf, "input": {"title": f"Perf {index}", "purpose": "T-PERF-01", "costCenter": "OPS",
                                            "amount": rnd.choice([120, 480, 1800, 2400, 4500])}}
        started = timed(rec, "start", lambda: requester.post(f"/workspaces/{ws}/runs", body, idem=True))
        if started is None or started.status_code != 201:
            time.sleep(1)
            continue
        run_id = started.json()["id"]
        bump(counters, "started")
        # the manager finds the task in the claimable inbox (polling like the SPA's inbox view)
        task = None
        for _ in range(40):
            page = timed(rec, "list.tasks", lambda: manager.get(f"/workspaces/{ws}/tasks", view="claimable", limit=50))
            if page is not None and page.status_code == 200:
                task = next((t for t in page.json()["items"] if t["runId"] == run_id), None)
                if task:
                    break
            time.sleep(0.5)
        if not task:
            bump(counters, "task_missing")
            continue
        detail = timed(rec, "task.get", lambda: manager.get(f"/workspaces/{ws}/tasks/{task['id']}"))
        version = detail.json()["version"] if detail is not None and detail.status_code == 200 else task["version"]
        claimed = timed(rec, "task.claim", lambda: manager.post(f"/workspaces/{ws}/tasks/{task['id']}/claim",
                                                                  {"expectedVersion": version}))
        if claimed is None or claimed.status_code != 200:
            bump(counters, "claim_failed")
            continue
        decided = timed(rec, "task.decide", lambda: manager.post(
            f"/workspaces/{ws}/tasks/{task['id']}/decision",
            {"expectedVersion": claimed.json()["version"], "action": "approve"}, idem=True))
        if decided is None or decided.status_code != 201:
            bump(counters, "decide_failed")
            continue
        bump(counters, "approved")
        # poll the run until it completes; the operator's run list and the requester's notifications in between
        end = time.monotonic() + 90
        done = False
        polls = 0
        while time.monotonic() < end:
            run = timed(rec, "run.get", lambda: requester.get(f"/workspaces/{ws}/runs/{run_id}"))
            if run is not None and run.status_code == 200 and run.json()["lifecycle"] in ("completed", "needsAttention"):
                done = run.json()["lifecycle"] == "completed"
                break
            polls += 1
            if polls % 3 == 0:
                timed(rec, "list.runs", lambda: operator.get(f"/workspaces/{ws}/runs", workflowId=wf, limit=20))
                timed(rec, "notifications", lambda: requester.get(f"/workspaces/{ws}/me/notifications"))
            time.sleep(0.7)
        bump(counters, "completed" if done else "not_completed")
        timed(rec, "list.runs", lambda: operator.get(f"/workspaces/{ws}/runs", workflowId=wf, limit=20))
        time.sleep(rnd.uniform(0.5, 2.0))  # think time


def sample_docker(stats, stop):
    while not stop.is_set():
        try:
            out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}"],
                                 capture_output=True, text=True, timeout=60).stdout
            for line in out.splitlines():
                name, cpu, mem = line.split("|")
                if name in ("orqix-orqix-1", "orqix-postgres-1", "orqix-procurement-stub-1"):
                    stats.setdefault(name, []).append((float(cpu.rstrip("%")), mem.split("/")[0].strip()))
        except (subprocess.SubprocessError, ValueError):
            pass
        stop.wait(20)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--users", type=int, default=20)
    parser.add_argument("--minutes", type=float, default=10)
    parser.add_argument("--out", default=str(ROOT / "evidence" / "perf" / "T-PERF-01.json"))
    args = parser.parse_args()

    print(f"Logging in {args.users} virtual users ...", flush=True)
    names = [("rhea.requester", "mani.manager" if i % 2 else "max.manager", "olga.operator") for i in range(args.users)]
    with ThreadPoolExecutor(8) as pool:
        requesters = list(pool.map(Client, [n[0] for n in names]))
        managers = list(pool.map(Client, [n[1] for n in names]))
        operators = list(pool.map(Client, [n[2] for n in names]))
    ws = next(w["id"] for w in requesters[0].get("/me").json()["workspaces"] if w["key"] == "purchasing")
    designer, publisher = Client("dana.designer"), Client("paul.publisher")
    key = "perf-" + uuid.uuid4().hex[:8]
    wf = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": "Perf smoke " + key,
                                                      "templateId": "purchase-approval"}).json()["id"]
    revision = designer.get(f"/workspaces/{ws}/workflows/{wf}/draft").json()["revision"]
    published = publisher.post(f"/workspaces/{ws}/workflows/{wf}/publish", {
        "draftRevision": revision, "acknowledgeWarnings": True}, idem=True)
    assert published.status_code == 201, published.text[:200]
    requests.post(f"{STUB}/__reset", timeout=10)
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)

    rec = Recorder()
    counters = {k: 0 for k in ("started", "approved", "completed", "not_completed", "task_missing", "claim_failed",
                               "decide_failed")}
    stop = threading.Event()
    stats = {}
    started_at = datetime.now(timezone.utc)
    t0 = time.monotonic()
    deadline = t0 + args.minutes * 60
    ctx = {"requesters": requesters, "managers": managers, "operators": operators, "ws": ws, "wf": wf}
    sampler = threading.Thread(target=sample_docker, args=(stats, stop), daemon=True)
    sampler.start()
    threads = [threading.Thread(target=vu, args=(i, deadline, rec, ctx, counters, stop)) for i in range(args.users)]
    print(f"Running {args.users} virtual users for {args.minutes} minutes ...", flush=True)
    for t in threads:
        t.start()
    try:
        while any(t.is_alive() for t in threads):
            time.sleep(30)
            elapsed = time.monotonic() - t0
            recent = [s for s in rec.samples if s[3] > time.monotonic() - 30]
            print(f"  {elapsed:5.0f} s  started {counters['started']}  completed {counters['completed']}  "
                  f"errors {len(rec.errors)}  last 30 s: {len(recent) / 30:.1f} req/s", flush=True)
    except KeyboardInterrupt:
        stop.set()
    for t in threads:
        t.join()
    load_seconds = time.monotonic() - t0
    stop.set()

    # consistency: every approved run completed, exactly one order each
    time.sleep(3)
    orders = requests.get(f"{STUB}/__orders", timeout=10).json()
    operator = operators[0]
    page = operator.get(f"/workspaces/{ws}/runs", workflowId=wf, limit=100).json()
    lifecycles = {}
    cursor = None
    while True:
        params = {"workflowId": wf, "limit": 100}
        if cursor:
            params["cursor"] = cursor
        page = operator.get(f"/workspaces/{ws}/runs", **params).json()
        for item in page["items"]:
            lifecycles[item["lifecycle"]] = lifecycles.get(item["lifecycle"], 0) + 1
        cursor = page.get("nextCursor")
        if not cursor:
            break

    samples = rec.samples
    all_summary = summarize(samples)
    list_task = [s for s in samples if s[0] in LIST_TASK_CATEGORIES]
    lt_values = [s[2] for s in list_task]
    start_values = [s[2] for s in samples if s[0] == "start"]
    command_values = [s[2] for s in samples if s[0] in ("task.claim", "task.decide")]
    errors = {}
    for category, status in rec.errors:
        errors[f"{category}:{status}"] = errors.get(f"{category}:{status}", 0) + 1
    result = {
        "test": "T-PERF-01", "startedAt": started_at.isoformat(timespec="seconds"), "users": args.users,
        "durationSeconds": round(load_seconds), "target": "http://localhost:8080 (one application instance, Docker)",
        "requests": len(samples), "throughputRequestsPerSecond": round(len(samples) / load_seconds, 1),
        "perCategory": all_summary,
        "listAndTaskApis": {"count": len(lt_values), "p50_ms": round(percentile(lt_values, 0.5) * 1000),
                            "p95_ms": round(percentile(lt_values, 0.95) * 1000),
                            "p99_ms": round(percentile(lt_values, 0.99) * 1000),
                            "max_ms": round(max(lt_values) * 1000), "target_p95_ms": 1000},
        "start": {"count": len(start_values), "p50_ms": round(percentile(start_values, 0.5) * 1000),
                  "p95_ms": round(percentile(start_values, 0.95) * 1000),
                  "p99_ms": round(percentile(start_values, 0.99) * 1000), "max_ms": round(max(start_values) * 1000),
                  "target_p95_ms": 2000},
        "taskCommands": {"count": len(command_values), "p95_ms": round(percentile(command_values, 0.95) * 1000)},
        "errors": {"count": len(rec.errors), "byKind": errors},
        "flow": counters, "runLifecycles": lifecycles,
        "stubOrders": orders["count"], "stubCreateRequests": orders.get("createRequests"),
        "containerResources": {name: {"maxCpuPercent": max(c for c, _ in values),
                                      "lastMemory": values[-1][1]} for name, values in stats.items()},
    }
    checks = {
        "p95 list/task < 1000 ms": result["listAndTaskApis"]["p95_ms"] < 1000,
        "p95 start < 2000 ms": result["start"]["p95_ms"] < 2000,
        "no errors": len(rec.errors) == 0,
        "all approved runs completed": counters["completed"] == counters["approved"] and counters["not_completed"] == 0,
        "one order per completed run": orders["count"] == counters["completed"],
    }
    result["checks"] = {k: ("pass" if v else "fail") for k, v in checks.items()}
    result["result"] = "passed" if all(checks.values()) else "failed"
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("users", "durationSeconds", "requests", "throughputRequestsPerSecond",
                                             "listAndTaskApis", "start", "taskCommands", "errors", "flow",
                                             "stubOrders", "checks", "result")}, indent=2))
    print(f"Evidence: {args.out}")
    return 0 if result["result"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
