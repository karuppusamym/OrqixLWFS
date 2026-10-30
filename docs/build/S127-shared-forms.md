# S127: Shared forms

30 October 2026. Follow-up to the forms, cases and AI review ([docs/26](../26-forms-cases-ai-review.md)) and the 30 October regression review: one workflow can hold several forms (the request form and per-step forms, [S120](S120-studio-form-tab.md), [S123](S123-step-forms.md)), but a form could only be reused in another workflow through a pinned request-view layout ([S79](S79-pinned-approval-views.md)), which works only when the target already has identical questions. This slice makes a **form a fourth kind of shared asset** next to decision tables, document templates and connections ([S61](S61-shared-assets.md)), reusing the S61 index, owner, archive, where-used and reference check instead of building a new system. Migration **V207**.

## 1. What a shared form is

A set of request questions with everything that makes them work together:

| Part | Shape | In the shared form | Stays with the workflow |
|---|---|---|---|
| Questions | workflow `inputSchema` (`formSchema`) | types, labels, help, limits, choices, patterns, required | |
| Presentation | subset of `inputUi` | `order`, `widgets` | `submitLabel`, `currency`, `layout.helpText`, `viewRef`/`viewDefinition` |
| Rules | `inputUi.visibleWhen`, `inputUi.requiredWhen` | conditions on the form's own questions (`/input/<key>`) | |
| Form groups | `inputUi.layout.sections` | titled groups, columns, group rules | |

Like the other kinds, a shared form is identified by kind and key in one workspace, has numbered **unchangeable versions**, an **owner** (a label that grants nothing) and an **archived** flag. At most 200 per workspace, 100 versions each, 1 to 30 questions, 64 KiB.

## 2. Decisions taken

1. **Copy on insert, pinned version, never live** (S61 decision 1). Inserting copies the questions into the request form and records `dependencies.assets: [{kind: "form", key, version, target: "request", fields}]`. `fields` maps each copied question from its shared key to its key in this workflow. The compiler and runtime work on the copy; a later library version never changes a draft, a release or a running request.
2. **Clashing keys are resolved before insert, never overwritten.** When this request form already has a question with a shared key, the designer renames the shared question (a free key is suggested; invalid, taken or duplicate keys are refused) or skips it and keeps this form's own question. Insert stays disabled until every clash is resolved. Rules copied with a renamed question follow it; a rule that named a skipped question keeps naming this form's question with that key.
3. **Publishing checks run on the copy.** The copied questions are ordinary request questions: the compiler (`FormGuards`, type and condition rules) checks them on every validate and publish, so reuse cannot bypass validation. For example, skipping a question that a copied rule depends on, in a form that has no such question, is a compiler blocker (`SharedFormsApiTest`).
4. **The content of a version is checked with the publishing compiler when it is saved.** The server wraps the form in the smallest publishable workflow (start, end, one route) and refuses the version (422, field errors under `/content`) when the compiler reports a blocker under `/inputSchema` or `/inputUi`. Workflow-level settings (`submitLabel`, `currency`, `helpText`, pinned views) are refused in the content.
5. **Reference check** (`AssetReferenceCheck`, same codes as S61): `WF_ASSET_MISSING` (key or version not in this workspace), `WF_ASSET_ARCHIVED`, `WF_ASSET_DIVERGED` (the copy no longer equals the pinned version), `WF_ASSET_STALE` (warning: a newer version exists; publish keeps the pinned one), and `WF_ASSET_REFERENCE_INVALID` (target is not `request`, no `fields`, a shared key not in the version, the same form linked twice, or one request question claimed twice).
6. **What "the copy equals the version" means.** Both sides are reduced to the copied questions in the shared keys: question definition, required flag, widget, `visibleWhen` and `requiredWhen` (condition references renamed back to shared keys; a reference to a question outside the copy is marked, so pointing a copied rule at another question counts as a change). Question **order and groups are not compared**: they are presentation, and a designer may rearrange the form without unlinking it. The Studio computes the same projection (`model/sharedForms.ts`) to show "Edited here" before validation.
7. **Update is explicit.** A linked form shows *Version N available*; **Update to vN** shows the added, removed and changed questions (by question) and asks for a rename-or-skip choice for every added question whose key is taken. Applying it deletes removed questions, replaces changed ones in place (keeping their key here), copies added ones and pins version N. Questions skipped earlier stay skipped.
8. **Permissions** are the decision permissions (S61 decision 4): read `workflow.read`, create, version, archive and owner change `workflow.edit`; where-used also needs `workflow.read`. Non-members 404, requesters and viewers 403 on writes.
9. **Groups** keep their title and columns; their ids are prefixed with the form key (`<form key>-<group id>`) so they cannot clash with this form's groups, and a group keeps only the questions copied here.
10. **Save as shared form** saves the whole request form in the shape the question editor writes (so the copy and version 1 compare equal) and links the questions it saved; questions already linked to another shared form stay linked to that one.

## 3. Contract

- `contracts/workflow-v0.schema.json`: `dependencies.assets[].kind` gains `form`; new optional `fields` (1 to 30 entries, field key to field key). Additive: every earlier definition stays valid.
- `contracts/openapi.yaml` (tag Workflows): `SharedAssetKind` gains `form`; new `createFormAsset` (`POST /workspaces/{ws}/shared-assets/form`, audit `shared.form.created`) and `addFormAssetVersion` (`POST .../shared-assets/form/{assetKey}/versions`, `expectedVersion`, 409 when stale, 422 when identical, archived or refused by the compiler; audit `shared.form.versioned`); schemas `FormAssetContent`, `CreateFormAsset`, `AddFormAssetVersion`; `SharedAssetVersion.content` is a decision table or a form. Search, detail, where-used, owner and archive operations take `form` unchanged. Audit metadata: kind, version, question count; never content.

## 4. Implementation

| Area | Files |
|---|---|
| Migration | `V207__form_asset.sql`: `form_asset`, `form_asset_version` (same shape as V80 decisions) |
| Server | `SharedAssets` (FORM kind in the index, detail, create, version, owner/archive), `FormAssets` (content checks, compiler wrapper, projection), `AssetReferenceCheck` (form links), `SharedAssetsController`, `AuditWriter` whitelist |
| Studio | `model/sharedForms.ts` (insert, clash choices, diff, update, restore, copy check, save copy as content), `inspector/SharedFormsSection.tsx` on the Form tab (Save as shared form, Insert shared form vN, linked forms with Up to date / Version N available / Edited here, Update to vN, Restore version N, Save as shared version N+1, Unlink); renaming a copied question keeps the link (`renameFieldKey`) |
| Settings | Shared assets lists forms (kind *Form*), each version as "N questions, M rules: titles" |
| Mock | `mocks/sharedAssets.ts`: forms, create/version handlers, the same reference findings; mock publish now runs the reference check like the server; shared assets persist across page loads in mock mode |

## 5. Not included

- Shared **step forms** (fields a human step fills in, S123): a shared form is inserted into the request form only (`target` is always `request`).
- Live references, automatic or bulk update of every workflow that uses a form, notifications to owners.
- A form editor in Settings (forms are authored in the Studio and shared from there); cross-workspace libraries (promotion carries the link; the target needs its own form with the same key and an equal version).
- Comparing question order and groups in the reference check (decision 6).
- Where-used for older releases and archived workflows (as S61).

## 6. Verification - 30 October 2026

- Backend (JDK 21, PostgreSQL via Testcontainers): new `SharedFormsApiTest` (3 tests) covers compiler-checked content (a rule on a missing question is refused with the compiler finding, workflow-level settings refused), requester/viewer 403, duplicate key 409, version conflict 409, identical version 422, versions and content, search and audit metadata; insert with a renamed question validates clean and publishes, a second workflow publishes, where-used lists both with draft and release versions, a new version is a warning and the copy stays, edited question and moved rule are `WF_ASSET_DIVERGED`, reordering and dropping groups is not, archive blocks; skipping a question a rule depends on is a compiler blocker with no asset finding; wrong target, unknown question, unknown form and a second link are reported. `SharedAssetsApiTest` (5) unchanged and passing; `SecurityMatrixTest` covers the two new operations.
- Frontend: `sharedForms.test.ts` (9), `SharedFormsSection.test.tsx` (5), a forms case in `SharedAssets.test.tsx`; full Vitest suite, `tsc -b`, ESLint and `api:check`.
- E2E: `t-forms-03-shared-forms.spec.ts` (mock mode and real stack): save the Purchase approval request form as a shared form, insert it into a new workflow with a `title` clash renamed to `item`, check, publish both, see both under Where it is used.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=SharedFormsApiTest,SharedAssetsApiTest,SecurityMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test`; from `frontend`: `npx vitest run src/features/studio/model/sharedForms.test.ts src/features/studio/inspector/SharedFormsSection.test.tsx src/features/settings/SharedAssets.test.tsx`; from `tests/e2e`: `npm run test:mock -- specs/t-forms-03-shared-forms.spec.ts`.
