# Permissions, data lifecycle and operational contract
Revision 0.2 · proposed controls. These rules require implementation and testing.

## Authorization model

Authenticate issuer + subject; resolve organization/workspace memberships; apply action grants to the specific resource. Derive workspace from the stored resource and compare it with the route/context. Never authorize solely from a client-supplied workspace ID.

Proposed inheritance: organization grants may apply to its workspaces only where explicitly configured; workspace grants apply to resources unless an explicit resource restriction denies them. Explicit deny wins; missing permission denies. Membership, connector/tool grants and enabled status are prerequisites, not bypassed by an action role. Sharing an asset makes it discoverable only to recipients with appropriate grants.

Do not use a platform-admin flag as a universal data-access bypass. A platform administrator may configure identity and grants; accessing business data requires a scoped grant or a time-limited, audited emergency-access procedure. Revoking membership also invalidates its grants and background use where relevant.

## Role/action matrix

Y = allowed within explicit assigned scope; T = eligible assigned/claimable task only; G = may manage grants in its scope, not automatically perform the action; — = not granted by this role.
Run/payload access also requires data permission. This matrix is the proposed default; a configured policy can reduce authority.

| Role | Manage platform | Manage scoped members/grants | Edit draft | Publish/select release | Start/test | View business runs | Recover/cancel | Approve |
|---|---|---|---|---|---|---|---|---|
| Platform Admin | Y | G | — | — | — | — | — | — |
| Organization Admin | — | G, own organization | — | — | — | — | — | — |
| Workspace Admin | — | G, own workspace | — | — | — | — | — | — |
| Workflow Designer | — | — | Y | — | Test only | Test runs only | Cancel own test | — |
| Publisher | — | — | — | Y | — | Release/test evidence only | — | — |
| Operator | — | — | — | — | — | Y | Y | — |
| Viewer | — | — | — | — | — | Y, redacted by policy | — | — |
| Approver | — | — | — | — | — | T, relevant context | — | T |

Requester has a separate scoped workflow.start grant and own-request read permission. Users may hold multiple roles; the business permission checks still apply. Integration administration and connector/tool use are explicit grants, not implied by editing a workflow. Technical recovery does not authorize an operator to change a business decision.

Workflow-specific restrictions, sensitive-field access and export permission are separately checked. Exporting definitions and exporting business data are different actions. Customer-specific role mappings must be recorded before production.

## Separation of duties and revocation

Proposed defaults: no requester self-approval; unavailable eligible approver is a visible routing problem. The backend identifies requester from the authenticated subject, never from form data. If self-approval is explicitly allowed for a test workflow, label and audit that policy.

Publisher/editor separation is optional in the test workspace and a business/security decision for production. Sensitive connector operations declare an approval policy; bind approval to a hash of operation, inputs, scope and expiry. Changing those invalidates approval. A generic approval earlier in the process does not authorize arbitrary future tool inputs.

Recheck current membership and policy for every command and external dispatch. Proposed application policy-cache lifetime is at most 60 seconds, with immediate local invalidation on role changes. IdP group revocation timing depends on refresh/session policy; measure and document it rather than implying instantaneous IdP synchronization. Service account credentials do not carry a browser user's unchecked authority.

## Privacy and retention

These are proposed engineering defaults for planning, not regulatory retention advice. The data owner must approve final periods and legal-hold behavior.

| Data class | Proposed initial policy | Guardrail |
|---|---|---|
| Active process variables/tasks | Retain while active | Never purge state required to resume |
| Published definitions/dependencies | Retain while referenced by a run or supported rollback | Archive after reference check |
| Completed run business payload | 90 days, configurable | Delete/redact only after dependent records and hold checks |
| Audit metadata | 365 days, configurable | Append-only application permission; restrict exports |
| Connector debug payload | Off by default; redacted diagnostic capture up to 7 days when authorized | No auth headers/secrets |
| General operational logs | 30 days | Central redaction, bounded cardinality and access |
| Idempotency records | At least documented retry window; initial 30 days | After expiry require reconciliation before replay |
| Effect journal keys/outcomes | At least run retention plus supported replay/recovery window | Never delete while a pending/unknown effect can recover |
| SSE/run-event feed | 30 days or explicit per-workspace policy | Expired cursors resync from snapshot; not an audit substitute |
| Uploaded migration source | Until conversion accepted, then owner-selected retention | Scan, encrypt, restrict access |
| Backups | Platform-owned retention and encryption policy | Document deletion lag and restore access |

Purge is a controlled, auditable job, not cascading blind deletion. Engine history cleanup must respect the same lifecycle. A logical erase may redact payload while preserving minimum audit metadata; the owner decides admissibility. Backups and archived exports require their own access/deletion procedure.

Attachments, when enabled, use opaque object references and authorized download handlers. Quarantined objects are inaccessible to workflows. Do not send sensitive form values to LLMs by default; use approved field selection and provider policy.

## Environment and configuration rules

Development, integration/test and production have separate secrets, connection bindings, OIDC clients, datasets and access policies. Test workspace is not a substitute for network isolation when real production connectors exist.

Deploy immutable image digests. Store nonsecret config in version control and secret references in deployment config. Environment names, endpoints and limits are explicitly bound to each release. Production boot fails on missing required identity/database/secret configuration; there is no silent permissive auth fallback.

Browser tokens remain server-side behind authenticated sessions. Apply CSRF to state changes; secure cookie settings and same-origin serving simplify the UI boundary. Machine APIs use an explicit service-token policy. Downloads, previews, live events, health detail and administrative views each have access rules.

## Build, test and release pipeline

1. Lock dependencies; compile frontend/backend and validate contracts.
2. Unit tests for compiler/policy; integration tests against PostgreSQL/Flowable; connector contract tests.
3. Browser end-to-end flow and required negative authorization tests.
4. Dependency/container/secret/license scanning and SBOM generation.
5. Build immutable image and record its digest, source commit and artifact hashes.
6. Deploy into integration environment with controlled migration job.
7. Run restart/concurrency/upgrade tests and selected business scenarios.
8. Promote tested artifact with environment binding and production gate evidence.
9. Observe readiness, error rate, job age and task progression; rollback new traffic if acceptance checks fail.

No feature is marked Verified because only mocks passed. Controlled test servers cover failure paths; real enterprise endpoint tests cover actual integration behavior.

Database schema rollback is not assumed reversible. Prefer expand/contract migrations compatible with old/new pods. Test recovery on a restored database before upgrades. If engine schema compatibility requires downtime, use a planned maintenance deployment instead of claiming rolling compatibility.

## Recovery and operational targets

Pod loss, database failover and disaster restore are distinct tests. Proposed pod job-recovery target remains 120 seconds only after tuning. Proposed initial disaster targets for owner review are RPO at most 15 minutes and RTO at most 4 hours; neither is achieved or promised until the HA/backup platform supports and demonstrates it.

Graceful shutdown stops new acquisition, drains bounded work and expires/releases locks safely. Database outage must not produce a success response for an uncommitted task/start. Return recoverable errors with idempotency guidance.

Runbooks cover: stuck/expired jobs; unknown remote result; permanently failing connector; exhausted connection pool; database outage; revoked credentials; failed schema migration; elevated denied-access rate; tenant data exposure incident; restore and post-restore reconciliation. Restore can reintroduce already-applied external effects; reconcile against effect keys/remote records before resuming jobs.

## Operational ownership and alert acceptance

| Signal | Responsible role | Required runbook outcome |
|---|---|---|
| Oldest runnable job age / dead-letter growth | Operator + backend on-call | Diagnose dependency and recover only when safe |
| DB connection saturation / failover | Platform/DB owner | Restore capacity and confirm accepted-state integrity |
| Repeated integration timeout / unknown effects | Integration owner | Reconcile remote outcomes; no blind replay |
| Elevated task SLA breach | Business process owner | Reassign/escalate under policy |
| Auth failures / cross-workspace denials / unusual exports | Security owner | Investigate without disclosing payloads |
| Missing backups / restore failure | Platform owner | Restore protection and block production gate |
| AI budget or provider-policy violation | Workspace/integration owner | Stop affected calls, preserve deterministic workflow behavior |

Alert thresholds and recipients must be bound to the actual pilot load; do not invent a universal rate. Each alert has a tested notification route and a named human owner before production. Routine workflow notifications are separate from operational incident alerts.

## Required negative tests

Test every role against foreign workspace IDs; revoked users and groups; claim/decision/reassignment races; escalation timer races; forged callbacks; changed approval payload; SSRF redirects and DNS changes; expression/delegate injection; unsafe XML/YAML; schema drift; log/export leakage; duplicate webhooks; SSE reconnect authorization; and unauthorized secret resolution. Test denied calls leave no engine/side-effect mutation.
