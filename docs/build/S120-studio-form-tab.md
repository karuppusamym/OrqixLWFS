# S120: Studio Form tab with live preview

29 October 2026. First slice of the forms, cases and AI review ([docs/26](../26-forms-cases-ai-review.md), findings F1–F5 and C1). It changes only the frontend. There is no contract, compiler, server or migration change, and every existing document renders and validates as before.

## 1. What changed

| Before | After |
|---|---|
| The whole request-form builder sat in the start step's inspector (about 350 px wide), with every question fully expanded. | A **Form** tab (Canvas · Steps · **Form** · Lanes · Source · Test) has two columns: the questions on the left and a live preview on the right. The side panel is hidden on this tab to give it the width. |
| Every question showed all of its controls at once. | Questions are compact rows showing number, label, type, required or optional, and any rule in plain words. One click opens the details (`aria-expanded`, `aria-controls`, short name "Question N: label", with type and rules as the description). A newly added question opens with its label focused. |
| A rule was readable only by opening its editor, and the help text used jargon ("unconditional scalar questions as controllers"). | Rows read "Shown only when Amount (USD) is at least 5000", "Required when …" and "In group "…"" (`model/formRules.ts`). The editor heading is "Rules for …", and its help text explains which questions a rule can look at. |
| Preview was a modal under More. | The preview is live beside the questions. You can type answers, use **Fill sample answers** (the Test tab's `sampleValues`), **Clear answers** and **Check my answers**. A polite status line says how many questions are shown and which are hidden by rules with the current answers. Submit is disabled. More → "Preview the request form" now opens the tab, and `PreviewFormDialog` is removed. |
| The start inspector also held case stages, sections and handlers, form layout and the reusable view. | The start inspector shows a summary (question count, required count, rule count, first six labels) and an **Edit the form** button. **Progress stages** (renamed from "case stages", so they are not confused with the Cases module) and **sections and handlers** moved to the workflow settings. Form layout sections are now **form groups**. |
| Workflow settings were reachable only by clicking empty canvas. | Details has a **Workflow settings** button whenever something is selected, on every tab. |
| A form issue's "Go to step" opened the start inspector. | `locateIssue` maps `/inputSchema/properties/<key>` and `/inputUi/{visibleWhen,requiredWhen}/<key>` to `field.<key>`, and other form paths to `form`. `formFocusKey` decides whether a focus request belongs to the Form tab, and the Studio then opens the tab, expands the question and focuses its label. |

## 2. Files

- **New:**
  - `features/studio/FormView.tsx`: the tab and its preview panel.
  - `features/studio/inspector/QuestionEditor.tsx`: rows and details, moved out of `StartInspector` unchanged apart from layout.
  - `features/studio/model/formRules.ts`
  - `features/studio/FormView.test.tsx`
- **Changed:**
  - `StudioPage.tsx`: tab, focus routing, and `openForm` on the studio context.
  - `StudioContext.tsx`: optional `openForm`.
  - `inspector/StartInspector.tsx`: summary only.
  - `inspector/SimpleInspectors.tsx`: stages and sections in `WorkflowInspector`.
  - `inspector/FormBehaviorEditor.tsx`: wording, form groups.
  - `inspector/StagesInspector.tsx`: "Progress stages".
  - `SidePanel.tsx`: Workflow settings button.
  - `model/issues.ts`
  - `StudioDialogs.tsx`: preview dialog removed.
- **Tests:**
  - updated `FormBehaviorEditor.test.tsx`, `PatternFields.test.tsx`, `StagesInspector.test.tsx` and `TestPanel.test.tsx`;
  - Playwright `t-forms-01`, `t-ui-02`, `t-ui-05`, `t-ui-06`, `t-ui-12` and `t-ui-17`, with new helpers `openFormTab` and `openQuestion` in `tests/e2e/support/app.ts`.

## 3. Verification (29 October 2026, local)

| Run | Result |
|---|---|
| `tsc -b`, `eslint .` (frontend) | Pass, no problems. |
| Full frontend `vitest run` | 178 files, 1,276 tests pass. `FormView.test.tsx` adds 10: rule wording, joined group rules, compact rows, live preview hiding and showing, sample answers and check, add-and-focus, read-only rows, start summary and Edit the form, issue location and focus key. |
| Playwright, mock mode (`E2E_MOCK=true`), six affected specs | 13 of 14 pass after the updates, including `t-forms-01`, which now also checks the preview hiding "Extra notes" below 5,000 and the row's rule text. One fails, **`t-ui-12` "studio: Date question…"**, at its first canvas assertion (`Until Start date, 09:00 UTC`): the card renders as a BPMN shape with text "Wait until the start date". That line is before any S120 change and no canvas file is touched, so the failure is treated as pre-existing, though it was **not re-run on `main`** to confirm. A copy of the test without that one assertion passes the S120-changed part (Form tab, Date question, Required toggle, check messages). |
| Real browser, mock mode, 1440×900, Dana Designer | Form tab renders with the questions and live preview. Added "Show only when" on Business purpose, set Amount (USD) is at least 5000; the row shows the rule, the preview hides the question until 7500 is typed, then shows all 4. The start step shows the summary; Workflow settings shows progress stages and sections. An invalid answer pattern found by Check workflow → **Go to step** opens the Form tab with that question expanded and its label focused. No console errors. Mock data was reset afterwards. |

Not done: an automated axe scan of the new tab outside the `t-ui-05` mock run, and a narrow-screen pass. On screens narrower than 1024 px the tab is read-only and stacks to one column.

## 3a. Real use case and real-stack run (30 October 2026)

The old integration stack (`orqix-integ-1029`, 52 workflows and 65 runs, almost all left behind by e2e runs) was removed together with its database volume. A fresh stack (`orqix-s120`) was started from an image built from this branch.

New spec **`t-forms-02-it-equipment-request`**, which runs in mock mode and on the real stack:
- The designer starts an empty workflow from the New workflow dialog and builds the request form on the Form tab: 7 questions, 5 rules, 3 groups and an answer pattern.
- Every rule's wording and preview behaviour are checked, as is Check workflow.
- The workflow is published.
- A USD 900 laptop is approved by the manager only, and the hidden answers are not stored.
- A USD 2,600 replacement item is approved by the manager and then finance. The asset tag `IT-12` is refused with the author's message, and `IT-00421` is accepted.

Bugs found while building and running it, all fixed in this slice:

| Bug | Fix |
|---|---|
| Renaming a question's field key (under Advanced) remounted the question: Advanced closed and focus was lost. | Open state of Advanced is kept across the rename, and focus returns to the key field. `Advanced` gained `onToggle`. Unit test added. |
| Form groups used the id `<group>-<fieldKey>` for "Include …" checkboxes, so a question keyed `title` (the purchase template's first question) collided with the group title input. The checkbox lost its label and the title box got the wrong accessible name. This predates S120. | Ids are `<group>-include-<fieldKey>`. Unit test added. |
| **Real backend only:** "Start empty" created a workflow with no steps (`WorkflowService.emptyDefinition`), although the dialog promises "a request form and a finish step" and the mock creates them. The Studio cannot add a request form step, so such a workflow could never be completed. | The backend creates `start` (Request submitted) → `finished` (Finished, success) with a layout, like the mock. `CatalogApiTest.startEmptyCreatesARequestFormAndAFinishStep` added. |
| `t-engineering-closeout` and `t-view-presentation` (real stack only) still chose the reusable request view on the start step. | They open the Form tab. |
| Found while building the use case by hand: in a real browser, "Add a question" did not move focus to the new question's label, because the focus ran on a 0 ms timer before the question rendered (jsdom hid this). Typing and Enter then went to the button and added a second question. | Focus is applied in an effect after render (`pendingFocus`), for new questions and renamed keys. Checked in a real browser: focus lands on the new label. |

Finding, not changed: grouped questions are rendered before ungrouped ones (the S35 rule), while the Form tab's question list keeps definition order. A question put into a group therefore jumps to the top of the real form. Changing the runtime order would alter published forms, so this is left for a later slice; the use case puts every question in a group.

Second real-stack run, after the fixes, on a fresh stack with `E2E_STUB_URL` set: **86 passed, 0 failed**, 65 skipped by design (25 minutes). Full backend `mvnw clean verify` (Testcontainers): BUILD SUCCESS, compiler 650 and application 1,092 tests, 0 failures, 4 skipped (the existing opt-in live tests). Full frontend `vitest run`: 178 files, 1,278 tests pass, and `tsc -b` and `eslint .` are clean.

The stack was then wiped again and the use case built on the clean stack through the UI, using a temporary Playwright script that was not kept:
- the questions, rules and groups on the Form tab;
- the steps in the Steps tab with Add step after, Add a path and route selects;
- Check workflow;
- publishing as the publisher.

Four requests were then run:
- a laptop (USD 900), approved by the manager;
- a docking station (USD 2,600, replacement `IT-00421`), approved by the manager and then finance;
- a phone (USD 1,200), rejected by the manager;
- a headset (USD 120), left waiting for a manager.

The stored inputs contain only the answers that were shown. The stack holds the two seeded templates, this workflow and these four requests.

First real-stack run, before these fixes: 82 passed, 65 skipped by design (mock-only specs and presenter rehearsals), 4 failed. The failures were the two view specs and the empty-workflow bug above, plus `t-ex-06`, which needs `E2E_STUB_URL` to be set (an environment setting, not a code problem).

## 4. Limits and next slices

- Open and closed questions are not kept when switching tabs, because the tab's content unmounts.
- The preview shows the requester's view only. A "show as this step" switch comes with step forms (S121).
- A check issue opens the question, not the exact control inside it (for example the answer pattern under Advanced).
- The next slices are in docs/26 section 5:
  - S121: step forms and the data-capture gap;
  - S123: one AI conversation;
  - S122: a shared field editor for case data;
  - S124: a case designer page.
