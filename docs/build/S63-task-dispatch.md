# S63 - Explicit task dispatch (WM06)

An approval step may declare `dispatch.policy`. When a task for that step is created, Orqix assigns it to one eligible person instead of leaving it in the candidate-group queue, and records why. Without the setting nothing changes.

| Policy | Rule |
|---|---|
| `leastLoaded` | The eligible person with the fewest open assigned tasks in the workspace. Ties go to the person who was assigned a task longest ago (never-assigned people first), then to the lowest subject id. |
| `roundRobin` | The eligible person who was assigned a task longest ago, regardless of load. |

**Eligibility** is exactly that of delegation: member of the candidate group, member of every required skill group, `task.act`, enabled, and not the requester unless self-approval is allowed. It is evaluated against each known person's groups stored at their latest login, over at most 500 known people. A person who has never signed in is not a candidate.

**Behavior.** The assignee appears on the step and the task, can decide without claiming, and can still return the task to the group (it then waits in the queue; it is not re-dispatched). If nobody is eligible the task stays in the queue. The timeline records `TASK_DISPATCHED` with the policy, the person, the number of eligible people, their open-task count and the rule in words; the other candidates' names and loads are not recorded. Reassignment by an operator and delegation work as before.

**Settings and releases.** The policy is read from the pinned release definition, so a running request keeps its policy. Studio: the approval step's inspector offers *Assign new tasks*.

**Limits (deliberate).** No proficiency levels or weights (skills stay binary group membership), no absence/out-of-office calendar, no capacity caps, no re-dispatch on release or timeout, no notification to the assignee beyond the existing task list, and the load is a plain count of open tasks, not estimated effort.

**Tests.** `TaskDispatchTest` (PostgreSQL + Flowable, public API): least-loaded choice and explanation, deciding without claiming, round robin across completed work, returning the task to the group, the requester never chosen for their own request, and a step without a policy staying in the queue. Studio: `WorkManagementFields.test.tsx`; timeline text in `events.test.ts`.
