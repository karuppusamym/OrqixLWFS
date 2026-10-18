# S64 - External workers (EX01)

A workflow step of kind **Work by an external worker** (`externalTask`) hands a piece of work to a program or team outside Orqix and waits for the result. Workers talk to a small REST protocol; a single-file Python SDK ([`tools/worker-sdk`](../../tools/worker-sdk/orqix_worker.py)) implements it.

## The step

| Setting | Meaning |
|---|---|
| `topic` | The kind of job (lowercase words joined with dashes). Workers ask for the topics they serve. |
| `inputMapping` | Up to ten named values the worker receives, each a reference to a request field or an earlier step output. Single values only (no lists or rows), always present when the step starts (`WF_EXTERNAL_INPUT_*`). |
| `result` | Up to ten declared return values (text, number, whole number, true/false, date), each required or optional. They become the step outputs: `/steps/<id>/<name>`. |
| `leaseSeconds` | Optional, 30–3600, default 120: the longest a worker may hold a job without extending. |

Attempts and the retry delay come from the workflow settings `execution.maxAttempts` and `retryDelaySeconds`, as for calls to systems. The step has one route, **next**. A final failure is an incident for an operator (there is no *failed* route yet).

The step compiles to an asynchronous, triggerable service task. Its delegate publishes the job (row in `external_job`, V32) and the engine waits; the completion by a worker triggers it, exactly as the completion of a child workflow does.

## Worker protocol (`/api/v1/workspaces/{workspaceId}/external-jobs`)

All calls need the workspace-wide permission **`job.work`** (granted like `message.send` to a person, a machine identity or an IdP group). No role includes it by default, so operators and requesters cannot take jobs.

| Call | Behavior |
|---|---|
| `POST /fetch-and-lock` with `workerId`, `topics` (up to 10), `maxJobs` (up to 10), `leaseSeconds` | Leases available jobs of those topics to the worker. The lease length is capped by the step setting. Each lock raises the job fence and its attempt number. Jobs of suspended or ended requests are not offered. |
| `POST /{jobId}/extend-lock` with `workerId`, `fence`, `leaseSeconds` | Pushes the lease out. `409 JOB_LEASE_LOST` unless the worker and fence still match. |
| `POST /{jobId}/complete` with `workerId`, `fence`, `result`, plus `Idempotency-Key` | The result must be exactly the declared fields with the declared types (`422` otherwise, lease kept). On success the request continues with the result as the step outputs. `409 RUN_SUSPENDED` while suspended; `409 JOB_NOT_OPEN` when the job or request ended. |
| `POST /{jobId}/fail` with `workerId`, `fence`, `message`, `retryable`, `retryAfterSeconds`, plus `Idempotency-Key` | A retryable failure returns the job to the queue after the delay until the attempt limit; otherwise the request needs attention (`RETRIES_EXHAUSTED`). |

**Fencing.** A worker that was too slow loses its lease when the sweeper (every 5 seconds) returns an expired job to the queue. Whoever locks the job next gets a higher fence, and every call with an older fence is refused with `JOB_LEASE_LOST`, even from a worker with the same name. A lease that expired but was not yet recovered can still be completed by its holder, since nothing else has happened to the job.

**Recovery.** Jobs whose lease ran out are offered again until the attempt limit; the last expiry makes the request need attention. An operator **Retry** on such a request offers the job again from attempt 1. Cancelling or ending the request makes its jobs unusable (`JOB_NOT_OPEN`).

## Boundaries

- **Workspace:** the permission and every query are workspace-scoped; a caller outside the workspace sees nothing (404).
- **Topic:** a worker only receives jobs of the topics in its request. Any holder of `job.work` in the workspace may serve any topic of that workspace; there is no per-topic grant yet.
- **Payload:** only the mapped values are sent. They are copied into the job when it is published and **deleted when the job completes**; a job awaiting an operator keeps them. Results are validated and stored only as the step outputs.
- **Visibility:** failure messages from workers (at most 500 characters, control characters removed) appear in the timeline for readers with request-data access; other readers see that the job failed.
- **Audit:** `externaljob.completed` and `externaljob.failed` (topic, step, attempt). Fetch and extend are high-volume protocol calls and are not audited individually.

## Studio, runs and tests

Studio has the step in the palette with an inspector for the topic, lease, sent values and returned values. The run page shows "Waiting for an outside worker", and the timeline records publication, retries, completion and failure. Dry-run tests may supply a worker answer under `workers.<stepId>.result`; otherwise declared fields get sample values, and no worker is called.

## SDK

`orqix_worker.Worker(base_url, workspace_id, token=callable, worker_id, ...)` with `run({topic: handler})`. A handler returns the result dict, or raises `JobFailed(message, retryable=...)`. The SDK extends the lease at half its length while the handler runs, sends `Idempotency-Key = <job>:<fence>:complete|fail`, drops jobs whose lease was lost, stops on 401/403, and reports an unexpected exception as a retryable failure naming only the exception class. Protocol tests: `python -m unittest tools/worker-sdk/test_orqix_worker.py`.

## Limits (deliberate)

- No `failed` route for the step, no business outcomes beyond declared results, and no streaming or large payloads (inputs and results are small scalar values).
- No worker registry, health view or per-topic grants. Operators see jobs through the step and timeline of a request, not a job queue screen.
- The SDK is Python only. The protocol is plain REST, so other languages can implement it from this document and the OpenAPI contract.
- The browser mock does not simulate workers; mock requests wait at the step until a test completes them.

## Tests

`ExternalTaskRulesTest` (compiler: availability, typing, BPMN, simulation), `ExternalWorkerTest` (PostgreSQL + Flowable through the public API: lease, typed hand-off to a second worker step, fencing after a lost lease, result validation, retry, exhaustion and operator retry, permission, suspension), the security matrix, SDK protocol tests, Studio inspector and mock tests.
