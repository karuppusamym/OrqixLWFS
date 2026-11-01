# ADR 0004 - Split by runtime role and by events, not into microservices or micro frontends

Status: **Accepted**. Date: 30 October 2026. The user asked whether Orqix should move to microservices or micro frontends after the Camunda/Pega comparison, and authorized this decision and the first step. Step 1 is implemented with this ADR; steps 2 to 4 are accepted direction with their own acceptance criteria and are not implemented.

## Context

The comparison with Camunda and Pega placed Orqix at "moderate" for microservices and scale: one Spring Boot application with embedded Flowable, where every pod serves users *and* runs the job executor, 14 scheduled background jobs and the Kafka consumers. The question was whether to split the product into microservices and the UI into micro frontends.

What the repository shows (30 October 2026):

| Finding | Evidence |
|---|---|
| The backend is already a verified modular monolith: 12 modules with declared allowed dependencies, checked on every build | `@ApplicationModule(allowedDependencies = ...)` in each `package-info.java`; `ArchitectureTest` runs `ApplicationModules.of(...).verify()` |
| The core guarantees depend on one local transaction | Task completion checks ownership, validates the form, appends audit and completes the engine task atomically ([03 §runtime](../03-architecture.md)); the effect journal gives one external effect per run/step/occurrence in the same database |
| Authorization is everywhere | `runtime` imports `identity` 79 times, `catalog` 88 times; a remote identity service would add a network call to each check |
| Cross-module events are barely used | One `publishEvent`/event-listener use in production code; modules call each other synchronously |
| All background work is database-coordinated | Every `@Scheduled` job claims rows with `FOR UPDATE SKIP LOCKED` and is safe on several pods; Flowable jobs use lock ownership and expiry |
| The engine executor is one switch | `flowable.async-executor-activate` drives both the BPMN and CMMN executors (Flowable 8 `ProcessEngineAutoConfiguration`, `CmmnEngineAutoConfiguration`) |
| The frontend already splits where it matters | Route-level lazy loading in `routes.tsx`; independently built and deployable Authoring and Tasks components ([hackathon-embedding](../hackathon-embedding.md)) |
| The scale limit is the database connection budget, not the code layout | `pods x 15 + 5 + 10 <= 0.8 x max_connections` caps the HPA at 4 pods ([S5 §11](../build/S5-runtime.md)) |

## Decision

Keep one codebase and one image. Improve isolation and scaling in four ordered steps; extract a service only when a module has a real reason to live apart.

### Step 1 - Separate API and worker roles (implemented)

The same image runs in one of three roles, chosen by Spring profile:

| Role | Profile | Serves users | Engine job executor (BPMN + CMMN) | Scheduled jobs and Kafka consumers |
|---|---|---|---|---|
| All-in-one (default, unchanged) | neither | yes | yes | yes |
| API | `api` | yes | **off** | **off** |
| Worker | `worker` | not routed (port open for probes) | yes, async jobs polled every 1 s | yes |

- `application-api.yaml` turns the executor off; scheduling (and so the Kafka consumer reconcile loop) is not enabled under `api`. It also turns off Spring Modulith Moments (`spring.modulith.moments.enabled: false`): the starter enables it by default, Orqix does not use its time events, and its auto-configuration carries its own `@EnableScheduling`, which would otherwise run every scheduled job on API pods. `SplitRolesTest` found this.
- `application-worker.yaml` shortens the async-job acquisition wait from Flowable's 10 s to 1 s for both engines, because an API pod no longer runs the jobs it creates. Timer acquisition keeps the 10 s default it has today.
- Startup refuses `api` together with `worker`, and either together with `migrate` (`NodeRoleGuard`).
- OpenShift: the opt-in Kustomize component `deploy/openshift/components/split-roles` turns the `orqix` Deployment into the API role and adds `orqix-worker` (label `app.kubernetes.io/component: worker`, so the Service and Route never send it traffic). The example overlay `overlays/prod-split` shows its use. The base and the dev/int/prod overlays are unchanged.
- Connection budget with the split: API pods 2 to 3 with 10 connections each (they no longer hold the job threads), workers 2 with 15 each: `3 x 10 + 2 x 15 + 5 + 10 = 75 <= 80`. Raising either ceiling needs a larger `max_connections` first.

**Rules.** An API-role deployment is invalid without at least one worker: nothing would run timers, retries, callbacks, outbound webhooks, reminders or Kafka. Synchronous steps still execute in the user's request exactly as today; only asynchronous continuations move to workers, with up to about 1 s of extra pickup delay.

**Acceptance.** Backend tests prove the `api` profile starts with the engine executors inactive and no scheduling, the `worker` profile with both active and the 1 s acquisition wait, and the guard refuses invalid combinations. `validate_manifests.py` checks the split overlay: worker not selected by the Service, Route or router/IdP policies; worker covered by monitoring ingress and connection egress; profiles, pool sizes and the connection budget. Not verified: a run on a cluster and a load test comparing the split to all-in-one. The default stays all-in-one until that load test is done.

### Step 2 - Events between modules (accepted, not implemented)

Adopt Spring Modulith application events with the JDBC event publication registry (transactional outbox) for side effects that do not need to be in the user's transaction: Insights projections, notifications, audit fan-out and outbound webhook enqueueing. Publication is recorded in the same transaction; listeners run after commit and are retried from the registry.

Acceptance: each converted path keeps its current observable outcome and ordering guarantees; an incomplete publication is retried after a restart; `ArchitectureTest` documents the event types per module. Audit entries that are part of a decision's evidence stay synchronous.

### Step 3 - Extract only migration and the AI gateway (accepted, not implemented)

Two modules have reasons to run apart, and both are loosely coupled (`migration`: identity 8 imports, no runtime dependency):

- **Migration** parses untrusted Pega/Camunda archives and PDFs, is bursty and memory-heavy, and already uses an isolated worker for PDF text. Run it as a separate deployment with its own memory limits and no egress except the database.
- **AI gateway** owns provider keys, model policy and budgets and is the only egress to model providers. A separate deployment narrows who holds the key and lets AI outages and slow calls stay away from approvals.

Start each as a role of the same image (like step 1); move to a separate artifact only if release cadence or team ownership requires it. Contracts stay in `contracts/openapi.yaml`.

### Step 4 - Close product gaps before further architecture work

Full CMMN lifecycle (WM04), COLLECT/PRIORITY hit policies (RL04), multi-currency amounts, field-level masking with audited reveal, and the production gates (OPS05: enterprise identity, high availability, restore, capacity at volume). These move the Pega/Camunda comparison more than any further decomposition.

## Alternatives rejected

| Alternative | Why not now |
|---|---|
| Full microservices (identity, catalog, runtime, integration as services) | Breaks the single-transaction task completion and the effect journal's one-effect guarantee, which would need sagas to recover; adds a network call to every authorization check; multiplies the connection budget problem; no separate teams need separate releases. Revisit only if a module needs an independent release cadence or owner |
| Replace Flowable with a distributed engine (Zeebe/Temporal) | Changes the execution model, migration semantics and all runtime evidence; see [07](../07-technology-decisions.md) |
| Micro frontends (module federation, per-team shells) | One team ships one UI; lazy routes already keep the initial bundle small, and the embeddable Authoring/Tasks components already give independent deployment where hosts need it. Revisit when a second team owns a UI area |

## Consequences

- Operators can scale request handling and background work separately and keep slow background work (HTTP retries, document generation, Kafka) from taking request threads and connections from users.
- Two deployments to monitor instead of one when the split is used. The all-in-one default keeps dev, Compose and the existing overlays unchanged.
- Asynchronous steps after a user action may start up to about 1 s later in the split layout.
- Steps 2 and 3 are prerequisites for any later extraction; until they are done, no module should be moved out of the process.
