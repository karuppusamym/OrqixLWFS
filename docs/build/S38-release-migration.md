# S38 - Move an in-flight request to another release

Second half of EX04 (suspension is [S37](S37-suspend-resume.md)). An operator can re-point a suspended request at another published release of the same workflow when the two releases are **explicitly compatible**. Compatibility is decided from the definitions, never assumed from the engine dependency.

## Operations

| Operation | Rule |
|---|---|
| `GET /runs/{id}/migration-options` | `run.operate`. Up to the 20 newest other releases of the workflow, each with `compatible` and the `findings` that explain a refusal. `ready` is false until the request is suspended; the verdicts are shown anyway so an operator can plan. |
| `POST /runs/{id}/migrate` | `run.operate`, `Idempotency-Key`, `targetReleaseId`, `reason`, `expectedVersion`. Only while the request is **suspended** (`RUN_NOT_SUSPENDED` otherwise). Refused with `RUN_NOT_MIGRATABLE` and the full `findings` list when any rule fails or the engine's own migration validation reports a problem. The request stays suspended; the operator resumes it afterwards. |

The target may be older or newer (rollback is a migration), but never a release of another workflow.

## Compatibility rules ([ReleaseCompatibility](../../backend/app/src/main/java/com/orqix/runtime/internal/ReleaseCompatibility.java))

The request must wait at **exactly one** step, of kind `approval`, `wait` or `waitForMessage`. Then:

| Finding | Rule that failed |
|---|---|
| `STEP_COUNT`, `STEP_UNKNOWN`, `STEP_KIND` | Zero or several waiting steps, an unknown step, or a step kind that is not migratable (HTTP, child workflow, document, parallel gateway, …). |
| `STEP_MISSING`, `STEP_CHANGED` | The waiting step must exist in the target with the same kind and **identical settings** (candidate group, deadline, time limit, skills, calendar, escalation, message name and payload, wait duration…). Only its label may differ. |
| `HISTORY_CHANGED` | Every step from which the waiting step can be reached must exist in both releases with the same kind and settings, the same decision table and connection version they reference, and the same routes between them. A step added or removed upstream also fails. What lies **ahead** of the waiting step may differ freely. |
| `PARALLEL_REGION` | The request has passed a parallel split whose join it has not reached. |
| `STAGES_CHANGED` | The two releases declare different case stages. |
| `INPUT_INVALID` | The request's answers do not satisfy the target release's request form (including conditional-form rules). |

Because the travelled part and the waiting step are identical, every variable an upstream step produced, every projection (timeline, run diagram, stage progress) and the open task, timer or message subscription mean the same thing under the target release. The decision rows and connection versions of steps still ahead come from the target release, which is the point of moving.

## What happens on migrate

One transaction: the Flowable instance is re-pointed at the target process definition (executions, task, timer due date and variables are kept); `workflow_run.release_id` changes; the step and the open task take the target's label; the version increments. The timeline gets `RUN_MIGRATED` (from/to version, reason), audit gets `run.migrated` with `fromRelease`/`toRelease` only, and the initiator receives one in-app notice. Replays with the same `Idempotency-Key` return the first result.

## Limits (deliberate)

- Only a request waiting at one approval, timer wait or message wait moves. Requests inside parallel sections, at an HTTP/child/document step, or needing attention do not; there is no mapping editor and no way to override a finding.
- Changing the waiting step itself (group, deadline, time limit) is refused rather than recomputing its task and timers.
- No bulk migration of every request of a release, no scheduled migration and no automatic migration when a release is published.
- A request with running child requests cannot be suspended (S37), hence cannot move.
- Equivalence is structural. It does not prove business equivalence of two releases and says nothing about external systems.

## Tests

`ReleaseCompatibilityTest` (pure rules, including the referenced decision table and connection version); `RunMigrationTest` (PostgreSQL + Flowable, public API): a request moves to a release that only changes a later decision and then follows the new rule; changed step settings and changed history are refused with findings; a request past the decision refuses a release with a different decision; renaming the waiting step carries over, plus permission, stale version, unknown target and idempotent replay. `SecurityMatrixTest` covers both operations. Frontend: `RunDetailPage.test.tsx` (move dialog with a refused and an allowed version).
