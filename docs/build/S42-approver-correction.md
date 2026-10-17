# S42 - Approver correction of declared request fields (WM07, bounded)

Extends [S30](S30-incident-correction.md). S30 lets an operator correct a request that is stuck and has not been seen by any task. This adds the common business case: the approver notices a mistake in the request and fixes it before deciding.

## Authoring

An approval step may list `correctable`: up to ten request fields (single-value fields only - text, number, whole number, true/false, date; never lists, rows or files). Studio: *Fields the approver may correct* in the step's inspector.

**Publishing proves the correction is safe.** `WF_CORRECTION_INVALID` blocks a release when:
- a listed field is not a request field, or is a list, row collection or file;
- any step on a path before the approval already uses the field - in a mapping into a system call, document or child request, a decision table, a route condition, a date wait or a message correlation - because the correction would contradict work that already happened;
- the approval sits inside a parallel section, where another branch could be using the request at the same time.

Steps after the approval simply read the corrected value.

## Use

`POST /workspaces/{workspaceId}/tasks/{taskId}/correct-input` with `expectedVersion` (the task's), `reason` and `changes` (field to new single value), plus an `Idempotency-Key`.

| Rule | Detail |
|---|---|
| Who | The person holding the task (claimed or dispatched, still eligible, not separated or self-approving), or an operator with `run.operate` and `run.read-data`. |
| When | Task open, request not suspended, `expectedVersion` current. |
| What | Only the step's declared fields, only to single values; the whole corrected request must still satisfy its form (ranges, formats, conditional rules). |
| Effect | The request data and the engine variable change in one transaction; the task version increases, so a decision prepared on the old data is refused with `TASK_VERSION_CONFLICT`; the timeline gets `INPUT_CORRECTED` with before and after values and the reason (redacted for readers without data access, as in S30); audit `task.input-corrected` carries the task, step and field count only. |
| Replay | The same key and body returns the current task without a second event. |

`TaskDetail.form.correctableFields` tells the caller which fields they may correct now (empty for everyone else, for a closed task and while suspended). The task panel shows *Correct request details* with an input per field and a required reason.

## Limits (deliberate)

- Only declared fields on declared approval steps; no free-form editing of a running request, no adding or removing list rows, no files.
- The correction is not offered after the decision or to people who merely watch the task.
- Object-row workflow iteration (the other half of the original WM07 ticket) is not built.
- Corrections do not re-run steps that already finished, which is why earlier use of a field blocks publishing instead of being reconciled at run time.

## Tests

`ApproverCorrectionTest` (PostgreSQL + Flowable): the holder corrects, the corrected amount routes the request to finance, a stale decision is refused, replay is idempotent, undeclared fields, invalid values, non-holders and closed tasks are refused, an operator may correct, and publishing is blocked when an earlier step used the field. Security matrix covers the operation. Frontend: `TaskPanel.test.tsx`, `WorkManagementFields.test.tsx`.
