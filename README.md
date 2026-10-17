# Orqix LWFS — product planning and build specification
Revision 0.5 · 9 October 2026 · R0 implemented and verified end to end on the local Docker stack; production gates not yet passed.

**Engineers start with the [build specification](docs/build/README.md).** It contains the machine-readable contracts, the 24 build decisions, the ticket breakdown with acceptance criteria, and the test plan for R0.
**Sponsors and product owners start with the [master implementation plan](docs/09-master-plan.md)** and the R0 capacity finding in [S8 §1](docs/build/S8-r0-tickets.md#1-capacity-finding-read-first), which needs a schedule decision (D01) before kickoff.

The package accounts for all 25 sections of the supplied master prompt, all 18 required deliverables and all seven demonstration scenarios. Coverage means each has a delivery path and acceptance expectations; it does not mean every feature is implemented or every customer-specific detail is settled.

## Reading path

| Need | Read |
|---|---|
| See what is built and verified | [Feature status](docs/feature-status.md) |
| See the current build and remaining non-infrastructure work | [Build tracker](docs/19-product-build-tracker.md), [product closeout](docs/21-product-closeout.md) |
| Use or operate Orqix | [User guide](docs/user-guide.md), [Admin guide](docs/admin-guide.md) |
| Start building R0 | [Build specification index](docs/build/README.md) → [S8 tickets and schedule](docs/build/S8-r0-tickets.md) → [S1 engineering baseline](docs/build/S1-engineering-baseline.md) |
| Implement backend behavior | [S2 data model](docs/build/S2-data-model.md), [S3 API rules](docs/build/S3-api.md), [S4 compiler](docs/build/S4-workflow-compiler.md), [S5 runtime](docs/build/S5-runtime.md), [S6 security](docs/build/S6-security.md) |
| Implement the UI | [S7 frontend](docs/build/S7-frontend.md), [02 UX specification](docs/02-ux-design.md) |
| Test and release R0 | [S9 test plan](docs/build/S9-test-plan.md), [06 delivery plan and gates](docs/06-delivery-plan.md) |
| Use the contracts | [OpenAPI](contracts/openapi.yaml), [workflow schema](contracts/workflow-v0.schema.json), [connection schema](contracts/connection-definition-v0.schema.json), [reference DDL](backend/app/src/main/resources/db/migration/V1__orqix_core.sql), [examples and fixtures](examples/) |
| Understand the whole plan and release sequence | [09 Master implementation plan](docs/09-master-plan.md) |
| Understand later features | [10 Full product backlog: 20 epics](docs/10-full-product-backlog.md) |
| Verify requirement coverage | [12 Requirements coverage](docs/12-requirements-coverage.md) |
| Find missing inputs, owners and deadlines | [13 Decisions and prerequisites](docs/13-decision-register.md) |
| Understand users, behavior and scope | [01 Product specification](docs/01-product-spec.md) |
| Understand architecture rationale | [03 Architecture](docs/03-architecture.md), [04 Data/API contracts overview](docs/04-contracts.md), [07 Technology decisions](docs/07-technology-decisions.md) |
| Plan source migration | [05 PEGA, Camunda and LWFS migration](docs/05-migration.md) |
| Import supported workflows | [Migration workbench: Camunda and reviewed Pega mappings](docs/17-migration-workbench.md) |
| Improve authoring and integration | [Designer, connectors and migration assessment](docs/16-designer-connectors-migration.md) |
| Compare with Camunda/Pega, banking direction and conversion execution | [20 Competitive analysis](docs/20-competitive-analysis.md), [banking templates](docs/banking-templates.md), [banking capability matrix](docs/banking-capability-matrix.md) |
| Review current defects and remaining scope | [Release code audit](docs/18-release-code-audit.md) |
| Permissions, data lifecycle, operations | [11 Security, data lifecycle and operations](docs/11-security-operations.md) |
| Standards and dependency sources | [14 Technical references](docs/14-technical-references.md) |
| Checks performed and limitations | [08 Package validation](docs/08-validation.md) |

## Release summary

- R0: real purchase approval with forms, task decisions, rules, API integration, versions and durable tracking. Ticket-level estimate ≈ 100 person-hours (P0); see S8 for schedule options.
- R1: stronger business UX and medium workflow patterns, task lifecycle, child/timer/parallel behavior and supported Camunda migration.
- R2: AI provider/draft generation, agents, MCP/tools, Teams, approved connectors, events and analytics.
- R3: native PEGA/LWFS adapters after discovery, advanced BPMN/CMMN, advanced editing, Git promotion and optional Kafka/A2A.
- GA gate: production acceptance for the declared supported scope; may occur before full R3 breadth if all relevant gates pass.

Later milestones require feature-level estimates and available inputs. Full PEGA/Camunda parity and universal lossless migration are not promised.

## Assumptions and remaining inputs

R0 assumes experienced engineers, an available business owner, working development identity/PostgreSQL and a real approved test API. Team size and schedule (D01) and the purchase policy (D02) must be confirmed before kickoff.

LWFS name/version/schema, representative native exports, enterprise identity/cluster access, real integration endpoints, model/tool permissions, retention and production capacity decisions remain in document 13. A missing input blocks its dependent feature, not independent planning or core work.

## Pending implementation completed (9 October 2026)

Background AI drafts now use jobs and polling. The compiler, studio and runtime support matched parallel work, wait timers, approval time limits and pinned child workflows. Agent connections can use approved read-only MCP tools with bounded calls and recorded usage. See [S10](docs/build/S10-ai-assistance.md), [S12](docs/build/S12-workflow-patterns.md), [S13](docs/build/S13-agents-mcp.md) and the [release audit](docs/18-release-code-audit.md) for checks and remaining scope.

## What changed in revision 0.5

R0 is implemented: compiler module, Spring Boot + Flowable application, React frontend, Docker Compose stack with a Keycloak dev realm and a procurement stub, OpenShift Kustomize manifests and a CI workflow. Status and evidence per requirement: [feature status](docs/feature-status.md). Guides: [user guide](docs/user-guide.md), [admin guide](docs/admin-guide.md).

## What changed in revision 0.4

- Added the [build specification](docs/build/README.md) (S1–S9) and machine-readable contracts: OpenAPI 3.1 (48 operations), reference DDL (21 tables, executed on PostgreSQL 16), revised workflow schema, new connection-definition schema, procurement connection example and executable fixtures (25 compiler cases, purchase scenarios).
- Resolved 15 inconsistencies in the planning package (listed in the build index §4), including the purchase-specific status enum, run start by release, manager routing, effect policy placement and the undefined test-run mode.
- Replaced the research reading guide with [technical references](docs/14-technical-references.md): current standards and dependency documentation only; dated research papers (1987–2022) removed; versions rechecked on 7 October 2026.
- Found that the ticket-level R0 estimate exceeds the three-day capacity; options are in S8 §1.

## Current state

| Area | Location | Verified |
|---|---|---|
| Compiler | `backend/compiler` | 280 tests |
| Application | `backend/app` | 453 tests on PostgreSQL 18 (Testcontainers), API responses validated against the OpenAPI contract |
| Frontend | `frontend` | 530 unit/component tests including color-contrast checks; typecheck, lint, build |
| Browser E2E | `tests/e2e` | 30 pass / 11 skipped (mock-only) against the Docker stack; 37 / 4 in mock mode; no serious accessibility findings |
| API E2E | `tools/api-e2e` | 26 / 26 with injected failures, plus 33 / 33 operations scenarios |
| Resilience, performance | `tools/resilience`, `tools/perf` | Kill, database outage, graceful shutdown and revocation scenarios pass; 20 users × 10 min with 0 errors and p95 under 120 ms |
| AI assistance | `backend/app` (`com.orqix.ai`), `tools/ai-eval` | Optional. Set `OPENROUTER_API_KEY` in `.env`; DeepSeek by default, Jev Router for *Auto*, costly models excluded, monthly budget. Empty means rules-only |
| Camunda / Pega import | `com.orqix.migration`, `examples/migration` | Camunda 7/8 subset converts with a compatibility report; Pega archive assessment and reviewed manual mappings create validated drafts; no automatic native Pega rule conversion |
| Local stack | `deploy/compose` | PostgreSQL 18, Keycloak 26.8, procurement stub, migration job, app (OpenShift-style hardening) |
| OpenShift, CI | `deploy/openshift`, `.github/workflows` | Rendered and statically validated; not applied to a cluster or executed |

The UI uses the `wf` palette (red and gold on warm neutrals) by default; build with `--build-arg ORQIX_BRAND=orqix` for the teal palette.

Start locally:

```bash
cd deploy/compose
docker compose --profile app up -d --build      # then open http://localhost:8080
```

Open items are listed in [feature status](docs/feature-status.md#open-r0-items): the database restore exercise, the real procurement endpoint (D07), PingFederate (D05), OpenShift access (D06), a CI host (D15) and the production gates.

The 11 October reviewed checkpoint closes the scoped Camunda 8 import, message-event, diagram/Insights, workflow-test, attachment and case-stage work. Latest verification and remaining scope are in the [build tracker](docs/19-product-build-tracker.md) and [product closeout](docs/21-product-closeout.md); the new image has not replaced the existing deployment.
