# S104 - Restore archived workflows, callback token cleanup and limits, per-item quorum (28 October 2026)

Three loose ends of features that were already shipped: archiving had no way back (S103), callback tokens were never cleaned up or rate limited (S98), and per-item approvals had one fixed rule (S88). Migrations **V174**, **V175** and **V176**; apply them before starting this image.

## 1. Restore an archived workflow (PL04)

| Decision | Choice |
|---|---|
| Where | **Workflows → Archived workflows** (people with `workflow.edit`): the most recently archived first, with who archived it and when, and the published version. **Restore…** asks why (3 to 300 characters), then puts the workflow back. |
| What comes back | Everything that archiving hid: catalog entry, folder, owner, draft and active release. Starting it again needs the same grants as before. Keys are unique per workspace including archived workflows, so a restore can never clash. |
| API | `GET /workspaces/{ws}/archived-workflows` (`listArchivedWorkflows`, up to 200, `truncated` when more) and `POST /workspaces/{ws}/workflows/{id}/restore` (`restoreWorkflow`, `expectedVersion` + `reason`, returns the workflow). Not archived or unknown: 404, so the call cannot probe live workflows. Stale version: 409 `WORKFLOW_VERSION_CONFLICT`. |
| Stored | V174 adds `workflow.archived_by` (set on archive, cleared on restore; null for workflows archived before V174). The reason is not stored, only its length in the audit (`workflow.restored`, like `workflow.archived`). |

The Organize dialog's archive text no longer says "cannot be undone"; the confirm button is now **Archive it**.

**Tests.** `WorkflowArchiveApiTest` (2): list needs edit, archived-by and release shown, restore needs edit, stale version refused, restore returns release 1, a second restore is 404, the workflow is back in the catalog and starts again. `SecurityMatrixTest` covers both operations from another workspace. Frontend `Organization.test.tsx`: archive then restore through the dialogs, reason required, confirmation shown, row back in the list. Also run by hand in mock mode in a browser (archive, Archived workflows list with "archived ... by Dana Designer", restore, row back); that run found the offline mock's overdue check failing on requests of an archived workflow (present since S103), now fixed by letting the mock read releases of archived workflows.

## 2. Callback tokens: cleanup and a per-token limit (EX10a)

| Decision | Choice |
|---|---|
| What is removed | A **closed** callback wait (resumed, answered elsewhere, timed out, or its request ended) once `orqix.callbacks.retention` (default **30 days**, 1 hour to 365 days) has passed since it closed. The row is deleted, so its token answers 404 like any unknown token, and a late replay of the original resume is no longer possible. Waits that are still open are never removed, even with an expired token. |
| How | The existing callback sweeper (every 30 s, every pod, `FOR UPDATE SKIP LOCKED`) deletes up to 500 rows per batch and 20 batches per pass. One audit entry per workspace and batch: `callback.waits-purged` with `count` and `retentionSeconds`. V175 grants `DELETE` on `callback_wait`. |
| Limit | Poll and resume together: `orqix.callbacks.requests-per-minute` (default **60**) per token, a sliding one-minute window on each pod; beyond that **429 `RATE_LIMITED`** with `Retry-After`. Only tokens that resolve to a wait are counted, so guesses cannot fill the table; the table is keyed by the token's SHA-256 and holds at most 50,000 tokens. With several pods a token may make up to pods × limit calls: this protects the service, it is not a quota. |

**Tests.** `CallbackTokenApiTest` (2 new, 9 in all): a resumed wait survives a purge inside the retention and its replay still works; 31 days later it is gone (poll and resume 404) while an open wait in the same workflow keeps working; the audit entry has the count and no token text. 60 polls pass, the 61st and a resume are 429 with `Retry-After`, an unknown token is 404 and another token is unaffected. `CallbackRateLimiterTest` (2): the window slides and settings outside their bounds are refused.

## 3. Per-item approvals: quorum, one at a time, proposed approver (CMP02a)

New optional members of `config.perItem` (workflow schema, no version bump; written to BPMN only when set, so no S88 workflow or golden file changes):

| Setting | Meaning | Compiler |
|---|---|---|
| `quorum: k` (1 to 20) | The step is **approved as soon as k items are approved**, and **rejected as soon as k can no longer be reached** (more than n − k rejections). The open items are then cancelled. Without it, k = n: the S88 rule. | `WF_PER_ITEM_QUORUM` when k is more than the list's `minItems`, so every request can reach it. |
| `sequential: true` | Items open **one at a time, in list order**; the next opens only after the previous is decided. Items not reached when the step settles count as `cancelled`. With one person per item, the person who decided item 1 is refused item 2 by the existing separation of duties. | Flowable `isSequential="true"`. |
| `assigneeFrom: <column>` | A plain text column of a list of rows holding an **email**. When an item task opens, the one active person with that email (case-insensitive) is assigned **only if** they could claim the task anyway: candidate group, required skills, `task.act`, the self-approval rule and separation of duties. Otherwise the task stays in the group queue, or goes to the step's dispatch policy if it has one. | `WF_PER_ITEM_ASSIGNEE` for a list of single values, an unknown column or a column that is not plain text. |

**Runtime.** A quorum or one-at-a-time step uses a new completion condition, `orqixRoute.itemsSettled`, which is true once the decision service has recorded the settled outcome (`itemDecisions` in the step output). The decision service now settles every list approval the same way: n items (V176 `task_context.item_count`, set when each item task opens; S88 tasks without it use the row count), k from the release, approvals and rejections counted from the decisions of this visit. For an S88 step (k = n) the result is the same as before. `itemDecisions` and `approvedCount` keep their types; `cancelled` now also covers items that were never opened. The proposed person shows on the timeline as "assigned ... Proposed for this item on the request" (`TASK_DISPATCHED`, policy `proposed`); when they cannot take it, `TASK_PROPOSED_UNAVAILABLE` says the item waits in the group queue, without the email.

**Studio.** Under *One approval per list item*: **Decide items one at a time, in list order**, **Items that must be approved** (empty = all) and **Proposed approver** (text columns only). Changing the list keeps the one-at-a-time and same-person choices and drops the quorum and column, which belong to the old list. The offline mock validator and simulator follow the same rules.

**Simulation.** With a quorum the first k items are approved and the rest cancelled; a rejection starts at `rejectedItem` (at most k) and continues until the quorum can no longer be reached, so with 3 items and quorum 2 the test shows `[rejected, rejected, cancelled]`. A test list shorter than the quorum stops with `TEST_LIST_SHORT` (normally refused earlier by input validation).

**Tests.** Compiler `PerItemApprovalTest` (5 new, 12 in all): BPMN attributes and settle condition only when set, parallel quorum, quorum reachability, assignee column rules, simulator approve and reject wording and outputs, exact BPMN source round trip and refusal of an edited `isSequential`. Application `ListDrivenApprovalTest` (5 new, 10 in all, PostgreSQL and Flowable): quorum 2 of 3 approves after two approvals and cancels the third (run path shows it cancelled, no engine job left); one rejection of three keeps the step open, the second rejects it; one at a time opens item 2 only after item 1, refuses the item-1 decider for item 2 and completes; one at a time stops at the first rejection having opened one task; the proposed manager is assigned (email matched ignoring case), while the requester, an unknown email and an empty column leave the item in the queue, and the timeline never contains the email. `InclusiveAndMultiInstanceTest`, `SeparationOfDutiesTest` and `TaskDispatchTest` still pass. Frontend `ListApprovalFields.test.tsx` (2 new) for the three controls and the offline checks.

## Evidence (28 October 2026, JDK 21, PostgreSQL via Testcontainers, Flowable)

Full `mvnw clean verify`: compiler 645 tests pass; application 1,023 run, 4 skipped (the usual opt-in live tests), none failing; build success. Frontend: 161 files, 1,191 tests pass (`vitest run`), typecheck, lint of the changed files and `api:check` pass. `contract_check.py` and `consistency_gates.py` pass.

## Not covered

- Restore: one workflow at a time; no bulk restore.
- Callback limit: per pod, in memory; no per-workspace or per-IP limit; no live multi-pod check.
- Per-item: the proposed person is matched by email only (no people picker on the request form); no quorum for the S51 several-approvers step; one-at-a-time order is the list order only; no Edge/axe journey was run for the new Studio controls or the Archived workflows dialog (the dialog was only checked by hand in mock mode).
