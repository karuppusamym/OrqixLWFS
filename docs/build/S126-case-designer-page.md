# S126: Case designer page

30 October 2026. Fourth slice of the forms, cases and AI review ([docs/26](../26-forms-cases-ai-review.md), findings C3 and C1, called "S124 Case designer page" there). It separates **authoring case types** from **working cases**, and gives a case type the same draft → check → publish flow a workflow has. Migration **V206**. Case definitions as such are described in [S110](S110-cmmn-canvas-planning-workflow-tasks.md), [S113](S113-cmmn-nested-cases-timers-conditions.md) and [S118](S118-cmmn-decisions-case-files-dates-any-of.md).

## 1. What changed

| Before | After |
|---|---|
| The Cases page opened with a "Create case definition" form (key, name, CMMN upload or the case model designer, data fields, case view) above the list of cases. | The Cases page lists the published **case types** to start and the recent cases. People who may author see a **Design case types** link. The form is gone. |
| A case definition was deployed in one step (`createCaseDefinition`). Unfinished work could not be kept, and the first problem the server found was the only one shown. | A case type has a **draft** that is saved in any state, a **Check** that runs every admission rule and lists every problem, and **Publish version N**, which checks again and deploys the next immutable version. |
| Editing a published case type meant starting over with a file. | **Open designer** on a published case type starts a draft from its source, data fields, case view and pinned mappings. The draft stays after publishing as the working copy of the next version. |
| Export CMMN sat on the Cases page. | Export CMMN is on the case types list of the designer. |
| The UI used "case" for two ideas (C1). | "Case types" names the CMMN module everywhere it is authored or started. The workflow screens say "request": *Request controls and evidence* on a run, *Request terminal email* in the stage email settings; the Start inspector already said *progress stages*. The Studio's export menu links to *Case types: design and download CMMN*. |

## 2. Contract

All additions; every existing operation and document stays valid. All draft operations need `workflow.edit`, like `createCaseDefinition` and `getCaseDefinition`.

| Operation | Path | Audit |
|---|---|---|
| `listCaseDefinitionDrafts` | `GET /workspaces/{ws}/cases/drafts` | none (read) |
| `createCaseDefinitionDraft` | `POST /workspaces/{ws}/cases/drafts` | `case.draft.saved` |
| `getCaseDefinitionDraft` | `GET /workspaces/{ws}/cases/drafts/{draftId}` | none (read) |
| `saveCaseDefinitionDraft` | `PUT /workspaces/{ws}/cases/drafts/{draftId}` | `case.draft.saved` |
| `deleteCaseDefinitionDraft` | `DELETE /workspaces/{ws}/cases/drafts/{draftId}` | `case.draft.deleted` |
| `checkCaseDefinitionDraft` | `POST /workspaces/{ws}/cases/drafts/{draftId}/check` | none: writes nothing, like `validateDraft` |
| `publishCaseDefinitionDraft` | `POST /workspaces/{ws}/cases/drafts/{draftId}/publish` | `case.definition.created` (written by the existing create path) |

- **Schemas:** `CreateCaseDefinitionDraft`, `SaveCaseDefinitionDraft` (`expectedVersion`, whole content), `CaseDefinitionDraft`, `CaseDefinitionDraftSummary`, `CaseDefinitionCheck` (`draftVersion`, `valid`, `issues[]` of `code` and `message`), `PublishCaseDefinitionDraft` (`expectedVersion`), `CaseDraftDataField` (a data field as it is being edited).
- **Draft content:** what `CreateCaseDefinition` takes (source, data fields, case view, workflow, case and decision task mappings), plus `designerModel`, the designer's own state. The server stores `designerModel` and never reads it; the CMMN source is what is checked and published.
- **Audit metadata:** `case.draft.saved` records the key and draft version, `case.draft.deleted` the key. Never the model, the fields or the source.
- **Problems:** a second draft for the same key is `409 DUPLICATE_KEY`; a save or publish from an older draft version is `409 WORKFLOW_VERSION_CONFLICT`; publishing while the check reports problems is `422 WORKFLOW_INVALID`, with the count and the first problem.

## 3. Rules

**Draft (`CaseDraftService`, V206 `case_definition_draft`)**
- One draft per case key and workspace. The key never changes; the name and content are replaced on every save, which adds one to the draft version.
- Saving checks the shape only: name 1 to 200 characters, source at most 64 KiB, at most 30 data fields (objects) and 30 mappings of each kind, content at most 256 KiB. A draft may be incomplete.
- The draft records the draft version that was last published; the list and the page compare it with the current version ("No changes since version 2").

**Check (`CaseService.checkDefinition`)**
- Runs the admission rules of `createCaseDefinition`, which now share their code with it: key and name, data fields, the case view binding, the CMMN subset validator, candidate groups, workflow, child case and decision task pins, and data fields used by case file items and if-parts.
- Rules that do not depend on each other all run, so every problem is listed at once. A rule whose input failed is skipped: without a valid source, nothing that reads the source runs; with unusable data fields, nothing that needs them runs.
- It writes nothing and does not try an engine deployment. A model the engine still rejects is reported only when publishing.
- It is deliberately not transactional: a refused rule inside a read-only query helper would otherwise mark a shared transaction rollback-only while the check carries on.

**Publish**
- Locks the draft, requires `expectedVersion` to be the current draft version, runs the check again and refuses on any problem. Then it runs `createCaseDefinition` unchanged, so pins, audit and versioning are exactly those of a direct create.

## 4. Pages, navigation and mock

- **`/w/:ws/case-designs` (Case types):** a "New case type" form (key, name, **Create draft**) and the case types of the workspace, merged by key: published version, draft state, **Open designer** and **Export CMMN**. Opening a published type without a draft creates one from that version.
- **`/w/:ws/case-designs/:draftId` (the designer):**
  - name, read-only key, and the status line ("draft version 3 · Version 1 is published; this draft has changes");
  - the existing case model designer (canvas and form view), which now takes an optional initial model and reports every change (`initialModel`, `onModelChange`); nothing else in it changed;
  - **CMMN file (maximum 64 KiB):** use a file as it is. The page then shows the uploaded file, with what the designer could not show, and **Open in the designer**;
  - the existing case data field editor and case view picker, unchanged;
  - **Save draft**, **Check** (saves first when there are changes), **Publish version N** (enabled only after a clean check of the current saved version), **Discard draft** (with a confirmation).
  - **Check results** list the designer's own problem (for example, a human task without an eligible group, which stops the CMMN from being generated) together with the server's.
- **Reopening a draft:** a saved designer model opens as it was. A draft with only a source (one started from a published version) is imported into the designer, with the pinned mappings put back on their tasks. If the import would leave anything out, the source stays an uploaded file.
- **Navigation:** *Case types* sits under *Workflows* in the main menu, for people with `workflow.edit`, the permission the old create form needed.
- **Mock backend (`mocks/cases.ts`):** drafts, check and publish follow the same rules with the mock's structural check: name and key, data fields, CMMN namespace and case ID, candidate groups that exist, workflow task mappings. Drafts are part of the saved mock state.

## 5. Files

- **Contracts:** `contracts/openapi.yaml`, regenerated `frontend/src/api/schema.d.ts`.
- **Backend:**
  - new: `cases/CaseDraftService.java`, `cases/api/CaseDraftController.java`, `db/migration/V206__case_definition_draft.sql`;
  - changed: `cases/CaseService.java` (`checkDefinition`; the admission rules shared with `createDefinition`), `audit/AuditWriter.java` (two audit actions).
- **Frontend:**
  - new: `features/cases/CaseDesignsPage.tsx`, `features/cases/CaseDesignerPage.tsx`, `features/cases/caseDraft.ts`;
  - changed: `features/cases/CasesPage.tsx` (create form and export removed, "Case types", link to the designer), `features/cases/CaseModelDesigner.tsx` (optional initial model and change callback), `features/cases/api.ts`, `app/routes.tsx`, `app/Shell.tsx`, `features/studio/StudioHeader.tsx`, `features/runs/CaseControlsPanel.tsx`, `features/studio/inspector/StageEmailFields.tsx`, `mocks/cases.ts`.
- **Tests:**
  - backend: new `CaseDraftApiTest` (2, Testcontainers, every response checked against the OpenAPI contract); `SecurityMatrixTest` covers the seven new operations;
  - frontend: new `CaseDesignerPage.test.tsx` (4: create, check with two problems, fix, publish; upload a file as it is; open a published type; permission) and `caseDraft.test.ts` (4); `CasesPage.test.tsx` (the page has no create form, authors get the link, workers do not), `CaseViews.test.tsx` (the case view is chosen on the designer page) and `BpmnDownload.test.tsx` updated;
  - Playwright: `t-case-events`, `t-case-model-designer`, `t-case-views`, `t-engineering-closeout` and `t-wm04-cases-advanced` author through the designer page, with the new `support/caseDesigner.ts` (`newCaseType`, `useCmmnFile`, `checkAndPublish`).

## 6. Verification (30 October 2026, local)

| Run | Result |
|---|---|
| `CaseDraftApiTest`, `SecurityMatrixTest` | 10 of 10 pass |
| All case tests (`com.orqix.cases`, 15 classes), `PegaNative*` (4 classes, they create case definitions), `ArchitectureTest`, `DatabaseTest`, `SecurityMatrixTest`; real PostgreSQL via Testcontainers, contract checked | 89 of 89 pass |
| Frontend `vitest run` (full suite) | 181 files and 1293 tests pass |
| `eslint` on changed frontend files, `npm run api:check` | Clean |
| `tsc -b` | Clean for this slice. The only errors are in `features/ai/` (`AuthoringPanel.tsx`, `draftEdits.ts`), from the S124 contract change already on the branch, and are outside this slice. |
| Playwright specs | Type-check clean (`tsc --noEmit` in `tests/e2e`); not run here (they need the real stack). |

## 7. Limits

- The check does not try an engine deployment. A model that passes every Orqix rule but that the pinned Flowable engine still rejects is reported only at publish time (`422`, "The CMMN engine rejected this model.").
- The draft is a single working copy per case type with optimistic versions. There is no draft history, no comparison with the published version and no review step before publishing; the person who may edit may publish, as with `createCaseDefinition` before.
- `designerModel` is the designer's own state and is not validated by the server. A draft saved from an uploaded file has none and is shown as a file until someone opens it in the designer.
- Starting a draft from a published version copies the version's pinned mappings, not the releases: publishing pins the releases that are current then.
- The page does not warn about unsaved changes when you leave it; **Check** saves first, **Save draft** saves explicitly.
- Discarding a draft does not change published versions; there is no way to retire a published case type yet.
- The Pega conversion (S117) still creates case definitions directly, without a draft.
