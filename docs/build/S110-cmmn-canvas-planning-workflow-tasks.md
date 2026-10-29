# S110 - CMMN case canvas, discretionary planning, workflow tasks, general repetition and import (WM04)

29 October 2026. Branch `claude/big-open-features-f6ffdd`, cut from `main` 6b1c0db. Continues WM04 after [S82](S82-case-user-events-repetition.md) and [S94](S94-bpmn-cmmn-notation.md). Migration **V180**. Built in parallel with UI02 (S111, V190-V199) and MIG11 (S112, V200-V209) in their own sessions; this record covers WM04 only.

## What was delivered

### 1. Drawable case canvas

The case model designer now opens on a **canvas** (`features/cases/CaseCanvas.tsx`, React Flow) with the existing form kept as **Form view**. The canvas draws CMMN 1.1 notation: rounded task with a person icon (human task) or a process icon (workflow task), angled-corner stage, stadium milestone, double-circle user event, dashed outline for discretionary items, hollow diamond (entry criterion) on the left and filled diamond (exit criterion) on the right, markers in words.

- **Arrange**: drag any shape. **Nest**: drop it onto a stage; it becomes a child of the innermost stage under its centre. Rules that would cross a stage boundary are removed and the change is announced. Stages can be resized when selected.
- **Connect**: drag from the dot under an item to another item's hollow diamond ("starts after") or filled diamond ("ends when"). A second connector to the same diamond makes a join (all sources). Invalid connectors are refused while dragging (same stage only, no cycles, no rules on user events or discretionary items, no exit rule on milestones or repeated items). Select a connector to remove it.
- **Inspector**: selecting a shape opens the same editor the form view uses; with a stage selected, the toolbar adds inside it. Everything is an ordinary undoable model edit; the view refits when items are added or removed.
- **Diagram interchange**: the generated CMMN carries `CMMNDI` (shapes for every plan item, entry/exit criterion and the case plan, edges from source plan items to criteria). The server admits and checks it (references, finite coordinates, at most 400 elements, 50 waypoints). Flowable's bundled schema requires a `CMMNLabel` in every shape and edge although the OMG schema does not, so the generator writes one and the engine model gets one added when a file omits it.

Pure model operations are in `caseCanvasOps.ts` (move, nest, connect, disconnect, placement, stage fitting).

### 2. Discretionary planning

A stage (or the case plan) may hold a standard `planningTable` with `discretionaryItem`s for human tasks and workflow tasks, each with 1 to 10 `authorizedRoleRefs` mapped to workspace groups. The pinned engine has no planning-table support, so at deployment (`CmmnSubsetValidator.engineXml`) each discretionary item becomes a plan item **with the discretionary item's own ID**, entered by a hidden user event (`<id>__planner`). The stored and exported source keeps the standard elements.

- New command **`POST .../plan-items/{id}/plan`** (`planCaseDiscretionaryItem`, task.act): current membership of a planner group, expected version, reason, idempotency, hold check, protected history; replays re-check the groups. The plan view shows the item as *Discretionary item*: *available*, *planned* or *not planned*, with a **Plan into case** action.
- A required discretionary item binds only once planned. Unplanned work never blocks closing: closing a case ends items that are still only waiting.
- Limits: planned once per stage instance; no entry/exit criteria, repetition or applicability rules on discretionary items; no planning tables on tasks.

### 3. Cases that call workflows

A standard `processTask` whose `process` names a workspace workflow as a QName in the namespace `urn:orqix:workflow` (`xmlns:orqixwf="urn:orqix:workflow"`, `externalRef="orqixwf:<workflow-key>"`) runs that workflow.

- **Pinned at publication**: creating the definition requires one mapping per workflow task (`workflowTasks` in `CreateCaseDefinition`): request field from case data field, and case data field from a result field, `outcome`, `outcomeKind` or `reference`. The server checks the workflow exists and is published, every required request field is mapped, every field exists and types match, and pins the active release (`case_definition_workflow`). The definition detail returns the pins. Later releases of the workflow do not change the case.
- **Start**: when the task becomes active, Orqix starts the pinned release on behalf of the case starter (who must be allowed to start it, checked at case start), through the workflow's own request form validation and open-request limit (`runtime.CaseWorkflowRuns`). The engine model turns the process task into a blocking task with the same ID. A run that cannot start (data does not fit the form, limit reached) is recorded as *could not start* with the reason.
- **Result**: `CaseWorkflowReconciler` (every 2 s, one transaction per task, case row locked before the task row) applies an ended run: maps the result into case data (validated against the pinned fields, with before/after history) and completes the task, which fires its sentries. Results are read from the run record, so a restart loses nothing.
- **Fencing and cancellation**: a result that arrives after the task or the case ended is recorded as *fenced* and never applied. Terminating the task or the case cancels its run (and anything that run started). A cancelled run leaves the task active; an operator can **Retry workflow** (`POST .../plan-items/{id}/retryWorkflow`, run.operate, at most 20 attempts, input mapped again from current data). A held case keeps the result until it resumes.
- Workflow tasks cannot be manually activated (make them discretionary to let people decide); they can be required, repeated and discretionary.

### 4. General repetition

Repetition (finite `repetitionCounter < N`, N 2 to 30) now applies to human tasks, workflow tasks and **stages**, with or without an entry criterion (with one, a new occurrence each time the criterion is met again), and to **milestones with an entry criterion**. Still refused: repetition with an exit criterion (the pinned engine does not evaluate repetition on termination), on user events and on discretionary items. Individual cancellation of repeated items stays withheld (S82).

### 5. CMMN import into the designer

**Import CMMN file** in the designer (`cmmnImport.ts`) reads any CMMN 1.1 file: plan items, stages (four levels), human tasks, workflow tasks, milestones, user events, planning tables, constant controls, finite repetition, entry criteria (one sentry with one or more on-parts), exit criteria, case roles and `CMMNDI` positions. **Model IDs are kept** (definitions, plan items, criteria, sentries, roles, case plan), so export and re-import do not rename them. Everything else is listed in an **Import report** with the element path (for example `case[claim]/casePlanModel[plan]/timerEventListener[t1]`): *left out* (timers, case tasks, decision tasks, case files, if-parts, a second entry criterion meaning "any of", expressions in controls, fixed assignees) or *review* (several candidate groups, a stage that completed automatically, a sentry shared by several criteria, which gets its own copy). Workflow tasks always ask for their mappings. The imported key and name prefill the form. The server validator remains the authority when the definition is created.

### Closing over waiting items

The engine's own "completable" flag counts items that are only waiting (an unplanned discretionary item, the next repetition, an unmet entry rule). CMMN 1.1 (8.6.1) lets a plan with `autoComplete=false` complete over them. *Close case* and *Complete stage* are now available when nothing is working and no required work is unresolved; waiting items end with the case (stages use the engine's forced completion). Active, enabled and required work still block, as before.

## Data and API

- **V180**: `case_definition_workflow` (pins, insert-only for the application role), `case_workflow_task` (attempts; running, completed, failed, cancelled, fenced), and the two new command operations in `case_command_response`.
- **Contract**: `planCaseDiscretionaryItem`, `retryCaseWorkflowTask`; `CreateCaseDefinition.workflowTasks`; `CaseDefinitionDetail.workflowTasks`; `CasePlanItem.workflow` (attempt, status, run, reference, outcome, detail). New history actions: `plan`, `workflowStarted`, `workflowNotStarted`, `workflowCompleted`, `workflowCancelled`, `workflowResultFenced`, `workflowResultRejected`; audit action `case.workflow.completed`.
- **Mock backend**: definitions can be created and exported in mock mode (structural check only); the demo case shows a discretionary item a manager can plan and a workflow task with its run.

## Verification

| Check | Result |
|---|---|
| Backend: all case tests, CMMN rules, CMMN inventory, case stages, engine compatibility, `ArchitectureTest`, `SecurityMatrixTest` (every operation, including the two new ones), `DatabaseTest`, `WorkflowPatternsTest` - real PostgreSQL via Testcontainers | **84/84 pass** |
| New backend tests | `CmmnPlanningRulesTest` (5: admission, engine model keeps IDs, repetition, refusals incl. QName namespace, DI); `CasePlanningApiTest` (3: authorized planning, replay, wrong role, required after planning; unplanned work does not block closing; repeated stages trigger repeated tasks and milestones); `CaseWorkflowTaskApiTest` (4: pinned release, starter as initiator, result mapping into data, sentry fires, idempotent reconciliation; definition refusals; task/case termination cancels the run; late result fenced, cancelled run retried by operator only, unstartable input recorded) |
| Frontend case tests (`npx vitest run src/features/cases`) | **43/43 pass**; new `caseModelCmmn.test.ts` (generation, import round trips of the backend fixtures keeping IDs, diagnostics, canvas operations) and `CaseCanvas.test.tsx` (canvas authoring with nesting and a diagram, jsdom axe, workflow/discretionary fields, import report, Plan action against the mock backend) |
| TypeScript, ESLint on changed files, `npm run api:check` | pass |
| Real browser (built-in browser, mock-mode Vite server) | Designer opens on the canvas; shapes drawn in CMMN notation; a connector drawn by dragging from the task's dot to a milestone's entry diamond; dragging the milestone into a stage nested it and removed the crossing rule with a spoken announcement; adding items refits the view; no console errors |
| Full frontend suite | see the closing note below |

## Not covered (WM04 remains open)

- Nested cases (`caseTask`), timer and other event listeners, if-parts and case-file items/data-triggered sentries, applicability rules, native suspension semantics and decision tasks are not executable; import reports them.
- "Any of" entry criteria (several criteria on one item) cannot be drawn; the server admits them in uploaded files.
- Discretionary items: once per stage instance, stage-level planning tables only.
- Workflow tasks: no manual activation; the starter, not the person whose action activated the task, is the run's initiator.
- No clause-by-clause CMMN conformance matrix or external conformance fixtures; no Docker image refresh, live-stack browser journey, restart test of the reconciler or E2E spec was run for this increment.
- The one-file import of the migration workbench (S58 inventory) still offers no conversion action; import goes through the designer.

WM04 stays **open** under the S56 status rule until those semantics and conformance evidence exist.
