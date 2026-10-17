# S51 - Inclusive gateways and several-approver approvals (EX06, bounded)

EX06 asked for embedded/event subprocesses, inclusive gateways and human multi-instance. This slice delivers two of those. **Embedded subprocesses and event subprocesses are not built** (see Not covered).

## Inclusive split and join

A **Run in parallel** step gains a mode (`config.mode`, `all` by default, or `inclusive`). In `inclusive` mode its outgoing paths are conditions plus exactly one Otherwise path, instead of plain Next paths:

- every path whose condition holds starts, in canonical order; conditions may overlap (unlike a *Choose a path* step, which rejects overlapping conditions);
- when no condition holds, the Otherwise path starts;
- the matching **Wait for all** step waits only for the paths that started. It compiles to a BPMN `inclusiveGateway`, as does the split (with a `default` flow). Ordinary splits and joins still compile to `parallelGateway`, so existing workflows generate the same process as before.

Validation: `WF_INCLUSIVE_DEFAULT` (exactly one Otherwise path), `WF_INCLUSIVE_NO_CONDITION`, `WF_OUTGOING_INVALID` for a plain Next path on an inclusive split (and for a condition path on a plain one), plus every existing parallel-region rule (paired join, branches share no step, nothing jumps in or out except a rejection, nesting depth, one entry per branch) and the normal typing of condition fields. Field availability: after an inclusive join only what was available before the split is guaranteed (a skipped path produces nothing), unlike an ordinary join which counts every branch.

Simulation: the split records each condition's result and which paths started ("starts 2 of 3 paths", or "only the default path"). Route coverage counts inclusive paths as choices.

## Several approvers

An approval gains `config.multiInstance: { "approvers": 2..5 }`. The step opens that many parallel tasks for its candidate group (BPMN parallel multi-instance user task) and continues as **approved** only when all of them approved. The first **rejection** rejects the step at once; the other open tasks are cancelled (status `cancelled`, timeline `TASK_DECIDED` carries `otherApproversCancelled`).

- **Different people.** The task copies its own step id into the existing `separate_from` list, so the shipped separation-of-duties machinery applies unchanged: the claimable queue omits the other open tasks for someone who already decided one, claim/decide/assign/dispatch refuse them (`SEPARATION_OF_DUTIES`), and the allowed-actions report says why. V50 adds a `task_decision.distinct_actor` flag and a partial unique index (`run_id, node_id, actor_subject_id`) so two simultaneous decisions by one person are also refused by the database.
- **Settings it cannot combine with** (`WF_MULTI_UNSUPPORTED`): a time limit, reminders/escalation and approver corrections; counts outside 2..5 fail the schema.
- Run detail shows one waiting entry per open task; the run steps list is keyed by task for these steps.
- A request waiting at such a step cannot be moved to another release (`MULTI_INSTANCE` finding).
- The decision visible to later steps (`/steps/<id>/decision`) is `approved` or `rejected`; `decidedBy` is the last approver. Each person's decision is kept separately in the task history.

## Authoring (Studio)

- Approval inspector: **Number of approvers** (empty = one; 2-5; clamped).
- Run in parallel inspector: **Which paths run?** (Every path / Every path whose condition applies). Switching to inclusive turns the last path into Otherwise and the others into conditions to fill in; switching back turns all into Next paths. In inclusive mode the inspector shows the same path editor as *Choose a path* (conditions, Otherwise, add path). The `connect` rules accept condition/Otherwise paths only for an inclusive split.
- Dragging a new path from an inclusive split on the canvas is refused (the canvas handle makes a Next path); add inclusive paths from the inspector.

## Evidence (run locally on 17 October 2026, JDK 21, PostgreSQL via Testcontainers, Flowable)

- Compiler `InclusiveAndMultiInstanceTest` (7 cases): inclusive gateways + `default` flow in the BPMN with no `parallelGateway`; default/no-condition/plain-path blockers; overlapping conditions allowed; simulator picks 2 of 3 / 1 of 3 / default-only; multi-instance BPMN (`loopCardinality`, completion condition); combination blockers and bounds; simulator wording for approve/reject.
- Application `InclusiveAndMultiInstanceTest` (5 cases, public API, real engine): both approvers must approve and the same person cannot claim/see the second task (403 `SEPARATION_OF_DUTIES`, absent from their queue, present in the other manager's); the database index refuses a second decision by the same person; the first rejection rejects the step, cancels the two other tasks and leaves no open step or job; release-move finding; an inclusive split with 800 / 1,500 / 6,000 starts exactly the default path, the one matching path, and both matching paths, and the join continues once (one finance task, one `TASK_CREATED`).
- Frontend `InclusiveAndMultiInstance.test.tsx` (4 cases): approver count set/clamp/clear, mode switch both ways keeps paths, `canConnect` rules, inspector radios.

Full clean `verify` on this branch passes: compiler 461 cases and the unchanged coverage gate; application 733 cases (729 pass, the same four intentional skips); packaging. Frontend: 95 files, 801 tests pass (one other file failed once under load in a first full run and passed on two reruns), typecheck, scoped lint and `api:check` pass.

## Not covered

- **Embedded subprocesses and event subprocesses (message/timer/error-triggered)**: not built. The workflow model is a flat graph with a single-level canvas; scoped subprocess regions, boundary scopes and non-interrupting event handlers need a separate model change and were excluded from this bounded slice.
- Multi-instance applies only to approvals, only in parallel, with a fixed count of 2..5 and "all must approve" completion (no quorum fractions, no per-instance assignee lists, no sequential mode, no collection-driven instances, no multi-instance for other steps).
- Inclusive mode has no canvas drag-to-add handle (inspector only), and `mock` (offline) validation/simulation does not understand the two new settings.
- The run path/diagram shows one visit per approver task for a multi-approver step; it is not drawn as a special group.
- No browser (Edge/axe) journey was run for these inspectors.
