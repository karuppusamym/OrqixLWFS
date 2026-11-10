"""CG-P01 scale qualification with runtime roles (docs/build/S150, ADR 0004): one API pod and two worker pods on the
Compose stack (deploy/compose/docker-compose.roles.yml). Opt-in; it never runs as part of a build.

Measures, per run:
  start throughput     successful starts per second (closed loop: virtual users wait for their own work)
  start latency        POST /runs wall time
  task latency         from the start's answer until the first task is claimable. The API pod runs no engine jobs,
                       so this includes a worker pod acquiring and executing the job (ADR 0004)
  completion latency   from the approval's answer until the request is completed (worker job + connector call)
  poll-check rate      checks per second of "Keep checking until..." steps, from orqix_poll_checks_total on the
                       worker pods, against the schedule (active waits / interval)
  analytics lag        orqix_analytics_lag_seconds sampled over the run (worker pods project the read model)
and checks that the roles held: the API pod executed no connector calls or poll checks, both workers did.

  python tools/perf/multipod.py --smoke                       # about 2 minutes, 3 users: CI-safe sanity run
  python tools/perf/multipod.py --users 40 --minutes 5 --poll-share 0.25 --out evidence/s150/run.json

Measurement limits: everything (stack and client) runs on one machine. Client threads compete with the pods for CPU,
latencies are loopback wall times, and the three pods share one PostgreSQL. Numbers are relative evidence for this
topology on this machine, not production capacity.
"""
import argparse
import json
import os
import random
import re
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
from scale import Recorder, bump, percentile, timed, COUNTER_LOCK  # noqa: E402

POLL_EVERY_SECONDS = 10
HEALTH_CONNECTION = {
    "schemaVersion": "0.1", "key": "perf-health", "version": 1, "name": "Perf health check",
    "description": "CG-P01 harness: read-only GET of the stub's health, polled by Keep checking steps.",
    "operations": {"getHealth": {
        "label": "Get health", "method": "GET", "path": "/health",
        "responseSchema": {"type": "object", "required": ["status"], "properties": {"status": {"type": "string"}},
                           "additionalProperties": True},
        "successStatuses": [200], "effectClass": "readOnly", "maxTimeoutSeconds": 10, "testable": False}},
}


def scrape(url, names):
    """Sums the samples of each metric family in `names` from one Prometheus endpoint (None when unreachable)."""
    try:
        text = requests.get(f"{url}/actuator/prometheus", timeout=10).text
    except requests.RequestException:
        return None
    totals = {n: 0.0 for n in names}
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        m = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([-+0-9.eEnNaIf]+)", line)
        if m and m.group(1) in totals:
            try:
                value = float(m.group(3))
            except ValueError:
                continue
            if value == value:  # not NaN
                totals[m.group(1)] += value
    return totals


POD_METRICS = ["orqix_runs_started_total", "orqix_poll_checks_total", "orqix_connector_call_seconds_count",
               "orqix_effect_attempts_total", "orqix_analytics_lag_seconds", "orqix_poll_waits_active",
               "orqix_oldest_executable_job_age_seconds", "hikaricp_connections_active", "hikaricp_connections_pending",
               "process_cpu_usage"]


def sampler(args, pods, out, stop, t0):
    while not stop.is_set():
        row = {"t": round(time.monotonic() - t0, 1)}
        for name, url in pods.items():
            row[name] = scrape(url, POD_METRICS)
        out.append(row)
        stop.wait(args.sample_every)


def publish(designer, publisher, ws, key, patch=None):
    wf = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": "CG-P01 " + key,
                                                      "templateId": "purchase-approval"}).json()["id"]
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf}/draft").json()
    revision = draft["revision"]
    if patch:
        definition = draft["definition"]
        patch(definition)
        saved = designer.put(f"/workspaces/{ws}/workflows/{wf}/draft", {"definition": definition},
                             if_match=f'"r{revision}"')
        assert saved.status_code == 200, saved.text[:400]
        revision = saved.json()["revision"]
    published = publisher.post(f"/workspaces/{ws}/workflows/{wf}/publish", {
        "draftRevision": revision, "acknowledgeWarnings": True}, idem=True)
    assert published.status_code == 201, published.text[:400]
    return wf


def add_watch(definition):
    """After the order is placed, keep checking the stub's health every 10 s until it says DOWN (never)."""
    definition.setdefault("dependencies", {}).setdefault("connections", []).append(
        {"key": "perf-health", "definitionVersion": 1})
    definition["nodes"].append({
        "id": "watch", "type": "http", "label": "Watch the supplier", "config": {
            "connectionRef": "perf-health", "operation": "getHealth", "inputMapping": {},
            "resultMapping": {"status": "/status"}, "timeoutSeconds": 5,
            "poll": {"every": f"PT{POLL_EVERY_SECONDS}S", "jitterPercent": 10, "giveUpAfter": "PT30M", "maxChecks": 1000,
                     "until": {"fieldRef": "/steps/watch/status", "operator": "eq", "value": "DOWN"}}}})
    for t in definition["transitions"]:
        if t.get("from") == "procure":
            t["to"] = "watch"
    definition["transitions"].append({"id": "w1", "from": "watch", "to": "notify", "on": "next"})
    definition["transitions"].append({"id": "w2", "from": "watch", "to": "notifyRejected", "on": "timedOut"})
    definition.pop("uiLayout", None)


def worker(index, start_at, deadline, rec, ctx, counters, stop, poll_share):
    requester, manager = ctx["requesters"][index], ctx["managers"][index]
    ws = ctx["ws"]
    rnd = random.Random(index)
    stop.wait(max(0.0, start_at - time.monotonic()))
    while time.monotonic() < deadline and not stop.is_set():
        polling = rnd.random() < poll_share
        wf = ctx["wf_poll"] if polling else ctx["wf"]
        idem = str(uuid.uuid4())
        body = {"workflowId": wf, "input": {"title": f"CG-P01 {index}", "purpose": "S150", "costCenter": "OPS",
                                            "amount": rnd.choice([120, 480, 1800, 2400])}}
        started = timed(rec, "start", lambda: requester.post(f"/workspaces/{ws}/runs", body, key=idem))
        if started is None or started.status_code not in (200, 201):
            bump(counters, "start_failed")
            time.sleep(1)
            continue
        answered = time.monotonic()
        run_id = started.json()["id"]
        bump(counters, "started")
        task = None
        while time.monotonic() - answered < 60:
            page = manager.get(f"/workspaces/{ws}/tasks", view="claimable", limit=50, sort="newest")
            if page.status_code == 200:
                task = next((t for t in page.json()["items"] if t["runId"] == run_id), None)
                if task:
                    break
            time.sleep(0.25)
        if not task:
            bump(counters, "task_missing")
            continue
        with COUNTER_LOCK:
            ctx["task_latency"].append(time.monotonic() - answered)
        claimed = timed(rec, "task.claim", lambda: manager.post(f"/workspaces/{ws}/tasks/{task['id']}/claim",
                                                                  {"expectedVersion": task["version"]}))
        if claimed is None or claimed.status_code != 200:
            bump(counters, "claim_failed")
            continue
        dbody = {"expectedVersion": claimed.json()["version"], "action": "approve"}
        decided = timed(rec, "task.decide", lambda: manager.post(f"/workspaces/{ws}/tasks/{task['id']}/decision",
                                                                   dbody, idem=True))
        if decided is None or decided.status_code not in (200, 201):
            bump(counters, "decide_failed")
            continue
        approved = time.monotonic()
        bump(counters, "approved_poll" if polling else "approved")
        wanted = ("waiting", "completed", "needsAttention") if polling else ("completed", "needsAttention")
        state = None
        while time.monotonic() - approved < 120:
            run = requester.get(f"/workspaces/{ws}/runs/{run_id}")
            if run.status_code == 200 and run.json()["lifecycle"] in wanted:
                # A polling request is "waiting" only once it reached the watch step.
                steps = [s.get("nodeId") for s in run.json().get("currentSteps", [])]
                if not polling or run.json()["lifecycle"] != "waiting" or "watch" in steps:
                    state = run.json()["lifecycle"]
                    break
            time.sleep(0.5)
        if polling:
            bump(counters, "polling" if state == "waiting" else "poll_not_reached")
        else:
            if state == "completed":
                with COUNTER_LOCK:
                    ctx["completion_latency"].append(time.monotonic() - approved)
            bump(counters, "completed" if state == "completed" else "not_completed")
        time.sleep(rnd.uniform(0.5, 1.5))


def stats(values):
    return {"n": len(values), "p50": percentile(values, 0.5), "p95": percentile(values, 0.95),
            "p99": percentile(values, 0.99), "max": max(values) if values else None}


def psql(container, sql):
    out = subprocess.run(["docker", "exec", container, "psql", "-U", "postgres", "-d", "orqix", "-qAtX", "-c", sql],
                         capture_output=True, text=True, timeout=60)
    return out.stdout.strip()


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--base", default=os.environ.get("ORQIX_BASE_URL", "http://localhost:8080"), help="API pod")
    p.add_argument("--pods", default="api=http://localhost:8081,worker-1=http://localhost:8083,worker-2=http://localhost:8085",
                   help="name=management URL of every pod; names starting with 'api' are API pods")
    p.add_argument("--pg-container", default="orqix-roles-postgres-1")
    p.add_argument("--stub-url-in-stack", default="http://procurement-stub:8090")
    p.add_argument("--users", type=int, default=20)
    p.add_argument("--ramp", type=float, default=30)
    p.add_argument("--minutes", type=float, default=5)
    p.add_argument("--poll-share", type=float, default=0.25, help="share of starts that end in a polling step")
    p.add_argument("--sample-every", type=float, default=10)
    p.add_argument("--smoke", action="store_true", help="3 users, 75 s, short ramp; asserts the roles and consistency")
    p.add_argument("--out", default=None)
    args = p.parse_args()
    if args.smoke:
        args.users, args.ramp, args.minutes, args.poll_share, args.sample_every = 3, 3, 1.25, 0.34, 5
    out_path = Path(args.out or ROOT / "evidence" / "s150" / f"multipod-{'smoke' if args.smoke else 'run'}-"
                    f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json")
    pods = dict(item.split("=", 1) for item in args.pods.split(","))
    os.environ["ORQIX_BASE_URL"] = args.base
    import orqix_client  # noqa: E402  (reads ORQIX_BASE_URL at import)

    def client(username):
        for attempt in range(5):
            try:
                return orqix_client.Client(username)
            except (requests.RequestException, RuntimeError):
                if attempt == 4:
                    raise
                time.sleep(2 + attempt * 2)

    print(f"Logging in {args.users} requester/manager pairs against {args.base} ...", flush=True)
    with ThreadPoolExecutor(8) as pool:
        requesters = list(pool.map(client, ["rhea.requester"] * args.users))
        managers = list(pool.map(client, ["mani.manager" if i % 2 else "max.manager" for i in range(args.users)]))
    admin, designer, publisher = client("wade.admin"), client("dana.designer"), client("paul.publisher")
    ws = next(w["id"] for w in requesters[0].get("/me").json()["workspaces"] if w["key"] == "purchasing")
    # The polled connection: read-only GET of the stub's health, bound to the stub inside the stack.
    if admin.get(f"/workspaces/{ws}/connections/perf-health").status_code == 404:
        created = admin.post(f"/workspaces/{ws}/connections/perf-health/versions", {"definition": HEALTH_CONNECTION})
        assert created.status_code in (200, 201), created.text[:400]
    bound = admin.put(f"/workspaces/{ws}/connections/perf-health/binding", {
        "baseUrl": args.stub_url_in_stack, "auth": {"type": "none"}, "enabled": True})
    # 428: the binding exists (made by an earlier run of this harness on this stack); keep it.
    assert bound.status_code in (200, 428), bound.text[:400]
    suffix = uuid.uuid4().hex[:6]
    ctx = {"requesters": requesters, "managers": managers, "ws": ws,
           "wf": publish(designer, publisher, ws, f"cgp01-{suffix}"),
           "wf_poll": publish(designer, publisher, ws, f"cgp01-poll-{suffix}", add_watch),
           "task_latency": [], "completion_latency": []}
    print("Published the two workflows", flush=True)

    before = {name: scrape(url, POD_METRICS) for name, url in pods.items()}
    missing = [n for n, v in before.items() if v is None]
    if missing:
        raise SystemExit(f"management endpoint not reachable: {missing}")
    rec, counters, samples, stop = Recorder(), {}, [], threading.Event()
    t0 = time.monotonic()
    deadline = t0 + args.minutes * 60
    threads = [threading.Thread(target=worker, args=(i, t0 + args.ramp * i / max(1, args.users), deadline, rec, ctx,
                                                     counters, stop, args.poll_share)) for i in range(args.users)]
    sampling = threading.Thread(target=sampler, args=(args, pods, samples, stop, t0), daemon=True)
    sampling.start()
    started_utc = datetime.now(timezone.utc).isoformat()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.monotonic() - t0
    stop.set()
    after = {name: scrape(url, POD_METRICS) for name, url in pods.items()}

    def delta(pod, metric):
        return (after[pod] or {}).get(metric, 0) - (before[pod] or {}).get(metric, 0)

    worker_pods = [n for n in pods if not n.startswith("api")]
    api_pods = [n for n in pods if n.startswith("api")]
    checks = sum(delta(n, "orqix_poll_checks_total") for n in worker_pods)
    calls = {n: delta(n, "orqix_connector_call_seconds_count") for n in pods}
    active_polls = [max((r[n] or {}).get("orqix_poll_waits_active", 0) for n in worker_pods) for r in samples if r]
    lag = [max((r[n] or {}).get("orqix_analytics_lag_seconds", 0) for n in pods) for r in samples if r]
    by_cat = {}
    for cat, status, latency, _, _ in rec.samples:
        by_cat.setdefault(cat, {"ok": [], "errors": 0})
        if status == 200:
            by_cat[cat]["ok"].append(latency)
        else:
            by_cat[cat]["errors"] += 1
    starts_ok = len(by_cat.get("start", {}).get("ok", []))
    result = {
        "harness": "tools/perf/multipod.py", "startedUtc": started_utc, "smoke": args.smoke,
        "topology": {"api": api_pods, "workers": worker_pods}, "users": args.users, "rampSeconds": args.ramp,
        "durationSeconds": round(elapsed, 1), "pollShare": args.poll_share, "counters": counters,
        "startThroughputPerSecond": round(starts_ok / elapsed, 2),
        "latencySeconds": {cat: dict(stats(v["ok"]), errors=v["errors"]) for cat, v in sorted(by_cat.items())},
        "taskLatencySeconds": stats(ctx["task_latency"]),
        "completionLatencySeconds": stats(ctx["completion_latency"]),
        "pollChecks": {"total": checks, "perSecond": round(checks / elapsed, 2),
                       "activeWaitsMax": max(active_polls) if active_polls else 0,
                       "scheduledPerSecondAtEnd": round((active_polls[-1] if active_polls else 0) / POLL_EVERY_SECONDS, 2)},
        "analyticsLagSeconds": {"max": max(lag) if lag else None, "p50": percentile(lag, 0.5)},
        "connectorCallsByPod": calls,
        "pollChecksByPod": {n: delta(n, "orqix_poll_checks_total") for n in pods},
        "samples": samples,
        "database": {
            "runsInWorkspace": psql(args.pg_container, f"SELECT count(*) FROM orqix.workflow_run WHERE workspace_id = '{ws}'"),
            "deadLetterJobs": psql(args.pg_container, "SELECT count(*) FROM flowable.act_ru_deadletter_job"),
            "effectLeaseOwners": psql(args.pg_container, "SELECT string_agg(DISTINCT split_part(lease_owner, ':', 1), ',')"
                                      " FROM orqix.effect_journal WHERE updated_at > now() - interval '1 hour'"),
        },
    }
    problems = []
    if any(calls[n] for n in api_pods) or any(result["pollChecksByPod"][n] for n in api_pods):
        problems.append("an API pod made connector calls or poll checks (ADR 0004 violated)")
    if sum(1 for n in worker_pods if calls[n] > 0) < min(2, len(worker_pods)):
        problems.append(f"not every worker pod made connector calls: {calls}")
    if counters.get("not_completed", 0) or counters.get("task_missing", 0) or counters.get("poll_not_reached", 0):
        problems.append(f"work did not finish: {counters}")
    if (counters.get("approved_poll", 0) and checks <= 0):
        problems.append("polling requests reached the watch step but no poll check was counted")
    if result["database"]["deadLetterJobs"] not in ("0", ""):
        problems.append(f"dead-letter jobs: {result['database']['deadLetterJobs']}")
    result["problems"] = problems
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    summary = {k: result[k] for k in ("startThroughputPerSecond", "taskLatencySeconds", "completionLatencySeconds",
                                      "pollChecks", "analyticsLagSeconds", "connectorCallsByPod", "problems")}
    print(json.dumps(summary, indent=2, default=str))
    print(f"Evidence: {out_path}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
