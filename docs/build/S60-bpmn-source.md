# S60 - BPMN source view, exact BPMN edit/apply and BPMN round-trip check (UI03 remainder)

S53 delivered the Lanes tab, the YAML source tab and threaded discussion, and left out BPMN source editing and a BPMN round trip. This slice adds them. It does not change the compiler output (no compiler version bump, golden files unchanged), the canonical document schema or the runtime. No migration was needed.

## What exists

**Source tab: YAML | BPMN.** The Source tab now has a format switch. YAML behaves exactly as in S53. BPMN shows the draft compiled by the real compiler: `process.bpmn20.xml` exactly as publishing would deploy it.

**What BPMN carries.** The workflow name, the steps (`nodes`), the connections (`transitions`) and, when every step has a canvas position, the diagram (`uiLayout.positions`). Everything else (`metadata`, `inputSchema`, `inputUi`, `outputSchema`, `outputMapping`, `triggers`, `decisions`, `execution`, `security`, `dependencies`, `stages`, `uiLayout.viewport`) is listed on screen as "kept from the draft" and is never changed by applying BPMN.

**Draft round trip (document -> BPMN -> document).** On every load the server compiles the stored revision, reads the BPMN back with a strict reader (the exact inverse of the generator) and compares the carried sections. The view shows either "converts to BPMN and back without any difference" or every difference as a JSON Pointer with both values. BPMN editing is only offered when the round trip is identical. Values are compared in canonical form (key order ignored, numbers compared as decimals).

Constructs that the compiled BPMN does not carry make the round trip fail, so BPMN editing is unavailable for such drafts (found by running every example; the reasons are reported, not hidden). [S70](S70-bpmn-defaults-and-examples.md) later removed the payload-title and explicit-default limits below (`order-payment-wait` and `start-date-onboarding` are now editable); the list is kept as delivered in S60:

- `startWorkflow` `repeat`, `forEach` and `collectionMapping` (examples `banking-credit-approval`, `medium-bounded-rework`, `banking-card-claim`, `complex-batch-review`);
- approval `separateFrom`, `correctable` and `dispatch`;
- the `title` of message-payload and external-task result fields (`order-payment-wait`);
- explicitly written defaults that the BPMN cannot distinguish from absence: `atTime: "09:00"` (`start-date-onboarding`), `leaseSeconds: 120`, `required: false`, parallel `mode: "all"`, and an empty `payload` written as absent. The reader writes these back the way the studio stores them (omitted), so studio-authored drafts round-trip.
- a draft with blockers has no BPMN at all (the view lists the blockers).

**Checking and applying edited BPMN.** Check BPMN and Apply BPMN to draft send the text to the server. It is accepted only when all of the following hold, otherwise every problem is listed with its kind, the BPMN element id and the specific difference:

1. the current draft itself round-trips exactly (otherwise applying would drop what BPMN cannot carry): `draftRoundTrip`;
2. the text is safe XML: at most 1 MiB, 10,000 elements and 32 levels; any DOCTYPE/ENTITY refused (same protections as the Camunda importer's `SafeXml`): `xml`;
3. nothing unsupported: XML comments and processing instructions, lanes (`laneSet`), pools/collaborations, sub-processes, call activities, script/send/receive/manual/business-rule tasks, throw events, annotations, data objects, a second process or diagram, service tasks or user tasks that are not Orqix steps (classes, scripts, Camunda/Zeebe extensions), free condition expressions, boundary events that are not a generated time limit or failure path, ids with the reserved `__` that are not generated helpers, a split without exactly one matching join, a step without a diagram shape when a diagram is present: `unsupported`;
4. the process id equals the workflow key: `identity`;
5. the resulting draft compiles without blockers in the workspace (groups, connections, child workflows): `blocker`;
6. regenerating BPMN from the resulting draft reproduces every element, attribute and text of the submitted BPMN. Elements under the process are matched by id, diagram shapes/edges by the element they show, `orqix:input`/`orqix:result` by name, everything else by position; namespace prefixes, attribute order and number spelling do not matter. An extra attribute (for example `camunda:assignee`), a missing generated attribute (for example `flowable:async` on a system call) or a different value is `notRepresentable`. Values owned by sections BPMN does not carry (connection version, retry cycle and attempts, decision table version/resource/field) must equal what the document produces: `otherSection`, with the required value in the message;
7. reading the regenerated BPMN gives the same document again: `roundTrip`.

Values that are computed rather than authored are recomputed and listed as notes, never read: the release hash and compiler version, child release pins, the names of generated helper elements (`X__route`, `X__timeout`, ...), terminate markers, and diagram edges, helper shapes and shape sizes. Moving a step's shape changes its canvas position (shape centre minus half the 220 x 72 card).

Accepted edits include renaming steps, connections and the workflow, changing step settings that BPMN carries, adding, removing and rewiring steps and connections (with a diagram shape for a new step when the diagram is present) and moving shapes. The check lists what applying would add, remove or change (at most 30 lines) and saves nothing. Apply runs the same check again on the server and saves the result as the next draft revision; in the studio it is one undoable step. Check workflow and all publish gates still apply.

## Authorization boundaries (same as the YAML source mode)

| Operation | Requires | Notes |
|---|---|---|
| `getDraftBpmn` (`GET .../draft/bpmn?revision=`) | `workflow.read` | read-only for viewers; latest revision when omitted |
| `checkDraftBpmn` (`POST .../draft/bpmn/check`) | `workflow.edit` | saves nothing; no audit (like `validateDraft`) |
| `applyDraftBpmn` (`PUT .../draft/bpmn`) | `workflow.edit` + `If-Match` | 428 without If-Match, 412 on a stale revision, 422 `VALIDATION_FAILED` with one field error per problem (code = problem kind); audit `workflow.draft.saved` with `{revision, source: "bpmn"}` |

Non-members get 404; requesters who may only start get 403/404. Request bodies for these paths may be up to 2 MiB (the XML is JSON-escaped); the XML itself is limited to 1 MiB.

## Implementation

- Compiler (`backend/compiler`): public facade `BpmnSource` (`export`, `check`, `keptFromDraft`); `internal/bpmn/BpmnXml` (hardened StAX parsing; the importer's `SafeXml` lives in the application's migration module, which the compiler and catalog cannot depend on, so its settings are mirrored), `BpmnReader` (compiled dialect to name/nodes/transitions/positions, `joinRef` re-derived structurally), `BpmnCompare` (flattened comparison and computed-value rules). The exporter is the existing `BpmnGenerator`, used unchanged through `WorkflowCompiler.compile`. The Camunda importer (`Analyzer`/`Converter`) is not used for reading: it converts Camunda BPMN with designer mappings and fixed defaults and cannot reproduce an Orqix document exactly.
- Application: `BpmnSourceService` and three endpoints in `CatalogController`; `WorkflowService.saveDraft` gained an overload that records `source` in the audit (whitelisted in `AuditWriter`); `RequestLimitsFilter` allows 2 MiB on the BPMN paths. Contract: `getDraftBpmn`, `checkDraftBpmn`, `applyDraftBpmn`, schemas `DraftBpmn`, `BpmnCheckRequest`, `BpmnApplyRequest`, `BpmnCheck`.
- Frontend: `BpmnSourceView` (Source tab, BPMN format), endpoints, regenerated `schema.d.ts`, mock handlers in `mocks/bpmnSource.ts`. The mock is a much simpler double: it writes a minimal BPMN without extensions or diagram and only applies name edits.

## Not included

- **Stored or editable swimlanes.** Excluded: lanes are derived from each approval's group (S53). Storing lanes would need a new section in the canonical document, schema, compiler and semantic-hash changes, and a definition of what a lane means for assignment; that does not fit cleanly in this slice. A `laneSet` in submitted BPMN is refused with an explanation rather than dropped.
- BPMN editing for drafts that do not round-trip (see the list above) or that have blockers. Making those constructs editable in BPMN would need new `orqix:*` extensions in the compiled output, which changes generated bytes and the compiler version.
- A graphical BPMN modeler, BPMN import of foreign (Camunda) files through this view (the migration workbench remains the way), preserving XML comments or formatting, partial apply, and merging concurrent edits (the draft revision/412 flow applies).
- A Playwright browser journey and a real-stack (Docker compose) run.

## Verification - 17 October 2026

Backend (JDK 21; app tests on real PostgreSQL via Testcontainers, every response validated against `openapi.yaml`):
- `BpmnSourceTest` (compiler, 14): every example and valid compiler fixture that compiles (21 documents) either round-trips exactly - then applying its own BPMN is accepted with no changes, gives the same canonical document and byte-identical BPMN - or reports the exact differing paths and refuses apply; renaming, moving a shape, adding and rewiring a step, other namespace prefixes; refusals for script tasks, lanes, comments, sub-processes, foreign attributes, removed generated attributes, free expressions, foreign service tasks, XXE/DOCTYPE, malformed XML, other-section values, wrong process id, blockers, incomplete or removed diagram, drafts with blockers, unknown helpers; parallel joins found again.
- `BpmnSourceApiTest` (3): viewer read with identical round trip; viewer check/apply 403; check saves nothing; lanes refused with element id; apply 428/422/200/412; `?revision=` returns the earlier BPMN; audit `source: bpmn`; non-round-tripping draft (`separateFrom`) and blocked draft; unknown revision 404; outsiders 404.
- Focused run: `BpmnSourceApiTest`, `SecurityMatrixTest` (extended with sample bodies; foreign principals get 404 on the new operations and nothing changes), `ArchitectureTest`, `CatalogApiTest`, `WorkflowDiscussionApiTest`, `DatabaseTest`: 22 tests, 0 failures (`evidence/s56/app-focused-2.log`). Compiler module `verify`: 475 tests, coverage gate met (`evidence/s56/compiler-verify.log`).
- Full backend `clean verify` of this change: compiler 475 tests (coverage gate met), application 761 tests (757 pass, the same four intentional skips), 0 failures, packaging succeeds (`evidence/s56/backend-verify.log`).

Frontend: `BpmnSourceView.test.tsx` (4: check/apply/reload against the mock, refusals leave the draft untouched, read-only for viewers, blocked draft explained). Full suite: 102 files, 840 tests pass (`evidence/s56/fe-full-2.log`). An earlier full run made while the backend verify was running had 5 tests time out at 15 s (`ai.test.tsx`, `MigrationPage.test.tsx`, `SourceLanesDiscussion.test.tsx`, `StudioPage.test.tsx`); they pass alone (44/44) and in the clean rerun. `tsc -b`, scoped ESLint, `api:check` and `build:mock` pass.

Evidence (untracked): `evidence/s56/*.log`.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=BpmnSourceApiTest,SecurityMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test` and `-pl compiler test -Dtest=BpmnSourceTest` (JDK 21); from `frontend`: `npx vitest run src/features/studio/BpmnSourceView.test.tsx`.
