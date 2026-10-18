# S56 — Adaptive cases and application UI designer scope

17 October 2026. The user selected **full CMMN and a general application UI designer** as the target for WM04 and UI02. They are separate product epics. This document makes their acceptance boundaries explicit before implementation is called complete. The current stage bar and conditional request form editor remain useful foundations, but neither is an executable case model or a general application UI designer.

The normative CMMN target is OMG [Case Management Model and Notation 1.1](https://www.omg.org/spec/CMMN/1.1/About-CMMN/), including its [published machine-readable model](https://www.omg.org/spec/CMMN/20151109/CMMN11.xsd). This target should be assessed by a clause-to-implementation conformance matrix and executable model fixtures, not by a feature-name checklist alone.

The existing backend pins Flowable 8.0.0 for BPMN and DMN. Flowable also provides a [CMMN 1.1 engine and Java API](https://www.flowable.com/open-source/docs/cmmn/ch06-cmmn), including a Spring Boot CMMN starter. The preferred implementation is to integrate that engine behind Orqix workspace authorization, case projections and APIs, and verify its supported semantics against the OMG model. Building a second ad hoc lifecycle engine would add avoidable semantic risk. Dependency compatibility and schema migration behavior are demonstrated by the real PostgreSQL compatibility test described below.

The initial compatibility gate is now implemented in [S57](S57-cmmn-engine-foundation.md): the migration job creates engine tables and a PostgreSQL lifecycle test exercises human tasks, sentries, a milestone and case completion through the runtime role. It is an internal engine foundation only; the WM04 acceptance below is still open.

## WM04 — adaptive cases

An adaptive case needs a **separate executable case instance**, not a workflow run with decorative stage labels. Its definition must declare case data and types, stages, milestones, required and discretionary tasks, available actions, and close criteria. A case instance needs a durable event history and a monotonic version. Every operation must verify workspace scope, caller permission and eligibility from current membership, and expected version; retries must be idempotent.

The first delivery should support one workspace, a bounded number of active tasks per case, named eligible performers or groups, task start/complete/cancel, stage activate/complete, milestone achieve once, case suspend/resume and guarded close. Case data updates must validate against the pinned definition, show before/after audit metadata without leaking protected payloads, and reject changes that invalidate an active task. Required work prevents closure; discretionary work can be added by an authorized case worker from a definition's allowed set. A workflow may be launched as a case task with an explicit result mapping; the workflow's own release remains pinned. Case state must reconcile after restart and concurrent decisions.

Acceptance needs real PostgreSQL tests for isolation, idempotency, stale-version conflict, dual worker races, permission revocation, required versus discretionary completion, late workflow result, suspend/resume, restart, and close invariants. The UI needs a case detail page showing data, milestones, active/available tasks and authorized actions; a designer needs to author and preview the bounded model. A real browser journey should create, change and close a case.

**Full CMMN** adds plan items, sentries and entry/exit criteria, repetition, manual and automatic activation, event listeners, nested cases, discretionary planning tables, termination semantics and import/conformance. The implementation must parse and export CMMN 1.1 models with source-linked diagnostics for unsupported content, preserve model IDs, and execute the normative lifecycle, including concurrent plan-item races and restart recovery. No full CMMN claim is justified until these semantics and representative source cases have executable acceptance evidence. The bounded first delivery should therefore receive its own ticket and must not close WM04's full CMMN line.

## UI02 — reusable views and actions

The full application UI designer needs a workspace-owned, versioned view library. Views should describe components, typed bindings to request/case/task data, conditional visibility and action definitions. The first delivery should cover request and approval views, sections and two-column layout, text/number/date/choice/file/row controls already supported by the form renderer, view copy and explicit version pinning. Initial actions should use a fixed allowlist: submit, save draft, approve, reject, request correction and navigate, each checked again by the server. A published workflow/case must pin exact view versions and reject missing or incompatible bindings. Designer changes must not mutate a published view.

The authoring UI needs a visual palette, field and section ordering, responsive preview, binding/error inspection, undo, and a review before publication. Server validation is authoritative for schema, references, permissions and action target. Runtime rendering must enforce the pinned data and action grants; hiding a control in the UI cannot grant or deny an operation by itself. Tests need cross-workflow reuse, version changes, incompatible bindings, read-only roles, stale drafts, accessibility and mobile layout, and a real browser authoring-to-runtime journey.

The full target also includes case and worklist pages, reusable components, data-bound lists/details, responsive breakpoints, localization and theme tokens, action composition, version migration, and a guarded extension mechanism for custom components and approved external data/actions. Extension execution needs sandboxing, capability grants, content security policy and testable failure isolation. Custom rendering or browser code must not bypass server authorization. The initial allowlist is one increment, not the final general-designer boundary.

## First safe increment on this branch

Studio's request form layout now lets a designer reorder sections and reorder questions inside each section with accessible controls. The edits use the existing undoable draft model, pinned `inputUi.layout.sections` and existing compiler/runtime form renderer; no new runtime syntax is introduced. This removes an authoring gap while the two epics are scoped. It does **not** close UI02 because shared views, pinned reusable versions and action authoring are still absent.

The focused `FormBehaviorEditor.test.tsx` suite passes 3/3 cases, including section/question reorder, retention of field rules and undo. Frontend TypeScript typecheck passes. This is local editor verification; no browser or real-stack run is claimed for the increment.

UI02a, the request-form ordering sub-ticket, is closed within this exact editor scope by commit `1ba14d3`. UI02 remains open for the general designer target.

## Status rule

Mark WM04 or UI02 Closed only after the chosen supported model, API, designer, runtime, permission checks and end-to-end acceptance above are delivered. A bounded subset receives its own sub-ticket with a precise name and evidence. This prevents a synthetic demo or a form layout improvement from being reported as full CMMN or full UI designer delivery.

## 18 October engineering follow-up

[S65](S65-engineering-closeout-increments.md) records operator case termination and version-pinned request-view reuse. The existing case API and view library supersede the earlier foundation-only descriptions above. These increments do not close the full WM04/UI02 acceptance in this specification.
