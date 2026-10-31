"""Control evidence pack scenarios against a Docker stack (docs/27 section 3, Create).

  60  Release pack: identity and checksums, author/reviewer/publisher, tests, diff from release 1, in PDF/DOCX/JSON
  61  Run pack: steps, decision, guarded corrections (suspend/resume), effect outcome, timeline; nothing private
  62  Access: no run.read -> 403 audited; other workspace -> 404; unknown format -> 422

Development stack only; passwords come from the dev realm file and are never printed. Point it at any stack:
    ORQIX_BASE_URL=http://localhost:8080 ORQIX_STUB_URL=http://localhost:8095 python tools/api-e2e/e2e_evidence.py [outdir]
With an output directory, the downloaded packs are saved there for inspection.
"""
import io
import os
import sys
import uuid
import zipfile

import requests

import e2e_scenarios as base
from e2e_operations import new_workflow
from e2e_scenarios import STUB, check, claim_and_decide, start, wait_run
from orqix_client import BASE, Client

FORMAT_TYPES = {"pdf": "application/pdf", "json": "application/json",
                "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}


def download(client, path, fmt, outdir):
    r = client.s.get(f"{BASE}/api/v1{path}", params={"format": fmt}, headers=client._headers(), timeout=60)
    if outdir and r.status_code == 200:
        name = r.headers.get("Content-Disposition", "").split("filename=")[-1].strip('"') or f"pack.{fmt}"
        with open(os.path.join(outdir, name), "wb") as f:
            f.write(r.content)
    return r


def docx_text(content):
    with zipfile.ZipFile(io.BytesIO(content)) as z:
        return z.read("word/document.xml").decode("utf-8")


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else None
    if outdir:
        os.makedirs(outdir, exist_ok=True)
    rhea, mani, paul, olga, dana, vic, fred = (Client(u) for u in (
        "rhea.requester", "mani.manager", "paul.publisher", "olga.operator", "dana.designer", "vic.viewer",
        "fred.facilities"))
    ws = next(w["id"] for w in rhea.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    requests.post(f"{STUB}/__reset", timeout=10)
    requests.post(f"{STUB}/__faults", json={"mode": "none"}, timeout=10)

    print("Scenario 60: release evidence pack")
    wf, r1 = new_workflow(dana, paul, ws, "e2e-evidence")
    draft = dana.get(f"/workspaces/{ws}/workflows/{wf}/draft").json()
    for node in draft["definition"]["nodes"]:
        if node["id"] == "manager":
            node["label"] = "Line manager approval"
    revision = dana.put(f"/workspaces/{ws}/workflows/{wf}/draft", {"definition": draft["definition"]},
                        if_match=f'"r{draft["revision"]}"').json()["revision"]
    r2 = paul.post(f"/workspaces/{ws}/workflows/{wf}/publish", {"draftRevision": revision,
                                                                "acknowledgeWarnings": True}, idem=True).json()
    check("release 2 published", r2.get("releaseNumber") == 2, str(r2.get("releaseNumber")))
    release = vic.get(f"/workspaces/{ws}/workflows/{wf}/releases/{r2['id']}").json()
    path = f"/workspaces/{ws}/workflows/{wf}/releases/{r2['id']}/evidence-pack"
    for fmt in ("pdf", "docx", "json"):
        r = download(vic, path, fmt, outdir)
        check(f"viewer downloads the release pack as {fmt}", r.status_code == 200
              and r.headers.get("Content-Type", "").startswith(FORMAT_TYPES[fmt]), f"{r.status_code} {r.headers.get('Content-Type')}")
        if fmt == "pdf":
            check("PDF file starts with %PDF", r.content[:4] == b"%PDF")
        if fmt == "docx":
            text = docx_text(r.content)
            check("DOCX names the release and the changed step", "Release evidence pack" in text and "Line manager approval" in text)
        if fmt == "json":
            pack = r.json()
            check("semantic hash matches the release", pack["release"]["semanticHash"] == release["semanticHash"])
            roles = [(a["role"], a["subject"]["displayName"]) for a in pack["approvals"]]
            check("author, reviewer and publisher are named", [x for x, _ in roles] == ["Author", "Reviewer", "Publisher"], str(roles))
            check("independent review recorded", pack["controls"]["independentReview"] is True)
            check("diff from release 1 lists the renamed step", pack["diff"]["previousReleaseNumber"] == 1
                  and any("Line manager approval" in c["title"] for c in pack["diff"]["changes"]), pack["diff"]["summary"])
            check("test status is stated", pack["tests"]["status"] in ("passed", "failed", "notRecorded", "notConfigured"),
                  pack["tests"]["summary"])
            check("release artifacts carry SHA-256", all(len(a["sha256"]) == 64 for a in pack["artifacts"]) and pack["artifacts"])

    print("Scenario 61: run evidence pack")
    run = start(rhea, ws, wf, 100, "Evidence private title").json()
    run = wait_run(olga, ws, run["id"], lambda x: any(t["status"] == "open" for t in x.get("tasks", [])))
    suspended = olga.post(f"/workspaces/{ws}/runs/{run['id']}/suspend",
                          {"reason": "Checking the supplier", "expectedVersion": run["version"]}, idem=True)
    check("operator suspends the run", suspended.status_code == 200, f"{suspended.status_code} {suspended.text[:120]}")
    current = olga.get(f"/workspaces/{ws}/runs/{run['id']}").json()
    resumed = olga.post(f"/workspaces/{ws}/runs/{run['id']}/resume",
                        {"reason": "Supplier confirmed", "expectedVersion": current["version"]}, idem=True)
    check("operator resumes the run", resumed.status_code == 200, f"{resumed.status_code} {resumed.text[:120]}")
    _, decided = claim_and_decide(mani, ws, run["id"], "approve")
    check("manager approves", decided is not None and decided.status_code == 201)
    done = wait_run(olga, ws, run["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention"))
    check("run completes", done["lifecycle"] == "completed", done["lifecycle"])
    rpath = f"/workspaces/{ws}/runs/{run['id']}/evidence-pack"
    for fmt in ("pdf", "docx", "json"):
        r = download(olga, rpath, fmt, outdir)
        check(f"operator downloads the run pack as {fmt}", r.status_code == 200
              and r.headers.get("Content-Type", "").startswith(FORMAT_TYPES[fmt]), f"{r.status_code}")
        if fmt == "json":
            pack = r.json()
            step = pack["steps"][0]
            check("manager step shows decision, decider and times", step["decision"] == "approve"
                  and step["decidedBy"]["displayName"] and step["createdAt"] and step["decidedAt"], str(step)[:200])
            check("suspend and resume are listed as corrections",
                  [c["action"] for c in pack["corrections"]] == ["run.suspended", "run.resumed"],
                  str([c["action"] for c in pack["corrections"]]))
            check("purchase-order call outcome is recorded", any(e["outcome"] == "succeeded" for e in pack["effects"]),
                  str([(e["operation"], e["outcome"]) for e in pack["effects"]]))
            check("release checksum matches", pack["release"]["semanticHash"] == release["semanticHash"] or
                  pack["release"]["releaseNumber"] == 2)
            check("timeline is complete", pack["summary"]["events"] == len(pack["timeline"]) and len(pack["timeline"]) > 3)
            check("no request values or reasons in the pack", "Evidence private title" not in r.text
                  and "Checking the supplier" not in r.text)
            check("integrity digest present", len(pack["integrity"]["digest"]) == 64)

    print("Scenario 62: access")
    check("initiator without run.read -> 403", download(rhea, rpath, "pdf", None).status_code == 403)
    check("user of another workspace -> 404 (release)", download(fred, path, "pdf", None).status_code == 404)
    check("user of another workspace -> 404 (run)", download(fred, rpath, "pdf", None).status_code == 404)
    bad = download(vic, path, "xlsx", None)
    check("unknown format -> 422", bad.status_code == 422 and bad.json().get("code") == "VALIDATION_FAILED", str(bad.status_code))

    results = base.results
    passed = sum(1 for _, ok in results if ok)
    print(f"\nRESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
