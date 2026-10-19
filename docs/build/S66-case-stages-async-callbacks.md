# S66 - Nested case stages and durable asynchronous callbacks

18 October 2026. Continued implementation under the user's instruction to complete the remaining engineering. WM04d and IN03b close the exact local scopes below. Full WM04, UI02, IN03 and IN04 acceptance remains unfinished; this checkpoint does not redefine S56.

## Executable nested case stages

The CMMN admission boundary accepts up to four nested stages within the existing 64 KiB / 30-plan-item model limit. Each stage may contain human tasks, milestones, nested stages and sentries. Entry and exit criteria reference sentries in that same stage; event sources and plan-item definitions must also be local to the stage. Sentries support human/stage `complete` and milestone `occur` events. A modeled exit cancels a human task or a stage's nested work through the engine. Literal stage `autoComplete` booleans are accepted; expressions are not.

Every definition in a stage must have exactly one local plan item. Reusing stage definitions, orphan definitions, cross-stage references, milestone exit criteria, arbitrary extensions, execution classes, scripts and XML external entities are rejected before deployment. At least one human task must be reachable through initially active stages. The original immutable workspace/version pin, group eligibility, case version lock, idempotent task decisions and operator termination remain authoritative.

`GET /cases/instances/{instanceId}/plan` reads Flowable's public historic-plan/milestone APIs after the same fresh workspace/case visibility check as case detail. It returns source element IDs, engine plan-item IDs, names/types/states, parent-stage IDs, creation/end times and achieved milestones. It returns no variables, task payloads or termination reasons. History survives successful completion or termination, subject to the existing visibility policy. Case detail loads this read-only view on demand via **Show case plan**, displays parent stages and milestone timestamps, and reloads it after a case version change.

This does not implement typed case data, manual activation/required/repetition rules, discretionary planning, event listeners, nested cases, suspension/resume, case-task workflow integration, a visual case designer or full CMMN conformance. The migration workbench remains inventory-only for CMMN conversion. Source upload through the case definition API deploys only the admitted subset.

## Durable external-agent callback handoff

An external-task gateway can register an asynchronous handoff before remote submission, then exit. The callback ticket is stored in PostgreSQL with its workspace, job, current fence, worker ID, registering subject and fixed deadline of 30-86400 seconds. Identical registration retries return the original ID/deadline. Timestamps are normalized to database microsecond precision so the original and replay responses agree.

Callback completion/failure authenticates as the registering subject with current `job.work`; knowing a callback ID grants no access. Completion validates the exact workflow-declared result fields/types, locks the job and run, advances the engine and records audit/idempotency in one transaction. Concurrent identical deliveries replay once; a changed payload conflicts. Suspended/cancelled runs, expired deadlines and superseded fences refuse new results. Original successful commands may replay after fresh authorization. Ordinary complete/fail/heartbeat cannot take over a handed-off job. Ordinary expired leases are now also rejected before sweeper recovery.

A callback failure or timeout raises operator attention without automatically returning the job to the dispatch queue. The operator must inspect the remote outcome before authorizing Retry; the next lease has a higher fence. This is not automatic remote-effect reconciliation or approved write-tool admission. A provider webhook must be verified by its gateway and translated into this authenticated protocol. The gateway must persist its ticket and use an outbox/provider idempotency mechanism to handle interrupted remote submission. The registration API itself does not contact the provider.

The Python SDK adds serializable `CallbackTicket`, `await_callback`, `complete_callback` and `fail_callback` for explicit gateway use. Its synchronous handler loop is unchanged. The run timeline explains the asynchronous wait. V85 adds the append-only registration table and must run before deployment. Existing synchronous MCP agent execution remains read-only; no extra model/A2A wire protocol or delegated OAuth flow is claimed.

## Verification

Evidence is recorded in the local ignored `evidence/` directory. This is focused local acceptance, not a new full-suite qualification or Docker deployment. Final counts and checks are recorded in the delivery tracker after verification completes.

- PostgreSQL/Flowable API tests exercise nested stages, task and stage exits, milestone-triggered work, retained history, workspace visibility and late-task rejection.
- Admission tests reject cross-scope references, dynamic stage settings, execution extensions and repeated stage instantiation.
- Worker API tests cover durable registration replay, typed results, concurrent duplicate delivery, changed intent, fixed deadlines, no timeout redispatch, operator retry/new fence, wrong callback IDs, subject ownership, fresh permission, suspension/cancellation and ordinary expired leases.
- SDK tests reconstruct a serialized callback ticket in a fresh Worker instance, deliver with renewed credentials and assert stable idempotency keys.
- Frontend tests cover lazy plan loading, parent-stage display, milestone refresh, collapse and asynchronous timeline wording.
- A real Edge journey with mock API checks termination/reload and mobile plan-history display with serious/critical axe and browser-error monitoring. The mock demonstrates presentation only; real case semantics are tested separately against PostgreSQL/Flowable.
