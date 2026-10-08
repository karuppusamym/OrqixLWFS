# Technology recommendations and evidence
Sources checked 7 October 2026. Recommendations below are engineering judgments, not vendor performance claims.

## Keep the supplied stack

| Layer | Recommendation | Reason / delivery choice |
|---|---|---|
| Runtime | Java 21 + Flowable OSS 8.x + compatible Spring Boot 4.x | Reuse process/decision semantics in one Java service |
| UI | React + TypeScript + Vite | Keep the requested frontend, compile static assets |
| Business canvas | React Flow / XYFlow | Custom business nodes; not a workflow runtime |
| BPMN | bpmn-js | Viewer/import inspection first; advanced editing later |
| Components/style | Tailwind + shadcn/ui | Reuse primitives and focus on tasks/forms |
| State | TanStack Query + Zustand | Server data in Query; draft/editor state in Zustand |
| Editors/layout | Monaco and ELK.js | Lazy-load and defer until needed; no day-one dependency on advanced editor |
| Database | PostgreSQL, approved supported release | Durable engine and application state; no SQLite runtime |
| Migrations | Flyway for app metadata; controlled engine upgrade | One deployment migration job |
| Forms | JSON Schema plus small UI schema | Restricted renderer first; no large custom form-builder platform |
| Authentication | Spring Security + PingFederate OIDC | Reuse enterprise identity; JDBC sessions for multi-pod browser access |
| Integration | Java HTTP client + approved connector delegates | Minimal mandatory dependencies; external language-neutral APIs |
| MCP | Official Java SDK when feature is built | Pin SDK/protocol/JSON module compatibility; remote transport only initially |
| Observability | Spring metrics + OpenTelemetry integration | Existing enterprise log/metric/trace destinations |
| Deploy | Container image + Compose + OpenShift manifests | Same application core from local to target environment |
| Testing | JUnit Jupiter, PostgreSQL integration tests, browser E2E | Prove engine behavior and security boundaries |

No mandatory Kafka, Redis, Python, Temporal, vector database, search cluster or separate API gateway. Add these only for a measured requirement. OpenShift, enterprise identity, managed database support and model/API usage may have costs even when application libraries are open source.

## Verified compatibility point

Flowable 8.0.0 release notes explicitly move to Spring Boot 4 / Spring Framework 7 and Jackson 3; Boot 3 is no longer supported in that release. Use Java 21 as requested, then lock the exact compatible dependency set after a startup/deploy/task/restart smoke test. Do not mix Flowable 8 with a copied Boot 3 starter. [Flowable release notes](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0)

Spring's current system requirements include Java 21 within its supported Java range. That alone does not establish compatibility for every plugin or SDK; the dependency spike must verify those together. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)

Exact patch versions are intentionally not called “tested” in this planning package. Pin Maven BOMs, frontend lockfile and container digests at implementation kickoff, review security/support status and record them in a dependency manifest.

## Open-source boundaries

Flowable's public engine repository provides BPMN, CMMN and DMN engines under Apache 2.0. This supports the engine choice; it does not mean every commercial Flowable designer, work application or enterprise connector is included. Build the Orqix UI and integrations around the OSS engine APIs. [Flowable engine repository](https://github.com/flowable/flowable-engine)

XYFlow documents its React Flow core as MIT licensed; paid examples/support are separate offerings. Use core features and project-owned node designs. [XYFlow open-source statement](https://xyflow.com/open-source)

bpmn-js has its own license text, including retention of its visible bpmn.io watermark. Do not categorize it as plain MIT or remove that attribution as a branding change. [bpmn-js license](https://github.com/bpmn-io/bpmn-js/blob/develop/LICENSE)

The official MCP Java SDK is MIT licensed and offers Java client/server support. Select a released version and compatible transport; do not build against an evolving draft protocol implicitly. [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)

These checks cover the major decision points, not a complete transitive license audit. Produce an SBOM and inspect exact shipped packages/images before release.

## Alternatives and why they are not the primary choice

| Alternative | Assessment for this request |
|---|---|
| Camunda 8 runtime | Viable if enterprise licensing is desired, but not the default for this OSS-focused Flowable mandate |
| Camunda 7 Community | Avoid as a new foundation given its announced community end-of-life |
| n8n-style platform | Borrow visual UX ideas; using another runtime conflicts with the single-engine requirement |
| Temporal | Strong code-oriented orchestration is a different product direction; not needed alongside Flowable here |
| Custom Node/Python graph runner | Fast-looking start but creates durable execution and migration semantics to maintain; reject |
| Full microservices | Adds distributed deployment/transactions too early; start with module boundaries |

Camunda states that production use of its current Self-Managed distribution requires the relevant production licensing. Its published Community 7 end-of-life announcement says releases/security patches end after the final 7.24 release in October 2025. These are decision inputs, not a comparison of execution quality. [Camunda licensing](https://docs.camunda.io/docs/self-managed/components/orchestration-cluster/core-settings/configuration/licensing/), [Camunda 7 timeline](https://camunda.com/blog/2025/02/camunda-7-enterprise-end-of-life-extension/)

## AI and agent integration decisions

Keep AI optional. First AI capability should generate a validated draft through one approved provider adapter, with no engine authority. Later add provider selection, quotas, cost metadata and prompt versions. Hosted providers and locally run models have different cost/data requirements; choose after enterprise constraints are known.

HTTP agent invocation is language-neutral. Python agents remain external services. MCP tools are registered operations with permission checks, schema snapshots, timeouts and audit. Remote descriptions are untrusted and cannot expand capabilities.

Teams starts with notification plus a link to the authenticated task inbox. Embedded actionable approvals require verified application permissions, authenticated callbacks, replay protection and the same task authorization. Do not base the design on an assumed anonymous webhook approval.
