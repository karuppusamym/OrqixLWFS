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

## 7. Decisions and reasons

| Topic | Decision | Reason |
|---|---|---|
| Schema | Stays `0.1`. Four additive node kinds (`parallelSplit`, `parallelJoin`, `wait`, `startWorkflow`), an optional `timeoutAfter` on approvals, the route `timedOut`. | Every existing workflow, the AI prompt, the Camunda import and the examples validate unchanged. The compiler moved to `0.2.0` because it generates new constructs (S4 section 9); the only change to the purchase golden files is that version attribute and the hashes that cover it. |
| Parallel structure | A split names its join (`joinRef`); each branch enters the join exactly once; branches share no step; nothing jumps into or out of a branch. The one exit is a rejection (or a timed-out approval) that continues to a *rejection tail* (notifications leading only to rejected Finish steps). Nesting is limited to 2 levels. | "Any rejection ends the request" is the common business need and fits in one sentence. A parallel join waits for one token per incoming path, so two paths from one branch could never both arrive; the compiler rejects that (`WF_PARALLEL_JOIN_MISMATCH`) instead of generating a request that hangs. |
| Join | `parallelGateway`, asynchronous and exclusive. | Approvals decided at the same moment are joined one after the other: exactly one continuation. |
| Rejection | In a workflow with parallel steps, rejected Finish steps are terminate ends; the projection handles `PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT`, cancels the still-open tasks and the active child runs. | Without it the sibling branch would stay open and the run would never complete. |
| Field availability | After a join everything guaranteed in any branch is guaranteed (union); inside a branch nothing of another branch is. | Same dominator analysis as before; a parallel join is the one node where "every path" means "all paths". |
| Timers | `wait` is an intermediate timer catch event; a time limit is an interrupting timer boundary event. Both are Flowable timer jobs: persisted, run by the async executor of any pod, pinned to the release's process definition. Durations: days, hours, minutes; `PT1M` to `P90D`. | Reuses the job executor, lock owner and retry rules of S5 (multi-pod and restart safe); no pod-local scheduler. |
| Approval time limit | `timeoutAfter` adds the route `timedOut` and the decision value `timedOut`. The task is stored as `cancelled` and `TASK_TIMED_OUT` explains why. `decidedBy`, `decidedAt`, `reason`, `comment` become optional. Escalation is an ordinary approval on the `timedOut` route; auto-reject is a route to a rejection. | No new task status in the contract; the behaviour is expressed with existing steps. |
| "Until a date" waits | `format: date` on string fields (plus `formatMinimum`/`formatMaximum`) and `untilField`/`atTime` on `wait`; UTC; see section 10. | Forms now have a date type; the validator, renderer and studio were extended; AI prompts and importers do not use dates yet. |
| Child start | Orqix-managed, not a BPMN `callActivity`: an asynchronous triggerable service task starts a real Orqix run (own row, ORQ reference, events, projection); the child's completion schedules Flowable's asynchronous trigger job on the waiting parent execution. | `callActivity` resolves the child by key (latest version) or needs the child's engine definition id inside the BPMN (not deterministic across environments), creates an engine instance the Orqix projection knows nothing about, and cannot carry the audit and idempotency rules. The trigger job is durable, runs on any pod, is retried by the job rules, and also covers a child that finishes inside the start job. |
| Pinning | The parent's manifest records `workflows[]` (node, workflow key, release id, release number, semantic hash), `callClosure` and `callHeight`; the BPMN carries key, release number and semantic hash only (no release UUID, so artifacts stay deterministic and portable). Republishing a child never changes a published parent. | A release is immutable; the pin is a fact of the parent's release. |
| Child result | One `next` route. Outputs: `outcome`, `outcomeKind`, `reference` and the mapped child outputs. Route on a result with a "Choose a path" step on `outcomeKind` (its domain is the kinds the child's Finish steps have). Mapped outputs the child does not always return are optional in the parent. | Reuses branch validation (domains, disjointness) instead of a second routing system. |
| Child cancelled | The parent continues with outcome "Cancelled" and `outcomeKind` `cancelled`. Cancelling the parent cancels its active children (deepest first). | A cancelled child is a decision a person made; the author handles it with the default route of the choice that follows. |
| Child failure | A child that needs attention stays visible from the parent: `children[]` of the run detail carries its lifecycle and attention, and the parent timeline gets `CHILD_NEEDS_ATTENTION`. Operators retry on the child. | The failed step belongs to the child run; the parent is not duplicated into `needsAttention`. |
| Who may start the child | The parent's initiator, checked when the parent is started (`403` naming the workflow they may not start). The child run's initiator is the parent's initiator. | A workflow can never do more than its requester could: no privilege escalation by wrapping a workflow; separation of duties still applies in the child (the requester cannot approve their own purchase); the requester sees both runs. A delegating system principal with an explicit grant is a deliberate follow-up. |
| Idempotency | Unique key (parent run, step, occurrence) on `workflow_run` (V9). A start job and its retries find the child already created. | A retried or duplicated job never creates a second child. |
| Mock mode | The in-browser API implements all four patterns. Its timers are shortened to a few seconds and resume after a page reload. | So the features can be demonstrated without waiting. |

## 8. Demo 4 script

1. As `paul.publisher` (Purchasing): make sure `purchase-approval` is published.
2. As `dana.designer`: Workflows, New workflow, choose **New hire onboarding** (the card says it starts Purchase approval), Create. On the canvas: two branches (IT with its escalation, Facilities), the join, the wait and **Order equipment**. In the Steps tab select **Allow lead time before ordering**: the inspector reads "Waits 1 minute."; type P2D and it reads "Waits 2 days." Select **IT accounts and access**: "Allows 4 hours." (it escalates to the IT lead).
3. As `paul.publisher`: publish. The manifest pins the purchase workflow's current release.
4. As `rhea.requester`: start the request. Run detail shows two approvals open for different groups.
5. As `mani.manager` approve IT; as `fin.finance` approve Facilities. The run shows "Waiting until the timer finishes" with its due time.
6. After a minute the run detail shows **Other requests started by this one**: *Purchase approval ORQ-nnnn*. Open it: it names its parent. As `mani.manager` approve it. The parent timeline reads "Started Purchase approval ORQ-nnnn" and "Purchase approval ORQ-nnnn finished: Fulfilled"; the parent finishes **Ready** with the purchase order number as its result.
7. Variants: reject Facilities (everything ends, the IT task is cancelled); cancel the parent as `olga.operator` while the child waits (the child is cancelled); republish the purchase workflow while a parent waits (the parent still starts the release it was published with).

Real stack: `python tools/api-e2e/e2e_patterns.py` (scenarios 15 to 17).

## 9. Limitations and follow-ups

- Waiting for a message is described in [S16](S16-message-events.md); no reminders (only the time limit route). "Until a date" waits use UTC (no workspace time zone) and a date question cannot be compared in conditions or decision tables.
- A cancelled child cannot be restarted by an operator; the parent continues with `cancelled`.
- The initiator's right to start the child is checked when the parent starts, not again when the child starts.
- Lock order is child before parent, except when a parent that ends (a rejection) cancels its children; a deadlock needs a child to finish in the same instant and the database reports it as a retryable conflict.
- Two paths from one branch into a join need a shared step first (an automatic merge is a follow-up).

## 10. Wait until a date

Added 10 October 2026; schema version stays `0.1` (additive keywords only).

**Date questions.** A form field `{"type": "string", "format": "date"}` holds a calendar date written `YYYY-MM-DD` (no time, no time zone). Optional `formatMinimum` and `formatMaximum` (same format, inclusive) bound it. The server validates real calendar dates (`format`, `formatMinimum`, `formatMaximum` field errors; 30 February is refused), the request form shows a labelled native date input with `min` and `max`, and the studio offers the question type **Date**. A date can be used to wait; it cannot (yet) be compared in a "Choose a path" condition or a decision table (`WF_OPERATOR_TYPE`). A date can be mapped to a text field of a connection or a date field of a child workflow.

**The step.** `wait.config` has either `duration` (as before) or `untilField` (a field reference such as `/input/startDate`) with an optional `atTime` (`HH:mm`, default `09:00`). The step ends on that date at that time. Exactly one of `duration` and `untilField` is required (`WF_WAIT_CHOICE`). `atTime` without `untilField` is a schema error.

**Rules.** `untilField` follows the usual field-reference rules (`WF_FIELDREF_UNKNOWN`, `WF_FIELDREF_NOT_GUARANTEED` for a step output that does not dominate the wait). It must name a date: `WF_WAIT_FIELD_TYPE` otherwise. A request-form date must be required (`WF_WAIT_FIELD_OPTIONAL`), because a wait without a date would have nothing to wait for. Date question limits are checked by `WF_FORM_INVALID` (a day that does not exist, an earliest date after the latest).

**BPMN.** The step is the same intermediate timer catch event, with `<timeDate>${orqixWait.until(execution, '<id>')}</timeDate>`. Flowable evaluates the expression when the token arrives; the bean `orqixWait` reads the field name and time of day from the step's `orqix:wait` extension (`untilField`, `atTime`), reads the date from the process data, and returns the due moment. The expression allowlist gained exactly this one form (S4 section 6); the bean map gained `orqixWait`. The generated XML is deterministic like the rest (golden files for `examples/start-date-onboarding.json`; the golden files of every earlier example are unchanged).

**Decisions.**

| Topic | Decision | Reason |
|---|---|---|
| Time zone | Dates and times are UTC. The date and `atTime` are combined as that day at that time in UTC, and every screen says so: "Waits until 14 Oct 2026, 09:00 UTC." | A calendar date has no time zone, and the workspace has no time zone setting yet. One fixed rule is predictable, testable and identical on every pod. A workspace time zone is a follow-up (it changes only how the due moment is computed). |
| Default time | 09:00 UTC | A start of the working day in most of Europe and Africa; authors change it with `atTime`. |
| Past date | The wait ends at once. The timer is created already due, so the job executor ends it on its next poll (seconds). `WAIT_STARTED` and `WAIT_ENDED` are still recorded. Today's date with a time of day that has passed behaves the same. | "Wait until the start date" must not strand a request whose date has already arrived. |
| Horizon | At most 366 days after today (UTC). A request is refused when it starts: `422 VALIDATION_FAILED` with a `formatMaximum` field error on the date (`Choose a date no later than ...`). The engine checks the same rule when the wait begins and fails with `WF_WAIT_DATE_TOO_FAR` in the job (the run needs attention) for a date that did not come from the request form; the start check implies the engine check for request fields because the wait begins no earlier than the start. | A human-sized bound beats a request that sits in the engine for decades; checking at the start tells the requester on the form, not a week later. |
| Date comes from | A request-form date today. A step output of type date does not exist yet (HTTP and child results are text or numbers), but the rules already cover it. | Smallest useful surface. |
| Child workflows | A request started by a child step is validated by its own form; a date wait inside a child fails with `WF_WAIT_DATE_TOO_FAR` (attention) rather than refusing at the parent. | The parent's start has no knowledge of the child's date. |
| Restart and pods | Same persisted Flowable timer job as a duration wait: any pod's job executor runs it; a restart keeps it. | Reuses S5. |
| Mock mode | The in-browser API shows the real due moment and ends a future-date wait after a few seconds, like every mock timer; a past date ends at once; the horizon is refused on submit. | So the feature can be demonstrated without waiting. |

**Projection.** The run's current step has kind `wait` and `dueAt` set to the due moment. The studio shows "Waiting until 14 Oct 2026, 09:00 UTC" (any wait with a due time reads this way). `WAIT_STARTED` carries `until` (the due moment) and `duration` null for a date wait; the timeline says "waiting until 14 Oct 2026, 09:00 UTC".

**Studio.** The wait inspector offers **For a duration** or **Until a date**; the second shows the date question (request form dates and guaranteed earlier outputs), the time of day (UTC) and a preview ("Try a date" fills in "Waits until 14 Oct 2026, 09:00 UTC."). The canvas card and the Steps tab read "Until Start date, 09:00 UTC". The Check panel explains problems in business language ("...is not a date", "...that field is optional on the request form").

**Example.** [`examples/start-date-onboarding.json`](../../examples/start-date-onboarding.json): a manager confirms a new colleague's start; the request waits until the start date and tells the requester. It is a sample for tests and demos (create a workflow and `PUT` the file as its draft, as the Playwright and API scripts do); it is not one of the built-in templates, and `new-hire-onboarding` is unchanged.

**Verification.** `WaitUntilDateTest` (compiler: rules, generated timer, golden files, determinism, date validation), `WaitUntilDateTest` (runtime, PostgreSQL and Flowable: due moment, `atTime`, executor restart, past date, horizon, form refusals, check on the wrong field type), Vitest (form renderer and validation, studio model, inspector, mock engine and validation), Playwright `t-ui-12-wait-until-date.spec.ts` (mock) and `tools/api-e2e/e2e_dates.py` (scenario 18, real stack).
