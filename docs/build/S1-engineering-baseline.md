# S1 — Engineering baseline
Build specification · revision 1.0 · 7 October 2026 · status: ready for kickoff (pins confirmed by spike B01.2)

This document fixes the toolchain, repository layout, module boundaries, configuration, local environment, pipeline and engineering conventions. Product rationale lives in docs 01–13; this is what engineers set up on Day 1.

## 1. Version pins

Pins below were checked against vendor sources on 7 October 2026. "Candidate" means the spike (§10) must pass before the version is recorded as the baseline in `docs/adr/0001-dependency-baseline.md` (closes decision D16).

| Component | R0 pin | Evidence checked 2026-10-07 | Rule |
|---|---|---|---|
| Java | 21 LTS (Temurin or Red Hat build) | Mandated by the master prompt | Runtime image `ubi9/openjdk-21-runtime` |
| Spring Boot | **4.0.x, latest patch** (4.0.8 at review) | [4.0.8 and 4.1.1 released 20 Aug 2026](https://spring.io/blog/2026/08/20/spring-boot-4-0-8-available-now/) | Flowable 8.0.0 was built on Spring Framework 7.0. Trial 4.1.x only after the spike passes on 4.0.x; record the result |
| Flowable OSS | **8.0.0** (`flowable-spring-boot-starter-process`, `-dmn`) | [GitHub release, 27 Feb 2026](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0): Boot 4 / Framework 7, Jackson 3 default with a Jackson 2 compatibility layer | No 8.0.x patch existed at review; check releases at kickoff |
| Spring Modulith | 2.0.x (2.0.8 at review) | [Release post, 26 Aug 2026](https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/) | 2.0.x pairs with Boot 4.0; use 2.1.x if Boot moves to 4.1 |
| PostgreSQL | Enterprise HA major, **16 or later**; dev/CI use the same major (18 if free choice) | [18.6 released 13 Aug 2026](https://www.postgresql.org/support/versioning/); reference DDL executed on 16.13 | DDL uses `NULLS NOT DISTINCT` (PG 15+) |
| Node.js (build only) | 24 LTS | Node 24 Active LTS until 20 Oct 2026, maintenance to Apr 2028; Node 26 enters LTS 28 Oct 2026 ([schedule](https://github.com/nodejs/Release)) | Move CI to 26 after its LTS date |
| Vite | 8.x | [Vite 8 stable, 12 Mar 2026](https://vite.dev/blog/announcing-vite8) (Rolldown bundler) | |
| React, TypeScript, @xyflow/react, TanStack Query, Zustand, Tailwind, shadcn/ui, React Router | Current stable majors at kickoff, locked by `package-lock.json` | Mandated stack; versions fixed by lockfile, not by this document | Record majors in ADR 0001 |
| Keycloak (local OIDC only) | 26.x latest (26.7 at review) | Keycloak has no LTS; only latest receives fixes | Never a production component; PingFederate is D05 |
| OpenAPI | **3.1** | 3.2.0 published Sep 2025; generator/validator support lags | Revisit when openapi-typescript and the response validator support 3.2 |
| JSON Schema | **2020-12** | A v1/2026 release is published; validator support unconfirmed | OpenAPI 3.1/3.2 use 2020-12; keep one dialect |

Supporting libraries (candidates, confirmed by spike S-7):

| Need | Choice | Why |
|---|---|---|
| JSON Schema validation (backend) | networknt `json-schema-validator` (2020-12) | Mature 2020-12 support. If it requires Jackson 2, keep Jackson 2 only for validation; never mix Jackson majors in DTO mapping |
| Canonical JSON hash | RFC 8785 (JCS) implementation, e.g. `io.github.erdtman:java-json-canonicalization` | Standard canonical form for `sourceHash`/`semanticHash` |
| UUIDv7 | `com.github.f4b6a3:uuid-creator` | Time-ordered keys for index locality (Java 21 has no v7) |
| Outbound HTTP | JDK `java.net.http.HttpClient` | No extra dependency; redirects disabled |
| DB access | Spring `JdbcClient` + Flyway | Explicit SQL, JSONB-friendly; **no JPA in R0** (BD-03) |
| Architecture tests | Spring Modulith `verify()` + ArchUnit | Enforce module and engine-access rules |
| Backend tests | JUnit Jupiter, AssertJ, Testcontainers (versions from the Boot BOM), WireMock | Real PostgreSQL; controlled HTTP faults |
| Frontend | openapi-typescript + openapi-fetch, ajv (2020 dialect), zundo, Vitest, Testing Library, Playwright, @axe-core/playwright | Generated API types, schema-driven forms, undo/redo, a11y checks |

## 2. Repository layout

```
/
├── backend/                         Maven reactor (./mvnw), one deployable jar
│   ├── pom.xml                      parent: Boot 4.0.8 parent, version pins, dependency management
│   ├── compiler/                    orqix-compiler: canonical model, validation rules, BPMN/DMN generation,
│   │                                evaluators, JCS hashing — pure Java (no Spring/Flowable/JDBC), golden files
│   └── app/                         orqix-app (target/orqix-app.jar)
│       ├── src/main/java/com/orqix/
│       │   ├── OrqixApplication.java
│       │   ├── shared/              problem details, ids, idempotency, pagination, json, clock
│       │   ├── identity/            OIDC login, subjects, policy/authz, access admin API
│       │   ├── catalog/             workflows, drafts, releases, templates, export
│       │   ├── runtime/             engine facade, start, tasks, delegates, listeners, projections, events, recovery
│       │   ├── integration/         connections, bindings, secrets, egress policy, HTTP executor, effect journal
│       │   ├── notification/        in-app notifications
│       │   └── audit/               append-only audit writer
│       ├── src/main/resources/
│       │   ├── application.yaml, application-dev.yaml, application-migrate.yaml
│       │   ├── db/migration/        V1__orqix_core.sql (moved from contracts/database at B01.4),
│       │   │                        V2__spring_session.sql (verbatim from the jar), V3__spring_session_grants.sql
│       │   └── db/dev-seed/         R__dev_seed.sql (dev profile only)
│       └── src/test/                integration tests (fixtures read from /examples)
├── frontend/                        Vite + React + TypeScript
│   └── src/{app,api,features,components,lib,stores}
├── contracts/                       openapi.yaml, *.schema.json, database/ (until B01.4)
├── examples/                        canonical workflows, connection definitions, fixtures
├── deploy/compose/                  docker-compose.yml, keycloak realm, postgres init
├── deploy/openshift/                kustomize base + overlays (dev, int, prod)
├── tools/procurement-stub/          small Spring Boot app used only in dev/E2E
├── tests/e2e/                       Playwright suites
├── docs/                            planning (01–14), build/ (S1–S9), adr/, user-guide.md, admin-guide.md, feature-status.md
└── Dockerfile                       multi-stage: node build → maven package → UBI runtime
```

Java base package `com.orqix` is a placeholder until the organisation confirms its namespace (BD-20). Change it before the first merge or not at all.

The compiler is a separate Maven module so that "no Spring, Flowable, JDBC or network in the compiler" is enforced by the build, not only by ArchUnit. The JSON Schemas in `/contracts` are packaged into its jar (`orqix/schemas/`) by Maven resource configuration — there is no hand-made copy.

**Resolved versions at kickoff (B01.1, 7 October 2026):** Spring Boot 4.0.8 (Spring Framework 7.0.9, Spring Security 7.0.7, Spring Session 4.0.5, Jackson 3.1.5, JUnit Jupiter 6.0.3, Testcontainers 2.0.5, Flyway 11.14.1, PostgreSQL JDBC 42.7.13), Flowable 8.0.0, Spring Modulith 2.0.8, networknt json-schema-validator 3.0.8 (Jackson 3), java-json-canonicalization 1.1, uuid-creator 6.1.1, ArchUnit 1.5.1, WireMock 3.13.2, Maven 3.9.16 via wrapper, Temurin JDK 21.0.12. The skeleton resolves and compiles; behavioral confirmation is spike B01.2 (ADR 0001).

## 3. Backend module rules

| Module | May depend on | Must not |
|---|---|---|
| shared | — | depend on any other module |
| compiler | shared | import Spring, Flowable, JDBC or HTTP classes (pure, unit-testable) |
| identity | shared, audit | call Flowable |
| catalog | shared, identity, compiler, integration (read connection definitions), audit | call Flowable except through `runtime`'s deployment port |
| runtime | shared, identity, catalog, compiler (model only), integration, notification, audit | expose Flowable types outside the module |
| integration | shared, identity, audit | call Flowable APIs |
| notification, audit | shared | depend on business modules |

Enforced by Spring Modulith `ApplicationModules.verify()` plus ArchUnit rules:

1. Workflow engine imports belong to `com.orqix.runtime..`. The separate CMMN execution adapter `com.orqix.cases.CaseService` may use only public `org.flowable.cmmn.api..`, `org.flowable.task.api..` and `org.flowable.identitylink.api..` APIs. No other case-module class may import Flowable; controllers remain in `..api..` and expose only application DTOs. Architecture tests enforce both boundaries.
2. No repository method loads a workspace-scoped row without a `workspaceId` parameter (naming rule `findByWorkspaceIdAnd…` or explicit SQL review checklist).
3. Controllers live in `<module>.api` and call application services, never repositories.
4. Nothing outside `integration` reads secret files.

## 4. Frontend structure

```
frontend/src/
├── app/            router, providers (QueryClient, auth bootstrap), layout shell, error boundary
├── api/            schema.d.ts (generated, committed), client.ts (openapi-fetch + CSRF + problem parsing), hooks per area
├── features/       home, start, tasks, requests, runs, workflows, studio, settings, notifications
├── components/     ui/ (shadcn), common/ (StatusBadge, EmptyState, ProblemAlert, Money, RelativeTime …)
├── lib/            form renderer (ajv 2020), money/date formatting, keyboard helpers
└── stores/         studio draft store (Zustand + zundo temporal history)
```

`src/api/schema.d.ts` is regenerated from `contracts/openapi.yaml` by `npm run api:generate`; CI fails if the committed file differs.

## 5. Configuration catalog

All Orqix properties live under `orqix.*`, bound to `@ConfigurationProperties` records validated at startup. The `prod` profile fails fast on missing or unsafe values.

| Property | Default | Notes |
|---|---|---|
| `orqix.identity.registration-id` | `orqix` | Spring OAuth2 client registration |
| `orqix.identity.groups-claim` | `groups` | IdP claim with group values |
| `orqix.identity.bootstrap-platform-admin-groups` | empty | IdP group values granted PLATFORM_ADMIN at startup (`source=bootstrap`) |
| `orqix.identity.session-idle-timeout` / `session-absolute-timeout` | `30m` / `10h` | Absolute timeout enforced by a filter on session creation time |
| `orqix.policy.cache-ttl` | `60s` | Upper bound for permission changes to reach other pods |
| `orqix.idempotency.retention` | `30d` | |
| `orqix.egress.allowed-hosts` | empty | `host:port` list; connection bindings must match |
| `orqix.egress.allowed-private-hosts` | empty | Private/internal hosts explicitly approved |
| `orqix.egress.plain-http-hosts` | empty | Dev only. `prod` profile refuses to start if non-empty |
| `orqix.secrets.dir` | `/var/run/secrets/orqix` | One file per `secretRef`; read on use (rotation without restart) |
| `orqix.limits.draft-max-bytes` / `request-max-bytes` / `json-max-depth` | `262144` / `65536` / `32` | |
| `orqix.runtime.lock-owner` | `${HOSTNAME}` | Flowable job lock owner, unique per pod |

Platform properties (names marked † are confirmed or corrected by spike S-5/S-6):

| Property | Pods | Migration job | Notes |
|---|---|---|---|
| `spring.flyway.enabled` | false | true | `default-schema=orqix`, placeholder `app_role` |
| `flowable.database-schema-update` † | false | true | Engine schema `flowable` (S-6) |
| `flowable.async-executor-activate` | true | false | |
| `flowable.process.async.executor.async-job-lock-time` † | 60s | — | Must exceed the 30 s HTTP budget plus journal writes |
| `flowable.process.async.executor.reset-expired-jobs-interval` † | 15s | — | |
| `flowable.history-level` | audit | — | |
| `flowable.check-process-definitions` | false | false | No classpath auto-deploy |
| `spring.datasource.hikari.maximum-pool-size` | 15 | 5 | Budget in S5 §11 |
| `server.shutdown` / `spring.lifecycle.timeout-per-shutdown-phase` | graceful / 45s | — | OCP `terminationGracePeriodSeconds: 60` |
| `management.server.port` | 8081 | — | Probes `/actuator/health/liveness`, `/readiness`; Prometheus |
| `spring.session.timeout` / `spring.session.jdbc.initialize-schema` | 30m / never | — | Tables come from V2 |
| `server.servlet.session.cookie.name` | ORQIX_SESSION | — | Secure, HttpOnly, SameSite=Lax |

Profiles: `dev` (local compose, dev seed, plain HTTP to the stub), `test` (CI/Testcontainers), `migrate` (runs Flyway + engine schema, then exits non-web), `prod` (default in OpenShift).

## 6. Local development environment

`deploy/compose/docker-compose.yml` provides:

| Service | Port | Purpose |
|---|---|---|
| postgres (same major as target) | 5432 | DB `orqix`; init script creates roles `orqix_migrator`, `orqix_app` and schemas `orqix`, `flowable` |
| keycloak 26.x (`start-dev --import-realm`) | 8180 | Realm `orqix-dev` from `deploy/compose/keycloak/orqix-dev-realm.json` |
| procurement-stub | 8095 on the host (8090 inside the Compose network) | Contract in §6.2 |
| orqix (optional) | 8080 / 8081 | Engineers usually run the backend from the IDE with profile `dev` |

The Vite dev server (5173) proxies `/api`, `/oauth2`, `/login`, `/logout` to 8080 with forwarded headers. Both `http://localhost:8080/login/oauth2/code/orqix` and `http://localhost:5173/login/oauth2/code/orqix` are registered redirect URIs.

### 6.1 Dev realm and seed (B01.3, B02.3)

Realm clients: `orqix-web` (confidential, authorization code + PKCE S256, `groups` claim mapper without full path) and `orqix-machine-demo` (client credentials, for the API-start test). User passwords are dev-only values kept in the realm file; never reuse them anywhere.

| User | IdP groups | Seeded meaning in workspace **Purchasing** |
|---|---|---|
| pat.platform | orqix-platform-admins | Platform Admin (bootstrap); no business data access |
| wade.admin | orqix-purchasing-admins | Workspace Admin + `connection.manage` grant |
| dana.designer | orqix-purchasing-designers | Workflow Designer |
| paul.publisher | orqix-purchasing-publishers | Publisher |
| olga.operator | orqix-purchasing-operators | Operator |
| vic.viewer | orqix-purchasing-viewers | Viewer |
| rhea.requester | orqix-purchasing-requesters | `workflow.start` |
| mani.manager, max.manager | orqix-purchasing-managers, orqix-purchasing-requesters | Approver; group `managers`; can also request (self-approval test) |
| fin.finance | orqix-purchasing-finance | Approver; group `finance-approvers` |
| fred.facilities | orqix-facilities-requesters | Member of workspace **Facilities** only (cross-workspace tests) |
| otto.outsider | — | Authenticated, member of nothing |

The dev seed (`db/dev-seed/R__dev_seed.sql`, loaded only by profile `dev`) creates organization `demo`; workspaces `purchasing`, `purchasing-sandbox` (`is_test=true`, self-approval workflows allowed) and `facilities`; the role bindings, grants and groups above; connection `procurement` v1 from `examples/connections/procurement.connection.json` with a binding to the stub; and a `purchase-approval` draft from the template (unpublished — publishing v1 is part of the demo).

### 6.2 Procurement stub contract (`tools/procurement-stub`, B05.5)

A development and test fixture only. It is never evidence of a real integration; the demo uses the approved endpoint (D07).

| Endpoint | Behavior |
|---|---|
| `POST /orders` (header `Idempotency-Key` required) | New key → 201 `{"id":"PO-<n>","status":"CREATED"}`. Same key + same body → 200 with the original order. Same key + different body → 422. Missing key → 400 |
| `GET /orders?idempotencyKey=…` | 200 with the order or 404 (used for manual reconciliation evidence) |
| `GET /health` | 200 `{"status":"UP"}` |
| `POST /__faults` `{"mode": …, "times": n}` | Modes: `none`, `delay` (`delayMs`), `status500`, `status400`, `status429`, `commitThenTimeout` (creates the order, then sleeps past the client timeout), `dropConnection` (closes the socket before reading) |
| `GET /__orders`, `POST /__reset` | Test assertions and cleanup |

## 7. Build and pipeline

| Stage | Command / tool | Fails on |
|---|---|---|
| 1 Contracts | `npx @redocly/cli lint contracts/openapi.yaml`; schema + fixture check (`backend` test `ContractFixturesTest`) | Lint errors, invalid examples, fixture drift |
| 2 Backend | `./mvnw -B verify` (unit, Modulith/ArchUnit, golden files, Testcontainers integration) | Any test failure; coverage gate on `compiler` ≥ 90 % lines |
| 3 Frontend | `npm ci && npm run api:check && npm run typecheck && npm run lint && npm test && npm run build` | Type errors, generated-API drift, test failure |
| 4 Image | `docker build` → digest; CycloneDX SBOM (Maven + npm); vulnerability scan with the enterprise scanner; secret scan | Critical/High per enterprise policy, any detected secret |
| 5 E2E | Compose stack with the built image → `npx playwright test` (includes axe checks) | Any failure |
| 6 Resilience (nightly + pre-release) | Restart, two-instance, kill-during-HTTP suites (S9 §5) | Lost accepted work, duplicate effect, stuck job |
| 7 Deploy int | Kustomize overlay `int`; migration Job, then rollout; smoke test | Failed migration, readiness timeout, smoke failure |

The CI product (GitHub Actions, Jenkins or OpenShift Pipelines) follows D15; the stages and gates above are tool-neutral.

## 8. Conventions

- **Branching:** trunk-based; short-lived branches `B03.2-compiler-rules`; one reviewer; squash merge; commit subject starts with the ticket ID.
- **Java:** records for DTOs and canonical model; no Lombok; constructor injection; `Clock` injected; all time in UTC `Instant`; money as `BigDecimal` only.
- **SQL:** every query on a workspace-scoped table includes `workspace_id`; migrations are append-only and expand/contract; no `DROP` in the same release that stops using a column.
- **API:** contract first — change `contracts/openapi.yaml` in the same PR as the controller; problem codes come from the enum.
- **TypeScript:** strict mode; no `any` in feature code; server state only in TanStack Query; studio draft only in the Zustand store.
- **Logging:** structured JSON; include `correlationId`, `workspaceId`, `runId`, `nodeId` when known; never request/response bodies, tokens or secret values.
- **ADRs:** `docs/adr/NNNN-title.md` for any change to a fixed decision (doc 09) or a build decision BD-xx.

**Definition of Ready:** ticket names requirement IDs, acceptance examples (Given/When/Then), API/DB changes, permission, failure behavior and test IDs (S8 already does this for R0).
**Definition of Done:** doc 06 definition plus: contract updated, tests listed in the ticket pass in CI, audit/permission annotations present, `docs/feature-status.md` updated, no new lint or analyzer warnings.

## 9. Observability baseline

Micrometer metrics with Prometheus endpoint; OpenTelemetry tracing via Boot's OTel integration (exporter configured per environment). Required custom metrics: `orqix_runs_started_total`, `orqix_tasks_decided_total{action}`, `orqix_effect_attempts_total{class,outcome}`, `orqix_runs_needs_attention` (gauge), `orqix_oldest_executable_job_age_seconds` (gauge), `orqix_authz_denied_total{permission}`. Correlation ID: accept `X-Correlation-Id` if it matches `^[A-Za-z0-9-]{8,64}$`, otherwise generate; echo in responses and problem bodies.

## 10. Compatibility spike (ticket B01.2, first four hours)

Stop and re-plan if S-1 to S-4 fail. Record exact versions and results in ADR 0001.

| ID | Check | Pass criterion |
|---|---|---|
| S-1 | Boot 4.0.x + Flowable 8.0.0 process and DMN starters on Java 21 + PostgreSQL | Starts; no Flowable REST/IDM endpoints mapped; Jackson 3 used by the web layer |
| S-2 | Deploy a generated BPMN + DMN in one tenant deployment; start by `processDefinitionId`; candidate-group user task; restart JVM | Task survives restart and completes |
| S-3 | Decision pinning: deployment 1 has decision v1, deployment 2 has v2 with the same key | Instance of deployment 1 evaluates v1 (via `parentDeploymentId` on the DMN API or `sameDeployment`); 4,999.99 / 5,000 / 5,000.01 route correctly with `BigDecimal` |
| S-4 | Async service task with `failedJobRetryTimeCycle`; custom failed-job command; dead-letter → executable | Exactly `maxAttempts` delegate invocations; non-retryable failure dead-letters immediately; resume works |
| S-5 | Two engine instances on one database; kill one mid-job | Each job executed once; expired lock recovered within lock time + reset interval; property names confirmed |
| S-6 | Engine tables in schema `flowable`, app tables in `orqix`, one datasource and transaction manager; `migrate` profile | Pods start with schema update disabled; migration job is the only writer of DDL |
| S-7 | JSON Schema validator + JCS library + Jackson 3 coexist; `BigDecimal` preserved end-to-end | `1800.07` survives parse → variable → evaluator unchanged |
| S-8 | Spring Security `oauth2Login` against the Keycloak realm, JDBC sessions, cookie CSRF for SPA | Login works on 5173 via proxy; `/api/**` returns 401 problem JSON, not a redirect |
| S-9 | Spring Modulith 2.0.x `verify()` on the package layout | Passes; a deliberate violation fails |
| S-10 | Writes from a task listener share the Spring transaction | Forced rollback removes both engine and `orqix` rows |
