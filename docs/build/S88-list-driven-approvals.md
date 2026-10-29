# S88 - List-driven approvals (CMP02, gap G2, bounded)

Gap G2 of the SnapFlow comparison asked for a human fan-out driven by a list in the request: one approval per item (for example per line item or per branch office), then a merge. S51 gave approvals a fixed number of 2 to 5 approvers and explicitly excluded collection-driven instances. This slice adds them, on the same machinery.

## Model

An approval gains `config.perItem`:

```json
"perItem": { "itemsRef": "/input/lines", "itemLabel": "description", "allowSamePerson": true }
```

- **The list** (`itemsRef`) is a request field of type list: a list of rows or a list of single values. It must be **required and unconditional** (no `visibleWhen`/`requiredWhen`, not in a conditional section) and its `maxItems` must be **20 or fewer** (`Node.PerItem.MAX_ITEMS`), a compile-time bound.
- **Each task shows its item.** For a list of rows `itemLabel` names the column shown (text, number or date; not yes/no or attachment); for a list of single values the value itself is shown and `itemLabel` is refused. The task label is `<step label>: <item> (item i of n)` (an empty value shows `Item i`; long values are cut at 120 characters). That label is what the task list, task detail, run detail and run path show, so no worklist code changed.
- **Completion rule** (as S51): every item must be approved; the **first rejection** rejects the step at once and cancels the open item tasks (status `cancelled`, `TASK_DECIDED` carries `otherApproversCancelled`, `itemIndex`, `itemLabel`).
- **Typed outputs**, in addition to `decision`, `reason`, `comment`, `decidedBy`, `decidedAt` (last decider, as S51):
  - `approvedCount`: integer, 0 to the list's `maxItems`;
  - `itemDecisions`: list of text, one per item in list order, each `approved`, `rejected` or `cancelled`.
  Both exist only on a list-driven approval (`WF_FIELDREF_UNKNOWN` otherwise) and follow the compiler's type rules (for example `itemDecisions` maps to a list field of text, not to a single text field). They are written once no item is open, i.e. when the step completes.
- **Same person for several items.** Default: **one person, one item** - the step's own id is added to the task's `separate_from` list exactly like S51, so the shipped separation-of-duties machinery (claimable queue, claim/decide/assign/dispatch refusal `SEPARATION_OF_DUTIES`, allowed-actions reason, the V50 `distinct_actor` unique index) applies unchanged. The group therefore needs at least as many eligible members as the list has items. `allowSamePerson: true` (the only allowed value; leave it out for the default) lets one person decide several items; then the step id is not added and nothing else changes.
- **Empty list: never reaches the step, deterministically.** Every list field has `minItems >= 1` in the schema, and the list must be required and unconditional, so request submission refuses an empty, missing or too long list (`VALIDATION_FAILED`, tested). If stored data were still empty or over 20 items, `orqixRoute.itemPositions` fails the step (`WF_PER_ITEM_LIST`, the request needs attention) rather than approving nothing or opening unbounded tasks. The step never auto-approves.
- **Separation of duties with other steps and permissions** are unchanged: `separateFrom`, candidate group, required skills, self-approval policy, `task.act` all apply per item task.

## Validation (compiler)

| Code | When |
| --- | --- |
| `WF_PER_ITEM_LIST` | `itemsRef` is not a list request field, or the list is optional or conditional |
| `WF_PER_ITEM_BOUND` | the list may hold more than 20 items |
| `WF_PER_ITEM_LABEL` | a list of rows without a usable label column, or a list of single values with one |
| `WF_PER_ITEM_UNSUPPORTED` | combined with a number of approvers (`multiInstance`), a time limit, reminders/escalation or correctable fields |

The schema refuses unknown members and `allowSamePerson: false`. A tier-1 workflow refuses `perItem` (`WF_TIER1_CONSTRUCT`), like `multiInstance`.

## BPMN, runtime and projections

- **BPMN**: a parallel multi-instance user task over a collection: `flowable:collection="${orqixRoute.itemPositions(execution, '<id>')}"` (item positions 0..n-1), `flowable:elementVariable="orqix_item"`, the S51 completion condition, and an `orqix:perItem` extension carrying `itemsRef`, `itemLabel` and (only when true) `allowSamePerson`. No new expression bean (the method is on `orqixRoute`). Workflows without `perItem` generate exactly the BPMN they generated before; no golden file changed.
- **BPMN source (UI03)**: read back exactly from `orqix:perItem`; the generated collection, element variable and completion condition are compared after regeneration, so editing them is refused (tested both ways).
- **Runtime projection**: V135 adds `task_context.item_index`, `item_label` and `item_scope` (the engine execution holding all item tasks of one visit, so a step on a loop keeps visits apart) with bounds checks and one task per item and visit. Each item task gets its own current-step entry (`itemIndex`, `itemLabel`) and a `TASK_CREATED` event with `itemIndex`, `itemLabel`, `itemCount`.
- **Run path / diagram**: one visit per item; `PathVisit` gains optional `itemIndex` and `itemLabel` (left out for every other visit). A decision closes the visit of its own item; a rejection that cancelled other tasks closes their visits as `cancelled` (this also fixes the S51 several-approver case, whose other visits previously stayed open until the request ended).
- **Release move**: a request waiting at a list-driven approval cannot be moved to another release (`PER_ITEM` finding, added to the `MigrationFinding` code enum).
- **Simulation**: the step opens one task per item of the test request's list; `approvals.<id>.rejectedItem` (1-based, default 1) picks the rejected item: earlier items approved, later ones cancelled; an empty test list stops with `TEST_LIST_EMPTY`.
- **Studio**: the approval inspector gains *One approval per list item* (choose a request list), *Item label* (columns that can name an item) and *One person may decide several items*. Choosing a list removes a number of approvers and vice versa. The field picker offers `approvedCount` and `itemDecisions` for such a step.
- **Mock (offline) mode**: `mocks/validate.ts` reports the four codes; the mock simulator writes `approvedCount` and `itemDecisions` (first item rejected on a rejection).

## Evidence (run locally on 25 October 2026, JDK 21, PostgreSQL via Testcontainers, Flowable)

- Compiler `PerItemApprovalTest` (7 cases): BPMN (collection, element variable, completion condition, extension, no `loopCardinality`, an ordinary workflow has no multi-instance); list bound/kind/optional/conditional blockers; label column rules for rows and single values; combination blockers and `allowSamePerson: false`; typed outputs (integer mapping accepted, list-to-text refused, unknown on an ordinary approval); simulator approve and reject-item-2 wording and outputs; BPMN source exact round trip for rows and single values, and refusal of an edited element variable.
- Application `ListDrivenApprovalTest` (5 cases, public API, real engine): two line items open two tasks labelled `Finance review: Laptop (item 1 of 2)` / `... Docking station (item 2 of 2)`, two current steps; the person who decided item 1 cannot claim or see item 2 (403 `SEPARATION_OF_DUTIES`); a finance user outside the group cannot claim or see it; after both approve the request result holds `approvedItems = 2` and `itemDecisions = [approved, approved]`; the run path has one visit per item with its label and the manager visit has no item fields. Rejecting item 2 of 3 ends the request as rejected with `[cancelled, rejected, cancelled]`, `approvedCount 0`, two cancelled tasks, no engine job left, a late decision refused and the run path showing `Laptop=cancelled, Monitor=rejected, Chair=cancelled`. With `allowSamePerson` one manager decides all three items. An empty, missing or six-item list is refused at submission. The release-move check reports `PER_ITEM`.
- Frontend `ListApprovalFields.test.tsx` (3 cases): list/label/same-person editing and clearing, list and approver count replace each other, field picker outputs and offline validation codes. S51 tests (`InclusiveAndMultiInstanceTest` compiler 7, application 5, frontend 4) and the run path tests still pass.

Full clean `verify` on this branch (`mvnw -o clean verify`, 25 October 2026) passes: compiler 531 cases with the unchanged coverage gate met; application 902 cases (898 pass, the same four intentional skips); packaging. Frontend: 136 files, 970 tests pass (`vitest run`), plus typecheck, lint of the changed files and `api:check` (regenerated `schema.d.ts` matches the contract).

## Not covered

- Only approvals, only in parallel, only "all must approve / first rejection ends it" (no quorum, no sequential mode, no per-item candidate group or assignee taken from the item, no fan-out of other step kinds; child workflows keep their own S12 `forEach`). **Update:** [S104](S104-restore-callback-limits-per-item-quorum.md) adds a quorum, one item at a time and a proposed approver per item.
- The item is shown through the task label (and run detail/path); the task form does not render the item's other columns separately.
- Dispatch (S39) is allowed; with one person per item a dispatcher may still assign two items to the same person, who can decide only one and must return the other (the same limitation as S51).
- No browser (Edge/axe) journey was run for the new inspector fields.
