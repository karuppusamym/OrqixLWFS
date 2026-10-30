# 26: Forms, cases and AI authoring: design review and redesign plan

29 October 2026. Review of how forms, case data and AI-assisted authoring are designed today, written after reading the code and contracts and walking the Studio, Cases, Views and Assistant screens in mock mode (1440×900, Dana Designer). It ends with a plan in slices. **Status:** S120 (the Form tab) is built, see [S120](build/S120-studio-form-tab.md). Step forms are built as [S123](build/S123-step-forms.md) (S121 and S122 were already taken by DMN parity and generated test cases, so the slices below are renumbered: step forms S123, one AI conversation S124, shared case field editor S125, case designer page S126).

## 1. Verdict

The engine side is strong and, in places, ahead of the competition: one schema enforced in the browser and on the server, publish-time proof that a conditional field is available before a step uses it (`FormGuards`), immutable pinned releases, and AI output that is never trusted (closed operation set, real compiler validation, review before apply).

The authoring side is the problem. Each capability was added as its own slice (S35, S42, S56, S69, S79, S80, S83, S90, S95, S111), so the same idea now has several editors, several names and several places. A designer has to know the history of the product to build a form. That is the confusion you see in the UI.

There is also one functional gap that matters against Camunda and Pega: **a human step cannot collect new data.** An approval can approve or reject, give a reason, and correct a listed request field (`correctable`), but it cannot ask for a PO number, an approved amount or a risk rating. In Camunda every user task has a form, and in Pega every assignment has a view.

## 2. Findings

### 2.1 Forms

| # | Finding | Where |
|---|---|---|
| F1 | The whole form builder lives in the start step's inspector, about 350 px wide. Every question is expanded at once (label, all 11 types, required, help, conditions, advanced), so four questions already make a very long scroll. | `StartInspector.tsx`, `FormBehaviorEditor.tsx` |
| F2 | The same inspector mixes four unrelated things: the request form, **case stages** (progress labels), **sections and handlers** (BPMN error scopes) and **form layout sections**. Three of them are called "section" or "stage" and mean different things. | Start step inspector |
| F3 | Conditions are shown on every question by default, with wording such as "Use unconditional scalar questions as controllers". A rule's meaning is not summarised ("Shown when Amount ≥ 5,000"); you have to open the editor to read it. | `FieldConditionsEditor` |
| F4 | Layout is edited apart from the questions: a section is built by ticking "Include <question>" checkboxes in another panel, then reordering there. You cannot drag a question into a section. | `FormLayoutEditor` |
| F5 | There is no live preview while editing. Preview is hidden under More → "Preview the request form" as a modal, so the effect of a condition cannot be tried while you write it. | `StudioHeader.tsx` |
| F6 | There are two sources of truth for labels: the workflow's own questions and an optional pinned **request view** from the View Library, which can relabel, translate and re-section the same fields. The seed already has "Purchase request" *and* "Purchase form" views next to the workflow's own form. | `RequestViewPicker`, Views page |
| F7 | Human steps cannot capture data (see section 1). `correctable` and the pinned approval view only show or edit request fields. | `approvalNode` in `workflow-v0.schema.json` |

### 2.2 Cases

| # | Finding | Where |
|---|---|---|
| C1 | There are two separate "case" ideas. Workflow **case stages** are progress labels on a run, while the **Cases** module is a CMMN engine with its own definitions, data and views. The UI does not explain when to use which. | Start inspector, `CasesPage` |
| C2 | Case data has its own field editor and type system (`CaseDataFieldsEditor`; key pattern `[A-Za-z][A-Za-z0-9_]`), separate from request questions (`^[a-z][A-Za-z0-9]`). It has no conditions, sections or patterns. This makes three field editors: request questions, case data fields and the View Library. | `CaseDataFields.tsx` |
| C3 | The Cases page opens with a raw "Create case definition" form (key, name, CMMN upload) above the list of cases. Authoring and working cases share one page, and a definition has no draft → check → publish flow like a workflow. | `CasesPage.tsx` |

### 2.3 AI and LLM use

| # | Finding | Where |
|---|---|---|
| A1 | There are too many entry points, each with its own model of the conversation: *Describe it* (one-shot async job), *Answer a few questions* (no AI), *governed questionnaire*, the Studio **Ask AI** dock (multi-turn, S95), **Suggest questions with AI** (form only, S90), **Explain**, **Ask Orqix** (Ctrl+K) and the runtime **Assistant**. | `NewWorkflowDialog`, `AskDock`, `FormAssistant`, `ExplainPanel` |
| A2 | *Describe it* is one-shot. It never asks a clarifying question; it guesses and lists "assumptions". Its description is not handed to the Ask AI dock, so the conversation starts from zero in the Studio (S95 section 8). | `draft-v1.md`, S95 |
| A3 | The AI cannot express the form rules the product has: no operation for `visibleWhen`, `requiredWhen`, form sections or approval corrections, and the draft prompt does not describe them. AI-made forms are therefore always flat. | `DraftEdits.java` (ops: addStep, updateStep, renameStep, removeStep, connect, disconnect, changeFormField) |
| A4 | Ask AI cannot add calls to systems, decisions, parallel work or child workflows (S95 section 8). It can add approval, branch, notification, wait and end. | `DraftEdits.addStep` |
| A5 | There is no AI for cases, views or pages. | `features/cases`, `features/views` |
| A6 | The conversation is lost on reload and is not shared with other editors. | S95 section 8 |
| A7 | The safety architecture is right and should be kept as the product's differentiator: structured operations, compiler validation, review before apply, nothing stored but counts. | S10, S90, S95 |

## 3. Where Orqix should stand against the competition

| | Camunda 8 | Pega | Lightweight tools (Kissflow, Power Automate approvals, Jotform) | **Orqix target** |
|---|---|---|---|---|
| Form model | Separate form file per user task, linked by form ID | Case data model; a view per step | One form; per-step field permissions (hidden, read-only, editable) | **One data model per workflow; each human step gets a field-access matrix plus fields of its own** |
| Data a step can use | Whatever the process variables happen to hold at runtime | Case data | Anything on the form | **Proved at publish: a step may use a field only if every path to it captured that field** (extends `FormGuards`) |
| Dynamic rules | FEEL expressions | When rules | Simple show/hide | Structured conditions, re-checked on the server (existing) |
| AI | Copilot generates BPMN, FEEL and forms; you fix the result | Blueprint generates case types from a conversation or documents | Template suggestions | **One conversation that asks before it guesses and proposes reviewable changes the compiler has already validated** |
| Process vs case | BPMN plus a separate CMMN offering | Case-first | Process only | **Process first; the same data and step forms promote to a case when work becomes flexible** |

The unique position: **as light as Kissflow to build, with guarantees that Camunda and Pega leave to testing.** No other tool proves at design time that every human step sees exactly the data it needs. Orqix already has most of the machinery for this.

## 4. Target design

1. **One data model.** A workflow's fields are its data: today's `inputSchema`, plus fields that later human steps capture. Case data fields use the same field type, editor and renderer.
2. **Step forms as a matrix, not separate files.** Every human step has a field-access setting per field: *hidden*, *read-only*, *editable* or *required*. It can also own new fields (for example `poNumber` captured at "Create order review"). This generalises `correctable` and replaces most uses of approval views. The compiler checks data flow: a field is available to a later step only when every path captured it.
3. **A Form tab in the Studio.** The tab bar becomes Canvas · Steps · **Form** · Lanes · Source · Test. The tab shows:
   - the field list on the left: compact rows with a one-line summary, where clicking a row opens its details;
   - a **live preview** on the right, with a "Show as" switch (requester, or any human step) and sample answers to try the conditions;
   - the step matrix as a grid: fields × human steps.

   The start step's inspector keeps only its name and a "Request form: 4 questions, Edit form" link.
4. **Plain rules.** Conditions are collapsed behind "Add a rule" and summarised in words. Controllers that are not allowed are disabled and say why, without jargon.
5. **Clear names.** Form groups are **pages or groups** (form layout). **Progress stages** are the run's labels. **Error scopes** are today's "sections and handlers", moved to the workflow level. "Case" means only the Cases module.
6. **Views stay for presentation only.** Views handle translation, theme and application pages; the form's structure comes from the workflow. Opening a view from the form shows a clear "presentation of this form" link instead of a second form editor.
7. **One AI conversation.**
   - *Describe it* becomes the first turn of the same Ask AI conversation. The model may ask up to three short clarifying questions (who approves, any thresholds, what data later steps need) before drafting; the author can always say "just draft it".
   - The draft opens in the Studio with the conversation attached.
   - New operations cover field rules, step field access, form groups, decisions and calls to systems.
   - The safety pipeline (closed operations, compiler, review, apply as one undo) is unchanged.
   - The conversation is saved with the draft, as text the author can delete. That is a policy change from S95 and needs an explicit decision.

## 5. Plan in slices

Each slice is shippable on its own, keeps every existing document valid, and follows the repo's rules: contract first, compiler, server, mock, UI, tests.

| Slice | Scope | Contract change |
|---|---|---|
| **S120 Form tab** (UI only) | Form tab with compact field list, inline details, live preview with sample answers and plain rule summaries. The start inspector shows only the step name and a link. Move case stages and error scopes out of the start inspector. | None |
| **S121 Step forms** | `approvalNode.fields: { <key>: hidden \| readOnly \| editable \| required }`, plus `stepSchema` for fields captured at that step. Compiler data-flow rule, runtime validation on decide, task UI rendering, mock, AI awareness. `correctable` keeps working and maps to `editable`. | Additive, optional |
| **S122 Shared field editor for cases** | Case data fields use the S120 field editor and the form renderer; one key pattern, with a migration note for existing keys. | Case field type aligned (additive) |
| **S123 One AI conversation** | Clarifying questions, Describe it hands off to Ask AI, new operations (`setFieldRule`, `setStepFieldAccess`, `setFormGroups`, `addDecision`, `addCall`), prompt `draft-edit-v2`, and a fixed evaluation set extended. | OpenAPI additions only |
| **S124 Case designer page** | Case definitions get a draft → check → publish page; the Cases page lists and works cases only. | None |

Recommended order: S120, then S121 (the functional gap), then S123, S122 and S124.

## 6. Open decisions

- Whether step-captured fields belong in `inputSchema` (one schema, with a "captured at" marker) or in a per-step `stepSchema`. A per-step schema is recommended: it keeps the request contract and API start payloads unchanged.
- Whether to keep the AI conversation with the draft (A6). Recommended: keep it, deletable, never sent to audit.
- Whether approval views remain as a second way to shape an approval screen once the step matrix exists. Recommended: keep them for presentation only, and deprecate their field binding.
