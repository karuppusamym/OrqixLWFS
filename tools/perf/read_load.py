"""S102 read load: many people looking at Insights and their inbox at once, on a large database.

Run after tools/perf/seed_large.sql. For --seconds, --threads sessions loop over three calls and the script reports calls
per second and p50/p95/max per call. Run it once on the old image and once on the new one against the same data.

  python tools/perf/read_load.py --base http://localhost:43080 --threads 12 --seconds 60 --out evidence/s102/after.json
"""
import argparse
import json
import os
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))] if s else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:43080")
    ap.add_argument("--threads", type=int, default=12)
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.environ["ORQIX_BASE_URL"] = args.base
    from orqix_client import Client

    operators = [Client("olga.operator") for _ in range(args.threads // 2)]
    managers = [Client("max.manager") for _ in range(args.threads - len(operators))]
    ws = next(w["id"] for w in operators[0].get("/me").json()["workspaces"] if w["key"] == "purchasing")
    calls = {
        "insights.summary.30d": lambda c: c.get(f"/workspaces/{ws}/insights/summary"),
        "insights.steps.30d": lambda c: c.get(f"/workspaces/{ws}/insights/steps"),
        "inbox.claimable": lambda c: c.get(f"/workspaces/{ws}/tasks", view="claimable", limit=20),
    }
    samples = {name: [] for name in calls}
    errors = {name: 0 for name in calls}
    lock = threading.Lock()
    stop = time.time() + args.seconds

    def worker(client, names):
        i = 0
        while time.time() < stop:
            name = names[i % len(names)]
            i += 1
            t = time.time()
            try:
                r = calls[name](client)
                ok = r.status_code == 200
            except Exception:
                ok = False
            ms = (time.time() - t) * 1000
            with lock:
                if ok:
                    samples[name].append(ms)
                else:
                    errors[name] += 1
            time.sleep(0.05)

    threads = [threading.Thread(target=worker, args=(c, ["insights.summary.30d", "insights.steps.30d"])) for c in operators]
    threads += [threading.Thread(target=worker, args=(c, ["inbox.claimable"])) for c in managers]
    started = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    took = time.time() - started
    report = {"threads": args.threads, "seconds": round(took, 1), "calls": {}}
    for name, values in samples.items():
        report["calls"][name] = {"count": len(values), "perSecond": round(len(values) / took, 1), "p50": round(pct(values, 0.5)),
                                 "p95": round(pct(values, 0.95)), "max": round(max(values)) if values else 0, "errors": errors[name]}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(report, indent=2), encoding="utf-8")
    for name, r in report["calls"].items():
        print(f"{name:24} {r['count']:6} calls {r['perSecond']:6}/s  p50 {r['p50']:6} ms  p95 {r['p95']:6} ms  max {r['max']:6} ms  errors {r['errors']}")


if __name__ == "__main__":
    main()
