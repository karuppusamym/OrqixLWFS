# S47 - Email, Teams, JDBC and Kafka adapters and signed inbound triggers (IN02)

IN02 adds four outbound adapter kinds and signed inbound triggers, with a **local stub transport only**. No SMTP, Teams, JDBC or Kafka client is linked into the build, so nothing can reach a remote system. Live acceptance against real mail, Teams tenants, databases and brokers remains an external gate.

## Outbound adapters

`POST /workspaces/{ws}/adapters` (fresh `connection.manage`) stores a named definition; `GET` needs `connection.read` and hides `secretRef` from non-managers. Configuration is a closed whitelist per kind; any other field is refused, and field names that look like secrets get a message pointing to `secretRef`. Credentials are never accepted: only a `secretRef` that names a file in the secrets directory (not read in this build).

| Kind | Configuration | Request rules |
| --- | --- | --- |
| email | sender, exact recipient domains (no wildcards), max recipients 1-10 | every recipient domain approved, de-duplicated, no CR/LF in subject, body at most 20,000 characters, no `bcc`/`cc` fields |
| teams | `baseUrl`, which must pass the existing egress policy (https, host allowlist, public DNS) | text at most 4,000 characters, optional single-line title |
| jdbc | one parameterized read-only `SELECT`/`WITH`, declared parameter names, at most 500 rows, 1-30 s | no semicolons, comments, quotes, casts, `$`, DML/DDL/`INTO`/`SET`/locking/catalog words; declared parameters must equal the `:names` used; values are scalars only |
| kafka | up to 10 exact topic names (no patterns) | topic must be in the list, key at most 256 characters, object value at most 32 KiB |

`POST /adapters/{key}/test-send` (fresh `connection.manage`) validates the request and hands it to `LocalStubTransport`, which keeps a bounded in-memory journal of kind, adapter and a payload digest (never the payload). The outcome is always `stubbed`. Audit metadata is `kind`, `key` and `outcome` only.

## Inbound triggers

`POST /workspaces/{ws}/inbound-triggers` (fresh `connection.manage`) defines a trigger: source kind, the target workflow of the workspace, a signing `secretRef`, a sender filter (approved sender domains for email, approved topics for Kafka) and a mapping of start-input fields to JSON Pointers. `POST /workspaces/{ws}/inbound/{triggerId}` receives events and uses neither session nor token; it is a separate Spring Security chain with CSRF off because no ambient credential exists.

- Signature: `X-Orqix-Signature: sha256=hex(HMAC-SHA256(secret, timestamp + "." + rawBody))`, `X-Orqix-Timestamp` within 5 minutes, `X-Orqix-Event-Id` unique per trigger. Comparison is constant time. Unknown or disabled trigger is 404, bad or stale signature 401, missing secret 503.
- Body at most 64 KiB, depth at most 10. The source shape and sender filter are enforced (422). Only mapped scalar values are stored in `inbound_event.mapped_input`; the raw payload is not stored, only its SHA-256 and size.
- A repeated event id returns 202 with `duplicate: true` and the original event. At most 1,000 unstarted events per trigger (429 with `Retry-After`).
- An event never starts a run. `GET /inbound-events` lists events only for workflows the caller may start (`workflow.start`); connection managers without start rights do not see event data. `POST /inbound-events/{id}/start` calls the normal `RunService.start` as the signed-in person, so that person is the initiator and existing start permissions, quotas and idempotency apply. The event row is locked: it starts at most one run, and a repeat returns that run.

Migration `V47__adapters_inbound.sql` adds `adapter_config`, `inbound_trigger` and `inbound_event` with the app-role grants. The version number is deliberately high so it does not collide with sibling work (S46); if a lower migration from another branch is merged later, existing databases need Flyway out-of-order handling.

## Evidence - 17 October 2026

All results come from runs in this worktree (JDK 21, Testcontainers PostgreSQL; backend command `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dorqix.buildDirectory=target/in02 -Dtest=<classes> -Dsurefire.failIfNoSpecifiedTests=false test`).

- `AdapterRulesTest` (pure): 8 pass. Wildcard domains, secret-named fields, header injection, recipient limits, egress refusal, ten unsafe SQL shapes, undeclared/unused parameters, topic patterns, oversize values, sender filters, mapping failures and HMAC determinism.
- `AdapterInboundApiTest` (real HTTP stack, PostgreSQL, Flowable, contract validation of responses): 3 pass. Covers permission boundaries (designer 403, requester 403, outsider 404, bearer token 403), duplicate key 409, secretRef visibility, 422 refusals for every kind, stub-only delivery with refused requests not reaching the transport, audit metadata whitelist, bad/missing/stale/tampered signatures (401, nothing stored), accepted event, duplicate, filter/shape/size/event-id/unknown-trigger refusals, no raw or unmapped field in the database, no run started by receipt, event visibility, 403/404 on start, initiator is the starter, locked single start and replay.
- Regression: `ArchitectureTest` (5, includes Modulith structure), `SecurityMatrixTest` (3, now includes the new operations for foreign principals and row-count snapshots), `ConnectionsApiTest` (5), `OpenApiConnectionReviewApiTest` (2), `PermissionRevocationTest` (2), `SecretLeakScenariosTest` (1) all pass. 29 backend cases in total.
- Frontend: `AdaptersPanel.test.tsx` 6 pass (config builders, secret reference only, test message to the stub, server refusal retained, trigger creation with mapping, read-only view). Full `vitest run` 785 passed in 92 files; `tsc -b --noEmit` and `eslint .` are clean.
- `tools/contract-check/contract_check.py`: 87 of 88 checks pass, including the permission vocabulary (new qualifier `inboundSignature`), audit whitelist, security-matrix membership and schema freshness. The one failure is pre-existing CRLF in `docs/11-security-operations.md` and `docs/source/master-build-prompt.txt`, which this change does not touch.

## Not included

- Any live transport: no SMTP, Microsoft Graph/Teams, JDBC driver or Kafka client, and `secretRef` contents are never read by an adapter. Live acceptance and tenant/broker/database provisioning stay external.
- Workflow steps that call an adapter, and relays that poll mailboxes, databases or topics and post signed events: the customer's own relay (or a later ticket) must produce the signed POST.
- Editing or disabling adapters and triggers after creation, deleting them, event retention/purge, a UI to list and start received events, and per-sender rate limiting beyond the 1,000-event pending cap.
- The browser mock for these endpoints is a simplified UI double. No browser journey, Docker rollout or customer-system equivalence is claimed.
