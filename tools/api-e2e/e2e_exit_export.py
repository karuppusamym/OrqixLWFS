"""Workflow exit export end to end (docs/admin-guide.md "Exit export") against a Docker stack.

  1  A purchase workflow runs: one request approved (purchase order), one rejected with a reason.
  2  The workspace administrator downloads the whole workspace (with run data) and one selected workflow, streamed to
     disk; a designer is refused (403) and an outsider sees nothing (404).
  3  The archive is checked WITHOUT Orqix, with the Python standard library only: SHA256SUMS and manifest.json match
     every file; BPMN and DMN are well-formed OMG XML; CSV and JSON Lines agree; the workflow JSON validates against the
     bundled JSON Schema (when the jsonschema package is installed); no credential reference leaks.
  4  The exported workflow JSON is imported again (create, save draft, publish) and compiles.

Environment: ORQIX_BASE_URL (default http://localhost:8080), EXIT_OUT (folder for the archives; default a temp folder).
"""
import csv
import hashlib
import io
import json
import os
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from e2e_scenarios import check, claim_and_decide, find_workflow, results, start, wait_run
from orqix_client import BASE, Client

BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"
DMN_NS_PREFIX = "https://www.omg.org/spec/DMN/"


def download(client, ws, body, target):
    """POSTs the export and streams the response to `target`; returns (status, headers, seconds)."""
    t0 = time.time()
    headers = client._headers()
    headers["Accept"] = "application/zip, application/problem+json"
    with client.s.post(f"{BASE}/api/v1/workspaces/{ws}/exit-export", json=body, headers=headers, stream=True,
                       timeout=600) as r:
        if r.status_code == 200:
            with open(target, "wb") as out:
                for chunk in r.iter_content(1 << 16):
                    out.write(chunk)
        return r.status_code, r.headers, time.time() - t0


def inspect(path, expect_run_data, run_ids, workflow_key, secret_marker):
    """Reads the archive with the standard library only; returns the parsed manifest."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        check("archive is a valid ZIP (CRC of every entry)", z.testzip() is None)
        files = {n: z.read(n) for n in names}
    check("README.md first, manifest.json and SHA256SUMS last", names[0] == "README.md"
          and names[-2:] == ["manifest.json", "SHA256SUMS"], f"{names[0]} ... {names[-2:]}")
    sums = {}
    for line in files["SHA256SUMS"].decode().splitlines():
        digest, name = line.split("  ", 1)
        sums[name] = digest
    bad = [n for n, d in sums.items() if hashlib.sha256(files[n]).hexdigest() != d]
    check("SHA256SUMS matches every file", not bad and len(sums) == len(files) - 2, f"{len(sums)} files, bad={bad}")
    manifest = json.loads(files["manifest.json"])
    bad = [f["path"] for f in manifest["files"]
           if hashlib.sha256(files[f["path"]]).hexdigest() != f["sha256"] or len(files[f["path"]]) != f["bytes"]]
    check("manifest.json lists every file with SHA-256 and size", not bad and len(manifest["files"]) == len(sums))

    release_dir = f"workflows/{workflow_key}/releases/"
    bpmns = [n for n in names if n.startswith(release_dir) and n.endswith(".bpmn20.xml")]
    check("released BPMN present for the workflow", bool(bpmns), str(bpmns[:3]))
    for name in bpmns:
        root = ET.fromstring(files[name])
        check(f"{name} is BPMN 2.0 definitions with a process",
              root.tag == f"{{{BPMN_NS}}}definitions" and root.find(f"{{{BPMN_NS}}}process") is not None, root.tag)
    dmns = [n for n in names if n.startswith(release_dir) and n.endswith(".dmn")]
    for name in dmns:
        root = ET.fromstring(files[name])
        check(f"{name} is DMN definitions", root.tag.startswith("{" + DMN_NS_PREFIX) and root.tag.endswith("}definitions"),
              root.tag)
    check("DMN decision tables present", bool(dmns))

    definition = json.loads(files[f"{release_dir}1/workflow.json"])
    check("release workflow JSON carries the workflow key", definition.get("workflowKey") == workflow_key)
    try:
        import jsonschema  # optional
        schema = json.loads(files["schemas/workflow-v0.schema.json"])
        errors = list(jsonschema.Draft202012Validator(schema).iter_errors(definition))
        check("workflow JSON validates against the bundled schema", not errors, str(errors[:1]))
    except ImportError:
        print("  [SKIP] jsonschema not installed: schema validation skipped")

    runs_csv = list(csv.DictReader(io.StringIO(files["history/runs.csv"].decode("utf-8"), newline="")))
    runs_jsonl = [json.loads(line) for line in files["history/runs.jsonl"].decode().splitlines()]
    check("runs.csv and runs.jsonl have the same rows", [r["runId"] for r in runs_csv] == [r["runId"] for r in runs_jsonl],
          f"{len(runs_csv)} rows")
    exported = {r["runId"]: r for r in runs_jsonl}
    check("both new runs are in the history", all(i in exported for i in run_ids))
    check("run data present only when requested",
          all(("input" in exported[i]) == expect_run_data for i in run_ids if i in exported))
    decisions = files["history/decisions.csv"].decode()
    check("decision with its reason is exported", "Not in budget this quarter" in decisions)
    check("tasks and run events exported", files["history/tasks.jsonl"] and files["history/run-events.jsonl"])
    audit = files["audit/audit-events.jsonl"].decode()
    check("audit log contains the run starts", '"run.started"' in audit)
    leaked = [n for n, c in files.items() if secret_marker.encode() in c]
    check("no credential reference anywhere in the archive", not leaked, str(leaked[:3]))
    return manifest, files


def sql(container, statement):
    import subprocess
    done = subprocess.run(["docker", "exec", container, "psql", "-U", "postgres", "-d", "orqix", "-tAc", statement],
                          capture_output=True, text=True, check=True)
    return done.stdout


def main():
    out = Path(os.environ.get("EXIT_OUT") or tempfile.mkdtemp(prefix="orqix-exit-"))
    out.mkdir(parents=True, exist_ok=True)
    wade, dana, paul, rhea, mani, otto = (Client(u) for u in (
        "wade.admin", "dana.designer", "paul.publisher", "rhea.requester", "mani.manager", "otto.outsider"))
    ws = next(w["id"] for w in rhea.get("/me").json()["workspaces"] if w["key"] == "purchasing")
    wf = find_workflow(paul, ws, "purchase-approval")
    if not wf.get("activeRelease"):
        rev = paul.get(f"/workspaces/{ws}/workflows/{wf['id']}/draft").json()["revision"]
        paul.post(f"/workspaces/{ws}/workflows/{wf['id']}/publish", {"draftRevision": rev}, idem=True)

    print("Step 1: run the workflow (one approved, one rejected)")
    approved = start(rhea, ws, wf["id"], 900, "Exit export laptop").json()
    _, decided = claim_and_decide(mani, ws, approved["id"], "approve")
    check("approve 201", decided is not None and decided.status_code == 201)
    rejected = start(rhea, ws, wf["id"], 400, "Exit export chair").json()
    _, decided = claim_and_decide(mani, ws, rejected["id"], "reject", "Not in budget this quarter")
    check("reject 201", decided is not None and decided.status_code == 201)
    a = wait_run(rhea, ws, approved["id"], lambda x: x["lifecycle"] in ("completed", "needsAttention"))
    r = wait_run(rhea, ws, rejected["id"], lambda x: x["lifecycle"] == "completed")
    check("approved run completed", a["lifecycle"] == "completed", f"{a['lifecycle']} {a.get('outcome')}")
    check("rejected run completed", r["lifecycle"] == "completed", f"{r['lifecycle']} {r.get('outcome')}")

    print("Step 2: export")
    container = os.environ.get("EXIT_PSQL_CONTAINER")
    marker = os.environ.get("EXIT_SECRET_MARKER", "exit-e2e-credential")
    if container:  # a credential reference on the binding while exporting; restored afterwards
        sql(container, "UPDATE orqix.connection_binding SET auth_type='bearerToken', secret_ref='%s' "
                       "WHERE connection_key='procurement'" % marker)
    status, _, _ = download(dana, ws, {}, out / "denied.zip")
    check("designer gets 403", status == 403, str(status))
    status, _, _ = download(otto, ws, {}, out / "outsider.zip")
    check("outsider gets 404", status == 404, str(status))
    whole = out / "whole-workspace.zip"
    status, headers, took = download(wade, ws, {"includeRunData": True}, whole)
    check("admin export 200 application/zip", status == 200 and headers.get("Content-Type", "").startswith("application/zip"),
          f"{status} {headers.get('Content-Type')}")
    check("streamed (no Content-Length, chunked)", "Content-Length" not in headers, headers.get("Transfer-Encoding", ""))
    print(f"  whole workspace: {whole.stat().st_size:,} bytes in {took:.1f} s -> {whole}")
    selected = out / "selected-workflow.zip"
    status, headers, took = download(wade, ws, {"workflowIds": [wf["id"]]}, selected)
    check("selected export 200", status == 200, str(status))
    print(f"  selected workflow: {selected.stat().st_size:,} bytes in {took:.1f} s -> {selected}")
    if container:
        sql(container, "UPDATE orqix.connection_binding SET auth_type='none', secret_ref=NULL "
                       "WHERE connection_key='procurement'")

    print("Step 3: inspect without Orqix")
    manifest, _ = inspect(whole, True, [approved["id"], rejected["id"]], wf["key"], marker)
    print(f"  counts: {json.dumps(manifest['counts'])}")
    sel_manifest, sel_files = inspect(selected, False, [approved["id"], rejected["id"]], wf["key"], marker)
    others = {n.split("/")[1] for n in sel_files if n.startswith("workflows/")}
    check("selected export holds only that workflow", others == {wf["key"]}, str(sorted(others)))
    digest = hashlib.sha256(whole.read_bytes()).hexdigest()
    print(f"  whole-workspace archive SHA-256 {digest}")
    if container:
        audited = sql(container, "SELECT metadata->>'sha256' FROM orqix.audit_event WHERE action='workspace.exit-exported' "
                                 "AND metadata->>'includeRunData'='true' ORDER BY occurred_at DESC LIMIT 1")
        check("audit log records the archive SHA-256", digest in audited, audited.strip())

    print("Step 4: round trip - import the exported workflow JSON again and publish")
    with zipfile.ZipFile(selected) as z:
        info = json.loads(z.read(f"workflows/{wf['key']}/workflow.json"))
        definition = json.loads(z.read(f"workflows/{wf['key']}/releases/{info['activeReleaseNumber']}/workflow.json"))
    key = "exit-rt-" + uuid.uuid4().hex[:6]
    definition["workflowKey"] = key
    created = dana.post(f"/workspaces/{ws}/workflows", {"key": key, "name": "Exit round trip " + key[-6:]})
    check("create 201", created.status_code == 201, created.text[:200])
    new_id = created.json()["id"]
    saved = dana.put(f"/workspaces/{ws}/workflows/{new_id}/draft", {"definition": definition}, if_match='"r1"')
    check("save exported JSON as draft 200", saved.status_code == 200, saved.text[:300])
    rev = saved.json()["revision"]
    published = paul.post(f"/workspaces/{ws}/workflows/{new_id}/publish",
                          {"draftRevision": rev, "acknowledgeWarnings": True}, idem=True)
    check("re-imported workflow compiles and publishes 201", published.status_code == 201, published.text[:300])

    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed. Archives in {out}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
