# Data model, APIs and workflow contract
Version 0.1. These are build contracts, not implemented endpoints.

## Domain model

| Entity | Important fields / constraints |
|---|---|
| organization | id, name |
| workspace | id, organizationId, name; stable engine tenant mapping |
| membership | workspaceId, subjectId, roles, groupSource; unique assignment key |
| folder | id, workspaceId, parentId; no cycles or cross-workspace parent |
| workflow | id, workspaceId, folderId, name, ownerId, activeReleaseId, archivedAt |
| workflow_draft | workflowId, revision, canonicalJson, schemaVersion, updatedBy; optimistic lock |
| workflow_release | id, workflowId, releaseNumber, canonicalHash, compilerVersion, immutable artifacts, publishedBy |
| deployment_binding | releaseId, environmentId, processDefinitionId, deploymentId, dependencyLocks |
| form_version | id, workspaceId, logicalKey, revision, jsonSchema, uiSchema |
| decision_version | id, workspaceId, logicalKey, revision, sourceJson, generatedDmn |
| connection_version | id, workspaceId, logicalKey, revision, allowedOperations, secretRef, policyId |
| workflow_run | id, workspaceId, releaseId, engineInstanceId, lifecycle, businessStatus, businessKey, initiator |
| task_context | engineTaskId, workspaceId, runId, formVersionId; metadata only |
| task_decision | id, engineTaskId, actor, action, reason, submittedData, timestamp; one completion record |
| idempotency_record | workspaceId, operation, key, requestHash, resourceId; unique scoped key |
| effect_journal | workspaceId, effectKey, requestHash, state, attempts, leaseUntil, redactedResponse |
| run_event | id, workspaceId, runId, type, nodeId, timestamp, safeData |
| audit_event | id, workspaceId, actor, action, resourceId, correlationId, timestamp, safeMetadata |
| notification | id, workspaceId, subjectId, runId, message, readAt |
| migration_job / migration_issue | sourceHash, adapterVersion, status; source element, severity, resolution |
| agent / mcp registration (later) | endpointRef, protocol, locked schema/version, allowed operations, authRef |

Use UUIDs for product identifiers and decimal/numeric for money. Engine task state and job state remain in Flowable; task_context is not a second authoritative task engine. Foreign keys and service checks prevent cross-workspace associations. Index runs by (workspaceId, createdAt), events by (runId, id), and membership by (workspaceId, subjectId). Never return unbounded variable/history collections.

## Lifecycle contract

Draft → Validated → Published (immutable).
Editing a published workflow creates another draft.

Run: accepted → running ↔ waiting → completed; optional suspended/cancelled/needsAttention.
NeedsAttention can recover into running after an authorized action.
Business status: Submitted, AwaitingManager, AwaitingFinance, Approved, Rejected or Fulfilled.
Rejected is normally a completed process with a rejected business outcome, not a technical engine error.

## API conventions

Base path /api/v1/workspaces/{workspaceId}. JSON over TLS.
Browser requests use authenticated sessions and CSRF tokens. Service clients use validated bearer tokens.
Run creation, publication and task decisions require Idempotency-Key. Other duplicate-producing operations should use the same mechanism. Store key, payload hash and original response reference transactionally. Proposed retention is 30 days; clients must not retry beyond the documented retention window without reconciliation.
Draft updates use If-Match with ETag/revision.
All list endpoints use cursor pagination and bounded page sizes.
Error envelope: code, message, correlationId, fieldErrors and optional currentRevision.

Status semantics: 400 malformed request, 401 unauthenticated, 403 known resource but denied action, 404 nonexistent or intentionally hidden foreign resource, 409 conflict/duplicate payload mismatch, 412 stale draft version, 422 semantic validation issue, 429 quota, 503 unavailable dependency. Do not expose object existence across workspaces.

## Core endpoints

| Method / relative path | Input | Result / rule |
|---|---|---|
| GET /workflows | filter, cursor | Authorized summaries only |
| POST /workflows | name, templateId optional | 201 workflow + draft revision |
| GET /workflows/{id}/draft | — | Canonical document and ETag |
| PUT /workflows/{id}/draft | definition; If-Match | New draft revision; 412 if stale |
| POST /workflows/{id}/validate | revision | Issues with nodeId/path/code/severity; no deployment |
| POST /workflows/{id}/publish | revision, validationHash; idempotency key | Revalidate exact revision, deploy and return immutable release |
| GET /workflows/{id}/releases | cursor | Authorized immutable release metadata |
| POST /runs | releaseId, input, businessKey optional; idempotency key | 201 run on commit; 200 on exact replay |
| GET /runs | workflow/status/date filters | Workspace-scoped run summaries |
| GET /runs/{id} | — | Version, lifecycle, business status, current work |
| GET /runs/{id}/events | after, cursor | Ordered persisted events for polling |
| GET /tasks | assigned/claimable/completed | Tasks restricted by workspace and eligibility |
| POST /tasks/{id}/claim | expectedVersion | Atomic claim, 409 if already claimed elsewhere |
| POST /tasks/{id}/decision | expectedVersion, action, reason, formData; idempotency key | One authorized durable decision |
| POST /runs/{id}/cancel | reason | Authorized cancellation; reports external-effect caveat |
| GET /workflows/{id}/releases/{releaseId}/export | — | Secret-free versioned bundle |
| GET /connections | — | Permitted operation metadata only |
| POST /connections/{id}/test | registered operation/test input | Audited allowlisted call; integration-admin permission |

Later routes: POST /migrations, GET /migrations/{id}/report, POST /migrations/{id}/convert, POST /ai/drafts, registries and schedule administration. Never expose them as working before implementation.

Task decision body example:

```json
{
  "expectedVersion": 3,
  "action": "approve",
  "reason": "Within department budget",
  "formData": {}
}
```

Caller cannot submit actor, workspace, assignee or protected run variables in formData. Those come from authenticated context and server mappings.

Run creation request references a release in the same workspace:

```json
{
  "releaseId": "release-identifier-from-publish",
  "businessKey": "REQ-1042",
  "input": {
    "title": "Laptop purchase",
    "purpose": "Replacement device",
    "costCenter": "OPS",
    "amount": 1800
  }
}
```

Business key is searchable, not assumed globally unique. Decide whether a particular workflow requires uniqueness; idempotency keys protect retries separately.

## Portable workflow format

See [draft JSON Schema](../contracts/workflow-v0.schema.json) and [purchase example](../examples/purchase-approval.json). The schema covers only the proposed first subset. It is not the entire master-prompt portability format.

Top level includes schemaVersion, workflowKey, name, metadata, inputSchema, outputSchema, outputMapping, triggers, nodes, transitions, decisions, execution, security and dependencies; uiLayout is optional. Deployment endpoints and credentials are excluded. uiLayout optionally stores node positions and viewport in the canonical draft/export; it must not affect execution semantics. Node positions may reference only existing node IDs.

Node IDs remain stable through compile so generated BPMN IDs and runtime events map back to the canvas. Decision and connection keys resolve through dependency locks at publication. Output fields use explicit safe mappings, not unrestricted expressions. The runtime stores validated step results at /steps/{nodeId}; decision output objects use the same convention. outputMapping selects final fields; a missing optional reference is omitted (for example procurementId on rejection), while a missing required reference fails output validation. Gateway default paths apply only when no condition matches. Decision-table default output applies only when no row matches; it is compiled as a tested non-overlapping fallback, never an overlapping UNIQUE row.

Structured conditions are field/operator/value objects. Allowed operators in v0 are eq, gte and lt with typed values. The compiler converts these through a tested whitelist; it does not evaluate user strings. Decision tables require non-overlapping rows and an explicit default.

## Compiler acceptance algorithm

1. Parse within file/size/depth bounds and validate JSON Schema.
2. Reject unknown schema versions and unsupported node kinds/configuration.
3. Verify ID uniqueness, source/target references, one start, end reachability and no cycles.
4. Verify each approval has exactly approved/rejected routes; exclusive branch conditions are mutually exclusive and include a default; nonbranch steps have one outgoing flow; ends have none.
5. Type-check field references and mappings against available prior outputs; reject ambiguous branch-scoped values.
6. Resolve all form, connection, decision and group references in the workspace.
7. Validate authorization, quotas, timeouts, permitted operations and deployment bindings.
8. Compile deterministic BPMN/DMN using controlled delegates and generate a source map.
9. Ask the engine to validate/deploy in a test transaction/environment; persist the release and engine mapping atomically for publication.
10. Persist artifact hashes, compiler version and immutable dependency manifest.

Only the approved form-schema keyword subset is accepted; disable remote schema retrieval and arbitrary UI code. execution retry settings apply to external service work, never to repeating a human decision. JSON Schema validates structure, not graph correctness or authorization. The implementation must perform all semantic checks above.

## Dependency manifest

Lock exact decision, form, connector-definition and compiler versions. Connection credentials can rotate without rewriting the workflow, but the operation's schema and behavior contract stay pinned. If a dependency is revoked, new dispatches fail closed and surface a policy error. A compatible secret rotation is different from silently changing an operation.

Example release manifest fields: releaseId, sourceHash, bpmnHash, dmnHashes, compilerVersion, processDefinitionId, forms, decisionVersions, connectorDefinitionVersions, environmentBindingRevision.

## Integration interface

Connector invocation input contains workspace execution identity, runId, nodeId, occurrenceId, operation reference, validated mapped input, credential reference and effect key. Output is one of succeeded(validated result), retryableFailure, permanentFailure or unknownOutcome. Audit stores metadata and redacted diagnostics.

HTTP agents later use the same interface. Asynchronous callbacks require an expiring signed/authorized correlation token, operation ownership validation and replay detection; an opaque run ID is insufficient authorization. MCP tools use registered tool names and locked schema snapshots with capability allowlists.

## Clarifications added in revision 0.2

- Authorize before serving an idempotency replay; old successful requests do not preserve access after revocation. The hash covers the normalized command, target release/task and payload in the workspace/operation scope.
- taskVersion is an application concurrency token updated on claim/reassignment/decision; the backend also uses engine transaction/concurrency controls. A UI token alone is not a lock. Return a fresh task snapshot/token after a conflict.
- Test runs use immutable test deployments and explicitly approved test bindings. Designer test permission cannot publish a production release or select production connectors.
- Record internal in-app notification creation in the engine/application transaction with a unique run/activity/occurrence/recipient key. It does not require an external HTTP journal.
- Business outcome Fulfilled indicates the purchase workflow completed successfully; engine lifecycle is completed. Rejected is also a completed process, with no procurement result.
- Serve run events using a commit-safe per-run sequence/cursor. Database IDs allocated before commit must not cause late-committing events to be skipped during polling.

### Missing core operation contracts now made explicit

All paths below use the same workspace prefix and server authorization rules.

| Method / relative path | Input / purpose | Guardrail |
|---|---|---|
| GET /me/requests | Cursor/filter; requester progress list | Server forces initiator = authenticated subject unless wider data grant exists |
| GET /me/notifications | Cursor/unread filter | Only recipient can read their notifications |
| POST /notifications/{id}/read | Mark own notification read | Idempotent, no cross-recipient access |
| POST /workflows/{id}/active-release | releaseId, expectedVersion | Publisher permission; selected release belongs to workflow; affects new starts only |
| POST /tasks/{id}/reassign | expectedVersion, eligible subject/group, reason | Operator permission + workspace/eligibility check + audit; no direct decision |
| POST /runs/{id}/retry | failed activity/effect reference, expectedVersion, reason; Idempotency-Key | Operator permission; only policy-approved safe retries; unknown effect needs reconciliation |
| POST /runs/{id}/effects/{effectId}/reconcile | Reconciliation action/evidence reference; Idempotency-Key | Approved reconciliation operation or reviewed manual evidence; no forged success without remote evidence |
| POST /connections | Versioned logical operation and environment binding refs | integration.admin grant; no secrets in response or arbitrary executable code |

Suspension/resumption APIs arrive with R1 E04. Identity bootstrap, membership/grant management and the remaining registry/schedule APIs must be frozen in OpenAPI as part of E14/E08–E13 ticket readiness; they are not implemented by this prose table.
