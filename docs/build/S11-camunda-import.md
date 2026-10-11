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
| Gateway with `not(...)` over a group, mixed and/or, nested groups, functions, property access (`not(<one comparison>)` converts since 0.5) | Blocked | `EXPRESSION_NOT_SUPPORTED` on the gateway and on the flow, with the source text and the reason |
| `serviceTask` (delegate class, delegate expression, expression, external task topic, Zeebe job type) | Blocked until mapped; tasks with the same implementation also get one grouped `action` item (`SERVICE_TASK_GROUP`) | `http`; `serviceGroupMappings` (one mapping per group) or `serviceTaskMappings` (per task, overrides the group) |
| `businessRuleTask` with a literal decision id | Blocked until `decisionMappings` points at an uploaded DMN migration, then `review` | `decision` step with the converted table; a gateway on its single-entry result variable reads `/steps/<task>/<output>` |
| `businessRuleTask` with a delegate/expression/worker or a dynamic decision id | Blocked (`DMN_IMPORT_NOT_SUPPORTED`) | none |
| `callActivity` with `multiInstanceLoopCharacteristics` over a plain collection | `action` until the child mapping carries `forEach`, then `review` | `startWorkflow` with `forEach {itemsRef, itemInput, maxItems, mode}`; the collection becomes a required bounded array field |
| Other multi-instance (user task, fixed cardinality, completion condition, output collection, expression collection, standard loop) | Blocked | `MULTI_INSTANCE_NOT_SUPPORTED` with the specific reason; loop child elements are dispositioned too |

Dropped listeners keep `info` (acknowledgement required): the importer cannot judge executable code harmless.

**DMN adapter.** Source format `dmn`; `sourceSystem` `dmn`; the file is stored like any source (bytes, SHA-256, adapter version) in `migration_job` (migration V19 allows the format). The report has a disposition for the definitions, every decision, table, column and rule. Hit policies UNIQUE and FIRST with one tested input and literal unary tests and outputs convert; all else is a blocker with a `DMN_*` reason code. The converted table is built by exact set algebra (`Region`: number intervals with open/closed bounds, integer normalisation, finite and co-finite value sets), so that overlap, gap, order and default decisions are proofs, not heuristics. Because decision tables exist only inside workflows, converting a DMN migration creates a draft that hosts one decision (start, decision, finish; request form from the input, result from the outputs); a business rule task attaches the same table to a BPMN draft. `report.testCases` holds the generated boundary cases: each is answered by the source semantics and by the compiler's `DecisionEvaluator`; a mismatch refuses the table.

**API additions** (additive): upload `sourceFormat: dmn`; convert request `serviceGroupMappings`, `decisionMappings`, `decisionId`, `acknowledgeReview`, `childWorkflowMappings[*].forEach` and `collectionMapping`; report `elementCounts.review`, `requiredMappings.serviceGroups` and `decisionTasks`, `testCases`, `sourceSystem: dmn`. Existing request keys are unchanged. `publishable` is not affected by review items.

**Tests.** `ExpressionsTest` (operators, groups, every unsupported form), `ConverterExpansionTest` (review items, groups, operators validated by the compiler and walked with its condition matcher, multi-instance validated against a published child), `DmnImportTest` (fixtures, hand-written boundary expectations through the compiler's evaluator, every unsupported construct, and 600 seeded random tables checked against an independent oracle), `FixtureDispositionTest` (every element of every new fixture) and `MigrationApiTest` (review counts persisted, grouped mapping, operators executed by Flowable, DMN converted and boundary values run through the rule tester and a published release, business rule task attached to a decision, multi-instance executed with two child runs). All fixtures are hand-authored and synthetic.

## Revision 0.5 Camunda 8 (adapter `camunda-bpmn-0.5`, form adapter `camunda-form-0.1`)

First-class reading of the Camunda 8 (`zeebe:`) constructs. The report states the source version (`sourceVersion`, from `modeler:executionPlatformVersion`; a file with only the modeler attributes is detected as Camunda 8 or 7 by platform) and, as its first assumption, what the adapter does and does not cover ("not a claim of general Camunda 8 compatibility"). All fixtures are hand-authored.

| Camunda 8 construct | Disposition | Canonical result / reason code |
|---|---|---|
| `zeebe:taskDefinition type` (job worker) | Blocker until mapped (`WORKER_NOT_PORTABLE`); the job type is shown and the suggested operation name is derived (`createPurchaseOrder`); ioMapping variables become suggestions | `http` through `serviceTaskMappings` / `serviceGroupMappings` |
| REST connector `io.camunda:http-json:1` | Blocker until mapped (`CONNECTOR_MAPPING_REQUIRED`), then review (`CONNECTOR_MAPPED`) | `http` against the operation the designer selects. The original method, host and path are read-only provenance (`mappingHints.services`); header and query parameter names only, never values; `{{secrets...}}`, credentials in URLs and query values are never stored. Request fields are suggested from `body`/`queryParameters` entries that read a plain variable; result pointers from `resultExpression` (`response.body.id` becomes `/id`). The URL, headers and authentication belong to the connection binding, never to the workflow |
| Other `io.camunda:` connectors (Slack, e-mail, GraphQL, ...), inbound connectors (`inbound.*` properties) | Blocker `CONNECTOR_NOT_SUPPORTED` with a suggested replacement (notification step or a registered connection) | none |
| FEEL conditions | Same operators as before plus `not(<one comparison>)` (the operator is inverted: `not(a >= 5)` becomes `a < 5`; booleans flip) | `branch`. Function calls, `in`, `between`, `if`, null checks, lists/ranges, mixed `and`/`or`, nested groups, property access: blocker `EXPRESSION_NOT_SUPPORTED` that shows the source expression and the specific reason. A null check has no canonical equivalent (a field a condition reads is always provided) |
| `zeebe:assignmentDefinition`, `zeebe:userTask` marker | As before (candidate groups need a workspace group; expressions blocked `ASSIGNMENT_NOT_SUPPORTED`) | `approval` |
| `zeebe:taskSchedule dueDate` as `now() + duration("PT48H")`, `duration(...)` or ISO duration of whole hours (1 to 720) | Review `DUE_DATE_MAPPED` | approval `dueAfterHours` (display only, as in S12). Absolute or computed dates and `followUpDate`: info (`DUE_DATE_NOT_IMPORTED`, `FOLLOW_UP_NOT_IMPORTED`) |
| Interrupting timer boundary event (constant duration, 1 minute to 90 days) on a user task that becomes an approval | Review `BOUNDARY_TIMER_MAPPED` | approval `timeoutAfter` and the `timedOut` route (the flow leaving the timer). Non-interrupting, date, cycle and message boundary events, or a task that cannot become an approval: blocker (`EVENT_NOT_SUPPORTED_IN_V0`, `MESSAGE_EVENT_NOT_SUPPORTED`) |
| Timer catch event: `timeDuration` literal, `="PT2H"` or `duration("PT2H")` | Review `DURATION_TIMER_MAPPED` | `wait.duration` |
| Timer catch event: `timeDate` that is a variable (`=startDate`) | Review `DATE_TIMER_MAPPED` | `wait.untilField` and a new required request date field (09:00 UTC, S12 section 10). A literal or computed date: `TIMER_DATE_NOT_SUPPORTED`; `timeCycle`: `TIMER_CYCLE_NOT_SUPPORTED`; an out-of-range duration: `TIMER_DURATION_NOT_SUPPORTED` |
| Message and signal events (catch, boundary) | Blocker `MESSAGE_EVENT_NOT_SUPPORTED`, `SIGNAL_EVENT_NOT_SUPPORTED`. The compiler had no `waitForMessage` when this revision was written | none; revisit when message events land |
| Parallel gateway pairs, multi-instance call activity | As in revisions 0.2 and 0.4 | `parallelSplit`/`parallelJoin`, `startWorkflow` with `forEach` |
| Call activity `zeebe:calledElement processId` | Action `CHILD_WORKFLOW_MAPPING_REQUIRED` (uses the process id), the call input/output mapping is offered as suggestion (`mappingHints.calls`) | `startWorkflow`. `propagateAllChildVariables`/`propagateAllParentVariables` (default true in Camunda 8) add the info item `PROPAGATE_ALL_NOT_SUPPORTED`: Orqix passes only the mapped fields |
| `zeebe:ioMapping` (other elements) | Info `IO_MAPPING_NOT_IMPORTED` listing the variables it sets (values are never echoed) | none |
| Business rule task with `zeebe:calledDecision` | Action `DECISION_MAPPING_REQUIRED` until `decisionMappings` links an uploaded DMN migration, then review `DECISION_MAPPED` | `decision` |
| `zeebe:formDefinition` | see Forms | |
| `zeebe:priorityDefinition`, `versionTag`, `linkedResources`, `conditionalFilter` | Info `SETTING_NOT_IMPORTED` | none |
| Job-worker user task (`zeebe:taskDefinition` on a user task) | Info `JOB_WORKER_USER_TASK` | `approval` |

**Forms.** A Camunda Forms file (`.form`, JSON, schema `camunda-forms`) is uploaded with `sourceFormat: camundaForm` (V24 adds the value to the `migration_job.source_format` check; the bytes, hash and adapter version are stored like any source) and gets its own compatibility report (`sourceSystem: camundaForm`, one disposition per component) and a preview (`mappingHints.formPreviews`). A form has no target of its own (convert returns `422 MIGRATION_BLOCKED`); it is attached to a BPMN import: a form linked by `zeebe:formDefinition formId` shows `FORM_NOT_IMPORTED` (info) with state `upload` in `mappingHints.forms`, and `formMappings: {<start event or user task id>: {migrationId}}` attaches an uploaded form whose own id must equal the referenced `formId`. A form embedded in the BPMN (`zeebe:userTaskForm`, referenced with `formKey="camunda-forms:bpmn:<id>"`) is read from the file itself. A start event form becomes the request form (`inputSchema`, `inputUi.order` and widgets); a user task form becomes a plain-text step note (`metadata.stepNotes`) on the approval, because approvals do not collect fields. A form field that a gateway also compares must have a compatible type; a field that a gateway reads is required in the request form (stated as an assumption).

| Component | Disposition |
|---|---|
| `textfield`, `textarea` | field (`text`, `textarea`); min/max length kept; pattern and `validationType`: noted as a difference |
| `number` | `number` (or `integer` with `decimalDigits` 0; two digits become `multipleOf 0.01`); min/max kept |
| `checkbox` | yes/no field (`required` means "must be ticked" in the source: noted) |
| `select`, `radio` with static values | text field with `enum` (at most 50 values; letters, digits, spaces and `_ . , : / -` only); option labels that differ from values: noted |
| `datetime` with subtype `date` | date field; other subtypes blocked |
| `group` (static) | info `FORM_GROUP_FLATTENED`; a group with a `path`, `isDynamic` or nested too deeply: blocked |
| `text`, `html`, `image`, `separator`, `spacer`, `button` | info `FORM_DISPLAY_NOT_IMPORTED` |
| `table`, `dynamiclist`, `filepicker`, `expression`, `iframe`, `documentPreview`, `checklist`, `taglist`, unknown | blocker `FORM_COMPONENT_NOT_SUPPORTED` |
| `valuesKey`, `valuesExpression`, `conditional.hide`, label expressions | blockers `FORM_OPTIONS_NOT_STATIC`, `FORM_EXPRESSION_NOT_SUPPORTED` |
| invalid or duplicate key, more than 30 fields, more than 300 components, no form id | blockers `FORM_KEY_NOT_SUPPORTED`, `FORM_TOO_LARGE`, `FORM_ID_MISSING` |
| default values | info `FORM_DEFAULT_NOT_IMPORTED` (the canonical field schema has no defaults) |

Form JSON is parsed with the existing strict JSON mapper (duplicate keys and trailing tokens rejected, depth at most 64) after the unchanged 1 MiB source limit; no form expression is ever evaluated.

**API additions** (additive): upload `sourceFormat: camundaForm`; `CompatibilityReport.sourceSystem` value `camundaForm` and the optional object `mappingHints` (`services`, `forms`, `calls`, `formPreviews`); convert request `formMappings`. Reason codes added: `CONNECTOR_MAPPING_REQUIRED`, `CONNECTOR_MAPPED`, `CONNECTOR_NOT_SUPPORTED`, `DECISION_MAPPING_REQUIRED` (Camunda 8 only), `DUE_DATE_MAPPED`, `DUE_DATE_NOT_IMPORTED`, `FOLLOW_UP_NOT_IMPORTED`, `BOUNDARY_TIMER_MAPPED`, `DATE_TIMER_MAPPED`, `TIMER_CYCLE_NOT_SUPPORTED`, `TIMER_DATE_NOT_SUPPORTED`, `TIMER_DURATION_NOT_SUPPORTED`, `MESSAGE_EVENT_NOT_SUPPORTED`, `SIGNAL_EVENT_NOT_SUPPORTED`, `PROPAGATE_ALL_NOT_SUPPORTED`, `JOB_WORKER_USER_TASK`, `FORM_*` (`FORM_MAPPED`, `TASK_FORM_NOTED`, `FORM_FIELD_MAPPED`, `FORM_FIELD_MAPPED_WITH_CHANGES`, `FORM_NOT_FOUND`, `FORM_INVALID`, `FORM_SHARED` and the component codes above). The JSON and CSV exports are built from the stored report, so they carry the new codes without change.

**Fixtures** (`examples/migration`): `camunda8-purchase.bpmn` (job worker, version 8.3.0), `camunda8-connector-http.bpmn`, `camunda8-user-task-form.bpmn` with `camunda8-user-task-form.form`, `camunda8-feel-gateways.bpmn`, `camunda8-timer-callactivity.bpmn`, `camunda8-unsupported.bpmn`.

**Tests.** `Camunda8ImportTest` (22: every element of every fixture has a disposition, version and limits, connector hints and secret handling, FEEL positives and negatives, each blocked construct's reason code, forms, timers, call activity, compiler validation of the conversions), `Camunda8MigrationApiTest` (4 against PostgreSQL, Flowable and the stub: connector mapped to the procurement operation and run through approval, the 5,000 boundary and the call; FEEL gateway paths; a form file attached to a BPMN import and run; duration timer, boundary-timer approval and pinned child run), `ExpressionsTest` (negation, null, function, `in`, `between`, list cases). Frontend: `camunda8.test.ts` (mock inventory), `Camunda8Review.test.tsx`; Playwright mock spec `t-mig-02-camunda8.spec.ts`.

**Limits stated honestly.** The adapter reads the listed constructs from hand-authored fixtures and the public file format; it has not been run against customer exports or against every modeler version. A converted connector call behaves like the operation you chose, not like the connector settings. Timer boundary semantics, due times and forms are approximations that are listed as review items. Message events, other connectors, FEEL beyond the subset, sub-processes, scripts and event sub-processes remain blockers.
