# S19: Task priority and queue aging

Open task projections retain an integer priority from 0 to 100, default 0. An eligible current assignee can adjust their task; a workspace operator with `run.operate` can adjust any visible open task. Candidates who have not claimed the task cannot change it. Self-approval and group eligibility restrictions still apply to assignees. Priority survives delegation, reassignment and release.

`PUT /workspaces/{workspaceId}/tasks/{taskId}/priority` accepts `{priority, expectedVersion, reason}`. Priority is bounded to 0..100; version is positive; reason is nonblank and at most 500 characters. A locked task compare-and-set increments its version. Closed tasks and stale versions fail without changing priority or creating history. The transaction records `TASK_PRIORITY_CHANGED` with task id, prior/new priority, reason and actor in the run timeline and `task.priority-changed` in durable audit.

Assigned and claimable lists accept `sort=priority|oldest|newest` and `minPriority=0..100`. Default ordering is priority descending, creation ascending, task id ascending. Oldest/newest ordering includes task id as a deterministic tie breaker. The signed keyset cursor includes priority and creation position and is bound to workspace, view, sort and filter; old cursor formats fail. Completed tasks remain ordered by decision date and reject priority filters. Counts remain the full assigned/claimable tab counts. Concurrent priority changes can move tasks between pages; refreshing the list starts a new traversal.

The inbox has accessible sort and priority filters, numeric priority display, actual creation age and existing due-date information. Aging is informational; it does not invent an SLA, alter priority automatically or imply an overdue task when no due date exists. The task panel offers versioned priority adjustment with explicit confirmation and required reason. Failure retains entered values and tells the user to reload. V20 adds the bounded column and partial open-queue indexes.

The priority form captures the task version when opened. Background refresh does not silently advance the version used for confirmation, so a concurrent operator change produces a version conflict. Reopening starts from the current priority and clears the previous reason/error.

Mock APIs follow permission/version/history/filter/order semantics with filter-bound keyset cursors. Mock state is browser-local; production audit and signed cursors remain server responsibilities.

Validation: `TaskPriorityTest` covers ownership/operator permissions, boundaries, version conflicts, reassignment retention, closure, durable reason/audit, and priority pagination/filter binding. `TaskPriority.test.tsx` covers explicit confirmation/version payload, conflict form retention, mock ownership/version enforcement and elapsed creation age. Root runs coordinated isolated Maven verification.

