# S70 - BPMN source: written defaults, field titles and the four example workflows (UI03)

S60 made BPMN editing available only for drafts whose round trip document -> BPMN -> document is identical. Several drafts failed that check only because they spelled a value differently from what the reader writes back. This slice removes those limits. It does not change the compiled output (no generator change, no compiler version bump, golden files unchanged), add `orqix:*` extensions, change the canonical schema, the runtime or the database (no migration).

## What changed

**1. A written default is equal to leaving it out.** A step setting is a *default* when all three hold:

1. the workflow schema allows the setting to be left out;
2. the compiler's model reader (`ModelReader`) uses exactly this value when it is left out, so the compiled model, the generated BPMN and the runtime behave identically whether it is written or omitted;
3. the studio shows an omitted setting as this value.

The complete list (asserted by `BpmnSourceTest.theDefaultsAreExactlyTheDocumentedOnes`, held in `BpmnDefaults.RULES`):

| Step type | Setting | Default | Note |
|---|---|---|---|
| `wait` | `atTime` | `"09:00"` | only when the step has `untilField` (the schema allows `atTime` only with it) |
| `externalTask` | `leaseSeconds` | `120` | compared as a number |
| `externalTask` | `result[].required` | `false` | per field |
| `waitForMessage` | `payload` | `[]` | |
| `waitForMessage` | `payload[].required` | `false` | per field |
| `parallelSplit` | `mode` | `"all"` | |
| `approval` | `requiredSkills` | `[]` | |
| `http` | `compensation` | `"manual"` | |
| `startWorkflow` | `collectionMapping` | `{}` | |

Nothing else is a default. A field `title` equal to its name is *not* one (the compiler falls back to the name, but the studio shows a humanized name, so condition 3 fails); `dueAfterHours`, `rejectReasonRequired` and `timeoutSeconds` have no default.

The BPMN cannot tell a written default from an omitted one. The reader keeps writing the omitted form, and `BpmnDefaults.adopt` gives what was read the draft's own spelling: per step with the same id **and** the same type, per setting, only when both sides mean the default (written-as-default on one side, absent on the other). It is used in the round trip (`export`), on the document built from submitted BPMN before it is merged (`check`/apply) and in the final read-back comparison. Consequences, each covered by a test:

- a draft that writes `atTime: "09:00"`, `mode: "all"`, `requiredSkills: []`, `compensation: "manual"`, `collectionMapping: {}`, `leaseSeconds: 120`, `required: false` or `payload: []` now round-trips and is BPMN-editable;
- applying its unchanged BPMN gives the draft back canonically identical, defaults included, so the document's semantic hash (a JCS hash of the document, which does differ between the two spellings) does not change on a no-op apply;
- a value that is not the default is never adopted: editing `atTime` from 09:00 to 10:30, `leaseSeconds` from 120 to 300, or from 10:30 back to 09:00 (then the omitted form is stored) are ordinary edits and listed as changes;
- a written default is not carried to a step that no longer uses it: turning the date wait into a duration wait gives `{"duration":"PT2H"}` without `atTime`.

**2. Field titles are kept from the draft.** The `title` of a message-payload field (`waitForMessage`) or an external-task result field (`externalTask`) is display text: the compiled BPMN does not carry it and no rule, route or runtime decision depends on it. It is now treated like the sections that are "kept from the draft": kept for a field with the **same name** on a step with the same id and type. The view lists it ("field titles of step 'waitPayment'") under "Kept from the draft and not in BPMN". A field renamed or added in BPMN has no title (the studio shows the humanized name) and the step is listed as changed.

**3. `dueAfterMinutes` is read back.** The generator already wrote `dueAfterMinutes` on `orqix:approval`, but the reader ignored it, so such drafts did not round-trip. The reader now reads it (exact inverse; no output change).

## The four example workflows

| Example | Before (S60) | Now | What still blocks |
|---|---|---|---|
| `start-date-onboarding` | differs at `/nodes/2/config/atTime` | **BPMN-editable** (round trip identical) | - |
| `order-payment-wait` | differs at `/nodes/1/config/payload/0/title`, `/1/title` | **BPMN-editable**; titles kept from the draft | - |
| `banking-credit-approval` | differs at `/nodes/3/config/repeat` | still read-only | step `review` (`startWorkflow`): `repeat {maxIterations: 3, untilOutcome: "Approved"}` |
| `banking-card-claim` | differs at `/nodes/1/config/collectionMapping`, `/forEach` | still read-only | step `disputes` (`startWorkflow`): `forEach {itemsRef: /input/transactions, itemInput, maxItems: 5, mode: parallel}` and `collectionMapping {outcomeItems, referenceItems}` |

The same two settings keep `medium-bounded-rework` (`repeat`) and `complex-batch-review` (`forEach`, `collectionMapping`) read-only. Of the 21 examples and compiler fixtures that compile, 17 now round-trip exactly (15 before); the test asserts the remaining four and their exact paths.

**Why the remaining two are not made editable.** `repeat`, `forEach` and `collectionMapping` change behaviour: how many child requests run, in what order or in parallel, when the loop stops and which collected outputs exist. The generator writes the same service task with or without them; the runtime reads them from the pinned document. Keeping them from the draft by step id (as done for titles) would let someone edit a BPMN that shows one child call while the step runs up to 3 rounds or 5 parallel children, and would silently keep the loop on a step whose child workflow was changed in the BPMN. Making them editable needs them in the BPMN, i.e. new `orqix:child` attributes or elements in the compiled output, which changes generated bytes and requires a compiler version bump. That is a user decision and was not done. The same holds for approval `separateFrom`, `correctable` and `dispatch` (no example uses them).

Not examined in this slice: whether drafts that use S59 constructs (sections, handlers, raise-error/signal/undo steps), automatic compensation (`isForCompensation`) or approval `notifications` round-trip; S60 did not list them and no example compiled here uses them.

## Implementation

- Compiler: new `internal/bpmn/BpmnDefaults` (rule table, `adopt`, `keptTitles`, `describe`); `BpmnSource.export`/`check` call `adopt` and `keptFromDraft` lists kept titles; `BpmnReader` reads `dueAfterMinutes`. `BpmnGenerator`, `BpmnCompare`, golden files and the compiler version are unchanged.
- Application, contract and frontend: unchanged (the view already shows the server's `keptFromDraft` list and round-trip result).

## Verification - 18 October 2026

Logs are in `evidence/s65/` (untracked). JDK 21; Docker/Testcontainers PostgreSQL.

- `BpmnSourceTest` (compiler): 20 tests, 0 failures (14 before). New: the exact default list; written versus omitted defaults for purchase approval with a worker step, new-hire onboarding, start-date onboarding and order payment (same compiled BPMN apart from document hashes, round trip identical for both spellings, own BPMN applicable with no changes and the draft canonically unchanged); non-default values are edits; no carry-over to a duration wait; titles kept by step and field name; `dueAfterMinutes`. The example test now expects `start-date-onboarding` and `order-payment-wait` to be identical and exactly four differing documents.
- Mutation check: with `adopt` disabled the new and changed tests fail (5 of 20; `evidence/s65/mutation-no-adopt.log`), so they test the change.
- `BpmnSourceApiTest` (PostgreSQL via Testcontainers): 4 tests, 0 failures; new `writtenDefaultsDoNotStopBpmnEditing` saves a draft with `requiredSkills: []` and `compensation: "manual"`, sees an identical round trip, applies an edited BPMN with If-Match and finds both written defaults still in the saved draft (`evidence/s65/focused-1.log`).
- Full backend `clean verify` (`evidence/s65/backend-verify.log`): compiler 501 tests, 0 failures. Application 794 tests, 1 failure, 4 intentional skips; `ArchitectureTest` (6) and `SecurityMatrixTest` (3) pass. The failure is `GracefulShutdownTest.tRes05_idempotentCallInFlightInstanceStoppedGracefullyOneOrderRunCompletes` (a failed job attempt during a two-node graceful shutdown). It also fails when run alone with this change (`graceful-rerun.log`) and when run alone at the base commit `db47787` with this change stashed (`graceful-head.log`). It predates this slice, which touches no runtime code. Because of that failure the build stopped before packaging.
- Frontend (no frontend file changed): full suite run 1 had 868 of 877 pass. The 9 failures were 6 timeouts at 15 s and 3 "element not found" waits in studio/AI/runs tests (`frontend-full.log`). All 9 files plus `BpmnSourceView.test.tsx` then passed alone, 56 of 56 (`frontend-rerun.log`). Full run 2 had 875 of 877 pass; both failures were 15 s timeouts in `ai.test.tsx` and `StudioPage.test.tsx` (`frontend-full-2.log`). S60 recorded the same timing pattern under load. There was no clean full frontend run in this slice.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=BpmnSourceTest,BpmnSourceApiTest -Dsurefire.failIfNoSpecifiedTests=false test`; from `frontend`: `npx vitest run src/features/studio/BpmnSourceView.test.tsx`.
