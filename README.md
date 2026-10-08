# Orqix LWFS — complete product planning package
Revision 0.2 · 7 October 2026 · planning delivered; application not implemented.

**Start with the [Master implementation plan](docs/09-master-plan.md).** It defines the full product, the three-day scope, later milestones, dependencies, ownership and the meaning of completion.

The package now accounts for all 25 sections of the supplied master prompt, all 18 required deliverables and all seven demonstration scenarios. Coverage means each has a delivery path and acceptance expectations; it does not mean every feature is implemented or every customer-specific detail is settled.

## Recommended reading path

| Need | Read |
|---|---|
| Understand the whole plan and release sequence | [09 — Master implementation plan](docs/09-master-plan.md) |
| Know exactly what to build in three days | [06 — Three-day delivery and acceptance](docs/06-delivery-plan.md) |
| Understand all later features and their completion criteria | [10 — Full product backlog: 20 epics](docs/10-full-product-backlog.md) |
| Verify every original requirement and deliverable is accounted for | [12 — Requirements coverage](docs/12-requirements-coverage.md) |
| Identify missing inputs, defaults, owners and deadlines | [13 — Decisions and prerequisites](docs/13-decision-register.md) |
| Understand users, business behavior and scope | [01 — Product specification](docs/01-product-spec.md) |
| Design the business experience | [02 — UX specification](docs/02-ux-design.md) and [visual design board](design/business-workspace.svg) |
| Implement the technical foundation | [03 — Architecture](docs/03-architecture.md) and [04 — Data/API contracts](docs/04-contracts.md) |
| Plan native source migration | [05 — PEGA, Camunda and LWFS migration](docs/05-migration.md) |
| Implement permissions and operations | [11 — Security, data lifecycle and operations](docs/11-security-operations.md) |
| Review stack choices and sources | [07 — Technology decisions](docs/07-technology-decisions.md) |
| Inspect the portable v0 draft | [JSON Schema](contracts/workflow-v0.schema.json) and [purchase workflow](examples/purchase-approval.json) |
| See checks performed and limitations | [08 — Package validation](docs/08-validation.md) |

## Release summary

- R0: three-day target for real purchase approval with forms, task decisions, rules, API integration, versions and durable tracking.
- R1: stronger business UX and medium workflow patterns, task lifecycle, child/timer/parallel behavior and supported Camunda migration.
- R2: AI provider/draft generation, agents, MCP/tools, Teams, approved connectors, events and analytics.
- R3: native PEGA/LWFS adapters after discovery, advanced BPMN/CMMN, advanced editing, Git promotion and optional Kafka/A2A.
- GA gate: production acceptance for the declared supported scope; may occur before full R3 breadth if all relevant gates pass.

Only R0 has a proposed three-day timebox. Later milestones require feature-level estimates and available inputs. Full PEGA/Camunda parity and universal lossless migration are not promised.

## Assumptions and remaining inputs

Three days assumes three experienced engineers, an available business owner, working development identity/PostgreSQL and a real approved test API. A solo plan has smaller scope. Team size and business policy remain unconfirmed.

LWFS name/version/schema, representative native exports, enterprise identity/cluster access, real integration endpoints, model/tool permissions, retention and production capacity decisions remain in document 13. A missing input blocks its dependent feature, not independent planning or core work.

## What changed in revision 0.2

Added the controlling roadmap, full backlog, role matrix, retention/recovery/CI policies, requirement/deliverable coverage and decision register. Clarified reduced-scope acceptance, authenticated requester identity, task reassignment/recovery operations, idempotency replay authorization, event cursor correctness and saved canvas layout.

## Current state

Planning documents, a static design board, a draft v0 JSON Schema, one example workflow and the [original prompt](docs/source/master-build-prompt.txt) are present. Runtime source, OpenAPI, database DDL, deployment manifests, executable integrations and application tests are not yet built. Their owners and target milestones are explicit in document 12.

The first implementation step is contract/dependency validation followed by one real Flowable approval flow on PostgreSQL. No application rewrite is planned after the demo; production readiness still requires passing the applicable gates.
