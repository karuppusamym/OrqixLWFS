# S113 - Nested cases, timers, data conditions and the CMMN 1.1 conformance matrix (WM04)

29 October 2026. Branch `claude/big-open-features-f6ffdd`, after [S110](S110-cmmn-canvas-planning-workflow-tasks.md). Migration **V181**. Built in parallel with UI02 round 2 (S114) and MIG11 round 2 (S115) in their own sessions.

## What was delivered

### Case tasks (nested cases)

A standard `caseTask` whose `caseRef` is a QName in `urn:orqix:case` (`xmlns:orqixcase="urn:orqix:case"`, `caseRef="orqixcase:<case-key>"`) starts another case of the workspace.

- **Pinned at publication** (`caseTasks` in `CreateCaseDefinition`): the child definition version that is current then; mappings child field from case field and case field from child field or `outcome`. The server checks the child exists, every required child field is mapped, types match and a case never starts itself. Pins are returned on the definition detail (`case_definition_child`).
- **Runtime**: when the task becomes active, the child case starts on behalf of the parent's starter with the mapped, validated data (`case_instance.parent_case_id`, `parent_plan_item_id`, `depth`, at most three levels). The plan shows the task as *Case task* with an **Open child case** link. When the child completes, the reconciler maps its data back and completes the task; a terminated child leaves the task active for an operator retry; a result after the task ended is fenced. Terminating the task or the parent terminates the child and anything it started. The engine model turns the case task into a blocking task, as for workflow tasks.

### Timers

`timerEventListener` with a `timerExpression` ISO-8601 duration (days, hours, minutes, seconds; 1 minute to 366 days) runs natively in the engine's job executor. Because it fires outside any Orqix command, the reconciler records each occurrence once in the case history (*Timer fired*) and then starts the work it released (workflow or case tasks) and updates the case status. Timers cannot have criteria, controls or repetition; start triggers are refused. Timers keep running while an Orqix command hold is in place (the hold is not native suspension).

### Data conditions (CMMN if-parts)

A sentry may carry one `ifPart` whose condition compares case data fields with literals: `${cd_amount > 5000 && cd_region == 'EU'}` - fields prefixed `cd_`, operators `== != < <= > >=`, number, quoted text (no quotes, backslash, `$`, `#`, braces) or boolean literals, up to ten comparisons joined by all `&&` or all `||`. Nothing else is evaluated. With an on-part the condition must hold when the event happens; alone it is a **data-triggered** rule: the item starts as soon as the data makes it true. Only the fields that conditions read are mirrored into engine variables (`cd_<field>`); a case data change, a workflow result or a child case result updates them and asks the engine to evaluate criteria. A condition that reads an undeclared field is refused at publication.

### Designer, canvas and import

- New kinds **Case task** (folder icon; child case picker and mappings) and **Timer** (double circle with a clock; amount and unit), on the canvas, in the form view and in the preview.
- "**Only when case data matches**" builder on every start rule (field, comparison, value; all/any), shown in words on the shapes.
- Import reads case tasks, timers and if-parts in the grammar above; other conditions, case-file item events, timer start triggers and timer dates/cycles are reported with their path.

## CMMN 1.1 conformance matrix

The target is OMG CMMN 1.1 (formal/16-12-01). "Executable" means admitted by `CmmnSubsetValidator`, deployed to the pinned Flowable 8.0.0 engine and covered by a test that executes it; "import only" means the designer reads it but the server refuses it; "refused" means a source-linked refusal.

| Clause | Construct | Status | Evidence |
|---|---|---|---|
| 5.1 | Definitions, Case, CasePlanModel | Executable (one case per file, key = case ID) | `CaseApiTest`, `CaseLifecycleApiTest` |
| 5.1.4 | CaseRoles and Role | Executable (role name = workspace group) | `CaseEventApiTest`, `CasePlanningApiTest` |
| 5.2 | CaseFile, CaseFileItem, CaseFileItemDefinition | Executable since [S118](S118-cmmn-decisions-case-files-dates-any-of.md) for one-value items named after case data fields | `CaseDecisionFileApiTest` |
| 5.3.1 | Process (external reference) | Executable when `externalRef` names a workspace workflow QName | `CaseWorkflowTaskApiTest`, `CmmnPlanningRulesTest` |
| 5.4.1, 5.4.2 | PlanItemDefinition, PlanFragment, Stage (nested up to four levels) | Executable | `CaseStagesTest`, `CmmnStageValidatorTest` |
| 5.4.3 | PlanItem | Executable (one plan item per definition) | `CmmnStageValidatorTest` |
| 5.4.4 | Sentry, PlanItemOnPart (complete, occur) | Executable | `CaseLifecycleApiTest`, `CaseEventApiTest` |
| 5.4.4 | Sentry IfPart | Executable in the restricted grammar above | `CaseNestingTriggersApiTest` (data-triggered and combined rules) |
| 5.4.4 | CaseFileItemOnPart create/update | Executable since S118 | `CaseDecisionFileApiTest` |
| 5.4.4 | Other standard events (suspend, terminate, exit, reactivate, case file delete/replace...) | Refused | `CmmnPlanningRulesTest`, import report |
| 5.4.5 | EntryCriterion, ExitCriterion | Executable (one entry criterion per item in the designer; several are admitted from files) | `CaseLifecycleApiTest`, `CmmnStageValidatorTest` |
| 5.4.6 | UserEventListener (authorized roles) | Executable | `CaseEventApiTest` |
| 5.4.6 | TimerEventListener (duration; dates since S118) | Executable; start triggers and cycles refused | `CaseNestingTriggersApiTest`, `CaseDecisionFileApiTest` |
| 5.4.7 | Milestone | Executable | `CaseStagesTest` |
| 5.4.8 | Task (generic), HumanTask (candidate groups, blocking) | Executable; non-blocking human tasks refused | `CaseApiTest` |
| 5.4.9 | ProcessTask (blocking, explicit mappings) | Executable; parameter mappings refused (explicit field mappings instead) | `CaseWorkflowTaskApiTest` |
| 5.4.10 | CaseTask (blocking, explicit mappings) | Executable; parameter mappings refused | `CaseNestingTriggersApiTest` |
| 5.4.11 | DecisionTask on a shared decision table | Executable since S118 | `CaseDecisionFileApiTest` |
| 5.4.12 | PlanItemControl: RepetitionRule (finite counter), RequiredRule, ManualActivationRule (constant conditions) | Executable; expression conditions refused | `CaseEventApiTest`, `CasePlanningApiTest`, `CmmnEventRulesTest` |
| 5.4.13 | PlanningTable, DiscretionaryItem, authorized roles | Executable on stages and the case plan; applicability rules and task planning tables refused | `CasePlanningApiTest` |
| 5.5 | Expressions | Restricted: constant booleans, `repetitionCounter < N`, if-part comparisons on case data; no general expression language | `CmmnEventRulesTest`, `CaseNestingTriggersApiTest` |
| 6, 7 | Notation and diagram interchange (CMMNDI) | Canvas notation, CMMNDI emitted, admitted and imported with IDs kept | `CmmnPlanningRulesTest`, `caseModelCmmn.test.ts`, `CaseCanvas.test.tsx` |
| 8.4 | Plan item lifecycle: available, enabled, disabled, active, completed, terminated, occur | Executable through authorized commands | `CaseLifecycleApiTest` |
| 8.4 | Suspend and resume of cases and plan items | Not native: an Orqix command hold freezes commands; engine timers keep running | `CaseLifecycleApiTest` |
| 8.6.1 | Stage and case completion (autoComplete, required items, waiting items) | Executable; waiting items end with the case | `CasePlanningApiTest` |
| 8.6.4 | Repetition on termination | Not evaluated by the pinned engine; repetition with exit criteria refused, termination of repeated items withheld | `CaseEventApiTest` |
| 9 | Conformance classes | Orqix claims no CMMN conformance class: it is a verified executable subset with source-linked refusal of the rest | this table |

## Data and API

- **V181**: `case_definition_child`; `case_instance.parent_case_id`, `parent_plan_item_id`, `depth` (0 to 3); `case_workflow_task.child_case_id` (one target per attempt); `case_definition.has_timers`; an index for recorded timer occurrences.
- **Contract**: `CreateCaseDefinition.caseTasks`, `CaseDefinitionDetail.caseTasks` (`CaseChildPin`), `CaseWorkflowStatus.caseId`. History actions `childCaseStarted`, `childCaseNotStarted`, `childCaseCompleted`, `childCaseTerminated`, `timerOccurred`.

## Verification

| Check | Result |
|---|---|
| Backend case suite (all `Case*Test`, `Cmmn*Test`, CMMN inventory, case stages, engine compatibility) on real PostgreSQL | **59/59 pass**, including the new `CaseNestingTriggersApiTest` (5: child case start with mapped data and parent completion; termination cascade and mapping refusals; timer occurrence recorded once and releasing work; data-triggered and event+condition rules; grammar refusals) |
| Frontend case tests | **47/47 pass**; new `caseModelTriggers.test.tsx` (case task QName and mappings, timers and if-parts generation and refusals, import of the backend fixtures, condition parser refusals, form-view authoring) |
| TypeScript, ESLint on changed files, API types regenerated | pass |

Integrated testing of all three branches (full suites, contract checks, live-stack browser journeys) is recorded in the integration record once the branches are combined.

## Not covered

- CaseFile semantics, decision tasks, parameter mappings, non-duration timers, applicability rules, native suspension and repetition on termination (see the matrix).
- No external CMMN conformance test suite exists to run; the matrix is evidence against the specification text, not a certification.
