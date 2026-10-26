"""S92 scale profile: a ramped, mixed load meaningfully larger than the T-PERF-01 smoke (20 users), for comparing one and
two application replicas behind a load balancer and for the replica-failure test (docs/build/S92).

Virtual users (VUs) are started one after the other over --ramp seconds and run until --minutes is over:

  workers (--worker-share, default 60 %): the T-PERF-01 loop. A requester starts a purchase request, a manager finds it in
      the claimable inbox, reads, claims and approves it, the requester polls the run until it completes.
  readers (the rest), three kinds in turn:
      operator: run list (first page, then a second page via the cursor), run detail of a run from the list, insights
          summary/throughput/steps (every 4th loop), shared assets, workflow catalog, notifications
      viewer:   same as operator (separate principal, separate policy cache key)
      manager:  claimable inbox, assigned inbox, completed tasks, workflow catalog, notifications

Every sample records category, status, latency, wall time and the replica that served it (the LB adds
X-Orqix-Upstream), so the result has a per-second timeline for the failure test. Optionally (--kill-after /
--kill-container) one replica container is killed with `docker kill` mid-load (no graceful shutdown) or stopped
gracefully (--kill-mode stop).

Consistency checks at the end, read from the API, the stub and the database (psql in the PostgreSQL container):
every approved run completed; exactly one stub order per completed run (stub de-duplicates by Idempotency-Key, so
re-sent effects show up as `replays`, never as extra orders); no run started twice for one idempotency key; no dead-
letter or stuck Flowable job; every engine job lock owner listed.

  python tools/perf/scale.py --base http://localhost:48080 --stub http://localhost:48095 --users 150 --ramp 120 \
      --minutes 8 --pg-container orqix-scale-1026-postgres-1 --app-containers orqix-scale-1026-orqix-1,... \
      --out evidence/s92/run-2rep-150.json

Measurement limits: client-side wall time over loopback from Python threads on the same machine as the stack; with
more than ~150 threads the client itself adds latency (GIL, scheduling). Treat numbers as relative (1 vs 2 replicas
on the same host), not as production capacity.
"""
import argparse
import json
import os
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
sys.path.insert(0, str(ROOT / "tools" / "perf"))


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p * (len(ordered) - 1))))]


class Recorder:
    def __init__(self):
        self.lock = threading.Lock()
        self.samples = []  # (category, status, latency s, monotonic t, upstream)

    def add(self, category, status, latency, upstream):
        with self.lock:
            self.samples.append((category, status, latency, time.monotonic(), upstream))


def timed(rec, category, call, expected=(200, 201)):
    started = time.perf_counter()
    try:
        response = call()
        status = response.status_code
        upstream = response.headers.get("X-Orqix-Upstream")
    except requests.RequestException as e:
        response, status, upstream = None, type(e).__name__, None
    rec.add(category, status if status not in expected else 200, time.perf_counter() - started, upstream)
    return response


COUNTER_LOCK = threading.Lock()


def bump(counters, name, n=1):
    with COUNTER_LOCK:
        counters[name] = counters.get(name, 0) + n


def worker(index, start_at, deadline, rec, ctx, counters, stop):
    requester, manager = ctx["requesters"][index], ctx["managers"][index]
    ws, wf = ctx["ws"], ctx["wf"]
    rnd = random.Random(index)
    stop.wait(max(0.0, start_at - time.monotonic()))
    while time.monotonic() < deadline and not stop.is_set():
        idem = str(uuid.uuid4())
        body = {"workflowId": wf, "input": {"title": f"Scale {index}", "purpose": "S92", "costCenter": "OPS",
                                            "amount": rnd.choice([120, 480, 1800, 2400, 4500])}}
        started = timed(rec, "start", lambda: requester.post(f"/workspaces/{ws}/runs", body, key=idem))
        if started is None or started.status_code not in (200, 201):
            # Same Idempotency-Key: a retry after a lost response must not create a second run.
            time.sleep(1)
            started = timed(rec, "start.retry", lambda: requester.post(f"/workspaces/{ws}/runs", body, key=idem))
            if started is None or started.status_code not in (200, 201):
                bump(counters, "start_failed")
                continue
            bump(counters, "start_retried")
        run_id = started.json()["id"]
        with COUNTER_LOCK:
            ctx["started_ids"].add(run_id)
        bump(counters, "started")
        task = None
        for _ in range(60):
            # sort=newest: the worker looks for the task it just created (the default sort is priority, oldest first,
            # where a large backlog would hide it; a real approver opens it from the notification instead).
            page = timed(rec, "list.tasks", lambda: manager.get(f"/workspaces/{ws}/tasks", view="claimable", limit=50,
                                                                 sort="newest"))
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
        dkey = str(uuid.uuid4())
        dbody = {"expectedVersion": claimed.json()["version"], "action": "approve"}
        decided = timed(rec, "task.decide", lambda: manager.post(f"/workspaces/{ws}/tasks/{task['id']}/decision",
                                                                   dbody, key=dkey))
        if decided is None or decided.status_code not in (200, 201):
            time.sleep(1)
            decided = timed(rec, "task.decide.retry", lambda: manager.post(
                f"/workspaces/{ws}/tasks/{task['id']}/decision", dbody, key=dkey))
            if decided is None or decided.status_code not in (200, 201):
                bump(counters, "decide_failed")
                continue
            bump(counters, "decide_retried")
        bump(counters, "approved")
        with COUNTER_LOCK:
            ctx["approved_ids"].add(run_id)
        end = time.monotonic() + 120
        done = False
        while time.monotonic() < end:
            run = timed(rec, "run.get", lambda: requester.get(f"/workspaces/{ws}/runs/{run_id}"))
            if run is not None and run.status_code == 200 and run.json()["lifecycle"] in ("completed", "needsAttention"):
                done = run.json()["lifecycle"] == "completed"
                break
            time.sleep(1.0)
        bump(counters, "completed" if done else "not_completed")
        time.sleep(rnd.uniform(1.0, 3.0))


def reader(index, kind, client, start_at, deadline, rec, ctx, stop):
    ws, wf = ctx["ws"], ctx["wf"]
    rnd = random.Random(1000 + index)
    stop.wait(max(0.0, start_at - time.monotonic()))
    loop = 0
    while time.monotonic() < deadline and not stop.is_set():
        loop += 1
        if kind == "manager":
            timed(rec, "list.tasks", lambda: client.get(f"/workspaces/{ws}/tasks", view="claimable", limit=50))
            timed(rec, "list.tasks.assigned", lambda: client.get(f"/workspaces/{ws}/tasks", view="assigned", limit=50))
            timed(rec, "list.tasks.completed", lambda: client.get(f"/workspaces/{ws}/tasks", view="completed", limit=50))
        else:
            page = timed(rec, "list.runs", lambda: client.get(f"/workspaces/{ws}/runs", limit=20))
            if page is not None and page.status_code == 200:
                body = page.json()
                if body.get("nextCursor"):
                    timed(rec, "list.runs.page2", lambda: client.get(f"/workspaces/{ws}/runs", limit=20,
                                                                       cursor=body["nextCursor"]))
                if body["items"]:
                    rid = rnd.choice(body["items"])["id"]
                    timed(rec, "run.detail", lambda: client.get(f"/workspaces/{ws}/runs/{rid}"))
            timed(rec, "list.runs.filtered", lambda: client.get(f"/workspaces/{ws}/runs", workflowId=wf,
                                                                lifecycle="completed", limit=20))
            if loop % 4 == 1:
                timed(rec, "insights.summary", lambda: client.get(f"/workspaces/{ws}/insights/summary"))
                timed(rec, "insights.throughput", lambda: client.get(f"/workspaces/{ws}/insights/throughput"))
                timed(rec, "insights.steps", lambda: client.get(f"/workspaces/{ws}/insights/steps"))
            timed(rec, "shared.assets", lambda: client.get(f"/workspaces/{ws}/shared-assets"))
        timed(rec, "catalog.workflows", lambda: client.get(f"/workspaces/{ws}/workflows"))
        timed(rec, "notifications", lambda: client.get(f"/workspaces/{ws}/me/notifications"))
        stop.wait(rnd.uniform(1.0, 3.0))


def sample(args, stats, stop, t0):
    names = [args.pg_container] + [c for c in args.app_containers.split(",") if c]
    while not stop.is_set():
        row = {"t": round(time.monotonic() - t0)}
        try:
            out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}"]
                                 + names, capture_output=True, text=True, timeout=60).stdout
            for line in out.splitlines():
                name, cpu, mem = line.split("|")
                row[name] = {"cpu": float(cpu.rstrip("%")), "mem": mem.split("/")[0].strip()}
            conns = psql(args.pg_container, "SELECT state, count(*) FROM pg_stat_activity WHERE datname='orqix' "
                                            "AND usename='orqix_app' GROUP BY 1 ORDER BY 1")
            row["db_connections"] = dict(line.split("|") for line in conns.splitlines() if "|" in line)
            for base in [m for m in args.metrics.split(",") if m]:
                try:
                    text = requests.get(base + "/actuator/prometheus", timeout=5).text
                    pool = {}
                    for line in text.splitlines():
                        for metric in ("hikaricp_connections_active", "hikaricp_connections_pending"):
                            if line.startswith(metric + "{"):
                                pool[metric.split("_")[-1]] = float(line.rsplit(" ", 1)[1])
                    row["pool " + base] = pool
                except requests.RequestException:
                    row["pool " + base] = "down"
        except (subprocess.SubprocessError, ValueError):
            pass
        try:
            jobs = psql(args.pg_container, "SELECT coalesce(lock_owner_, 'unlocked'), count(*) FROM flowable.act_ru_job "
                                           "GROUP BY 1 UNION ALL SELECT 'effects inFlight', count(*) FROM effect_journal "
                                           "WHERE state = 'inFlight'")
            row["jobs"] = dict(line.split("|") for line in jobs.splitlines() if "|" in line)
        except (subprocess.SubprocessError, ValueError):
            pass
        stats.append(row)
        stop.wait(args.sample_every)


def psql(container, sql):
    return subprocess.run(["docker", "exec", "-e", "PGOPTIONS=-c search_path=orqix,flowable,public", container, "psql", "-U", "postgres", "-d", "orqix", "-At", "-F", "|", "-c",
                           sql], capture_output=True, text=True, timeout=120).stdout.strip()


def summarize(samples):
    by = {}
    for category, status, latency, _, _ in samples:
        by.setdefault(category, []).append((latency, status))
    out = {}
    for category, values in sorted(by.items()):
        lat = [v for v, _ in values]
        out[category] = {"count": len(values), "errors": sum(1 for _, s in values if s != 200),
                         "p50_ms": round(percentile(lat, 0.5) * 1000), "p95_ms": round(percentile(lat, 0.95) * 1000),
                         "p99_ms": round(percentile(lat, 0.99) * 1000), "max_ms": round(max(lat) * 1000)}
    return out


def agg(samples, cats=None):
    lat = [s[2] for s in samples if cats is None or s[0] in cats]
    if not lat:
        return None
    return {"count": len(lat), "p50_ms": round(percentile(lat, 0.5) * 1000), "p95_ms": round(percentile(lat, 0.95) * 1000),
            "p99_ms": round(percentile(lat, 0.99) * 1000), "max_ms": round(max(lat) * 1000)}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default=os.environ.get("ORQIX_BASE_URL", "http://localhost:48080"))
    p.add_argument("--stub", default="http://localhost:48095")
    p.add_argument("--users", type=int, default=150)
    p.add_argument("--worker-share", type=float, default=0.6)
    p.add_argument("--ramp", type=float, default=120)
    p.add_argument("--minutes", type=float, default=8)
    p.add_argument("--label", default="")
    p.add_argument("--pg-container", default="orqix-scale-1026-postgres-1")
    p.add_argument("--app-containers", default="orqix-scale-1026-orqix-1,orqix-scale-1026-orqix-2-1")
    p.add_argument("--metrics", default="http://localhost:48083,http://localhost:48085",
                   help="management base URLs of the replicas (Prometheus: Hikari pool active/pending)")
    p.add_argument("--stub-delay-ms", type=int, default=0,
                   help="arm the stub's delay fault for every order, so engine jobs are in flight when a replica dies")
    p.add_argument("--sample-every", type=float, default=10)
    p.add_argument("--kill-after", type=float, default=0, help="seconds after load start; 0 = no failure injection")
    p.add_argument("--kill-container", default="orqix-scale-1026-orqix-2-1")
    p.add_argument("--kill-mode", choices=("kill", "stop"), default="kill")
    p.add_argument("--settle", type=float, default=150, help="seconds to wait for in-flight runs after the load")
    p.add_argument("--out", required=True)
    args = p.parse_args()
    import faulthandler
    faulthandler.dump_traceback_later(1500, repeat=True, file=sys.stderr)  # diagnose a hung client thread
    os.environ["ORQIX_BASE_URL"] = args.base
    import orqix_client  # noqa: E402  (reads ORQIX_BASE_URL at import)
    def Client(username):
        # Login is setup, not measured: retry a reset connection (Docker Desktop's port proxy occasionally resets a
        # fresh connection when hundreds are opened at once).
        for attempt in range(5):
            try:
                return orqix_client.Client(username)
            except (requests.RequestException, RuntimeError):
                if attempt == 4:
                    raise
                time.sleep(2 + attempt * 2)

    workers = int(round(args.users * args.worker_share))
    readers = args.users - workers
    print(f"Logging in {workers} workers (2 sessions each) and {readers} readers against {args.base} ...", flush=True)
    t_login = time.monotonic()
    with ThreadPoolExecutor(8) as pool:
        requesters = list(pool.map(Client, ["rhea.requester"] * workers))
        managers = list(pool.map(Client, ["mani.manager" if i % 2 else "max.manager" for i in range(workers)]))
        kinds = [("operator", "olga.operator"), ("viewer", "vic.viewer"), ("manager", "max.manager")]
        reader_kinds = [kinds[i % 3] for i in range(readers)]
        reader_clients = list(pool.map(Client, [u for _, u in reader_kinds]))
    login_seconds = time.monotonic() - t_login
    print(f"Logged in {len(requesters) + len(managers) + len(reader_clients)} sessions in {login_seconds:.0f} s", flush=True)
    ws = next(w["id"] for w in requesters[0].get("/me").json()["workspaces"] if w["key"] == "purchasing")
    designer, publisher = Client("dana.designer"), Client("paul.publisher")
    key = "scale-" + uuid.uuid4().hex[:8]
    wf = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": "Scale " + key,
                                                      "templateId": "purchase-approval"}).json()["id"]
    revision = designer.get(f"/workspaces/{ws}/workflows/{wf}/draft").json()["revision"]
    published = publisher.post(f"/workspaces/{ws}/workflows/{wf}/publish", {
        "draftRevision": revision, "acknowledgeWarnings": True}, idem=True)
    assert published.status_code == 201, published.text[:300]
    print(f"Published workflow {key}", flush=True)
    requests.post(f"{args.stub}/__reset", timeout=10)
    requests.post(f"{args.stub}/__faults", json={"mode": "none"}, timeout=10)
    if args.stub_delay_ms:
        requests.post(f"{args.stub}/__faults", json={"mode": "delay", "delayMs": args.stub_delay_ms}, timeout=10)
    psql(args.pg_container, "SELECT pg_stat_statements_reset()")

    rec = Recorder()
    counters = {}
    stop = threading.Event()
    stats = []
    t0 = time.monotonic()
    deadline = t0 + args.minutes * 60
    ctx = {"requesters": requesters, "managers": managers, "ws": ws, "wf": wf, "started_ids": set(),
           "approved_ids": set()}
    threads = []
    order = list(range(args.users))
    random.Random(7).shuffle(order)  # interleave workers and readers during the ramp
    for slot, n in enumerate(order):
        start_at = t0 + args.ramp * slot / max(1, args.users)
        if n < workers:
            threads.append(threading.Thread(target=worker, args=(n, start_at, deadline, rec, ctx, counters, stop)))
        else:
            r = n - workers
            threads.append(threading.Thread(target=reader, args=(r, reader_kinds[r][0], reader_clients[r], start_at,
                                                                 deadline, rec, ctx, stop)))
    sampler = threading.Thread(target=sample, args=(args, stats, stop, t0), daemon=True)
    sampler.start()
    started_at = datetime.now(timezone.utc)
    print(f"Running {args.users} VUs ({workers} workers, {readers} readers), ramp {args.ramp} s, {args.minutes} min",
          flush=True)
    for t in threads:
        t.start()
    kill = None
    if args.kill_after:
        def do_kill():
            if stop.wait(args.kill_after):
                return
            cmd = ["docker", "kill", args.kill_container] if args.kill_mode == "kill" else \
                ["docker", "stop", "-t", "60", args.kill_container]
            kill_t = time.monotonic() - t0
            res = subprocess.run(cmd, capture_output=True, text=True)
            ctx["kill"] = {"at_s": round(kill_t, 1), "mode": args.kill_mode, "container": args.kill_container,
                           "returned_after_s": round(time.monotonic() - t0 - kill_t, 1), "rc": res.returncode}
            print(f"  !!! {args.kill_mode} {args.kill_container} at {kill_t:.0f} s (rc {res.returncode})", flush=True)
        kill = threading.Thread(target=do_kill, daemon=True)
        kill.start()
    while any(t.is_alive() for t in threads):
        time.sleep(30)
        el = time.monotonic() - t0
        recent = [s for s in rec.samples if s[3] > time.monotonic() - 30]
        errs = sum(1 for s in recent if s[1] != 200)
        print(f"  {el:5.0f} s started {counters.get('started', 0)} completed {counters.get('completed', 0)} "
              f"last30s {len(recent) / 30:.1f} req/s errors {errs}", flush=True)
    load_seconds = time.monotonic() - t0
    stop.set()

    # Settle: wait until every approved run has ended (or --settle seconds), then check consistency.
    checker = Client("olga.operator")
    pending = set(ctx["approved_ids"])
    settle_end = time.monotonic() + args.settle
    while pending and time.monotonic() < settle_end:
        for rid in list(pending):
            r = checker.get(f"/workspaces/{ws}/runs/{rid}")
            if r.status_code == 200 and r.json()["lifecycle"] in ("completed", "cancelled", "needsAttention"):
                pending.discard(rid)
        if pending:
            time.sleep(5)
    orders = requests.get(f"{args.stub}/__orders", timeout=30).json()
    db = {
        "runsByLifecycle": psql(args.pg_container, f"SELECT lifecycle, count(*) FROM workflow_run WHERE workflow_id='{wf}' GROUP BY 1"),
        "runsWithoutEngineInstance": psql(args.pg_container, f"SELECT count(*) FROM workflow_run WHERE workflow_id='{wf}' AND engine_process_instance_id IS NULL"),
        "startIdempotencyRecordsVsRuns": psql(args.pg_container, f"SELECT (SELECT count(*) FROM idempotency_record WHERE operation='startRun' AND resource_id IN (SELECT id::text FROM workflow_run WHERE workflow_id='{wf}')), (SELECT count(*) FROM workflow_run WHERE workflow_id='{wf}')"),
        "decisionsPerTaskMax": psql(args.pg_container, f"SELECT coalesce(max(c),0) FROM (SELECT count(*) c FROM task_decision d JOIN workflow_run r ON r.id=d.run_id WHERE r.workflow_id='{wf}' GROUP BY d.engine_task_id) x"),
        "effectsByState": psql(args.pg_container, f"SELECT e.state, count(*) FROM effect_journal e JOIN workflow_run r ON r.id=e.run_id WHERE r.workflow_id='{wf}' GROUP BY 1"),
        "effectAttemptsMax": psql(args.pg_container, f"SELECT coalesce(max(attempts),0) FROM effect_journal e JOIN workflow_run r ON r.id=e.run_id WHERE r.workflow_id='{wf}'"),
        "flowableJobs": psql(args.pg_container, "SELECT 'async', count(*) FROM flowable.act_ru_job UNION ALL SELECT 'timer', count(*) FROM flowable.act_ru_timer_job UNION ALL SELECT 'deadletter', count(*) FROM flowable.act_ru_deadletter_job UNION ALL SELECT 'locked', count(*) FROM flowable.act_ru_job WHERE lock_owner_ IS NOT NULL"),
        "jobLockOwners": psql(args.pg_container, "SELECT coalesce(lock_owner_,'-'), count(*) FROM flowable.act_ru_job GROUP BY 1"),
        "topStatements": psql(args.pg_container, "SELECT calls, round(total_exec_time)::int AS total_ms, round(mean_exec_time::numeric,2) AS mean_ms, round(max_exec_time)::int AS max_ms, regexp_replace(left(query,160),'\\s+',' ','g') FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname='orqix') ORDER BY total_exec_time DESC LIMIT 15"),
    }
    approved = counters.get("approved", 0)
    completed_ids = int(psql(args.pg_container, f"SELECT count(*) FROM workflow_run WHERE workflow_id='{wf}' AND lifecycle='completed'") or 0)
    samples = rec.samples
    errors = {}
    for c, s, _, _, _ in samples:
        if s != 200:
            errors[f"{c}:{s}"] = errors.get(f"{c}:{s}", 0) + 1
    timeline = {}
    for c, s, lat, t, up in samples:
        sec = int(t - t0)
        b = timeline.setdefault(sec, {"n": 0, "err": 0, "lat": [], "up": {}})
        b["n"] += 1
        b["err"] += s != 200
        b["lat"].append(lat)
        if up:
            b["up"][up] = b["up"].get(up, 0) + 1
    tl = [{"s": sec, "n": b["n"], "err": b["err"], "p95_ms": round(percentile(b["lat"], 0.95) * 1000), "up": b["up"]}
          for sec, b in sorted(timeline.items())]
    upstreams = {}
    for *_, up in samples:
        upstreams[up or "-"] = upstreams.get(up or "-", 0) + 1
    steady = [s for s in samples if s[3] - t0 >= args.ramp]
    steady_secs = max(1.0, load_seconds - args.ramp)
    reads = ("list.tasks", "list.tasks.assigned", "list.tasks.completed", "list.runs", "list.runs.page2",
             "list.runs.filtered", "run.detail", "run.get", "task.get", "catalog.workflows", "notifications",
             "shared.assets")
    result = {
        "test": "S92-scale", "label": args.label, "startedAt": started_at.isoformat(timespec="seconds"),
        "base": args.base, "users": args.users, "workers": workers, "readers": readers, "rampSeconds": args.ramp,
        "durationSeconds": round(load_seconds), "loginSeconds": round(login_seconds),
        "requests": len(samples), "throughputRps": round(len(samples) / load_seconds, 1),
        "steadyThroughputRps": round(len(steady) / steady_secs, 1),
        "steadyStartsPerMinute": round(sum(1 for s in steady if s[0] == "start") / steady_secs * 60, 1),
        "overall": agg(samples), "steadyOverall": agg(steady), "steadyReads": agg(steady, reads),
        "steadyStart": agg(steady, ("start",)), "steadyCommands": agg(steady, ("task.claim", "task.decide")),
        "steadyInsights": agg(steady, ("insights.summary", "insights.throughput", "insights.steps")),
        "perCategory": summarize(samples), "errors": {"count": sum(errors.values()), "byKind": errors},
        "errorRate": round(sum(errors.values()) / max(1, len(samples)), 5),
        "upstreams": upstreams, "flow": counters, "kill": ctx.get("kill"),
        "consistency": {"approved": approved, "approvedStillPending": len(pending), "completedRunsInDb": completed_ids,
                        "stubOrders": orders["count"], "stubCreateRequests": orders.get("createRequests"),
                        "stubReplays": orders.get("replays"), "db": db},
        "resources": stats, "timeline": tl,
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, indent=1), encoding="utf-8")
    brief = {k: result[k] for k in ("label", "users", "durationSeconds", "requests", "throughputRps",
                                    "steadyThroughputRps", "steadyStartsPerMinute", "steadyReads", "steadyStart",
                                    "steadyCommands", "steadyInsights", "errors", "errorRate", "upstreams", "flow",
                                    "kill")}
    brief["consistency"] = {k: v for k, v in result["consistency"].items() if k != "db"}
    brief["db"] = {k: v for k, v in db.items() if k != "topStatements"}
    print(json.dumps(brief, indent=1))
    print(f"Evidence: {args.out}")


if __name__ == "__main__":
    main()
