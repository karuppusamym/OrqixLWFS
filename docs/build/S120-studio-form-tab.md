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

Not done: a real-stack (Docker) run, an automated axe scan of the new tab outside the `t-ui-05` mock run, and a narrow-screen pass. On screens narrower than 1024 px the tab is read-only and stacks to one column.

## 4. Limits and next slices

- Open and closed questions are not kept when switching tabs, because the tab's content unmounts.
- The preview shows the requester's view only. A "show as this step" switch comes with step forms (S121).
- A check issue opens the question, not the exact control inside it (for example the answer pattern under Advanced).
- The next slices are in docs/26 section 5:
  - S121: step forms and the data-capture gap;
  - S123: one AI conversation;
  - S122: a shared field editor for case data;
  - S124: a case designer page.
