"""Unit tests of the orqix CLI (standard library unittest; run: python -m unittest discover -s tests)."""
from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib import parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from orqix_cli import auth, commands, config, packaging  # noqa: E402

REPO = Path(__file__).resolve().parents[3]


class PackagingTest(unittest.TestCase):

    def test_a_folder_packs_like_the_server_reads_it(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "connections").mkdir()
            (root / "manifest.json").write_bytes(b'{"id": "x"}\r\n')
            (root / "connections" / "a.connection.json").write_text(json.dumps({"key": "a", "operations": {
                "getThing": {"effectClass": "readOnly"}, "makeThing": {"effectClass": "nonIdempotent"}}}))
            (root / "notes.txt").write_text("not part of the package")
            (root / "checksums.sha256").write_text("stale")
            first = packaging.load(root)
            self.assertEqual(first, packaging.load(root), "the same files give the same bytes")
            with zipfile.ZipFile(io.BytesIO(first)) as z:
                names = z.namelist()
                self.assertEqual(names, ["connections/a.connection.json", "manifest.json", "checksums.sha256"])
                self.assertEqual(z.read("manifest.json"), b'{"id": "x"}\n', "CRLF becomes LF")
                lines = z.read("checksums.sha256").decode().splitlines()
                self.assertEqual(lines[1], hashlib.sha256(b'{"id": "x"}\n').hexdigest() + "  manifest.json")
                self.assertEqual(z.getinfo("manifest.json").date_time, (2026, 1, 1, 0, 0, 0))
            self.assertEqual(packaging.read_only_operations(first), [{"connection": "a", "operation": "getThing"}])

    def test_the_packed_example_follows_the_server_rules(self):
        # The same patterns as PackageArchive (backend/app/.../integration/packages/PackageArchive.java).
        import re
        file_rule = re.compile(r"^(manifest\.json|checksums\.sha256|signature\.jws|(connections|triggers|templates/steps"
                               r"|templates/workflows|fixtures|docs)/[A-Za-z0-9][A-Za-z0-9._-]{0,80}"
                               r"(/[A-Za-z0-9][A-Za-z0-9._-]{0,80})?)$")
        line_rule = re.compile(r"^([0-9a-f]{64})  (\S+)$")
        archive = packaging.load(REPO / "examples/packages/servicenow-itsm")
        with zipfile.ZipFile(io.BytesIO(archive)) as z:
            names = z.namelist()
            self.assertTrue(all(file_rule.match(n) and ".." not in n for n in names), names)
            listed = {}
            for line in z.read("checksums.sha256").decode().split("\n"):
                if line:
                    m = line_rule.match(line)
                    self.assertIsNotNone(m, line)
                    listed[m.group(2)] = m.group(1)
            content = [n for n in names if n != "checksums.sha256"]
            self.assertEqual(sorted(listed), sorted(content))
            for n in content:
                self.assertEqual(listed[n], hashlib.sha256(z.read(n)).hexdigest())
        self.assertLess(len(archive), 5 * 1024 * 1024)

    def test_the_example_package_has_read_only_operations(self):
        archive = packaging.load(REPO / "examples/packages/servicenow-itsm")
        operations = packaging.read_only_operations(archive)
        self.assertTrue(operations)
        self.assertTrue(all(o["connection"].startswith("servicenow") for o in operations))

    def test_a_folder_without_manifest_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                packaging.load(Path(d))


class FakeIdp(BaseHTTPRequestHandler):
    """Discovery, device authorization and a token endpoint that answers pending, slow_down, then a token."""
    answers: list = []
    seen: list = []

    def log_message(self, *args):
        pass

    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        base = f"http://127.0.0.1:{self.server.server_port}/realm"
        if self.path == "/realm/.well-known/openid-configuration":
            self._send(200, {"device_authorization_endpoint": base + "/device", "token_endpoint": base + "/token"})
        else:
            self._send(404, {})

    def do_POST(self):
        form = dict(parse.parse_qsl(self.rfile.read(int(self.headers["Content-Length"])).decode()))
        FakeIdp.seen.append((self.path, form))
        if self.path == "/realm/device":
            self._send(200, {"device_code": "dev-123", "user_code": "ABCD-EFGH", "verification_uri": "http://idp/device",
                             "verification_uri_complete": "http://idp/device?user_code=ABCD-EFGH", "interval": 1,
                             "expires_in": 600})
        elif self.path == "/realm/token":
            status, body = FakeIdp.answers.pop(0)
            self._send(status, body)


class AuthTest(unittest.TestCase):

    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeIdp)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.issuer = f"http://127.0.0.1:{self.server.server_port}/realm"
        FakeIdp.seen = []

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_device_flow_waits_slows_down_and_stores_tokens(self):
        FakeIdp.answers = [(400, {"error": "authorization_pending"}), (400, {"error": "slow_down"}),
                           (200, {"access_token": "at-1", "refresh_token": "rt-1", "expires_in": 300})]
        shown, sleeps = [], []
        record = auth.device_login(self.issuer, "orqix-cli", shown.append, sleep=sleeps.append, now=lambda: 1000.0)
        self.assertEqual(record["access_token"], "at-1")
        self.assertEqual(record["expires_at"], 1300)
        self.assertIn("ABCD-EFGH", shown[1])
        self.assertEqual(sleeps, [1.0, 1.0, 6.0], "slow_down adds five seconds")
        device = [f for p, f in FakeIdp.seen if p == "/realm/device"][0]
        self.assertEqual(device["client_id"], "orqix-cli")
        token = [f for p, f in FakeIdp.seen if p == "/realm/token"][0]
        self.assertEqual(token["grant_type"], auth.DEVICE_GRANT)

    def test_a_declined_sign_in_stops(self):
        FakeIdp.answers = [(400, {"error": "access_denied"})]
        with self.assertRaises(auth.AuthError):
            auth.device_login(self.issuer, "orqix-cli", lambda s: None, sleep=lambda s: None)

    def test_an_expired_token_is_refreshed_and_saved(self):
        FakeIdp.answers = [(200, {"access_token": "at-2", "expires_in": 300})]
        settings = {"token": {"access_token": "old", "refresh_token": "rt-1", "expires_at": 10,
                              "token_endpoint": self.issuer + "/token", "client_id": "orqix-cli"}}
        saved = []
        self.assertEqual(auth.access_token(settings, saved.append, now=lambda: 100.0), "at-2")
        self.assertEqual(saved[0]["token"]["refresh_token"], "rt-1", "the refresh token is kept when not rotated")
        self.assertEqual([f["grant_type"] for p, f in FakeIdp.seen], ["refresh_token"])

    def test_token_sources(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "token"
            path.write_text("abc.def.ghi\n")
            self.assertEqual(auth.access_token({"tokenFile": str(path)}, lambda s: None), "abc.def.ghi")
            path.write_text("two words")
            with self.assertRaises(auth.AuthError):
                auth.access_token({"tokenFile": str(path)}, lambda s: None)
        with self.assertRaises(auth.AuthError):
            auth.access_token({}, lambda s: None)
        os.environ["ORQIX_TOKEN"] = "from-env"
        try:
            self.assertEqual(auth.access_token({}, lambda s: None), "from-env")
        finally:
            del os.environ["ORQIX_TOKEN"]


class CommandHelpersTest(unittest.TestCase):

    def test_junit_reports_merge_into_one_file(self):
        one = ('<?xml version="1.0" encoding="UTF-8"?>\n<testsuites name="orqix" tests="1" failures="0" errors="0">'
               '<testsuite name="a" tests="1" failures="0" errors="0"><testcase classname="x" name="y"/></testsuite>'
               '</testsuites>')
        two = one.replace('name="a"', 'name="b"').replace('failures="0" errors="0"><testcase',
                                                          'failures="1" errors="0"><testcase')
        merged = commands.merge_junit([one, two])
        self.assertIn('<testsuites name="orqix" tests="2" failures="1" errors="0" time="0">', merged)
        self.assertEqual(merged.count("<testsuite "), 2)
        self.assertEqual(commands.merge_junit([one]), one)

    def test_input_from_file_and_set(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "in.json"
            path.write_text(json.dumps({"title": "Laptop", "amount": 1}))
            args = commands.parser().parse_args(["run", "start", "wf", "--input", str(path), "--set", "amount=1800",
                                                 "--set", "purpose=Replacement"])
            self.assertEqual(commands._input(args), {"title": "Laptop", "amount": 1800, "purpose": "Replacement"})

    def test_usage_errors_exit_2(self):
        err = io.StringIO()
        with tempfile.TemporaryDirectory() as d:
            os.environ["ORQIX_CONFIG"] = str(Path(d) / "config.json")
            try:
                self.assertEqual(commands.main(["no-such-command"], out=io.StringIO(), err=err), commands.USAGE)
                self.assertEqual(commands.main(["workflow", "list"], out=io.StringIO(), err=err,
                                               client_factory=lambda ctx: None), commands.USAGE)
                self.assertIn("No workspace chosen", err.getvalue())
            finally:
                del os.environ["ORQIX_CONFIG"]

    def test_config_is_private_and_round_trips(self):
        with tempfile.TemporaryDirectory() as d:
            os.environ["ORQIX_CONFIG"] = str(Path(d) / "sub" / "config.json")
            try:
                config.save({"server": "http://x", "token": {"access_token": "t"}})
                self.assertEqual(config.load()["server"], "http://x")
                if os.name == "posix":
                    self.assertEqual(os.stat(config.config_path()).st_mode & 0o077, 0)
            finally:
                del os.environ["ORQIX_CONFIG"]


if __name__ == "__main__":
    unittest.main()
