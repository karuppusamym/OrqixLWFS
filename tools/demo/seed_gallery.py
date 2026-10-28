"""A gallery of ready-made examples for a live demo or a first look, created through the public API only.

    ORQIX_BASE_URL=http://localhost:8080 python tools/demo/seed_gallery.py

It adds, to the 'purchasing' workspace of a development stack:
  * shared decision tables (rules you can reuse in many workflows), and
  * example workflows from the examples/ folder, named "Sample - ...", saved as drafts and published when they publish
    cleanly (an example that needs a connection or setting this stack does not have stays a draft and is listed).
Safe to repeat: a decision table or workflow whose key already exists is left alone. Dev-realm users only.
"""
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "api-e2e"))
from orqix_client import Client  # noqa: E402

EXAMPLES = ROOT / "examples"

DECISIONS = [
    ("sample-purchase-routing", "Sample - Purchase routing", "Who approves a purchase: by amount, then by cost centre. First matching rule wins.",
     {"hitPolicy": "FIRST",
      "rows": [
          {"when": {"fieldRef": "/input/amount", "operator": "gte", "value": 50000}, "then": {"route": "executiveReview"}},
          {"when": {"fieldRef": "/input/amount", "operator": "gte", "value": 5000}, "then": {"route": "financeReview"}},
      ],
      "default": {"route": "standard"}}),
    ("sample-expense-limit", "Sample - Expense limit", "A simple two-way rule: small expenses are approved by the manager, large ones also by finance.",
     {"hitPolicy": "UNIQUE",
      "rows": [{"when": {"fieldRef": "/input/amount", "operator": "gte", "value": 1000}, "then": {"route": "financeReview"}}],
      "default": {"route": "standard"}}),
]

WORKFLOWS = [
    # The loop and batch samples start this one as a child workflow, so it comes first (its key is fixed by them).
    ("review-request", "simple-review.json", "Sample - Review request (used by the loop and batch samples)"),
    ("sample-simple-review", "simple-review.json", "Sample - Simple review (one approver)"),
    ("sample-new-hire", "new-hire-onboarding.json", "Sample - New hire onboarding (several teams)"),
    ("sample-rework-loop", "medium-bounded-rework.json", "Sample - Review with rework loop"),
    ("sample-order-payment", "order-payment-wait.json", "Sample - Order waits for payment (outside system)"),
    ("sample-start-date", "start-date-onboarding.json", "Sample - Wait until a start date"),
    ("sample-case-stages", "case-with-stages.json", "Sample - Case with stages"),
    ("sample-complex-batch", "complex-batch-review.json", "Sample - Batch review (a larger workflow)"),
]


def main():
    dana, paul = Client("dana.designer"), Client("paul.publisher")
    ws = next(w["id"] for w in dana.get("/me").json()["workspaces"] if w["key"] == "purchasing")

    print("Shared decision tables")
    for key, name, description, content in DECISIONS:
        r = dana.post(f"/workspaces/{ws}/shared-assets/decision", {"key": key, "name": name, "description": description, "content": content})
        print(f"  {name:40} {'created' if r.status_code == 201 else 'kept (' + str(r.status_code) + ')' if r.status_code == 409 else 'NOT created: ' + r.text[:140]}")

    print("Example workflows")
    items = {w["key"]: w for w in dana.get(f"/workspaces/{ws}/workflows").json()["items"]}
    existing = set(items)
    drafts = []
    for key, filename, name in WORKFLOWS:
        if key in existing:
            if items[key].get("activeRelease") is None:  # a draft from an earlier run: try to publish it now
                draft = dana.get(f"/workspaces/{ws}/workflows/{items[key]['id']}/draft").json()
                published = paul.post(f"/workspaces/{ws}/workflows/{items[key]['id']}/publish", {"draftRevision": draft["revision"], "acknowledgeWarnings": True}, idem=True)
                print(f"  {name:55} {'published now' if published.status_code == 201 else 'still a draft (' + str(published.status_code) + ')'}")
            else:
                print(f"  {name:55} kept")
            continue
        definition = json.loads((EXAMPLES / filename).read_text(encoding="utf-8"))
        definition["workflowKey"] = key
        definition["name"] = name
        created = dana.post(f"/workspaces/{ws}/workflows", {"key": key, "name": name})
        if created.status_code != 201:
            print(f"  {name:55} NOT created: {created.status_code} {created.text[:120]}")
            continue
        wf = created.json()["id"]
        draft = dana.get(f"/workspaces/{ws}/workflows/{wf}/draft").json()
        saved = dana.put(f"/workspaces/{ws}/workflows/{wf}/draft", {"definition": definition}, if_match=f'"r{draft["revision"]}"')
        if saved.status_code != 200:
            print(f"  {name:55} draft not saved: {saved.status_code} {saved.text[:160]}")
            continue
        published = paul.post(f"/workspaces/{ws}/workflows/{wf}/publish", {"draftRevision": saved.json()["revision"], "acknowledgeWarnings": True}, idem=True)
        if published.status_code == 201:
            print(f"  {name:55} published")
        else:
            drafts.append(name)
            print(f"  {name:55} left as a draft ({published.status_code}: {published.text[:110]})")
    if drafts:
        print(f"\n{len(drafts)} example(s) stay drafts here because this stack lacks something they need; open them in the Studio and press Check workflow.")


if __name__ == "__main__":
    main()
