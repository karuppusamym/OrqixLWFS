"""Pega Blueprint PDF import on the Docker stack (docs/build/S91).

Uploads examples/migration/pega-blueprint/RetailLoanOrigination.pdf through the real stack (Keycloak login, app
container, PDF extraction worker, PostgreSQL), checks the transcription of each case type and the findings, then
reviews the Home Loan approval path the way a designer would and converts it into a workflow draft.

The script prints counts, ids and finding codes, not the document text.

    python tools/api-e2e/e2e_blueprint.py      # about 30 s; creates one workflow draft per run
"""
import copy
import json
import sys
import uuid
from pathlib import Path

from orqix_client import BASE, Client

PDF = Path(__file__).resolve().parents[2] / "examples" / "migration" / "pega-blueprint" / "RetailLoanOrigination.pdf"
EXPECTED = PDF.with_name("RetailLoanOrigination.transcription.json")
CASE_TYPES = ["Home Loan Application", "Unsecured Retail Loan Application", "Secured Retail Loan Application"]
APPROVALS = ["step-in-principal-approval-or-reject", "step-conditional-approval-reject", "step-approve-reject-loan",
             "step-approval-loan-contract"]
ROLES = ["role-mortgage-advisor", "role-credit-risk-analyst", "role-underwriter", "role-underwriter"]
LISTS = ["stages", "steps", "decisions", "fields", "roles", "integrations"]

failures = []


def check(label, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + label + (f" ({detail})" if detail and not ok else ""))
    if not ok:
        failures.append(label)


def workspace(client):
    return next(w["id"] for w in client.get("/me").json()["workspaces"] if w["key"] == "purchasing")


def upload(client, ws, case_type=None):
    data = {"caseType": case_type} if case_type else {}
    return client.s.post(f"{BASE}/api/v1/workspaces/{ws}/migrations/blueprints",
                         files={"file": ("RetailLoanOrigination.pdf", PDF.read_bytes(), "application/pdf")},
                         data=data, headers=client._headers(str(uuid.uuid4())), timeout=180)


def codes(report):
    return sorted({i["reasonCode"] for i in report.get("issues", [])})


def exclude(element, reason):
    element["disposition"] = "excluded"
    element["dispositionReason"] = reason


def reviewed(transcription):
    """A designer's review: keep the four approvals in order, exclude everything the export cannot execute."""
    t = copy.deepcopy(transcription)
    for step in t["steps"]:
        if step["id"] in APPROVALS:
            at = APPROVALS.index(step["id"])
            step["role"] = ROLES[at]
            step["next"] = APPROVALS[at + 1] if at + 1 < len(APPROVALS) else "outcome-approved"
            step["onReject"] = "outcome-rejected"
        else:
            exclude(step, "Not an approval; rebuilt in the studio after conversion.")
    for name in ("decisions", "integrations"):
        for element in t[name]:
            exclude(element, "Rebuilt in the studio after conversion.")
    for field in t["fields"]:
        if field["id"] not in ("loanAmount", "applicantName"):
            exclude(field, "Not a request input.")
    t["start"] = APPROVALS[0]
    t["outcomes"] = [
        {"id": "outcome-approved", "name": "Approved", "type": "success", "disposition": "manuallyResolved"},
        {"id": "outcome-rejected", "name": "Rejected", "type": "rejected", "disposition": "manuallyResolved"},
    ]
    for name in ("stages", "roles", "fields", "steps", "decisions", "integrations"):
        for element in t[name]:
            if element.get("disposition") != "excluded":
                element["disposition"] = "mapped"
    t["caseType"]["disposition"] = "mapped"
    return t


def main():
    if not PDF.exists():
        print(f"missing {PDF}")
        return 2
    designer = Client("dana.designer")
    ws = workspace(designer)

    # 1. Every case type in the export transcribes; the first one matches the committed expectation.
    expected = json.loads(EXPECTED.read_text(encoding="utf-8"))
    home = None
    for name in CASE_TYPES:
        r = upload(designer, ws, name)
        check(f"upload '{name}' -> 201", r.status_code == 201, f"{r.status_code} {r.text[:200]}")
        if r.status_code != 201:
            continue
        job = r.json()
        view = designer.get(f"/workspaces/{ws}/migrations/{job['id']}/blueprint").json()
        t = view["transcription"]
        counts = {k: len(t.get(k, [])) for k in LISTS}
        print(f"     {name}: " + ", ".join(f"{v} {k}" for k, v in counts.items()))
        check(f"'{name}' read by the Pega export layout", view["report"].get("adapterVersion") == "pega-blueprint-pdf-0.1",
              view["report"].get("adapterVersion"))
        check(f"'{name}' has stages and steps", counts["stages"] > 0 and counts["steps"] > 0, str(counts))
        check(f"'{name}' is the case type that was asked for", t["caseType"]["name"] == name, t["caseType"]["name"])
        check(f"'{name}' is not publishable before review", view["report"].get("publishable") is False)
        if name == CASE_TYPES[0]:
            home = (job, view)
            check("Home Loan transcription equals the committed expectation", t == expected,
                  ", ".join(f"{k} {len(t.get(k, []))}/{len(expected.get(k, []))}" for k in LISTS))
            found = codes(view["report"])
            print("     findings: " + ", ".join(found))
            for code in ("BLUEPRINT_CASE_TYPES_NOT_TRANSCRIBED", "BLUEPRINT_OUTCOME_MISSING", "BLUEPRINT_STEP_ROLE_MISSING",
                         "BLUEPRINT_DECISION_OUTCOMES_MISSING", "BLUEPRINT_REJECTION_ROUTE_MISSING",
                         "BLUEPRINT_INTEGRATION_UNSUPPORTED"):
                check(f"finding {code}", code in found)

    r = upload(designer, ws, "Car Insurance Claim")
    check("unknown case type -> 400", r.status_code == 400, str(r.status_code))
    if home is None:
        print("\nFAILED: the Home Loan upload did not succeed")
        return 1

    # 2. Review the approval path and convert it into a workflow draft.
    job, view = home
    saved = designer.put(f"/workspaces/{ws}/migrations/{job['id']}/blueprint",
                         {"expectedRevision": view.get("revision", 1), "transcription": reviewed(view["transcription"])})
    check("reviewed transcription saved -> 200", saved.status_code == 200, f"{saved.status_code} {saved.text[:300]}")
    if saved.status_code == 200:
        report = saved.json()["report"]
        blockers = [i["reasonCode"] for i in report.get("issues", []) if i.get("severity") == "blocker"]
        check("no blocking findings after review", not blockers, ", ".join(sorted(set(blockers))))
        print("     open findings after review: " + (", ".join(codes(report)) or "none"))
        key = "home-loan-" + uuid.uuid4().hex[:8]
        request = {"workflowKey": key, "name": "Home loan (e2e)", "acknowledgeInfo": True, "acknowledgeReview": True,
                   "transcriptionSha256": saved.json()["transcriptionSha256"],
                   "groupMappings": {"role-mortgage-advisor": "managers", "role-credit-risk-analyst": "finance-approvers",
                                     "role-underwriter": "finance-approvers"}}
        converted = designer.post(f"/workspaces/{ws}/migrations/{job['id']}/convert", request, idem=True)
        check("convert -> 201", converted.status_code == 201, f"{converted.status_code} {converted.text[:300]}")
        if converted.status_code == 201:
            workflow = converted.json()["workflow"]["id"]
            draft = designer.get(f"/workspaces/{ws}/workflows/{workflow}/draft").json()
            nodes = {n["id"]: n.get("type") for n in draft["definition"]["nodes"]}
            print(f"     workflow {key}: {len(nodes)} steps")
            check("draft contains the four approvals", all(a in nodes for a in APPROVALS), str(sorted(nodes)))
            check("draft leaves out excluded steps", "step-collect-applicant-information" not in nodes)

    print(f"\n{'PASSED' if not failures else 'FAILED'}: {len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
