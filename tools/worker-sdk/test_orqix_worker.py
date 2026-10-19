"""Protocol tests for the worker SDK against a fake server. Run: python -m unittest tools/worker-sdk/test_orqix_worker.py"""
import json
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from orqix_worker import ApiError, CallbackTicket, Job, JobFailed, Worker  # noqa: E402


class Fake:
    """Records calls and scripts the server's answers."""

    def __init__(self):
        self.calls = []
        self.jobs = []
        self.refuse = {}  # path suffix -> (status, code)


def serve(fake):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):  # silence
            pass

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
            fake.calls.append({"path": self.path, "body": body, "key": self.headers.get("Idempotency-Key"),
                               "auth": self.headers.get("Authorization")})
            for suffix, (status, code) in fake.refuse.items():
                if self.path.endswith(suffix):
                    return self.reply(status, {"code": code, "detail": "refused"})
            if self.path.endswith("/fetch-and-lock"):
                items, fake.jobs = fake.jobs, []
                return self.reply(200, {"items": items})
            if self.path.endswith("/await-callback"):
                return self.reply(200, {"callbackId": "cb1", "deadline": "2099-01-01T00:00:00Z"})
            if self.path.endswith("/extend-lock"):
                return self.reply(200, {"leaseExpiresAt": "2099-01-01T00:00:00Z"})
            return self.reply(200, {})

        def reply(self, status, payload):
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def job_json(job_id="j1", fence=3):
    return {"id": job_id, "topic": "t", "input": {"x": 1}, "fence": fence, "leaseExpiresAt": "2099-01-01T00:00:00Z",
            "attempt": 1, "maxAttempts": 3, "runReference": "ORQ-1", "nodeId": "check"}


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.fake = Fake()
        self.server = serve(self.fake)
        base = f"http://127.0.0.1:{self.server.server_address[1]}/api/v1"
        self.worker = Worker(base, "ws1", token=lambda: "tok", worker_id="w1", lease_seconds=1, poll_seconds=0.05)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def paths(self):
        return [c["path"].split("/workspaces/ws1")[1] for c in self.fake.calls]

    def test_completes_with_the_fence_and_a_stable_idempotency_key(self):
        self.fake.jobs = [job_json()]
        done = self.worker.run({"t": lambda job: {"ok": job.input["x"] == 1}}, once=True)
        self.assertEqual(done, 1)
        complete = next(c for c in self.fake.calls if c["path"].endswith("/complete"))
        self.assertEqual(complete["body"], {"workerId": "w1", "fence": 3, "result": {"ok": True}})
        self.assertEqual(complete["key"], "j1:3:complete")
        self.assertEqual(complete["auth"], "Bearer tok")

    def test_callback_ticket_survives_a_gateway_restart_and_keys_are_stable(self):
        from dataclasses import asdict

        self.fake.jobs = [job_json()]
        job = self.worker.fetch_and_lock(["t"])[0]
        ticket = self.worker.await_callback(job, 7200)
        restored = CallbackTicket(**json.loads(json.dumps(asdict(ticket))))
        restarted = Worker(self.worker._base, "ws1", token=lambda: "renewed", worker_id="restarted")
        restarted.complete_callback(restored, {"ok": True})
        restarted.complete_callback(restored, {"ok": True})
        registered = self.fake.calls[1]
        self.assertEqual(registered["body"], {"workerId": "w1", "fence": 3, "timeoutSeconds": 7200})
        self.assertEqual(registered["key"], "j1:3:await-callback")
        for delivered in self.fake.calls[2:]:
            self.assertTrue(delivered["path"].endswith("/j1/callbacks/cb1/complete"))
            self.assertEqual(delivered["key"], "cb1:complete")
            self.assertEqual(delivered["auth"], "Bearer renewed")
            self.assertEqual(delivered["body"], {"result": {"ok": True}})
        restarted.fail_callback(restored, "Provider failed")
        self.assertEqual(self.fake.calls[-1]["body"], {"message": "Provider failed"})
        self.assertEqual(self.fake.calls[-1]["key"], "cb1:fail")

    def test_a_failed_job_reports_the_message_and_retry_flag(self):
        self.fake.jobs = [job_json()]

        def handler(job):
            raise JobFailed("Address service rejected the postcode", retryable=False)

        self.assertEqual(self.worker.run({"t": handler}, once=True), 0)
        fail = next(c for c in self.fake.calls if c["path"].endswith("/fail"))
        self.assertFalse(fail["body"]["retryable"])
        self.assertEqual(fail["body"]["message"], "Address service rejected the postcode")

    def test_an_unexpected_error_reports_only_its_class_name(self):
        self.fake.jobs = [job_json()]

        def handler(job):
            raise RuntimeError("password=hunter2 failed")

        self.worker.run({"t": handler}, once=True)
        fail = next(c for c in self.fake.calls if c["path"].endswith("/fail"))
        self.assertEqual(fail["body"]["message"], "The worker raised RuntimeError")
        self.assertNotIn("hunter2", json.dumps(self.fake.calls))

    def test_a_lost_lease_is_dropped_not_raised(self):
        self.fake.jobs = [job_json()]
        self.fake.refuse["/complete"] = (409, "JOB_LEASE_LOST")
        self.assertEqual(self.worker.run({"t": lambda job: {}}, once=True), 0)

    def test_a_slow_handler_keeps_its_lease_alive(self):
        self.fake.jobs = [job_json()]

        def slow(job):
            time.sleep(2.3)
            return {}

        self.worker.run({"t": slow}, once=True)
        self.assertGreaterEqual(sum(1 for p in self.paths() if p.endswith("/extend-lock")), 1)
        extend = next(c for c in self.fake.calls if c["path"].endswith("/extend-lock"))
        self.assertEqual(extend["body"]["fence"], 3)

    def test_authorization_errors_stop_the_worker(self):
        self.fake.refuse["/fetch-and-lock"] = (403, "FORBIDDEN")
        with self.assertRaises(ApiError) as caught:
            self.worker.run({"t": lambda job: {}}, once=True)
        self.assertEqual(caught.exception.status, 403)


if __name__ == "__main__":
    unittest.main()
