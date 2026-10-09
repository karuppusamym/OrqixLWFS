# Current release code audit

Reviewed 9 October 2026. The pending implementation is complete for background AI drafts, matched parallel branches, wait timers, approval time limits, pinned child workflows and bounded agent connections with approved read-only MCP tools. Changes remain uncommitted.

## Completed and checked

- AI drafting returns a persisted job with `202` and polling. Workspace notices let people continue working and open the draft when ready. Repeated requests reuse the job; concurrent admissions respect the per-person limit. Interrupted jobs fail after their deadline, access is rechecked before saving, and expired terminal jobs are removed while live replay records, drafts, usage and audit records remain.
- The compiler validates parallel joins, durations, approval timeout routes, child input/output mappings, recursion and depth limits. Published manifests pin child releases. The studio exposes the new steps, mappings and routes in Canvas and Steps, and updates references when steps are renamed.
- The runtime persists timers, joins parallel arrivals once, cancels timed-out tasks and waits for child results. Immediate child completion resumes its parent through a durable job. Cancellation or rejection ends active descendants and their tasks. A run remains Waiting while another parallel approval is open.
- Agent connections use bounded model/tool calls, schema validation, existing egress and binding controls, effect outcomes and usage records. The procurement stub implements deterministic MCP tools, authentication and fault modes. Full workflow tests cover automatic low-risk completion and high-risk compliance approval.
- Earlier audit fixes remain: task-tab selection, operator recovery links, accessible version state, authoritative compiler readiness beside AI explanations, and Pega manual-mapping integrity checks.
- API types were regenerated, frontend lint was corrected, and the root build ignore rule was narrowed so source specifications under `docs/build` are visible to Git.

## Verification evidence

| Check | Result |
|---|---|
| [Full backend verify](../evidence/pending-backend-verified.log) | 263 compiler tests and JaCoCo gate pass; 180 application tests, 1 opt-in live-provider test skipped, no failures |
| Frontend | 421 tests pass; TypeScript, lint, API drift check and production build pass |
| [AI concurrency and agent workflow regressions](../evidence/pending-agent-ai.log) | 23 tests pass |
| [Procurement stub](../evidence/pending-stub.log) | 87 tests pass |
| [Contract check](../evidence/pending-contracts.log) | 50 checks pass, including all three workflow examples and both connection definitions |
| [Selected mock browser checks](../evidence/pending-browser-mock.log) | 7 pass: both AI flows and five theme/canvas/mobile checks |
| [Real-stack browser suite](../evidence/pending-browser-real.log), [final connector checks](../evidence/pending-browser-connectors.log) | All 25 applicable checks pass across these runs, 3 mock-only checks skipped. The broad run passed 24 checks and exposed a test assuming one connection; scoping it to Procurement fixes the remaining check. Both connector checks then pass |

Backend integration tests use PostgreSQL 18, Flowable, real policy checks and scripted provider/tool services. Earlier live AI, performance, API and Docker resilience evidence in [feature status](feature-status.md) was retained; those external checks were not rerun here.

## Local application

The app runs at http://localhost:8080 with the new migrations and existing database contents preserved. The normal multi-stage image build was attempted twice, but Docker Hub authentication returned `504 Gateway Timeout` ([log](../evidence/pending-image-build.log)). Local Java and frontend artifacts were packaged into the existing runtime images instead ([package](../evidence/pending-local-package.log), [app image](../evidence/pending-app-image-local.log), [stub image](../evidence/pending-stub-image-local.log)). Compose completed successfully and the application, database, identity provider and stub are healthy. Previous runtime images remain tagged `before-pending` for local recovery. Repeat the standard image build when the registry is reachable.

## Remaining scope

| Scope | Remaining work |
|---|---|
| Workflow breadth | Bounded rework and arbitrary loops; graphs remain acyclic |
| Agent breadth | Write tools, Teams and A2A; real-provider accuracy and customer-tool acceptance |
| Native Pega/LWFS conversion | Representative exports, supported semantics and source-versus-target equivalence |
| Full Camunda coverage | Explicit extensions beyond the declared supported subset and equivalence fixtures |
| Production deployment | Enterprise identity, procurement endpoint, cluster, restore exercise and CI execution |

These are future scope and environment gates. Local checks do not establish production readiness. See [S10](build/S10-ai-assistance.md), [S12](build/S12-workflow-patterns.md), [S13](build/S13-agents-mcp.md), [feature status](feature-status.md) and the [full backlog](10-full-product-backlog.md).
