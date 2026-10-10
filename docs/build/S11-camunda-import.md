# S11 — Camunda BPMN import (first migration adapter)
Build specification · revision 1.0 · 9 October 2026 · first slice of E16 (R1), Demo 5

Imports a Camunda 7 or Camunda 8 BPMN 2.0 file into a reviewable Orqix **draft** with a compatibility report that gives every source element a disposition (doc 05). Nothing imported becomes executable automatically: the designer resolves mappings, the compiler validates, and a publisher publishes as usual. Running instances, history and Java/worker code are never migrated.

## 1. Pipeline

Upload → safe parse → detect source (Camunda 7 `camunda:` namespace, Camunda 8 `zeebe:` namespace, or plain BPMN) → inventory → compatibility report → designer mappings → convert to canonical v0.1 → compiler validation → draft. The original bytes, their SHA-256 and the adapter version are stored with the job.

**Safe parsing:** XML only, ≤ 1 MiB, ≤ 2,000 elements; DTDs and external entities disabled (XXE), no external schema fetching; exactly one executable `process` (others are reported, not converted).

## 2. Element dispositions (baseline adapter `camunda-bpmn-0.1`; changes in the revisions below)

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

Report severities: `blocker` (cannot convert), `action` (needs a designer mapping), `review` (converted, behaviour could differ; never blocks), `info` (acknowledged loss, e.g. listeners), `supported`. Element counts are an inventory metric only.

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

## Revision 0.2 migration expansion

The adapter now imports unambiguous paired parallel gateways, fixed positive intermediate duration timers (one minute to 90 days), and call activities after explicit `childWorkflowMappings` review. The normal compiler still validates all converted graphs, child contracts and publish-time release pins. Date/recurring/boundary timers and ambiguous gateway regions remain blocked. See `docs/17-migration-workbench.md` for the supported surface.

Pega archive inspection recognizes bounded XML identity fields and unresolved dependency references, and suggests reviewed target mappings according to recognized rule class. It retains file-level disposition and provenance; no rule-resolution equivalence or automatic native conversion is claimed. Genuine customer exports and expected scenarios remain acceptance inputs.

## Revision 0.3 projects and independent review

Multi-file projects (`MigrationProjects`, `ProjectAnalyzer`, V17) order BPMN files by call-activity dependencies and convert them one at a time through `convert-next`; the independent review policy (`MigrationGovernance`, V18) binds an approval to the draft, source and mappings hashes and requires mapper, reviewer and publisher to be three people before a migrated workflow can be published. Behavior, limits and the permission matrix are in `docs/17-migration-workbench.md`; integration tests are `MigrationProjectApiTest` and `MigrationGovernanceApiTest`.

## Revision 0.4 conversion expansion (adapter `camunda-bpmn-0.4`, DMN adapter `dmn-0.1`)

The section 2 table is the 0.1 baseline; this revision changes these rows. See `docs/17-migration-workbench.md` for the user-facing description, the severity model and the exact limits.

| Source element | Disposition now | Canonical result |
|---|---|---|
| Any converted construct whose behaviour can differ | **`review`** (non-blocking, counted in `elementCounts.review`, persisted in the report, acknowledged with `acknowledgeReview`) | the target policy, finish outcome kind, inferred approval routes and request fields, converted conditions, mapped timers, parallel regions, mapped service/child/decision calls, multi-instance, DMN rules |
| `exclusiveGateway` | Mapped (`review` per condition, original text kept) | `branch` with `eq ne gte gt lt lte` and flat `all` / `any` groups of 2 to 8 comparisons, JUEL and FEEL. The compiler proves the paths exclusive |
| Gateway with `not(...)` over a comparison, mixed and/or, nested groups, functions, property access | Blocked | `EXPRESSION_NOT_SUPPORTED` on the gateway and on the flow, with the source text and the reason |
| `serviceTask` (delegate class, delegate expression, expression, external task topic, Zeebe job type) | Blocked until mapped; tasks with the same implementation also get one grouped `action` item (`SERVICE_TASK_GROUP`) | `http`; `serviceGroupMappings` (one mapping per group) or `serviceTaskMappings` (per task, overrides the group) |
| `businessRuleTask` with a literal decision id | Blocked until `decisionMappings` points at an uploaded DMN migration, then `review` | `decision` step with the converted table; a gateway on its single-entry result variable reads `/steps/<task>/<output>` |
| `businessRuleTask` with a delegate/expression/worker or a dynamic decision id | Blocked (`DMN_IMPORT_NOT_SUPPORTED`) | none |
| `callActivity` with `multiInstanceLoopCharacteristics` over a plain collection | `action` until the child mapping carries `forEach`, then `review` | `startWorkflow` with `forEach {itemsRef, itemInput, maxItems, mode}`; the collection becomes a required bounded array field |
| Other multi-instance (user task, fixed cardinality, completion condition, output collection, expression collection, standard loop) | Blocked | `MULTI_INSTANCE_NOT_SUPPORTED` with the specific reason; loop child elements are dispositioned too |

Dropped listeners keep `info` (acknowledgement required): the importer cannot judge executable code harmless.

**DMN adapter.** Source format `dmn`; `sourceSystem` `dmn`; the file is stored like any source (bytes, SHA-256, adapter version) in `migration_job` (migration V19 allows the format). The report has a disposition for the definitions, every decision, table, column and rule. Hit policies UNIQUE and FIRST with one tested input and literal unary tests and outputs convert; all else is a blocker with a `DMN_*` reason code. The converted table is built by exact set algebra (`Region`: number intervals with open/closed bounds, integer normalisation, finite and co-finite value sets), so that overlap, gap, order and default decisions are proofs, not heuristics. Because decision tables exist only inside workflows, converting a DMN migration creates a draft that hosts one decision (start, decision, finish; request form from the input, result from the outputs); a business rule task attaches the same table to a BPMN draft. `report.testCases` holds the generated boundary cases: each is answered by the source semantics and by the compiler's `DecisionEvaluator`; a mismatch refuses the table.

**API additions** (additive): upload `sourceFormat: dmn`; convert request `serviceGroupMappings`, `decisionMappings`, `decisionId`, `acknowledgeReview`, `childWorkflowMappings[*].forEach` and `collectionMapping`; report `elementCounts.review`, `requiredMappings.serviceGroups` and `decisionTasks`, `testCases`, `sourceSystem: dmn`. Existing request keys are unchanged. `publishable` is not affected by review items.

**Tests.** `ExpressionsTest` (operators, groups, every unsupported form), `ConverterExpansionTest` (review items, groups, operators validated by the compiler and walked with its condition matcher, multi-instance validated against a published child), `DmnImportTest` (fixtures, hand-written boundary expectations through the compiler's evaluator, every unsupported construct, and 600 seeded random tables checked against an independent oracle), `FixtureDispositionTest` (every element of every new fixture) and `MigrationApiTest` (review counts persisted, grouped mapping, operators executed by Flowable, DMN converted and boundary values run through the rule tester and a published release, business rule task attached to a decision, multi-instance executed with two child runs). All fixtures are hand-authored and synthetic.
