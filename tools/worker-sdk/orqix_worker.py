"""Orqix external-worker SDK (docs/build/S64-external-workers.md).

A worker takes jobs for the topics it serves, does the work in its own system, and reports the result. The server
leases a job to one worker at a time and numbers each lease with a *fence*; every later call must present the fence,
so a worker that was too slow (its lease expired and another worker took over) can never overwrite the newer lease.

Standard library only. Typical use::

    from orqix_worker import Worker

    def check_address(job):
        # job.input is the mapped request data; return the declared result fields
        return {"valid": job.input["postcode"].startswith("W"), "score": 0.9}

    Worker("https://orqix.example/api/v1", workspace_id, token=lambda: get_token(),
           worker_id="address-checker-1").run({"address-check": check_address})

A handler that raises :class:`JobFailed` reports a failure (retryable by default); any other exception is reported as
a retryable failure with the exception's class name only, never its message, so internal details are not leaked to
the workflow's timeline.
"""
from __future__ import annotations

import json
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from urllib import error, request

__all__ = ["Worker", "Job", "JobFailed", "ApiError"]


class ApiError(Exception):
    """The server refused a call. ``code`` is the problem code, e.g. ``JOB_LEASE_LOST``."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(f"{status} {code}: {detail}")
        self.status = status
        self.code = code
        self.detail = detail


class JobFailed(Exception):
    """Raised by a handler to report that the job failed. ``retryable=False`` fails the job for good."""

    def __init__(self, message: str, retryable: bool = True, retry_after_seconds: Optional[int] = None):
        super().__init__(message)
        self.message = message
        self.retryable = retryable
        self.retry_after_seconds = retry_after_seconds


@dataclass
class Job:
    id: str
    topic: str
    input: Dict[str, Any]
    fence: int
    lease_expires_at: str
    attempt: int
    max_attempts: int
    run_reference: str
    node_id: str
    raw: Dict[str, Any] = field(default_factory=dict, repr=False)


Handler = Callable[[Job], Dict[str, Any]]


class Worker:
    def __init__(self, base_url: str, workspace_id: str, token: Callable[[], str], worker_id: str,
                 lease_seconds: int = 120, max_jobs: int = 5, poll_seconds: float = 2.0, timeout: float = 30.0):
        self._base = base_url.rstrip("/")
        self._ws = workspace_id
        self._token = token
        self.worker_id = worker_id
        self.lease_seconds = lease_seconds
        self.max_jobs = max_jobs
        self.poll_seconds = poll_seconds
        self._timeout = timeout
        self._stop = threading.Event()

    # ------------------------------------------------------------------ protocol calls

    def _call(self, method: str, path: str, body: Optional[dict] = None, idempotency_key: Optional[str] = None) -> Any:
        headers = {"Authorization": "Bearer " + self._token(), "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        req = request.Request(f"{self._base}/workspaces/{self._ws}{path}", data=data, headers=headers, method=method)
        try:
            with request.urlopen(req, timeout=self._timeout) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except error.HTTPError as e:
            raw = e.read()
            try:
                problem = json.loads(raw)
            except ValueError:
                problem = {}
            raise ApiError(e.code, problem.get("code", "HTTP_ERROR"), problem.get("detail", e.reason)) from None

    def fetch_and_lock(self, topics: List[str]) -> List[Job]:
        body = {"workerId": self.worker_id, "topics": topics, "maxJobs": self.max_jobs,
                "leaseSeconds": self.lease_seconds}
        result = self._call("POST", "/external-jobs/fetch-and-lock", body)
        return [Job(id=j["id"], topic=j["topic"], input=j.get("input") or {}, fence=j["fence"],
                    lease_expires_at=j["leaseExpiresAt"], attempt=j["attempt"], max_attempts=j["maxAttempts"],
                    run_reference=j.get("runReference", ""), node_id=j.get("nodeId", ""), raw=j)
                for j in result.get("items", [])]

    def extend_lock(self, job: Job, seconds: Optional[int] = None) -> str:
        result = self._call("POST", f"/external-jobs/{job.id}/extend-lock", {
            "workerId": self.worker_id, "fence": job.fence, "leaseSeconds": seconds or self.lease_seconds})
        job.lease_expires_at = result["leaseExpiresAt"]
        return job.lease_expires_at

    def complete(self, job: Job, result: Dict[str, Any]) -> None:
        # One key per job and fence: a retried call after a lost response cannot complete the job twice.
        self._call("POST", f"/external-jobs/{job.id}/complete",
                   {"workerId": self.worker_id, "fence": job.fence, "result": result},
                   idempotency_key=f"{job.id}:{job.fence}:complete")

    def fail(self, job: Job, message: str, retryable: bool = True, retry_after_seconds: Optional[int] = None) -> None:
        body: Dict[str, Any] = {"workerId": self.worker_id, "fence": job.fence, "message": message[:500],
                                "retryable": retryable}
        if retry_after_seconds is not None:
            body["retryAfterSeconds"] = retry_after_seconds
        self._call("POST", f"/external-jobs/{job.id}/fail", body, idempotency_key=f"{job.id}:{job.fence}:fail")

    # ------------------------------------------------------------------ running handlers

    def handle(self, job: Job, handler: Handler) -> bool:
        """Runs one job with automatic lease extension; returns True when the job was completed."""
        done = threading.Event()

        def keep_alive() -> None:
            # Extend at half the lease so one missed beat does not lose the job.
            interval = max(1.0, self.lease_seconds / 2)
            while not done.wait(interval):
                try:
                    self.extend_lock(job)
                except ApiError:
                    return  # the lease is gone; the final call will report it

        beat = threading.Thread(target=keep_alive, daemon=True)
        beat.start()
        try:
            try:
                result = handler(job)
            except JobFailed as failure:
                done.set()
                self.fail(job, failure.message, failure.retryable, failure.retry_after_seconds)
                return False
            except Exception as unexpected:  # noqa: BLE001 - the class name is all the workflow gets to see
                done.set()
                self.fail(job, f"The worker raised {type(unexpected).__name__}", True)
                return False
            done.set()
            self.complete(job, result)
            return True
        finally:
            done.set()

    def run(self, handlers: Dict[str, Handler], once: bool = False) -> int:
        """Polls for the topics of ``handlers`` until :meth:`stop`; returns the number of jobs completed."""
        completed = 0
        topics = sorted(handlers)
        while not self._stop.is_set():
            try:
                jobs = self.fetch_and_lock(topics)
            except ApiError as e:
                if e.status in (401, 403):
                    raise
                jobs = []
            for job in jobs:
                try:
                    completed += 1 if self.handle(job, handlers[job.topic]) else 0
                except ApiError as e:
                    if e.code not in ("JOB_LEASE_LOST", "JOB_NOT_OPEN"):
                        raise
                    # Another worker owns the job now (or it ended); drop ours.
            if once:
                break
            if not jobs:
                self._stop.wait(self.poll_seconds)
        return completed

    def stop(self) -> None:
        self._stop.set()


def new_worker_id(prefix: str = "worker") -> str:
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


if __name__ == "__main__":  # pragma: no cover - a smoke check, not a CLI
    print(__doc__)
    time.sleep(0)
