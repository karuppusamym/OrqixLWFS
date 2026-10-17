# S46 - Consistency check and projection repair (EX09)

Builds on S31 (EX03). The watchdog only classifies a request whose projection is empty and whose engine holds no work. EX09 adds an operator-run comparison of what a request shows with what the workflow engine holds, and one repair that is provably safe.

## Classification (`GET /runs/{id}/diagnosis`, `run.operate`)

| State | Meaning | Action offered |
| --- | --- | --- |
| `inSync` | The open engine approvals match the projected steps (task ids included), or every active engine step is projected | none |
| `projectionDrift` | The engine holds open approvals (each with an open Orqix task row) that the request does not show, on a running or waiting, unsuspended request | rebuild |
| `strandedToken` | The engine has a token at an activity, but no task, job, timer, message subscription, dead-letter or suspended job, active child request or open external job can move it | cancel only |
| `emptyRequest` | No token and no work (the EX03 case) | cancel only |
| `engineMissing` | The engine has no instance | cancel only |
| `unsupported` | Differences beyond approvals, or an approval without an open task row, or a request needing attention for another reason | none |
| `protected` / `ended` | Suspended request / finished request | none |

## Repair (`POST /runs/{id}/repair`, `run.operate`)

Requires an idempotency key, `expectedVersion` and a reason. The classification is made again under the run row lock, so a request that progressed since the operator looked is refused (409 `RUN_NOT_REPAIRABLE` or a version conflict) rather than overwritten. The only change is rewriting `current_steps` from the engine's live approval tasks and their `task_context` rows, then settling the lifecycle. The engine is never triggered, skipped, migrated or modified, so no step can run twice and no outcome is invented. It appends `RUN_PROJECTION_REPAIRED` (with reason) to the timeline and audit `run.projection-repaired` (counts only). A replay with the same key returns the stored result without a second event.

A stranded token is deliberately not repaired: re-triggering a lost job or skipping the activity could repeat an external effect or fabricate completion. Operators cancel it using the existing guarded cancel.

## Operations UI

Run detail, operator section: "Check consistency" (hidden while suspended and from non-operators) opens a dialog that shows the classification and message. "Rebuild approval steps" appears only for `projectionDrift` and requires a reason; stranded, empty and missing-engine classes offer "Cancel request" instead.

## Evidence (run locally on 17 October 2026)

- `RunRepairTest` (3 cases, PostgreSQL/Flowable via the public API) passes: lost projection detected as `projectionDrift`, the watchdog leaves it alone, requester gets 403 on diagnosis and repair, stale version refused, repair restores the step with the same engine task id, audit and event written once, replay safe, engine task unchanged, repeated repair refused, the approval still completes; a lost engine task row is classified `strandedToken`, repair refused with the engine and version unchanged, operator cancel succeeds; suspended and engine-missing requests are refused.
- Full clean `verify` passes: compiler 454 cases, application 689 cases (685 pass, the same four intentional skips), coverage gate and packaging. `SecurityMatrixTest` covers `repairRun` for foreign callers.
- Frontend: 92 files, 782 tests pass; typecheck, lint and `api:check` pass. `RepairDialog.test.tsx` (3 cases, mock API with overridden responses) covers repairable vs stranded vs requester.
- Defect found by execution: audit actions are an allow-list; the first run returned 500 until `run.projection-repaired` was registered. The stranded fixture first failed on an identity-link foreign key when deleting the engine task row.

## Not covered

Rebuild covers approval steps only; wait, HTTP/document, message-wait, child and external-worker projections are reported `unsupported` or in sync, not rebuilt. Detection is on demand and is not added to the periodic watchdog (the oldest-first bounded sweep would repeatedly re-inspect healthy waiting requests). No automatic engine token repair, backup/restore or reconstruction of lost engine state. No real-Docker stack or browser (Edge/axe) run for this ticket.
