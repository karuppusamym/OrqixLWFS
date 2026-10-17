# S8 — R0 ticket breakdown, schedule and cut line
Build specification · revision 1.0 · 7 October 2026

Breaks the work packages B01–B09 from [doc 06](../06-delivery-plan.md) into tickets that meet the Definition of Ready (S1 §8). Each ticket names its lane, estimate, priority, dependencies, requirement IDs, acceptance examples and tests (IDs in [S9](S9-test-plan.md)).

Lanes: **BE** backend engineer · **FE** frontend engineer · **IQ** integration/QA engineer.
Priority: **P0** required for R0 acceptance · **P1** expected in R0, first to slip into a reduced-scope R0 · **P2** cut first.

## 1. Capacity finding (read first)

| | Person-hours |
|---|---:|
| P0 tickets below (52 tickets in total) | **100.5** |
| P0 by lane: BE / IQ / FE | 37 / 35.5 / 28 |
| P0 + P1 | 110 |
| Doc 06 capacity: 3 engineers × 3 days × 8 h (24 h per lane) | 72 |
| Doc 06 original estimate | 60–72 |

The ticket-level estimate exceeds the three-day capacity by about 40 %, even before contingency, and every lane exceeds its 24 hours. The specs remove design uncertainty; they do not remove implementation work (rule catalog, journal recovery, projection listener, studio, generated authorization suite, resilience tests).

**Options (decision D01, owner: sponsor + technical lead):**

| Option | What is delivered | Recommendation |
|---|---|---|
| A. 3 engineers × 5 days | Full R0 P0 and most P1 on Day 5; Day-3 checkpoint demo of the end-to-end flow through the UI using the template (no studio yet) | **Recommended** |
| B. 4 engineers × 4 days | Full R0 P0 + P1; the fourth engineer takes FE studio or IQ resilience | If an experienced engineer is available now |
| C. Keep 3 days | Day-3 checkpoint scope only, declared as **reduced-scope R0** (FR03 studio, export, reassign/cancel and parts of the resilience suite carried to week 2) | Only if the date is fixed; report it honestly per doc 09 |

Whichever option is chosen, no ticket may drop server authorization, persistence, immutable versions, honest integration results or the restart/duplicate-effect tests.

## 2. Schedule (option A) and exit gates

| Day | BE | FE | IQ | Exit evidence |
|---|---|---|---|---|
| 1 | B01.2 spike, B01.4, B02.1, B02.2 | B06.1 shell + generated API client (mocked by OpenAPI examples) | B01.1, B01.3, B01.5, B02.3 | Spike S-1…S-10 recorded; login against Keycloak; V1 applied to real PostgreSQL |
| 2 | B03.1, B03.2, B03.3 | B06.2 form renderer, B07.1 lists | B05.7 stub, B05.2 connections, B05.1 decision delegate, B02.5 harness | Purchase template validates; golden BPMN/DMN generated |
| 3 | B03.4, B03.5, B03.8, B04.1, B04.2 | B06.3 inbox, B06.4 run detail | B05.3 HTTP + journal, B05.4 failed jobs | **Checkpoint demo:** publish via API, submit via UI, run waits at manager task, survives restart |
| 4 | B04.3, B05.8, B03.4 rest, B02.4 | B07.2 canvas, B07.3 inspector | B05.5 operator, B08.1 packaging, B08.3 resilience | Approval/rejection/finance/HTTP paths complete; duplicate actions safe; kill-during-HTTP verified |
| 5 (½ day freeze) | Fixes, B03.6, B08.2 | B07.4 steps tab, B07.5 publish review, B06.5 | B08.4, B08.6 E2E, B09.1, B09.4 | Full demo script (doc 06) passes; evidence pack; feature status published |

Stop rules from doc 06 still apply: if real persisted execution is absent at the end of Day 1, stop adding features; if task completion or authorization is unreliable by midday Day 3, defer the canvas (B07.2–B07.4) and keep the template-based flow.

Critical path: B01.2 → B01.4 → B03.1 → B03.2 → B03.3 → B03.5 → B03.8 → B04.3 → B05.3 → B08.3.

## 3. Ticket register

| ID | Title | Lane | Est h | Pri | Depends | Req |
|---|---|---|---:|---|---|---|
| B01.1 | Repository, Maven/Vite skeletons, Dockerfile skeleton | IQ | 2 | P0 | — | Deliverable 1 |
| B01.2 | Compatibility spike S-1…S-10, ADR 0001 | BE | 4 | P0 | B01.1 | D16 |
| B01.3 | Compose: PostgreSQL init, Keycloak realm, stub container | IQ | 2 | P0 | B01.1 | Deliverable 9 |
| B01.4 | Flyway V1/V2, DB roles, `migrate` profile | BE | 1.5 | P0 | B01.2 | Deliverable 5 |
| B01.5 | CI stages 1–3 | IQ | 1.5 | P0 | B01.1 | Deliverable 12 |
| B02.1 | OIDC login, JDBC session, CSRF, `/me`, subject upsert | BE | 3 | P0 | B01.4 | FR01 |
| B02.2 | Policy service, 404/403 rule, problem details, correlation IDs | BE | 3 | P0 | B02.1 | FR01, FR14 |
| B02.3 | Dev seed (organizations, workspaces, bindings, groups, connection) | IQ | 1 | P0 | B01.4 | — |
| B02.4 | Access + admin API (`getAccessOverview`, bindings, grants, `putGroup`, `createWorkspace`) | BE | 2 | P1 | B02.2 | FR01 |
| B02.5 | Negative authorization suite generated from `x-orqix-permission` | IQ | 2.5 | P0 | B02.2, B02.3 | FR01 |
| B02.6 | Machine-client start (bearer, path filter) | BE | 1.5 | P2 | B03.8 | E19 |
| B03.1 | Canonical model, parse limits, schema validation | BE | 2 | P0 | B01.2 | FR02, FR08 |
| B03.2 | Semantic rule catalog, dominance, disjointness, `validateDraft` | BE | 4 | P0 | B03.1 | FR08 |
| B03.3 | BPMN/DMN generation, source map, hashes, golden files | BE | 3.5 | P0 | B03.2 | FR06, FR08, FR13 |
| B03.4 | Workflow, draft (ETag), template and `listGroups` API | BE | 2 | P0 | B02.2 | FR02 |
| B03.5 | Publish in one transaction, releases, active release | BE | 2.5 | P0 | B03.3, B03.4 | FR08, FR11 |
| B03.6 | Export bundle | BE | 1 | P1 | B03.5 | FR13 |
| B03.7 | BPMN diagram interchange from `uiLayout` | BE | 1 | P2 | B03.3 | — |
| B03.8 | Start run, start form, quota, projection listener | BE | 3 | P0 | B03.5 | FR09, FR10 |
| B04.1 | Task projection and queries (assigned, claimable, completed) | BE | 2 | P0 | B03.8 | FR05 |
| B04.2 | Claim and release | BE | 1.5 | P0 | B04.1 | FR05 |
| B04.3 | Decide command (one winner, SoD, idempotency, continuation) | BE | 3 | P0 | B04.2 | FR05, FR14 |
| B04.4 | Operator reassignment | BE | 1 | P1 | B04.2 | FR05 |
| B05.1 | Decision delegate, `evaluateDecision`, DMN parity test | IQ | 2 | P0 | B03.3 | FR06 |
| B05.2 | Connections API, bindings, secrets, egress policy (test endpoint P1) | IQ | 3 | P0 | B02.2 | FR07 |
| B05.3 | HTTP delegate, effect journal, outcome classification | IQ | 4 | P0 | B05.2, B03.8 | FR07 |
| B05.4 | Failed-job handling, needs-attention, attention notification | IQ | 2 | P0 | B05.3 | FR07, FR10, FR12 |
| B05.5 | Operator retry and reconcile | IQ | 2 | P0 | B05.4 | FR10 |
| B05.6 | Operator cancel | IQ | 1 | P1 | B05.4 | FR10 |
| B05.7 | Procurement stub | IQ | 1.5 | P0 | B01.3 | FR07 |
| B05.8 | Notification delegate and notification API | BE | 1 | P0 | B03.8 | FR12 |
| B06.1 | Shell, routing, auth handling, API client, problem mapping | FE | 3 | P0 | B01.1 | — |
| B06.2 | Home, request form renderer, submit | FE | 3 | P0 | B06.1 | FR04, FR09 |
| B06.3 | Task inbox and task panel with conflict UX | FE | 3.5 | P0 | B06.1 | FR05 |
| B06.4 | My requests, run detail, timeline polling | FE | 2.5 | P0 | B06.1 | FR10 |
| B06.5 | Runs list, attention filter, retry/reconcile dialogs (cancel/reassign dialogs P1) | FE | 2 | P0 | B06.4 | FR10 |
| B06.6 | Settings (read-only access, connection binding form) | FE | 1.5 | P1 | B06.1 | FR07 |
| B07.1 | Workflow list/overview, create from template, rollback | FE | 2 | P0 | B06.1 | FR02, FR11 |
| B07.2 | Canvas, palette, connection rules, layout, undo/redo, autosave/conflict | FE | 5 | P0 | B07.1 | FR02, FR03 |
| B07.3 | Inspector forms, decision table editor, test rule | FE | 3 | P0 | B07.2 | FR03, FR06 |
| B07.4 | Steps tab (keyboard alternative) | FE | 2 | P0 | B07.2 | FR03 |
| B07.5 | Check-workflow panel and publish review | FE | 2 | P0 | B07.3 | FR08 |
| B08.1 | Image, kustomize base/overlays, migration Job, probes | IQ | 2.5 | P0 | B01.4 | Deliverable 8 |
| B08.2 | Metrics and dashboards | BE | 1 | P1 | B05.4 | E17 |
| B08.3 | Resilience suite (restart, two executors, kill during HTTP) | IQ | 3 | P0 | B05.4 | §24 quality gates |
| B08.4 | Secret-leak test, SBOM, dependency/container scans | IQ | 1.5 | P0 | B08.1 | §19 |
| B08.5 | Database restore exercise | IQ | 1 | P2 | D06 access | GA gate |
| B08.6 | Playwright demo path + axe | IQ | 3 | P0 | B06.3, B07.5 | Demo 1 |
| B09.1 | Template wiring, demo data, demo runbook | IQ | 1 | P0 | B03.5 | Deliverable 17 |
| B09.2 | `docs/feature-status.md` capability manifest | BE | 1 | P0 | — | Deliverable 18 |
| B09.3 | User guide and admin guide | FE + BE | 2 | P1 | B08.6 | Deliverables 14, 15 |
| B09.4 | Evidence pack and triage | IQ | 1 | P0 | all | Doc 06 |

## 4. Acceptance criteria

Format: **Given / When / Then**, compressed. Each ticket also inherits the Definition of Done.

**B01.2 Spike.** All S-1…S-10 pass criteria in S1 §10 are met and recorded with exact versions in ADR 0001; any failure is escalated before other tickets start depending on it.

**B01.4 Migrations.** Given an empty PostgreSQL, when the `migrate` profile runs, then schemas `orqix` and `flowable` exist, V1/V2 are applied, and the app role cannot update `audit_event` (T-DB-01). When pods start with the default profile, then no DDL runs.

**B02.1 Login.** Given the dev realm, when rhea signs in, then `/me` returns her subject and workspace `purchasing` with `workflow.start`; when otto signs in, `/me.workspaces` is empty. Unauthenticated `GET /api/v1/me` returns 401 problem JSON, not a redirect. A POST without the CSRF header returns `403 CSRF_INVALID`.

**B02.2 Policy.** Given fred (facilities only), when he requests any purchasing resource by a correct ID, then 404 (T-SEC-01 seeds). Given vic (viewer), when he calls `publishDraft`, then 403 with audit `auth.denied`. Removing a subject binding takes effect within 60 s on another app instance (T-SEC-09).

**B02.5 Authz suite.** The suite enumerates every operation in `openapi.yaml` and every seeded principal; it fails if an operation lacks `x-orqix-permission`, if any foreign-workspace call returns anything but 404, or if a denied call changes the database (row-count and engine-state snapshot before/after).

**B03.1–B03.3 Compiler.** Every case in `examples/fixtures/compiler-cases.json` yields at least its expected blocker codes and `valid-base` yields none (T-COMP-01). Compiling the purchase template twice produces byte-identical artifacts that match the golden files (T-COMP-03). Generated XML contains no `${…}` other than the four allowed forms (T-SEC-06).

**B03.4 Drafts.** Given revision 4, when two designers save with `If-Match: "r4"`, then exactly one gets 200 (`"r5"`) and the other `412 STALE_REVISION` with `currentRevision: 5` (T-API-02). An incomplete draft (approval without group) saves successfully and validation reports `WF_SCHEMA_INVALID`.

**B03.5 Publish.** Given a valid draft, when paul publishes with an idempotency key, then 201 with release 1 and its manifest; repeating the call returns 200 with `Idempotent-Replayed: true` and no second deployment (T-API-03). A draft with a blocker returns `422 WORKFLOW_INVALID` and creates no engine deployment. Given release 1 active and a run waiting, when release 2 is published, the waiting run still reports release 1 and completes on it (T-RUN-06).

**B03.8 Start.** Given an active release, when rhea starts with valid input, then 201 and the run is `waiting` at "Review request" for group managers, with `RUN_STARTED` and `TASK_CREATED` events. Invalid input cases from `purchase-scenarios.json` return `422 VALIDATION_FAILED` with the listed pointers and create no run (T-RUN-01). Same key + same body → 200 replay; same key + different body → `409 IDEMPOTENCY_KEY_REUSED`.

**B04.1–B04.2 Tasks.** mani sees rhea's task under *claimable*; mani's own request does not appear in his claimable list (T-SEC-03). Two managers claim simultaneously → one 200, one `409 TASK_ALREADY_CLAIMED` (T-TASK-02).

**B04.3 Decide.** Given mani claimed, when he approves an amount of 1,800, then 201 and the run moves to "Create purchase order" (`running`). When he rejects without a reason → `422 REASON_REQUIRED`. Two concurrent decisions with different keys → one 201, one 409; exactly one `task_decision` row and one engine continuation (T-TASK-03). Restarting the app while a task waits keeps the task actionable (T-RES-01).

**B05.1 Decisions.** For every `decisionBoundaries` fixture, canonical evaluator and compiled DMN agree (T-COMP-04); 4,999.99 → standard, 5,000 and 5,000.01 → financeReview.

**B05.2 Connections.** A binding to a host not in `allowed-hosts`, to `127.0.0.1`, to `169.254.169.254` or with userinfo is rejected with `422 EGRESS_NOT_ALLOWED` (T-SEC-07). Responses never include secret values; `testConnection` refuses non-testable operations (`422 OPERATION_NOT_TESTABLE`).

**B05.3–B05.4 HTTP and journal.** Happy path: exactly one stub order per run; result stored; `EFFECT_SUCCEEDED`. With `status500 × 2` then success and `maxAttempts = 3`: three attempts, one order (T-EFF-02). With `commitThenTimeout` on an idempotent operation: the retry reuses the key and the stub returns the original order — one order (T-EFF-03). Same fault on a `nonIdempotent` copy of the operation: run `needsAttention` with `EFFECT_UNKNOWN`, no automatic retry (T-EFF-04). `status400` → `EFFECT_REJECTED` immediately (T-EFF-05).

**B05.5 Operator.** On `EFFECT_UNKNOWN`, retry returns `409 EFFECT_NOT_RECONCILED`; reconcile `confirmedApplied` with `procurementId` resumes the run without calling the stub again and completes as Fulfilled (T-EFF-06). Every operator action requires a reason and writes audit.

**B05.8 Notifications.** Fulfilled and rejected paths each create exactly one in-app notification for the initiator, also when the job is retried (dedup key) (T-RUN-05).

**B06.2 Request form.** Rendering `purchase-approval` shows four labelled fields in order, money with USD formatting; client and server errors appear on the same fields; a `RELEASE_NOT_ACTIVE` response reloads the form keeping values.

**B06.3 Inbox.** Keyboard-only: tab to a task, claim, enter a reason, reject; focus moves to the result message. When another manager decided first, the panel shows "This task was completed by {name}" (T-UI-03).

**B06.4–B06.5 Runs.** Timeline updates by polling without reload; needs-attention runs show the business-language message and, for operators, Retry/Reconcile with the rules above.

**B07.1–B07.5 Studio.** Create from template; change the finance threshold to 10,000 using only the keyboard via the Steps tab and inspector; save survives reload; undo restores the previous threshold and edges (T-UI-04). Deleting a node prunes its layout entry (no `WF_LAYOUT_UNKNOWN_NODE`). "Check workflow" lists issues by step with working "Go to step". Publish review shows changed steps and warnings and publishes with the success message from doc 02.

**B08.1 Packaging.** Image runs as an arbitrary non-root UID with a read-only root filesystem and a writable `/tmp`; the migration Job runs before rollout; readiness fails while the database is unreachable and liveness does not.

**B08.3 Resilience.** T-RES-01…T-RES-05 pass on two app instances against one PostgreSQL.

**B08.4 Secrets.** Export bundle, API responses, logs and problem bodies captured during the E2E run contain no secret value or authorization header (T-SEC-08).

**B08.6 E2E.** The doc 06 demo script runs green in CI (steps not requiring OpenShift), with no serious/critical axe findings and no CSP violations.

**B09.2 Feature status.** `docs/feature-status.md` lists each FR and capability with status from the vocabulary in doc 09 (Planned … Verified) and links to test evidence; nothing is marked Verified on mocks alone.

## 5. Day-0 prerequisites (before the clock starts)

| Item | Owner | Blocks |
|---|---|---|
| D01 option chosen (§1) and named engineers | Sponsor | Schedule |
| D02 confirmation that the purchase rules are acceptable sample policy | Business owner | B03 template freeze |
| Developer machines: Java 21, Node 24, Docker; repository access | Technical lead | Day 1 |
| Approved test procurement endpoint and its dedup/reconciliation contract (D07), or explicit acceptance that the demo shows the stub and the real-endpoint step is outstanding | Integration owner | Real-integration claim in demo step 4 |
| OpenShift namespace, registry and database access (D06), or acceptance that deployment is verified on Compose only | Platform owner | B08.1 on target |
| PingFederate development client (D05), or acceptance that Keycloak stands in and enterprise login stays an open gate | Identity owner | Enterprise login gate |
