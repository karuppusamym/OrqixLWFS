"""Message events end to end (scenario 40), on the Docker stack.

  40  Order payment (examples/order-payment-wait.json): a request waits for the message payment-received, correlated
      on its order number, with a two day time limit.
        a  A message after the wait started ends it; the payload values reach the result; the timeline shows it.
        b  A message for another order number only waits in the buffer; the request keeps waiting.
        c  A message that arrives before the request is kept and used when the wait starts (buffered window).
        d  The same Idempotency-Key replays (header Idempotent-Replayed) and delivers once; another message is kept.
        e  The payload must match the declared values (required, type, unknown) and nothing is delivered then.
        f  Authorization: a requester without message.send gets 403; a person of another workspace gets 404.

Uses session users of the dev seed (olga.operator holds message.send through the Operator role). A machine client can
call the same endpoint with a bearer token once an admin grants it message.send (S16 section 4).

    python tools/api-e2e/e2e_messages.py
"""
import json
import sys
import uuid
from pathlib import Path

import e2e_scenarios as base
from e2e_scenarios import check, wait_run
from orqix_client import Client

SAMPLE = Path(__file__).resolve().parents[2] / "examples" / "order-payment-wait.json"


def workspace(client, key="purchasing"):
    return next(w["id"] for w in client.get("/me").json()["workspaces"] if w["key"] == key)


def new_workflow(designer, publisher, ws):
    key = f"e2e-msg-{uuid.uuid4().hex[:8]}"
    definition = json.loads(SAMPLE.read_text(encoding="utf-8"))
    definition["workflowKey"] = key
    created = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": f"Order payment {key}"})
    check("workflow created", created.status_code == 201, f"{created.status_code} {created.text[:200]}")
    wf = created.json()
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()
    saved = designer.put(f"/workspaces/{ws}/workflows/{wf['id']}/draft", {"definition": definition},
                         if_match=f'"r{draft["revision"]}"')
    check("the draft with a message wait is saved", saved.status_code == 200, f"{saved.status_code} {saved.text[:200]}")
    published = publisher.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish",
                               {"draftRevision": saved.json()["revision"], "acknowledgeWarnings": True}, idem=True)
    check("the workflow is published", published.status_code == 201, f"{published.status_code} {published.text[:300]}")
    return wf["id"]


def start(rhea, ws, wf, order):
    r = rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf, "input": {"orderNumber": order, "customerName": "Acme"}},
                  idem=True)
    check(f"request {order} starts", r.status_code == 201, f"{r.status_code} {r.text[:200]}")
    return r.json()["id"]


def send(client, ws, key, name, order, payload=None, idem=None):
    body = {"name": name, "correlationKey": order}
    if payload is not None:
        body["payload"] = payload
    return client.post(f"/workspaces/{ws}/messages", body, key=idem or str(uuid.uuid4()))


def events(client, ws, run_id):
    return client.get(f"/workspaces/{ws}/runs/{run_id}/events", limit=100).json()["items"]


def main():
    rhea, paul, olga, dana, fred = (Client(u) for u in (
        "rhea.requester", "paul.publisher", "olga.operator", "dana.designer", "fred.facilities"))
    ws = workspace(rhea)
    wf = new_workflow(dana, paul, ws)
    suffix = uuid.uuid4().hex[:6]

    print("Scenario 40a: a message ends the wait")
    order = f"SO-{suffix}-A"
    run = start(rhea, ws, wf, order)
    detail = rhea.get(f"/workspaces/{ws}/runs/{run}").json()
    step = detail["currentSteps"][0]
    check("the request waits for the message", detail["lifecycle"] == "waiting" and step["kind"] == "wait"
          and step["messageName"] == "payment-received" and step["dueAt"], str(step))
    r = send(olga, ws, order, "payment-received", order, {"paymentReference": "PAY-77", "amountPaid": 125.5})
    check("the message is accepted and delivered to the request", r.status_code == 202 and r.json()["status"] == "delivered"
          and r.json()["matched"] == 1 and r.json()["delivered"][0]["runId"] == run, f"{r.status_code} {r.text[:300]}")
    end = wait_run(rhea, ws, run, lambda x: x["lifecycle"] in ("completed", "needsAttention"), 60)
    check("the request finishes as paid with the payment reference", end["lifecycle"] == "completed"
          and end["outcome"] == "Order paid" and end["output"].get("paymentReference") == "PAY-77", str(end)[:300])
    log = events(rhea, ws, run)
    types = [e["type"] for e in log]
    ended = next((e for e in log if e["type"] == "WAIT_ENDED"), None)
    check("one WAIT_STARTED and one WAIT_ENDED (received), without the order number or values",
          types.count("WAIT_STARTED") == 1 and types.count("WAIT_ENDED") == 1 and ended["data"].get("outcome") == "received"
          and order not in json.dumps(log) and "PAY-77" not in json.dumps(log), str(types))

    print("Scenario 40b: another order number does not touch the request")
    order_b = f"SO-{suffix}-B"
    run_b = start(rhea, ws, wf, order_b)
    r = send(olga, ws, f"SO-{suffix}-OTHER", "payment-received", f"SO-{suffix}-OTHER", {"paymentReference": "X"})
    check("a message for another order is only buffered", r.status_code == 202 and r.json()["status"] == "buffered"
          and r.json()["matched"] == 0 and r.json()["bufferedUntil"], f"{r.status_code} {r.text[:200]}")
    check("the request keeps waiting", rhea.get(f"/workspaces/{ws}/runs/{run_b}").json()["lifecycle"] == "waiting")

    print("Scenario 40c: a message that arrives first is used when the wait starts")
    order_c = f"SO-{suffix}-C"
    early = send(olga, ws, order_c, "payment-received", order_c, {"paymentReference": "EARLY-1"})
    check("it is kept", early.status_code == 202 and early.json()["status"] == "buffered", early.text[:200])
    run_c = start(rhea, ws, wf, order_c)
    end_c = wait_run(rhea, ws, run_c, lambda x: x["lifecycle"] in ("completed", "needsAttention"), 60)
    check("the request finishes with the early message", end_c["lifecycle"] == "completed"
          and end_c["output"].get("paymentReference") == "EARLY-1", str(end_c)[:300])

    print("Scenario 40d: idempotent delivery")
    order_d = f"SO-{suffix}-D"
    run_d = start(rhea, ws, wf, order_d)
    idem = f"msg-{uuid.uuid4()}"
    first = send(olga, ws, order_d, "payment-received", order_d, {"paymentReference": "P1"}, idem)
    again = send(olga, ws, order_d, "payment-received", order_d, {"paymentReference": "P1"}, idem)
    check("the replay returns the same message", again.status_code == 202 and again.headers.get("Idempotent-Replayed") == "true"
          and again.json()["id"] == first.json()["id"], f"{again.status_code} {again.text[:200]}")
    different = send(olga, ws, order_d, "payment-received", order_d, {"paymentReference": "P2"}, idem)
    check("the same key with another body is refused", different.status_code == 409, str(different.status_code))
    wait_run(rhea, ws, run_d, lambda x: x["lifecycle"] == "completed", 60)
    second = send(olga, ws, order_d, "payment-received", order_d, {"paymentReference": "P3"})
    check("a second message finds the wait closed and is kept", second.json()["status"] == "buffered")
    check("the wait ended once", [e["type"] for e in events(rhea, ws, run_d)].count("WAIT_ENDED") == 1)

    print("Scenario 40e: the payload must match what the step declares")
    for label, payload, code, pointer in [
        ("a required value is missing", {"amountPaid": 1}, "required", "/payload/paymentReference"),
        ("a value of the wrong kind", {"paymentReference": "P", "amountPaid": "lots"}, "type", "/payload/amountPaid"),
        ("a value the step does not declare", {"paymentReference": "P", "surprise": 1}, "unknown", "/payload/surprise"),
    ]:
        r = send(olga, ws, order_b, "payment-received", order_b, payload)
        fields = r.json().get("fieldErrors", []) if r.status_code == 422 else []
        check(f"refused: {label}", r.status_code == 422 and any(f["code"] == code and f["pointer"] == pointer for f in fields),
              f"{r.status_code} {r.text[:200]}")
    check("the request still waits", rhea.get(f"/workspaces/{ws}/runs/{run_b}").json()["lifecycle"] == "waiting")
    nested = send(olga, ws, order_b, "payment-received", order_b, {"paymentReference": {"a": 1}})
    check("a nested value is refused", nested.status_code == 422, str(nested.status_code))

    print("Scenario 40f: authorization")
    denied = send(rhea, ws, order_b, "payment-received", order_b, {"paymentReference": "P"})
    check("a requester without message.send gets 403", denied.status_code == 403, f"{denied.status_code} {denied.text[:200]}")
    foreign = send(fred, ws, order_b, "payment-received", order_b, {"paymentReference": "P"})
    check("a person of another workspace gets 404", foreign.status_code == 404, f"{foreign.status_code} {foreign.text[:200]}")
    check("the request still waits", rhea.get(f"/workspaces/{ws}/runs/{run_b}").json()["lifecycle"] == "waiting")
    missing = olga.post(f"/workspaces/{ws}/messages", {"name": "payment-received", "correlationKey": order_b})
    check("an Idempotency-Key is required", missing.status_code == 400, str(missing.status_code))
    finish = send(olga, ws, order_b, "payment-received", order_b, {"paymentReference": "OK"})
    check("the right message still works", finish.status_code == 202 and finish.json()["matched"] == 1, finish.text[:200])

    results = base.results
    passed = sum(1 for _, ok in results if ok)
    print(f"\nRESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
