"""Local Tier-1 load qualification: 50 virtual users, 10,000 real stored cases, 5m warm-up + 15m measurement.

Each virtual user owns its cases and real requester/approver/operator sessions. The measured HTTP mix is
40% reads, 20% starts, 30% decisions, 10% reassignment/closure. No extra hidden API calls are made in that phase.
Preparation creates real engine cases in ten independent workflows and closes 10% to leave quota headroom.
"""
import argparse
import collections
import concurrent.futures
import json
import os
import platform
import random
import sys
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/api-e2e"))
from orqix_client import Client  # noqa: E402
from demo import require  # noqa: E402


def percentile(values, fraction):
    values = sorted(values)
    return values[min(len(values) - 1, int((len(values) - 1) * fraction))] if values else None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--users", type=int, default=50)
    p.add_argument("--cases", type=int, default=10000)
    p.add_argument("--warmup-minutes", type=float, default=5)
    p.add_argument("--minutes", type=float, default=15)
    p.add_argument("--out", default=str(ROOT / "evidence/hackathon/load.json"))
    args = p.parse_args()
    if urlparse(os.environ.get("ORQIX_BASE_URL", "http://localhost:8080")).hostname not in {"localhost", "127.0.0.1"}:
        p.error("This synthetic fixture is restricted to a local development stack.")
    if not 1 <= args.users <= 50 or args.cases < args.users or args.cases > 10000 or args.minutes <= 0 or args.warmup_minutes < 0:
        p.error("Use 1–50 users, users <= cases <= 10000, positive measurement duration and nonnegative warm-up.")
    designer, publisher = Client("dana.designer"), Client("paul.publisher")
    ws = next(w["id"] for w in require(designer.get("/me"), 200)["workspaces"] if w["key"] == "purchasing")
    prefix = f"/workspaces/{ws}"
    # Populate directory snapshots before tasks are dispatched.
    mani, maximum = Client("mani.manager"), Client("max.manager")
    manager_ids = [require(c.get("/me"), 200)["subject"]["id"] for c in [mani, maximum]]
    workflows = []
    for i in range(min(10, args.users)):
        d = json.loads((ROOT / "examples/hackathon/tier1-approval.json").read_text(encoding="utf-8"))
        key = "tier1-load-" + uuid.uuid4().hex[:10]
        d["workflowKey"] = key
        d["execution"]["maxConcurrentRuns"] = 1000
        d["provenance"] = {"gitCommit": "0" * 40, "reviewReference": "SYNTHETIC LOCAL LOAD FIXTURE; source review excluded"}
        config = d["nodes"][1]["config"]
        config["dispatch"] = {"policy": "leastLoaded"}
        config["dueAfterMinutes"] = 480
        config["calendar"].update(zone="UTC", start="00:00", end="23:59", weekdays=list(range(1, 8)), holidays=[])
        wf = require(designer.post(prefix + "/workflows", {"key": key, "name": f"Synthetic load workflow {i}"}), 201)["id"]
        require(designer.put(prefix + f"/workflows/{wf}/draft", {"definition": d}, if_match='"r1"'), 200)
        require(publisher.post(prefix + f"/workflows/{wf}/publish", {"draftRevision": 2, "acknowledgeWarnings": True}, idem=True), 201)
        workflows.append(wf)
    print(f"Preparing {args.cases} real cases across {len(workflows)} workflows ...", flush=True)
    clients = []
    for _ in range(args.users):
        clients.append(None)

    def session(index):
        requester, a, b, operator = [Client(n) for n in ["rhea.requester", "mani.manager", "max.manager", "olga.operator"]]
        wf = workflows[index % len(workflows)]
        pool = collections.deque()
        count = args.cases // args.users + (index < args.cases % args.users)
        for j in range(count):
            run = require(requester.post(prefix + "/runs", {"workflowId": wf, "input": {"title": "Synthetic capacity case", "amount": 10}}, idem=True), 201)
            detail = require(operator.get(prefix + f"/runs/{run['id']}"), 200)
            task = next(t for t in detail["tasks"] if t["status"] == "open")
            item = {"run": run["id"], "runVersion": detail["version"], "task": task["id"], "version": task["version"], "assignee": task["assignee"]["id"]}
            if j < max(1, count // 10):
                require(operator.post(prefix + f"/runs/{run['id']}/cancel", {"expectedVersion": detail["version"], "reason": "Synthetic load headroom"}, idem=True), 200)
            else:
                pool.append(item)
        clients[index] = (requester, {manager_ids[0]: a, manager_ids[1]: b}, operator, wf, pool)

    with concurrent.futures.ThreadPoolExecutor(max_workers=min(10, args.users)) as executor:
        list(executor.map(session, range(args.users)))
    print("Preparation complete; warm-up starts. The recorded phase excludes preparation and warm-up.", flush=True)
    lock = threading.Lock()
    samples, failures, counts = [], [], collections.Counter()
    start = time.monotonic()
    measure_from = start + args.warmup_minutes * 60
    end = measure_from + args.minutes * 60

    def user(index):
        requester, managers, operator, wf, pool = clients[index]
        pending = collections.deque()
        rng = random.Random(index)
        plan = ["read"] * 4 + ["start"] * 2 + ["decision"] * 3 + ["operate"]
        time.sleep(index / max(1, args.users))
        iteration = 0
        while time.monotonic() < end:
            category = plan[iteration % len(plan)]
            began = time.monotonic()
            status, response = "ERROR", None
            try:
                if category == "start":
                    response = requester.post(prefix + "/runs", {"workflowId": wf, "input": {"title": "Synthetic measured case", "amount": 10}}, idem=True)
                    if response.status_code == 201:
                        pending.append(response.json()["id"])
                elif category == "read":
                    if pending:
                        response = operator.get(prefix + f"/runs/{pending[0]}")
                        if response.status_code == 200:
                            detail = response.json()
                            task = next(t for t in detail["tasks"] if t["status"] == "open")
                            pool.append({"run": pending.popleft(), "runVersion": detail["version"], "task": task["id"], "version": task["version"], "assignee": task["assignee"]["id"]})
                    else:
                        response = operator.get(prefix + "/runs", workflowId=wf, limit=20)
                elif not pool:
                    raise AssertionError("TASK_POOL_STARVED")
                elif category == "decision":
                    item = pool[0]
                    response = managers[item["assignee"]].post(prefix + f"/tasks/{item['task']}/decision", {"expectedVersion": item["version"], "action": "approve"}, idem=True)
                    if response.status_code == 201:
                        pool.popleft()
                else:
                    item = pool[0]
                    if iteration % 20 == 19:
                        response = operator.post(prefix + f"/runs/{item['run']}/cancel", {"expectedVersion": item["runVersion"], "reason": "Synthetic measured closure"}, idem=True)
                        if response.status_code == 200:
                            pool.popleft()
                    else:
                        target = next(id for id in manager_ids if id != item["assignee"])
                        response = operator.post(prefix + f"/tasks/{item['task']}/reassign", {"expectedVersion": item["version"], "assigneeSubjectId": target, "reason": "Synthetic measured reassignment"})
                        if response.status_code == 200:
                            item.update(assignee=target, version=response.json()["version"], runVersion=item["runVersion"] + 1)
                status = response.status_code
            except Exception as exc:
                status = type(exc).__name__
            latency = (time.monotonic() - began) * 1000
            if began >= measure_from:
                with lock:
                    samples.append((category, latency, status))
                    counts[category] += 1
                    if status not in (200, 201):
                        failures.append({"category": category, "status": status})
            iteration += 1
            time.sleep(rng.uniform(1, 2))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.users) as executor:
        jobs = [executor.submit(user, i) for i in range(args.users)]
        while not all(job.done() for job in jobs):
            time.sleep(30)
            print(f"elapsed={round(time.monotonic()-start)}s measured={len(samples)} errors={len(failures)}", flush=True)
        for job in jobs:
            job.result()
    reads = [latency for category, latency, _ in samples if category == "read"]
    mutations = [latency for category, latency, _ in samples if category != "read"]
    result = {"users": args.users, "initialStoredCases": args.cases, "workflows": len(workflows),
              "warmupMinutes": args.warmup_minutes, "measurementMinutes": args.minutes, "measuredHttpCounts": dict(counts),
              "readP95Ms": percentile(reads, .95), "mutationP95Ms": percentile(mutations, .95),
              "unexpectedErrorRate": len(failures) / max(1, len(samples)), "errors": failures[:100],
              "host": {"platform": platform.platform(), "logicalProcessors": os.cpu_count()},
              "measurement": "client-side API latency; real local OIDC/PostgreSQL/Flowable/SMTP capture; no external business integrations"}
    result["passed"] = bool(reads and mutations and result["readP95Ms"] < 500 and result["mutationP95Ms"] < 1000 and result["unexpectedErrorRate"] < .01)
    target = Path(args.out)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes((json.dumps(result, indent=2) + "\n").encode())
    print(json.dumps({k: result[k] for k in ["passed", "readP95Ms", "mutationP95Ms", "unexpectedErrorRate"]}), flush=True)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
