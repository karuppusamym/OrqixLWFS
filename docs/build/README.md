# Orqix build specification — start here
Original specification: revision 1.0, 7 October 2026. Implementation status updated 9 October 2026; see [feature status](../feature-status.md).

The planning package (docs 01–14) explains **what** Orqix is and **why**. This folder is the developer-facing layer: **exactly what to build for R0**, with machine-readable contracts, ticket-level acceptance criteria and tests. When a planning document and a build spec disagree, the build spec wins for R0 and the planning document is corrected (see §4).

## 1. Reading order

| Role | Read |
|---|---|
| Everyone (30 min) | This page → [S8 R0 tickets and schedule](S8-r0-tickets.md) → [S1 engineering baseline](S1-engineering-baseline.md) |
| Backend | [S2 data model](S2-data-model.md), [S3 API rules](S3-api.md), [S4 compiler](S4-workflow-compiler.md), [S5 runtime](S5-runtime.md), [S6 security](S6-security.md) |
| Frontend | [S7 frontend](S7-frontend.md), [S3 API rules](S3-api.md), [doc 02 UX](../02-ux-design.md), [design board](../../design/business-workspace.svg) |
| Integration / QA | [S5 §6 effects](S5-runtime.md#6-http-node-and-effect-journal-orqixhttptask-asynchronous-job), [S6](S6-security.md), [S9 test plan](S9-test-plan.md), [S1 §6 local environment](S1-engineering-baseline.md#6-local-development-environment) |

## 2. Package contents and verification status

| Artifact | Purpose | Checked in this revision |
|---|---|---|
| [`contracts/openapi.yaml`](../../contracts/openapi.yaml) | R0 REST contract (48 operations) with permission and audit annotations | Parsed; all `$ref`s resolve; path parameters, operation IDs, permission/audit annotations and component schemas checked |
| [`contracts/workflow-v0.schema.json`](../../contracts/workflow-v0.schema.json) | Canonical workflow v0.1 | Valid 2020-12 schema; purchase example validates |
| [`contracts/connection-definition-v0.schema.json`](../../contracts/connection-definition-v0.schema.json) | Portable HTTP operation contract | Valid; procurement example validates; conditional rules rejected bad variants |
| [`backend/app/src/main/resources/db/migration/V1__orqix_core.sql`](../../backend/app/src/main/resources/db/migration/V1__orqix_core.sql) | Reference DDL (21 tables) | Applied on PostgreSQL 16.13; cross-workspace FK, test-only self-approval, `NULLS NOT DISTINCT` uniqueness and append-only privileges exercised |
| [`examples/purchase-approval.json`](../../examples/purchase-approval.json) | Template and fixture base | Validates against the schema |
| [`examples/connections/procurement.connection.json`](../../examples/connections/procurement.connection.json) | Procurement contract implemented by the stub | Validates |
| [`examples/fixtures/compiler-cases.json`](../../examples/fixtures/compiler-cases.json) | 25 compiler acceptance cases | Every patch applies; schema-layer outcomes match expectations. Semantic-layer expectations are specified, not yet executable (compiler not built) |
| [`examples/fixtures/purchase-scenarios.json`](../../examples/fixtures/purchase-scenarios.json) | Input validation, decision boundaries, end-to-end scenarios | Parsed; values reviewed against the template |
| S1–S9 | Build specifications | Cross-references and test IDs reviewed |

The table above records the original specification checks. R0 is now implemented; current results and remaining deployment gates are in [feature status](../feature-status.md). Later implemented slices are [S10 AI assistance](S10-ai-assistance.md), [S11 Camunda import](S11-camunda-import.md), [S12 workflow patterns](S12-workflow-patterns.md) and [S13 agents and MCP](S13-agents-mcp.md).

## 3. Build decisions

Defaults chosen so development can start. Each can be changed through an ADR (`docs/adr/`) before the affected ticket starts.

| ID | Decision | Where |
|---|---|---|
| BD-01 | Maven wrapper; one deployable; Spring Modulith package modules | S1 §2–3 |
| BD-02 | Version pins as in S1 §1; Spring Boot 4.0.x (not 4.1) until the spike proves Flowable 8.0.0 on 4.1 | S1 §1 |
| BD-03 | Spring `JdbcClient` + Flyway; no JPA in R0 | S1 §1 |
| BD-04 | Orqix process data stored as JSON text variables parsed with `BigDecimal` | S4 §8 |
| BD-05 | Generated BPMN uses four fixed expression forms only; conditions live in extension elements; engine bean allowlist | S4 §6 |
| BD-06 | Decision rows test one field and are disjoint; canonical UNIQUE compiles to DMN FIRST + catch-all; DMN deployed with the release and evaluated pinned | S4 §5.4, §7 |
| BD-07 | R0 approval routing uses a workspace candidate group per approval step; "requester's own manager" lookup is R1 (needs a directory source, D02) | S4 §2 |
| BD-08 | Generic run status: lifecycle + current steps + outcome label/kind (replaces the purchase-specific status enum) | S2 §4 |
| BD-09 | Effect class (`readOnly`, `idempotentWithKey`, `nonIdempotent`) belongs to the connection operation, not the workflow node | S5 §6 |
| BD-10 | Testing a workflow = running it in a test workspace with its own bindings; no in-studio "Test" button in R0 | S6 §3 |
| BD-11 | Start by workflow (active release) with optional `expectedReleaseId` | S5 §3 |
| BD-12 | Errors are RFC 9457 problem details with `code` and `correlationId` | S3 §4 |
| BD-13 | Idempotency scope includes the principal; authorization rechecked on replay | S3 §5 |
| BD-14 | Keycloak dev realm for local OIDC; PingFederate stays open gate D05 | S1 §6 |
| BD-15 | Kustomize base + overlays for OpenShift (no Helm dependency) | S1 §2 |
| BD-16 | OpenAPI 3.1 and JSON Schema 2020-12 (3.2 and v1/2026 exist but tooling support is unconfirmed) | S1 §1 |
| BD-17 | Frontend: React Router, generated openapi-fetch client, ajv form renderer, zundo undo/redo | S7 |
| BD-18 | In-app notifications only in R0 (outcome, attention, cancellation) | S5 §9 |
| BD-19 | Draft save accepts incomplete documents; validation and publish are strict | S3 §8 |
| BD-20 | Java base package `com.orqix` is a placeholder; confirm before the first merge | S1 §2 |
| BD-21 | Purchase template also notifies on rejection (exercises the simple-merge pattern) | examples |
| BD-22 | Projections maintained by one engine-wide event listener; no listeners in generated BPMN | S5 §2 |
| BD-23 | New permission `run.read-data` separates request data from run metadata (viewers see redacted runs) | S6 §3 |
| BD-24 | Unrecoverable job failures dead-letter and set `needsAttention`; recovery only via audited operator retry/reconcile | S5 §6–7 |

## 4. Inconsistencies found in the planning package and how they were resolved

| # | Finding | Resolution |
|---|---|---|
| C1 | Doc 04 business status enum (AwaitingManager, AwaitingFinance …) was specific to the purchase example | BD-08; doc 04 updated |
| C2 | Doc 04 `POST /runs` required `releaseId`, while the UX starts "a workflow" and publish selects the active release | BD-11; doc 04 updated |
| C3 | Doc 01 required "missing manager prevents submission" but the schema routes to a group | BD-07; doc 01 updated; unbound groups block publish |
| C4 | `effectPolicy` on the workflow node conflicted with doc 03's per-operation classification | BD-09; schema updated |
| C5 | Schema `security` block repeated fixed permissions and derivable groups | Replaced with `selfApproval` policy |
| C6 | `inputSchema` accepted any JSON Schema although the product allows an approved subset | Subset defined in the schema |
| C7 | Publish took an undefined `validationHash` | `draftRevision` + `acknowledgeWarnings` |
| C8 | "Default compiled as a non-overlapping fallback" was undefined | BD-06 |
| C9 | `deployment_binding.environmentId` had no environment model | One environment per installation in R0; deployment IDs stored on the release |
| C10 | `form_version`/`decision_version` listed as shared assets while forms and decisions are inline | Inline and pinned by the release in R0; shared assets R1 |
| C11 | UX "Test" action implied an unspecified test-run mode | BD-10; doc 02 updated |
| C12 | Deliverables 5 (DDL) and 7 (OpenAPI) did not exist | Created (§2) |
| C13 | Designers had no way to list groups for the approval step | `listGroups` added |
| C14 | Three-day capacity (72 h) is below the ticket-level estimate (≈ 100 h P0) | S8 §1 options; decision D01 |
| C15 | Kickoff references included dated research papers (1987–2022) with no build impact | Removed; doc 14 now lists current standards and maintained documentation only |

## 5. Still open before or during R0

From the [decision register](../13-decision-register.md): **D01** (team size and schedule option — now urgent, see S8 §1), **D02** (purchase policy accepted as sample), **D05** (PingFederate client), **D06** (OpenShift/DB access), **D07** (real procurement endpoint), **D16** (closed by the Day-1 spike). Everything else (D03, D04, D08–D15, D17, D18) gates later milestones, not R0.

## 6. Change control

- Contract changes (OpenAPI, schemas, DDL) ship in the same PR as code and tests; breaking changes need an ADR.
- A change to a build decision (BD-xx) or a fixed decision (doc 09) needs an ADR and an update here.
- Fixtures are shared: never copy them into test folders; load them from `examples/`.

Current extensions: [S14 bounded iteration](S14-bounded-iteration.md), [S15 guided conversation](S15-conversational-execution.md), [S16 message events](S16-message-events.md), [Microsoft documents](../microsoft-documents.md), and [active product tracker](../19-product-build-tracker.md).

Additional build slices: [task collaboration](S17-task-collaboration.md) and [migration equivalence acceptance](migration-equivalence-acceptance.md). The active tracker records current integration evidence rather than treating future roadmap items as completed.

Current closeout slices: [cross-file project findings](S18-project-findings.md), [task priority and aging](S19-task-priority.md), historical Microsoft version selection, published-version comparison and step notes. [Remaining work](../21-product-closeout.md) separates engineering features, customer/source acceptance and excluded infrastructure.

Gap follow-up slices: [mixed BPMN/DMN projects](S20-project-dmn.md), [permission-scoped decision evidence](S21-decision-evidence.md), and [approved document reference authoring](S22-document-authoring.md). Coordinated verification is recorded in [the tracker](../19-product-build-tracker.md).

When an IDE Java compiler is active, run coordinated verification with `./mvnw.cmd clean verify "-Dorqix.buildDirectory=target/verification" "-Dsurefire.useManifestOnlyJar=false"` from `backend/` (PowerShell). This leaves the IDE's default `target/classes` untouched; each module writes below its own ignored `target/verification`. The default build and Docker artifact path stay unchanged.

Pega Blueprint PDF import through a reviewed transcription: [S91](S91-pega-blueprint-pdf.md).

Native Pega rule archive conversion (MIG11, synthetic grammar): [S112](S112-pega-native-conversion.md), completed in [S115](S115-pega-native-completion.md); connectors, transforms, validation and case types as case definitions in [S117](S117-pega-native-round-3.md).

Resume workstreams: [message events](S23-message-events.md), [run diagram and Insights](S24-run-diagram-insights.md), [attachments and stages](S25-attachments-stages.md), and [workflow simulation](S26-workflow-simulation.md). Camunda 8 import is documented in [S11](S11-camunda-import.md). Operator [suspend and resume](S37-suspend-resume.md) and [in-flight release migration](S38-release-migration.md) implement EX04.

Scalability: [S92 scalability qualification](S92-scalability-qualification.md) (two replicas, load, failover, volume, V155).

Application pages, composed actions and sandboxed extensions (UI02g): [S111](S111-application-designer.md) (V190-V192).
Application designer completion (UI02h): [S114](S114-application-designer-completion.md) (V193).
Application designer round 3 (UI02i): [S116](S116-application-designer-round-3.md) (V194-V195).
DMN parity (DMN01): [S121](S121-dmn-parity.md) (lists and ranges, ANY/PRIORITY/COLLECT, standard DMN download and upload; no migration).

Studio Form tab with live preview (forms review slice 1, docs/26): [S120](S120-studio-form-tab.md) (frontend only).
Step forms: field access and captured fields on human steps (forms review slice 2): [S123](S123-step-forms.md) (additive contract change).
One AI conversation: clarifying questions, new edit operations, conversation saved with the draft (forms review slice 3): [S124](S124-one-ai-conversation.md) (V205).
Shared field editor for case data: case fields edited, rendered and checked like request questions (forms review slice 3): [S125](S125-shared-case-field-editor.md) (additive contract change, no migration).
Case designer page: case type drafts with a check before publishing, Cases page for working cases only (forms review, C3 and C1): [S126](S126-case-designer-page.md) (V206, additive contract change).
Workspace feature switches (administrators turn AI, cases, import, agents, apps and Kafka on or off per workspace; `409 FEATURE_DISABLED`): [S127](S127-workspace-feature-switches.md) (V208).
