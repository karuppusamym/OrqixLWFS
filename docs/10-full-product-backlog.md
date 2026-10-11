# Full product backlog and acceptance contracts
Revision 0.5, 10 October 2026. The epics below describe the full product scope. Core and selected later slices are implemented; authoritative completion evidence is in [feature status](feature-status.md). Current build evidence is tracked in [the product build tracker](19-product-build-tracker.md); remaining engineering, customer/source acceptance and excluded infrastructure are separated in [product closeout](21-product-closeout.md). These epics remain the future product scope; implemented slices do not close whole epics.
R0/R1/R2/R3 are defined in the [master plan](09-master-plan.md). Effort for R1–R3 must be estimated after the R0 integration spike and relevant discovery; no unsupported completion date is implied.

## E01 — Workspace and asset organization
Milestone: basic membership R0; full hierarchy R1. Owner: backend/frontend leads.
Dependencies: identity policy, resource ownership model.

Behavior: organization → workspace → nested folder → workflow; tags, favorites, search, named owners and workspace-shared templates/forms/rules/connections. Search filters authorization before returning results or counts. Folder moves cannot create cycles or silently change grants. Cross-workspace copy creates a new draft with dependencies requiring rebinding; it does not copy credentials or permissions.
Acceptance: nonmembers cannot discover names/counts; moving preserves history; deleting an owner/member identifies orphaned assignments; referenced published artifacts cannot be hard-deleted.

## E02 — Studio and six authoring modes
Milestone: R0 business subset/templates; R1 polished business editor/import modification; R2 conversational drafts; R3 advanced BPMN and JSON/YAML editing.
Owner: frontend lead. Dependencies: compiler/capability manifest.

Include searchable palette, drag/drop, undo/redo, copy/paste, duplication, multi-selection, keyboard shortcuts, inline properties, contextual help, schema-aware mappings, input/output preview, auto-connect suggestions and ELK layout. A suggestion requires a user's action and never modifies semantics silently. Node-level tests use authorized test bindings and sample input; workflow tests produce real labelled test runs.

Published-version comparison and plain-text step notes are implemented in the current closeout. Later add live execution highlighting, BPMN editing, Monaco JSON/YAML editing, accessible light/dark themes. Keep one authoritative source per mode; switching modes requires compatibility validation and creates a revision. Unknown fields/constructs produce issues, not silent loss.
Acceptance: graph edits survive reload; undo restores configuration and edges; keyboard step list supports equivalent edits; all six modes produce validated drafts; round-trip tests cover the documented subset; incompatible mode changes are blocked.

## E03 — Portable definitions and compiler
Milestone: v0 R0, medium extensions R1, AI/tool refs R2, advanced format R3.
Owner: backend/compiler lead. Dependencies: versioned node contracts and policy.

Extend v0 by schema version rather than adding unsupported optional fields that appear executable. Model triggers, node retry/timeout/error handling, form/rule refs, agent/tool/MCP refs, child refs, process variables, dependency locks and execution/security settings. JSON and YAML normalize to the same typed model; reject duplicate YAML keys, unsafe tags and excessive aliases. Preserve source maps.

Artifacts: canonical document, generated BPMN/DMN, UI layout/form metadata, dependency manifest, compiler version and hashes. Exports are portable source plus environment binding requirements.
Acceptance: deterministic artifact generation for the same inputs; unknown schema/node types blocked; explicit up/down conversion report; schema migration does not rewrite a published release.

## E04 — Medium workflow execution
Milestone: R1. Owner: backend lead. Dependencies: E03 and R0 transaction/recovery gates.

Support matched parallel forks/joins, bounded rework, embedded subprocesses, pinned call activities, duration/date timers, boundary timer/error patterns, cancellation and suspension/resumption. Rework creates a new occurrence ID and task; prior decisions remain immutable. Parallel branches write separate scoped outputs; joins use explicit merge rules, not last-writer-wins. Child cancellation/propagation and variable mappings are specified per activity.

Business deadlines store original UTC due time; reminders create durable, idempotent notification effects. Pausing execution does not automatically move business deadlines; the product must display that fact. A new due time requires an authorized audited action.
Acceptance: concurrent branch completion joins once; rework is bounded; child remains pinned after another version is published; kill/restart retains waits; race between approval and interrupting timer has one valid winner; suspend/resume behavior matches documented policy.

## E05 — Advanced BPMN and case management
Milestone: R3. Owner: BPMN/case lead. Dependencies: E04; representative advanced cases.
Status: Needs input for case semantics.

Add supported inclusive gateways, message/error events, event correlation, multi-instance tasks, compensation and script-equivalent external workers. Test each construct separately; mark unsupported combinations. A compensation action is a business-specific operation, not automatic reversal of arbitrary APIs.

Case module uses Flowable CMMN where appropriate: case data schema, stages, milestones, discretionary work, roles and case/process linkage. Preserve case and process IDs separately. Define which updates trigger case rules and how business history is recorded.
Acceptance: a capability matrix states supported engine/import/editor/API combinations; cancellation during multi-instance work is tested; compensation has authorized scope and auditable outcome; a representative adaptive case demonstrates actual required behavior. No “PEGA parity” claim from a stage diagram.

## E06 — Forms and task lifecycle
Milestone: core R0; expanded forms/lifecycle R1. Owner: frontend/backend leads.
Dependencies: E03, authorization, form-version pinning.

Add conditional visibility/required fields, repeatable sections, safe lookup fields, comments, request save-as-draft, send-back/rework, delegation, reassignment, escalation, reminders and task history. Conditional form values are validated on the server; hidden fields do not grant permission to alter protected data. Delegation records original assignee, acting delegate and period.

Attachment support is an R1 extension only when required: external object storage, size/type limits, malware scanning/quarantine, authorized downloads and retention linkage. Do not place large binary files in process variables or public URLs. Object storage is optional to the core until attachments are enabled.

Acceptance: expired delegation cannot decide; revoked membership cannot act; comments are scoped and safely rendered; historical tasks retain their form version; reminder/completion races do not send obsolete actionable prompts. Attachment access and deletion follow the case/run's policy.

## E07 — Decision management
Milestone: simple table R0; richer R1; practical JSONLogic subset R3.
Owner: backend/frontend leads. Dependencies: typed inputs and deterministic compiler.

Support visual decision tables, if/else, compound AND/OR conditions, input validation, output mapping, tests, versions and dependency pinning. Declare supported hit policies and expression operators. No general-purpose scripting.
Acceptance: threshold/null/type/overlap/default cases tested; a table edit never changes a published process dependency; JSONLogic imports either map to approved deterministic operators or return a blocker.

## E08 — Connector framework and catalog
Milestone: HTTP R0; extended catalog R2; optional Kafka/worker adapters R3.
Owner: integration lead. Dependencies: effect journal, secrets, permissions.

Each operation declares input/output schemas, version, transport, auth ref, endpoint policy, timeout, retry class, side-effect class, deduplication and reconciliation contract. Connection registration is distinct from granting use. Platform-shared definitions require explicit workspace grants; workspace secrets are not automatically shared.

Catalog sequence: HTTP → OpenAPI operation import → email/Teams → approved PostgreSQL/JDBC operations → agent/MCP → optional Kafka. Child workflow is an engine activity exposed in the same business palette. Webhook receiver is a trigger, separate from outbound HTTP.

OpenAPI import selects operations and validates endpoint bindings; remote descriptions do not authorize access. JDBC uses approved parameterized operations and least-privilege credentials; no arbitrary business-user SQL or runtime driver upload.
Acceptance: every operation has positive/negative contract tests; response schema failure is visible; timeout/unknown outcome is reconciled; replacing a connector implementation preserves its versioned contract or requires a new version.

## E09 — LLM router and AI workflow generation
Milestone: one provider draft R2 (optional earlier stretch); full named-provider matrix R2.
Owner: AI/integration lead. Dependencies: provider access, data policy, E03, budgets.
Status: Needs input for endpoint/provider choices.

Adapters target OpenAI-compatible endpoints, Azure OpenAI, Anthropic, Gemini and Ollama-compatible endpoints as requested. “Compatible” is a tested capability profile, not an assumption of identical APIs. Provider/model records include workspace grants, capability flags, context/output limits, timeouts, prompt versions and cost metadata when available.

Policy selects only allowed models/providers. Fallback may occur before a valid draft response, within data-residency and budget policy; never silently sends data to a different provider or replays tool side effects. Track token usage where returned; mark price/usage unknown when unavailable. Budget checks include concurrent requests.

Generation flow: request → permitted template/context → structured draft → schema/semantic checks → visible assumptions/missing bindings → human edit → publish permission check → separate run. Invalid model output can be repaired within a bounded retry budget, otherwise fails visibly.
Acceptance: all configured provider adapters pass contract tests; malicious instructions cannot grant tools/publish; partial/timeout responses never appear complete; no-provider mode works; unavailable cost metadata is not shown as zero cost.

## E10 — Agent registry
Milestone: HTTP agents R2; A2A adapter R3. Owner: integration lead.
Dependencies: E08, schema/version locking and authenticated callbacks.

Records include ID, name, description, owner, endpoint binding, protocol/version, input/output schemas, authentication reference, grants, timeout, health and tags. Allow platform-level and workspace-level registration. Internal, enterprise and external agents use the same authorization boundary; implementation language is irrelevant.

Support synchronous invocation with bounded timeout and asynchronous invocation returning an operation ID. Callback correlates operation/workspace/run/activity/attempt, validates identity/signature, expiration and nonce, and ignores duplicate completion safely. Late responses after cancellation do not resume a run.
Acceptance: real external agent result validated; endpoint revocation takes effect; duplicate/late/wrong-workspace callbacks blocked; health status does not substitute for runtime authorization. A2A is enabled only for an explicitly tested protocol version.

Bounded agent tasks: an agent or tool-using model runs inside one bounded task with explicit tool grants, input/output validation, time and call limits, and audited external actions. Flowable alone decides durable process continuation after the validated task result. Do not require private model reasoning in the audit trail. Apply the current [MCP security best practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices) (specification 2026-07-28) and evaluate outcomes from persisted state as in [doc 14](14-technical-references.md) section C. This applies to E09–E11 and remains R2 scope.

## E11 — MCP and unified tool registry
Milestone: R2. Owner: integration/security leads. Dependencies: E08/E10 policies, remote test server.

MCP registration includes endpoint/auth ref, selected released protocol/transport, connection test, workspace grants and discovery state. Discovery returns candidates; an administrator approves tool names and schema versions. Tool Registry unifies approved HTTP/agent/MCP operations through source references; it does not duplicate secret storage.

A tool becomes a palette node only after grant and schema validation. Capture schema hash in the release; schema drift blocks affected executions or requires a new reviewed binding. Descriptions/results are untrusted. Disable optional server-requested capabilities such as sampling or elicitation unless explicitly implemented and authorized.
Acceptance: real discovery/invocation works; unapproved tools denied; schema drift and malicious descriptions cannot widen rights; credentials stay server-side; timeout, server loss and cancellation have honest outcomes.

## E12 — Teams and external notifications
Milestone: notifications + secured inbox links R2; actionable approvals R2 after enterprise permissions.
Owner: integration lead. Dependencies: E06/E08, enterprise app identity.
Status: Needs input for Teams/mail permissions.

Persist notification delivery independently of workflow business outcome. Show queued/sent/failed, not “delivered” unless the transport provides such evidence. Each logical notification has a stable deduplication key. Notification failure does not reverse an approval.

Actionable Teams approval maps verified external identity to an authorized application subject and task. Validate callback integrity, audience, timestamp/nonce and current task state. Use the same decision service as the web inbox; never trust a card's embedded user/group fields.
Acceptance: authorized approval resumes once; forwarded-card/forged-user/replay attempts fail; stale cards show already completed; a real notification and real authorized decision demonstrate Demo 3.

## E13 — Triggers, schedules and event correlation
Milestone: manual/REST R0; webhooks/cron/DB events R2; Kafka R3.
Owner: backend/integration leads. Dependencies: versioned start API and idempotency.

Schedule record: workspace, release binding policy, cron expression, IANA timezone, enabled state, next due time and missed-run policy. Default is pinned release; “selected active release” requires explicit policy. Use tested Flowable scheduling where suitable. Product default for missed occurrences: skip backlog and record misses; optional catch-up is bounded. DST duplicate/missing local times follow a documented scheduler policy with preview and tests.

Webhook uses signature over original bytes, bounded age, replay key and rate limit; persist accepted event before asynchronous processing. Rotate signing keys with an explicit overlap period. DB-backed inbox/outbox and correlation records are transactionally persisted. Correlate by workspace, event type, business key and authorized waiting subscription; zero or ambiguous matches are visible errors.

Kafka consumption commits acknowledgement only after durable acceptance; replay deduplicates. Define dead-letter handling and operator replay limits.
Acceptance: two pods create one logical scheduled occurrence; restart/missed/DST cases tested; signature/replay/oversized input rejected; duplicate events do not start duplicates; ambiguous correlation never selects an arbitrary instance.

## E14 — Identity and governance
Milestone: safe core R0; full inheritance/resource grants R1; integration/tool grants R2.
Owner: security/backend leads. Dependencies: IdP/group contract.

Implement all eight master-prompt roles, explicit workflow/action grants, workspace membership and manual assignments. Identity is issuer + subject; email is display/contact information. Group claim synchronization and revocation behavior must be documented. Protect background/service execution as carefully as browser commands.

Policy-controlled separation of duties can disallow self-approval and require a different publisher from the last editor. These restrictions are workspace policy, with explicit pilot defaults. Administrators do not silently inherit authority to read all sensitive run data or approve tasks.
Acceptance: negative authorization matrix passes; role changes revoke cached access within the declared bound; scope inheritance cannot create cross-organization access; privileged grants and break-glass events are audited. See document 11.

## E15 — Version control, Git and environment promotion
Milestone: immutable revisions/export R0; semantic comparison/rollback R1; Git/promotion R3.
Owner: backend/platform leads. Dependencies: artifact manifest and environment binding policy.

Compare semantic changes separately from layout changes. Git integration uses repository-scoped credentials and exported secret-free bundles. A commit identifies source; deployment release also identifies compiled hashes and dependency locks.

Promotion moves the tested artifact unchanged between development/test/production, resolves approved environment bindings and rechecks policy. Publish is distinct from selecting an active release. Rollback redirects new starts; in-flight version migrations are separately validated.
Acceptance: promoting identical source does not silently recompile with a different compiler; secret scan passes; denied production binding blocks promotion; rollback preserves existing runs and audit.

## E16 — Migration center
Milestone: compatible Camunda subset R1; native PEGA/LWFS R3 after samples.
Owner: migration lead + source-system owners. Dependencies: supported capability manifest, source exports.
Status: Needs input for native source-specific contracts.

Implement adapters, source inventory, mapping/remediation UI and compatibility reports per document 05. Preserve source IDs and original artifacts. Rules/forms/integration code/identity mapping are separate migration work, not implied by drawing a flow.

Acceptance: every source element has a disposition; zero unresolved blockers before publish; compare success, rejection, rework, error, timeout and assignment cases; native PEGA demo uses a genuine representative export; LWFS mappings identify actual source version/schema. Existing live instances stay in the source system unless a separate migration project is approved.

## E17 — Execution visibility and analytics
Milestone: timeline/status R0; node graph/SSE R1; analytics R2.
Owner: frontend/platform leads. Dependencies: durable event projection.

Provide node status, history, failure diagnosis, retry history, duration, throughput, SLA monitoring, correlation IDs and OpenTelemetry. Metrics distinguish business rejection from technical failure; waiting time from active execution; queued from failed external work. Define denominator/time window for completion and SLA rates.

SSE requires workspace-authorized reconnect using event cursor and bounded retention; if cursor expired, return a fresh snapshot/resync response. Correct polling remains a fallback. Dashboard counters must reconcile with source data.
Acceptance: reconnect across pods has no misleading success/missing terminal state; graph maps to pinned source version; metrics reconcile with a fixture set; logs/events/redaction obey data permissions.

## E18 — Deployment, resilience and operations
Milestone: R0 package and basic checks; GA gate for every production scope; capacity expansion throughout.
Owner: platform lead. Dependencies: target OpenShift/HA PostgreSQL, CI and monitoring.

Deliver probes, graceful shutdown, resources, HPA ceilings, rolling deployment, configuration/secrets, NetworkPolicies, TLS, metrics/logs/traces and tested job recovery. Include build/dependency/container scans, controlled migrations, backup/restore, secret rotation and release rollback. Test actual database failover separately from application pod loss.

Acceptance: evidence identifies image digest/configuration/test environment; database connection budget respected at maximum replicas; forced termination and rolling upgrade preserve accepted work; restore and failover meet owner-agreed targets; alerts/runbook and named operators exist.

## E19 — Desktop and external application entry points
Milestone: REST external apps R0; desktop launcher and Teams start R3/R2 respectively.
Owner: integration/frontend leads. Dependencies: unified authorized start API.

Desktop launcher is initially a registered deep link or small approved launcher into an authenticated browser form; it cannot carry secrets or bypass OIDC. External apps use service tokens with bounded workflow.start grants. A Teams start interaction validates identity and input through the same API.
Acceptance: launch inputs are validated; replay controls hold; unauthorized workflow IDs fail; no token is embedded in a URL.

## E20 — Documentation and product adoption
Milestone: initial guides/demo/status R0; expand each release.
Owner: technical lead + business owner.

User guide covers create/test/publish/submit/approve/track; admin guide covers identity, permissions, secrets, migrations, operations and recovery. Provide template catalog, contextual help, glossary, release notes, compatibility matrix and current feature status.

Acceptance: another engineer can build/deploy from the guide and a business user can run the approval scenario without developer intervention; all seven master-prompt demos have real evidence by full-scope completion; guides match enabled capabilities.

## Ticket readiness before implementation

For each epic's next slice, freeze exact endpoint/request/response/error contracts, schema migration, policy actions and Given/When/Then cases. Publish OpenAPI and generated/shared DTOs, connector contract fixtures and engine integration tests. E05/E09/E10/E11/E13/E16 particularly require protocol/semantic spikes; estimated work must include them, not hide them in implementation.
