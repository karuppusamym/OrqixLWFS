# S151 - Resource-level grants, field protection, tamper-evident audit (CG-I01, CG-I05, CG-I06)

Wave E, doc 29 §9.12. Branch `claude/wave-e-security-059b70`. Flyway V450-V453. OpenAPI tag `SecurityAndAudit`.

Security rule for the whole slice: nothing here widens access. Resource grants only add one permission on one
resource; masking applies to everyone; the audit chain and signed exports use the permission that already reads audit
data (`audit.read`). Every existing security-matrix assertion still holds (SecurityMatrixTest unchanged except for two
sample bodies and two snapshot tables).

## CG-I01 Resource-level grants

Table `resource_grant` (V450): one row per (workspace, resource type, resource id, permission, principal). Resource
types and the permissions each allows (database check `resource_grant_permission_fits_type`):

| Resource | `resource_id` | Permissions |
|---|---|---|
| folder | folder ID | workflow.read, workflow.edit, workflow.publish, workflow.start, run.read, run.operate |
| workflow | workflow ID | the same |
| decision | shared decision table key | workflow.read, workflow.edit |
| connection | connection key | connection.read, connection.manage |

API (workspace.manage-access, audited `access.resource-grant.created/deleted`): `GET/POST
/workspaces/{ws}/access/resource-grants`, `DELETE /workspaces/{ws}/access/resource-grants/{grantId}`. Settings → Access
has a "single resources" section.

Evaluation (`PolicyService.compute`, every fresh access): the caller's resource grants are read with folder grants
expanded to the workflows filed in the folder now. `WorkspaceAccess` keeps them in `resources()`;
`has(permission)` still means workspace-wide only, so every existing check is unchanged. Resource-aware call sites ask
`hasOnWorkflow / hasOnDecision / hasOnConnection` or take a narrowed copy (`onWorkflow(id)`, `onDecision(key)`,
`onConnection(key)`) that adds only the grants on that one resource and carries no other resource grants.

Where resource grants count:

- Workflows: `WorkflowService.visible` (every per-workflow operation that uses it: view, draft read/save, validate,
  releases, publish, rollout, tests, simulation, BPMN, decision CSV/DMN of the workflow, discussion), the workflow list
  (scoped readers see the workflows they may read plus the ones they may start now), start (`workflow.start` grants
  merge into the existing per-workflow start set).
- Requests: `RunAccess.require` narrows to the request's workflow (run.read / run.operate on its requests: detail,
  operator actions), the run list (`run.read` on some workflows lists only those; a workflow filter outside them is 403),
  searchable fields of a workflow.
- Shared decision tables: get, add version (`workflow.edit`), shared-asset search (filtered to granted keys).
- Connections: list (filtered), get, register a new version of an existing key, binding, test.

Everything else needs the workspace-wide permission as before (conservative): creating workflows, folder and owner
changes, archiving, folder lists, shared-asset usage lists, owner/archive of assets, DMN downloads of shared versions,
analytics, bulk operations, exports.

Decisions (doc 29 is silent):

- D1 Additive only. A resource grant never removes workspace-wide access (no deny rules). "HR designers edit only the HR
  folder" = give the HR group no workspace role and folder grants for read and edit.
- D2 No implication between permissions: edit does not imply read; the UI offers them separately.
- D3 A resource grant makes the caller a workspace member (as a workspace-wide grant does), so the workspace is visible.
- D4 Folder grants follow the folder's current content; moving a workflow out ends the access immediately (fresh access
  on every request). Deleting a folder deletes its grants (trigger on `workflow_folder`).
- D5 Workflow-scoped `workflow.start` behaves exactly like the old per-workflow start grant.
- D6 Decision and connection resources are addressed by key (they are versioned by key).

## CG-I05 Field protection

- Classification: `classification` on request form scalar fields (`contracts/workflow-v0.schema.json`
  `scalarFormField`): public, internal, personal, sensitive. Compiler rule `FieldClassificationRules`
  (`WF_FIELD_CLASSIFICATION`, blocker): only request questions; personal/sensitive cannot be searchable (the search index
  keeps plain values) or files. No BPMN change, compiler VERSION not bumped.
- Masking: personal and sensitive answers are replaced by `••••••` (`FieldClassification.MASK`, six U+2022) in the run
  detail (`input`, new `protectedFields`), task detail (`request.input`), request titles in every list and
  notification, send-back views, correction history events (`INPUT_CORRECTED` before/after, task corrections), the
  workspace export with run data and run archives (database function `orqix_mask_input`, V452). Evidence packs never
  held request values; the explain service and conversation read the masked detail. Audit metadata never held values
  (whitelist). Application logs never print request values (unchanged rule).
- Reveal: `POST /workspaces/{ws}/runs/{runId}/reveal` needs `data.reveal` (new grant-only, workspace-wide permission,
  V451 rebuilds both `permission_grant` checks) and the request's data visibility (initiator, participant or
  run.read-data). Audited `data.revealed` with run, field names and classifications, never values; denials are audited.
- Encryption at rest: sensitive answers are stored as `orqix-enc:v1:<keyId>:<base64url(nonce|ciphertext|tag)>`,
  AES-256-GCM over the answer's JSON text with additional data `orqix-field|<workspace>|<field>`, in
  `workflow_run.input` and in the engine variable `orqix_input` (Flowable runtime and history tables). `RunStore`
  encrypts on insert and correction and decrypts on read; `ProcessData.input` decrypts inside the engine, so
  conditions, decisions and expressions see the answer. Without a key, a request with a sensitive answer is refused with
  `DEPENDENCY_UNAVAILABLE` (fail closed).
- Keys: `orqix.protection.field-keys.current` (key ID) and `refs` (key ID → secretRef, S132 resolver; the secret holds 32
  random bytes, base64). Tests and the dev profile use `dev-key` (key ID `dev`), never production.

Decisions:

- D7 Masked for everyone, including the initiator and data.reveal holders: the reveal operation is the only plain path,
  so every plain read is audited. Exports and archives are masked too (an export is not a reveal).
- D8 Sending the mask text back in a correction keeps the stored answer.
- D9 Only request form scalar fields are classified (lists, rows, step forms and step results are not).
- D10 Processing is not masking: mail/reminder addresses, document generation and AI tasks still receive the plain
  answer they are configured to use (follow-up below).

### Runbook: field key rotation

1. Create a new secret with 32 random bytes (`openssl rand -base64 32`), for example `field-key-2027`.
2. Add it under `orqix.protection.field-keys.refs` (`k2027: field-key-2027`), keep the old ref, set
   `current: k2027` and roll the pods. New sensitive answers use the new key; old ones still decrypt with the old ref.
3. Re-encryption of stored answers: `FieldProtection.reseal` re-encrypts sealed answers with the current key; run it
   over `workflow_run.input` of open and retained requests (a one-off job, not scheduled in this build).
4. Remove the old ref only when no stored answer names its key ID
   (`SELECT count(*) FROM workflow_run WHERE input::text LIKE '%orqix-enc:v1:<oldId>:%'`) and the engine history of
   such requests is purged. A missing key never breaks reads: the answer stays encrypted and is shown masked.

## CG-I06 Tamper-evident audit

- Hash chain (V453): `audit_event` gains `chain_seq`, `prev_hash`, `hash` (NOT NULL, unique position per chain). Chain
  key = workspace ID, or `platform`. `hash = sha256` over length-prefixed fields (prev hash, position, every stored
  column, time in UTC with microseconds, metadata as stored jsonb text) — `orqix_audit_hash`. A `SECURITY DEFINER`
  BEFORE INSERT trigger writes the chain for every insert path; the head table `audit_chain_head` (one row per chain,
  locked per insert) is readable but not writable by the application role. Existing rows are chained by the migration
  in `occurred_at, id` order.
- Verification: `AuditChains.verify` recomputes every hash in the database and walks the chain up to the head read at
  the start, reporting the first broken link: `hashMismatch`, `previousHashMismatch`, `missingEvent` or `headMismatch`
  (events removed from the end or a changed head). `POST /workspaces/{ws}/audit/chain-verifications` (audit.read,
  audited `audit.chain-verified`), `GET` lists the latest 20 recorded checks. Job `AuditChainScheduling` checks every
  chain every `orqix.protection.audit-chain.interval` (6 h); a break logs an error and counts
  `orqix_audit_chain_broken`.
- Signed exports: `GET /workspaces/{ws}/audit/export?afterSeq&limit` (audit.read, audited `audit.exported`): one
  contiguous segment (at most 5000 events) as a compact JWS, ES256, header `typ orqix-audit-export+jws` and `kid`;
  payload `orqix.audit-export.v1` with every stored column, chain positions and hashes, `firstPrevHash` linking to the
  previous segment. `GET /workspaces/{ws}/audit/signing-keys` returns the public JWK set. Key:
  `orqix.protection.audit-signing.key-ref` → secret holding a private EC P-256 JWK with `kid` (rotation: publish a new
  kid; old exports stay verifiable with the old public key kept by the verifier). Without a key the export answers 503;
  dev and tests sign with a key generated at start-up.

Decisions:

- D11 One chain per workspace (and one for the platform), so a workspace's evidence never depends on another tenant.
- D12 Chaining in the database trigger, not in `AuditWriter`, so no insert path can skip it and concurrent writers are
  serialised by the head row lock.
- D13 Verification and export read with audit.read (who can read audit data is unchanged); the platform chain is
  verified only by the job.

## Evidence

- `ResourceGrantsTest`: folder grants (read/edit on the folder's workflow only, list filtered, no publish/start/create/
  access admin/folder move, run.read lists and opens only that workflow's requests without data, moving the workflow out
  ends access), workflow grants (start and operate one workflow, workspace-wide holders unchanged), decision and
  connection grants (own key only, search and lists filtered), validation, duplicate, admin-only, audit, folder delete.
- `FieldProtectionTest`: ciphertext in `workflow_run.input` and the engine variable, masked for operator, requester and
  approver, routing on the encrypted amount, reveal needs data.reveal and data visibility, audit without values, the
  database mask function.
- `AuditChainTest`: chain links, verification needs audit.read, signed export verifies with the published key and links,
  tampering reports position 2 `hashMismatch`, a removed last event reports `headMismatch`.
- `FieldClassificationTest` (compiler), `AccessApiTest.everyGrantablePermissionCanBeGranted` (+ data.reveal),
  `DatabaseTest` (the application role cannot change `audit_chain_head`), `SecurityMatrixTest` (new operations: foreign
  principals 404, no state change).

## Merge notes

- Flyway V450-V453 (V454-V459 free). **V451 recreates both `permission_grant` checks** with the V400 list plus
  `data.reveal`: `workflow.start, connection.manage, message.send, document.browse, job.work, audit.read,
  workflow.approve-promotion, agent.approve-write-tool, connector.manage, connector.approve, ai.approve-skill,
  ai.evaluate, run.bulk-operate, alert.manage, data.reveal`. A sibling adding grantable permissions must list all in a
  higher migration and merge `AccessService.createGrant`, `AccessController.CreateGrantRequest`, the OpenAPI
  `Permission` enum and both grant enums, `AccessApiTest`, frontend `GRANT_KEYS` and `AccessPanel` labels.
- No new `run_id` table (nothing for `orqix_purge_run`). New tables: `resource_grant`, `audit_chain_head`,
  `audit_chain_check` (SecurityMatrixTest snapshot gains `resource_grant`, `audit_chain_check`).
- `audit_event` has three new NOT NULL columns filled by a trigger: code that inserts audit rows needs no change; a
  test or tool that deletes or updates audit rows as the owner breaks that workspace's chain (only AuditChainTest does,
  in its own workspace).
- `WorkspaceAccess` gains an 8th component `resources` (7-argument constructor kept). `RuntimeDtos.RunDetail` gains
  `protectedFields` (old constructor kept). `RunStore` gets `FieldProtection` through a setter (constructor unchanged)
  and a new `engineInput(...)` used by every engine start/correction that writes `orqix_input` (RunService, ChildRuns x2,
  CaseWorkflowRuns, IncidentCorrectionService, TaskService): a sibling adding another writer of `orqix_input` must use it.
- `RunQueries.list` has an overload with a workflow restriction; `WorkflowService.visible` returns the narrowed access.
- New module `com.orqix.governance` (depends on shared, identity, audit, integration; nothing depends on it). New
  integration facade `integration.ProtectionKeys`; settings record `shared.config.ProtectionSettings`
  (`orqix.protection.*` in application.yaml, -test and -dev).
- AuditWriter whitelist +5: `access.resource-grant.created/deleted`, `data.revealed`, `audit.chain-verified`,
  `audit.exported`. No ProblemCode change. Compiler: new rule `FieldClassificationRules` (called from `Pipeline` after
  `SearchableFieldRules`), new public `FieldClassification`; schema `scalarFormField.classification`.
- OpenAPI: tag `SecurityAndAudit` after `AlertsAndRetention`; paths block at the end of `paths` (after the
  AlertsAndRetention block); schemas block before `InboundAccepted`; `RunDetail.protectedFields`. Regenerate
  `frontend/src/api/schema.d.ts` after merging.

## Not done (follow-ups)

- Processing paths still use plain answers: mail templates and reminder addresses, document generation, AI tasks with
  full values, inbound/outbound mappings. A rule "personal/sensitive fields only in approved steps" is a follow-up.
- Schedules store their start input unencrypted (`schedule.input`); a request started by a schedule is encrypted.
- Re-encryption job for key rotation (the method exists; no scheduled job or endpoint).
- Platform-chain verification endpoint for platform administrators; external anchoring of chain heads (for example a
  periodic signed head published outside the database).
- Resource grants on document templates, forms and step templates; scoped grants for folder lists and analytics.
