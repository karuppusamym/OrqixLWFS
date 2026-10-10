# Migration workbench

Implemented 9 October 2026. Open **Workflows → Import workflow** at http://localhost:8080. Imports create drafts; publishing and activating a release remain separate actions.

## Camunda 7 and 8

Upload BPMN XML, inspect the compatibility report, map source assignments to workspace groups, and map service tasks to registered connection operations. The operation helper pins the selected contract version and proposes request/result mappings for review. Review inferred input fields, approval/rejection routes and target policies before acknowledging assumptions and converting.

The supported subset includes none start/end events, sequence flows, group approval tasks, recognized approval outcomes, typed exclusive conditions (comparisons and flat and/or groups, see below), explicitly mapped HTTP service tasks (one mapping per worker type, see below), unambiguous parallel split/join regions, constant duration timer catches, explicitly mapped child-process calls, multi-instance call activities over a plain collection with a designer-set bound, and business rule tasks that call a DMN decision uploaded as a separate migration. Source delegates and workers are never executed. Unsupported constructs, expressions and unresolved mappings block conversion. Self-approval is denied and rejection requires a reason in the resulting draft; these assumptions are displayed for review.

### Severity model (adapter `camunda-bpmn-0.4`)

Every source element and nested construct gets one disposition. The five severities follow the meaning of Camunda's Diagram Converter levels (INFO nothing to do, REVIEW converted but verify, WARNING no direct equivalent so a decision is needed, TASK manual work); the mapping below is by meaning, not a claim that the tool classifies identically.

| Report severity | Converter level | Meaning | Gate |
|---|---|---|---|
| `supported` | INFO | Exact structural mapping with no behaviour to verify (start event, plain flow, removed merge gateway, paired join) | none |
| `info` | INFO (warning that may be ignored) | Source content that is not imported and carries no converted behaviour: documentation, layout, forms, engine settings, input/output mappings, lanes, **dropped listeners**, unused DMN columns | `acknowledgeInfo` must be true or the report is not publishable |
| `review` | REVIEW | Converted, but behaviour could differ from the source: converted comparisons (original text kept), mapped timers, inferred approval routes, inferred request fields and outcome kinds, mapped service/child/decision calls, bounded for-each, DMN rules | never blocks; open until the designer sets `acknowledgeReview`, then `acknowledged`. Always counted in `elementCounts.review` and stored in the report, which the studio and the exports show |
| `action` | WARNING | No direct equivalent; the designer must choose (workspace group, approval route, child workflow, one service mapping per worker group) | blocks until resolved |
| `blocker` | TASK | Cannot be converted; rebuild by hand | blocks; acknowledging cannot remove it |

Decisions made while classifying: dropped listeners stay `info` with a required acknowledgement rather than `review`, because they run code whose effect the importer cannot judge harmless; a `review` item must never be the only record of a loss. `review` never changes `publishable`. Service, child-call and decision items start as `blocker`/`action` and are shown as `review` (codes `SERVICE_MAPPED`, `CHILD_WORKFLOW_MAPPED`, `DECISION_MAPPED`) once the designer's mapping resolved them, because the mapped behaviour (timeouts, retries, results) still differs from the source.

Counts are an inventory metric: resolved items stay counted under their severity. CSV and JSON exports of the report are built by the studio from the stored report; the backend returns the full report, including `review` counts.

### Condition operators

Gateway conditions convert for both JUEL (`${amount > 5000 && region != 'EU'}`) and FEEL (`= amount > 5000 and region != "EU"`) when they are exactly what the canonical workflow and compiler accept: one comparison with `eq ne gte gt lt lte` (ordering operators on numbers only; the compiler rejects them on text and yes/no fields), a bare or negated yes/no variable, or a flat group of 2 to 8 such comparisons joined only by `and` or only by `or` (canonical `all` / `any`). Mixed `and`/`or`, parentheses around groups, `not` over a comparison, functions, property access, arithmetic and null stay blocked with a reason on the gateway and on the flow, and the original text is kept. Each converted condition is a `review` item (`CONDITION_CONVERTED`) with the source text. Camunda takes the first true flow in document order, Orqix needs mutually exclusive conditions: the importer converts as written and the compiler rejects overlaps (`WF_BRANCH_OVERLAP`) when the draft is created, so no draft with overlapping paths exists.

### Grouped service tasks

Service tasks that share an implementation are listed once as a grouped `action` item (`SERVICE_TASK_GROUP`) with the keys `topic:<external task topic>`, `jobType:<Zeebe job type>`, `class:<delegate class>`, `delegateExpression:<text>` or `expression:<text>` (the text is only an identity, never evaluated; computed job types and topics such as `=type` are not grouped). `requiredMappings.serviceGroups` lists the task ids of every group. One entry under `serviceGroupMappings` (same shape as a `serviceTaskMappings` entry) maps every task of the group; a `serviceTaskMappings` entry for a task overrides its group. Every task keeps its own disposition item; the existing per-task mapping format is unchanged.

### Multi-instance call activities

A call activity with `multiInstanceLoopCharacteristics` over a plain collection variable and an element variable (Camunda 7 `camunda:collection` / `camunda:elementVariable`, Camunda 8 `zeebe:loopCharacteristics inputCollection` / `inputElement`) converts to the bounded for-each of [S14](build/S14-bounded-iteration.md). The collection becomes a required request array field; its size is unbounded in the source, so the child mapping must carry `forEach: {itemInput, maxItems (1 to 25), itemType}` (`itemType` is only needed without an explicit `inputSchema`; the collection field is generated from it). `isSequential` selects the for-each mode. Optional `collectionMapping` collects child results in item order. Items must be text, number, integer or yes/no. A fixed `loopCardinality`, a `completionCondition`, a Camunda 8 output collection, an expression as collection, standard loops and multi-instance user tasks, service tasks and other activities remain `MULTI_INSTANCE_NOT_SUPPORTED` blockers with the specific reason. The compiler validates the bound, item type and child mapping (`WF_FOREACH_*`).

### DMN decision tables

Upload a Camunda DMN 1.1 to 1.4 file with source format `dmn` (up to 1 MiB, parsed under the same XML limits). Each decision table gets a disposition for the decision, table, columns and rules (cells are reported with their rule).

Decision tables exist in the product only inside workflows, so a DMN file has no standalone target:

- **Convert the DMN migration** (`workflowKey`, `name`, optional `decisionId` when the file holds several decisions) to create a draft that hosts the table in the smallest valid workflow: a request form with the tested input, one decision step and a result that returns the decision outputs. The draft is validated by the normal compiler and its table can be tested with the draft's rule tester.
- **Attach it to a BPMN import**: a business rule task that calls a literal decision id (`camunda:decisionRef`, `zeebe:calledDecision`) becomes a decision step when `decisionMappings[taskId] = {migrationId, decisionId?, inputRef?}` points at the uploaded DMN migration of the same workspace. A gateway that reads the task's single-entry result variable (`mapDecisionResult="singleEntry"`, or a Camunda 8 result with one output) reads that decision output; list and map results stay blocked.

Supported: hit policy `UNIQUE` (rules must not overlap, checked exactly) and `FIRST` (rule order is folded into mutually exclusive rows); one tested input column (other columns must be `-` in every rule and are dropped as an info item); input type `string`, `boolean`, `integer`, `long`, `double`, `number` (or inferred from literal entries when undeclared); literal FEEL unary tests: `-`, numbers, `>= > < <=` comparisons, ranges `[a..b]` `(a..b)` `]a..b[`, comma lists, double-quoted text, `true`/`false` and `not(...)` around a list; literal outputs of the same types. Every canonical row holds one comparison, so a bounded range, `not(a, b)` or a catch-all is carried as the table default when exactly one rule needs it and the other rules cover everything else; a table with values that match no rule is blocked (the source returns no result, a canonical table always returns its default).

Blocked with reason codes: other hit policies (`DMN_HIT_POLICY_NOT_SUPPORTED`: ANY, PRIORITY, COLLECT, RULE ORDER, OUTPUT ORDER), expression/script/variable entries (`DMN_ENTRY_NOT_SUPPORTED`), computed or empty outputs (`DMN_OUTPUT_EXPRESSION_NOT_SUPPORTED`, `DMN_OUTPUT_EMPTY`), several tested inputs (`DMN_MULTIPLE_INPUTS_NOT_SUPPORTED`), non-variable input expressions (`DMN_INPUT_EXPRESSION_NOT_SUPPORTED`), unsupported types such as dates (`DMN_TYPE_NOT_SUPPORTED`), overlapping UNIQUE rules (`DMN_UNIQUE_OVERLAP`), gaps (`DMN_NO_MATCH_NOT_REPRESENTABLE`), two or more rules that need bounded ranges (`DMN_RULE_NOT_REPRESENTABLE`), text with characters canonical workflows reject (`DMN_LITERAL_NOT_ALLOWED`), decision requirements graphs, literal-expression decisions, decision services and business knowledge models (`DMN_DRD_NOT_SUPPORTED`, `DMN_DECISION_TYPE_NOT_SUPPORTED`). A `defaultOutputEntry` is not converted.

For every converted table the importer generates boundary cases (one step below, at and one step above every threshold, every listed text value and an unlisted one, plus extremes), answers each with the source semantics and with the compiler's table evaluation, and puts the results in `report.testCases`. Any difference refuses the table (`DMN_SELF_CHECK_FAILED`). These checks prove the conversion, not the business decision: compare with the source decision on real samples before relying on it.

Conversion validates the canonical definition with the normal compiler before creating a workflow. Invalid mappings create no draft. Repeating the same successful conversion returns the original workflow; changing that conversion request is rejected. The studio links back to the source report. Original uploaded bytes, SHA-256, adapter version, approved mappings and source-to-target IDs persist in the workspace.

Conversion rejects unknown request properties, incorrect field types, fractional or string contract versions/timeouts, unknown source mapping IDs and approval routes that contradict a recognized source gateway. An explicit request schema is checked by the compiler instead of being constrained by inference. Source maps include gateways and flows absorbed into target approvals or merge destinations; these may map to a target node rather than a distinct transition.

After conversion commits, the created draft opens independently of report refresh. A failed report fetch shows a retry action and keeps the report link available.

### Parallel work, waits and child processes

The `camunda-bpmn-0.2` adapter maps separate diverging/converging parallel gateways only when a unique matching join is identifiable. The normal compiler verifies branch boundaries; inclusive/event gateways, mixed split/join gateways, ambiguous pairs and unsupported scopes remain blockers.

Intermediate timer catches support fixed positive day/hour/minute durations, bounded from one minute to 90 days. Date, recurring, expression-based and boundary timers remain blocked. Each wait survives runtime restarts.

Call activities require `childWorkflowMappings` keyed by source call ID. A value contains `workflowRef`, `inputMapping` and `resultMapping` using canonical field references. Import and publish the child separately first; publishing the parent pins the child release. Source binding/version settings and input/output extensions require reviewed interpretation; execution of source code is never implied.

## Multi-file projects

**Workflows → Import workflow → Import a multi-file project** analyzes several BPMN files of one application together (up to 10 files, 1 MiB each, 4 MiB in total; `POST /workspaces/{ws}/migrations/projects`). Call activities are matched to process IDs in the other files and the files are ordered children first. The project page lists each file with its process ID, the processes it calls and its status, the conversion order, and project-level findings.

A project is blocked, with no conversion order, while a called process is not part of the project, two files define the same process ID (a call would be ambiguous), the calls form a cycle, a call target is missing or computed at runtime, or a file is not BPMN. Nothing can be converted until the findings are fixed in the source files and the project is imported again; a stored project is never edited.

**Convert next file** (`POST /workspaces/{ws}/migrations/projects/{id}/convert-next`) shows the same compatibility report and mapping form as a single import for the next file in the order and converts it to a draft. The request names the migration it expects to convert next, so a stale page or a repeated request gets `409` instead of converting the wrong file or creating a second draft. A parent's `childWorkflowMappings` must name a child that is already published, so publish each child before converting the files that call it. Each file keeps its own audit trail (`migration.uploaded`, `migration.converted`); the project itself is recorded as `migration.project.created`.

## Independent review of migrated workflows

By default a publisher can publish a migrated draft like any other. A workspace administrator (`workspace.manage-access`) can require an independent review in **Settings → Migration review** (`GET`/`PUT /workspaces/{ws}/migration-policy`). Changing the setting needs a reason and the version that was loaded, so two administrators cannot overwrite each other, and is audited as `migration.policy.changed`.

When the setting is on, publishing a workflow that was created by a migration needs three different people:

| Role | Who | What they cannot do |
|---|---|---|
| Mapper | Whoever converted the source (recorded at conversion) | Approve or publish it |
| Reviewer | A person with `workflow.publish` who approves the exact draft with a reason (`POST /workspaces/{ws}/workflows/{id}/migration-review`) | Be the mapper or the author of the latest draft revision; publish it |
| Publisher | A person with `workflow.publish` | Be the mapper, the reviewer or the author of the latest draft revision |

An approval applies to one draft revision and is bound to the canonical draft, the stored source hash and the stored mappings. It stops counting when the draft is saved again, when the source or mappings differ, or when the reviewer no longer has publish permission; publishing then needs a new review. Conversions made before the mapper was recorded cannot be approved, so with the setting on they cannot be published; turn the setting off or import the source again. Workflows that were not created by a migration are not affected, and publishing is unchanged while the setting is off.

The publish page shows a **Migration review** section for migrated workflows: who mapped it, who reviewed it and why, the draft, source and mappings hashes (full values under *Show full hashes*), whether the approval covers exactly these hashes, and why publishing is or is not blocked. The Approve button is disabled with the reason when you are the mapper or the draft author, or lack publish permission. The server enforces every rule; the page only explains them.

Seeded accounts: in the sandbox `dana.designer` maps, `paul.publisher` reviews, and a third person with publish permission publishes (the dev realm has none yet, so add a user to `orqix-purchasing-publishers`). The in-browser mock adds `pia.publisher` for this. In `Purchasing`, `dana.designer` cannot publish and `paul.publisher` is the only publisher, so a three-person flow needs another publisher there as well.

## Pega

Select **Pega archive** and upload a ZIP/RAP package. This safely inventories archive files and recognizes selected XML rule identity fields (class, name, ruleset/version, availability and circumstance). Recognized dependency fields and rule-type mapping suggestions appear in each file report. These are descriptive source evidence; they do not resolve effective rules or establish runtime equivalence. Every file starts with a mapping blocker. Download the mapping package template, supply a manually authored canonical definition, source version and a disposition for every file, then upload it as **Pega reviewed mapping**.

The `orqix-pega-mapping-0.1` package must reference the stored archive job in the same workspace and match its checksum. Each file needs either valid target node IDs or an explicit exclusion, with an explanation. Every target node needs source provenance. Unresolved items block conversion; accepted manual mappings require acknowledgement and normal compiler validation. The package can consolidate multiple rules into target nodes, so file-level provenance alone does not prove that every rule inside a file was preserved.

Representative native exports and expected business scenarios are still needed to implement automatic Pega rule conversion. Stages, rule resolution, activities, declarative behavior and SLA calendars have no automatic equivalence claim. Running cases, history, users, attachments and credentials require separate migration work.

## Access and limits

Reading a migration requires workspace visibility and `workflow.read`; uploading/converting requires `workflow.edit`. Foreign-workspace jobs are hidden. Connection administration and publishing retain their own permissions.

Use these seeded local accounts to validate the roles; their workspace bindings determine access:

| Account | Validation |
|---|---|
| `dana.designer` | Analyze sources, resolve mappings, create and edit imported drafts |
| `paul.publisher` | Review the imported draft and separately publish/activate it |
| `vic.viewer` | Read source reports; upload/conversion controls are unavailable |
| `rhea.requester` | Start a published workflow; migration upload/conversion is denied |
| `mani.manager`, `fin.finance` | Validate mapped approval routing and rejection outcomes |
| `fred.facilities`, `otto.outsider` | Purchasing migration jobs are hidden outside their workspace access |

Decoded sources are limited to 1 MiB. Archive inspection is bounded to 200 entries, 1 MiB per expanded entry and 4 MiB total; unsafe/duplicate paths and corrupt archives are rejected without extracting files. XML external entities and excessive parser depth/element counts are rejected. Source integration code is not evaluated.

API endpoints under `/api/v1/workspaces/{workspaceId}/migrations`: `POST` uploads/analyzes; `GET /{migrationId}` retrieves the report and source map; `POST /{migrationId}/convert` validates mappings and creates a draft. Upload `sourceFormat` is `bpmn`, `dmn`, `pegaArchive` (base64 content), or `pegaMapping` (JSON text). Projects are under `/migrations/projects` and the review policy under `/migration-policy` and `/workflows/{workflowId}/migration-review`. See [OpenAPI](../contracts/openapi.yaml) for request schemas.

## Evidence and remaining validation

[Fixtures](../examples/migration/README.md) are hand-authored and explicitly synthetic. Integration tests convert Camunda 7/8 fixtures and execute approval, rejection and the 5,000 threshold against PostgreSQL/Flowable with a stub integration. Pega tests verify checksum linkage, provenance, acknowledgement and compiler validation; they do not demonstrate native Pega conversion. Security tests exercise workspace isolation and source-parser limits. Browser tests cover both import journeys.

Evidence: [backend verification](../evidence/ui-review/migration-backend-final.log), [final migration regression checks](../evidence/ui-review/migration-backend-focused-final.log), [frontend verification](../evidence/ui-review/migration-frontend-final.log), [local image build](../evidence/ui-review/migration-compose-build.log), [migration browser checks](../evidence/ui-review/migration-browser.log). Both migration journeys passed on the final image; 18 other browser checks passed in the preceding full run, with one mock-only check skipped.

Further hardening: [full backend suite](../evidence/ui-review/migration-hardening-backend.log) (204 compiler and 91 application tests), [frontend suite](../evidence/ui-review/migration-hardening-frontend.log) (159 tests), [rebuilt image](../evidence/ui-review/migration-hardening-build.log) and [browser suite](../evidence/ui-review/migration-hardening-browser.log). Added regressions verify rejection without draft creation, collapsed-element provenance and recovery from report-refresh failure.

The final full browser run passed all 20 real-stack checks; one mock-only scenario was skipped. The workflow lookup helper now follows pagination, so repeated imports do not hide seeded fixtures from accessibility and console/CSP checks.

Conversion expansion (review severity, grouped service mappings, comparison operators, DMN, multi-instance): the migration test classes grew from 20 to 150 tests (`ExpressionsTest`, `ConverterExpansionTest`, `DmnImportTest`, `FixtureDispositionTest`, `MigrationApiTest`, `MigrationSafetyTest`, `IdentifiersTest`) and run against PostgreSQL and Flowable for the API cases. The 600 seeded random DMN tables are checked against an independent oracle, never against the importer's own regions. Every fixture is synthetic; none of this shows equivalence with a real Camunda decision or process.

Before a customer cutover, compare target behavior with the actual source system across routing, thresholds, rejection, retries and external effects. The current subset and synthetic fixture evidence do not establish general Camunda or Pega parity.
