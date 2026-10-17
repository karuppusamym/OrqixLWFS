# orqix-app

Spring Boot 4.0.8 application with embedded Flowable 8.0.0 (BPMN + DMN): identity and policy, workflow catalog and
publishing, runtime (tasks, decisions, HTTP effects with the effect journal), notifications, audit and the `/api/v1`
REST API (`contracts/openapi.yaml`). Specifications: [docs/build](../../docs/build/README.md); version and spike
findings: [ADR 0001](../../docs/adr/0001-dependency-baseline.md).

## Run locally

Requires JDK 21 and the Compose dependencies (`cd deploy/compose && docker compose up -d postgres keycloak procurement-stub`).

```bash
cd backend
./mvnw -B -pl compiler install -DskipTests                     # once, or after compiler changes
./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=migrate,dev   # Flyway + dev seed + engine schema, then exits
./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=dev           # app on :8080, management on :8081
```

The `dev` profile's defaults match `deploy/compose` (database `orqix_app`/`orqix_migrator`, Keycloak realm
`orqix-dev` on `localhost:8180`, procurement stub on `localhost:8095`, secrets in `deploy/compose/secrets`). Sign in
with the dev users listed in [S1 §6.1](../../docs/build/S1-engineering-baseline.md#61-dev-realm-and-seed-b013-b023);
their dev-only passwords are in `deploy/compose/keycloak/orqix-dev-realm.json`. For the UI, run the Vite dev server
(`frontend`, port 5173), which proxies to this application.

## Profiles

| Profile | Purpose |
|---|---|
| `dev` | Local run against Compose; dev seed; plain HTTP allowed only to the stub; session cookie not `Secure` |
| `test` | Integration tests (Testcontainers PostgreSQL) |
| `migrate` | Applies Flyway (`orqix`) and the Flowable schema (`flowable`), grants DML to the app role, then exits — the only DDL writer |
| `prod` | OpenShift default; refuses to start when required configuration is missing or unsafe |

Environment variables are listed in the [admin guide](../../docs/admin-guide.md#3-required-configuration).

## Tests

```bash
./mvnw -B -ntp -pl app -am verify      # compiler + application tests (Docker must be running for Testcontainers)
```

| Test class | Covers |
|---|---|
| `EngineSpikeTest` | Compatibility spike S-1…S-10 (ADR 0001) |
| `IdentityApiTest`, `SecurityMatrixTest` | OIDC session, CSRF, logout, generated cross-workspace sweep (T-SEC-01/11), bearer scope (T-SEC-10) |
| `CatalogApiTest` | Drafts/ETag, validation, publish idempotency, releases, export (T-API-02/03) |
| `PurchaseScenariosTest`, `RunsAndTasksTest` | All purchase scenarios, tasks, claims/decisions races, notifications, events (T-RUN, T-TASK) |
| `EffectsTest` | HTTP outcome classification, retries, unknown outcomes, operator recovery (T-EFF) |
| `ConnectionsApiTest`, `AccessApiTest` | Connections, egress policy (T-SEC-07), access administration |
| `DatabaseTest` | Migrations, privileges, composite foreign keys (T-DB-01/02) |
| `ArchitectureTest` | Spring Modulith module rules and ArchUnit engine-access rules |

Every integration-test response is validated against `contracts/openapi.yaml` (`support/OpenApiContract`).
