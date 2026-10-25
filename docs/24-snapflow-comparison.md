# SnapFlow comparison

25 October 2026. Compares Orqix with the public repository `github.com/srakella/snapflow`, which presents itself as an "enterprise-grade AI-powered workflow automation platform, built to rival Pega and Camunda".

## Method and limits

- Read on 25 October 2026 from a shallow clone of the default branch: the README, the 42 Java files of `snapflow-engine`, the 80 TypeScript files of `snapflow-designer` and the SQL under `database/`. Nothing from it was run.
- The repository is licensed "All Rights Reserved / Proprietary". **No code, text or design was copied or reused.** This is a feature-level comparison only, and the Orqix side is written from our own notes and code.
- A shallow clone shows no history, so nothing is claimed about activity or maturity beyond the snapshot. The snapshot contains **no automated test files**, no server-side security configuration (the controllers use `@CrossOrigin(origins = "*")`) and a mock user in development mode.
- "Verdict" values: **Ahead** (Orqix does more, with evidence), **Equal**, **Gap** (SnapFlow has it and Orqix does not; tracked below) and **Different** (a deliberate design difference, not a missing feature).

## Feature matrix

| Area | SnapFlow (evidence in the snapshot) | Orqix (note / evidence) | Verdict |
|---|---|---|---|
| Visual workflow designer | Node canvas (ReactFlow) with start, end, user task, service task, email, timer, gateway, AI agent, dynamic router and rules-engine nodes; palette and properties panel | Studio canvas, steps and source views; steps for approvals, calls, waits, documents, messages, adapters, agents, sections and handlers ([S53](build/S53-lanes-source-discussion.md), [S59](build/S59-subprocesses-errors-signals-compensation.md), [S73](build/S73-adapter-step-inbound-events.md)) | Equal (Orqix has more step kinds) |
| BPMN 2.0 | Converts the canvas to BPMN (`bpmnConverter`, `bpmnMapper`) | Deploys BPMN; exact BPMN source view, edit/apply and round-trip check ([S60](build/S60-bpmn-source.md), [S77](build/S77-bpmn-sections-handlers-and-new-steps.md)); Camunda 7/8 import with source-linked blockers | Ahead |
| Versioning | "Smart versioning": auto-increment versions on save | Draft revisions with If-Match, immutable releases, pinned rollback, release compatibility, promotion between stages ([S54](build/S54-promotion-canary-environments.md), [S74](build/S74-promotion-ui-approval-gate.md)) | Ahead |
| Form designer | Drag-and-drop elements, row layout, preview, validations (required, min/max length, **regex pattern**, number and date bounds) | Schema-based request forms with conditional visibility, sections, columns and rows, enum, length, number, date and file limits, server and browser validation ([S35](build/S35-conditional-forms.md) and the form notes, FR04) | Equal, except **no regex `pattern`** (Gap G5) |
| AI form builder | `FormAiSidebar`, text to form fields | "Describe it" drafts a whole workflow including its request form ([S10](build/S10-ai-assistance.md)); no form-only assistant inside the form editor | **Gap G4** |
| AI text to workflow | `/api/ai/generate`; README says "Coming Soon" | "Describe it" with profiles, validation, human review and never auto-publish ([S10](build/S10-ai-assistance.md)) | Ahead (validated and reviewed) |
| AI agent step | `AIAgentDelegate` sends case context and a prompt to a model and logs the response | Agent steps with a closed tool set, MCP tasks, approved write tools, no content in logs ([S13](build/S13-agents-mcp.md), [S75](build/S75-approved-agent-write-tools.md), [S76](build/S76-native-async-agent-protocols.md)) | Ahead |
| Rules engine | Rule sets of prioritized rules (JSON conditions and actions), operators such as equals, greater/less than, contains, startsWith; evaluate and per-rule test endpoints; rule editor UI | Decision tables with hit policies, DMN import, Pega decision snapshots, shared decisions, evaluate endpoint and Studio test panel ([S40](build/S40-decision-hit-policies.md), [S61](build/S61-shared-assets.md)) | Equal or ahead on semantics |
| Rules import and export | CSV template import of a rule set (`ExcelImportExport`) | DMN import only; **no CSV or spreadsheet import or export of a decision table** | **Gap G3** |
| Collaboration comments | Threaded comments per node, resolve | Threaded discussion on steps and connections, resolve and reopen, permissions per role ([S53](build/S53-lanes-source-discussion.md)) | Equal |
| @mentions and notification bell | `@username` in collaboration messages, notifications with read and read-all, bell with jump to the mention | In-app notifications exist for tasks and runs, but **discussion has no @mentions and no notification on mention** (excluded in S53) | **Gap G1** |
| Dynamic router | A fan-out node; the designer warns when it has no merge point | Parallel and inclusive splits, bounded repeat, child-workflow `forEach`, several approvers (fixed 2 to 5) ([S51](build/S51-inclusive-and-multi-approver.md)); **no list-driven fan-out of human tasks** | **Gap G2** (partial) |
| Email node | Email node and config | Stage mail with capture, adapter step to an email adapter (stub transport) ([S73](build/S73-adapter-step-inbound-events.md)) | Equal (neither sends live in the demo) |
| Timers | Timer node | Duration and date waits with business calendars and SLAs | Ahead |
| Tasks and forms at runtime | Task list, complete with form data | Claim, decide, reassignment, separation of duties, dispatch, skills and load, reminders, SLAs | Ahead |
| Users and groups | Local user CRUD, status, groups, user lookup | OIDC identity (Keycloak), workspace roles, grant-only permissions, groups with membership; users are created in the identity provider | Different |
| Audit | Process history endpoints (instances, activities) | Append-only audit with an action whitelist, per-request timeline and run path | Ahead |
| Security | None found in the engine snapshot; wildcard CORS | OIDC, role and grant checks on every operation, security matrix test, SoD, egress policy, secret handling ([S6](build/S6-security.md)) | Ahead |
| Operations | Docker Compose | Repair and consistency scan, watchdog, graceful shutdown, load and accessibility qualification | Ahead |
| Migration | None found | Camunda 7/8, DMN, Pega XPDL, archive assessment, FEEL subset ([S46](build/S46-bounded-feel-expressions.md), [S50](build/S50-pega-xpdl.md)) | Ahead |
| Excel/CSV import of workflows | Not found | Not provided | Equal |
| Real-time collaboration | Message polling endpoints | Not real-time | Equal |
| Dashboard | Dashboard page | Home and Insights with filters and downloadable reports | Ahead |

## Gaps to close

| ID | Gap | Why it matters | Proposed slice |
|---|---|---|---|
| G1 | @mentions and notifications in the step discussion | SnapFlow's headline collaboration feature | Mention only people who can read the workflow; one in-app notification per mentioned person; the bell links to the step; no email, no leakage of the text to people without access |
| G2 | List-driven human fan-out (their dynamic router) | One approval or task per item of a list, with a merge | Bounded collection of human tasks with an "all must approve" or "first rejection ends it" rule, reusing S51 separation of duties |
| G3 | Decision-table CSV import and export | Business users maintain rules in spreadsheets | Strict CSV and a documented column format for a decision table, with line-numbered errors, export that round-trips, and the same hit-policy validation as DMN import |
| G4 | AI assistant for request forms | Faster form authoring | Suggest or refine request-form fields from a description, validated against the form schema, shown as a reviewable diff, never auto-applied |
| G5 | Regex `pattern` validation on text fields | A common form validation | A linear-time regex subset or engine, a length cap on pattern and input, a custom message, applied in browser and server |

## Where Orqix is already ahead

Runtime governance (separation of duties, SLAs, dispatch, repair), real authorization on every endpoint, migration from Camunda and Pega, release promotion with an approval gate, approved agent write tools, BPMN source editing with exact round trip, and a tested, accessible, qualified delivery (about 2,400 automated backend, frontend and SDK tests at the last full run, plus real-stack browser journeys with accessibility checks).

## What is not claimed

SnapFlow's repository is a small snapshot, so its README may describe capabilities not present there, and this comparison does not assess a deployed SnapFlow. Parity means feature coverage, not equal maturity.
