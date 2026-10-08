# Product specification
Version 0.1 · proposed baseline · 7 October 2026

## Product promise

Orqix helps business teams create, approve and track routine work without needing to understand BPMN. Engineers retain control over integrations, identity, deployment and operational policy. AI may help draft or explain a workflow, but deterministic process execution remains with Flowable.

“Lightweight” means a small operational footprint and a focused user experience: one application deployment and PostgreSQL, no required Kafka/Redis, no extra language runtime, and no long menu of unused tools.

## Users and their jobs

| User | Main job | Default landing page |
|---|---|---|
| Requester | Submit a request and understand its progress | My requests |
| Approver | Decide quickly with enough context | My tasks |
| Business designer | Configure forms, steps, ownership and rules | Workflows |
| Publisher | Review and release a tested revision | Publish review |
| Operator | Investigate blocked work and recover safely | Runs needing attention |
| Workspace administrator | Manage memberships and approved assets | Workspace settings |
| Platform administrator | Configure identity, system policy and operations | Administration |

Requester is a product persona; implement permission workflow.start plus own-request access without creating a ninth administrative role. Organization administrators and viewers retain the master prompt's roles. Roles can be combined for a small pilot; authority is explicit.

## Workflow complexity

| Class | Definition for this product | Examples | Release |
|---|---|---|---|
| Simple | Form, sequential approvals, deterministic branches, one/few APIs; typically 3–10 business steps | Purchase approval, access request, document review | Three-day target |
| Medium | Parallel review, rework, timers, child process, multiple integrations; typically 10–30 business steps | Employee onboarding, vendor onboarding, exception handling | Selected bounded patterns after core gates |
| Complex | Dynamic case planning, specialized rules, compensation, large event choreography, unrestricted scripts or extensive legacy customizations | Claims adjudication, collections, complex PEGA cases | Discovery and later releases |

Step counts guide discussion; semantics, data and integrations determine migration difficulty.

## Primary use case: purchase request

Requester supplies request title, purpose, cost center and amount in USD. The backend derives requester identity from the authenticated session. The backend obtains manager routing from workspace policy or an approved directory mapping; the requester cannot nominate an arbitrary approver.

1. Validate and save input, then start an immutable published revision.
2. Create a manager group task. Users claim it before deciding.
3. Manager approves or rejects; rejection requires a reason.
4. If approved, a versioned decision table returns standard or financeReview using an inclusive threshold of USD 5,000.
5. Finance approves high-value requests; rejection ends as Rejected.
6. Approved requests call the registered procurement API using an idempotency key.
7. Create an in-app notification and end as Completed. External notification is additional, with its own delivery status.
8. Display who acted, when, why and which version ran.

Failures are explicit: missing manager prevents submission; an unavailable procurement API results in retry/attention status; notification failure never changes a rejected request into approved or claims external delivery succeeded. External systems must expose a test endpoint or approved non-destructive operation.

First release has fixed currency and simple fields. Attachments, currency conversion, directory synchronization and rich conditional forms are later scope.

## Second use case: employee onboarding

HR creates an onboarding case → manager approval → IT and facilities work in parallel → both complete → HR checks readiness → send confirmation. Selected medium features add the parallel fork/join and due-time reminder. This is a planned template, not a claim that all PEGA case behaviors are supported.

## First release requirements

| ID | Requirement | Acceptance condition |
|---|---|---|
| FR01 | OIDC sign-in and workspace membership | Backend denies nonmember reads and writes, including guessed IDs |
| FR02 | Workflow list and draft CRUD | Draft survives reload; conflicting edits do not overwrite silently |
| FR03 | Business canvas | Add, connect, configure and remove supported steps; undo/redo; keyboard alternative |
| FR04 | Schema-based forms | Required/type/enum/size validation runs in browser and backend |
| FR05 | Human tasks | Group claim, approve/reject, reason and durable history; only one decision wins |
| FR06 | Rules | Test a small decision table; revision is pinned to the release |
| FR07 | REST connector | Only admin-registered endpoints and credential references; real result or visible failure |
| FR08 | Publish | Validate graph, configuration, permission and dependencies before immutable deployment |
| FR09 | Start workflow | Same idempotency key and payload returns same run; different payload conflicts |
| FR10 | Run detail | Current step, task ownership, history and readable errors persist after restart |
| FR11 | Versioning | Editing/publishing a new revision does not change existing runs |
| FR12 | Notifications | Persist in-app result/attention notifications; distinguish them from email/Teams delivery |
| FR13 | Export | Export canonical JSON plus compiled BPMN/DMN and manifest without secrets |
| FR14 | Audit | Record actor, workspace, object, action, time and correlation ID for sensitive actions |

Core node subset: start, end, human approval, decision table, exclusive branch, HTTP service task and in-app notification. No arbitrary expression or script editor in the first release. Support one start, at least one end and acyclic graphs initially; bounded rework requires a later explicitly tested construct.

## Priorities beyond the first slice

**Next, if time and dependencies permit:** one parallel fork/join pattern, a timer reminder, a pinned child workflow, a safe subset Camunda import, BPMN viewer, execution refresh via SSE.

**After the core works:** AI draft generation with one provider; HTTP agent registry and invocation; one remote MCP server/tool; Teams notification linking to the secured inbox.

**Later:** full advanced editor, YAML editor, extensive BPMN constructs, adaptive CMMN, cron administration, webhook administration, connector catalog, A2A adapter, Git push/promotion automation, advanced analytics, multi-provider routing and full migration remediation UI.

All seven master-prompt demos stay on the roadmap. Only Business Approval is a baseline three-day promise contingent on prerequisites and acceptance tests.

## Product behavior and boundaries

- Draft is editable; published revision is immutable. A change creates a new revision.
- Publish and Run are separate actions and permissions.
- A test is a real run in a test workspace with test connections, visibly labelled. No implicit production calls.
- Cancellation stops future progression where possible; already completed external effects are not automatically reversed.
- SLA in v0 means elapsed duration, stored in UTC and displayed locally. Business calendars and holidays follow later.
- Do not claim a due-date label is an active reminder. Reminder timers ship only when executed and tested.
- Delegation is R1. v0 operator reassignment is authorized and audited, restricted to eligible subjects/groups in the same workspace; the action does not change an existing business decision.
- Approval data edits are limited by the pinned form schema. Integration output cannot overwrite protected system fields.
- Business status (e.g. Rejected) is separate from engine lifecycle (e.g. Completed).

## Proposed acceptance targets

- A trained business user creates the approval example from a template in 15 minutes without editing XML.
- An approver completes a task in under one minute, excluding reading lengthy business material.
- Pilot load test: 50 concurrent users and 10,000 stored runs; target p95 list/task API latency under 1 second excluding external dependencies.
- A simple start request returns a run identifier within 2 seconds at the pilot load; service work runs asynchronously.
- No lost accepted starts or task decisions across an application restart against the surviving PostgreSQL database.
- Test job recovery within 120 seconds using explicitly tuned leases and bounded connector calls; this is not a default engine guarantee.
- Zero successful cross-workspace access in the authorization test suite.
- Accessibility target WCAG 2.2 AA, verified with keyboard and screen-reader checks before broad release.
- Backup/restore targets must be agreed with the database owner; a database failover or disaster RPO is not implied by pod recovery.

## Full-product planning references

The [master plan](09-master-plan.md) controls release scope. The [full backlog](10-full-product-backlog.md) specifies later capabilities; the [permissions contract](11-security-operations.md) defines role defaults. A template-only fallback is a reduced-scope R0 release, not completion of FR03.
