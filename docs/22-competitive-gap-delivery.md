# Competitive gap delivery register

Updated 12 October 2026. The user authorized the remaining engineering gaps after the Pega/Camunda comparison and confirmed that the other model stopped editing on 12 October. This supersedes the prior deferment of these features; existing verified implementation remains in docs 19/21. Infrastructure and customer-source acceptance retain their own gates. No claim of universal Pega/Camunda equivalence is implied by closing a bounded slice.

Status: Planned, In progress, Implemented (awaiting verification), Verified locally, Needs external input. Each ticket requires backend/runtime, permission boundaries, authoring/operations UI as applicable, meaningful tests, documentation and actual execution evidence. Root coordinates shared contracts, verification and integration; independent agents implement scoped workstreams.

| ID | Gap / acceptance | Status | Current owner / dependency |
|---|---|---|---|
| WM01 | Skill-aware eligible queues/actions/data access; fresh membership checks | Implemented (awaiting verification) | Case/work management; S27; group-represented skills, no proficiency/least-load claim |
| WM02 | Working-day/time-zone/holiday calendars for due dates, DST/boundary tests | Implemented (awaiting verification) | Case/work management; distinguish due date from wall-clock timeout |
| WM03 | Durable recurring overdue notices, bounded repeats, completion race checks | Implemented (awaiting verification) | Case/work management; automatic reassignment separate |
| WM04 | Adaptive case data/milestones/discretionary tasks and actual CMMN lifecycle | Planned | Requires separate executable case model and runtime, not stage chips |
| WM05 | Task correction forms, conditional/repeatable sections and object collections | In progress | Other-model form guards/object model discovered; integration review and execution pending |
| WM06 | Automatic skills/proficiency/load-aware dispatch and queue administration | Planned | Explicit routing policy and explainable eligible selection |
| RL01 | Server-shared immutable workflow test suites; exact-draft/dependency publication gate | In progress | Decision/authoring; S28; payload visibility and CAS |
| RL02 | Multi-input decision tables, richer hit policies and deterministic evidence | In progress | Decision/authoring; flat AND slice implemented; other hit policies remain planned |
| RL03 | Effective-dated rule packs, currency semantics and separation of duties across stages | Planned | Policy dates/currency/version contracts; domain acceptance external |
| UI01 | Conditional forms and responsive reusable form layouts | Planned | Authoring / validation |
| UI02 | Full application UI designer and reusable views/actions | Planned | Define supported UI capabilities explicitly |
| UI03 | Swimlanes, threaded element discussion, YAML/BPMN editing and round-trip checks | Planned | Authoring/compiler/source modes |
| DOC06 | Workflow PDF/DOCX generation with pinned templates, mapping, limits and authorized artifacts | In progress | Documents/integration; S29; deterministic renderer |
| DOC07 | Approved drive/folder file browsing, exact version selection and narrow requester permissions | In progress | Documents/integration; admin browser implemented; requester grants/UI in progress; consent external |
| DOC08 | OCR, generation template registry/versioning and attachment retention/quarantine lifecycle | Planned | Parser/storage/provider and retention contracts |
| EX01 | Authorized external-worker jobs, leases/fencing/recovery and usable SDK | Planned | Root; workspace/topic/payload boundaries |
| EX02 | Audited incident input correction with concurrency and effect-intent safeguards | In progress | Case/work management; S30; no alteration of settled effects |
| EX03 | Detect runs waiting on nothing and recover under operator authorization | In progress | Root; S31 stale-empty watchdog implemented; arbitrary stranded-token repair remains planned |
| EX04 | Suspend/resume and explicitly compatible in-flight release migration | Planned | Root; pinned graph/activity/data checks |
| EX05 | Error, compensation and signal events with explicit supported combinations | In progress | Other-model failure routes/compiler rules discovered; runtime/editor verification pending; compensation/signal remain planned |
| EX06 | Embedded/event subprocesses, inclusive gateways and human multi-instance | Planned | Advanced BPMN; not inferred from engine dependency |
| EX07 | Broader bounded FEEL/import expressions and multi-decision project selection | Planned | Import/compiler; every unsupported source reported |
| IN01 | Approved OpenAPI connector import/catalog expansion | In progress | Other-model converter/parser discovered; compile API mismatches repaired; wiring/security/execution pending |
| IN02 | Email/Teams/JDBC/Kafka adapters and inbound triggers | Planned | Local stubs first; approved remote sends/contracts needed for live acceptance |
| IN03 | OAuth lifecycle, asynchronous agent callbacks, additional LLM protocols and A2A | Planned | Secure credential/callback contracts; external provisioning |
| IN04 | Approved write-capable agent tools with explicit effect/idempotency/reconciliation | Planned | Never infer recipient/send authorization from connector availability |
| PL01 | Folders/shared assets/search/owners and authorization-safe organization | Planned | Versioned resource references and workspace policy |
| PL02 | Git promotion/canary/environment lifecycle and broader analytics | Planned | Portable bindings and release compatibility; production infra excluded |
| AC01 | Genuine Pega/LWFS resolved exports and observed source outcomes | Needs external input | Source owner, formats, golden scenarios and sign-off |
| AC02 | Customer Microsoft/integration/scanner acceptance | Needs external input | Tenant consent, approved resources and securely provisioned credentials |
| AC03 | Banking policies, jurisdictions, domain scenarios and owner acceptance | Needs external input | Templates remain illustrative until accepted |
| OPS05 | Production HA/restore/capacity/enterprise identity/CI deployment | Needs external input | Previously excluded infrastructure stream |

## Evidence log

No new ticket is verified merely because code or tests exist. Coordinated results will be appended here and in docs 19/21 after execution. The earlier green checkpoint remains 56e721d; all new changes must preserve it.

12 October integration review: prior focused runtime pass failed because reminder SQL mixed named parameters with PostgreSQL's `?` operator, and the document delegate was missing from Flowable's explicit expression whitelist. Both were repaired. Review also repaired object-field DMN switch exhaustiveness, OpenAPI parser/type references, mock duplicate imports and incident-correction client argument/header errors. New checks are being rerun; prior agent-reported attachment/stage results and browser results are not substituted for combined verification. Scenario 50's container streaming limit and current accessibility coverage remain pending until executed against the rebuilt stack/browser.
