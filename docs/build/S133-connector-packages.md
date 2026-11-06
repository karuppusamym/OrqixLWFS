# S133: Start and follow up, connector packages, package triggers, catalog and the ServiceNow ITSM package

5 November 2026. The rest of Wave A of the Camunda gap plan ([doc 29](../29-camunda-gap-implementation-plan.md)):
**CG-W02** start and follow up (§5.4), **CG-C03** package format (§6.6), **CG-C04** package lifecycle (§6.7), **CG-C05**
inbound triggers in packages (§6.8), **CG-C10** internal catalog (§6.13) and the **ServiceNow ITSM** worked example
(§6.14). Builds on S129 (check until done, safety net), S130 (connection definition v1, lookup reconciliation), S131
(retry, circuit breaker) and S132 (secret providers). Migration **V250**. OpenAPI: one paths block and one schemas block
tagged **ConnectorPackages**. Permissions **`connector.manage`** and **`connector.approve`**.

## 1. Decisions taken

1. **Packages belong to the organization, installs to a workspace.** A package version is imported through one
   workspace (by someone holding `connector.manage` there), approved by someone holding `connector.approve`, and can then
   be installed in any workspace of the same organization. That is what makes an internal catalog (CG-C10) possible
   without a second, organization-level permission model: every action still needs the permission in the workspace the
   person acts in, and a foreign workspace sees nothing (security matrix).
2. **Both permissions are grant-only and workspace-wide**, like `agent.approve-write-tool`: no role carries them.
   `connector.approve` can be held together with `connector.manage`; the importer can still never approve their own
   import (403), and the approver names the exact archive hash and revision they reviewed (409 otherwise).
3. **Signatures are Ed25519 JWS over `checksums.sha256`.** Trusted publisher keys come only from deployment settings
   (`orqix.connector-packages.trusted-keys.<keyId>`, base64 X.509). A package that is unsigned, or signed by a key the
   deployment does not trust, needs an override reason (stored and audited as `unsignedOverride: true`); a signature
   that does not match the content is refused, override or not. The repository's example publisher key
   (`orqix-example-2026`) is derived from a published seed so the committed `.orqpkg` can be rebuilt byte for byte; only
   the test configuration trusts it.
4. **Install registers ordinary connection versions; never bindings or credentials.** A connection key that already
   exists in the workspace and did not come from the same package is refused (`PKG_CONNECTION_KEY_TAKEN`). An unchanged
   definition reuses its connection version on upgrade. Releases keep their pins; where-used and the catalog show what
   is outdated. Moving drafts to the new version stays the designer's (S114 reviewed pin migration is not extended).
5. **Bindings of package connections are limited to the package hosts** (`PKG_HOST` on save), plus deployment-listed
   development hosts (`orqix.connector-packages.development-hosts`, empty by default) for WireMock and sandboxes. The
   egress policy applies on top, unchanged.
6. **Retirement is per version.** A retired version cannot be installed again. In the retiring person's workspace its
   active install becomes `retired` and its connection keys are archived (S57 archive: no new use; bindings and releases
   untouched; a later install un-archives). Other workspaces see the retirement in the catalog; their installs are not
   changed by someone outside them.
7. **Package triggers reuse the Kafka trigger port** (S99 K8): one transaction per event with its dedupe row, the start
   or message as the run-as person (the one who set the trigger up, who needs `workflow.start` or `message.send`), event
   content filling mapped values and the correlation key only. Message triggers use the existing message machinery (S16),
   so a trigger and a safety-net check that race resolve to exactly one resume (S129).
8. **`async` results are path parameters of the status operation.** The status location must have the binding's scheme,
   host, port and base path (`STATUS_URL_OUTSIDE_BINDING`) and match the status operation's path template, each value
   passing that parameter's pattern (`STATUS_URL_UNEXPECTED`). The values become results `/@async/<name>`, so Studio's
   "Call and wait for completion" is two ordinary steps and nothing new reaches the engine. A bare reference (a job id
   in the body) fills a status operation with exactly one path parameter.
9. **Compiler version not bumped.** Workflows without `/@async/` results compile exactly as before; no golden file
   moved. The one D-CG1 bump of Wave A stays with the coordinator.

## 2. Start and follow up (CG-W02)

```json
"startReport": { "method": "POST", "path": "/api/jobs", "successStatuses": [202], "effectClass": "nonIdempotent",
  "async": { "acceptedStatuses": [202], "statusFrom": { "header": "Location" }, "statusOperation": "getJob",
             "pollIntervalSeconds": 30, "maxWaitSeconds": 7200 } },
"getJob": { "method": "GET", "path": "/api/jobs/{jobId}", "pathParams": { "jobId": { "type": "string", "pattern": "^[a-z0-9]{1,40}$" } },
  "effectClass": "readOnly" }
```

| Rule | Code |
|---|---|
| The status operation is a `readOnly` GET of the same connection, not paginated, whose inputs are only path parameters (at least one) | `CONN_V1_ASYNC` |
| The status header is not a forbidden response header (`Set-Cookie`, auth challenges) | `CONN_V1_HEADER_FORBIDDEN` |
| An asynchronous operation is not paginated | `CONN_V1_ASYNC` |
| `/@async/<name>` names a non-constant path parameter of the status operation; its type is the parameter's; it is "not always present" (warning) when a success status is not an accepted status | compiler `WF_RESULT_POINTER_UNKNOWN`, `WF_RESULT_OPTIONAL` |

At run time (`AsyncStatus`, `HttpExecutor`): accepted statuses count as success; the location (header, or a body value
by pointer) resolves against the request address; userinfo, fragments, encoded `/`, `\` or `.`, and `..` are refused;
the remaining path is matched against the status operation's template segment by segment. A refused location is an
invalid response (for a `nonIdempotent` start: unknown, the request needs attention; the remote work may exist), and
nothing is followed. The dry run (Java simulator) returns `sample-<name>` for `/@async/` results.

**Studio.** The Call a system inspector shows **Finishes later** for an asynchronous operation with **Call and wait for
completion**: it keeps `/@async/<name>` on the call and inserts a Call a system step on the status operation right
after it, with the reference mapped in and **Keep checking until…** switched on (interval from `pollIntervalSeconds`,
deadline from `maxWaitSeconds`). The designer then chooses the done condition and draws the timed-out path; the check
panel lists both. `/@async/` values also appear among the results a step can keep.

## 3. Package format (CG-C03)

`contracts/connector-package-v1.schema.json` defines `manifest`, `trigger` (webhook or poll), `stepTemplate`,
`workflowTemplate` and `fixture`; connections are connection definitions v0 or v1 (kind `http` only). The manifest
lists every file under `contents`; an unlisted file is refused.

| Check on import | Refusal code |
|---|---|
| At most 5 MiB packed, 10 MiB unpacked, 200 files of at most 1 MiB, compression ratio at most 100; only relative paths in the known folders (no `..`, backslash, absolute path, drive letter, duplicate, unknown folder) | `PKG_ARCHIVE`, `PKG_ARCHIVE_TOO_LARGE`, `PKG_PATH` |
| `checksums.sha256` lists exactly the files and every hash matches | `PKG_CHECKSUMS` |
| `signature.jws` (alg EdDSA, typ `orqix-package+jws`, kid = publisher key id, payload = the checksum file) verifies with a trusted key | `PKG_SIGNATURE_INVALID`, `PKG_UNSIGNED` (needs a reason) |
| Every file against its schema; connections through the v0/v1 schema and `CONN_V1_*` rules | `SCHEMA`, `CONN_V1_*`, `PKG_JSON`, `PKG_CONTENTS`, `PKG_DUPLICATE` |
| The manifest's declared operation counts per effect class and trigger keys equal the content | `PKG_MANIFEST_MISMATCH` |
| Host patterns: a wildcard only as the whole leftmost label, then at least two concrete labels | `PKG_HOST_PATTERN` (and the schema) |
| Step templates compiled inside a generated workflow (their request fields, the step, the routes a check needs); starter workflows compiled as they are, against the package's own connections | compiler codes, pointers into the template's `step` |
| Polling triggers: a `readOnly` GET of a v1 connection whose only required input is the watermark input; items, watermark, dedupe, mapping and correlation pointers name single values of each record | `PKG_POLL_*` |
| Fixtures replayed through `HttpExecutor.replay` (the live response handling: status classification, error mapping, accepted answers, response schema, result mapping) and give the expected outcome, code and results | `PKG_FIXTURE` |

Problems are reported together (at most 50) as field errors `/files/<path><pointer>`; contents are never echoed.

## 4. Lifecycle (CG-C04)

| Step | API | Permission | Notes |
|---|---|---|---|
| Import | `POST /connector-packages` (`archive` base64, `unsignedReason`) | fresh `connector.manage` | `connector.package-imported` (package id, version, hash, signature status, override flag, key id) |
| Review | `GET /connector-packages/{id}` | `connector.manage`, `connector.approve` or `connection.read` | Hosts, auth schemes, data leaving Orqix, operations with method, path, effect class and inputs, triggers (verification, weaker basic flag), step and workflow templates, fixtures passed, comparison with the version active here (added and removed operations; effect class changes, removed or newly required inputs and removed response fields are breaking), where-used with outdated pins |
| Approve / reject | `POST /connector-packages/{id}/decision` (`sha256`, `note`, `expectedRevision`) | grant-only `connector.approve`, not the importer | `connector.package-decided` |
| Install / upgrade | `POST /connector-packages/{id}/install` | fresh `connector.manage` | Registers connection versions (`connection.version.created` each), supersedes the earlier install, points set-up package triggers at the new trigger files (and pauses those the new version no longer declares), returns the host patterns to bind and allow; `connector.package-installed` |
| Bind | existing `PUT /connections/{key}/binding` | `connection.manage` | `PKG_HOST` outside the package hosts |
| Test | existing `POST /connections/{key}/test` | `connection.manage` | Read-only, testable operations only |
| Use | `GET /connector-palette?q=` | `workflow.read` | Operations as "Raise an incident · ServiceNow ITSM" and step templates of active, approved installs, with the installed connection version |
| Starter workflow | `POST /connector-packages/{id}/workflow-templates/{key}/workflows` | `workflow.edit` | A draft whose package connections are pinned to the installed versions; audited `workflow.created` with `templateId package:<id>@<version>/<key>` |
| Retire | `POST /connector-packages/{id}/retire` (`reason`, `expectedRevision`) | fresh `connector.manage` | `connector.package-retired`; decision 6 |

**Catalog (CG-C10):** `GET /connector-catalog` lists per package the versions (status, signature), the newest approved
version, the workspaces where a version is installed or retired, and every workflow draft or active release in the
organization that pins a connection version of an older or retired package version.

**Studio palette.** "From connector packages" under the step list searches the palette with the same search text; a
template adds a prefilled Call a system step (its `/steps/templateStep/` references pointed at the new step, the
connection pinned at the installed version), an operation adds a call to it.

**Settings → Connector packages.** Import (with the override reason when the server asks for it), the version list,
the review with approve/reject (note, exact hash), install or upgrade, create a workflow from a starter, retire, the
package triggers with their status, and the organization catalog.

## 5. Package triggers (CG-C05)

`POST /package-triggers` sets up a trigger of an installed package (`connector.manage`, fresh); `PUT` changes, pauses
or resumes it (`expectedVersion`); `GET /package-triggers` lists them with status for `connector.manage` or
`run.operate`; `GET /package-triggers/{id}/dead-records` lists codes, digests and times only. At most 10 per
workspace. Audit `connector.trigger-configured`, `connector.trigger-record-dead`.

### 5.1 Webhooks

`POST /workspaces/{ws}/package-triggers/{id}/events` is on the stateless chain (no session, no CSRF), verified by the
scheme the package declares (`x-orqix-permission: packageTriggerVerification`):

| Scheme | Check |
|---|---|
| `hmacSha256` | HMAC-SHA256 with the trigger secret over `timestamp + "." + rawBody` in the declared headers, hex or base64 (optional prefix), timestamp within the tolerance (default 300 s); constant-time comparison |
| `jwt` | Bearer token; HS256 with the trigger secret, or RS256 when the secret is a PEM public key (the key decides the algorithm, so a public key can never be used as an HMAC secret); `iss` and `aud` as configured on the trigger, `exp` required (30 s skew), `nbf`, lifetime at most `maxLifetimeSeconds` (default 300) |
| `basic` | Only over HTTPS (`request.isSecure()`), the secret file holds `user:password`; shown as weaker on the review screen and in the trigger list |

Every verification failure is the same 401; unknown, disabled and polling triggers are 404; bodies up to 64 KiB.
A repeat of the dedupe key answers 202 `duplicate` and never starts or resumes twice; unusable events (malformed,
unmapped, no dedupe key or correlation value) are 422 and a dead record.

### 5.2 Polling

`PackagePolls` (scheduled every `orqix.connector-packages.poll-tick`, default 2 s, on worker and all-in-one pods)
claims due, enabled polling triggers with `FOR UPDATE SKIP LOCKED`, a lease (`poll-lease`, default 60 s) and a fence,
so one pod polls a trigger at a time. A poll calls the operation through the binding, the egress policy and the
binding's circuit and request limit (CG-W04; an open circuit defers the poll), with the watermark input set to the
stored watermark minus the overlap (first poll: now minus the overlap). Records are handled in order, each in its own
transaction; the watermark moves to the newest handled record; a transient failure stops the poll there and polls again
in 30 s, so nothing is skipped; a full page polls again at once. The result is written only while the claim holds.
Status: watermark, next and last run, last success, last error code, lag, polls, records handled, duplicates, dead
records, leased. Handled records are kept `orqix.connector-packages.record-retention` (default 7 days) for dedupe.
Watermark formats: `iso8601` and `servicenowDateTime` (`yyyy-MM-dd HH:mm:ss`, UTC).

## 6. The ServiceNow ITSM package

Source `examples/packages/servicenow-itsm/`, built and signed `examples/packages/servicenow-itsm-1.0.0.orqpkg`
(`ConnectorPackageArchiveTest` checks it equals a fresh build of the source; rebuild with
`-Dorqix.packages.write=true`).

| Connection | Operations (effect class) |
|---|---|
| `servicenow-itsm` | `createIncident` (idempotentByLookup on `correlation_id`), `getIncident`, `findIncidentByCorrelation`, `lookupUser`, `listChangedIncidents` (readOnly), `updateIncidentFields` (idempotentNatural), `addWorkNote`, `attachFile` (nonIdempotent; work notes append, and attachFile stays nonIdempotent as allowed) |
| `servicenow-catalog` | `orderCatalogItem` (idempotentByLookup: the key travels in the catalog variable `orqix_reference`, copied to the request's `correlation_id` by a business rule), `findRequestByReference`, `getRequestItem` (readOnly) |

Triggers `incident-updated` (webhook, HMAC base64 in `X-ServiceNow-Signature` / `X-ServiceNow-Timestamp`, message
`servicenow-incident-updated` by `correlation_id`) and `incident-changes` (poll every 2 minutes, watermark
`sys_updated_on`, overlap 1 minute, dedupe `sys_id` + `sys_mod_count`). Step templates "Raise an incident" and "Wait
until the incident is resolved"; starter workflow "IT access request": raise the incident, wait for the webhook with a
safety-net check every 2 hours (at most 5 days, then the timed-out path), confirm the outcome in ServiceNow with a short
check until done (Resolved/Closed → granted, Canceled → the failed path "Cancelled by IT"). Five fixtures. The package
README gives the ServiceNow business rule (RESTMessageV2 with `GlideCertificateEncryption.generateMac`), the catalog
rule and the binding steps; ServiceNow script names are [Knowledge] for the default platform and must be checked on the
customer's release. Auth schemes listed are the ones Orqix bindings support today (OAuth client credentials or
authorization code, bearer token); Basic, JWT bearer and mutual TLS bindings remain outside this slice (S130 §6).

## 7. Implementation

| Area | Files |
|---|---|
| Contracts | `contracts/connector-package-v1.schema.json` (packaged by the compiler module), `contracts/connection-definition-v1.schema.json` (async description), `contracts/openapi.yaml` (ConnectorPackages block, permission enums), `tools/contract-check` (package sources validated; `packageTriggerVerification` qualifier) |
| Migration | `V250__connector_packages.sql`: `connector_package_version`, `connector_package_install`, `connector_package_connection`, `package_trigger`, `package_trigger_record`; `permission_grant` checks gain the two permissions |
| Compiler | `ConnectionContracts` (`/@async/` pointers, `asyncResults`, `alwaysAccepted`, `CONN_V1_ASYNC` rules), `Connections.result`, `BindingRules`, `FieldTypes`, `SimulationRun` |
| Integration | `AsyncStatus`, `HttpExecutor` (accepted answers; `replay` for fixtures; response handling shared by calls and replays), `V1Results` (`/@async/`), `packages/PackageArchive`, `PackageSettings`, `PackageValidator`, `ConnectorPackageService`, `PackageTriggerService`, `PackagePolls`, `api/ConnectorPackageController`, `ConnectorPackageTemplates` port; `ConnectionAdminService` (package host rule on bind) |
| Identity, catalog, audit | `Permission` (`CONNECTOR_MANAGE`, `CONNECTOR_APPROVE`), `AccessService`, `AccessController` (grantable), `SecurityConfiguration` (stateless webhook route), `WorkflowService.createFromPackageTemplate`, `CatalogController`, `AuditWriter` (six `connector.*` actions) |
| Frontend | `features/settings/ConnectorPackagesPanel.tsx`, `connectorPackagesApi.ts`, Settings tab, access labels; Studio `model/packageSteps.ts`, `Palette.tsx` (package steps), `StudioPage.tsx`, `HttpInspector.tsx` (Finishes later, `/@async/` results); `mocks/connectorPackages.ts` |
| Example | `examples/packages/servicenow-itsm/**`, `examples/packages/servicenow-itsm-1.0.0.orqpkg`; `.gitattributes` marks `*.orqpkg` binary |

## 8. Evidence

All on PostgreSQL (Testcontainers) and Flowable through the public API, with the shared WireMock origin; nothing
verified on mocks alone.

| Test | Covers |
|---|---|
| `ConnectorPackageArchiveTest` (3) | The committed `.orqpkg` equals a fresh deterministic build and verifies; untrusted key; a changed file caught by checksums; rebuilt checksums with the old signature, a signature by another key under the trusted id, `alg: none`, unsigned; path traversal, absolute, drive, backslash, unknown and nested folders; a file over 1 MiB; a file missing from the checksums; not a ZIP |
| `ConnectorPackageLifecycleTest` (3) | Unsigned refused without a reason and accepted (audited) with one; `connector.manage` required; immutable versions; manifest mismatch, failing fixture and a template missing a required input reported per file; a wildcard top-level host refused; a mismatched signature refused even with a reason; the review facts; nothing installable before approval; the importer cannot approve; wrong hash 409; approval needs the grant; install needs `connector.manage`, registers connections and creates no binding; second install 409; binding outside the package hosts refused (`PKG_HOST`); connection test; palette labels and the template with the installed version; a workflow from the starter template pinned and free of blockers; 1.1.0 upgrade with removed operation, newly required input, `compatible: false`, where-used; upgrade reuses nothing changed, supersedes 1.0.0; outdated draft pin in where-used and the catalog; retire needs a reason, archives the keys here and removes them from the palette; readers without connector permissions get 403 |
| `PackageTriggersTest` (4) | HMAC hex start trigger: wrong secret and stale timestamp 401, one request started as the run-as person, a repeat dropped, an unmapped event a dead record without content, counters, a disabled trigger 404; JWT: wrong issuer, audience, expired, too long-lived, no `exp`, wrong secret and missing token 401, a valid token 202; basic: refused over HTTP, wrong password 401, accepted over HTTPS; polling: two concurrent schedulers plus the application's own give exactly one poll and one read, records become messages, the next poll re-reads the overlap and drops both as duplicates, a trigger leased by another pod is not polled |
| `ServiceNowPackageE2ETest` (5) | The ServiceNow package imported, approved by a second person and installed; its starter workflow published; its webhook trigger. Create whose answer is lost reconciled by lookup (one incident, `reconciled: true`); a forged post 401; the signed business-rule post resumes the request once (repeat `duplicate`), confirm, "Access granted" with the incident number and close code, safety net closed by the message; webhook lost → safety-net check finds Resolved and the late post never resumes twice; Canceled → failed path "Cancelled by IT"; no answer → the 5-day limit → "No answer in time"; 429 with `Retry-After` retried with the same `correlation_id`, one incident, 429 counted in connection health |
| `AsyncStartFollowUpTest` (2) | 202 + `Location` gives `/@async/jobId`; the check until done on `getJob` reads twice and completes with the document; locations at another host (metadata address), another host name for the same address, another base path and an encoded traversal are `STATUS_URL_OUTSIDE_BINDING`, another path and an id that fails the pattern are `STATUS_URL_UNEXPECTED`; none is followed and no check is scheduled |
| `ConnectionV1CasesTest` | Four new registration cases: status operation with request inputs, paginated status operation, forbidden status header, body-pointer location |
| `SecurityMatrixTest` | All 14 new operations as foreign principals (404, no state change; the five new tables in the snapshot) |
| Frontend | `packageSteps.test.ts` (call and wait, refusal, names and durations, template rebinding and pinning), `ConnectorPackagesPanel.test.tsx` (review facts, approve exact hash, install message, import, hidden without permissions) |

Full-suite totals are in the commit message of this branch.

## 9. Not in this slice

Code components (CG-C06) are refused by the format (`codeComponents` must be empty). Generators (CG-C07). Basic, JWT
bearer and mutual TLS **bindings** (the package lists only supported schemes). A pin migration of drafts after an
upgrade (S114 is not extended; drafts show "update available"). Retirement does not change other workspaces' installs.
Fixture conformance runs against a sandbox instance (fixtures are replayed locally only). Polling reads one page per
poll (at most 100 records; a full page polls again at once). `attachFile` lookup by file name and SHA-256 (stays
`nonIdempotent`). The Wave A exit criterion against a real ServiceNow developer instance is not run here: the end-to-end
test uses the ServiceNow-shaped stub.

## 10. Merge notes

- `permission_grant_permission_check` and `permission_grant_connection_is_workspace_wide` are replaced in V250 with the
  full list. A sibling migration that adds its own grantable permissions (for example `ai.approve-skill`, `mcp.use`)
  replaces the same constraints: the coordinator must merge the lists in the last migration.
- Touch points outside new files: `HttpExecutor` (response handling moved into `handle`, `replay` added, accepted
  answers), `V1Results.map` overload, `V1Requests.conforms` now package-visible, `ConnectionContracts.ResultPointer`
  gained a fifth component (four-argument constructor kept), `ConnectionAdminService` constructor (ObjectProvider of the
  package service), `CatalogController` constructor, `WorkflowService` (new method), `AccessService`/`AccessController`
  grantable lists, `Permission` enum (two values at the end), `SecurityConfiguration` (one route), `AuditWriter` (six
  actions), `IntegrationTestBase` (two properties), `ServiceNowStub` (users, PATCH, `>`/`STARTSWITH`/`IN`/`ORDERBY`,
  429 fault, sys_ids unique per reset), `Stub` (`/reports/`), `SecurityMatrixTest` (ids, bodies, snapshot tables),
  `compiler/pom.xml` (packages the new schema).
- Frontend: regenerate `src/api/schema.d.ts` after merging (`npm run api:generate`).
