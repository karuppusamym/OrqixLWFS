# S6 — Identity, authorization and security controls
Build specification · revision 1.0 · 7 October 2026

Turns the proposed controls in [doc 11](../11-security-operations.md) into implementable rules for R0. Each control maps to a test in [S9](S9-test-plan.md).

## 1. Authentication

**Browser (OIDC).** Spring Security `oauth2Login`, authorization code with PKCE (S256), exact redirect-URI matching, confidential client, no implicit or password grants. This follows the current OAuth security best practice ([RFC 9700](https://www.rfc-editor.org/rfc/rfc9700), BCP 240, 2025) and [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html). Tokens stay on the server; the browser holds only `ORQIX_SESSION` (Secure, HttpOnly, SameSite=Lax — Lax is required for the IdP redirect back).

On every successful login:
1. Upsert `subject` by (issuer, `sub`); refresh display name and email; set `last_login_at`. A `disabled_at` subject is refused.
2. Snapshot the IdP group values from `orqix.identity.groups-claim` into the session (bounded to 200 values of ≤ 256 chars).
3. Rotate the session ID (fixation protection); audit `auth.login`.

Session: idle timeout 30 min, absolute 10 h (filter compares session creation time), JDBC-backed so any pod serves any request. Logout is `POST /logout` with CSRF: invalidate the session, then RP-initiated logout at the IdP `end_session_endpoint`.

**Group revocation bound.** IdP group changes take effect at the next login or session expiry (≤ 10 h). Removing a subject-level binding in Orqix takes effect within the 60 s policy-cache bound. Measure the real IdP behavior with PingFederate (D05) before production; do not describe it as instantaneous.

**Machine clients.** OAuth2 resource server (JWT): issuer, audience `orqix-api`, expiry and signature validated. The token's (issuer, `sub`) maps to a `subject` row (display name = client ID). A path filter limits bearer requests to `startRun`, `getRun`, `listRunEvents` and `sendMessage` (which also needs the explicit workspace-wide grant `message.send`, S16 section 4); everything else returns 403 regardless of grants. Bearer requests are CSRF-exempt.

**Bootstrap.** At startup, IdP group values in `orqix.identity.bootstrap-platform-admin-groups` receive a platform `PLATFORM_ADMIN` binding (`source = bootstrap`). Production must set this to a dedicated, small admin group.

## 2. Principals and effective permissions

```
principals(s)  = { subject:s.id } ∪ { idpGroup:g | g ∈ session groups }
roles(s, ws)   = workspace-scoped bindings for principals in ws
               ∪ ORGANIZATION_ADMIN bindings on ws.organization
               ∪ PLATFORM_ADMIN bindings
perms(s, ws)   = ∪ ROLE_PERMISSIONS[r] for r ∈ roles(s, ws)       (table §3)
               ∪ permission_grant rows for principals in ws          (workflow-specific grants kept with their workflowId)
member(s, ws)  = any workspace-scoped role binding or grant exists
groups(s, ws)  = keys of workspace_group with a member principal ∈ principals(s)
```

Results are cached per (subject, hash of session groups, workspace) for 50 s by default (`orqix.policy.cache-ttl`, which must stay below the promised 60 s bound; T-SEC-09 measured TTL + 0.1 s) and evicted locally on any access change. Background work never uses this cache; dispatch-time checks (connection binding enabled, egress) read current data.

## 3. Permission catalog and default role mapping

| Permission | Meaning | Default holders |
|---|---|---|
| `platform.admin` | Create organizations and workspaces; manage bootstrap/platform bindings | PLATFORM_ADMIN |
| `workspace.manage-access` | Role bindings, grants, groups of a workspace | WORKSPACE_ADMIN (own), ORGANIZATION_ADMIN (organization's workspaces), PLATFORM_ADMIN (all) |
| `workflow.read` | View workflows, drafts, releases, validation | WORKFLOW_DESIGNER, PUBLISHER, OPERATOR, VIEWER |
| `workflow.edit` | Create workflows, save drafts, use templates | WORKFLOW_DESIGNER |
| `workflow.publish` | Publish releases, select the active release | PUBLISHER |
| `workflow.export` | Download release bundles (definitions, not business data) | WORKFLOW_DESIGNER, PUBLISHER |
| `workflow.start` | Start runs; read own runs | Grant only (the Requester persona) |
| `run.read` | Run list, lifecycle, steps, timeline metadata | OPERATOR, VIEWER |
| `run.read-data` | Request input/output values of any run | OPERATOR (initiator and task participants always see their own run's data) |
| `run.operate` | Retry, reconcile, cancel, reassign | OPERATOR |
| `task.act` | Claim and decide tasks the subject is eligible for | APPROVER |
| `connection.read` | Connection metadata and operation contracts | WORKSPACE_ADMIN, WORKFLOW_DESIGNER, PUBLISHER, OPERATOR |
| `connection.manage` | Register definitions, set bindings, run connection tests | Grant only (integration administrator) |
| `audit.read` | Audit log browsing (R1; R0 shows run timelines only) | Reserved |

Administrators do not inherit business permissions: PLATFORM/ORGANIZATION/WORKSPACE_ADMIN can manage access but cannot read runs, approve or publish unless separately granted. Technical recovery (`run.operate`) never authorizes changing a business decision. Designers test by holding requester/approver access in a test workspace (`is_test`); there is no in-studio test run in R0.

## 4. Authorization algorithm

For every request:

1. Authenticate (§1); attach `correlationId`.
2. Load the target resource by **(path workspaceId, id)**; the SQL always includes `workspace_id`. Not found → 404.
3. Compute `perms(s, ws)`. Not a member and lacking `workspace.manage-access` → 404 (even for `GET /workspaces/{id}`).
4. Read check for the resource (e.g. `workflow.read`, `run.read`, initiator, task participant, eligible candidate). Fails → 404.
5. Action check (`x-orqix-permission`). Fails → 403 with the most specific code.
6. Execute; audit success. Denials of privileged operations (publish, access management, connection management, operator actions, export) are audited with `outcome = denied`.

Denied requests must not change engine or application state (T-SEC-11). Authorization happens before idempotency replay (S3 §5).

## 5. Separation of duties

- Requester self-approval is denied by default at claim, decide and in the claimable list. Only a workflow with `security.selfApproval = allow` published in a workspace with `allow_self_approval_workflows` (test workspaces only; DB check) may skip it, and each such decision carries `policyFlags: ["selfApprovalAllowed"]`.
- An empty or fully self-excluded candidate group leaves the task visible to operators as a routing problem (they can reassign to an eligible subject); it never auto-approves.
- Publisher ≠ last editor is optional in R0 (D13); record the choice per workspace before production.

## 6. Input, expression, egress and platform controls

| Area | Control |
|---|---|
| Request parsing | Body limits (S3 §8); JSON depth 32; unknown properties rejected; numbers as `BigDecimal`; strings bounded by schema |
| Expressions | No user expressions or scripts. Compiler literal/label rules (S4 §3); engine expression bean allowlist (S5 §1); no user-supplied BPMN in R0 (import is R1 and will use an XXE-safe parser with DTDs and external entities disabled) |
| Egress (SSRF) | Binding save and every dispatch check: scheme `https` (plain `http` only for `orqix.egress.plain-http-hosts`, refused in `prod`); no userinfo; `host:port` exactly in `orqix.egress.allowed-hosts`; DNS resolves only to public addresses unless the host is in `allowed-private-hosts`; loopback, link-local (including 169.254.169.254), unspecified, multicast, private and CGNAT ranges rejected; addresses re-resolved per dispatch; redirects never followed; responses ≤ 1 MiB JSON. Residual DNS time-of-check/time-of-use risk is closed by the OpenShift egress policy/proxy (D06) |
| Secrets | `secretRef` names a file in `orqix.secrets.dir` (mounted Kubernetes Secret or approved secret store), read per call for rotation; never logged, returned, exported or stored in definitions; a missing file → `CONNECTION_UNAVAILABLE` |
| Connection test | Only `testable` (read-only) operations; audited |
| HTTP headers | CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'`; `X-Content-Type-Options: nosniff`; `Referrer-Policy: no-referrer`; `Cache-Control: no-store` on `/api/**`; HSTS at the route |
| Rate limits | OpenShift route connection/request-rate annotations per source IP; per-workflow `maxConcurrentRuns`. Application-level per-subject limits are R1 (database-backed so they hold across pods) |
| Output encoding | React escaping only; `dangerouslySetInnerHTML` banned by lint; comments, reasons and labels rendered as text |
| Management endpoints | Separate port 8081, not exposed by the route; only health and Prometheus enabled |

## 7. Audit catalog

Written in the command transaction. `metadata` is a whitelist per action — never request field values, reasons or comments.

| Action | Metadata |
|---|---|
| `auth.login`, `auth.logout` | `method` (oidc, bearer) |
| `auth.denied` | `permission`, `operationId` |
| `admin.organization.created`, `admin.workspace.created` | `key`, `isTest` |
| `access.role-binding.created/deleted` | `role`, `principalType`, `principalValue` |
| `access.grant.created/deleted` | `permission`, `workflowId`, `principalType`, `principalValue` |
| `access.group.updated` | `groupKey`, `memberCount`, `revision` |
| `workflow.created` | `key`, `templateId` |
| `workflow.draft.saved` | `revision` |
| `workflow.published` | `releaseNumber`, `draftRevision`, `semanticHash`, `warningsAcknowledged` |
| `workflow.active-release.changed` | `fromReleaseNumber`, `toReleaseNumber` |
| `workflow.exported` | `releaseNumber` |
| `run.started` | `workflowKey`, `releaseNumber` |
| `task.claimed`, `task.released` | `taskId`, `nodeId` |
| `task.reassigned` | `taskId`, `toSubjectId` |
| `task.priority-changed` | `taskId`, `from`, `to`; free-text reason remains in permission-scoped run history, excluded from global audit metadata |
| `task.decided` | `taskId`, `nodeId`, `action`, `policyFlags` |
| `run.cancelled`, `run.retry-requested` | `effectId` (retry) |
| `effect.reconciled` | `effectId`, `resolution`, `remoteReference` |
| `connection.version.created` | `key`, `version`, `definitionHash` |
| `connection.binding.updated` | `key`, `host`, `authType`, `secretRef`, `enabled`, `revision` |
| `connection.tested` | `key`, `operation`, `outcome` |

## 7a. Attachments (S25)

Files on requests are untrusted input, stored in PostgreSQL and served only as downloads.

| Control | Rule |
|---|---|
| Type | Allowlist: PDF, PNG, JPEG, UTF-8 text, CSV, docx, xlsx, pptx. The extension, the declared content type and the bytes (signature; OOXML central directory without extraction; no macros, no path tricks; text without control characters, HTML, SVG, XML or shebang) must agree. Executables, archives, HTML and SVG, legacy Office files and mail files (msg, eml) are refused with `422` on `/file` |
| Names | Sanitized display label (no directory part, control or bidirectional characters, at most 120 characters); never used as a path. The audit log holds sizes and types, never names |
| Size and count | 10 MiB per file (platform maximum), 25 files and 50 MiB per request, 10 files waiting on forms per person, 30 uploads per minute per person (counted in the database, so it holds across pods). The upload path has its own request-size allowance in `RequestLimitsFilter`; the container stops reading a file at 10 MiB |
| Download | `Content-Type: application/octet-stream`, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox`, `Cache-Control: no-store`. No inline rendering |
| Access | The visibility of request data (initiator, task participants, `run.read-data`); outsiders get 404; adding needs participation or `run.operate` while the request runs; removal by the initiator while it runs, and by operators |
| Scanning | Pluggable `AttachmentScanner`. The default does not scan and records `notScanned`; with `orqix.attachments.clamav.enabled=true` files are scanned over clamd INSTREAM. `infected` and `error` files are never served or accepted on a form. **A production deployment that accepts files from outside must enable a real scanner** |
| Audit | `attachment.added` (`runId`, `attachmentId`, `sizeBytes`, `contentType`, `scanStatus`, `staged`) and `attachment.removed` (`runId`, `attachmentId`) |

## 8. Logging and redaction

Structured JSON logs with `correlationId`, `workspaceId`, `runId`, `nodeId`, `subjectId` (UUID, not email). Never log request or response bodies, headers containing credentials, cookies, tokens, secret values or form field values. Problem responses contain no stack traces. Effect `last_error_detail` stores status, error class and a truncated, scrubbed message only.

## 9. Threats, controls and tests

| Threat | Control | Test |
|---|---|---|
| Guessed IDs across workspaces (runs, tasks, releases, events, export, connections) | Resource-derived workspace, composite FKs, 404 rule | T-SEC-01 (generated from `x-orqix-permission` × all seeded principals) |
| Replay after revocation | Authorization before idempotency replay | T-SEC-02 |
| Self-approval | SoD at list/claim/decide | T-SEC-03 |
| Forged actor/assignee/workspace in payloads | Strict DTOs; actor from principal | T-SEC-04 |
| CSRF | Cookie-to-header token | T-SEC-05 |
| Expression or delegate injection | Literal/label rules, bean allowlist, schema `additionalProperties:false` | T-SEC-06 |
| SSRF, redirects, DNS rebinding | Egress policy | T-SEC-07 |
| Secret leakage (API, export, logs, problems) | `secretRef` only; redaction | T-SEC-08 |
| Stale permissions after role removal | 60 s cache bound | T-SEC-09 |
| Machine token used beyond scope | Bearer path filter | T-SEC-10 |
| Denied command mutates state | Authorize before writes; single transaction | T-SEC-11 |
| Stored XSS via labels, reasons, comments | Text rendering, lint ban, CSP | T-SEC-12 |

Verification standard for the GA gate: [OWASP ASVS 5.0.0](https://owasp.org/www-project-application-security-verification-standard/) (May 2025), Level 2, for the chapters covering authentication, session management, authorization, validation and encoding, API and web services, configuration, data protection and logging. Record requirement IDs and evidence; R0 runs the tests above, not a full ASVS assessment.
