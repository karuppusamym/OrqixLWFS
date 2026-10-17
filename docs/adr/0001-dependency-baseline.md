# ADR 0001 — Dependency baseline and compatibility spike results

Status: **Accepted** · Date: 8 October 2026 · Closes decision D16 · Ticket B01.2 (S1 section 10)

## Context

S1 section 1 fixed candidate versions and S1 section 10 defined ten spike checks (S-1 … S-10) that had to pass before
dependent tickets started. The spike was run as automated integration tests in `backend/app`
(`com.orqix.runtime.spike.EngineSpikeTest` and the API/runtime suites), against PostgreSQL 18 in Testcontainers,
migrated by the real `migrate` profile, with the application connecting as the DML-only runtime role.

## Decision: baseline versions

| Component | Version (resolved by the build) |
|---|---|
| JDK | Temurin 21.0.12.1+1 (Java 21 LTS) |
| Spring Boot | 4.0.8 (Spring Framework 7.0.9, Spring Security 7.0.7, Spring Session 4.0.5, Tomcat 11.0.24) |
| Flowable OSS | 8.0.0 (`flowable-spring-boot-starter-process`, `-dmn`) |
| Spring Modulith | 2.0.8 |
| Jackson | 3.1.5 (`tools.jackson`; annotations 2.21). Flyway brings Jackson 2.21.5 transitively for its own use only |
| JSON Schema validator | networknt `json-schema-validator` 3.0.8 (runs on Jackson 3) |
| JCS | `io.github.erdtman:java-json-canonicalization` 1.1 |
| UUIDv7 | `com.github.f4b6a3:uuid-creator` 6.1.1 |
| Flyway | 11.14.1 (+ `flyway-database-postgresql`) |
| PostgreSQL JDBC / pool | 42.7.13 / HikariCP 7.0.2 |
| PostgreSQL server | 18 (`postgres:18-alpine`) for dev/CI; reference DDL also runs on 16 |
| Micrometer | 1.16.7 (Prometheus registry) |
| Tests | JUnit Jupiter 6.0.3, AssertJ 3.27.7, Testcontainers 2.0.5, WireMock 3.13.2, ArchUnit 1.5.1, Awaitility 4.3.0 |

Spring Boot stays on 4.0.x (BD-02); 4.1.x was not tried.

## Spike results

| ID | Result | Evidence / notes |
|---|---|---|
| S-1 | **Pass** | Context starts on Java 21 with both starters; no Flowable REST classes on the classpath; IDM and event-registry engines disabled (`flowable.idm.enabled=false`, `flowable.eventregistry.enabled=false`); the web layer uses the Boot Jackson 3 `JsonMapper` (no Jackson 2 `ObjectMapper` bean). |
| S-2 | **Pass** | BPMN + DMN in one deployment with `tenantId` and category `orqix`; the DMN resource becomes a child DMN deployment whose `parentDeploymentId` is the process deployment; start by `processDefinitionId`; candidate-group task found by `taskCandidateGroup`. Restart survival is covered by T-RES-01. |
| S-3 | **Pass** | Two deployments with the same decision key (thresholds 5,000 and 10,000): the instance of deployment 1 evaluates v1. Mechanism: `DmnDecisionService.createExecuteDecisionBuilder().parentDeploymentId(<process deployment>)`. Flowable silently falls back to the *latest* decision when nothing matches the parent deployment, so the delegate first asserts that exactly one decision with that key exists under the parent deployment and fails otherwise. BigDecimal boundaries 4,999.99 → standard; 5,000, 5,000.00, 5,000.01 → financeReview. DMN namespace `https://www.omg.org/spec/DMN/20191111/MODEL/` (DMN 1.3) parses. Input entries `>= n` work with `BigDecimal` input. |
| S-4 | **Pass** | **`R<n>` yields exactly n delegate invocations** (measured for R1, R2, R3): Flowable replaces the default retries with the cycle's repetition count on the first failure and dead-letters when retries reach ≤ 1. Therefore `retryCycle(maxAttempts, delay) = "R" + maxAttempts + "/PT" + delay + "S"` (what the compiler emits; the `R2` example in S4 section 6 is wrong). A custom failed-job command (`OrqixFailedJobCommandFactory`) reproduces this logic and dead-letters non-retryable Orqix failures after **one** attempt with retries 0. `moveDeadLetterJobToExecutableJob` resumes the run. Important: the exception message stored on the job must never be null (Flowable treats a null message as "first failure" and would reset the retry count forever); the command substitutes the class name. |
| S-5 | **Pass** | Two application instances on one PostgreSQL, distinct lock owners (`orqix.runtime.lock-owner` = pod/container name), lock time 60 s and expired-job reset 15 s (production values; the in-JVM suite uses 10 s / 2 s). Property names confirmed from the Flowable 8 sources: `flowable.process.async.executor.async-job-lock-time`, `…reset-expired-jobs-interval`, `…lock-owner`, `…unlock-owned-jobs`. **Kill test (Docker, `kill -9` of the instance executing a 25 s HTTP call, T-RES-03):** the other instance finished the run 73-76 s after the kill (lock expiry 60 s + reset interval ≤ 15 s; bound 120 s), for either instance as the victim; the resend carried the original idempotency key and was answered as a replay: one order, journal attempts 2, one `EFFECT_SUCCEEDED`. **Two executors (T-RES-02, in-JVM):** 50 concurrent approvals, one effect and one completion each, jobs split 25/25. **Finding fixed:** with Flowable's default `unlockOwnedJobs=true` a graceful shutdown released the stopping pod's locked jobs *before* its job pool had drained, so the running job failed to commit (optimistic lock on the job row), recorded a spurious `EFFECT_FAILED`/`INTERNAL_ERROR` and used up a retry although the effect was already journaled; the journal prevented a second call. `flowable.process.async.executor.unlock-owned-jobs: false` makes the drain clean (GracefulShutdownTest, `resilience.py shutdown`: exit 143 after 12 s, one order, no failed attempt). A job abandoned by a crashed pod is therefore only re-acquired after lock time + reset interval, which is the S5 section 11 contract. Evidence: `evidence/resilience/`, `tools/resilience/README.md`. |
| S-6 | **Pass, with a changed mechanism** | Engine tables in schema `flowable`, application tables in `orqix`, one datasource and one transaction manager. `flowable.database-schema` is **not** used: Flowable calls `Connection.setSchema()` on the shared, Spring-bound connection, which would replace the search path for application SQL in the same transaction. Instead the pool sets the search path: pods `SET search_path TO orqix, flowable` (plus `lock_timeout 10s`, `statement_timeout 30s`); the migrate profile uses `SET search_path TO flowable, orqix` so Flowable's DDL lands in `flowable` while Flyway (default schema `orqix`) prepends `orqix` itself. Table names never collide (`act_*`/`flw_*` vs Orqix names). Pods run with `flowable.database-schema-update=false` and Flyway disabled; the runtime role cannot create tables (asserted). The migrate profile is the only DDL writer and also grants the runtime role DML on the engine tables. |
| S-7 | **Pass** | Jackson 3 with `USE_BIG_DECIMAL_FOR_FLOATS` (data binding and `JsonNode`) and `STRIP_TRAILING_BIGDECIMAL_ZEROES` disabled: `1800.07` survives parse → JSON-text process variable → evaluator; `5000.00` round-trips unchanged. networknt 3.0.8 and the JCS library coexist with Jackson 3. |
| S-8 | **Pass (test + Docker)** | `oauth2Login` with PKCE (`OAuth2AuthorizationRequestCustomizers.withPkce()`), JDBC sessions (cookie `ORQIX_SESSION`), cookie CSRF for the SPA, `/api/**` → 401 problem JSON (MockMvc tests); login against the Keycloak realm through the Compose stack verified by the lead. Notes: Boot only applies `server.servlet.session.cookie.*` to Spring Session with an embedded server, so the cookie serializer is declared explicitly; `csrf().spa()` expects a masked token in form parameters, so a non-masking `CsrfTokenRequestAttributeHandler` is used (the SPA logs out with a form POST); explicit provider endpoints (no discovery) need `orqix.identity.end-session-uri` for RP-initiated logout. |
| S-9 | **Pass** | `ApplicationModules.of(OrqixApplication.class).verify()` passes with `allowedDependencies` declared per module (S1 section 3); a real violation (catalog using a type from `identity.api`) was detected and fixed during the build. `shared` is an OPEN module. |
| S-10 | **Pass** | A global listener's write into `orqix` and the engine's rows share the Spring transaction: a forced rollback removes both; a commit keeps both. |

Additional confirmation: the engine **expression bean allowlist** works through `SpringProcessEngineConfiguration.setBeans(Map)`
(set in an `EngineConfigurationConfigurer`): `orqixRoute` resolves, while existing Spring beans such as `clock`,
`jdbcClient` and `dataSource` fail with "Cannot resolve identifier".

## Consequences

* The compiler keeps `R{maxAttempts}`; S4 section 6's `R2` example should be corrected to `R3` for three attempts.
* `database-schema` must never be set; the search-path settings are part of the deployment contract (pods and migration
  job). The OpenShift migration Job must run with the `migrate` profile.
* S-5 is closed: two engines, kill mid-job and graceful stop mid-job were run in-JVM (Testcontainers) and on the Compose stack with two instances
  (`tools/resilience`, evidence in `evidence/resilience/`). `flowable.process.async.executor.unlock-owned-jobs=false` is part of the
  deployment contract (graceful shutdown must drain, not release, the running jobs).
