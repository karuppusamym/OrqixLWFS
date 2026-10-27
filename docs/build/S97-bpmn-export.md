# S97 - BPMN download with complete diagram interchange (STU04) and standards verification (STD01)

Plan: [docs/25 section 4](../25-studio-and-integration-roadmap.md#4-stu04---export-to-bpmn-and-cmmn). Scope rule as elsewhere: a bounded slice. No migration, no change to the compiler output, the deployed BPMN, hashes or golden files.

## What was added

- **Download BPMN (.bpmn)** in three places: the new **Export** menu in the studio header (which also links to the existing case-model XML download at Cases), the Source tab (BPMN view), and a **BPMN** button on each published version of the workflow overview.
- **Endpoints** (`contracts/openapi.yaml`, `schema.d.ts` regenerated, `api:check` green):
  - `exportDraftBpmn` `GET /workspaces/{ws}/workflows/{id}/draft/bpmn/export?revision=` - `workflow.read`; a draft with blockers answers 422 `WORKFLOW_INVALID`.
  - `exportReleaseBpmn` `GET /workspaces/{ws}/workflows/{id}/releases/{releaseId}/export/bpmn` - `workflow.export`; the stored deployed BPMN plus header and diagram.
  - Both answer `application/xml`, `Content-Disposition: attachment` with a file name cut to letters, digits, `.`, `-`, `_` (`<key>-draft-<rev>.bpmn`, `<key>-r<n>.bpmn`) and `Cache-Control: no-store`. Outsiders get 404 (like every workspace route), a person without the permission 403.
  - Audit `workflow.exported` with ids only: `{format: "bpmn", revision}` for a draft (resource: the workflow) and `{format: "bpmn", releaseNumber}` for a release (resource: the release). No content is audited. The metadata whitelist gained `format` and `revision`.
- **Complete diagram interchange** (`BpmnInterchange` in the compiler module; facade `BpmnSource.downloadFile`). The compiled text is kept byte for byte; its partial diagram (present only when every step has a position) is replaced by one with a BPMNShape and Bounds for every flow node (generated helpers, boundary events, sub-processes and their start and end events included) and a BPMNEdge with waypoints for every sequence flow, plus a header comment.
- **Sizes**: events (boundary events too) 48 x 48, gateways 64 x 64, tasks and call activities 200 x 80; a saved position is the top-left corner. One backend constant, `BpmnShapeSizes`, with a comment pointing at the frontend `studio/model/shapeSizes.ts` (created by the canvas-notation branch; not imported or depended on here; the two must be kept equal when that branch lands). Sections and handlers (sub-processes) are as large as their content plus padding.
- **Header comment**: Orqix compiler version, source (draft id and revision, or release id and version, workspace id), semantic hash, whether positions were saved or computed, and the extensions actually present in the file: the `orqix:*` elements and attributes (namespace `https://orqix.example/bpmn/v0`) and the `flowable:*` attributes. It also says what is not carried (form fields, access rules, triggers, decision tables, connection settings and secrets).

## Layout algorithm (deterministic: same input, same bytes)

1. If every step (flow node whose id has no `__`) has a saved position, those are used exactly. If even one has none, none is used (mixing could put computed shapes on saved ones) and everything is computed.
2. Computed, left to right, per container (the process; each sub-process separately): a depth-first search from the nodes without incoming flow, in document order, finds loop (back) edges, which are ignored for layering; each node goes in column `1 + max(column of predecessors)`. Columns are as wide as their widest node plus its helpers, 80 apart; nodes in a column are stacked in document order 60 apart and centred on the tallest column; margin 40.
3. Helpers (approval outcome gateway, message catch and timer events) sit 40 right of their step, stacked and centred; boundary events are centred on the bottom edge of what they are attached to, evenly spread; a sub-process has its start event inside the left edge and its end events in a column inside the right edge, with 100 side and 50 top and bottom padding.
4. Edges: right edge of the source to left edge of the target, straight or with an elbow halfway; a target behind the source is reached by leaving the bottom, running 40 below both and entering the bottom; vertically stacked shapes are joined bottom to top. Waypoints are absolute.

## What BPMN carries and does not (unchanged from S60 / S77)

Carried in standard BPMN: name, steps, connections, sections and handlers, start/end/gateway/task/boundary structure, positions. Carried only as the documented extensions: the `orqix:` elements and the `flowable:` attributes and `failedJobRetryTimeCycle` written by the generator (approval settings, outcomes, conditions, decisions, notifications, HTTP calls, timeouts, release marker and so on). Not in the file at all: `metadata`, `inputSchema`/`inputUi`, `outputSchema`/`outputMapping`, `triggers`, decision table bodies (DMN files are in the release bundle), `security`, `dependencies`, and `uiLayout.viewport`. Another modeller that does not know the extensions can drop them when it saves; the Orqix BPMN source view only accepts BPMN that regenerates exactly, and foreign BPMN goes through the migration workbench.

## STD01 verification (what is conformant, checked by tests)

| Claim | Evidence | Result |
|---|---|---|
| Export is well-formed BPMN 2.0 and valid against the BPMN 2.0 XSD (model and DI) | Flowable's own converter with schema validation on, every example and the section fixtures (`BpmnExportStandardsTest`, `BpmnExportApiTest`) | conformant for the admitted subset |
| Every flow node has a BPMNShape with Bounds, every sequence flow a BPMNEdge with at least two waypoints, one plane for the process | `BpmnInterchangeTest` (all examples and compiler cases, with and without saved positions), converter checks | conformant |
| Shape sizes are the shared sizes | same tests (events 48, gateways 64, tasks 200 x 80) | conformant |
| Same elements and flows as the deployed BPMN | element id sets compared with the compiled text; the process text outside header and diagram is byte-identical | conformant |
| Survives a standard read, write, read | converter round trip: element ids, flow ids, shapes, edges and bounds equal | conformant |
| Saved positions kept; computed layout never overlaps shapes (outside sections) | `BpmnInterchangeTest` | conformant |
| Re-import through the Camunda importer | header and diagram add no finding; every "extension not imported" finding names a documented Orqix extension (`BpmnExportApiTest`) | partly, see below |
| Executable Orqix semantics in other engines | not claimed | not conformant by design: delegate beans (`flowable:delegateExpression`), route-condition expressions and approval outcomes are Orqix/Flowable execution bindings |
| BPMN notation on the studio canvas | STU01 | not in this slice |
| CMMN | existing exact XML download (S81), now linked from the studio Export menu | unchanged; no CMMN diagram interchange |
| DMN, UML | unchanged | unchanged |

**Camunda importer result, stated honestly.** Re-importing an Orqix export through the Camunda importer does not report only the documented extensions. It reports (a) info "extension not imported" for each documented `orqix:` element and for `flowable:failedJobRetryTimeCycle` (as intended), and (b) findings for the Orqix execution bindings, which the importer reads as Camunda constructs: blockers `JAVA_DELEGATE_NOT_PORTABLE` for service tasks with a delegate expression, `EXPRESSION_NOT_SUPPORTED` for route conditions, and action items `APPROVAL_ROUTE_REQUIRED` for approval tasks. These are identical with and without the new header and diagram (tested), so STU04 adds nothing to them. Making the importer recognise Orqix's own files would be a separate change to the migration module; the exact way back into Orqix is the BPMN source apply (S60). This is a decision for the owner.

## Tests that ran

Compiler: `BpmnInterchangeTest` (6). Application: `BpmnExportApiTest` (4: viewer draft download, header, audit, 404 for outsiders, 422 for a blocked draft; release permissions 403/404, process unchanged, audit; Camunda importer), `BpmnExportStandardsTest` (2: all examples in both layout modes plus a section with time limit and error route through the standard converter). `SecurityMatrixTest` walks every operation of `openapi.yaml`, so both new operations are covered for foreign principals (404, no state change). The API contract test helper now also validates `application/xml` responses. Frontend: `BpmnDownload.test.tsx` (4) and mock handlers for both endpoints. Full-suite numbers are in the commit report.

## Not included

STU01 canvas notation (the frontend sizes constant), CMMN diagram interchange, importing a downloaded file back with its layout through anything but the BPMN source view, BPMNLabel on shapes and edges, pools and lanes (lanes are derived, S53), a Playwright journey.
