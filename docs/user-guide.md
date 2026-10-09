# Orqix user guide (R0)

Orqix runs everyday business approvals — such as purchase requests — and shows everyone where each request stands. This guide covers the first release (R0). Screens are described as they work in R0; anything marked *later* is planned but not yet available.

## Signing in and finding your way

Open Orqix in your browser and sign in with your company account. You land on **Home** in your current workspace. A workspace is a team area (for example *Purchasing*); use the switcher at the top to change workspace if you belong to more than one.

The left menu shows only what your access allows:

| Menu | Who sees it | What it is for |
|---|---|---|
| **Home** | Everyone | Start a request, see work waiting for you and your recent requests |
| **My requests** | Everyone | Track requests you submitted and filter by status |
| **My tasks** | Approvers | Approvals assigned to you or available for your group |
| **Workflows** | Designers, publishers, and anyone who can start requests | Request types, their versions, and the workflow studio |
| **Runs** | Operators and viewers | Every request in the workspace, including those needing attention |
| **Settings** | Workspace and integration administrators | Members, groups and connections |

Home includes shortcuts to track your requests. Approvers also see assigned and available task counts; select a count to open that task list. Use **Load more workflows** to see additional request types.

A yellow banner reading "Test workspace" means requests there use test connections only.

## Requesting something

1. On **Home**, choose a request type under *Start a request* and select **Start**.
2. Fill in the form. Required fields are marked "required"; help text under each field explains what to enter. Amounts are in US dollars with up to two decimals.
3. Select **Review**, check the summary, then **Submit**.
4. The confirmation shows your request ID, who has it now (for example "Waiting for Managers"), and a link to follow it.

If the form changed while you were filling it in, Orqix asks you to review the new version; your entries are kept wherever the fields still exist.

**Following your requests.** **Home → Your recent requests** or **My requests** lists everything you started. Open one to see:

- its status — *Waiting*, *Running*, *Needs attention*, *Completed · Fulfilled*, *Completed · Rejected* or *Cancelled*;
- the current step and who owns it, with a due date where one is set (a due date is a label; Orqix does not send reminders in R0);
- the decisions so far — who decided, when, and why;
- a timeline of what happened, which refreshes by itself.

You also get an in-app notification (bell icon) when your request is fulfilled, rejected, delayed or cancelled. Email and Teams notifications come *later*.

## Approving

**My tasks** has three tabs: *Assigned to me*, *Available to claim* (tasks for a group you belong to), and *Completed*.

1. Select a task to open it beside the list. You see the request details, the decision history, and why the task is offered to you.
2. If the task belongs to your group, select **Claim task** first. This tells colleagues you are handling it; use **Release** to hand it back.
3. Write a comment if you like, then select **Approve** — or **Reject**. Rejecting requires a reason so the requester knows what to change.

You never see your own requests in your approval list, and you cannot approve them. If a colleague decides the same task first, Orqix shows "This task was completed by …" and nothing is recorded twice.

## Designing a workflow (designers)

To import an existing process, choose **Workflows → Import workflow**. Camunda BPMN imports require explicit assignment and connector mappings. Pega archives produce an inventory and a template for reviewed manual mappings. Resolve blockers, review assumptions and convert to a draft; publication remains a separate step. See [migration workbench](17-migration-workbench.md) for supported formats and limits.

1. **Workflows → New workflow**, then pick a template such as *Purchase approval*, or start from scratch.
2. The **studio** opens with the **Canvas** tab: steps connected by arrows. Add steps from the palette — *Ask for approval*, *Make a decision*, *Choose a path*, *Call a system*, *Notify someone*, *Finish*. Select a step to edit it in the panel on the right; the basic settings come first, and *Advanced* holds the rest.
3. The **Steps** tab offers the same editing as a list that works fully with the keyboard.
4. Changes save automatically ("Saved just now" at the top). Undo and redo with Ctrl+Z and Ctrl+Shift+Z (Cmd on Mac). If a colleague saved a newer version, Orqix stops saving and lets you reload or download your version.
5. **Check workflow** lists anything that must be fixed before publishing, grouped by step; **Go to step** takes you there. Warnings explain consequences but do not block.
6. On a *Make a decision* step, **Test this rule** shows which outcome a sample value produces.

Testing a whole workflow means publishing and running it in a test workspace. An in-studio test button comes *later*.

## Connecting another system

Workspace administrators can open **Settings → Connections**, configure the address and sign-in method, enable the connection and save it. Use **Test connection** with a read-only operation to check the saved binding. A new system first needs an approved operation contract registered by the platform team.

In the designer, select a **Call a system** step, choose a connection and operation, map **What to send**, and select **Result fields to keep**. The designer uses the workflow's pinned connection version. If that version is unavailable, restore it before editing mappings. Renaming a kept result updates its downstream references; one Undo restores both the name and references.

## Publishing and versions (publishers)

From the studio select **Publish**. The review page shows what changed compared with the version in use, the groups and connections it depends on, and any warnings to acknowledge. Select **Publish version N**.

Requests already in progress always finish on the version they started with. To go back, open the workflow overview and choose **Make active** on an earlier version: new requests use it, and existing requests are unchanged.

## Handling problems (operators)

**Runs** with the *Needs attention* filter lists requests that stopped because an external system failed. Open one to see a plain explanation, for example "Procurement service did not respond. Attempt 3 of 3", and the technical details.

| Situation | What to do |
|---|---|
| The system was down and is back | **Retry** |
| The system *may* have done the work ("outcome unknown") | Check in that system first, then **Reconcile**: confirm it was done (enter the reference, such as the order number) or confirm it was not, then **Retry** |
| The request must stop | **Cancel** — work already done in other systems is listed and is not undone automatically |
| An approver is unavailable | **Reassign** the task to another eligible person, or back to the group |

Every operator action asks for a reason and is recorded. Operators cannot change an approval decision.

## Finding requests and workflows

**Runs** supports status, workflow and request-reference filters. Enter an exact request reference (such as ORQ-1003) or business key and select **Search**; **Clear filters** returns to all runs. If the workflow menu has more choices, select **Load more workflow choices**.

**My requests** filters by status. **Workflows** supports searching by name, with **Clear search** to restore the full list. These filters are saved in the page URL, so refreshing, sharing a link or using browser Back keeps the selected filters. Access to the results still depends on the signed-in user.

## Words used in Orqix

| Term | Meaning |
|---|---|
| Request / run | One submitted case moving through a workflow |
| Workflow | The steps a request type follows |
| Version (release) | A published, unchangeable copy of a workflow |
| Draft | The editable copy in the studio |
| Task | A step waiting for a person |
| Needs attention | Stopped until an operator acts |
| Fulfilled / Rejected | The business outcome of a completed request |
