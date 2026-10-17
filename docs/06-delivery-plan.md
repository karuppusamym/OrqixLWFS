# Three-day delivery plan and release gates
Planning estimate, not a guarantee. No implementation has started.

## Capacity and prerequisites

Base assumption: three experienced engineers for three 8-hour workdays = 72 person-hours, with an available business owner and platform support. A first estimate of the core work is 60–72 person-hours, leaving little contingency. Dependencies and unfamiliarity can exceed this; drop stretch scope before weakening correctness.

With one developer, budget 24 person-hours: prove a template-based Flowable approval flow, persistent task inbox and basic run view. A polished canvas, migration, AI and production hardening cannot responsibly all be committed in that window.

Before the clock starts, obtain PostgreSQL access, Java/Node/container tooling, a reachable OIDC development realm or PingFederate client, an approved test API with idempotency/reconciliation behavior, and a business decision maker. OpenShift namespace/image registry/HA database access is required to demonstrate OpenShift deployment. A local compatible OIDC provider is acceptable for development; it does not prove PingFederate integration.

Working dates may be 7–9 October 2026 if prerequisites and staff are ready; otherwise use Day 1–3 relative to the actual kickoff.

## Day-by-day outcomes

| Day | Backend engineer | Frontend engineer | Integration/QA engineer | Exit evidence |
|---|---|---|---|---|
| 1 | Compatible dependency spike, PostgreSQL, OIDC/RBAC, first Flowable deployment/start/task | App shell, workflow list, schema form, inbox scaffold | Compose, test fixtures, authorization tests, CI skeleton | Real persisted request reaches approval; restart retains it |
| 2 | Task decisions, rule/HTTP delegates, version locks, effect journal | Canvas subset, inspector, task actions, validation and run detail | Connector failure/idempotency tests, contract checks, two-instance recovery setup | Approval and rejection paths complete; duplicate actions safe |
| 3 | Resolve failures, publish concurrency, export, operational diagnostics | Publish review, task conflict UX, accessibility pass, template polish | OpenShift package/deploy if access exists, two-pod tests, restore exercise, demo evidence | Core scenario passes; limits and failures documented |

At end of Day 1, if real persisted execution is absent, stop adding features. At midday Day 2, if task completion or authorization is unreliable, defer the custom canvas and use a guided template editor around the same canonical schema/compiler. This is a UX scope reduction, not an engine rewrite. Record FR03/B07 as incomplete and declare a reduced-scope R0 release; do not label the original R0 acceptance as passed.

Freeze new features during the final half-day. Do not move testing to an unspecified “after demo” period.

## Core backlog

Effort ranges are rough person-hours and overlap only where engineers can work independently.

| ID | Work package | Estimate | Dependency | Done means |
|---|---|---:|---|---|
| B01 | Pin stack, project build, DB and migrations | 4–5 | None | Repeatable local build + real PostgreSQL |
| B02 | Identity, workspace policy, negative tests | 5–6 | B01 | Server rejects cross-workspace/task abuse |
| B03 | Canonical subset/compiler/release/start | 8–9 | B01 | Engine executes generated artifacts; version pin tested |
| B04 | Human task form/claim/decision/audit | 7–8 | B02/B03 | Conflict-safe persisted approval/rejection |
| B05 | Decision + approved HTTP + effect journal | 7–9 | B03 | Real API response, timeout and recovery behavior |
| B06 | UI shell/forms/inbox/run detail | 8–9 | API contract; integrate B04 | Business user completes flow |
| B07 | Small canvas/inspector/validation/publish | 8–10 | Canonical contract; integrate B03 | Supported graph creates real release |
| B08 | Packaging/operations/resilience/security QA | 9–11 | Incremental, finalize all | Evidence pack and deployment guide |
| B09 | Template/demo/user guidance/triage | 4–5 | All | Repeatable demonstration with honest status |
| | **Total estimate** | **60–72** | | No contingency for broad optional features |

Documentation already provided here reduces design uncertainty, not the need to implement or verify these packages.

## Stretch work: only after core gates pass

Choose at most one based on business value and available time:
1. One supported Camunda BPMN import plus compatibility report.
2. One matched parallel fork/join and timer for onboarding.
3. One pinned child workflow.
4. One AI draft provider with schema validation and human publish.

Agent/MCP/Teams features are separately estimated after endpoints and credentials exist. Native PEGA and LWFS parsers cannot be estimated credibly from a name alone.

## Master-prompt feature traceability

| Prompt capability | Three-day commitment | Next milestone |
|---|---|---|
| React/Java/PostgreSQL/Flowable | Core | Performance tuning |
| OpenShift/multiple pods | Package + test when access ready | Operational certification |
| Business visual designer | Bounded subset | Rich canvas and advanced editing |
| Workspaces/folders/tags | Workspace + workflow list | Nested folders/tags/favorites |
| JSON/YAML/BPMN portability | Canonical JSON + generated artifacts | YAML/advanced BPMN editor |
| Human approvals/forms/history | Core | Delegation/calendars/escalations |
| DMN rules | Small versioned decision table | Rich editor and broader policies |
| REST connector | One approved operation | Catalog/OpenAPI/JDBC/email |
| Live monitoring | Persistent timeline with polling | SSE/graph overlays |
| Versioning/rollback | Immutable versions; new-start selection | Git automation/promotion |
| Camunda import | Conditional stretch subset | Expanded adapters/behavior tests |
| PEGA import | Specification only until sample | Source-specific parser |
| LWFS import | Adapter contract only | Source-specific mapping |
| AI builder/LLM router | Optional single-provider draft | Multi-provider routing/budgets |
| Agent registry/agent task | Deferred unless replaces chosen stretch | HTTP, callbacks, A2A |
| MCP registry/tool execution | Deferred | Real server/tool + security tests |
| Teams approvals | Deferred | Notification first, authorized actions later |
| Child/parallel/timers | Selected stretch | Medium workflow library |
| CMMN/compensation/inclusive/multi-instance | Deferred | Separately tested advanced scope |
| Webhooks/cron/Kafka/desktop launcher | Deferred | Durable triggers/adapters |
| Full analytics/theme/editor conveniences | Basic status only | Usage-led improvements |

## Seven demonstration scenarios

| Demo | Intended status at three days |
|---|---|
| 1 Business Approval | Baseline target, real end to end |
| 2 Agent → MCP → Rule | Deferred unless real integrations replace other scope |
| 3 Teams approval | Deferred; secured inbox is baseline |
| 4 Child workflow | Optional chosen stretch |
| 5 Camunda migration | Optional chosen stretch |
| 6 PEGA migration | Blocked by missing representative native export |
| 7 AI workflow builder | Optional chosen stretch; no automatic execution |

## Functional and resilience checks

| Test | Required evidence |
|---|---|
| Valid low-cost request | Manager approval, standard decision, exactly one reconciled procurement effect, notification and completed outcome |
| High-cost request | Amount 5,000 routes to finance; 4,999.99 does not |
| Rejection | Required reason, rejected outcome, no procurement call |
| Missing/invalid form input | Backend returns validation errors without starting |
| Claim race | Two eligible users race; one claim succeeds |
| Completion race/replay | One engine continuation and audit decision; duplicate request is safe |
| Cross-workspace guessed ID | No disclosure or modification through runs/tasks/releases/export/events |
| Draft race | Stale If-Match returns 412; no silent overwrite |
| Version test | v1 run stays on v1 after v2 publish; new default starts use selected release |
| Restart waiting on task | Task and its pinned form remain available |
| Forced pod kill during HTTP | Journal/remote reconciliation prevents blind duplicate side effect |
| Two active executors | Concurrent runs, timer if enabled, expired lease recovery and retry counts verified |
| Unknown remote outcome | Run needs attention; no unsafe automatic retry |
| DB outage | Accepted-state claims stay accurate; readiness/recovery behavior verified |
| Secret leak test | Export, browser payloads, logs and diagnostics contain no credential material |
| Invalid/malicious definition | Arbitrary delegates, scripts, XXE and unsupported nodes blocked |
| Workflow without LLM | Entire business approval works |
| Backup restore | Restore to isolated DB and verify pending tasks/releases; record elapsed time |

Use backend unit tests for compiler and policy boundaries; integration tests with real PostgreSQL and Flowable; HTTP test servers for controlled error cases; browser tests for approval/publish flow. Contract fixtures are tests, not production connectors. The final demo also uses a real approved endpoint.

## Definition of done

Feature complete means code, real persisted behavior, server authorization, validation, failure handling, version correctness and relevant automated tests. Shipping a button or a stub does not count.

Full three-day R0 acceptance requires B01–B09, repeatable demo and build artifacts. Any incomplete work is an explicit reduced-scope delivery, with the missing requirements carried into R1; documented exceptions are not equivalent to passing the original scope. If a gate fails, report it and keep the affected feature unavailable. “Production-oriented” describes the design, not a passed readiness assessment.

## Production release gates

- Identity/group mapping validated with actual PingFederate and AD claims.
- Cross-workspace tests and review pass; privileged endpoints restricted.
- Dependency/SBOM/license and vulnerability checks pass under enterprise policy.
- TLS, secret rotation, egress policy and least-privilege service accounts verified.
- Two-pod recovery and concurrent completion tests pass on the target topology.
- Database backup/restore, upgrade and rollback rehearsed; RPO/RTO agreed and measured.
- Connector idempotency/reconciliation and operational ownership documented.
- Pilot load/SLO targets tested; pool/worker/HPA limits tuned.
- Sensitive-data retention/redaction and audit access approved by owners.
- Business owner accepts workflow behavior and migration differences.
- Monitoring, alert routing, support runbook and on-call owner are assigned.

## Demo script

1. Sign in as designer; open Purchase approval and inspect its form/rule.
2. Validate, publish v1 and submit a USD 1,800 request.
3. Restart an application instance while waiting; sign in as manager and claim/approve.
4. Show the real procurement reference, task history and completed outcome.
5. Submit USD 5,000; show finance routing and a rejection with no procurement call.
6. Publish v2 while a v1 task waits; show unchanged v1 behavior.
7. Demonstrate an unauthorized cross-workspace action being denied.
8. Force the approved test endpoint to fail; show retries or unknown-outcome handling and audited recovery.
9. Display current feature status and the remaining production gates.

## Risk register

| Risk | Response |
|---|---|
| Scope exceeds three-day capacity | Protect one complete vertical slice; choose one stretch only |
| Engine/Boot/JSON dependencies conflict | First four hours include compatibility startup/deploy test |
| Identity/cluster access arrives late | Develop against real local DB/OIDC; label enterprise verification outstanding |
| Missing source exports | Deliver adapter specification and report UI design, not invented converter |
| Duplicate external effects | Stable keys, journal, remote dedup/reconciliation, unknown-outcome state |
| Two-editors semantic drift | One canonical source per mode; immutable releases |
| Business users find canvas difficult | Template and step-list workflow creation precede advanced diagram features |
| Open-source component mistaken for commercial suite | Record exact packages/licenses; build needed UI explicitly |

## Current implementation status

As of this planning delivery: all runtime features are Not started. Documents/design/example are Delivered. Source export adapters are Specified only. Performance, security, recovery and production checks are Not run.

## Extended delivery control

Use [09 — Master plan](09-master-plan.md) for milestone definitions, [10 — Full backlog](10-full-product-backlog.md) for later work, [12 — Coverage](12-requirements-coverage.md) for all master-prompt requirements, and [13 — Decisions](13-decision-register.md) for inputs and owners. R1–R3 dates remain uncommitted until their discovery and estimates are complete.

## Evidence required by design rules

These checks are part of existing work, not extra R0 feature commitments.

| Work package / epic | Design rule | Evidence required |
|---|---|---|
| B03 compiler/runtime | Patterns are supported only when tested | Sequence, exclusive route and simple-merge tests; unsupported patterns rejected (S4 §12) |
| B06/B07 business UX | Readable business labels; keyboard alternative to the canvas | Observed comprehension of approval/rejection paths; WCAG 2.2 AA checks (S7 §7) |
| B05 integration recovery | Effect class per operation; unknown outcomes never auto-retried | Classification evidence, timeout/unknown-outcome handling and safe replay (S5 §6, T-EFF tests) |
| E04 medium execution | Joins continue once | Both branch completion orders, one join continuation and restart during parallel work |
| E05/E16 cases | Classify workflow versus case before conversion | Approved classification per migration candidate |
| E16 migration | No silent conversion loss | Original source, mapping log, unresolved issues and behavior comparison fixture |
| E09–E11 agents/tools | Bounded agent tasks under deterministic control | Allowlist denial, invalid output, timeout/budget and no unauthorized continuation tests |

## Ticket-level plan

[S8 — R0 tickets](build/S8-r0-tickets.md) breaks B01–B09 into tickets with acceptance criteria. Its ticket-level estimate is about 100 person-hours for P0 work, above the 72 hours assumed here; S8 §1 sets out the schedule options for decision D01. The B01–B09 estimates above are kept for traceability but are superseded by S8.
