# S11 — Camunda BPMN import (first migration adapter)
Build specification · revision 1.0 · 9 October 2026 · first slice of E16 (R1), Demo 5

Imports a Camunda 7 or Camunda 8 BPMN 2.0 file into a reviewable Orqix **draft** with a compatibility report that gives every source element a disposition (doc 05). Nothing imported becomes executable automatically: the designer resolves mappings, the compiler validates, and a publisher publishes as usual. Running instances, history and Java/worker code are never migrated.

## 1. Pipeline

Upload → safe parse → detect source (Camunda 7 `camunda:` namespace, Camunda 8 `zeebe:` namespace, or plain BPMN) → inventory → compatibility report → designer mappings → convert to canonical v0.1 → compiler validation → draft. The original bytes, their SHA-256 and the adapter version are stored with the job.

**Safe parsing:** XML only, ≤ 1 MiB, ≤ 2,000 elements; DTDs and external entities disabled (XXE), no external schema fetching; exactly one executable `process` (others are reported, not converted).

## 2. Element dispositions (adapter `camunda-bpmn-0.1`)

| Source element | Disposition | Canonical result |
|---|---|---|
| `startEvent` (none) | Supported | `start` |
| `endEvent` (none) | Supported | `end`; outcome label from the element name, kind `rejected` when the name contains "reject"/"denied", else `success` (assumption, editable) |
| `sequenceFlow` | Supported | transition (ids preserved when valid, else sanitized with a source map entry) |
| `userTask` with candidate groups (`camunda:candidateGroups` / `zeebe:assignmentDefinition candidateGroups`) | Mapped | `approval` when followed by an exclusive gateway whose two outgoing conditions test the same boolean variable (`${approved}`, `${!approved}`, `=approved = true`, …) → `approved` / `rejected` routes. Group needs a mapping to a workspace group |
| `userTask` without that pattern | Needs action | Designer selects which outgoing flow means approved and which means rejected, or the task stays blocked |
| `userTask` with assignee only | Needs action | Map to a workspace group |
| `exclusiveGateway` with simple comparisons (`${amount >= 5000}`, `=amount >= 5000`, `==`, `<`, string equality) on one variable | Mapped | `branch`; the variable becomes an input field (type inferred, recorded as an assumption); default flow preserved |
| `exclusiveGateway` with other expressions | Blocked | `EXPRESSION_NOT_SUPPORTED` |
| `serviceTask` (`camunda:class`, `delegateExpression`, `expression`, `type="external"`, `zeebe:taskDefinition`) | Blocked until mapped | `JAVA_DELEGATE_NOT_PORTABLE` / `WORKER_NOT_PORTABLE`; a mapping to a registered connection operation (with input/result mapping) turns it into `http` |
| `businessRuleTask` | Blocked | `DMN_IMPORT_NOT_SUPPORTED` (rebuild as a decision table) |
| `scriptTask`, `sendTask`, `receiveTask`, `manualTask` | Blocked | `TASK_TYPE_NOT_SUPPORTED` |
| `parallelGateway`, `inclusiveGateway`, `eventBasedGateway` | Blocked | `GATEWAY_NOT_SUPPORTED_IN_V0` (parallel arrives with E04) |
| Timers, boundary/intermediate events, message/signal/error events, compensation | Blocked | `EVENT_NOT_SUPPORTED_IN_V0` |
| `subProcess`, `callActivity`, multi-instance, execution/task listeners, `camunda:inputOutput`, forms | Blocked or Info | Reported with reason; listeners/forms are Info (dropped knowingly, acknowledgement required) |
| Unknown elements | Blocked | `UNKNOWN_ELEMENT` — never silently omitted |

Report severities: `blocker` (cannot convert), `action` (needs a designer mapping), `info` (acknowledged loss, e.g. listeners), `supported`. Element counts are an inventory metric only.

## 3. API (OpenAPI tag *Migration*)

| Operation | Rule |
|---|---|
| `POST /workspaces/{ws}/migrations` `{fileName, content}` | `workflow.edit`; parses and stores the job; `201 MigrationJob` with the report. Audit `migration.uploaded` |
| `GET /workspaces/{ws}/migrations/{id}` | `workflow.read` |
| `POST /workspaces/{ws}/migrations/{id}/convert` `{workflowKey, name, groupMappings, approvalRoutes, serviceTaskMappings, acknowledgeInfo}` | `workflow.edit`; unresolved blockers/actions → `422 MIGRATION_BLOCKED` with the open items; success → `201 {workflow, draft, report}` with the canonical draft (source map stored in `metadata` is not allowed by the schema, so the job keeps the element → node map). Audit `migration.converted` |

Database (V6): `migration_job(id, workspace_id, created_by, file_name, source_format, source_sha256, source bytea, adapter_version, report jsonb, status, converted_workflow_id, created_at, updated_at)`.

## 4. User interface

Workflows → **Import** → upload a `.bpmn` file → compatibility report (counts by disposition; per-element table with source id, name, type, disposition chip, explanation and suggested action) → mapping form (groups, approval routes, service-task operations from `listConnections`) → **Convert to draft** → studio opens with a banner "Imported from Camunda — review and test before publishing" and each step's source element id shown under *Advanced*.

## 5. Fixtures and tests

`examples/migration/`: `camunda7-purchase.bpmn` and `camunda8-purchase.bpmn` (hand-authored compatibility fixtures, clearly labelled as such — they are not customer exports), `unsupported-features.bpmn` (one of each blocked construct) and `xxe-attack.bpmn`; the expected dispositions are asserted in `FixtureDispositionTest`. Tests: every element of every fixture has a disposition; XXE and oversized files rejected; purchase fixtures convert (with mappings) into drafts that pass the compiler and run end to end like the template (approval, threshold, procurement); behavior comparison against the original BPMN for the decision boundaries (4,999.99 / 5,000).
