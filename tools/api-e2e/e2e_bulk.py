"""Bulk handling end to end (scenario 51), on the integration stack (see e2e_integrations.py).

  51a  N requests are started concurrently by several people; every one gets a unique, gap-free reference.
  51b  The N messages that end their waits are sent concurrently; every request completes exactly once.
  51c  A burst of Kafka records on one topic starts one request each, once, and the consumer keeps up.
  51d  A burst of signed inbound events (every event id sent twice) never produces a server error.

Sizes: BULK_N (default 200), BULK_KAFKA (default 300), BULK_THREADS (default 8). Prints throughput; asserts correctness.
"""
import json
import os
import re
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import e2e_scenarios as base
from e2e_integrations import inbound, new_callback_workflow, produce, until
from e2e_scenarios import check
from orqix_client import Client

N = int(os.environ.get("BULK_N", "200"))
K = int(os.environ.get("BULK_KAFKA", "300"))
THREADS = int(os.environ.get("BULK_THREADS", "8"))


def workflow_runs(olga, ws, wf, prefix=None):
    items, cursor = [], None
    while True:
        params = {"limit": 100}
        if cursor:
            params["cursor"] = cursor
        page = olga.get(f"/workspaces/{ws}/runs", **params).json()
        items += [r for r in page["items"] if r["workflow"]["id"] == wf and (prefix is None or str(r.get("requestTitle", "")).startswith(prefix))]
        cursor = page.get("nextCursor")
        if not cursor:
            return items


def main():
    wade, dana, paul, olga = (Client(u) for u in ("wade.admin", "dana.designer", "paul.publisher", "olga.operator"))
    ws = next(w["id"] for w in wade.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    wf = new_callback_workflow(dana, paul, ws, max_open=1000)
    requesters = [Client("rhea.requester") for _ in range(THREADS)]
    operators = [Client("olga.operator") for _ in range(THREADS)]
    suffix = uuid.uuid4().hex[:6]

    print(f"Scenario 51a: {N} concurrent starts with {THREADS} sessions")
    orders = [f"BK-{suffix}-{i:04d}" for i in range(N)]
    t0 = time.time()

    def start_one(i):
        r = requesters[i % THREADS].post(f"/workspaces/{ws}/runs", {"workflowId": wf, "input": {"orderNumber": orders[i], "customerName": "Bulk"}}, idem=True)
        return r.status_code, (r.json() if r.status_code == 201 else r.text[:120])

    with ThreadPoolExecutor(THREADS) as ex:
        results = list(ex.map(start_one, range(N)))
    took = time.time() - t0
    ok = [r for code, r in results if code == 201]
    errors = [r for code, r in results if code != 201]
    check(f"all {N} starts accepted", len(ok) == N, f"{len(ok)} ok, errors: {str(errors[:3])}")
    refs = sorted(int(re.sub(r"\D", "", r["reference"])) for r in ok)
    check("references are unique and gap-free", bool(refs) and len(set(refs)) == len(refs) and refs == list(range(refs[0], refs[0] + len(refs))))
    print(f"  starts: {len(ok)} in {took:.1f}s = {len(ok) / took:.1f}/s")

    print(f"Scenario 51b: {N} concurrent messages end the waits")
    t1 = time.time()

    def message(i):
        r = operators[i % THREADS].post(f"/workspaces/{ws}/messages", {"name": "payment-received", "correlationKey": orders[i], "payload": {"paymentReference": f"BP-{i}"}}, key=str(uuid.uuid4()))
        return r.status_code, r.text[:120]

    with ThreadPoolExecutor(THREADS) as ex:
        sent = list(ex.map(message, range(N)))
    took_m = time.time() - t1
    check(f"all {N} messages accepted", all(code == 202 for code, _ in sent), str([s for s in sent if s[0] != 202][:3]))
    final = until(lambda: (lambda items: items if len(items) == N and all(r["lifecycle"] == "completed" for r in items) else None)(workflow_runs(olga, ws, wf, "BK-")), 240, 3)
    check(f"all {N} requests completed exactly once", bool(final), f"{len(workflow_runs(olga, ws, wf, 'BK-'))} listed")
    print(f"  messages: {N} in {took_m:.1f}s = {N / took_m:.1f}/s; everything complete {time.time() - t0:.1f}s after the first start")

    print(f"Scenario 51c: {K} Kafka records")
    me = wade.get("/me").json()["subject"]["id"]
    wade.post(f"/workspaces/{ws}/access/grants", {"permission": "workflow.start", "workflowId": wf, "principal": {"type": "subject", "value": me}})
    created = wade.post(f"/workspaces/{ws}/kafka-triggers", {"key": f"e2e-bulk-{suffix}", "name": "E2E bulk", "cluster": "local", "topic": "e2e-bulk", "action": "start",
                                                              "workflowId": wf, "mapping": {"orderNumber": "/order", "customerName": "/customer"}, "batchSize": 100, "maxPerSecond": 200, "startFrom": "earliest"})
    check("the bulk trigger is created", created.status_code == 201, f"{created.status_code} {created.text[:200]}")
    if created.status_code == 201:
        time.sleep(8)
        t2 = time.time()
        produce("e2e-bulk", [(f"k-{suffix}-{i}", json.dumps({"order": f"KB-{suffix}-{i:04d}", "customer": "Kafka bulk"})) for i in range(K)])
        until(lambda: len(workflow_runs(olga, ws, wf, f"KB-{suffix}")) >= K, 300, 3)
        count = len(workflow_runs(olga, ws, wf, f"KB-{suffix}"))
        print(f"  kafka: {count}/{K} requests in {time.time() - t2:.1f}s")
        time.sleep(6)
        count_after = len(workflow_runs(olga, ws, wf, f"KB-{suffix}"))
        check(f"{K} records started {K} requests, once each", count == K and count_after == K, f"{count} then {count_after}")

    print("Scenario 51d: inbound webhook burst")
    inb = wade.post(f"/workspaces/{ws}/inbound-triggers", {"key": f"e2e-bulk-in-{suffix}", "source": "jdbc", "name": "E2E bulk inbound", "workflowId": wf,
                                                           "secretRef": "e2e-inbound", "mapping": {"orderNumber": "/row/order", "customerName": "/row/customer"}})
    check("the inbound trigger is created", inb.status_code == 201, f"{inb.status_code} {inb.text[:160]}")
    if inb.status_code == 201:
        path_id = (inb.json().get("receivePath") or "").rsplit("/", 1)[-1] or inb.json()["id"]
        m = min(N, 100)
        events = [str(uuid.uuid4()) for _ in range(m)]

        def post(i):
            return inbound(ws, path_id, {"row": {"order": f"IB-{suffix}-{i % m}", "customer": "Bulk"}}, event_id=events[i % m]).status_code

        t3 = time.time()
        with ThreadPoolExecutor(THREADS) as ex:
            codes = list(ex.map(post, range(m * 2)))
        accepted = [c for c in codes if c == 202]
        limited = [c for c in codes if c == 429]
        check("every call is accepted or rate limited, never a server error", len(accepted) + len(limited) == len(codes), str(sorted(set(codes))))
        print(f"  inbound: {len(accepted)} accepted, {len(limited)} rate limited in {time.time() - t3:.1f}s")

    failed = [n for n, good in base.results if not good]
    print(f"\n{len(base.results) - len(failed)}/{len(base.results)} checks passed")
    for n in failed:
        print("  FAILED:", n)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
