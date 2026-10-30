# S123: Step forms on human steps

30 October 2026. Second slice of the forms, cases and AI review ([docs/26](../26-forms-cases-ai-review.md), finding F7, called "S121 Step forms" there; S121 and S122 were already used by DMN parity and generated test cases). It closes the functional gap the review found: **a human step could not collect new data.**

## 1. What changed

| Before | After |
|---|---|
| An approval could approve or reject, give a reason and correct listed request fields (`correctable`, S42). | Each approval has a **step form**: a field-access setting per request field, and fields of its own that the approver fills in when approving. |
| The approver saw every request field. | `fieldAccess` sets each field to `hidden`, `readOnly` (the default), `editable` or `required`. A hidden field is left out of the task's `request.input`. |
| A later step could use only request fields and step results. | `capture` is a form schema (single values only: text, numbers, yes or no, dates). Values are step outputs at `/steps/<id>/<key>`, so a later route, mapping or decision can use them. |
| `correctable` was the only way to let an approver change a field. | `editable` and `required` go through the same S42 checks (single value, not used by an earlier step, not inside an open parallel section). `correctable` keeps working and reads as `editable`. |

## 2. Contract

- **Workflow schema**, `approvalNode.config`: both settings are additive and optional.
  - `fieldAccess`: an object of field key to `hidden | readOnly | editable | required`, at most 30 entries.
  - `capture`: a `formSchema`, at most 10 fields.
- **OpenAPI:**
  - `DecisionRequest.fields`: values for the captured fields. Approve only; a rejection with fields gets 422.
  - `TaskDetail.form.fieldAccess` and `TaskDetail.form.capture` are included only when the step has them.

## 3. Rules

**Compiler**
- New `StepForms` checks report `WF_STEP_FORM_INVALID` when:
  - an access key is not a request question;
  - a required captured key is not one of the captured fields;
  - a captured field reuses a name the step already records (`decision`, `reason`, `comment`, `decidedBy`, `decidedAt`, `approvedCount`, `itemDecisions`);
  - a captured field is a list, rows or a file.
- **Data flow:** a captured field resolves as an output of its approval. A required one exists only after the approved route. `Conditions.availability` reports `WF_FIELDREF_NOT_GUARANTEED` when the place that uses it can be reached without taking that route, for example after a rejection or a time-out. An optional captured field may be missing.
- **Combinations:** several approvers (`multiInstance`) and one task per list item (`perItem`) cannot have editable fields or captured fields. Tier-1 workflows cannot use either setting.
- **Test runs:** the simulator fills in captured fields on approval, from `approvals.<id>.fields` in the scenario or with a sample value per required field.

**Runtime (`TaskService.decide`, new `StepForm`)**
- **Approving** needs a value for every request field with `required` access, and the captured values must match the step's capture schema. Unknown fields are refused, and so are fields sent to a step that has no capture schema.
- **Values:** stored in the step output.
- **Records:** `TASK_DECIDED` records `fieldsCaptured` as a count. Audit never stores the values.
- **Corrections:** the correction and send-back paths use the step's editable fields (`correctable` plus `editable` and `required` access).

## 4. Studio, tasks and mock

- **Form tab:**
  - a **Step forms** section with a grid of questions × approval steps (a select in each cell; lists, rows and files offer only Read only and Hidden);
  - per step, a "Fields <step> fills in" editor: name, type, required to approve, remove, at most 10 fields;
  - a **Show as** switch in the live preview: the answers as that step shows them, with access badges and the hidden count, and the step's own fields to try.
- **Condition and mapping pickers:** list captured fields as outputs of their step, only where they are available (the same approved-route rule, `availableFields`).
- **Task panel:**
  - hidden fields are left out;
  - a required field without a value says "Fill this in before approving", and approving explains which field to add through "Correct the request";
  - a **Fill in to approve** section renders the captured fields, checks them before sending, and sends them as `fields`.
- **Mock backend and mock validator:** follow the same rules.

## 5. Files

- **Contracts:** `contracts/workflow-v0.schema.json`, `contracts/openapi.yaml`, regenerated `frontend/src/api/schema.d.ts`.
- **Compiler:**
  - new: `rules/StepForms.java`;
  - changed: `model/Node.java` (`fieldAccess`, `capture`, `editableFields()`), `model/ModelReader.java`, `rules/FieldTypes.java`, `rules/Conditions.java`, `rules/BindingRules.java`, `rules/MultiInstanceRules.java`, `rules/PerItemRules.java`, `rules/Tier1Rules.java`, `sim/SimulationRun.java`.
- **Application:**
  - new: `runtime/internal/StepForm.java`;
  - changed: `runtime/internal/TaskService.java`, `runtime/api/RuntimeDtos.java`.
- **Frontend:**
  - new: `features/studio/model/stepForms.ts`, `features/studio/inspector/StepFormsEditor.tsx`;
  - changed: `features/studio/FormView.tsx`, `features/studio/model/fieldRefs.ts`, `features/tasks/TaskPanel.tsx`, `mocks/engine.ts`, `mocks/handlers.ts`, `mocks/validate.ts`.
- **Tests:**
  - `StepFormsTest` (compiler, 9);
  - `StepFormRuntimeTest` (application, 3, Testcontainers, responses checked against the OpenAPI contract);
  - `StepFormsEditor.test.tsx` (frontend, 6: model, mock validator, grid, captured-field editor, Show as preview).

## 6. Verification (30 October 2026, local)

| Run | Result |
|---|---|
| `StepFormsTest` | 9 of 9 pass |
| `StepFormRuntimeTest`, `ApproverCorrectionTest`, `SendBackAndCompensationApiTest` | 9 of 9 pass |
| `tsc -b`, frontend `vitest run` for studio, tasks and mocks | Clean; 64 files and 423 tests pass, plus the 6 new ones |

Full suites are recorded with the later slices of this round.

## 7. Limits

- Hidden is a presentation setting for the task. People who can read the request's data (`run.read-data`) still see every field on the request page.
- Captured fields are single values. Lists, rows and files at a step are not supported.
- The approval's reusable view (`viewBinding`) does not yet show the captured fields; the task panel shows them below it.
- The AI does not set step forms yet. That comes with the AI conversation slice (S124).
