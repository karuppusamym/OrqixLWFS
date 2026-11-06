# S132 - Secret providers with access audit (CG-C08 / CG-I04) and worker business errors (CG-E10)

Wave A of [doc 29](../29-camunda-gap-implementation-plan.md) (sections 6.9, 6.11, 9.1 CG-E10, 10). Two independent pieces in one slice: a secret provider SPI behind `SecretStore` with a secret-access audit, and business errors from external workers with a `failed` route and section error routes on "Work by an external worker" steps.

Flyway: V240, V241 (V242-V249 unused). Compiler version not bumped: the new BPMN (`check__failed` boundary, `raiseOnFailure` on `orqix:external`) is generated only when a worker step has a Failed route or `raiseErrorOnFailure`, so golden files do not move. `HttpExecutor` and the connection-definition schema are untouched.

## 1. Secret providers (CG-C08) and secret-access audit (CG-I04)

### Design

- **Reference grammar** (`SecretRef`, one regular expression shared by the API DTO, the OpenAPI schema and the database check): a plain name `crm-token` is a mounted file, as before. A scheme selects an external store: `vault:<mount>/<path>#<key>`, `aws-sm:<secret id or ARN>#<json key>`, `azure-kv:<vault>/<secret>[/<version>]#<json key>`, `gcp-sm:<project>/<secret>[/<version>]#<json key>`. `#key` is optional and picks one field of a JSON secret. Locators with `..`, `//` or a trailing `/` are refused (422 on the binding).
- **SPI** (`com.orqix.integration.secrets.SecretProvider`: `scheme()`, `enabled()`, `cacheable()`, `resolve(SecretRef)`). Providers: `FileSecretProvider` (the old `SecretStore` logic, never cached), `VaultSecretProvider` (KV v2; token auth from a token file or Kubernetes auth), `AwsSecretsManagerProvider`, `AzureKeyVaultProvider`, `GoogleSecretManagerProvider`. Each cloud provider talks to its store through a small `Client` interface; the default clients are JDK `HttpClient` implementations (no cloud SDK dependency, so the licence gate is unaffected):
  - AWS: `GetSecretValue` signed with Signature V4 (`AwsSigV4`), credentials from EKS web identity (STS `AssumeRoleWithWebIdentity`, cached until 5 minutes before expiry) or environment keys.
  - Azure: Key Vault REST 7.4 with a token from AKS workload identity (federated token exchange) or the managed identity endpoint.
  - Google: Secret Manager v1 `versions/*:access` with a metadata-server token (GKE workload identity / VM service account).
  - Vault Kubernetes auth: `auth/<mount>/login` with the service-account token; the Vault token is kept for 80 % of its lease and dropped on a 403 (one retry).
- **Resolver** (`SecretResolver`): picks the provider by scheme; a reference to a provider that is off resolves to nothing (the caller reports `CONNECTION_UNAVAILABLE`, as for a missing file). Remote values are cached in memory for `orqix.secrets.cache-ttl` (default 30 s, `0s` disables; at most 2000 entries); missing values and failures are never cached. A 401 that makes `HttpExecutor` invalidate an OAuth token now also drops the cached value of that reference (through `SecretStore.invalidateOAuth`, no `HttpExecutor` change).
- **`SecretStore`** keeps its public methods (`credential`, `read`, `invalidateOAuth`) and adds `read(ref, workspace)`; webhook, Kafka and inbound-trigger callers now pass their workspace so their resolutions are counted there.
- **Audit** (`SecretAccessAudit`): every resolution increments in-memory counters per workspace, reference, provider and UTC day (resolved, cache hit, not found, failed; last resolved/failed time), flushed every 5 s (`orqix.secrets.audit-flush-delay`) into `secret_access_count` (V240) with an additive upsert; a failed flush keeps the counts. Resolutions without a workspace use the nil UUID. No value, hash or length is stored or logged; provider exceptions carry the store name and HTTP status only, never a response body.
- **API**: `GET /workspaces/{workspaceId}/secret-access?days=1..90` (`getSecretAccess`, `connection.manage`, default 7 days) sums the counts and lists the connection bindings that use each reference. Foreign principals get 404 (tSec01), members without the permission 403.
- **Configuration** (`application.yaml`, `SecretProviderSettings`, prefix `orqix.secrets`): `cache-ttl`, `timeout` (5 s), and per provider `enabled` plus addresses/identities (`vault.address`, `namespace`, `auth`, `token-file`, `kubernetes-role`, `kubernetes-mount`; `aws.region`, `endpoint`, `sts-endpoint`; `azure.client-id`, `vault-endpoint`, `authority-host`, `identity-endpoint`; `gcp.endpoint`, `metadata-endpoint`). Everything is off by default. Admin guide: [Secret providers and secret-access audit](../admin-guide.md#secret-providers-and-secret-access-audit---5-november-2026).
- **Database**: V240 widens `connection_binding.secret_ref` to the same expression and creates `secret_access_count` (granted to the runtime role).

### Decisions taken (no user input was available)

1. Store references are accepted on **connection bindings only** (also for OAuth client-credential configuration kept as a JSON secret). Adapters, inbound triggers, Kafka clusters and outbound webhooks keep the file-name pattern and their DB checks; widening them is a follow-up.
2. Mounted files stay uncached (rotation within a call, as before); only remote stores are cached.
3. Store addresses are operator configuration and are not run through the connection egress allowlist (the allowlist governs workspace-authored targets).
4. Counting is per reference, not per binding row; the API joins the current bindings by reference. A reference shared by two bindings is one entry listing both keys.
5. No cloud SDKs: small HTTP clients behind interfaces, so tests need no cloud accounts and the dependency set does not grow.

## 2. Worker business errors (CG-E10)

### Model and compiler

- `externalTask` steps may have one `failed` route (`GraphRules`, `FailureRules.canFail`). On it the step's outputs are the failure facts `failureKind` (`workerError`), `failureCode` (the worker's code) and `failureMessage` (`FailureRules.EXTERNAL_FAILURE_OUTPUTS`); they exist only on that route (`WF_FIELDREF_NOT_GUARANTEED` / `WF_OUTPUT_NOT_GUARANTEED` otherwise), and a declared result field with one of those names clashes (`WF_FAILURE_ROUTE_OUTPUT_CLASH`). The HTTP failure outputs are unchanged.
- `externalTask.config.raiseErrorOnFailure: <errorCode>` (schema `$defs/errorCode`) is the S71 opt-in for worker steps: the step must sit in a section whose error route catches the code, cannot also have a Failed route, and `ORQIX_` is reserved (same rule codes as for calls). Worker steps may now be section members (`ScopeRules.SECTION_KINDS`); they still cannot be in handlers or in workflows with interrupting handlers.
- BPMN: the generic `<node>__failed` error boundary (`ORQIX_STEP_FAILED`) and `raiseOnFailure="CODE"` on `orqix:external`; the BPMN reader reads the attribute back (round trip).
- Simulator: `workers.<id>.failure: businessError` with optional `errorCode` (default `WORKER_ERROR`) and `message` takes the Failed route, the section error route, or needs attention.

### Runtime

- `POST .../external-jobs/{jobId}/fail` accepts `errorCode` (`^[A-Z][A-Z0-9_]{1,39}$`). It is final: `retryable: true` or an `ORQIX_` code is 422 before anything changes.
- With a code, `ExternalJobService` looks at the release: a Failed route raises `ORQIX_STEP_FAILED`, `raiseErrorOnFailure` raises that code, otherwise the request needs attention exactly as for a final failure (last error `CODE: message`). The job ends `failed` with `external_job.error_code` (V241); events `EXTERNAL_JOB_FAILED` (`routed`, `errorCode`, `raisedError`), then `STEP_FAILED` (failed route) or `ERROR_RAISED` (`source: workerError`, `workerErrorCode`); the step variable holds the failure facts.
- The engine is triggered as for a completion with an extra variable; `ExternalTaskDelegate` is now a `TriggerableJavaDelegate` whose `trigger` throws the BPMN error, which the step's boundary or the section's error boundary catches in the same command (marked as already projected, so no `STEP_INTERRUPTED`).
- `ExternalTaskDelegate` is also an engine event listener: when the engine cancels a worker step (section time limit, another step's error, the request ending) its open job (`available`/`locked`) is set to `cancelled`, so a late complete or fail gets 409.
- Retryable failures, lease expiry and final failures without a code behave as before.

### Python SDK and Studio

- `orqix_worker.BusinessError(code, message)` raised by a handler, or `Worker.fail_with_error(job, code, message)`; the code is checked locally; same idempotency key as `fail` (one final report per lease). README section "Business errors".
- Studio: worker steps get a Failed handle and route slot (canvas, BPMN notation, steps list, route dialog "When the worker reports a business error"); `canConnect` allows one Failed route and refuses it while the opt-in is on. The worker inspector explains business errors and shows the S71 "raise an error for the section's error route" control (worker wording) inside a section with an error route; worker steps can be added to sections.

## Verification

Filled in from the final run (5 November 2026, JDK 21, PostgreSQL and Vault via Testcontainers, Flowable 8):

- Compiler `WorkerBusinessErrorsTest` (5): failed route compiles to the error boundary and nothing changes without it; failure facts only on the failed route, clash with result names; section opt-in compiles (`raiseOnFailure`, `error__CODE`, no step boundary) and its blockers (conflict, not in a section, other code, reserved); simulator follows business errors (failed route, section route, attention, normal path).
- Application `SecretProvidersTest` (11, no Spring): reference grammar and path checks; resolver cache (30 s, rotation after expiry, zero TTL), audit outcomes, disabled provider; AWS Signature V4 against the AWS test-suite `get-vanilla` vector; AWS `GetSecretValue` request shape with session token, `ResourceNotFoundException`; EKS web identity via STS (one STS call reused); Azure workload identity and managed identity; Google metadata token and payload decoding, pinned versions; error messages without response bodies; Vault Kubernetes login, namespace header and re-login on 403.
- Application `SecretProvidersVaultTest` (2, real Vault `hashicorp/vault` dev server + PostgreSQL): KV v2 field and whole-secret reads, missing field/secret, rotation picked up after the cache time, audit counts through the API with no value in the response or the table; a binding with a `vault:` reference saved through the API and the database check, invalid references refused, the connection unavailable while Vault is off in the app and counted as a failure, a file reference counted with its binding key; 403 without `connection.manage`, 422 for 91 days.
- Application `ExternalWorkerErrorsTest` (5, real engine): business error takes the failed route (`STEP_FAILED`, no task for the manager, job `failed` with code, later report 409); section route (`ERROR_RAISED` with `source workerError`, `ERROR_CAUGHT`, `SECTION_LEFT`, no `STEP_FAILED`/`STEP_INTERRUPTED`); retryable failure and final failure without a code unchanged (retry scheduled, then attention, no code stored); no route needs attention, 422 for retryable/reserved/badly formed codes with the job still locked; a section time limit cancels the open job and a late completion gets 409.
- `ExternalWorkerTest` (12), `SecretLeakScenariosTest`, `SecurityMatrixTest` (new operation in tSec01), `ArchitectureTest`, `ConnectionsApiTest` pass unchanged.
- Full backend `clean verify` (compiler and application, coverage gate, packaging): BUILD SUCCESS - compiler 779 tests, application 1188 (1184 pass, the same four intentional skips).
- SDK: `python -m unittest test_orqix_worker test_agent_gateway test_native_protocols` - 38 tests OK (2 new).
- Frontend: `api:generate` + `api:check`, `tsc -b --noEmit`, ESLint on the changed files, Vitest for the Studio files touched (`ExternalTaskInspector.test.tsx` with 3 new cases, `ScopeInspectors.test.tsx`, `ScopeFrames.test.tsx`, `ops.test.ts`: 51 passed); full ESLint clean; full Vitest 196 files, 1390 tests passed.

## Not covered

Vault dynamic database credentials; store references for adapters, triggers, Kafka and webhooks; an audit view in the UI; mock-mode (frontend offline simulator) handling of `businessError`; failure-fact chips in the Studio field picker (as for calls); error handlers catching worker errors; per-code routing (one Failed route for every code; use a choice step on `failureCode`); live cloud accounts (cloud clients are verified against local HTTP stand-ins only); the Docker stack and browser journeys.
