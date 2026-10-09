# S12 — Workflow patterns

Implemented 9 October 2026. Contracts remain at schema version `0.1`; existing workflows retain their behavior.

## 1. Parallel work

`parallelSplit` starts every outgoing `next` branch. Its `config.joinRef` names a matching `parallelJoin`. The compiler checks branch boundaries, matching joins and supported graph structure. The join runs asynchronously with exclusive engine jobs so concurrent arrivals produce one continuation. A rejected end terminates the whole request and cancels remaining tasks and child requests.

The studio offers **Run in parallel** and **Wait for all** in the palette. Each branch can be connected on the canvas or through the Steps tab. Renaming a join updates its split references.

## 2. Supported graphs

Graphs remain acyclic. Bounded rework and arbitrary loops are future work; this change does not enable them. The existing compiler checks continue to govern field availability and required outputs.

## 3. Timers and approval limits

`wait.config.duration` uses a supported ISO duration, for example `PT30M`, `PT2H` or `P1D`. Flowable persists the timer. The run reports a `wait` current step and records `WAIT_STARTED` and `WAIT_ENDED` events.

An approval may set `config.timeoutAfter` and must then include exactly one `timedOut` transition. An interrupting timer cancels its task, records `TASK_TIMED_OUT`, and follows that transition. `dueAfterHours` remains the displayed due date; it does not itself cancel a task. Configure a later approval on the timeout route to escalate work.

## 4. Child workflows

`startWorkflow.config.workflowRef` is the key of a published workflow in the same workspace. `inputMapping` maps its request field names to parent field references; `resultMapping` maps parent result names to child output names. `outcome`, `outcomeKind` and `reference` are provided by every child step.

Publishing pins the child's release ID, release number and semantic hash in the parent's immutable manifest. Republishing a child does not change existing parent releases. The compiler rejects recursive calls and chains deeper than three levels below the root; runtime checks the same depth bound.

The child has its own run, request reference, tasks and effects, and inherits the parent's initiator. A unique database key permits one child per parent/node/occurrence. Child start and projection writes share the engine transaction. Completion schedules a durable asynchronous trigger for the waiting parent, including when the child finishes immediately. Cancelling or rejecting the parent cancels active descendants and their open tasks. Completed external effects retain their journal records.

## 5. Example

[`examples/new-hire-onboarding.json`](../../examples/new-hire-onboarding.json) combines parallel approvals, an approval escalation, a wait and a purchase child. Publish the purchase workflow and configure the example's groups before publishing onboarding.

## 6. Verification

`WorkflowPatternsRulesTest` and `WorkflowPatternsGoldenTest` cover compiler rules and generated artifacts. `WorkflowPatternsTest` exercises timer recovery after executor restart, approval timeout, parallel joins, rejection, child release pinning, immediate child completion and cancellation through PostgreSQL, Flowable and the public API. Editor regression tests cover multiple branches, timeout routes and reference rewriting.
