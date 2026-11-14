# S138 - Bounded loops ("send back to") and for each on any step

Wave C of [doc 29](../29-camunda-gap-implementation-plan.md) section 9.1: **CG-E01 bounded loops** and **CG-E06 for each on any step**. The doc's recommended defaults were taken. Branch `claude/wave-c-control-flow-63a9ec`, based on `claude/camunda-features-gap-a441b7` at a55eaa31.

Flyway: V310 only (V311-V319 unused). OpenAPI: one paths block and one schemas block, both comment-delimited "ControlFlow", tag `ControlFlow` after `Waits`; a few edits outside the blocks (section 8). Compiler version **not** bumped: loop and for-each BPMN is generated only when a workflow declares `maxTraversals` or `forEach` on a call, worker or AI task, so no golden file moves (all 896 earlier compiler tests pass unchanged).

## 1. Contract (`contracts/workflow-v0.schema.json`)

- Transition: optional `maxTraversals` (integer 1-10) and the new route `on: "limitReached"`.
- `$defs.stepForEach`, referenced as `config.forEach` on `http`, `externalTask` and `aiTask` nodes: `itemsRef` (`/input/<list>`), exactly one of `itemInput` (list of single values) or `itemMapping` (input name -> row column, 1-10), `maxItems` (1-500), `mode` (`sequential` default, `parallel`), `batchSize` (1-25, default 25, parallel only), `completion` (`when`: `all` default, `firstSuccess`, `atLeast` + `count`), `collect` (output name -> per-item result, up to 10).
- Request-form lists widened so a for-each can go above 25: `arrayFormField` min/maxItems up to 500 (was 25), `rowsFormField` up to 500 (was 50). Per-item approvals (S88, 20) and child for-each (S14, 25) keep their own lower limits through their own rules (`WF_PER_ITEM_*`, `WF_FOREACH_BOUND`).

Example (finance sends the request back to the manager at most twice, then rejects):

```json
{ "id": "t8", "from": "finance", "to": "manager", "on": "rejected", "maxTraversals": 2, "label": "Send back" },
{ "id": "t12", "from": "finance", "to": "notifyRejected", "on": "limitReached" }
```

```json
"forEach": { "itemsRef": "/input/lines", "itemMapping": { "title": "title", "amount": "amount" }, "maxItems": 100,
             "mode": "parallel", "batchSize": 20, "completion": { "when": "atLeast", "count": 2 },
             "collect": { "orderIds": "procurementId" } }
```

## 2. Compiler (CG-E01)

- Model: `Transition.maxTraversals` (9th component, old constructors kept), `Route.LIMIT_REACHED`, and `Loops` (model package): each bounded path, its body (steps on forward paths from its target back to its source; bounded paths left out), its limitReached path, and the most visits of every step, `1 + sum(maxTraversals of the loops whose body holds it)`.
- **Proof of the bound.** `GraphRules.cycles` now refuses every cycle of the *forward* graph (`Graph.backEdges(true)` skips bounded paths): `WF_CYCLE`, with a hint to add a limit. So every cycle contains a declared bounded path, each bounded path is taken at most `maxTraversals` times per request, a workflow has at most 5 loops, and therefore no step runs more than 51 times.
- `LoopRules`:
  - `WF_LOOP_NOT_BACK`: a limit only on a path whose target leads back to its source (a real back edge); never back to the request form.
  - `WF_LOOP_LIMIT_ROUTE`: the source step needs exactly one `limitReached` path; `limitReached` only where a bounded path leaves; it cannot carry a limit itself. `GraphRules` allows `limitReached` on exactly those steps.
  - `WF_LOOP_ONE_PER_STEP`, `WF_LOOP_COUNT` (at most 5).
  - `WF_LOOP_SCOPE`: the whole loop stays inside one section/handler or outside all of them.
  - `WF_LOOP_PARALLEL`: a loop contains a whole parallel block (split and join) or none of it, and never jumps into a branch.
  - `WF_LOOP_STEP_KIND`: steps whose runtime keeps one row per step stay outside loops in this slice: message waits (one subscription per step), polling calls (one check row), child workflows (they repeat with "Repeat until"), document generation (one generated file per step), signals, undo (`compensate`, undo handlers, and calls that have an automatic undo).
- Field availability needs no change: dominators are computed on the cyclic graph, so an output of a step inside the loop is guaranteed only after that step (`WF_FIELDREF_NOT_GUARANTEED` before it on the first visit).
- BPMN (only for workflows with bounded paths): the bounded flow ends at a generated exclusive gateway `<t>__loop` (`orqix:loop path maxTraversals limitPath`, default flow = the limitReached path). Its flow back `<t>__again` has the condition `${orqixLoop.again(execution, '<t>', N)}` and take listeners `${orqixLoop.traversed(...)}` and `${orqixLoop.entered(execution, '<to>')}`. Every other flow into a step inside a loop body gets `${orqixLoop.entered(execution, '<step>')}`; the limitReached flow gets `${orqixLoop.limitReached(...)}`. Steps outside loops get nothing.
- BPMN round trip: the reader turns `<t>__loop` + `<t>__again` back into `maxTraversals` and the guard's default flow back into the `limitReached` path (exact; `LoopsAndForEachTest.theSendBackPathRoundTripsThroughBpmn`).

## 3. Compiler (CG-E06)

- Model: `StepForEach` in `WorkflowDefinition.Execution.forEach` by step id (kept off the node records to avoid merge conflicts, like S131's `stepRetry`). The reader folds the item into the step's ordinary input mapping as `/input/<list>/*` (single values) or `/input/<list>/*/<column>` (rows), so the existing input rules of calls, workers and AI tasks type-check items like any other input (`WF_MAPPING_TYPE`, `WF_FIELDREF_UNKNOWN`, ...). `FieldTypes` resolves those references (an optional column is "may be absent").
- Outputs at `/steps/<id>/`: `itemCount`, `completedCount` (integers 0..maxItems), `stoppedEarly` (boolean) and each collected output as a list of the per-item result (entries may be empty when the step stops early or the result is optional). Per-item outputs are not visible at step level (`WF_FIELDREF_UNKNOWN` "collect a result to use it"); failure outputs on the failed route are unchanged.
- `ForEachRules`: `WF_FOR_EACH_KIND` (not on polling or undo calls), `WF_FOR_EACH_LIST` (a request list with typed items), `WF_FOR_EACH_BOUND` (the list's maxItems must be set and covered), `WF_FOR_EACH_ITEM` (itemInput for single values, itemMapping for rows), `WF_FOR_EACH_INPUT` (no clash with inputMapping), `WF_FOR_EACH_COMPLETION`, `WF_FOR_EACH_COLLECT`, `WF_FOR_EACH_PARALLEL` (only calls run items in parallel, see section 5), `WF_FOR_EACH_ROUTES` (a for-each AI task continues on one `next` path; each item's `status` can be collected and routed on afterwards), warning `WF_FOR_EACH_BATCH_UNUSED`.
- BPMN: sequential items are Flowable multi-instance on the step itself (`flowable:collection="${orqixForEach.items(execution, '<id>')}"`, element variable `orqix_forEachItem` = `{"i": position, "v": item}`, completion condition `${orqixForEach.done(...)}` only when the step can stop early). Parallel calls are wrapped in a sequential multi-instance subprocess `<id>__batches` (one instance per batch, `orqix_forEachBatch`); the step inside runs once per batch and sends the batch's items at the same time (section 5); the failure boundary sits on the subprocess. `orqix:forEach` carries the policy; the flow that leaves the step has the take listener `${orqixForEach.collect(...)}`. Round trip exact (sequential and batched).

## 4. Runtime (CG-E01)

- `OrqixLoop` (`orqixLoop`): counters are process variables on the process instance, written in the transaction that takes the flow: `orqix_occ_<step>` (visit number) and `orqix_loop_<path>` (traversals). A retried job sees the same visit number. Events `LOOP_TRAVERSED` (transitionId, traversal) and `LOOP_LIMIT_REACHED`.
- **Occurrence numbers** (`ProcessData.occurrence`):
  - Effects: `HttpTaskDelegate`, `AdapterTaskDelegate` dispatch with the visit number, so each visit is a new effect key (`sha256(ws|run|step|occurrence)`) and a retry within a visit reuses the journal. Notifications dedup per visit.
  - Tasks: `task_context.occurrence` (V310), set by the projection listener for tasks of later visits; `TASK_CREATED` carries `occurrence`. Task decisions and SLA breaches belong to their task, so they are per visit too. AI decision records already numbered each AI job (`ai_task_job.occurrence`).
  - Run path: visits were already numbered per step; loops add `diagram.transitions[].maxTraversals`, `loops[]` (traversals, limitReached) and exact `taken` for bounded and limitReached paths (from the loop events, not inferred).
- **Separation of duties on every traversal**: unchanged code. `separate_from` is checked against every decision of the request at the named steps, so a person who decided the manager step in any visit is kept off finance in every later visit (`LoopsAndForEachRuntimeTest.separationOfDutiesCountsTheDecisionsOfEveryVisit`).
- **Release migration (S38)**: `ReleaseCompatibility` adds `LOOP_CHANGED`: a request waiting at a step inside a loop body (in either release) moves only when every loop is identical in the target release (path id, ends, route, limit, limitReached path, and the type and settings of every step in the body), because traversal counts and visit numbers belong to those paths.

## 5. Runtime (CG-E06)

- `OrqixForEach` (`orqixForEach`): `items` (the list, refused when longer than the step's limit; never truncated), `batches`, `done`, `collect`, and the item store `step_item_result` (V310: run, step, occurrence, position 0-499, succeeded, result). Delegates record an item's result there instead of the step variable; `collect` builds the outputs in item order when the step is left. Events `FOR_EACH_STARTED` and `FOR_EACH_COMPLETED` (items, completed, succeeded, occurrence).
- **Per-item effect keys**: a for-each call journals each item under the step key `<step>#i<position>` with the visit's occurrence, so every item is its own effect, a retry of one item never resends another, and a later visit is new again (`theCallRunsOncePerLineWithItsOwnEffectKey`, `aRetriedItemIsNotSentTwice`). The keyField idempotency value (`<reference>-<step>#i<n>-<occurrence>`) is per item too.
- **Parallel calls in batches**: Flowable runs the jobs of one request one at a time (exclusive jobs, S12), and a parallel multi-instance of async jobs waits one acquisition cycle (10 s) per item; 500 items would take over an hour. Instead each batch is one job: `HttpTaskDelegate.batch` resolves the batch's items, sends them at the same time on virtual threads (each through `EffectDispatcher`, whose journal transactions are their own), records the results in item order in the job transaction, then decides: all succeeded -> next batch; else the first failure in item order takes the failed path (routed known failure) or fails the job, whose retry reuses every recorded success. 27 lines in batches of 10 complete well inside the test wait against the stub (`parallelItemsAboveTheBatchSizeRunInBatches`; separate item jobs had stalled at about 8 s per item). A completion condition is checked between batches (a batch is never cut in half). Durable agent operations (S135) cannot run in parallel batches (attention).
- **Workers and AI tasks** run their items one at a time: their job tables keep one open job per step (`external_job_one_open_per_step`, `ai_task_job_one_open_per_step`, shared constraints not changed here). The engine trigger (`EngineCommands.resumeChild`) now ignores the multi-instance root execution, so the worker or AI completion resumes the item execution; the trigger records the item result (AI: succeeded when `status` is `decided`).
- `getRunStepItems` (`GET /workspaces/{ws}/runs/{runId}/steps/{nodeId}/items`, tag ControlFlow, `run.read | initiator | taskParticipant`, 404 for steps without for-each): every finished item of every visit (occurrence, position, succeeded, completedAt). Results stay in the step outputs.

## 6. Simulator

- Bounded paths are taken while traversals remain, then the limitReached path; trace entries of later visits carry `occurrence`; the loop entry says "time n of at most N" or that the limit was reached.
- Scenario `approvals.<id>.outcomes`: one outcome per visit (last repeats), e.g. `["reject", "approve"]`.
- For-each steps run the step once per item (`itemIndex` on the item entries), stop early per the completion condition, and write the collected outputs in a summary entry.

## 7. Studio and run view

- Connection inspector: "Send back to an earlier step": choose the step (the step itself or any step before it), "At most (times)" 1-10, and "When the limit is reached, continue at" (starts as where the path led). "Stop sending back" undoes it. Drawing a loop by hand stays refused, with a pointer to this setting. Route text on the canvas: "Rejected (send back, at most 2 times)", "Limit reached".
- Call, worker and AI task inspectors: "For each item": list, item input or row columns, limit (1-500), order (calls: one at a time or parallel batches; batch size), when the step is done, and which results to collect.
- Run diagram: steps already show "· 2x"; the step panel adds "Sent back: 1 of at most 2 times (limit reached)" and "Items: 3 of 3 done, 3 succeeded".

## 8. Merge notes (for the coordinator)

- Flyway: `V310__loops_and_for_each.sql` (task_context.occurrence 1-51 with index `task_by_run_node`; table `step_item_result`). No shared CHECK constraint or enum of other tables changed; no permission, problem code or notification kind added.
- Workflow schema: transition `on` enum gains `limitReached` and member `maxTraversals`; `$defs.stepForEach` (inserted before `fieldKey`) referenced from the `config.properties` of httpNode, externalTaskNode, aiTaskNode; **arrayFormField min/maxItems maximum 25 -> 500 and rowsFormField 50 -> 500** (forms session: merge by hand if you changed these). The expressions session may add routes to the same enum: keep both.
- Compiler model: `Enums.Route.LIMIT_REACHED` (last); `WorkflowDefinition.Transition` 9th component `maxTraversals` (8-arg and 7-arg constructors kept); `Execution` 5th component `forEach` (3- and 4-arg constructors kept); new `Loops`, `StepForEach`. Rules: new `LoopRules`, `ForEachRules` (wired after `GraphRules` in `Rules.run`); `GraphRules` (cycles, limitReached allowed, AI task routes with forEach), `Graph.backEdges(boolean)`, `FieldTypes` (item references, for-each outputs; `resolve` now delegates to a new private `stepOutput`), `AiTaskRules.check` (skips route counting for for-each), `ModelReader` (`inputsWithItem`, `stepForEach`, `maxTraversals`). BpmnGenerator: `flow()` signature gains `Scopes, WorkflowDefinition`; `flowSource(.., d)`; `Scopes.loops`, `Scopes.entry/outerTarget` know batches; `failureBoundary` overload; constants `FOR_EACH_ITEM`, `FOR_EACH_BATCH`, `BATCHES`. BpmnReader: batches subprocess, loop guards, `forEach()`. SimulationRun: `bounded()`, `forEach()`, `currentItem`, per-visit approval outcomes.
- App: `FlowableEngineConfiguration` beans `orqixLoop`, `orqixForEach` (and `EXPRESSION_BEANS`); `ProcessData` (constants, `item`, `occurrence`, `effectNode`, `resolve` for `/*` and an item overload); `HttpTaskDelegate` (occurrence, effect node, `batch`, constructor gains `OrqixForEach`); `AdapterTaskDelegate`, `NotificationTaskDelegate` (occurrence); `ExternalTaskDelegate`, `AiTaskDelegate` (constructor gains `OrqixForEach`; trigger records items); `EngineCommands.resumeChild` (skips multi-instance roots); `RuntimeProjectionListener` + `TaskStore.occurrence`; `RunPathBuilder` (`Transition.maxTraversals`, `Loop`, `ForEach`, `Result` gains `loops`, `forEach` with the 8-arg constructor kept), `RunPaths` (keeps them, new `items`), new `StepItemsController`; `ReleaseCompatibility` (`LOOP_CHANGED`). SecurityMatrixTest snapshot gains `step_item_result`.
- OpenAPI outside the ControlFlow blocks: `RunPath.loops`, `RunPath.forEach`; `PathTransition.on` gains `limitReached` and member `maxTraversals`; `PathVisit.itemIndex` maximum 19 -> 499 (description extended); `SimulationScenario.approvals.*.outcomes`. Regenerate `frontend/src/api/schema.d.ts` after merging (`npm run api:generate`).
- Frontend: `RouteKind` gains `limitReached`, `Transition.maxTraversals`; `ROUTE_LABEL`, `RouteEdge` text class, `routeText`/`routeSlotLabel`, mocks/simulator route words; new ops `setSendBack`, `setMaxTraversals`, `setLimitTarget`, graph helpers `forwardReachableFrom`, `sendBackTargets`; the cycle refusal message in `canConnect` changed; new `SendBackFields`, `ForEachFields` (wired into `Inspector`, `HttpInspector`, `ExternalTaskInspector`, `AiTaskInspector`); run diagram `pathModel`, `api.ts`, `RunDiagram.toDefinition`.

## 9. Evidence

- Compiler: `LoopsAndForEachTest` (32 cases: rules, BPMN, round trips of loops, batches, rows, single values and worker items, simulator) plus every earlier compiler test unchanged; `GeneratedXmlTest` allow-list gains the `orqixLoop` and `orqixForEach` expressions (checked by `LoopsAndForEachTest`).
- Runtime (real PostgreSQL and Flowable): `LoopsAndForEachRuntimeTest` (9 cases: send back and second-visit tasks, limitReached path with events and path, separation of duties across visits, a call inside a loop is a new effect and notice per visit, one call per line with its own effect key and the items API with its access rule, retry of an item never resends, parallel batches of 27 lines, stop at first success, worker items one at a time with collected results) and `ReleaseCompatibilityTest.aRequestInsideALoopMovesOnlyWhenTheLoopIsIdentical`.
- Frontend: `SendBackFields.test.tsx` (ops, route text, connection panel, for-each panel), `runs/diagram/loopsAndItems.test.ts`.

## 10. Not done / follow-ups

- For each on wait steps: a timer or message wait per item needs one wait row and one message subscription per item (`message_subscription UNIQUE (run_id, node_id)`, `wait_state` PK), which the waits session owns. Not started.
- Parallel items for workers and AI tasks (needs an item column in `external_job` / `ai_task_job` and their one-open indexes, and per-item current-step projection).
- Loops around message waits, child workflows, document generation, signals and undo (each needs its per-step row keyed by occurrence).
- The run diagram infers choices (silent steps) once per step, not per visit; per-item visits of a call appear as separate visits with `itemIndex`.
- Request-form editor: the list field editor still suggests the old 25/50 maximums in its help text; the compiler accepts up to 500.
