# S106 - Shared shape sizes, promotion notifications, secured Kafka, E2E suite repair (29 October 2026)

The rest of the loose-ends list after S104 and S105. Migration **V177**; apply it before starting this image.

## 1. One file for BPMN shape sizes (STU04 follow-up)

The Studio canvas (`frontend/src/features/studio/model/shapeSizes.ts`) and the BPMN export (`BpmnShapeSizes` in the compiler) kept the same numbers in two places "by hand". Both now read **`contracts/bpmn-shape-sizes.json`** (events 48, gateways 64, tasks 200 x 80): the frontend imports it, the compiler packages it as `orqix/schemas/bpmn-shape-sizes.json` beside the JSON Schemas and refuses to start if a size is missing, not whole or a non-square event or gateway. `BpmnInterchangeTest` checks that the export's sizes equal the file and that the canvas file imports it instead of repeating numbers. Nothing in the exported BPMN changes.

## 2. Promotion approval notifications (PL02a)

| When | Who is told | Where it leads |
|---|---|---|
| A promotion into a workspace with the approval gate is requested | Each person who may approve promotions there now (fresh access, explicit `workflow.approve-promotion`, the first 50 of the known people), **never the requester** | **Promotions** in the target workspace |
| The request is approved or rejected | The requester, in the **source** workspace | The source workflow (none when it no longer exists) |

In-app only (kinds `promotionRequest` and `promotionDecision`, V177 widens the kind check). The request message names the requester, workflow key, release and source workspace, never the request note; the decision message includes the approver's note. Idempotent through dedup keys. The approval-requested audit entry records `approversNotified` (a count).

Tests: `PromotionApprovalTest` (1 new, 4 in all): the approver is notified and the requester is not, the message has no request note, rejection and approval each reach the requester in the source workspace with the source workflow id. Two existing assertions that counted every request and audit row in the workspace now count only their own test's, so test order no longer matters. Frontend `notificationHref.test.ts`.

## 3. Kafka: a broker that requires sign-in, and error status that reaches the screen (IN05 follow-up)

- **`KafkaSaslTest`** (new): a throw-away `apache/kafka` broker that only accepts SASL/PLAIN sign-in. A trigger whose `secretRef` file has the wrong password consumes nothing and its status says `authentication_failed`; with the right credentials a record starts a request; the password is in no audit row or status field.
- **Fixed: a consumer that never reached the broker never showed its error.** The error code was kept in memory but the status row was written only from the poll loop, so a trigger with a wrong password or an unreachable broker showed no error at all. The error paths now write the status row. A sign-in refusal has its own code, `authentication_failed`; other failures stay `consumer_error`.
- **Fixed: no backoff while failing.** The retry delay was reset when the consumer was created, before the first poll, so a failing sign-in retried (and logged) every one to two seconds. It is now reset only after the broker answered a poll, so retries back off to one minute.
- The Kafka triggers panel shows the codes in words ("the broker refused the credentials ...").

Not covered: TLS (`SSL`, `SASL_SSL`) against a real broker with certificates, SCRAM, and two separate application processes (the existing two-replica test runs two consumer runtimes in one JVM). The https webhook receiver is also still untested against a real certificate.

## 4. The end-to-end suite runs in CI again

The Playwright suite had not run in CI: the job never started the stack (no procurement stub image), then had no sign-in passwords (S105 commits `1334c9e`, `f635e5d`). Once it ran, 9 tests failed before the 40-minute limit. Causes and fixes:

| Cause | Fix |
|---|---|
| Opening a task by its request title in "Available to claim" used the default priority order, so a new task fell off the first page once earlier specs left tasks behind | `openTask` and the specs that open the list directly use `sort=newest` |
| "Preview form" moved into the Studio's **More** menu | Specs open **More actions → Preview the request form** |
| `t-demo-*` presenter rehearsals need a seeded demo stack (S86) | Skipped unless `E2E_REHEARSAL=true` |
| `t-ai-02` ends with a live model call | Runs the call only with `E2E_AI_LIVE=true` (mock mode still runs it) |
| `t-chat-01` ran before any spec had published Purchase approval | Publishes the template first (`ensureTemplatePublished`) |
| Test copies of the template were all named "Purchase approval", so exact-name lookups matched several | Copies are created as `Purchase approval (<key>)`; the pins spec finds the seeded template's row by its id (copies take the template's name again when saved) |
| The Discussion card of the Studio side panel is collapsible and starts closed | `t-ui-22` opens it |
| BPMN shapes print "Failed" on the route, not in the step | `t-ui-18` looks for it on the canvas |
| BPMN shapes are the default canvas | `t-ui-10` measures either label |
| **Product bug**: BPMN shape labels are 15 px, so at the 0.85 minimum opening zoom they were 12.75 px on screen, under the 14 px rule | The minimum opening zoom is per notation: cards 0.85, BPMN 0.94 |

The E2E job limit is now 60 minutes. README lists `E2E_REHEARSAL` and `E2E_AI_LIVE`.

Local run (29 October 2026, fresh Compose stack built from this branch, Chromium, `CI=true` so one retry): 70 passed, 65 skipped by design (mock-only specs and the rehearsals), 1 failed (the pins spec, then fixed and rerun with the demo path before it: 2 passed). The fixes were checked spec by spec on the same stack.

## Evidence

Full `mvnw clean verify` (29 October 2026, JDK 21, Testcontainers PostgreSQL and Kafka, Flowable): compiler 649 pass; application 1,026 run, 4 skipped (opt-in live), none failing. Frontend 162 files, 1,193 tests: one AI-entry test failed once under full-suite load and passes on its own (11/11); typecheck, lint of changed files and `api:check` pass. `contract_check.py` and `consistency_gates.py` pass.

## Not covered

- Promotion: email or Teams delivery, reminders, multi-approver and expiry, Git promotion, automatic canary evaluation.
- Kafka: see section 3.
- E2E: the suite was repaired against a local stack; the CI run is the record for the full suite (see the PR checks). Specs still assume a shared, growing database; they are ordered and tolerant, not isolated.
