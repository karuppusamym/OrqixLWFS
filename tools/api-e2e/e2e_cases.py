"""Request files and case stages end to end (scenario 50), on the Docker stack.

  50  Loan application case (template case-with-stages): two files chosen on the request form, three stages with time
      limits, files added and removed later, and the limits the container enforces.
        a  Upload-then-reference: the form's two file questions are filled with uploads; starting attaches them.
        b  The run lists both files, downloads with attachment / nosniff / sandbox headers and the same bytes.
        c  Type checks: an executable named .txt, HTML named .txt and a PDF named .png are refused (422 on /file);
           a file over 10 MiB is refused with 413 before it is stored.
        d  A file added later with a description, then removed (204); the metadata stays, the bytes are gone (404).
        e  People outside the workspace get 404 for the list, the download and the upload.
        f  Stages: the run starts in stage 1 of 3 (Intake) with a due time; the manager's approval moves it to stage 2
           and the timeline shows STAGE_COMPLETED then STAGE_ENTERED, once each.
        g  The request form without a required file is refused (422 on /input/idDocument).

Needs the dev seed groups `managers` and `finance-approvers`, and users rhea.requester, mani.manager, dana.designer,
paul.publisher and otto.outsider.

    python tools/api-e2e/e2e_cases.py
"""
import sys
import uuid

import requests

import e2e_scenarios as base
from e2e_scenarios import check
from orqix_client import BASE, Client

PDF = b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + b"\x00" * 24


def workspace(client):
    return next(w["id"] for w in client.get("/me").json()["workspaces"] if w["key"] == "purchasing")


def upload(client, path, name, content_type, content, fields=None, key=None):
    headers = client._headers(key or str(uuid.uuid4()))
    return client.s.post(f"{BASE}/api/v1{path}", files={"file": (name, content, content_type)}, data=fields or {},
                         headers=headers, timeout=120)


def publish_case(designer, publisher, ws):
    key = f"e2e-case-{uuid.uuid4().hex[:8]}"
    created = designer.post(f"/workspaces/{ws}/workflows", {"key": key, "name": f"Loan case {key}",
                                                              "templateId": "case-with-stages"})
    check("workflow created from the loan case template", created.status_code == 201,
          f"{created.status_code} {created.text[:200]}")
    wf = created.json()
    draft = designer.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()
    published = publisher.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish",
                               {"draftRevision": draft["revision"], "acknowledgeWarnings": True}, idem=True)
    check("the loan case is published", published.status_code == 201, f"{published.status_code} {published.text[:300]}")
    return wf["id"]


def events(client, ws, run_id):
    items, after = [], 0
    while True:
        page = client.get(f"/workspaces/{ws}/runs/{run_id}/events", after=after, limit=100).json()
        items.extend(page["items"])
        if not page["hasMore"]:
            return items
        after = page["nextAfter"]


def main():
    rhea, mani = Client("rhea.requester"), Client("mani.manager")
    designer, publisher, otto = Client("dana.designer"), Client("paul.publisher"), Client("otto.outsider")
    ws = workspace(rhea)
    wf_id = publish_case(designer, publisher, ws)

    # a: upload then reference
    uploads = f"/workspaces/{ws}/attachment-uploads"
    id_doc = upload(rhea, uploads, "passport.pdf", "application/pdf", PDF)
    check("a: the ID file is accepted", id_doc.status_code == 201, f"{id_doc.status_code} {id_doc.text[:200]}")
    income = upload(rhea, uploads, "payslips.png", "image/png", PNG)
    check("a: the proof of income is accepted", income.status_code == 201, f"{income.status_code} {income.text[:200]}")
    check("a: a scan status is reported", id_doc.json()["scanStatus"] in ("notScanned", "clean"), id_doc.text[:200])

    # g: a required file is missing
    missing = rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf_id, "input": {
        "applicant": "Ada Lovelace", "loanAmount": 12000, "purpose": "Vehicle", "incomeProof": income.json()["id"]}},
        idem=True)
    check("g: a missing required file is refused", missing.status_code == 422
          and any(e["pointer"] == "/input/idDocument" for e in missing.json().get("fieldErrors", [])), missing.text[:300])

    started = rhea.post(f"/workspaces/{ws}/runs", {"workflowId": wf_id, "input": {
        "applicant": "Ada Lovelace", "loanAmount": 12000, "purpose": "Vehicle",
        "idDocument": id_doc.json()["id"], "incomeProof": income.json()["id"]}}, idem=True)
    check("a: the request starts with its files", started.status_code == 201, f"{started.status_code} {started.text[:300]}")
    run = started.json()
    run_id = run["id"]
    check("f: the request starts in stage 1 of 3", run.get("stage", {}).get("index") == 1
          and run["stage"]["total"] == 3 and run["stage"]["dueAt"], str(run.get("stage")))

    # b: list and download
    files = rhea.get(f"/workspaces/{ws}/runs/{run_id}/attachments").json()
    check("b: both files are listed with their form questions", sorted(f["fieldKey"] for f in files["items"])
          == ["idDocument", "incomeProof"], str(files["items"]))
    first = next(f for f in files["items"] if f["fieldKey"] == "idDocument")
    download = rhea.get(f"/workspaces/{ws}/runs/{run_id}/attachments/{first['id']}/content")
    check("b: the download is the same bytes", download.status_code == 200 and download.content == PDF)
    check("b: the download is an attachment that is never rendered",
          download.headers.get("Content-Disposition", "").startswith("attachment")
          and download.headers.get("X-Content-Type-Options") == "nosniff"
          and "sandbox" in download.headers.get("Content-Security-Policy", "")
          and download.headers.get("Content-Type", "").startswith("application/octet-stream"), str(download.headers))

    # c: type and size checks
    for name, ctype, content in (("run.txt", "text/plain", b"MZ\x90\x00\x03\x00\x00\x00"),
                                 ("page.txt", "text/plain", b"<html><script>alert(1)</script></html>"),
                                 ("fake.png", "image/png", PDF),
                                 ("tool.exe", "application/octet-stream", b"MZ\x90\x00")):
        refused = upload(rhea, f"/workspaces/{ws}/runs/{run_id}/attachments", name, ctype, content)
        check(f"c: {name} is refused", refused.status_code == 422
              and refused.json()["fieldErrors"][0]["pointer"] == "/file", f"{refused.status_code} {refused.text[:200]}")
    big = upload(rhea, f"/workspaces/{ws}/runs/{run_id}/attachments", "big.txt", "text/plain",
                 b"a" * (10 * 1024 * 1024 + 1))
    check("c: a file over 10 MiB is refused with 413", big.status_code == 413, f"{big.status_code} {big.text[:200]}")

    # d: add later, remove
    added = upload(rhea, f"/workspaces/{ws}/runs/{run_id}/attachments", "note.txt", "text/plain", b"Signed copy",
                   {"description": "Signed copy of the contract"})
    check("d: a file is added later", added.status_code == 201 and added.json()["description"]
          == "Signed copy of the contract", f"{added.status_code} {added.text[:200]}")
    removed = rhea.s.delete(f"{BASE}/api/v1/workspaces/{ws}/runs/{run_id}/attachments/{added.json()['id']}",
                            headers=rhea._headers(), timeout=30)
    check("d: the file is removed", removed.status_code == 204, str(removed.status_code))
    listed = rhea.get(f"/workspaces/{ws}/runs/{run_id}/attachments").json()["items"]
    gone = next(f for f in listed if f["id"] == added.json()["id"])
    check("d: the metadata stays and the file is no longer downloadable", gone["removedAt"] and not gone["downloadable"])
    check("d: the removed file cannot be downloaded", rhea.get(
        f"/workspaces/{ws}/runs/{run_id}/attachments/{gone['id']}/content").status_code == 404)

    # e: outsiders
    check("e: an outsider cannot list the files", otto.get(f"/workspaces/{ws}/runs/{run_id}/attachments").status_code == 404)
    check("e: an outsider cannot download", otto.get(
        f"/workspaces/{ws}/runs/{run_id}/attachments/{first['id']}/content").status_code == 404)
    check("e: an outsider cannot upload", upload(otto, f"/workspaces/{ws}/runs/{run_id}/attachments", "x.txt",
                                                 "text/plain", b"x").status_code == 404)

    # f: the manager's approval moves the request to stage 2
    task = base.task_for(mani, ws, run_id)
    claimed = mani.post(f"/workspaces/{ws}/tasks/{task['id']}/claim", {"expectedVersion": task["version"]})
    decided = mani.post(f"/workspaces/{ws}/tasks/{task['id']}/decision", {
        "expectedVersion": claimed.json()["version"], "action": "approve"}, idem=True)
    check("f: the document check is approved", decided.status_code == 201, decided.text[:200])
    detail = rhea.get(f"/workspaces/{ws}/runs/{run_id}").json()
    check("f: the request is now in stage 2 of 3", detail["stage"]["index"] == 2 and detail["stage"]["id"] == "assessment",
          str(detail.get("stage")))
    kinds = [e["type"] + ":" + e["data"].get("stageId", "") for e in events(rhea, ws, run_id) if e["type"].startswith("STAGE")]
    check("f: the timeline shows each stage change once", kinds == ["STAGE_ENTERED:intake", "STAGE_COMPLETED:intake",
                                                                  "STAGE_ENTERED:assessment"], str(kinds))
    cancelled = rhea.post(f"/workspaces/{ws}/runs/{run_id}/cancel", {"reason": "e2e cleanup",
                                                                      "expectedVersion": detail["version"]}, idem=True)
    print("cleanup cancel:", cancelled.status_code)
    print("scenario 50 done")


if __name__ == "__main__":
    try:
        main()
    except requests.RequestException as exc:  # pragma: no cover - environment problem
        print("FAIL: stack not reachable:", exc)
        sys.exit(1)
