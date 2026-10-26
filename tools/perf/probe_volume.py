"""S92 volume probe: single-user latency of the list, search and insights APIs on a large database (docs/build/S92).

Run after tools/perf/seed_large.sql. Each call is repeated --repeat times sequentially (no concurrent load) through the
given base URL, and p50/p95/max are reported per call. Use it before and after an index change to show the effect.

  python tools/perf/probe_volume.py --base http://localhost:48080 --repeat 20 --out evidence/s92/probe-before.json
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:48080")
    ap.add_argument("--repeat", type=int, default=20)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.environ["ORQIX_BASE_URL"] = args.base
    from orqix_client import Client

    op, mgr, req = Client("olga.operator"), Client("max.manager"), Client("rhea.requester")
    ws = next(w["id"] for w in op.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    wfs = op.get(f"/workspaces/{ws}/workflows").json()["items"]
    wf = next(w["id"] for w in wfs if w["key"].startswith("scale-"))
    first = op.get(f"/workspaces/{ws}/runs", limit=20).json()
    calls = {
        "runs.list": (op, "/runs", {"limit": 20}),
        "runs.list.page2": (op, "/runs", {"limit": 20, "cursor": first.get("nextCursor")}),
        "runs.byWorkflow": (op, "/runs", {"workflowId": wf, "limit": 20}),
        "runs.byWorkflowCompleted": (op, "/runs", {"workflowId": wf, "lifecycle": "completed", "limit": 20}),
        "runs.waiting": (op, "/runs", {"lifecycle": "waiting", "limit": 20}),
        "runs.needsAttention": (op, "/runs", {"lifecycle": "needsAttention", "limit": 20}),
        "runs.cancelled": (op, "/runs", {"lifecycle": "cancelled", "limit": 20}),
        "runs.rejected": (op, "/runs", {"outcomeKind": "rejected", "limit": 20}),
        "runs.search.businessKey": (op, "/runs", {"businessKey": "PO-50000", "limit": 20}),
        "runs.search.reference": (op, "/runs", {"businessKey": "ORQ-5000", "limit": 20}),
        "runs.search.noMatch": (op, "/runs", {"businessKey": "PO-NOPE", "limit": 20}),
        "runs.overdue": (op, "/runs", {"overdue": "true", "limit": 20}),
        "myRequests": (req, "/me/requests", {"limit": 20}),
        "tasks.claimable": (mgr, "/tasks", {"view": "claimable", "limit": 50}),
        "tasks.claimable.priority": (mgr, "/tasks", {"view": "claimable", "limit": 50, "sort": "priority"}),
        "tasks.assigned": (mgr, "/tasks", {"view": "assigned", "limit": 50}),
        "tasks.completed": (mgr, "/tasks", {"view": "completed", "limit": 50}),
        "insights.summary.30d": (op, "/insights/summary", {}),
        "insights.throughput.30d": (op, "/insights/throughput", {}),
        "insights.steps.30d": (op, "/insights/steps", {}),
        "insights.summary.90d": (op, "/insights/summary", {"from": time.strftime("%Y-%m-%d", time.gmtime(time.time() - 89 * 86400))}),
        "insights.steps.90d": (op, "/insights/steps", {"from": time.strftime("%Y-%m-%d", time.gmtime(time.time() - 89 * 86400))}),
        "notifications": (req, "/me/notifications", {}),
        "catalog.workflows": (op, "/workflows", {}),
        "shared.assets": (op, "/shared-assets", {}),
    }
    out = {}
    for name, (client, path, params) in calls.items():
        lat, statuses = [], {}
        for _ in range(args.repeat):
            t = time.perf_counter()
            r = client.get(f"/workspaces/{ws}{path}", **{k: v for k, v in params.items() if v is not None})
            lat.append((time.perf_counter() - t) * 1000)
            statuses[r.status_code] = statuses.get(r.status_code, 0) + 1
        out[name] = {"status": statuses, "p50_ms": round(pct(lat, 0.5)), "p95_ms": round(pct(lat, 0.95)),
                     "max_ms": round(max(lat))}
        print(f"{name:28s} {statuses} p50 {out[name]['p50_ms']:6d} ms  p95 {out[name]['p95_ms']:6d} ms", flush=True)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
