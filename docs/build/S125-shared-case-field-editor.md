# S125: Shared field editor for case data

30 October 2026. Third slice of the forms, cases and AI review ([docs/26](../26-forms-cases-ai-review.md), finding C2, called "S122 Shared field editor for cases" there). It removes the separate editor and type system of case data fields: **a case data field is now defined, rendered and checked like a request question.**

## 1. What changed

| Before | After |
|---|---|
| Case data fields had their own editor (`CaseDataFieldsEditor`): a grid of key, label, type, required and choices for every field at once. | The case definition form uses the **shared field editor** extracted from the Studio's question editor: compact rows ("Field 1: Claim amount", type and required in one line), one click opens the details, moving and deleting from the row. A new field opens with its label focused. |
| Six types (text, number, whole number, yes or no, date, choice), with only a text limit and numeric bounds. | Eight of the request form's single-value types: Text, Long text, Number, Money (USD), Whole number, Date, Yes/No and Choice. Each has the request form's checks: help text, shortest and longest length, an **answer pattern** with its message (the same safe engine, S90), earliest and latest date, minimum and maximum, cents for money. |
| Case data was rendered by its own component: plain inputs, yes or no as a select. | Case data is rendered by the **request form renderer** (`FormRenderer`) when a case starts, when it is edited and when it is shown read-only. A pinned case view still supplies labels, choice labels and sections (as groups). Yes/No is a checkbox, as on the request form. |
| Values were checked only by the server. | Values are checked before sending with the request form's validator and messages ("Enter a reference like CASE-1234."); the case data panel shows the message on the field. The server checks the same keywords. |
| Keys followed `[A-Za-z][A-Za-z0-9_]{0,63}`; request questions follow `^[a-z][A-Za-z0-9]{0,63}$`. | New and renamed keys follow the request-question pattern (the key is under Advanced, as in the Studio). Existing keys in the older pattern keep working everywhere; the editor and the API report them as a **warning**, with a suggested key. |

The Studio is unchanged: `QuestionList` is now a thin wrapper that binds the shared editor to the Studio store and adds what only the request form has (rules, the incident-correction setting, collections, files and the "used by" check before a delete). Its element ids, test ids and wording are the same.

## 2. Contract

Additive only; every existing definition reads and validates as before.

- **OpenAPI `CaseDataField`**, new optional properties, named as in the workflow schema's `formField`:
  - `description` (help text, at most 500);
  - `minLength` (text only, at most `maxLength`, default 2000);
  - `pattern` and `patternMessage` (text only; the S90 safe pattern subset, at most 200 characters);
  - `formatMinimum` and `formatMaximum` (date only, `YYYY-MM-DD`, inclusive);
  - `multipleOf` (`0.01` or `1`, numeric only);
  - `widget` (`textarea` on text, `money` on number; the `inputUi.widgets` hint of a request question).
- **`CaseDataField.key`** keeps its pattern. Its description says that new keys follow `^[a-z][A-Za-z0-9]{0,63}$`.
- **New `CaseDefinitionWarning`** `{ code: CASE_DATA_KEY_LEGACY, field, message }`. `CaseDefinition.warnings` and `CaseDefinitionDetail.warnings` list the keys outside the request-question pattern, and are left out when empty. Create, list and read return them.
- No migration: the new properties live in the existing `case_definition.data_fields` JSON column. Older rows simply lack them.

## 3. Rules

**Server (`CaseDataModel`)**
- Definition checks (422 `VALIDATION_FAILED`): each new property only on the types listed above; the pattern compiles with the compiler's `FormPattern` (the same check as `WF_FORM_PATTERN_INVALID`); a pattern message needs a pattern; dates are real calendar dates and the earliest is not after the latest; money only on number fields, long text only on text fields.
- Value checks on start, data edit, workflow results and child cases use the same `FormValidator` keywords as a request: `minLength`, `pattern` (with the author's message), `formatMinimum`, `formatMaximum` and `multipleOf` are added to the schema built from the pinned fields.
- `warnings(fields)` reports `CASE_DATA_KEY_LEGACY` for a key outside the request-question pattern, with a suggestion (`Customer_Name` → `customerName`). It never blocks.
- New properties are written to the stored fields only when set, so older definitions keep the same stored and returned shape.

**Editor and renderer**
- The case side offers single values only: no collections, rows or files, no request-form rules and no incident setting.
- A key is renamed only to a key in the request-question pattern that is not taken: "Start with a lowercase letter; use letters and digits only.", the same message as the Studio.
- A required Yes/No field is a checkbox, so an untouched one is sent as No. An optional untouched one is left out, as before.

## 4. Migration note for existing keys

Nothing has to be migrated. A definition whose keys use capitals first or underscores (for example `Customer_Name`, or keys imported from Pega or CMMN case file items) keeps starting, editing and mapping case data exactly as before.

To move a key to the request-question pattern, publish a **new definition version** with the renamed key (the warning suggests one), and update in the same version whatever names it: the pinned case view's `case.<key>` binding, workflow and decision task mappings, child case mappings and CMMN if-parts (`cd_<key>`) or case file items. Running cases stay on their pinned version and keep the old key. Mapping rewrites are not automatic in this slice.

## 5. Mock

`mocks/cases.ts` runs the same checks through `features/cases/caseFields.ts`:
- create refuses the same field shapes and returns `warnings`;
- start and data edits check values with the case form schema and keep the values;
- `GET .../data` returns the definition's fields and the case's values;
- `POST .../data` saves a reasoned edit (version check, replay, history), instead of answering 503;
- the definition list no longer includes the source XML.

## 6. Files

- **Contracts:** `contracts/openapi.yaml`, regenerated `frontend/src/api/schema.d.ts`.
- **Application:** `cases/CaseDataModel.java` (new properties, checks, `Warning`, `warnings`), `cases/CaseService.java` (`warnings` on `Definition` and `DefinitionDetail`).
- **Frontend:**
  - new: `features/studio/inspector/FieldListEditor.tsx` (the shared editor), `features/cases/caseFields.ts` (conversions, form schema, key rules, mock checks);
  - changed: `features/studio/inspector/QuestionEditor.tsx` (Studio wrapper), `features/cases/CaseDataFields.tsx` (renderer and editor), `features/cases/CaseDataPanel.tsx` (field messages), `components/form/FormRenderer.tsx` (`readOnly`), `lib/form/values.ts` (`messageFor` exported), `mocks/cases.ts`.
  - `features/cases/CasesPage.tsx` is not changed: it keeps using `CaseDataFieldsEditor` and `CaseDataFields`.
- **Tests:**
  - `CaseDataModelTest` (application unit, 4);
  - `CaseDataApiTest.sharedFieldChecksAreEnforcedAndLegacyKeysOnlyWarn` (application, Testcontainers, responses checked against the OpenAPI contract);
  - `CaseDataFieldsEditor.test.tsx` (frontend, 6: conversions, editing with the shared editor, key rule and legacy warning, rendering with the form renderer, value messages, mock backend);
  - updated `CasesPage.test.tsx` and `CaseViews.test.tsx` (checkbox, group, labels);
  - Playwright `t-case-views` and `t-engineering-closeout` updated for the shared editor with a new helper `addCaseField` in `tests/e2e/support/app.ts`.

## 7. Verification (30 October 2026, local)

| Run | Result |
|---|---|
| `CaseDataModelTest` | 4 of 4 pass |
| All `com.orqix.cases` tests, plus `ExtensionBundlesAndOlderPinsApiTest`, `PageCompletionApiTest`, `PinMigrationsApiTest`, the four Pega native tests and `SecurityMatrixTest` (Testcontainers, responses checked against the OpenAPI contract) | 92 of 92 pass, `CaseDataApiTest` 6 of 6 with the new case |
| Full frontend `vitest run` | 180 files, 1,290 tests pass, with the 6 new ones |
| `tsc -b` (frontend) | Clean in every file this slice touches. Two errors remain in `features/ai/` (`AuthoringPanel.tsx`, `draftEdits.ts`, the S124 `noQuestions` field): they come from S124's contract change and are present at this slice's starting commit. |
| `eslint .` (frontend) | Clean in every file this slice touches. Three errors remain in S123 files (`FormView.tsx`, `StepFormsEditor.tsx`, `TaskPanel.tsx`), present at the starting commit. |
| `tsc --noEmit` (Playwright project) | Clean. The two updated specs were **not run**: they need the real stack. |

Not done: a real-browser pass of the case definition form and the start dialog.

## 8. Limits

- Case fields have no conditions (`visibleWhen`, `requiredWhen`) and no form groups yet: the server would have to evaluate them on every data edit. Sections come from a pinned case view.
- Renaming a key does not rewrite case views, task mappings or CMMN if-parts; the author updates them in the same version (section 4).
- The server only warns about older keys. Making them an error for new definitions needs a decision on Pega and CMMN imports, whose names often use underscores.
- The Pega native converter still refuses case fields with a pattern or a minimum length (`PEGA_NATIVE_CASE_FIELD_CHECK_UNSUPPORTED`), although case data now supports both. It can be relaxed in a later slice.
- The case definition page (`CasesPage`) does not show the API's `warnings` after creating a definition; the editor shows the same warning while the field is edited. The case designer page (S126) can show them.
- The data change history still lists raw values, not formatted ones.
