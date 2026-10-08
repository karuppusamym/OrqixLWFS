# Master-prompt coverage and deliverable register
Revision 0.2 · 7 October 2026

Coverage means the requirement is addressed by a plan, including an explicit later milestone or dependency. It does not mean implemented, tested or production-ready. All runtime capabilities remain Planned or Needs input.

## Audit result and changes

The original package sufficiently scoped the first slice but was not a detailed full-product delivery plan. Revision 0.2 adds: a controlling roadmap; 20 delivery epics; owners/dependencies/exit tests; all eight roles and access rules; data retention and recovery policy; source-to-plan mapping; required artifact ownership; and unresolved decision gates.

No master-prompt section is intentionally omitted. Full-scope completion still depends on external inputs and feature-level implementation contracts. The workflow JSON Schema remains a draft for the v0 subset, not the complete eventual platform format.

## Coverage of all 25 prompt sections

| Prompt section | Planned coverage | Delivery milestone | Detailed plan |
|---|---|---|---|
| 1. Role/production-first | Same codebase, engineering ownership, no fake success, explicit gates | All | 09 master plan; 06 delivery |
| 2. Product vision | Business processes/cases, BPMN, visual authoring, AI/agents/MCP, tasks/rules, identity, portability/migration/events | R0–R3 | 01 product; 10 E01–E20 |
| 3. Mandatory stack | Requested frontend/backend/database/infrastructure; external language adapters; no mandatory extra runtime | R0 base, optional adapters later | 03 architecture; 07 technology; E08–E11 |
| 4. Architecture principles | Single engine/Java/React, DB durability, modularity, security, versions, optional AI | All | 03; 09 fixed decisions |
| 5. Workflow Studio | Grouped navigation covering all destinations, hierarchy/assets, six creation modes, full editor affordances | R0–R3 | 02 UX; E01/E02; navigation map below |
| 6. Definition/portability | Versioned JSON/YAML, schemas, node/reference types, compile/export/import, source maps, environment separation | R0 subset → R3 full supported format | 04 contracts; E03 |
| 7. Workflow engine | Basic/medium/advanced constructs; all invocation channels; safe external effects; durable/versioned execution | R0–R3 | 03; E04/E05/E13/E19 |
| 8. AI orchestration | All named provider families, model/router policies, fallback/usage/cost/prompts, validated human-reviewed drafts | R2 | E09 |
| 9. Agent registry | All metadata/scope; HTTP/internal/external/enterprise; language neutrality; sync/callback; A2A | R2, A2A R3 | E10 |
| 10. MCP | Remote transport, registration/discovery/schema/test/invocation/auth, palette integration, controls | R2 | E11 |
| 11. Connectors | HTTP/OpenAPI/PostgreSQL/JDBC/webhook/Teams/email/Kafka/MCP/agent/child, reusable/versioned scoped operations | R0 HTTP → R2/R3 | E08/E12/E13 |
| 12. Rules | DMN tables, if/else/compound conditions, validation/mapping/versions/testing/editor, practical JSONLogic subset | R0/R1, JSONLogic R3 | E07 |
| 13. Human tasks | Inbox, groups, decisions/forms/comments, delegation/reminders/escalation/SLA/history; Teams decisions | R0/R1, Teams R2 | E06/E12 |
| 14. Identity/RBAC | PingFederate/AD/manual grants, membership/inheritance/resource permissions; eight roles; backend/runtime checks | R0/R1/R2 | E14; 11 role matrix |
| 15. Version control | Draft/published/deployment versions, comparison/rollback, Git/export/import/promotion/audit/locks | R0/R1, Git/promotion R3 | E15 |
| 16. Migration | Camunda, native PEGA, LWFS adapters; mapping/report/remediation; full upload-to-publish path | R1 Camunda; R3 PEGA/LWFS with samples | 05 migration; E16 |
| 17. Events/scheduling | REST/webhook/cron/DB events, correlation/deduplication/retry/dead-letter, optional Kafka | R0/R2/R3 | E13 |
| 18. OpenShift | Replicas/probes/drain/resources/HPA/rolling/config/secrets/network/TLS/telemetry/HA DB/pools | R0 package; actual topology + GA verification | 03; E18; 11 |
| 19. Security/operations | RBAC/audit/secrets/TLS/input/expression/SSRF/signatures/rate limit/redaction/isolation/quotas/tool/sensitive action policy | Core R0; feature-specific checks each release | 03; 11; E08–E14 |
| 20. Observability | Graph/timeline/node/failure/retry/duration/throughput/SLA/audit/correlation/OTel | R0 basics → R1/R2 | E17 |
| 21. Seven demos | All seven retained, evidence required; no synthetic integration success | R0/R1/R2/R3 | Demo register below |
| 22. First 72 hours | Reconciled with staffing; dependencies, critical path, scope fallback and daily gates | R0 | 06; 09 |
| 23. Deliverables | All 18 assigned an artifact location and owner; current status explicit | R0–R3 | Deliverable register below |
| 24. Quality gates | Backend/durability/errors/auth/versions/validation/tests/secrets/real integrations/restart/concurrency | Every released feature | 06; 11 |
| 25. Implementation instructions | Empty workspace inspected; plan now; implement smallest real slice then editor/integrations/migration/OCP/tests | Sequential build per 09 | 06; 09; status remains not implemented |

## Navigation coverage

| Prompt destination | Business-friendly placement | First full availability |
|---|---|---|
| Dashboard | Home | R0 basic, R2 analytics |
| Workspaces | Workspace switcher / Settings → Workspaces | R0 basic, R1 organization |
| Workflow Studio | Workflows → Open draft | R0 subset |
| My Tasks | My tasks | R0 |
| Executions | Runs | R0 |
| Templates | Workflows → Templates | R0 sample, R1 catalog |
| Migration Center | Workflows → Import / Migration jobs | R1 |
| Agent Registry | Settings → Integrations → Agents | R2 |
| MCP Registry | Settings → Integrations → MCP servers | R2 |
| Tool Registry | Settings → Integrations → Tools | R2 |
| Connectors | Settings → Integrations → Connections | R0 admin-bound HTTP; R2 catalog |
| Rules | Workflows → Shared assets → Decisions | R0 embedded, R1 shared editor |
| Schedules | Workflows → Triggers / Schedules | R2 |
| Analytics | Home → Analytics | R2 |
| Administration | Settings / privileged Administration | R0 essentials, expands each release |

Grouped navigation is a design choice to reduce clutter, not removal of capabilities.

## All 18 required deliverables

Paths below are intended build locations unless marked present. Owners are role assignments pending named staff.

| # | Deliverable | Target location | Owner | Current state / planned milestone |
|---|---|---|---|---|
| 1 | Complete source repository | Root, build files, dependency locks | Technical lead | Planning files present; source/build/Git setup R0 |
| 2 | React frontend | frontend/ | Frontend lead | Not started; R0 then each release |
| 3 | Java backend | backend/ | Backend lead | Not started; R0 then each release |
| 4 | Flowable integration | backend/runtime and compiler modules | Backend lead | Specified; not implemented R0 |
| 5 | PostgreSQL schema/migrations | backend database migrations + engine migration job | Backend/platform leads | Data model specified; DDL not created R0 |
| 6 | Workflow JSON Schema | contracts/workflow-v0.schema.json | Compiler lead | v0 draft present; standards validation/compiler tests pending; extend per milestone |
| 7 | REST API specification | contracts/openapi.yaml | Backend lead | Prose contract present; machine-readable OpenAPI not created; freeze R0 |
| 8 | OCP manifests | deploy/openshift/ | Platform lead | Specified, not created/tested R0 |
| 9 | Docker Compose | deploy/compose/ | Platform lead | Not created/tested R0 |
| 10 | Example workflows | examples/ | Business/backend leads | Purchase JSON draft present; executable artifacts/tests R0, onboarding R1 |
| 11 | Migration adapters | backend/migration/ | Migration lead | Specified only; R1/R3 and source dependencies |
| 12 | Automated tests | backend tests, frontend tests, tests/e2e/ | QA/engineering leads | Not implemented; package checks are not app tests |
| 13 | Architecture documentation | docs/03-architecture.md + decisions | Technical lead | Present; validate against implementation continuously |
| 14 | User guide | docs/user-guide.md | Frontend/business leads | UX spec present; actual user guide pending R0 |
| 15 | Admin guide | docs/admin-guide.md | Platform lead | Operational contract present; deployment-specific guide pending R0/GA |
| 16 | Production readiness checklist | docs/06-delivery-plan.md and release evidence | Security/platform leads | Checklist present; all runtime gates not run |
| 17 | Demo execution script | docs/06-delivery-plan.md; executable setup later | QA/business leads | Narrative script present; runnable setup/credentials-bound walkthrough R0 |
| 18 | Feature implementation status | docs/feature-status.md | Technical lead | Status plan in 06/09/10/12; create live capability/status manifest R0 |

A prose contract is not marked as a completed OpenAPI file. An architecture diagram is not marked as a running application. A required future artifact is not marked missing from the plan merely because implementation has not begun.

## All seven demos

| # | Scenario | Milestone / owner | Evidence needed |
|---|---|---|---|
| 1 | Form → approval → rule → API → notification | R0 / backend + business | Real persisted flow and remote reference; reject/error/restart cases |
| 2 | Agent → MCP tool → rule | R2 / integration | Real registered agent and server/tool results; grant-denial tests |
| 3 | Teams decision resumes workflow | R2 / integration | Authenticated callback, user authorization and duplicate/stale handling |
| 4 | Parent → child → result → continue | R1 / backend | Exact child version/result mapping and failure/cancellation behavior |
| 5 | Camunda import → review → execute | R1 / migration | Source BPMN, compatibility report, equivalence cases and engine run |
| 6 | PEGA export → supported conversion → execute | R3 / migration + PEGA owner | Genuine source export and approved semantic mapping |
| 7 | Prompt → draft → edit → publish → execute | R2 / AI/frontend | Real provider response, validation, human publish and deterministic engine execution |

## Gaps remaining by nature, not forgotten scope

- Customer decisions: document 13 records owners/deadlines/defaults.
- Full machine-readable contracts: generate and validate during feature readiness; v0 examples are drafts.
- Native migration: needs actual source artifacts and semantic validation.
- UI and workflow usability: design concept exists; user research and rendered visual verification are still outstanding.
- Dependency compatibility, resilience, capacity and security: proposed designs need actual build/test evidence.
- R1–R3 schedule: estimate after relevant spikes; roadmap milestones are not guaranteed calendar dates.

The plan is comprehensive enough to organize delivery and expose uncertainty. Claiming every detail is final before those inputs would make it less reliable.
