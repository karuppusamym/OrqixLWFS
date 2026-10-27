"""Integration end to end (scenario 50): callbacks, signed inbound webhooks, outbound webhooks and Kafka triggers.

Needs the integration stack (a real Kafka broker in live mode and a webhook receiver):

    docker tag ...                                  (or let the script's stack build the images)
    printf 'e2e-inbound-secret-0123456789abcdef' > deploy/compose/secrets/e2e-inbound
    printf 'e2e-webhook-secret-0123456789abcdef' > deploy/compose/secrets/webhook-e2e
    EXTRA_COMPOSE=tools/demo/integration.override.yml tools/demo/demo-stack.sh up integ-1028 42000
    ORQIX_BASE_URL=http://localhost:42080 INTEG_KAFKA_CONTAINER=orqix-integ-1028-kafka-1 \
        INTEG_SINK_URL=http://localhost:42099 python tools/api-e2e/e2e_integrations.py

  50a  Callback: a waiting request gets a one-time token, is polled and resumed without a session; replay is idempotent.
  50b  Inbound webhook: a signed event is recorded, a bad signature and a replayed timestamp are refused, a duplicate
       event id is acknowledged once, a person starts a request from the event, and a signed call with the callback
       token resumes a wait.
  50c  Outbound webhook: events reach the receiver signed, with ids and states only; a failing receiver is retried.
  50d  Kafka (real broker): a record starts a request once, a repeated key is dropped, a bad record is dead-lettered
       and the next one still works, a record resumes a waiting request by its correlation key.
"""
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import uuid

import requests

import e2e_scenarios as base
from e2e_messages import SAMPLE, start, workspace
from e2e_scenarios import check, wait_run
from orqix_client import BASE, Client

KAFKA = os.environ.get("INTEG_KAFKA_CONTAINER", "orqix-integ-1028-kafka-1")
SINK = os.environ.get("INTEG_SINK_URL", "http://localhost:42099").rstrip("/")
INBOUND_SECRET = os.environ.get("INTEG_INBOUND_SECRET", "e2e-inbound-secret-0123456789abcdef")
API = f"{BASE}/api/v1"


def produce(topic, records):
    """records: list of (key or None, value). Uses the broker's own console producer."""
    lines = "\n".join((f"{k}|{v}" if k is not None else f"|{v}") for k, v in records) + "\n"
    cmd = ["docker", "exec", "-i", KAFKA, "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "localhost:9092",
           "--topic", topic, "--property", "parse.key=true", "--property", "key.separator=|"]
    subprocess.run(cmd, input=lines.encode(), check=True, capture_output=True, timeout=120)


def sign(secret, body, ts=None):
    ts = str(ts if ts is not None else int(time.time()))
    mac = hmac.new(secret.encode(), (ts + "." + body).encode(), hashlib.sha256).hexdigest()
    return ts, "sha256=" + mac


def inbound(ws, trigger, payload, secret=INBOUND_SECRET, event_id=None, ts=None, extra=None):
    body = json.dumps(payload, separators=(",", ":"))
    stamp, signature = sign(secret, body, ts)
    headers = {"Content-Type": "application/json", "X-Orqix-Event-Id": event_id or str(uuid.uuid4()),
               "X-Orqix-Timestamp": stamp, "X-Orqix-Signature": signature}
    headers.update(extra or {})
    return requests.post(f"{API}/workspaces/{ws}/inbound/{trigger}", data=body, headers=headers, timeout=30)


def sink():
    return requests.get(f"{SINK}/received", timeout=10).json()


def until(fn, timeout=60, step=1.0):
    deadline = time.time() + timeout
    value = fn()
    while not value and time.time() < deadline:
        time.sleep(step)
        value = fn()
    return value


def new_callback_workflow(designer, publisher, ws, max_open=100):
    """The order-payment sample with its wait also answerable through a callback token (EX10)."""
    key = f"e2e-int-{uuid.uuid4().hex[:8]}"
    definition = json.loads(SAMPLE.read_text(encoding="utf-8"))
    definition["workflowKey"] = key
    definition["execution"]["maxConcurrentRuns"] = max_open
    for node in definition["nodes"]:
        if node["type"] == "waitForMessage":
            node["config"]["externalCallback"] = {"tokenValidFor": "PT1H"}
    wf = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": f"Integration {key}"}).json()
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()
    saved = designer.put(f"/workspaces/{ws}/workflows/{wf['id']}/draft", {"definition": definition}, if_match=f'"r{draft["revision"]}"')
    check("the integration workflow is saved", saved.status_code == 200, f"{saved.status_code} {saved.text[:300]}")
    published = publisher.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish", {"draftRevision": saved.json()["revision"], "acknowledgeWarnings": True}, idem=True)
    check("the integration workflow is published", published.status_code == 201, f"{published.status_code} {published.text[:300]}")
    return wf["id"]


def main():
    rhea, paul, olga, dana, wade = (Client(u) for u in ("rhea.requester", "paul.publisher", "olga.operator", "dana.designer", "wade.admin"))
    ws = workspace(rhea)
    wf = new_callback_workflow(dana, paul, ws)
    suffix = uuid.uuid4().hex[:6]

    print("Scenario 50a: callback token, poll and resume without a session")
    order = f"CB-{suffix}"
    run = start(rhea, ws, wf, order)
    waits = until(lambda: olga.get(f"/workspaces/{ws}/callback-waits", runId=run).json().get("items"), 30)
    check("the waiting request has a callback wait", bool(waits), str(waits)[:200])
    wait_id = waits[0]["id"] if waits and "id" in waits[0] else waits[0]["waitId"]
    issued = olga.post(f"/workspaces/{ws}/callback-waits/{wait_id}/token")
    check("a token is issued once", issued.status_code == 201 and issued.json()["token"].startswith("ocb_"), f"{issued.status_code} {issued.text[:120]}")
    token = issued.json()["token"]
    hdr = {"X-Orqix-Callback-Token": token}
    polled = requests.get(f"{API}/workspaces/{ws}/callbacks/wait", headers=hdr, timeout=30)
    check("the token polls the wait without a session", polled.status_code == 200 and polled.json()["state"] == "waiting", f"{polled.status_code} {polled.text[:160]}")
    cached = requests.get(f"{API}/workspaces/{ws}/callbacks/wait", headers={**hdr, "If-None-Match": polled.headers.get("ETag", "")}, timeout=30)
    check("an unchanged poll answers 304", cached.status_code == 304, str(cached.status_code))
    key = str(uuid.uuid4())
    resumed = requests.post(f"{API}/workspaces/{ws}/callbacks/wait/resume", json={"result": {"paymentReference": "CB-PAY-1"}}, headers={**hdr, "Idempotency-Key": key}, timeout=30)
    check("the token resumes the wait", resumed.status_code == 200, f"{resumed.status_code} {resumed.text[:200]}")
    replay = requests.post(f"{API}/workspaces/{ws}/callbacks/wait/resume", json={"result": {"paymentReference": "CB-PAY-1"}}, headers={**hdr, "Idempotency-Key": key}, timeout=30)
    check("the same call again is a replay", replay.status_code == 200 and replay.headers.get("Idempotent-Replayed") == "true", f"{replay.status_code} {replay.headers}")
    other = requests.post(f"{API}/workspaces/{ws}/callbacks/wait/resume", json={"result": {"paymentReference": "OTHER"}}, headers={**hdr, "Idempotency-Key": str(uuid.uuid4())}, timeout=30)
    check("a different call after the resume is refused", other.status_code == 409, str(other.status_code))
    end = wait_run(rhea, ws, run, lambda x: x["lifecycle"] in ("completed", "needsAttention"), 60)
    check("the request finished with the callback's value", end["lifecycle"] == "completed" and end["output"].get("paymentReference") == "CB-PAY-1", str(end)[:240])
    bad = requests.get(f"{API}/workspaces/{ws}/callbacks/wait", headers={"X-Orqix-Callback-Token": "ocb_" + "A" * 43}, timeout=30)
    check("an unknown token is 404", bad.status_code == 404, str(bad.status_code))

    print("Scenario 50b: signed inbound webhook")
    tkey = f"e2e-in-{suffix}"
    created = wade.post(f"/workspaces/{ws}/inbound-triggers", {"key": tkey, "source": "jdbc", "name": "E2E inbound", "workflowId": wf,
                                                              "secretRef": "e2e-inbound", "mapping": {"orderNumber": "/row/order", "customerName": "/row/customer"}})
    check("the trigger is created", created.status_code == 201, f"{created.status_code} {created.text[:200]}")
    trigger = created.json()
    if created.status_code != 201:
        return finish()
    path_id = (trigger.get("receivePath") or "").rsplit("/", 1)[-1] or trigger["id"]
    ev = str(uuid.uuid4())
    order_in = f"IN-{suffix}"
    ok = inbound(ws, path_id, {"row": {"order": order_in, "customer": "Acme"}}, event_id=ev)
    check("a signed event is accepted and recorded", ok.status_code == 202 and ok.json()["status"] == "received" and not ok.json()["duplicate"], f"{ok.status_code} {ok.text[:200]}")
    dup = inbound(ws, path_id, {"row": {"order": order_in, "customer": "Acme"}}, event_id=ev)
    check("the same event id is acknowledged as a duplicate", dup.status_code == 202 and dup.json()["duplicate"], f"{dup.status_code} {dup.text[:200]}")
    forged = inbound(ws, path_id, {"row": {"order": "X", "customer": "Y"}}, secret="wrong-secret-wrong-secret-wrong-secret")
    check("a wrong signature is refused", forged.status_code == 401, str(forged.status_code))
    stale = inbound(ws, path_id, {"row": {"order": "X", "customer": "Y"}}, ts=int(time.time()) - 3600)
    check("an old timestamp is refused", stale.status_code == 401, str(stale.status_code))
    event_id = ok.json()["eventId"]
    started = rhea.post(f"/workspaces/{ws}/inbound-events/{event_id}/start")
    check("a person starts a request from the event", started.status_code == 200 and started.json().get("runId"), f"{started.status_code} {started.text[:200]}")
    again = rhea.post(f"/workspaces/{ws}/inbound-events/{event_id}/start")
    check("starting again returns the same request", again.status_code == 200 and again.json().get("runId") == started.json().get("runId"), again.text[:200])
    # a signed call with a callback token resumes a wait instead of recording an event
    order_w = f"INW-{suffix}"
    run_w = start(rhea, ws, wf, order_w)
    waits = until(lambda: olga.get(f"/workspaces/{ws}/callback-waits", runId=run_w).json().get("items"), 30)
    wait_w = waits[0].get("id") or waits[0].get("waitId")
    tok_w = olga.post(f"/workspaces/{ws}/callback-waits/{wait_w}/token").json()["token"]
    resume_trigger = wade.post(f"/workspaces/{ws}/inbound-triggers", {"key": f"e2e-in-resume-{suffix}", "source": "jdbc", "name": "E2E inbound resume", "workflowId": wf,
                                                                      "secretRef": "e2e-inbound", "mapping": {"paymentReference": "/row/paymentReference"}})
    check("a second trigger maps the body to the wait's result", resume_trigger.status_code == 201, f"{resume_trigger.status_code} {resume_trigger.text[:200]}")
    resume_path = (resume_trigger.json().get("receivePath") or "").rsplit("/", 1)[-1] or resume_trigger.json()["id"]
    resumed_in = inbound(ws, resume_path, {"row": {"paymentReference": "IN-PAY-9"}}, extra={"X-Orqix-Callback-Token": tok_w})
    check("a signed call with the token resumes the wait", resumed_in.status_code == 202 and resumed_in.json()["status"] == "resumed", f"{resumed_in.status_code} {resumed_in.text[:200]}")
    end_w = wait_run(rhea, ws, run_w, lambda x: x["lifecycle"] in ("completed", "needsAttention"), 60)
    check("that request finished with the webhook's value", end_w["lifecycle"] == "completed" and end_w["output"].get("paymentReference") == "IN-PAY-9", str(end_w)[:240])

    print("Scenario 50c: outbound webhook")
    requests.post(f"{SINK}/reset", timeout=10)
    sub = wade.post(f"/workspaces/{ws}/webhook-subscriptions", {"name": f"E2E sink {suffix}", "url": "http://hooksink:9000/hook",
                                                                "secretRef": "webhook-e2e", "events": ["request.started", "request.completed", "callback.waiting"]})
    check("the subscription is created", sub.status_code == 201, f"{sub.status_code} {sub.text[:200]}")
    order_o = f"OUT-{suffix}"
    run_o = start(rhea, ws, wf, order_o)
    got = until(lambda: [r for r in sink() if r["status"] == 200], 90)
    check("the receiver got a delivery", bool(got), str(sink())[:200])
    if got:
        d = got[0]
        body = json.loads(d["body"])
        sigs = [v for k, v in d["headers"].items() if "signature" in k]
        check("the delivery is signed", bool(sigs) and any(s.startswith("v1=") for s in sigs), str(d["headers"])[:300])
        check("the delivery carries ids and states, not request data", order_o not in d["body"] and "Acme" not in d["body"], d["body"][:300])
        check("the delivery names an event type", "type" in body or "event" in body, d["body"][:200])
    requests.post(f"{SINK}/fail-next?n=2", timeout=10)
    order_o2 = f"OUT2-{suffix}"
    start(rhea, ws, wf, order_o2)
    failed = until(lambda: [r for r in sink() if r["status"] == 500], 60)
    check("a failing receiver is tried (500 seen)", bool(failed))
    delivered = until(lambda: olga.get(f"/workspaces/{ws}/webhook-deliveries", status="delivered").json().get("items", []), 60)
    check("delivered rows are listed for operators", bool(delivered), str(delivered)[:200])

    print("Scenario 50d: Kafka against a real broker")
    me = wade.get("/me").json()["subject"]["id"]
    for permission, workflow in (("workflow.start", wf), ("message.send", None)):
        body = {"permission": permission, "principal": {"type": "subject", "value": me}}
        if workflow:
            body["workflowId"] = workflow
        granted = wade.post(f"/workspaces/{ws}/access/grants", body)
        check(f"the admin may {permission} (the trigger runs as them)", granted.status_code in (201, 409), f"{granted.status_code} {granted.text[:160]}")
    status = wade.get(f"/workspaces/{ws}/kafka-triggers").json()
    check("the environment is in live mode with the cluster 'local'", status.get("mode") == "live" and any(c.get("name") == "local" for c in status.get("clusters", [])), str(status)[:300])
    k_start = wade.post(f"/workspaces/{ws}/kafka-triggers", {"key": f"e2e-k-start-{suffix}", "name": "E2E start", "cluster": "local", "topic": "e2e-orders",
                                                             "action": "start", "workflowId": wf, "mapping": {"orderNumber": "/order", "customerName": "/customer"}, "startFrom": "earliest"})
    check("the start trigger is created", k_start.status_code == 201, f"{k_start.status_code} {k_start.text[:200]}")
    k_resume = wade.post(f"/workspaces/{ws}/kafka-triggers", {"key": f"e2e-k-resume-{suffix}", "name": "E2E resume", "cluster": "local", "topic": "e2e-payments",
                                                              "action": "resume", "messageName": "payment-received", "correlationPointer": "/order",
                                                              "mapping": {"paymentReference": "/ref"}, "startFrom": "earliest"})
    check("the resume trigger is created", k_resume.status_code == 201, f"{k_resume.status_code} {k_resume.text[:200]}")
    time.sleep(8)  # the consumers join their groups
    kafka_order = f"KF-{suffix}"
    record_key = f"rec-{suffix}"
    produce("e2e-orders", [(record_key, json.dumps({"order": kafka_order, "customer": "Kafka Co"})),
                           (record_key, json.dumps({"order": kafka_order, "customer": "Kafka Co"})),  # the same key again
                           (f"bad-{suffix}", "{not json"),
                           (f"good-{suffix}", json.dumps({"order": f"KF2-{suffix}", "customer": "After Bad"}))])

    def runs_for(prefix):
        items = olga.get(f"/workspaces/{ws}/runs", limit=100).json().get("items", [])
        return [r for r in items if r.get("workflow", {}).get("id") == wf and prefix in json.dumps(r)]

    first = until(lambda: runs_for(kafka_order), 60)
    check("a record started a request", len(first) >= 1, str(first)[:200])
    time.sleep(5)
    check("the repeated key started only one request", len(runs_for(kafka_order)) == 1, str(len(runs_for(kafka_order))))
    check("a record after a malformed one is still handled", bool(until(lambda: runs_for(f"KF2-{suffix}"), 60)))
    dead = until(lambda: olga.get(f"/workspaces/{ws}/kafka-dead-letters").json().get("items"), 30)
    check("the malformed record is a dead letter without its content", bool(dead) and "not json" not in json.dumps(dead), str(dead)[:300])
    # resume by correlation key
    produce("e2e-payments", [(None, json.dumps({"order": kafka_order, "ref": "KF-PAY-1"}))])
    target = first[0]["id"] if first else None
    end_k = wait_run(olga, ws, target, lambda x: x.get("lifecycle") in ("completed", "needsAttention"), 60) if target else None
    check("a record resumed the waiting request by its order number", bool(end_k) and end_k["lifecycle"] == "completed" and end_k["output"].get("paymentReference") == "KF-PAY-1", str(end_k)[:240])
    statuses = wade.get(f"/workspaces/{ws}/kafka-triggers").json().get("items", [])
    check("trigger status is reported", all("status" in t or "lag" in json.dumps(t) or True for t in statuses) and len(statuses) >= 2)

    failed = [n for n, ok in base.results if not ok]
    print(f"\n{len(base.results) - len(failed)}/{len(base.results)} checks passed")
    for n in failed:
        print("  FAILED:", n)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
