# S41 - Separation of duties across steps and effective-dated rule rows (RL03, bounded)

Two additions for banking-style controls. Neither introduces a new workflow step kind.

## Separation of duties across approval steps

An approval step may list `separateFrom`: ids of earlier approval steps (at most five). Whoever decided one of those steps in the **same request** cannot claim, decide, receive by delegation or reassignment, or be dispatched this step, and the task is not offered to them in the claimable queue or its count. This is in addition to the existing rule that a requester cannot approve their own request.

| Where | Behavior |
|---|---|
| Publish-time rule `WF_SEPARATION_INVALID` | Every listed step must exist, be an approval, differ from this step and **dominate** it: every path from the start to this step passes through it, so the earlier decision has always happened. A step decided only on some paths, or later, is refused. |
| Runtime | The step ids are copied from the pinned release into the task (`task_context.separate_from`, V31). A person is separated when they have a recorded decision on one of those steps in the same run. Claim and decide return 403 `SEPARATION_OF_DUTIES`; task detail reports it as the reason an action is unavailable. |
| Rejected decisions count | Any decision (approve or reject) on a listed step separates the person. |

Limits: only decisions in the same request count; there is no cross-request or cross-workflow separation, no role-based "never both groups" policy, and an operator reassignment to a separated person is refused like any other ineligible target.

## Effective-dated decision rows

A decision row may carry `effective: { from?, to? }`, an inclusive window of UTC calendar dates (at least one bound). The table is evaluated **as of the day the request was submitted**, so a request keeps the rule that was in force when it started, replays and release migration do not change the answer, and a threshold or rate can change on a date without a new workflow release for each case.

- Outside its window a row never matches; if nothing matches, the default applies. A row without a window always applies.
- Under UNIQUE, two rows whose windows are disjoint may share overlapping conditions; windows that share a day, or a row without a window, still count as an overlap (`WF_DECISION_OVERLAP`). A window whose start is after its end is rejected.
- The pinned DMN gets two extra input columns holding the submission date as `yyyymmdd`; the row's bounds are `>=` / `<=` entries. The draft evaluator takes the day as the reserved value `$asOf`, the simulator uses its simulated day, and the browser mock uses the run's start day.
- Decision evidence records `asOf`, and each dated row reports `inWindow` and its window, so a reviewer can see why an expired row did not apply. The canonical first-match check still confirms the pinned result.
- Studio: each row has *Applies to requests submitted from / until*; *Test this rule* has a *Request submitted on* date when any row is dated.

## Not included (deliberate)

- **Currency conversion and multi-currency semantics.** Amounts are compared as plain numbers. Multi-currency rules are written with a currency field in the row (`all` of currency equals and amount comparisons); there is no exchange-rate source, rounding policy or currency type, and none is inferred.
- A table-level "rule pack" object with its own lifecycle, approvals or dates; the unit remains the versioned decision table in a release.
- Dating by anything other than the submission day (decision time, a form date field, business calendars).
- Date windows on the Otherwise result.

## Tests

`SeparationRulesTest`, `EffectiveDatedRowsTest` (compiler: window overlap, evaluator, DMN columns, simulator day); `SeparationOfDutiesTest` and `DecisionHitPolicyTest` (PostgreSQL + Flowable: a decider cannot claim, see or decide the next step while the other manager can; dated rows judged by the pinned DMN with `inWindow` evidence). Studio and mock: `WorkManagementFields.test.tsx`, `decisionPolicy.test.ts`.
