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

**One server, no internet:** the sovereign single-VM kit (`deploy/single-vm`, guide [sovereign-deployment.md](sovereign-deployment.md)) runs the same image with PostgreSQL, Keycloak and a TLS proxy on one Linux VM, with preflight, backup, restore and upgrade scripts.

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
3. Grant `workflow.start` (the requester capability) with `POST /workspaces/{id}/access/grants`, workspace-wide or for one workflow, and `connection.manage` to integration administrators. Other grant-only permissions are listed under [Grant-only permissions](#grant-only-permissions---19-october-2026).
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

## 8b. Scaling and capacity

Evidence: [S92 scalability qualification](build/S92-scalability-qualification.md). Everything below about throughput was **measured on one shared laptop** and is relative guidance, not a production capacity statement.

**Replicas.** Every replica is identical: it serves the UI and API and runs background jobs. Run **at least two replicas for availability**. Add more for throughput only when PostgreSQL has headroom (CPU below about 60 % at peak) and load is spread over several workspaces: request starts within one workspace are serialized by the gapless `ORQ-n` counter row (S92 F1), so more replicas do not start requests of one workspace faster. On the test machine the database saturated before two 1.5-CPU replicas did.

**Insights are kept for 30 seconds ([S102](build/S102-insights-and-inbox-at-volume.md)).** Each replica keeps finished Insights figures for 30 seconds (`orqix.insights.cache-ttl-seconds`, environment `ORQIX_INSIGHTS_CACHE_TTL_SECONDS`; 0 turns it off, at the price of recomputing for every viewer) and lets viewers of the same figures share one calculation. Access is still checked on every call. **Inbox counts** (the My tasks badge and the tab counts) are exact up to 1,000 and show 1,000 beyond that. On 100,000 requests this took Insights from about 1 to 26 calls per second for twelve viewers. **Request numbers ([S103](build/S103-start-throughput-and-loose-ends.md)).** `orqix.runs.reference-allocation` (environment `ORQIX_RUNS_REFERENCE_ALLOCATION`) is `gapless` by default: `ORQ-n` numbers have no gaps, and starts in one workspace queue behind each other. Set `fast` on every replica when many requests start in one workspace at once: numbers stay unique and increasing, but a start that fails after taking its number leaves a gap. On one laptop this took 200 concurrent starts from 54 to 82 per second. In `fast` mode the numbers come from a small pool of their own (`orqix.runs.reference-pool-size`, default 4 connections per replica), so count those connections against the database's connection limit. The open-request limit of a workflow stays exact, for child requests too: starts queue only within `orqix.runs.quota-margin` (default 64) requests of the limit. That margin must exceed the connection pool size times the number of replicas; the application refuses to start when it does not, using `orqix.runs.expected-replicas` (default 4). Raise both when you run more replicas or a larger pool.

**No sticky sessions.** Sessions live in PostgreSQL (Spring Session JDBC); any replica serves any request, including the login callback. Pass `Host`/`X-Forwarded-Host` and `X-Forwarded-Proto` from the load balancer; do not let it send an internal `X-Forwarded-Port` (it ends up in the OAuth redirect URI). A balancer must not re-send a request it already delivered (POSTs are protected by `Idempotency-Key` only for client retries). Local reference: `deploy/compose/docker-compose.scale.yml` and `deploy/compose/lb/nginx.conf`.

**Settings that must be identical on all replicas:** database URL and credentials, `ORQIX_CURSOR_SECRET` (list cursors are signed and decoded by any replica), identity/OIDC settings, `ORQIX_SECRETS_DIR` content, egress allow-lists, `orqix.policy.cache-ttl` (bounds how long a revoked permission still works on other replicas: 50 s), `orqix.runtime.async-job-lock-time` / `reset-expired-jobs-interval`. Must differ: the Flowable lock owner (`orqix.runtime.lock-owner`, default the pod hostname - keep the default).

**Background work is cluster-safe.** Run watchdog, consistency scan, stage overdue, reminders, stage mail and SLA, external-job lease recovery, staged-attachment cleanup, attachment retention and agent-write retention take bounded batches with `FOR UPDATE SKIP LOCKED`, so every replica can run them without double processing. The message sweeper runs unbatched statements every 5 s on every replica (correct, may wait on another replica's locks). Flowable jobs are locked per replica; a replica that dies holds its jobs until the lock time (60 s) plus the reset interval (15 s), then another replica takes them over and re-sends in-flight calls with the same idempotency key (measured: takeover 45-58 s after a SIGKILL, every request completed exactly once). Per-replica limits: workflow dry runs (30/min per person per replica) and the AI model circuit breaker.

**Connection budget.** Each replica opens up to 15 connections (`SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`): `replicas x pool + 5 (migration) + 10 (operations) <= 0.8 x max_connections`. Under load the pool is the first queue; watch `hikaricp_connections_pending`. A larger pool helps only if the database has spare CPU.

**Database settings shipped with the image.** Every pooled connection sets `plan_cache_mode = force_custom_plan` (`ORQIX_DB_PLAN_CACHE_MODE`, S92 F3); keep it, since the optional-filter queries can get generic plans that take more than a minute.

**Growth.** R0 purges no request history, events, audit or idempotency records; database size grows linearly with traffic (measured: about 0.8 GB for 111,000 requests with 3 tasks and 8 events each). Insights recomputes its figures from that history on every view (0.5 s for 30 days and 1.2-1.3 s for 90 days on 111,000 requests, single user); many simultaneous Insights users are the most expensive read workload. Large open inboxes are expensive too: with 8,000 open claimable tasks the claimable list and its count cost 50-200 ms each.

**Measured on this machine (8 logical CPUs, shared, Docker Desktop; one workspace; 150 active users with 1-3 s think time):**

| Setup | Mixed requests/s | Starts/min | p95 reads / starts | Bottleneck |
|---|---|---|---|---|
| 1 replica (1.5 CPU), PostgreSQL 3 CPU | about 140 | about 420 | 1.1 s / 1.4 s | app CPU and connection pool |
| 2 replicas (1.5 CPU each), PostgreSQL 3 CPU | about 123 | about 440 | 2.1 s / 4.3 s | PostgreSQL CPU, start counter lock |
| same, database with 111,000 requests and 8,000 open tasks | about 58 | about 160 | 5.2 s / 6.4 s | PostgreSQL CPU: Insights aggregates, inbox counts, start counter lock |

These figures do not transfer to other hardware, a network between app and database, or multi-workspace traffic. Size the database first, and repeat `tools/perf/scale.py` on the target environment before committing to a capacity.

## 9. Security notes

- Never put credentials in workflows, connection definitions or exports; only secret references are stored.
- Audit records (sign-ins, access changes, publishes, decisions, operator actions, connection changes) are append-only for the application role.
- The management port must stay unrouted; only health and Prometheus are exposed on it.
- Production-readiness gates still open for R0 are listed in [doc 06](06-delivery-plan.md#production-release-gates) and the feature status page.

## OAuth service connections — 18 October 2026

Bindings can use `oauth2ClientCredentials` with a mounted secret containing the approved token endpoint, client ID, client secret and optional scope. Both the service and token endpoint need approved egress. [S65](build/S65-engineering-closeout-increments.md#oauth-client-credentials-in03a) documents the format, renewal/rotation, limits and deployment migrations. Credentials are provisioned through platform secret management; settings accepts only their reference. Delegated consent/refresh tokens and enterprise provider acceptance remain open.

## Asynchronous external-agent gateways - 18 October 2026

Apply V85 before running callback-enabled application code. Provision a gateway service identity with the existing workspace `job.work` grant. The gateway leases an external-task topic, registers its callback before remote submission, and persists the returned ticket in its dispatch outbox. The same subject must authenticate to deliver the result after restart; token renewal is allowed. Verify provider webhooks in the gateway, rather than giving providers Orqix service tokens. Callback timeout/failure requires operator review and never automatically redispatches remote work. See the [worker protocol](build/S64-external-workers.md#durable-asynchronous-callbacks) for endpoints, SDK methods and exact recovery behavior. The embedded synchronous MCP agent executor remains read-only and unchanged.

## Delegated OAuth workspace consent - 18 October 2026

Choose **OAuth delegated consent** on a connection binding and save its mounted credential reference. Provision the provider endpoints, client ID/secret, approved scopes and exact connection-specific callback URI, plus the reserved `oauth-token-keyring`, as described in [S67](build/S67-delegated-oauth.md). Apply V86 before deployment. All nodes need the same keyring; key bytes stay in platform secrets and cannot be used as ordinary connection credentials.

In the connection's **Workspace authorization** panel, choose **Authorize workspace connection**, continue to the approved provider and consent with the intended account. This authorizes the workspace connection, including its permitted workflow uses. The callback removes transient code/error data and returns to settings without showing tokens. **Disconnect authorization** erases local credentials and fences pending refresh/consent results; review the provider account when remote revocation is unconfirmed. An uncertain refresh or code exchange requires new consent instead of retrying an already-consumed credential. Retain prior encryption keys until all dependent grants and unexpired consent records have rotated or expired. Provider account/tenant registration and enterprise acceptance remain deployment work.

## Grant-only permissions - 19 October 2026

No role carries these; a workspace administrator grants them with `POST /workspaces/{id}/access/grants`. Except `workflow.start`, every one is workspace-wide (a `workflowId` is refused):

| Permission | Allows | Notes |
|---|---|---|
| `workflow.start` | Start requests | Workspace-wide or for one workflow |
| `connection.manage` | Register/bind connections, create and edit adapters and inbound triggers, purge received events, manage shared connection assets | Fresh check on every call |
| `workflow.approve-promotion` | Approve or reject promotion requests into a production-stage workspace ([S74](build/S74-promotion-ui-approval-gate.md)) | Granted in the **target** workspace; never lets the requester decide their own request |
| `agent.approve-write-tool` | Approve or revoke AI agent write tools ([S75](build/S75-approved-agent-write-tools.md)) | Revocation is also allowed to `connection.manage`; the approver can never trigger a write through the tool |
| `message.send`, `document.browse`, `job.work`, `audit.read` | As before | |

Run operators (`run.operate`) check consistency, send signals, quarantine files and record unknown agent writes. `workspace.manage-access` (administrators) sets the workspace stage, the promotion approval requirement, the file retention period and the agent write record retention period ([S85](build/S85-agent-write-expiry-and-retention.md)). Promoting across workspaces needs `workflow.export` (designers, publishers) in the source and `workflow.edit` (designers) in the target. Migrations V99 and V110 recreate the grant check constraints; reconcile them with any concurrent migration that changes the list.

## Adapters and inbound triggers - 19 October 2026

[S47](build/S47-adapters-inbound-triggers.md), [S73](build/S73-adapter-step-inbound-events.md). Under **Settings → Connections** (`connection.manage` to change, `connection.read` to view; `secretRef` is hidden from non-managers) administrators define email, Teams, JDBC (one parameterized read-only `SELECT`/`WITH`) and Kafka adapters, edit or disable them (`PUT` with `expectedVersion`, `409 CONFIGURATION_VERSION_CONFLICT`) and test them. **There is no live transport: no SMTP, Teams, JDBC or Kafka client is linked, `secretRef` contents are not read, and every send and test goes to a local stub.** Live acceptance against real systems remains an external gate. Adapters cannot be deleted. Workflow steps check the allowlists (exact recipient domains, topics) again on every send.

Inbound triggers (`POST /workspaces/{ws}/inbound/{triggerId}`) use no session or token. The sender signs `timestamp + "." + rawBody` with HMAC-SHA256 using the trigger's `secretRef` file (`X-Orqix-Signature: sha256=<hex>`, `X-Orqix-Timestamp` within 5 minutes, a unique `X-Orqix-Event-Id`). A customer relay must produce these calls; Orqix does not poll mailboxes or topics. Troubleshooting: 404 unknown or disabled trigger, 401 bad or stale signature, 503 secret file missing, 422 shape or sender filter refused, 429 when 1,000 events for the trigger are unstarted (start or purge some), 202 with `duplicate: true` for a repeat. Events are kept until purged: **Settings → Connections → Received event retention** or `POST /inbound-events/purge` (1 to 3,650 days; started events always, unstarted only with `includeUnstarted`; a purged event id is accepted again). Apply migrations V47 and V96 first. An adapter step with an unknown outcome is never resent automatically; an operator reconciles it as for any effect.

## Promotion, environments and canary - 19 October 2026

[S54](build/S54-promotion-canary-environments.md), [S74](build/S74-promotion-ui-approval-gate.md). Set each workspace stage (`development`, `test`, `staging`, `production`; default `test` for test workspaces, otherwise `production`) with `workspace.manage-access`, through the environment endpoint (`expectedVersion`) or the **Promotions** page. Promotion works only between workspaces of one organization and strictly forward in stage. For a production workspace, `requireApproval` can be switched on; it switches off by itself when the stage moves out of production. Promotion refusals:

| Response | Cause |
|---|---|
| `404` | Target not visible to the caller, or in another organization |
| `422` (stage) | Same workspace, or not a later stage |
| `422 WORKFLOW_INVALID` | Target report not compatible: binding missing, definition missing or differing, a group or called workflow absent, or compiler blockers. Fix them in the target; bindings, groups and secrets are never copied |
| `412 STALE_REVISION` or `422` | The target already has the workflow and its draft revision moved or was not supplied; run the check again |
| `409 PROMOTION_APPROVAL_REQUIRED` | The production gate is on; use **Request approval** |
| `409 DUPLICATE_KEY` | A pending request for this workflow and target exists; decide or cancel it |
| `403 SELF_APPROVAL_DENIED` | The requester tried to approve or reject their own request |
| `422 REASON_REQUIRED` | Rejection without a reason |

An approved request whose target is no longer compatible, or whose draft moved, stays pending: cancel it and request again. Promotion never publishes. The canary (`workflow.publish`) sends 1 to 50 percent of new starts to one other release with an identical form; it is not evaluated or rolled back automatically. Apply V65 to V67 and V99.

## Consistency scan and run repair - 19 October 2026

[S49](build/S49-run-repair.md), [S72](build/S72-repair-waits-and-scan.md). **Check consistency** and **Rebuild steps** work per request (`GET /runs/{id}/diagnosis`, `POST /runs/{id}/repair`, `run.operate`) for approvals, duration and date waits, message waits and external-worker steps. A repair only rewrites the projection from state the engine holds; it never triggers or modifies the engine. The states and the operator wording are in the [user guide](user-guide.md#checking-a-request-against-the-engine-operators). `strandedToken`, `emptyRequest` and `engineMissing` are cancel-only. `unsupported` means the engine holds something the check cannot account for (an unknown timer or subscription, an in-flight, dead-letter or suspended job, a child, call, document or signal wait); resolve that first or cancel.

The periodic scan is **off by default** and reports only; it never changes a request:

| Setting | Default |
|---|---|
| `orqix.watchdog.consistency-scan.enabled` | `false` |
| `...batch-size` (hard cap 25) | `10` requests per pass |
| `...interval` | `5m` per instance |
| `...recheck-after` (in-sync requests) | `6h` |
| `...finding-recheck-after` (requests with a finding) | `30m` |

It runs inside the watchdog sweep (`orqix.watchdog.poll-delay`, default 60 s), inspects each request under `FOR UPDATE SKIP LOCKED` and keeps one row per scanned request (migration V93). `GET /workspaces/{ws}/consistency-findings` (`run.operate`) feeds the **Runs** panel. A successful repair clears the finding at once, and findings of finished requests are purged. Consider an alert on a non-empty finding list if you enable it.

## Request file quarantine, retention and OCR - 19 October 2026

[S52](build/S52-document-lifecycle.md). Retention (`workspace.manage-access`, 1 to 3,650 days or none; **Settings → File retention** shows how many files are eligible now) is applied by an hourly sweep (`orqix.attachments.retention-delay`, 200 files per batch, safe on several pods). It deletes the bytes and extracted text of files of finished requests, keeps the metadata, and never touches running requests or quarantined files. It does not purge staged uploads, audit or timeline records, exports or database backups. Quarantine (`run.operate`) is manual and not tied to the virus scanner result. The OCR provider is a bean (`OcrProvider`); the shipped `local-stub` reads plain text and CSV only and reports images and PDFs as unsupported, so a real provider must be supplied by the deployment. Apply V55 to V57.

## Agent write tools - 19 October 2026

[S75](build/S75-approved-agent-write-tools.md). Writes are declared in the connection definition (`agentSpec.writeTools`, up to 4, email or Teams, fixed recipients) and need an active approval per workflow key, connection version and tool before publish and again at run time. Delivery goes to the local stub only. Troubleshooting:

| Symptom | Cause and action |
|---|---|
| Publish fails with `WF_AGENT_WRITE_TOOL_UNAPPROVED` | No active approval for this workflow, connection version and tool. A holder of `agent.approve-write-tool` approves it on **Agent writes**; a new connection version needs a new approval |
| `WF_AGENT_WRITE_TOOL_ADAPTER` or `_RECIPIENT` | The adapter is missing, disabled or of another kind, or a fixed recipient's domain is off the adapter allowlist |
| Agent step needs attention, `WRITE_NOT_APPROVED` | The approval was revoked after publishing; approve again. The model is not called meanwhile |
| Agent step needs attention, `WRITE_APPROVAL_EXPIRED`; the list shows **Expired** | The approval had an end date ([S85](build/S85-agent-write-expiry-and-retention.md)) and it has passed; it allows nothing, like a revoked one, and cannot be revoked. Approve the tool again (the old approval is then stored as `expired`) and retry the request. Publish of a workflow whose only approval expired fails with `WF_AGENT_WRITE_TOOL_UNAPPROVED`, whose message names expiry |
| A write refused as `WRITE_NOT_APPROVED` or `WRITE_APPROVAL_EXPIRED` with a journal entry in `permanentFailure` | The approval was revoked or expired after the write was authorized and before it was sent; the final check just before the send stopped it and nothing was sent. Approve again and retry |
| A write refused as `WRITE_NOT_SENT` (journal `retryableFailure`) | The final approval check could not run (for example a lock timeout behind a revocation). Nothing was sent; retry the request |
| Refused with `WRITE_SELF_APPROVAL` | The request was started by the tool's approver; another person must start it |
| Needs attention with `WRITE_OUTCOME_UNKNOWN` or `EFFECT_UNKNOWN`; retry gives `EFFECT_NOT_RECONCILED` | A send may or may not have happened. Check the target, use **Record outcome** (delivered or not delivered, reason, optional reference) on **Agent writes** (`reconcileAgentWrite`, `run.operate`), then retry the request. The generic step reconcile refuses agent writes. Nothing is inferred or resent automatically |

Approval expiry ([S85](build/S85-agent-write-expiry-and-retention.md)): optional `expiresInDays` (1 to 90, counted as 24-hour days from the approval instant; omitted means no expiry). Expiry is checked by the publish compiler, before the model is asked, before each write and again in the transaction that sends (the approval row is read `FOR SHARE`; a revocation waits for an in-progress send, at most `lock_timeout`, 10 s, and applies to the next write). Expiry is compared with the application clock, so keep pod clocks synchronized. Nothing is audited or notified at the moment an approval expires; there is no reminder before it.

Write record retention ([S85](build/S85-agent-write-expiry-and-retention.md)): `getAgentWriteRetention` / `setAgentWriteRetention` (`workspace.manage-access`, 1 to 3,650 days or none, `expectedVersion`, `409 WORKFLOW_VERSION_CONFLICT`, audit `agent.write.retention.updated`), set in **Settings → Agent write retention**, which shows the number eligible now. An hourly sweep (`orqix.agent-writes.retention-delay`, 200 records per run, safe on several pods) deletes `agent_write_call` rows of requests that are `completed` or `cancelled` and ended longer ago than the period, only when every write of that request has a final journal outcome (`succeeded`, `permanentFailure`, `notApplied`); a request with an `unknown`, in-flight, pending or retryable write, or a write never registered in the journal, keeps all its records. Each removal is audited (`agent.write.purged`, system actor, ids only). The effect journal, `agent_call` and audit events are not touched.

Known limits: approval is bound to the workflow key, not one release; no quorum or compensation; the sending transaction holds a database connection for the duration of the (stub) send. Apply V110, V111 and V120 to V122. V120 and V121 are unchanged from commit `48aaf4c`, so no Flyway repair is needed; their SQL headers still cite S79 by design (see S85).

## Callback tokens and outbound webhooks - 26 October 2026

[S98](build/S98-callback-tokens-and-triggers.md), [S99](build/S99-webhooks-and-kafka-triggers.md). Apply migrations V170 and V171 first.

**Callback tokens.** A "Wait for a message" step with **Answered by an external system** gets a callback wait when a request reaches it. On the request page an operator (`run.operate`) chooses **Issue token**; the token (`ocb_...`) is shown once and Orqix keeps only its SHA-256. Give it to the external system only. It polls `GET /api/v1/workspaces/{ws}/callbacks/wait` and resumes once with `POST .../callbacks/wait/resume` (`Idempotency-Key`, body `{"result": {...}}`), sending the token in `X-Orqix-Callback-Token` and never in a URL. A relay with the Operator role bound to its machine subject may issue tokens with a bearer token. **Replace token** issues a new one and stops the old; **Revoke token** stops it while the step keeps waiting (not after a resume: then the token only replays the original answer, and revoking is refused with 409). Troubleshooting: 404 unknown, replaced, revoked or other-workspace token; 409 `CALLBACK_ALREADY_RESUMED` (a different request after the resume), `CALLBACK_NOT_WAITING` (expired token or closed wait: issue a new token if the step still waits), `RUN_SUSPENDED` (an operator suspended the request: resume the request, then the caller repeats the same call); 422 result does not match the declared values. Suspending or resuming a request is unrelated to callback tokens. Expiry and every issue, revoke and resume are audited with ids and counts only. A signed inbound trigger may carry `X-Orqix-Callback-Token` to resume a wait instead of recording an event, but only for a request of the trigger's own workflow.

**Outbound webhooks.** **Settings → Webhooks**: connection managers subscribe an endpoint (at most 10 per workspace) to request started, task opened, request completed, request needs attention and "waits for an external system". The URL must pass the egress allow-list (`orqix.egress.allowed-hosts`; https unless the host is a configured plain-http development host) when saved and before every attempt; at send time Orqix connects only to the address it just vetted, so DNS rebinding cannot redirect a delivery, and redirects are never followed. The signing secret is a file whose name **starts with `webhook-`** and is used by nothing else (connections, adapters, inbound and Kafka triggers cannot use `webhook-` names, and a webhook cannot use theirs). Deliveries carry ids and states only and are signed for webhooks only:

```
X-Orqix-Signature: v1=hex(HMAC-SHA256(secret, "orqix-webhook-v1." + X-Orqix-Webhook-Id + "." + X-Orqix-Timestamp + "." + rawBody))
```

Receivers should recompute that value, compare in constant time, reject timestamps older than 5 minutes and deduplicate on `X-Orqix-Webhook-Id`. Failed deliveries are retried after 30 s, doubling up to 1 h (`orqix.webhooks.*`), eight attempts in all, then appear under **Dead letters** for operators (`run.operate`), who can **Replay** one (not while the subscription is paused; 429 when 10,000 deliveries already wait). **Pausing** a subscription moves its waiting deliveries to the dead letters (`subscription_paused`) so resuming does not flood the receiver. A host removed from the allow-list dead-letters at once (`egress_denied`); a missing secret file is retried (`secret_unavailable`). The dispatcher runs on its own thread, claims one delivery at a time with a 60 s lease (`orqix.webhooks.lease`, at least 30 s) and gives a slow subscription at most 5 s of each 20 s pass, so one slow receiver cannot delay others. Delivery is at least once. People with only `connection.read` see subscription URLs as `scheme://host/…`. Delivered rows are removed after 7 days, dead ones after 30.

**Kafka consumer triggers.** Apply migration V172. Brokers are configured by the deployment only, per named cluster: `orqix.kafka.clusters.<name>.bootstrap-servers`, `security-protocol` (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL; default SASL_SSL), `sasl-mechanism` (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512) and `allowed-topics` (exact names). `orqix.kafka.mode` is **`off` by default** (triggers can be prepared, nothing consumes); set `stub` for a demo without a broker (Settings → Kafka → **Send test message** pushes one JSON value through the same handling) or `live` in an environment with a reachable broker. Each replica needs a distinct `orqix.kafka.instance-id` (default: host name plus a random suffix). Under **Settings → Kafka** a connection manager chooses a cluster, an allowed topic, a consumer group (default `orqix-<key>`, unique per cluster), an optional credentials `secretRef` (a file with JSON `username` and `password`, never a `webhook-` name), and either **start a request** (workflow plus a `field=/pointer` mapping) or **send a message to a waiting request** (message name, correlation key pointer, mapping). The trigger acts as the person who created or last enabled it, who must be allowed to start that workflow (`workflow.start`) or send messages (`message.send`); every start or message is checked exactly like a manual one. All replicas join the trigger's consumer group, so each partition is consumed by exactly one replica and is taken over when a replica stops. Offsets are committed only after a record and its effect are stored; a repeated record key is dropped. Malformed, too large (> 64 KiB), unmapped or refused records and records that still fail after 10 attempts become dead letters (error code, partition, offset, size and a digest - never the content), visible to operators (`run.operate`); re-produce them on the topic to try again. The panel shows lag, the partitions each replica holds, counts and the last error code. At most 5 triggers per workspace; batch 1-100 and 1-200 records per second per replica.

## Restore, callback token cleanup and per-item quorum - 28 October 2026

[S104](build/S104-restore-callback-limits-per-item-quorum.md). Apply migrations V174 (who archived a workflow), V175 (delete permission on callback waits) and V176 (item count on approval tasks) before starting this image.

**Callback token settings.** `orqix.callbacks.retention` (default `30d`, 1 hour to 365 days): a callback wait that has closed (resumed, answered elsewhere, timed out or its request ended) is deleted that long after it closed, and its token then answers 404. Each pass is audited as `callback.waits-purged` with a count. `orqix.callbacks.requests-per-minute` (default `60`, 1 to 6000): poll and resume calls one token may make per minute on each server; beyond that 429 `RATE_LIMITED` with `Retry-After`. Tell external systems to poll with `If-None-Match` and back off on 429. With several servers the effective limit is up to servers × the setting.

## Promotion notifications and Kafka sign-in errors - 29 October 2026

[S106](build/S106-shapes-promotion-kafka-e2e.md). Apply migrations V177 (two new notification kinds) and V203 (two indexes, for the callback purge and the proposed-approver lookup) before starting this image.

**Promotion notifications.** When a promotion into a workspace with the approval gate is requested, everyone who may approve promotions there (explicit `workflow.approve-promotion` grant) gets an in-app notification, except the requester. The requester is told in their own workspace when it is approved or rejected. Nothing is sent by email.

**Kafka trigger errors.** The triggers panel and the API show a code after a failure: `secret_unavailable` (the trigger's `secretRef` file is missing or not `{"username": ..., "password": ...}`), `authentication_failed` (the broker refused those credentials) and `consumer_error` (anything else, for example the broker cannot be reached). The consumer keeps retrying, backing off to one minute.


## Pega Blueprint export layout - 30 October 2026

[S91](build/S91-pega-blueprint-pdf.md). Apply migration V204 (the names of a blueprint's case types that were not transcribed) before starting this image.

**Pega Blueprint PDFs.** Pega's "Application Overview Document" Blueprint export is now read with its own layout (`pega-blueprint`): stages and steps from the lifecycle columns, step kinds from the step icons, fields from the field tables, personas as roles and Live Data objects as integrations. Other PDFs are still read as a generic outline. An export can define several case types and one import transcribes one: enter its name under **Case type** when uploading (empty: the first); the findings name the others. Page texts are now stored in reading order for this layout. Nothing changes for blueprints imported before.

## Workspace feature switches - 30 October 2026

Workspace administrators (`workspace.manage-access`) turn product modules on or off per workspace in **Settings → Features** ([S127](build/S127-workspace-feature-switches.md)): AI assistance, Cases, Migration and import, Agents and tools, Apps and extensions, Kafka triggers and adapters. Everything is on until switched. A switched-off module leaves the menu, Home and the Workflows page, and the server refuses its authoring and start operations with `409 FEATURE_DISABLED` (`feature` names the module; operations are marked `x-orqix-feature` in the contract), so the switch cannot be bypassed through the API. Nothing is deleted, reads stay available, and work that is already running keeps running. Kafka triggers of the workspace pause within one reconcile (default 10 s): messages wait on the topic and are read from the committed offset when the module is switched on again. Each change is audited (`workspace.feature.updated`, `feature`, `enabled`) and takes effect on all replicas at once. Platform settings (Kafka clusters, ClamAV, the AI key) stay in server configuration.

## Exit export - 30 October 2026

Workspace administrators (`workspace.manage-access`) can download the whole workspace, or chosen workflows, as one ZIP archive in open formats that can be read without Orqix: **Settings → Exit export → Download exit archive**, or `POST /api/v1/workspaces/{workspaceId}/exit-export` with an optional body `{"workflowIds": [...], "includeRunData": false}` (operation `exportWorkspaceArchive`). It supports DORA-style exit plans: definitions, history and evidence leave in formats any other tool reads. No migration is needed.

**What is in the archive.** `README.md` explains every file and format. For each workflow: `workflow.json` (name, owner, dates, active release), `draft.json` (latest draft) and, for every released version `n`, `releases/n/workflow.json` (Orqix workflow JSON, exactly as published, schema bundled under `schemas/`), `process.bpmn20.xml` (BPMN 2.0, the process that ran), `decisions/*.dmn` (DMN), `source-map.json`, `compile-manifest.json` and `release.json` (hashes, compiler version, publisher). Shared forms and decision tables with every version. Connection definitions and the binding (base URL, auth type, enabled) **without credentials**: the credential reference is removed, secret-looking fields are removed from definitions again as a safeguard, and OAuth grants are never exported. Run history - `history/runs`, `tasks`, `decisions` (with reason and comment) and `run-events` - and `audit/audit-events`, each as CSV (UTF-8, RFC 4180) and JSON Lines, plus `people.csv` resolving every person id to a name, e-mail and identity-provider subject. Run input/output values and run event details are included only when **Include run input and output data** is ticked (`includeRunData: true`).

**Checking a copy.** `manifest.json` lists every file with its SHA-256 and size, and `SHA256SUMS` holds the same hashes for `sha256sum -c SHA256SUMS`. The archive's own SHA-256 and size are recorded in the audit log, so a stored copy can be matched to the export that produced it.

**Audit.** Every export writes `workspace.exit-export.started` (scope, number of selected workflows, whether run data was requested) before the first byte is sent - if that write fails, nothing is sent - and `workspace.exit-exported` at the end with counts, the archive size and SHA-256, or outcome `failed` when the stream broke off. A member without the permission gets 403 and an audited denial; anybody outside the workspace gets 404.

**Size and streaming.** The archive is streamed while it is produced: history and audit rows are read with a database cursor (500 rows at a time) inside one read-only snapshot, so memory use does not grow with history and every file describes the same moment. The response has no `Content-Length`; a download that breaks off leaves a file that is not a valid ZIP and is audited as `failed`. The browser button keeps the archive in browser memory until it is saved, so for very large workspaces call the API with a client that writes to disk (`curl -o archive.zip ...` with a session cookie and the XSRF header). Proxies in front of Orqix must allow a long-running response.

**Re-import.** A `releases/n/workflow.json` file can be imported into Orqix again: create a workflow with the same key, save the file as its draft and publish (the round trip is covered by an automated test). Another BPMN engine can open `process.bpmn20.xml`, but service steps are Flowable delegate expressions (`${orqix...}`) that need their own implementation there.

**Limits.** With a workflow selection, shared forms, decision tables and connections are still exported in full and the audit log holds only entries about the selected workflows, their releases and runs. A workspace administrator who cannot also read workflows exports the whole workspace (picking single workflows needs `workflow.read`). Not exported: request file attachments, generated documents, case instances, application pages and views, and the engine's own `ACT_*` tables. At most 500 workflows can be selected in one request.
