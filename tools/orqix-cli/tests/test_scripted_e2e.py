"""End-to-end test of the orqix CLI against a scripted Orqix server.

The server below answers the operations the CLI uses with the shapes of contracts/openapi.yaml and keeps state, so a
whole session runs: sign in with a token file, choose a workspace, import, validate and publish a workflow, start a
request, list runs and tasks, run a test file with JUnit XML (and fail on a failing case), pull and push the shared
suite, and run the package contract test with a smoke run. For a run against a real stack see e2e_live.py.
"""
from __future__ import annotations

import base64
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import uuid
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib import parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from orqix_cli import commands  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
WS = "00000000-0000-7000-8000-000000000201"
TOKEN = "header.payload.signature"


def junit(name, cases):
    failures = sum(1 for c in cases if not c[1])
    body = "".join(f'<testcase classname="orqix.workflow.{name}" name="{c[0]}" time="0"/>' if c[1] else
                   f'<testcase classname="orqix.workflow.{name}" name="{c[0]}" time="0"><failure message="x"/></testcase>'
                   for c in cases)
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuites name="orqix" tests="{len(cases)}" failures="{failures}"'
            f' errors="0" time="0"><testsuite name="{name}" tests="{len(cases)}" failures="{failures}" errors="0"'
            f' skipped="0" time="0">{body}</testsuite></testsuites>\n')


class Orqix(BaseHTTPRequestHandler):
    state: dict = {}

    def log_message(self, *args):
        pass

    def _send(self, status, body=None, headers=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/problem+json" if status >= 400 else "application/json")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _problem(self, status, code, detail):
        self._send(status, {"type": "about:blank", "title": code, "status": status, "code": code, "detail": detail})

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode()) if n else None

    def _route(self, method):
        s = Orqix.state
        url = parse.urlsplit(self.path)
        s["requests"].append({"method": method, "path": url.path, "query": url.query,
                              "auth": self.headers.get("Authorization"), "idempotency": self.headers.get("Idempotency-Key")})
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            return self._problem(401, "UNAUTHENTICATED", "Sign in.")
        p = url.path.removeprefix("/api/v1")
        q = dict(parse.parse_qsl(url.query))
        body = self._body() if method in ("POST", "PUT") else None
        wfs = s["workflows"]
        if p == "/me":
            return self._send(200, {"subject": {"id": str(uuid.uuid4()), "displayName": "Dana Designer"},
                                    "platformAdmin": False, "workspaces": [{"id": WS, "key": "purchasing", "name": "Purchasing",
                                                                            "organizationName": "Org", "isTest": False,
                                                                            "permissions": [], "disabledFeatures": []}]})
        prefix = f"/workspaces/{WS}"
        if not p.startswith(prefix):
            return self._problem(404, "NOT_FOUND", "Not found.")
        p = p[len(prefix):]
        if p == "/workflows" and method == "GET":
            items = [w["summary"] for w in wfs.values() if q.get("q", "") in w["summary"]["key"]]
            return self._send(200, {"items": items, "nextCursor": None})
        if p == "/workflows" and method == "POST":
            wid = str(uuid.uuid4())
            wfs[wid] = {"summary": {"id": wid, "key": body["key"], "name": body["name"], "draftRevision": 1,
                                    "activeRelease": None, "canStart": False, "updatedAt": "2026-11-09T10:00:00Z"},
                        "drafts": [{"schemaVersion": "0.1", "workflowKey": body["key"], "name": body["name"]}],
                        "releases": [], "suite": {"version": 0, "cases": []}}
            return self._send(201, wfs[wid]["summary"])
        parts = p.strip("/").split("/")
        if len(parts) >= 2 and parts[0] == "workflows" and parts[1] in wfs:
            wf = wfs[parts[1]]
            rest = "/".join(parts[2:])
            rev = len(wf["drafts"])
            if rest == "draft" and method == "GET":
                return self._send(200, {"workflowId": parts[1], "revision": rev, "savedAt": "2026-11-09T10:00:00Z",
                                        "savedBy": {"id": WS, "displayName": "Dana"}, "definition": wf["drafts"][-1]},
                                  {"ETag": f'"r{rev}"'})
            if rest == "draft" and method == "PUT":
                if self.headers.get("If-Match") != f'"r{rev}"':
                    return self._problem(412, "PRECONDITION_FAILED", "Reload.")
                wf["drafts"].append(body["definition"])
                wf["summary"]["draftRevision"] = rev + 1
                return self._send(200, {"workflowId": parts[1], "revision": rev + 1, "savedAt": "2026-11-09T10:00:00Z",
                                        "savedBy": {"id": WS, "displayName": "Dana"}, "definition": body["definition"]})
            if rest == "validate":
                definition = wf["drafts"][body["revision"] - 1]
                blockers = [] if definition.get("nodes") else [{"code": "WF_NO_START", "severity": "blocker",
                                                                 "message": "Add a start.", "path": "/nodes"}]
                return self._send(200, {"revision": body["revision"], "compilerVersion": "x", "publishable": not blockers,
                                        "semanticHash": None, "issues": blockers})
            if rest == "publish":
                if not self.headers.get("Idempotency-Key"):
                    return self._problem(400, "MALFORMED_REQUEST", "Idempotency-Key is required.")
                number = len(wf["releases"]) + 1
                release = {"id": str(uuid.uuid4()), "workflowId": parts[1], "releaseNumber": number,
                           "draftRevision": body["draftRevision"], "isActive": body.get("activate", True),
                           "definition": wf["drafts"][body["draftRevision"] - 1]}
                wf["releases"].append(release)
                wf["summary"]["activeRelease"] = {"id": release["id"], "releaseNumber": number,
                                                  "publishedAt": "2026-11-09T10:00:00Z", "draftRevision": body["draftRevision"]}
                return self._send(201, release)
            if parts[2:3] == ["releases"] and method == "GET":
                return self._send(200, next(r for r in wf["releases"] if r["id"] == parts[3]))
            if rest == "test-suite" and method == "GET":
                return self._send(200, wf["suite"])
            if rest == "test-suite" and method == "PUT":
                if body["expectedVersion"] != wf["suite"]["version"]:
                    return self._problem(409, "WORKFLOW_VERSION_CONFLICT", "Reload.")
                if any("studio" in c for c in body["cases"]):
                    return self._problem(422, "VALIDATION_FAILED", "Unknown member studio.")
                wf["suite"] = {"version": wf["suite"]["version"] + 1, "cases": body["cases"],
                               **({"coverageMinimum": body["coverageMinimum"]} if "coverageMinimum" in body else {})}
                return self._send(200, wf["suite"])
            if rest == "test-runs":
                shared = "suite" not in body
                cases = wf["suite"]["cases"] if shared else body["suite"]["cases"]
                if body.get("suite", {}).get("workflowKey") not in (None, wf["summary"]["key"]):
                    return self._problem(422, "VALIDATION_FAILED", "The test suite is for another workflow.")
                results = [{"id": c["id"], "name": c["name"], "status": "finished", "passed": c["expect"] == "Approved",
                            "failures": [] if c["expect"] == "Approved" else [
                                {"kind": "outcome", "label": "Outcome", "passed": False,
                                 "expected": c["expect"], "actual": "Approved"}]} for c in cases]
                passed = sum(r["passed"] for r in results)
                key = wf["summary"]["key"]
                return self._send(200, {
                    "workflowId": parts[1], "workflowKey": key,
                    "source": {"revision": None, "releaseNumber": 1} if body.get("useActiveRelease")
                    else {"revision": body.get("revision") or rev, "releaseNumber": None},
                    "suite": "shared" if shared else "file", "suiteVersion": wf["suite"]["version"] if shared else None,
                    "recorded": shared and not body.get("useActiveRelease"), "passed": passed == len(results) and bool(results),
                    "casesPassed": passed == len(results), "total": len(results), "passedCount": passed,
                    "failedCount": len(results) - passed, "invalidCount": 0, "results": results,
                    "coverage": {"cases": len(results), "summary": "Together the cases cover 3 of 4 steps."},
                    "coverageMinimum": None, "coverageShortfalls": [],
                    "junitXml": junit(key, [(r["name"], r["passed"]) for r in results])})
        if p == "/runs" and method == "POST":
            if not self.headers.get("Idempotency-Key"):
                return self._problem(400, "MALFORMED_REQUEST", "Idempotency-Key is required.")
            run = {"id": str(uuid.uuid4()), "reference": f"ORQ-{1000 + len(s['runs'])}", "lifecycle": "running",
                   "workflow": {"id": body["workflowId"], "key": wfs[body["workflowId"]]["summary"]["key"], "name": "x"},
                   "createdAt": "2026-11-09T10:00:00Z", "input": body["input"]}
            s["runs"].append(run)
            return self._send(201, run)
        if p == "/runs" and method == "GET":
            return self._send(200, {"items": s["runs"], "nextCursor": None})
        if p == "/tasks" and method == "GET":
            return self._send(200, {"items": [{"id": "t1", "runReference": "ORQ-1000", "label": "Manager review",
                                               "status": "open", "assignee": None, "workflowName": "Purchase"}],
                                    "nextCursor": None, "counts": {}})
        if p == "/connector-packages/contract-tests":
            archive = base64.b64decode(body["archive"])
            s["archives"].append(archive)
            return self._send(200, {"packageId": "com.orqix.servicenow-itsm", "version": "1.0.0", "sha256": "0" * 64,
                                    "signatureStatus": "unsigned", "passed": True, "fixtures": 5, "fixturesPassed": 5,
                                    "results": [], "problems": [],
                                    "junitXml": junit("com.orqix.servicenow-itsm@1.0.0", [("Package checks", True)])})
        if p.startswith("/connections/") and p.endswith("/test"):
            s["smoke"].append(body)
            ok = body["operation"] != "getIncident"
            return self._send(200, {"outcome": "succeeded" if ok else "failed", "httpStatus": 200 if ok else 404,
                                    "durationMs": 3, "errorCode": None if ok else "UNEXPECTED_STATUS", "detail": None})
        if p.startswith("/connections/"):
            return self._send(200, {"key": p.split("/")[2], "definitions": [{"version": 1}, {"version": 2}], "binding": None})
        return self._problem(404, "NOT_FOUND", "Not found.")

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_PUT(self):
        self._route("PUT")


class ScriptedEndToEndTest(unittest.TestCase):

    def setUp(self):
        Orqix.state = {"workflows": {}, "runs": [], "requests": [], "archives": [], "smoke": []}
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Orqix)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self.dir.name)
        os.environ["ORQIX_CONFIG"] = str(self.tmp / "config.json")
        (self.tmp / "token").write_text(TOKEN + "\n")
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        del os.environ["ORQIX_CONFIG"]
        self.dir.cleanup()

    def orqix(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        code = commands.main(list(argv), out=out, err=err)
        return code, out.getvalue(), err.getvalue()

    def test_a_whole_session(self):
        code, out, _ = self.orqix("--server", self.url, "login", "--token-file", str(self.tmp / "token"))
        self.assertEqual(code, 0, out)
        self.assertEqual(self.orqix("workspace", "use", "purchasing")[0], 0)
        self.assertIn("* purchasing", self.orqix("workspace", "list")[1])

        definition = {"schemaVersion": "0.1", "workflowKey": "purchase-cli", "name": "Purchase from the CLI",
                      "nodes": [{"id": "start", "type": "start"}]}
        path = self.tmp / "purchase-cli.json"
        path.write_text(json.dumps(definition))
        code, out, _ = self.orqix("workflow", "import", str(path))
        self.assertEqual(code, 0)
        self.assertIn("Created the workflow purchase-cli.", out)
        self.assertIn("draft revision 2", out)
        code, out, _ = self.orqix("workflow", "import", str(path))
        self.assertIn("already matches", out, "an unchanged file saves nothing")

        broken = self.tmp / "broken.json"
        broken.write_text(json.dumps({**definition, "nodes": []}))
        code, out, _ = self.orqix("workflow", "validate", str(broken))
        self.assertEqual(code, 1, out)
        self.assertIn("WF_NO_START", out)

        code, out, _ = self.orqix("workflow", "publish", str(path), "--note", "From CI")
        self.assertEqual(code, 0, out)
        self.assertIn("as release 1 (active)", out)
        publish = [r for r in Orqix.state["requests"] if r["path"].endswith("/publish")][0]
        self.assertEqual(len(publish["idempotency"]), 40, "a stable key per workflow and revision")

        code, out, _ = self.orqix("workflow", "export", "purchase-cli", "--release", "-o", str(self.tmp / "out.json"))
        self.assertEqual(code, 0)
        self.assertEqual(json.loads((self.tmp / "out.json").read_text())["nodes"], definition["nodes"])

        code, out, _ = self.orqix("run", "start", "purchase-cli", "--set", "amount=1800", "--business-key", "PO-1")
        self.assertEqual(code, 0)
        self.assertIn("Started ORQ-1000", out)
        self.assertEqual(Orqix.state["runs"][0]["input"], {"amount": 1800})
        self.assertIn("ORQ-1000", self.orqix("run", "list", "--workflow", "purchase-cli")[1])
        self.assertIn("Manager review", self.orqix("task", "list")[1])

        suite = {"schemaVersion": "orqix.workflow-tests/v1", "workflowKey": "purchase-cli", "cases": [
            {"id": "ok", "name": "Small", "input": {"amount": 1}, "expect": "Approved", "studio": {"values": {}}}]}
        suite_path = self.tmp / "purchase-cli.tests.json"
        suite_path.write_text(json.dumps(suite))
        code, out, _ = self.orqix("test", "run", str(suite_path), "--junit", str(self.tmp / "junit.xml"))
        self.assertEqual(code, 0, out)
        self.assertIn("1 of 1 passed", out)
        root = ET.parse(self.tmp / "junit.xml").getroot()
        self.assertEqual(root.tag, "testsuites")
        self.assertEqual(root.get("failures"), "0")

        failing = dict(suite, cases=suite["cases"] + [{"id": "bad", "name": "Wrong", "input": {}, "expect": "Rejected"}])
        failing_path = self.tmp / "failing.tests.json"
        failing_path.write_text(json.dumps(failing))
        code, out, _ = self.orqix("test", "run", str(suite_path), str(failing_path), "--junit", str(self.tmp / "all.xml"))
        self.assertEqual(code, 1, "a failing case fails the command")
        self.assertIn("Outcome: expected Rejected, got Approved", out)
        merged = ET.parse(self.tmp / "all.xml").getroot()
        self.assertEqual((merged.get("tests"), merged.get("failures")), ("3", "1"))

        code, out, _ = self.orqix("test", "push", str(suite_path))
        self.assertEqual(code, 0, out)
        self.assertIn("suite version 1", out)
        code, out, _ = self.orqix("test", "pull", "purchase-cli", "-o", str(self.tmp / "pulled.json"))
        pulled = json.loads((self.tmp / "pulled.json").read_text())
        self.assertEqual(pulled["schemaVersion"], "orqix.workflow-tests/v1")
        self.assertNotIn("studio", pulled["cases"][0])
        code, out, _ = self.orqix("test", "run", "--shared", "--workflow", "purchase-cli")
        self.assertEqual(code, 0, out)
        self.assertIn("recorded for publication", out)

        code, out, _ = self.orqix("package", "test", str(REPO / "examples/packages/servicenow-itsm"), "--smoke",
                                  "--junit", str(self.tmp / "pkg.xml"))
        self.assertEqual(code, 1, "the failing smoke call fails the command")
        self.assertIn("5 of 5 fixtures passed", out)
        self.assertIn("FAIL  smoke servicenow-itsm.getIncident", out)
        self.assertTrue(Orqix.state["archives"][0].startswith(b"PK"))
        self.assertTrue(all(b["version"] == 2 for b in Orqix.state["smoke"]), "the newest registered version")
        pkg = ET.parse(self.tmp / "pkg.xml").getroot()
        self.assertEqual(len(pkg.findall("testsuite")), 2)

        # The token travels in the header only.
        for r in Orqix.state["requests"]:
            self.assertEqual(r["auth"], "Bearer " + TOKEN)
            self.assertNotIn(TOKEN, r["query"])

    def test_refusals_map_to_exit_codes(self):
        self.orqix("--server", self.url, "login", "--token-file", str(self.tmp / "token"))
        self.orqix("workspace", "use", "purchasing")
        code, _, err = self.orqix("run", "start", "missing")
        self.assertEqual(code, 2)
        self.assertIn("No workflow with the key missing", err)
        (self.tmp / "token").write_text("wrong.token.here")
        code, _, err = self.orqix("workflow", "list")
        self.assertEqual(code, 3)
        self.assertIn("UNAUTHENTICATED", err)


if __name__ == "__main__":
    unittest.main()
