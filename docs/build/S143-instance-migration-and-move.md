# S143 - Instance migration with a step mapping, and guarded moves (CG-O02, CG-O03)

Wave D of [doc 29](../29-camunda-gap-implementation-plan.md) section 9.6. Builds on [S37](S37-suspend-resume.md) (suspend), [S38](S38-release-migration.md) (single-wait release migration), [S49](S49-run-repair.md)/[S72](S72-repair-waits-and-scan.md) (repair), [S129](S129-check-until-and-waits.md) (checks), [S138](S138-loops-and-for-each.md) (loops) and [S139](S139-waits-reminders-schedules.md) (wait for the first). S38's `POST /runs/{id}/migrate` and its rules are unchanged; everything here is a separate operation with its own, documented rules.

## CG-O02 - Migration with a step mapping

| Operation (tag `MigrationAndMove`, all `run.operate`) | Rule |
|---|---|
| `POST /runs/{id}/migration-plan` | Dry run: `{targetReleaseId, mappings[]}` returns the per-request plan (each waiting step `keep` or `rename`, data copies), every refusal finding, `ready`/`hold` and `suggestedMappings`. Writes nothing (not audited, like `validateDraft`). |
| `POST /runs/{id}/migrate-with-mapping` | `Idempotency-Key`, `targetReleaseId`, `mappings`, `reason`, `expectedVersion`. 409 `RUN_NOT_SUSPENDED` when not ready, 409 `RUN_NOT_MIGRATABLE` with `findings`, plus the engine's own validation with the mappings. Timeline `RUN_MIGRATED` (with `mapped`, `dataCopies`, `heldBy`), audit `run.migrated` (`fromRelease`, `toRelease`, `mapped` count), one notice to the initiator. |
| `POST /migration-batches/preview` | The plan of each listed request, or of the newest 100 active top-level requests on the source release (`truncated`). |
| `POST /migration-batches` | `Idempotency-Key`, source and target release, `mappings`, `runIds` (1-100), `reason`, `suspendDuring`, `expectedCount` (must equal the list, else 422). Every listed request must exist and follow the source release (422 otherwise). Audit `run.migration-batch.started`/`.completed` (counts only). |
| `GET /migration-batches/{id}` | The batch with each request's result (`migrated`, `refused` with findings, `failed`). |

A **mapping** `{from, to}` says that the request at step `from` (or the data step `from` produced) continues as step `to` of the target. Steps without a mapping keep their id. `suggestedMappings` pairs a waiting or finished step the target no longer has with the one new target step of the same kind and label (or, failing that, the same settings).

**Ready** (nothing can change under the request): it is suspended (S37), or every step it waits at is a child request still running and its engine instance has no job (`hold: childRequests`; child completion takes the parent's run lock, which the migration holds). Requests needing attention, child requests themselves and ended requests are not ready.

### Rules ([MigrationRules](../../backend/app/src/main/java/com/orqix/runtime/internal/MigrationRules.java), [StepGraph](../../backend/app/src/main/java/com/orqix/runtime/internal/StepGraph.java))

| Finding | Refused when |
|---|---|
| `MAPPING_INVALID`, `MAPPING_MERGE` | A mapping names an unknown step, a step the request never reached, a step twice; or two waiting steps would continue as one. |
| `STEP_KIND`, `STEP_MISSING`, `STEP_CHANGED`, `POLL_CHANGED`, `MULTI_INSTANCE` | Waiting steps can be approvals, waits, message and signal waits, "wait for the first", worker steps, checks (S129) and child-request steps. Each must exist in the target with the same kind and **identical settings** (its decision table and connection version too, as in S38). Repeated steps (several approvers, per item, for each) are refused. |
| `RENAME_UNSUPPORTED` | Only an approval may continue under another id: timers, message subscriptions, callback tokens, check rows and child links are bound to their element id. |
| `EFFECT_IN_FLIGHT` | A call of the waiting step is `pending`, `inFlight` or `unknown` in the effect journal, a worker holds its job, or a check is running. |
| `LOOP_IN_PROGRESS` | A waiting step is inside a bounded loop (S138) and the loops differ or the step is renamed; or the request already took a loop path and the loops differ. |
| `PARALLEL_REGION`, `INCLUSIVE_REGION` | The request is inside a parallel section that differs in the target (steps, settings, routes, incoming routes of the join), or a step inside it is renamed; inclusive sections are refused. Several branches move together when their section is identical. |
| `JOIN_UNREACHABLE` | A parallel join ahead in the target whose split the request has neither passed nor will pass: it would wait forever. |
| `DATA_MISSING` | A step ahead (its settings, the conditions of its routes, required request outputs at an end) reads `/steps/<id>/…` or `steps.<id>.` data that the request does not have and no step before it in the target produces. A mapping of a finished step copies its data (`orqix_step_<from>` → `orqix_step_<to>`). |
| `SUBPROCESSES`, `STAGES_CHANGED`, `TIER1_PINNED_RELEASE`, `INPUT_INVALID`, `SAME_RELEASE` | As in S38. |

The **history may differ**, unlike S38 (`HISTORY_CHANGED` is not a finding here): the data check replaces it. What it does not prove is business equivalence of the paths not travelled.

### What happens

One transaction under the run lock: Flowable migration with `ActivityMigrationMapping` for renamed steps and the data copies as process variables; `workflow_run.release_id` changes; kept steps take the target's label (S38); a renamed approval keeps its open task under the new step id (Flowable migrates a user task directly, so the task id and claim are kept; if the engine opened a new task instead, the old one is closed); the version increments. `wait_state` follows `current_steps` as before. The request stays suspended.

**Batch** (self-contained; the CG-O01 bulk framework is not on this branch): items run one after another, each through `migrate-with-mapping` with a key derived from batch and request, so every request is locked, checked, audited and timelined as if moved alone. With `suspendDuring` a running request is suspended for its move and resumed afterwards, also when it is refused (a request held by child requests is not suspended). The batch runs inside the call (cap 100); the response is the finished batch.

## CG-O03 - Guarded move

| Operation | Rule |
|---|---|
| `POST /runs/{id}/move-plan` | Dry run of `{kind: move|skip|rerun, fromStepId, toStepId}`: the classified kind, steps passed over (`notRun`) or run again (`runsAgain`, `reuseRecorded`, `wouldRepeat`), findings, `approvalRequired` and why. |
| `POST /runs/{id}/moves` | `Idempotency-Key`, kind, steps, `reason`, `expectedVersion`. 409 `RUN_NOT_MOVABLE` with findings. With four eyes the move is stored `pendingApproval` (24 h); otherwise it runs at once (`executed`). One pending move per request. |
| `GET /runs/{id}/moves` | Newest 50. |
| `POST /runs/{id}/moves/{moveId}/approve` | Another operator (403 `MOVE_SAME_OPERATOR` for the requester). Re-planned under the run lock: if the request version differs from the planned one, or the plan no longer allows it, the move becomes `stale` (expired after 24 h) and nothing runs; the answer is the move. |
| `POST /runs/{id}/moves/{moveId}/reject` | `rejected`, or `withdrawn` when the requester does it. 409 `MOVE_NOT_PENDING` for a move that is no longer pending. |

**Four eyes** ([RunFacts.fourEyesReason](../../backend/app/src/main/java/com/orqix/runtime/internal/RunFacts.java)): the workspace stage is `production` (an explicit `workspace_environment` row, or the default for a workspace not flagged as test, PL02), or the AI workspace policy is `regulated` (D-CG3). Conservative default: a workspace without an environment row is production.

### Rules ([MoveRules](../../backend/app/src/main/java/com/orqix/runtime/internal/MoveRules.java))

- The request is at exactly one step (`STEP_COUNT`), the `from` step (`FROM_NOT_CURRENT`); not suspended, no running child request, not inside a loop body, a parallel section or a workflow with sections/handlers, no repeated step at either end.
- **From** an approval, a timer wait, or a call (`http`, `sendAdapter`) that needs attention (`MOVE_FROM_KIND`); never from a call whose outcome is in doubt (`EFFECT_IN_FLIGHT`: reconcile first). Message waits are not moved away from (their subscription, safety-net check and callback token would need closing; not built).
- **To** an approval, wait, call, adapter send, decision, calculation, branch or notice that the engine can enter directly (`MOVE_TARGET_KIND`).
- **Kind** follows from the graph (`MOVE_KIND_MISMATCH` otherwise): `rerun` when the target is the current step or comes before it, `skip` when it directly follows, `move` otherwise.
- **Inputs** (`DATA_MISSING`): the target and every step after it must find the step data they read, already produced or produced before them on the way. Skipped steps produce nothing.
- **Effects**: on a re-run, calls and adapter sends that ran are answered from the effect journal (same effect key: run, step, visit), so nothing is sent again (`reuseRecorded`); a notice, document, child request, worker job, AI task, signal or compensation that ran is refused (`EFFECT_WOULD_REPEAT`).

### What happens

Under the run lock: Orqix closes what it kept open for the step it leaves (its task is cancelled, the step leaves `current_steps`, a request needing attention runs again), then Flowable's change-state API moves the step's executions by id (`moveExecutionsToSingleActivityId`; a call parked on a dead-letter job is included, attached boundary events go with their activity); the engine listeners project the new step. Timeline `RUN_MOVE_REQUESTED`, `RUN_MOVED` (kind, steps, re-run and reused steps, requester and approver), `RUN_MOVE_REJECTED`, `RUN_MOVE_LAPSED`; audit `run.move-requested`, `run.moved` (`kind`, `fourEyes`, counts), `run.move-rejected`; one notice to the initiator. The **evidence pack** lists the three audit actions under guarded corrections.

## Operator UI

Run detail → Operator actions: **Move with step mapping** (version, a mapping row per waiting or suggested step, Check plan, Move this request; a batch preview of the version's active requests and Move N requests) and **Move to another step** (skip / run again / move, from and to steps, Check plan with the effect handling of each step, Ask a second operator or Move request; pending moves with Approve and run, Reject or Withdraw). Timeline texts for the new events.

## Decisions taken where doc 29 is silent

- A separate operation instead of loosening S38: S38 keeps its guarantees for clients that rely on them.
- Rename only approvals; every other waiting step keeps its id. Settings of waiting steps must be identical (no recomputed deadlines or groups).
- Readiness: suspended, or held only by running child requests. A child request itself is never migrated on its own (it follows its parent's release choice, S12).
- Moves refuse running requests in parallel sections, loops and sections/handlers rather than reasoning about partial scopes.
- Four eyes counts a workspace without an environment row as production; pending moves lapse after 24 hours.
- The batch is synchronous and capped at 100, reusing the single migration per item; CG-O01 can drive it later.

## Merge notes

- Flyway: `V360__run_migration_batches_and_moves.sql` only (new tables `run_migration_batch`, `run_migration_batch_item`, `run_move`). No shared CHECK constraint or enum rebuilt.
- OpenAPI: the shared `MigrationFinding.code` enum gains codes (also the existing S38/S129/S138 codes `POLL_CHANGED`, `LOOP_CHANGED`, `TIER1_PINNED_RELEASE`, which were missing); `Problem.code` gains `RUN_NOT_MOVABLE`, `MOVE_NOT_PENDING`, `MOVE_SAME_OPERATOR`; new tag `MigrationAndMove`. Regenerate `frontend/src/api/schema.d.ts` after merging.
- `AuditWriter` whitelist: `run.migrated` gains `mapped`; five new actions. `RunEvidencePack.CORRECTIONS` gains three actions. `tools/contract-check` `AUDIT_EXEMPT_OPERATIONS` and `SecurityMatrixTest` gain the three dry runs.
- Test users: `opal.operator` (second purchasing operator) added to `TestUsers`.
- `ProblemCode` and `frontend/src/lib/problemMessages.ts` gain the three codes.
- Redocly lint: two pre-existing S139 inline descriptions with commas (`WorkingCalendarContent.zone`, `ScheduleRequest.input`) are quoted here; the lint then passes (71 warnings, as before).
- Frontend: `studio/model/notation.test.ts` ("maps every step kind to a shape") fails on this branch with 22 kinds against 21 expected; it comes from the Wave C merge and touches nothing here.

## Tests

- `MigrationAndMoveRulesTest` (9, pure): rename through a mapping and suggestion, waits keep ids, invalid mappings, history with missing/copied/ahead data, parallel branches, merge, unreachable join, effects in flight, loops; move kinds, inputs, journal reuse vs repeated notice, calls in doubt, parallel and several steps.
- `RunMigrationWithMappingTest` (5, PostgreSQL + Flowable through the API): renamed approval carried over (same task) and completed on release 2, access/stale/unknown target/replay; changed history refused with `DATA_MISSING` and an unread upstream step accepted where S38 says `HISTORY_CHANGED`; two parallel branches moved and the new step after the join reached; a parent held by its child request moved without suspension; a batch preview and run with two moved, one refused, suspension released, replay, audit.
- `RunMoveTest` (4): skip, re-run and refusals outside production with replay and audit; re-run of a call answered from the journal (one POST in total) and a re-run that would repeat a notice refused; a failed call skipped and a call in doubt refused; production four eyes (self-approval refused, second operator runs it, stale after a change, withdraw, reject, not pending).
- `SecurityMatrixTest` covers the ten operations for foreign principals.
- Frontend: `MigrationAndMoveDialogs.test.tsx` (3).

## Not covered

- Renaming waits, message waits, checks, callbacks or child steps; changing a waiting step's settings; inclusive sections; sections and handlers; migration of a child request on its own.
- Moves inside parallel sections or loops, adding or cancelling single tokens, moves from message waits, and moves to message waits, worker, AI, document or child steps.
- Scheduled or automatic migration on publish, background batches beyond 100, and mock-mode handlers for the new endpoints (the dialogs are tested with request-level mocks).
- Equivalence is structural; nothing proves that the business meaning of two releases matches.
