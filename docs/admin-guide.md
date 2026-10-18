# Orqix administrator guide (R0)

For platform engineers and workspace/integration administrators. Detailed contracts: [docs/build](build/README.md). Local stack: [deploy/compose/README.md](../deploy/compose/README.md). OpenShift: [deploy/openshift/README.md](../deploy/openshift/README.md).

## 1. What runs

One container image (`Dockerfile`) contains the React UI and the Spring Boot application with the embedded Flowable BPMN/DMN engine. Every pod serves the API and executes background jobs. State lives only in PostgreSQL: schema `orqix` (application, sessions) and schema `flowable` (engine). There is no Redis, Kafka or message broker.

| Port | Purpose | Exposure |
|---|---|---|
| 8080 | UI and `/api/v1` | Route (TLS edge) |
| 8081 | `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` | Cluster-internal only (monitoring namespace) |

## 2. Deploying

Order matters: **migrate first, then roll out the application.**

1. **Database.** PostgreSQL 16 or later. Create roles `orqix_migrator` (owns schemas `orqix` and `flowable`) and `orqix_app` (runtime, DML only), and default privileges on `flowable` for the app role — the Compose init script `deploy/compose/postgres/` is the reference.
2. **Migration job.** Run the image with `SPRING_PROFILES_ACTIVE=migrate` and the migrator credentials. It applies Flyway migrations to `orqix` and creates or upgrades the Flowable tables, grants DML to the app role, logs `Migration complete: … tables`, and exits 0. Application pods never run DDL.
3. **Application.** Run with `SPRING_PROFILES_ACTIVE=prod` (the default in the OpenShift manifests). With fewer than two replicas there is no pod-failure tolerance; the HPA ceiling is set by the database connection budget below.

OpenShift: `oc apply -k deploy/openshift/overlays/<env>` in the stages documented in [deploy/openshift/README.md](../deploy/openshift/README.md) (prerequisites → migration Job → app). The image runs as an arbitrary non-root UID with a read-only root filesystem and a writable `/tmp`.

**Connection budget:** `pods × 15 + 5 (migration) + 10 (operations) ≤ 0.8 × max_connections`. With `max_connections = 100`, keep at most 4 pods.

## 3. Required configuration

The `prod` profile refuses to start when any required value is missing or unsafe.

| Setting | Environment variable | Notes |
|---|---|---|
| Database URL, user, password | `ORQIX_DB_URL`, `ORQIX_DB_USER`, `ORQIX_DB_PASSWORD` | Runtime role `orqix_app` |
| Migrator credentials (migration job only) | `ORQIX_DB_MIGRATOR_USER`, `ORQIX_DB_MIGRATOR_PASSWORD`; `ORQIX_APP_ROLE` | |
| Cursor signing key | `ORQIX_CURSOR_SECRET` | Random, identical on all pods; rotating it invalidates open list cursors only |
| OIDC client | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ORQIX_CLIENT_ID`, `…_CLIENT_SECRET`, and the provider issuer or explicit endpoints | Authorization code + PKCE; redirect URI `https://<host>/login/oauth2/code/orqix` |
| Machine clients | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, `…_JWT_AUDIENCES=orqix-api` | Only start/read runs are allowed with bearer tokens |
| Bootstrap administrators | `ORQIX_IDENTITY_BOOTSTRAP_PLATFORM_ADMIN_GROUPS` | IdP group values that receive Platform Admin at startup — keep this group small |
| Egress allowlist | `ORQIX_EGRESS_ALLOWED_HOSTS` (`host:port` list), `ORQIX_EGRESS_ALLOWED_PRIVATE_HOSTS` | Connection bindings to any other host are refused; `ORQIX_EGRESS_PLAIN_HTTP_HOSTS` must be empty in production |
| Connection secrets | Files in `ORQIX_SECRETS_DIR` (default `/var/run/secrets/orqix`) | One file per `secretRef`, read on every call (rotation needs no restart) |
| AI assistance (optional) | `OPENROUTER_API_KEY` | Read unprefixed on purpose. Empty means AI is off and Orqix runs rules-only. OpenShift: optional Secret `orqix-ai`, key `openrouter-api-key`. See section 7 |

All other `orqix.*` settings have safe defaults ([S1 §5](build/S1-engineering-baseline.md#5-configuration-catalog)).

## 4. Identity provider

Register a confidential OIDC client with the authorization-code flow, PKCE (S256) required, and the redirect URI above. Release a `groups` claim (configurable with `orqix.identity.groups-claim`) containing the user's group names without paths. Orqix identifies users by issuer + subject; email is display data only.

Group changes in the IdP reach Orqix at the user's next sign-in or when the session ends (absolute session limit 10 hours). Access removed inside Orqix takes effect within 60 seconds.

## 5. Setting up a workspace

R0 manages access through the API (`/api/v1`, see [OpenAPI](../contracts/openapi.yaml)); the Settings screen shows it read-only. Signed-in browser calls need the `X-XSRF-TOKEN` header.

1. **Platform admin:** `POST /admin/organizations`, then `POST /admin/workspaces` with `initialAdmins` (principals of type `idpGroup` or `subject`). Mark test workspaces with `isTest: true`; only they may allow self-approval workflows.
2. **Workspace admin:** map IdP groups to roles with `POST /workspaces/{id}/access/role-bindings` — `WORKFLOW_DESIGNER`, `PUBLISHER`, `OPERATOR`, `VIEWER`, `APPROVER`, `WORKSPACE_ADMIN`.
3. Grant `workflow.start` (the requester capability) with `POST /workspaces/{id}/access/grants`, workspace-wide or for one workflow, and `connection.manage` to integration administrators.
4. Create approval groups with `PUT /workspaces/{id}/groups/{key}` (for example `managers`, `finance-approvers`) listing IdP groups or subjects. Workflows assign approval steps to these keys; publishing fails while a referenced group is missing.

Administrator roles manage access only: they do not see request data, approve or publish unless granted those roles too.

## 6. Connections to other systems

For approved Microsoft document connections, the test panel can load a bounded complete history for an approved drive/item and lets the administrator choose the historical version explicitly before extraction. Changing the document resets the selection; unavailable pins fail visibly. Tenant consent, resource grants, host allowlists and credential rotation remain customer setup. This tool does not browse a requester's tenant or automatically refresh OAuth credentials; see [Microsoft document operations](microsoft-documents.md).

1. **Register the contract** (`connection.manage`): `POST /workspaces/{id}/connections/{key}/versions` with a definition that follows [the connection schema](../contracts/connection-definition-v0.schema.json) — operations, request/response schemas, success statuses and the effect class:
   - `readOnly` — safe to retry;
   - `idempotentWithKey` — the remote system de-duplicates on the idempotency header Orqix sends (the effect key), so retries are safe;
   - `nonIdempotent` — Orqix retries only when the request provably was not sent; otherwise an operator must reconcile.
   Choose the class from the remote API's documented behavior, never by assumption. Example: [procurement](../examples/connections/procurement.connection.json).
2. **Bind it to this environment:** `PUT /workspaces/{id}/connections/{key}/binding` with `baseUrl`, auth type (`none`, `bearerToken`, `apiKeyHeader`) and a `secretRef` — the name of the secret file, never the secret. The host must be on the egress allowlist; Orqix also refuses addresses that resolve to loopback, link-local, metadata or private ranges unless explicitly allowed.
3. **Test:** `POST …/test` calls a `testable` (read-only) operation through the real binding.

Bindings are never exported; a release export contains only the connection definitions it was published against.

## 7. AI assistance (optional)

AI can draft a workflow from a description and explain a workflow in plain language ([S10](build/S10-ai-assistance.md)). It never publishes anything: every AI draft passes the compiler and then the normal review and publish steps. Without a key, the AI entry points are hidden, and templates and manual design work as before.

**Provider and models.** Orqix uses OpenRouter's OpenAI-compatible API. The defaults keep cost low:

| Use | Model | Notes |
|---|---|---|
| Profile *Economy* (default) | `deepseek/deepseek-v3.2` | Drafts and explanations |
| Profile *Auto* | `typesafe/jev-router` | Jev Router picks the model and reasoning effort for each request, so the price varies. Also used for one escalation attempt when Economy cannot produce a valid draft (`orqix.ai.escalation-profile`, empty to disable) |
| Evaluation judge (tools only) | `anthropic/claude-haiku-5.5` | A different model family from the generator |

**Cost controls.** Each of these applies on its own:
- `orqix.ai.excluded-models` refuses expensive model families. The defaults are `anthropic/claude-opus-*`, `anthropic/claude-fable-*`, `anthropic/claude-mythos-*`, `openai/*-pro*` and `*:batch`.
- `orqix.ai.allowed-models` is an optional allowlist.
- Price caps of USD 1.00 per million input tokens and USD 3.00 per million output tokens are checked against OpenRouter pricing and sent as `provider.max_price`.
- `orqix.ai.budget.monthly-usd` (default 10.00) is the monthly budget, counted from the cost OpenRouter reports on each call. When it is spent, calls fail with `429 AI_BUDGET_EXCEEDED`.
- `orqix.ai.budget.per-request-max-usd` (default 0.50) is the per-request limit.

A profile whose model is excluded or over the caps is disabled at startup with a logged reason. Also set a credit limit on the key itself at OpenRouter. Jev Router chooses its model per request and reports that model only after the call, so the exclusion list cannot block it in advance. Its cost is still capped by the budgets above.

**Background generation.** Draft requests return `202 Accepted` and a job URL. The browser polls and lets people keep working, then offers **Open draft**. A generation stops at `orqix.ai.generation-deadline` (150 s); each provider call stops at `orqix.ai.timeout` (120 s). Explanations remain synchronous, so proxy timeouts still need to cover those calls. Provider routing uses `orqix.ai.provider-sort: throughput`.

**Job limits and recovery.** `orqix.ai.jobs` bounds threads, queue size and active jobs per person (default two per workspace). Admission is serialized across pods. Jobs interrupted by a restart fail after their deadline plus the orphan margin; their descriptions are held only in memory and are not replayed. Editing access is checked again before saving a generated draft. Terminal jobs use `orqix.idempotency.retention` (default 30 days), with live replay records protected. Cleanup keeps generated drafts, usage records and audit history.

**Data sent to the model.** Orqix sends the description, the node catalog and rules, one example, workspace group keys and names, and connection operation names and schemas. It never sends request data, bindings or URLs, secrets, user lists or audit data. Orqix stores usage metadata (model, tokens, cost, outcome) in `ai_generation` and in the audit log, never prompt or response text.

**Settings → AI** shows the status, profiles, excluded models, caps and month-to-date spend. Read-only: configuration belongs to the deployment.

**Network.** Pods need outbound HTTPS to `openrouter.ai:443`. See the AI egress note in the [OpenShift README](../deploy/openshift/README.md).

| Situation | Action |
|---|---|
| Settings → AI shows *credentials rejected* | Replace the key and restart the pods. Provider 401 and 403 responses switch AI off until then |
| `AI_PROVIDER_UNAVAILABLE` | The provider timed out or returned 429 or 5xx. Inspect the failed job; users can submit again with a new Idempotency-Key |
| `AI_BUDGET_EXCEEDED` | Wait for the next month (UTC) or raise `orqix.ai.budget.monthly-usd` |

## 7b. Request files and virus scanning

People can attach files to requests (PDF, PNG, JPEG, text, CSV, Word, Excel, PowerPoint). Limits, all under
`orqix.attachments.*`: `max-file-bytes` (10 MiB, the maximum), `max-files-per-run` (25), `max-bytes-per-run` (50 MiB),
`staged-ttl` (24 h for files chosen on a form that is never submitted), `max-staged-per-subject` (10) and
`uploads-per-minute` (30). Uploads above 256 KiB are spooled to the temporary directory (`spring.servlet.multipart.location`),
which must be writable.

**The default does not scan files.** They are stored with the status "Not scanned". Before accepting files from outside your
organization, run ClamAV (clamd) and set `orqix.attachments.clamav.enabled=true`, `...clamav.host` and `...clamav.port`
(default 3310); allow the egress to it. Files the scanner flags or cannot check are blocked from download. Alternatively provide
your own `AttachmentScanner` bean marked `@Primary`. Existing files are not rescanned. Uploaded content is kept in
PostgreSQL: include it in the database sizing and backup plan. Removing a file deletes its bytes and keeps its metadata.

## 8. Operating

**Health and metrics.** Readiness fails while the database is unreachable; liveness does not (no restart storms during a database outage). Metrics on 8081 include `orqix_runs_started_total`, `orqix_tasks_decided_total`, `orqix_effect_attempts_total`, `orqix_runs_needs_attention`, `orqix_oldest_executable_job_age_seconds` and `orqix_authz_denied_total`, plus JVM, HTTP and connection-pool metrics.

**Insights.** The *Insights* page (workflow analytics) and its three endpoints (`/workspaces/{id}/insights/summary|throughput|steps`) are available to everyone with `run.read` in the workspace: the Operator and Viewer roles, and anyone granted `run.read`. Requesters, approvers and designers without that permission do not see the navigation item and receive 403. Insights contain counts, durations, workflow and step names and request references, never request titles or data; opening a request from the page still needs access to that request. Figures are computed from the database on every call (nothing is cached) with a statement timeout of 10 seconds and a range of at most 365 days; a very large workspace may need a shorter period or one workflow, and the response is then `503 DEPENDENCY_UNAVAILABLE`. Production query capacity still requires acceptance on representative workspace data.

**Suggested alerts** (set thresholds from pilot traffic): runs needing attention above zero for 15 minutes; oldest executable job older than 5 minutes; connection pool saturation; rising authorization denials; missing backups.

**Runbooks**

| Situation | Action |
|---|---|
| Runs need attention, reason *retries exhausted* | Confirm the remote system is healthy, then **Retry** the runs (UI or `POST /runs/{id}/retry`) |
| Reason *effect unknown* | Check the remote system for the effect key or reference; **Reconcile** as applied (with the result) or not applied, then retry if needed. Never retry blindly |
| Reason *effect rejected* or *connection unavailable* | Usually credentials, contract or binding; fix the binding/secret, then retry |
| Jobs not progressing | Check `orqix_oldest_executable_job_age_seconds`, pod logs and database health. A killed pod's jobs are re-acquired by other pods after the lock time (60 s) plus the reset interval (15 s) |
| Database outage | Requests return 503 and nothing is half-committed; jobs resume after recovery. Confirm readiness recovers on all pods |
| Restore from backup | Restore to an isolated database first; before resuming jobs, reconcile in-flight effects against the remote systems, because a restore can roll back Orqix's record of work that was already done remotely |
| Secret rotation | Replace the mounted secret file; the next call uses it |

**Upgrades.** Run the new image's migration job before rolling out pods. Migrations are additive (expand/contract) so old and new pods can overlap during a rolling update. Rehearse Flowable engine schema upgrades on a restored copy first.

## 9. Security notes

- Never put credentials in workflows, connection definitions or exports; only secret references are stored.
- Audit records (sign-ins, access changes, publishes, decisions, operator actions, connection changes) are append-only for the application role.
- The management port must stay unrouted; only health and Prometheus are exposed on it.
- Production-readiness gates still open for R0 are listed in [doc 06](06-delivery-plan.md#production-release-gates) and the feature status page.

## OAuth service connections — 18 October 2026

Bindings can use `oauth2ClientCredentials` with a mounted secret containing the approved token endpoint, client ID, client secret and optional scope. Both the service and token endpoint need approved egress. [S65](build/S65-engineering-closeout-increments.md#oauth-client-credentials-in03a) documents the format, renewal/rotation, limits and deployment migrations. Credentials are provisioned through platform secret management; settings accepts only their reference. Delegated consent/refresh tokens and enterprise provider acceptance remain open.
