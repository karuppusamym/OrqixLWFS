# Migration workbench

Implemented 9 October 2026. Open **Workflows → Import workflow** at http://localhost:8080. Imports create drafts; publishing and activating a release remain separate actions.

## Camunda 7 and 8

Upload BPMN XML, inspect the compatibility report, map source assignments to workspace groups, and map service tasks to registered connection operations. The operation helper pins the selected contract version and proposes request/result mappings for review. Review inferred input fields, approval/rejection routes and target policies before acknowledging assumptions and converting.

The supported subset includes none start/end events, sequence flows, group approval tasks, recognized approval outcomes, typed exclusive conditions explicitly mapped HTTP service tasks, unambiguous parallel split/join regions, constant duration timer catches and explicitly mapped child-process calls. Source delegates and workers are never executed. Unsupported constructs, expressions and unresolved mappings block conversion. Self-approval is denied and rejection requires a reason in the resulting draft; these assumptions are displayed for review.

Conversion validates the canonical definition with the normal compiler before creating a workflow. Invalid mappings create no draft. Repeating the same successful conversion returns the original workflow; changing that conversion request is rejected. The studio links back to the source report. Original uploaded bytes, SHA-256, adapter version, approved mappings and source-to-target IDs persist in the workspace.

Conversion rejects unknown request properties, incorrect field types, fractional or string contract versions/timeouts, unknown source mapping IDs and approval routes that contradict a recognized source gateway. An explicit request schema is checked by the compiler instead of being constrained by inference. Source maps include gateways and flows absorbed into target approvals or merge destinations; these may map to a target node rather than a distinct transition.

After conversion commits, the created draft opens independently of report refresh. A failed report fetch shows a retry action and keeps the report link available.

### Parallel work, waits and child processes

The `camunda-bpmn-0.2` adapter maps separate diverging/converging parallel gateways only when a unique matching join is identifiable. The normal compiler verifies branch boundaries; inclusive/event gateways, mixed split/join gateways, ambiguous pairs and unsupported scopes remain blockers.

Intermediate timer catches support fixed positive day/hour/minute durations, bounded from one minute to 90 days. Date, recurring, expression-based and boundary timers remain blocked. Each wait survives runtime restarts.

Call activities require `childWorkflowMappings` keyed by source call ID. A value contains `workflowRef`, `inputMapping` and `resultMapping` using canonical field references. Import and publish the child separately first; publishing the parent pins the child release. Source binding/version settings and input/output extensions require reviewed interpretation; execution of source code is never implied.

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

API endpoints under `/api/v1/workspaces/{workspaceId}/migrations`: `POST` uploads/analyzes; `GET /{migrationId}` retrieves the report and source map; `POST /{migrationId}/convert` validates mappings and creates a draft. Upload `sourceFormat` is `bpmn`, `pegaArchive` (base64 content), or `pegaMapping` (JSON text). See [OpenAPI](../contracts/openapi.yaml) for request schemas.

## Evidence and remaining validation

[Fixtures](../examples/migration/README.md) are hand-authored and explicitly synthetic. Integration tests convert Camunda 7/8 fixtures and execute approval, rejection and the 5,000 threshold against PostgreSQL/Flowable with a stub integration. Pega tests verify checksum linkage, provenance, acknowledgement and compiler validation; they do not demonstrate native Pega conversion. Security tests exercise workspace isolation and source-parser limits. Browser tests cover both import journeys.

Evidence: [backend verification](../evidence/ui-review/migration-backend-final.log), [final migration regression checks](../evidence/ui-review/migration-backend-focused-final.log), [frontend verification](../evidence/ui-review/migration-frontend-final.log), [local image build](../evidence/ui-review/migration-compose-build.log), [migration browser checks](../evidence/ui-review/migration-browser.log). Both migration journeys passed on the final image; 18 other browser checks passed in the preceding full run, with one mock-only check skipped.

Further hardening: [full backend suite](../evidence/ui-review/migration-hardening-backend.log) (204 compiler and 91 application tests), [frontend suite](../evidence/ui-review/migration-hardening-frontend.log) (159 tests), [rebuilt image](../evidence/ui-review/migration-hardening-build.log) and [browser suite](../evidence/ui-review/migration-hardening-browser.log). Added regressions verify rejection without draft creation, collapsed-element provenance and recovery from report-refresh failure.

The final full browser run passed all 20 real-stack checks; one mock-only scenario was skipped. The workflow lookup helper now follows pagination, so repeated imports do not hide seeded fixtures from accessibility and console/CSP checks.

Before a customer cutover, compare target behavior with the actual source system across routing, thresholds, rejection, retries and external effects. The current subset and synthetic fixture evidence do not establish general Camunda or Pega parity.
