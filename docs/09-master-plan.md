# Master implementation plan
Revision 0.3 · 7 October 2026 · proposed baseline

## Purpose and coverage

Build a business-friendly workflow product for simple and medium processes, with a deliberate path to advanced BPMN, cases, AI, integrations and migration. Use the same Java/Flowable/React/PostgreSQL codebase throughout.

This is the controlling overview for the planning package. The first package described the core slice well but left later capabilities too broadly deferred. This revision assigns those capabilities to milestones, owners, dependencies and acceptance evidence. It does not claim that missing customer inputs, implementation or production verification are complete.

Read this document first, then the [full backlog](10-full-product-backlog.md), [requirement coverage](12-requirements-coverage.md) and [decisions register](13-decision-register.md). Detailed product, UX, architecture and contracts remain in documents 01–07.

## Meaning of complete

| Term | Meaning |
|---|---|
| Plan coverage | Every master-prompt section has a disposition, owner and delivery path |
| Build-ready feature | Behavior, data/API contract, dependencies and acceptance tests are settled for that feature |
| Implemented feature | Real backend behavior, UI where required, durable data and error paths exist |
| Verified feature | Relevant automated and operational evidence has passed |
| Production-ready release | Applicable security, resilience, integration, capacity and business acceptance gates have passed |

The expanded plan covers all 25 prompt sections at planning level. Detailed external protocol contracts, source-specific migration mappings and enterprise configuration remain conditional on discovery. A later feature is not build-ready simply because it appears in the roadmap.

## Product boundaries

Business users receive templates, forms, approvals, a plain-language step editor and clear progress. Engineers receive versioned artifacts, tested mappings, policy-controlled connectors and operational diagnostics.

Simple processes ship first. Medium processes add matched parallel work, rework, timers, child workflows and asynchronous integrations. Advanced cases need explicit CMMN and case-data design; a PEGA stage list alone is not case parity.

Supported means all four layers are ready: engine semantics, canonical/import representation, UI/API exposure and tests. Publish a capability manifest covering those layers; engine support by itself never enables an untested editor node.

## Release milestones

Milestones describe scope and evidence; except R0, they do not promise delivery dates. Some work can proceed concurrently after its dependencies pass.

| Milestone | Intended outcome | Entry dependencies | Exit gate | Accountable owner |
|---|---|---|---|---|
| R0 — first three working days | Purchase approval: identity, form, designer subset, publish/start, task decision, rule, real HTTP, history | Staff/tools/DB/identity/test API available | B01–B09 and core tests pass; exceptions explicit | Technical lead + business owner |
| R1 — business workflow pilot | Reliable simple/medium work: task lifecycle, parallel/child/timer patterns, decisions, workspace organization, richer editing, supported Camunda import | R0 behavior and security stable | Purchase + onboarding + child + Camunda acceptance suites; business-user trial | Backend/frontend leads |
| R2 — AI and integration release | LLM draft generation, provider policies, agent/tool/MCP registries, authenticated events, Teams/email and approved connector catalog | R1 contracts, egress/secret policies, real endpoints | Real agent/MCP and Teams demos; denial/replay/schema-drift tests | Integration lead |
| R3 — migration and advanced process release | Native PEGA/LWFS adapters for supplied formats, broader BPMN/CMMN, advanced editor, Git promotion and optional Kafka/A2A | Representative exports, source owners, reviewed semantic mappings | Migration equivalence suites; advanced-pattern suite; auditable promotion | Migration lead + source-system owners |
| GA gate — production acceptance | Operational release of the selected supported scope | Any candidate release with declared capability set | Security, restore/failover, load, runbooks and business sign-off | Platform/security/business owners |

A limited production release can pass the GA gate before R3. Full master-prompt scope requires the relevant R1–R3 capabilities and all seven demos. Conditional features such as JSONLogic compatibility or compensation require an explicitly documented supported subset; they cannot disappear from status reporting.

## Three-day scope rule

The three-day base estimate remains 60–72 person-hours for three experienced engineers, with little contingency. It is an aggressive target, not a guarantee. Enterprise provisioning and unresolved integration work can invalidate it.

The base acceptance includes the small business canvas. If the team uses a template editor to recover schedule, mark R0 as a reduced-scope release and FR03 incomplete. Do not report the original full R0 scope as passed. No scope change may remove server authorization, persistence, immutable versioning, honest integration results or relevant recovery tests.

The master prompt's Day 2 and Day 3 feature list exceeds the baseline capacity assumption. Those items stay in R1–R3. If all seven demos must be delivered in the same three days, the current plan is not a credible commitment; staffing, prebuilt assets, endpoints and sample availability must be re-estimated first.

## Critical path

1. Confirm identity, PostgreSQL and allowed test integration; lock compatible dependencies.
2. Freeze v0 schema, API contract and authorization policy.
3. Prove real generated BPMN deployment and durable approval continuation.
4. Prove version binding, concurrency safety and external-effect recovery.
5. Connect the editor and task UX to the proven backend.
6. Run integration/security/restart checks and deploy to the available target.
7. Accept the declared release scope; only then add a chosen stretch feature.

Frontend can implement contract-driven components while backend work proceeds. Those components do not count as finished until connected and verified.

## Definition of a build ticket

Every ticket contains: requirement IDs; user outcome; milestone; accountable role; dependency tickets; allowed inputs; UI/API behavior; storage changes; authorization; failure and retry behavior; acceptance examples; test evidence; rollout/rollback; and current status.

Status vocabulary: Planned, Needs input, Ready, In progress, Implemented, Verified, Released, or Explicitly excluded. Documentation is Delivered separately. “Deferred” alone is no longer sufficient: it needs milestone, owner and exit gate.

## Design decisions that remain fixed

- Flowable executes process semantics; no custom graph runner.
- Canonical business model has a published supported subset; broader imported BPMN cannot silently change semantics.
- Immutable releases pin process, form, decision and integration-definition versions.
- PostgreSQL provides durability; Kafka/Redis/vector search are not prerequisites.
- Browser UX remains usable without an LLM.
- AI drafts and imported definitions cannot self-publish.
- Environment bindings and credentials stay outside exported definitions.
- Workspace policy applies to APIs, queries, events, background work and tools.
- External effects require deduplication/reconciliation; no blanket exactly-once claim.

## Explicitly outside this product commitment

An unrestricted PEGA replacement; automatic migration of active legacy instances/history; arbitrary in-process user scripts; unrestricted connector access; native mobile apps; billing/subscription management; simultaneous real-time coediting; and cross-region active-active operation.

These are exclusions, not missing implementation tickets. Basic optimistic edit conflict handling is included. Browser-based mobile task completion is included. If a new exclusion becomes a business requirement, create a separate epic and re-estimate.

## Review and change control

Business owner owns priority and accepted behavior. Technical lead owns scope/dependency coherence. Security/platform owners own their production gates. Assign named people before kickoff; roles in this document are placeholders, not actual staffing.

At each daily checkpoint, update completed work, failed gates, open inputs, remaining estimate and declared scope. At each milestone, publish the capability manifest, test evidence and unresolved limitations. Changes to execution semantics require schema/contract impact review and regression tests.

## Handoff checklist

- Start with documents 09, 13 and 06 to settle scope and dependencies.
- Build the v0 contracts in document 04 into machine-readable OpenAPI and typed schemas before splitting implementation.
- Implement B01–B09 in dependency order, preserving security and persistence.
- Use document 10 for all capabilities after the first slice.
- Use document 11 for permission, data lifecycle and operational rules.
- Use document 12 to prove that no master-prompt section or required deliverable was forgotten.

## References

[Technical references](14-technical-references.md) lists only current standards (BPMN 2.0.2, DMN 1.6, CMMN 1.1, JSON Schema 2020-12, OpenAPI 3.1, RFC 9457, WCAG 2.2, ASVS 5.0 and others), maintained documentation of pinned dependencies, and a small set of 2025–2026 research tied to later-milestone evaluations. Older research papers were removed; the design rules they once motivated are stated and tested directly in the build specification. The pinned Flowable release and Orqix's own tests determine the supported subset. No reference changes the selected stack or the release scope.

## Build specification status

The handoff step "build the v0 contracts into machine-readable OpenAPI and typed schemas" is done: see [docs/build](build/README.md) for the OpenAPI contract, reference DDL, schemas, fixtures, S1–S9 specifications, 24 build decisions and the inconsistencies they resolved. The ticket-level estimate in [S8](build/S8-r0-tickets.md) exceeds the three-day capacity assumed above; decision D01 must choose a schedule option before kickoff.
