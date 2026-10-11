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

Open queues sort by priority, then oldest creation time. Use **Sort tasks** or **Priority filter** to change the view. **Open for** shows elapsed age; it does not mean an SLA has been breached. After claiming a task, choose **Change priority**, enter a number from 0 to 100 and a reason, and explicitly confirm. Eligible current assignees and operators can change priority; candidates must claim first. The reason is retained in request history. A concurrent change requires reviewing the refreshed task before trying again.

**My tasks** has three tabs: *Assigned to me*, *Available to claim* (tasks for a group you belong to), and *Completed*.

1. Select a task to open it beside the list. You see the request details, the decision history, and why the task is offered to you.
2. If the task belongs to your group, select **Claim task** first. This tells colleagues you are handling it; use **Release** to hand it back.
3. Write a comment if you like, then select **Approve** — or **Reject**. Rejecting requires a reason so the requester knows what to change.

You never see your own requests in your approval list, and you cannot approve them. If a colleague decides the same task first, Orqix shows "This task was completed by …" and nothing is recorded twice.

## Designing a workflow (designers)

Select a step to add **Step notes** explaining its purpose or review decisions. Notes save with the draft and each published version. They are plain-text annotations; they do not change what the step executes.

To import an existing process, choose **Workflows → Import workflow**. Camunda BPMN imports require explicit assignment and connector mappings. Pega archives produce an inventory and a template for reviewed manual mappings. Resolve blockers, review assumptions and convert to a draft; publication remains a separate step. See [migration workbench](17-migration-workbench.md) for supported formats and limits.

**Import a multi-file project** accepts BPMN and DMN together, up to ten files. Each DMN file must contain one convertible decision. XML namespaces identify the format, including `.xml` files. The project lists missing or duplicate process/decision references and orders dependencies before callers. Review the suggested **Decision mappings (JSON)** before converting. A DMN table is copied from its source migration into the caller; publishing its standalone workflow is optional. Called child workflows must still be published before their callers.

On a request's timeline, expand **Decision evidence** to see the input, evaluated candidate rules, selected rule and default status from its pinned workflow version. The requester, eligible participants and readers with request-data permission can see values. Metadata-only viewers receive a restricted-evidence message.

1. **Workflows → New workflow**, then pick a template such as *Purchase approval*, or start from scratch.
2. The **studio** opens with the **Canvas** tab: steps connected by arrows. Add steps from the palette — *Ask for approval*, *Make a decision*, *Choose a path*, *Call a system*, *Notify someone*, *Finish*. Select a step to edit it in the panel on the right; the basic settings come first, and *Advanced* holds the rest.
3. The **Steps** tab offers the same editing as a list that works fully with the keyboard.
4. Changes save automatically ("Saved just now" at the top). Undo and redo with Ctrl+Z and Ctrl+Shift+Z (Cmd on Mac). If a colleague saved a newer version, Orqix stops saving and lets you reload or download your version.
5. **Check workflow** lists anything that must be fixed before publishing, grouped by step; **Go to step** takes you there. Warnings explain consequences but do not block.
6. On a *Make a decision* step, **Test this rule** shows which outcome a sample value produces.

Testing a whole workflow means publishing and running it in a test workspace. An in-studio test button comes *later*.

## Background drafts and workflow patterns

**Workflows → New workflow → Describe it** creates a draft in the background when AI is enabled. You can close the dialog and keep working; the workspace notice offers **Open draft** when it is ready. Review every step and the AI's assumptions before publishing. If generation fails, the notice explains the reason and you can submit again.

The studio palette also includes **Run in parallel**, **Wait for all**, **Wait** and **Start another workflow**. Connect every parallel branch to its matching **Wait for all** step. A rejection ends the request and cancels other open branches. **Wait** has two forms: **For a duration** (`PT30M`, `P1D`) and **Until a date**, which waits until the date the requester entered in a **Date** question (for example "Start date") at a time of day you choose, 09:00 by default. Times are always UTC; the inspector shows "Waits until 14 Oct 2026, 09:00 UTC." for a date you try. If the date has already passed the request continues at once; a date more than a year (366 days) ahead is refused on the form. The date question must be required.

An approval's **Time limit** cancels the task and follows its **Timed out** route; connect that route to another approval to escalate. **Due after** displays a due date. **Start another workflow** uses a published workflow in the same workspace, maps its request and result values, and waits for completion. Publishing pins the child version. Cancelling a parent cancels its active child requests. Bounded repeated children support rework with immutable prior decisions. Bounded scalar collections can run child workflows sequentially or in parallel and retain ordered collected outputs. Mutable correction forms and object/nested collections remain future features; see [iteration](build/S14-bounded-iteration.md).

## Connecting another system

Workflows can reference approved SharePoint/OneDrive files through the registered Microsoft document operations. Use stable drive/item identifiers and an explicit historical version or retained snapshot. An integration administrator can load and select historical versions in the connection test panel. The workflow reads the configured pin and fails if unavailable; it does not silently substitute current content. See [Microsoft documents](microsoft-documents.md) for supported text/PDF/Office formats and limits.

In a document **Call a system** step, choose **Configure document reference** to enter an approved drive, known item and exact historical version or retained snapshot. **Apply approved reference to request form** creates required single-choice questions and maps them into the step. Existing conflicting mappings must be cleared explicitly. Connection administrators can load historical versions or explicitly capture a current snapshot in this helper. This configures a fixed approved reference for the published workflow; requesters choose those exact values in the normal form. See [document authoring](build/S22-document-authoring.md).

Workspace administrators can open **Settings → Connections**, configure the address and sign-in method, enable the connection and save it. Use **Test connection** with a read-only operation to check the saved binding. A new system first needs an approved operation contract registered by the platform team.

In the designer, select a **Call a system** step, choose a connection and operation, map **What to send**, and select **Result fields to keep**. The designer uses the workflow's pinned connection version. If that version is unavailable, restore it before editing mappings. Renaming a kept result updates its downstream references; one Undo restores both the name and references.

## Publishing and versions (publishers)

On a workflow overview with at least two published releases, choose **Compare versions** and select the two versions to review. **Load more** adds older versions to the choices. The comparison includes step notes, rules, routes, forms and settings; canvas positions are omitted. Fetch failures are shown explicitly.

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

## Request collections and comments

Collection questions let requesters add or remove bounded scalar items. The form validates each item's type and constraints plus the configured count; the studio supports collection questions with text, number, whole-number or Yes/No items (1 to 25). A **Date** question shows a date picker and stores a calendar date without a time zone; you can set an earliest and a latest date. Published child for-each steps process these values sequentially or in parallel according to their pinned contract; collected result lists retain input order.

People allowed to see request data can read permanent comments on the request page. The initiator, task participants and operators can add comments. Comments are plain text, limited to 2,000 characters, retain their author/time, and cannot be edited or deleted through the application. Summary-only visibility does not expose comments. A failed save retains your text and retries use the same idempotency key.

## Using the Assistant

The **Assistant** (in the left menu) is a guided way to do three things: start a request, decide a task that is waiting for you, and check on a request. It asks one question at a time, shows you the details, and does nothing until you confirm. It uses the same permissions as the normal screens, so you can only start, approve or reject what you could already start, approve or reject there.

**Start a request.** Choose **Start a request**, then the request type (for example Purchase approval). Answer each question in the answer box and press Enter. Where the answer is a choice (such as a cost center) or Yes/No, buttons appear as shortcuts. For a list, separate the items with commas, for example `Laptop, Monitor`. Numbers can include thousands separators (1,250.50). Date questions ask for year-month-day, for example 2026-10-14, and the review shows them as, for example, 14 October 2026. Optional questions have a **Skip this question** button. If an answer is not valid, the same question stays open with a hint. You can also describe what you need in your own words, for example "I need a laptop"; if your organization has set up an AI service, your message and the names of the request types you can use are sent to it only to find the right request type. You always review before anything is submitted.

**Review and submit.** The review lists your answers with their question names. Use **Change** beside a value to correct it, then choose **Submit request**. Nothing is submitted until you do. **Discard this draft** clears it without submitting. If you press Submit twice or your connection drops, the request is still created only once.

**Decide a task.** Choose **My tasks**, then a task. The Assistant first shows you the request details. Choose **Approve**, or **Reject** and say why (a reason is required). You are then asked to confirm; only **Confirm approval** or **Confirm rejection** records the decision. **Go back** or **Leave this task alone** records nothing. If someone else decides the task first, you are told and nothing is recorded for you.

**Check a request.** Choose **My requests**, then a request, for its current status, or open it for details.

Your last conversation is remembered for this browser session, so reloading the page continues where you were; **New conversation** starts over. A conversation that has expired or was changed in another tab shows a message with a button to reload or start again. Keyboard: after each answer focus moves to the next place you act, Enter sends, Shift+Enter adds a line, and new replies are announced to screen readers.

## Document references and repeated work

A workflow can read an approved SharePoint or OneDrive document through a configured Microsoft document connection. Supply the drive, item and version identifiers requested by the form. Approved operations read pinned UTF-8 text and metadata, extract bounded PDF/DOCX/XLSX/PPTX text, or retain current content as an immutable snapshot before extraction. A snapshot ID and hash identify the captured bytes. Scanned PDFs require a separate OCR capability; encrypted or unsupported files fail visibly. Document browsing in a picker remains planned. An administrator must configure tenant access first.

A child-workflow step can repeat up to its configured bound, stopping on the selected outcome, or process a bounded list of scalar values sequentially or in parallel. Each child has its own request and history. The parent can route on the final result or whether all children succeeded, and collect selected child outputs into lists in input order. Runnable simple, medium and complex definitions and setup instructions are in [S14](build/S14-bounded-iteration.md).

## Delegating an assigned approval

Open a task assigned to you and select **Delegate to a colleague**. Choose an eligible named colleague, enter a reason and select **Confirm delegation**. The colleague becomes responsible for the decision, and the reason is saved in request history. If ownership changed while the page was open, reload before trying again. Administrators can still use operator reassignment for other cases.

Assigned open tasks receive one in-app reminder after the configured delay (24 hours by default). Eligibility and current assignment are checked again before delivery. These reminders do not send email or broadcast to an unassigned group.
