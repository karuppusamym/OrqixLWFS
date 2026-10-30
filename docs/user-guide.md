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

To import an existing process, choose **Workflows → Import workflow**. Camunda BPMN imports require explicit assignment and connector mappings. A Camunda 8 file is shown with its source version; its REST connector calls are mapped to a registered operation (the original method and path are shown for comparison, never copied), FEEL conditions of the supported subset convert (comparisons, a literal on the left, `between`, number ranges, `in` lists of up to 8 literals, `not(...)`, and one `and`/`or` group; mixed `and`/`or`, functions, arithmetic and anything else stay blocked with the original text quoted, see [S46](build/S46-bounded-feel-expressions.md)), a due time or boundary timer on a task becomes the approval's due time or time limit, and Camunda forms (the `.form` file, uploaded in the forms step) become the request form or a note on the approval. A message wait on a plain correlation variable converts; other message and signal events, other connectors and complex conditions stay blocked with a plain-language reason. Pega archives produce an inventory and a template for reviewed manual mappings. Resolve blockers, review assumptions and convert to a draft; publication remains a separate step. See [migration workbench](17-migration-workbench.md) for supported formats and limits.

**Import a multi-file project** accepts BPMN and DMN together, up to ten files. Each DMN file must contain one convertible decision. XML namespaces identify the format, including `.xml` files. The project lists missing or duplicate process/decision references and orders dependencies before callers. Review the suggested **Decision mappings (JSON)** before converting. A DMN table is copied from its source migration into the caller; publishing its standalone workflow is optional. Called child workflows must still be published before their callers.

On a request's timeline, expand **Decision evidence** to see the input, evaluated candidate rules, selected rule and default status from its pinned workflow version. The requester, eligible participants and readers with request-data permission can see values. Metadata-only viewers receive a restricted-evidence message.

1. **Workflows → New workflow** asks how you want to start. Every choice gives you an ordinary draft; nothing runs until you publish.
   - **Start from a template**: pick one such as *Purchase approval*, name your copy and adjust it.
   - **Answer a few questions**: three short steps (a name, what the requester fills in, who approves, optionally only above an amount). No AI is used; deadlines, emails and rules are added in the studio afterwards.
   - **Describe it to AI** (only when AI is turned on): write what should happen and review the draft AI produces.
   - **Start empty**: a request form and a finish step to build on yourself.

   AI is never required. Start any way you like and open **Ask AI** in the studio only when you want help. **Use the governed questionnaire** at the bottom of the dialog asks for working-hour deadlines, stage emails and the Tier-1 sign-off up front.
2. The **studio** opens with the **Canvas** tab: steps connected by arrows. Add steps from the palette — *Ask for approval*, *Make a decision*, *Choose a path*, *Call a system*, *Notify someone*, *Finish*. Select a step to edit it in the panel on the right; the basic settings come first, and *Advanced* holds the rest.
3. The **Steps** tab offers the same editing as a list that works fully with the keyboard.
4. Changes save automatically ("Saved just now" at the top). Undo and redo with Ctrl+Z and Ctrl+Shift+Z (Cmd on Mac). If a colleague saved a newer version, Orqix stops saving and lets you reload or download your version.
5. **Check workflow** lists anything that must be fixed before publishing, grouped by step; **Go to step** takes you there. Warnings explain consequences but do not block.
6. On a *Make a decision* step, **Test this rule** shows which outcome a sample value produces.

7. **Test workflow** tries the whole workflow before you publish it. Answer the sample form, choose what should happen at each step that has a choice (for example "Finance rejects" or "The procurement system does not answer"), and press **Run test**. You get an outcome and a step-by-step playback: each step says in plain words what happened and why ("Routed to Finance because Amount (USD 5,000) is at least USD 5,000"), and the canvas shows the path. Use **Previous step** and **Next step** (or the arrow keys), or read the list of all steps. The panel also says how many routes this run tested ("3 of 6 routes tested") and which ones you have not tried yet; **Try another case** lets you change the answers and run again. Keep up to ten named cases per workflow in your browser, set what each should end with, and use **Run all saved cases** to see which still pass; **Export** and **Import** share them with a colleague. No request is started and no external call or notification is sent. Test cases are saved in your browser; the server records only audit counts for a test. **Share and test for publication** copies your cases (each with an expected outcome) to the workflow's shared suite and runs it on the saved draft; once a shared suite exists, publishing requires it to pass.

**Why a test is not a proof.** The test follows your workflow exactly as designed, with the choices you make. It does not call the other systems, so it cannot tell you that a connection works; it does not know who is in a group; and it skips all waiting, so it shows nothing about timing. A request can still need attention in real life for reasons the test cannot see. Treat a passing test as "the design does what I meant", and check connections and groups with **Check workflow** and the connection tests.

## Background drafts and workflow patterns

**Workflows → New workflow → Describe it to AI** creates a draft in the background when AI is enabled. You can close the dialog and keep working; the workspace notice offers **Open draft** when it is ready. Review every step and the AI's assumptions before publishing. If generation fails, the notice explains the reason and you can submit again.

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
| A request shows fewer open steps than it should, or looks stuck | **Check consistency** on the request, see [Checking a request against the engine](#checking-a-request-against-the-engine-operators) |
| A request waits for a signal that nothing will send | Send it through the API, see [Sections, handlers, errors, signals and undo](#sections-handlers-errors-signals-and-undo--19-october-2026) |
| An AI agent send has an unknown outcome | **Agent writes** page, **Record outcome**, see [Agent write tools](#agent-write-tools-operators-and-approvers) |

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

## Following requests with the diagram and Insights

On a request, switch between **Timeline** and **Diagram**. The diagram uses the version that started the request. Current steps, decisions and routes are shown in words and in the step list below it. If a request has more history than the diagram supports, use the timeline; current steps remain visible. Operators and workspace readers can open **Insights** for throughput, cycle times, bottlenecks, overdue items and incidents, with period/workflow filters and a CSV export. Individual request links retain their normal access checks.

## Request files and case stages

A **File** question lets you choose a file before starting the request. Its allowed types and size are shown below the question; wait for upload and scan status before reviewing. Files marked **Not scanned** have not had a malware check. On the request's **Files** panel, authorized participants can add, download or remove files within the displayed limits. Removed files retain history, and readers without request-data access cannot read files.

Designers can select the request-form step in Studio and open **Case stages** to name stages, assign steps and set optional time limits. The request and task pages show progress and deadlines. An overdue stage records an in-app notice; it does not automatically reroute or cancel the request. Put any escalation route explicitly into the workflow.

File questions currently use the normal request form. The Assistant does not offer a file upload control; start workflows requiring files from their form. Approved SharePoint/OneDrive reference choices remain usable as ordinary guided text/choice questions.

## Reusable request views and case termination — 18 October 2026

In Studio, select Start, then **Choose reusable view**. Choose a published request view and an explicit version, then **Apply selected version**. Every question must have a matching binding, type and required flag. The selected layout and optional submit label are copied into the draft. Save and publish the workflow normally. A later library edit does not update an existing pin. **Detach view and keep form** keeps the copied layout editable; normal undo is available. Publication blocks incompatible edits while the pin remains. This supports request forms; approval and case/worklist runtime views remain future work.

On case detail, operators can choose **Terminate case**, enter a reason and confirm. This ends open case tasks and records the case as terminated. A stale version is rejected and refreshed. Termination is distinct from successfully completing the case; ordinary case responses do not expose the reason. Full CMMN lifecycle support remains open.

## Case plan history - 18 October 2026

Choose **Show case plan** on case detail to inspect stages, plan items, their current or ended states, parent-stage names and achieved milestones. The plan refreshes after a task decision or case termination. Case starters and authorized readers retain access after completion; task-only participants need current eligibility, as for case detail. Completing a task may activate a later stage or milestone; a modeled exit sentry can cancel alternate tasks or a whole nested stage. These actions use the pinned case model. They do not add discretionary planning or a full case designer. [S66](build/S66-case-stages-async-callbacks.md) records the supported source subset.

## Folders, owners and shared assets — 19 October 2026

**Folders and owners** ([S48](build/S48-workflow-folders-owners.md)). Workflow folders are labels, not permissions: they never grant, narrow or inherit access. Anyone who can read workflows sees the **Folder** and **Owner** filters on **Workflows** (they combine with search and are kept in the page address; **Clear filters** resets them). Designers (`workflow.edit`) also get **Manage folders** (create, rename, delete; up to 50 flat folders per workspace, names unique ignoring case) and **Organize** on each row to choose a folder and an owner. Deleting a folder returns its workflows to unfiled and never deletes a workflow. A new owner must be an enabled person who can edit workflows in that workspace right now; the owner gains no rights. If someone else changed the folder or owner first, you are told to reload and nothing changes. Start-only requesters do not see folders. Not included: nested folders, folder permissions, tags, bulk moves.

**Shared assets** ([S61](build/S61-shared-assets.md)). Decision tables, document templates, connections and forms ([S127](build/S127-shared-forms.md)) can be shared across workflows in one workspace. Each asset has numbered, unchangeable versions, an owner and an archived flag. **Settings → Shared assets** searches by name, key or owner, shows where each asset is used and lets managers change the owner or archive and restore it. Reading needs `workflow.read` (connections: `connection.read`); managing needs `workflow.edit` (connections: `connection.manage`, which no role carries and which must be granted). Shared forms are described in [Shared forms](#shared-forms-designers---30-october-2026) below.

- In a *Make a decision* step, **Shared decision** copies the latest shared table and links it (**Use a shared decision**), shares the current table as version 1 (**Share this table**), or for a linked table offers **Use version N**, **Save as shared version N+1** and **Unlink**. In a *Generate a document* step, **Start from a saved template** copies the latest template version (see [S52](build/S52-document-lifecycle.md)) and links the step.
- A link pins a version and a copy. It is never live: a published or running workflow keeps what it was published with. Check workflow and **Publish** refuse a link whose asset is missing in this workspace, archived or edited away from the pinned copy (`WF_ASSET_MISSING`, `WF_ASSET_ARCHIVED`, `WF_ASSET_DIVERGED`) and warn when a newer version exists (`WF_ASSET_STALE`, which needs the normal warning acknowledgement and keeps the pinned version). Assets cannot be hard-deleted, only archived. Shared UI components and cross-workspace libraries are not built.

**Document templates** ([S52](build/S52-document-lifecycle.md)). **Settings → Document templates** keeps up to 200 named plain-text templates (PDF or Word format, up to 16,000 characters, `{{field}}` placeholders as in *Generate a document*). Designers add versions (each sent against the latest version they saw, so a stale edit is rejected), archive and restore; readers see every version. Requesters and approvers have no access to the library. It is a library, not a live reference.

**Decision tables in a spreadsheet** ([S89](build/S89-decision-csv-io.md)). In a *Make a decision* step, **Spreadsheet (CSV)** has **Export CSV**, **Download template** (the table's columns with one example row) and **Import CSV**. Keep the rules in Excel or Google Sheets and save as *CSV UTF-8 (comma delimited)*. The first line names the policy and the columns (`in:number:/input/amount`, `out:text:route`), each following line is a rule (top to bottom is the order), and the last line starts with `default`. A condition cell is a comparison word and a value, such as `gte 5000` or `eq IT` (words: `eq ne gte gt lt lte`); an empty cell means the field is not tested. Import replaces the rows, the policy and the Otherwise result of that table as a new draft revision; it never publishes, and you can undo it. If anything is unclear the file is refused with every problem listed as "Line 3, column 4: ..." and nothing changes; numbers must be plain (`1000`, not `1,000` or `$1000`). The same checks as Publish apply, for example overlapping rows under "Exactly one". Cells that start with `=`, `+`, `-` or `@` are exported with a leading apostrophe so a spreadsheet never runs them; leave the apostrophe in place. Rows with "Any comparison (OR)" cannot be exported; split them into one row per alternative under "The first matching row". Importing needs permission to edit the workflow; exporting needs permission to read it. See `examples/decision-csv/purchase-routing.csv`.

## Studio source views and discussion — 19 October 2026

In Studio, next to **Canvas** and **Steps**, three views are derived from the same draft ([S53](build/S53-lanes-source-discussion.md), [S60](build/S60-bpmn-source.md), [S70](build/S70-bpmn-defaults-and-examples.md), [S77](build/S77-bpmn-sections-handlers-and-new-steps.md)):

- **Lanes** (read-only). Steps grouped by who is responsible: the requester, each approval group, external workers and automation, with the handoffs between lanes listed. Selecting a step selects it for the inspector. Lanes are computed, never stored or edited.
- **Source → YAML**. The whole workflow document as YAML. Edit, **Check source**, then **Apply to draft** (one undoable step). Apply is blocked when the text does not parse or exceeds the limits, changes the workflow key, or does not survive a YAML round trip; the check lists what would be added, removed or changed. Server validation and **Check workflow** still run afterwards. People without `workflow.edit` see it read-only.
- **Source → BPMN**. The BPMN the compiler would publish. It carries step names, steps, connections and canvas positions; everything else is listed as "kept from the draft". Editing is offered only when the draft converts to BPMN and back with no difference. **Check BPMN** lists every problem (unsupported elements, foreign attributes, free expressions, a missing generated part, a different value than the draft would write) without saving; **Apply BPMN to draft** saves the result as the next draft revision. Sections, handlers, raise/signal/undo steps, call-failure errors and adapter steps can be edited here. Still read-only: steps using repeat, for-each or collection mapping, approval separation, correctable or dispatch settings and approval notifications, `"subprocesses": []`, and drafts with blockers; the view names the reason. An empty written default (for example `atTime: "09:00"`) counts as the omitted value. BPMN from other tools is imported through the migration workbench, not here.
- **Discussion**. In the inspector, a thread can be started on the selected step or connection, answered one level deep, resolved and reopened. Reading needs `workflow.read`; writing needs `workflow.edit` or `workflow.publish`, so a reviewer can comment without being able to edit. Comments are plain text (1 to 2,000 characters), cannot be edited or deleted, and never change the draft or block publishing.

## Sections, handlers, errors, signals and undo — 19 October 2026

For designers ([S59](build/S59-subprocesses-errors-signals-compensation.md), [S71](build/S71-scope-frames-and-call-failure-errors.md)). In the request form inspector, **Sections and handlers** groups existing steps into up to 10 non-nested scopes.

1. **Section.** One entry step; the section can have a **time limit** (and the step to continue at) and one **error route** (to a step, for one error code or any). A time limit cancels the open work in the section and continues at its target; fields produced inside a section are not guaranteed afterwards.
2. **Handler.** Starts on an *error* (always stops the request), a *signal* of this request, or a *timer* after the request started. A handler that **stops the request** must end at a Finish step; one that **runs alongside** just ends and may not contain Finish steps.
3. **Steps.** *Raise an error* (stops the section; needs a catching error route or handler), *Send a signal* and *Wait for a signal* (signals never leave the request), and *Undo completed calls*.
4. **Undo.** On a call that can change something, set **Undo** to the call it reverses and choose **Run automatically**. Orqix runs the automatic undo calls of the calls that actually succeeded in this request, most recent first, through the same journaled path as any call; a failing undo call needs attention and an operator retry sends only the remaining ones. Undo is request-wide, covers only system calls and is not per section.
5. **When it fails.** A call inside a section with an error route can opt in to raise a named error when it fails for good (a rejection by the system, not a timeout, 5xx, 429 or unknown outcome); the section's error route then runs. Turning it on removes the call's own Failed route.

The canvas draws a frame behind each section and handler. A *Wait for a signal* step can also be released by an operator. Limits: none of this works with parallel steps inside sections, and child workflows, external-worker jobs and message waits are not cancelled cleanly by a section boundary; a request waiting in a workflow that has sections cannot be moved to another release.

## Several approvers and inclusive paths — 19 October 2026

[S51](build/S51-inclusive-and-multi-approver.md). In an approval's inspector, **Number of approvers** (2 to 5) opens that many tasks for the group; the step is approved only when all approve and the first rejection rejects it at once and cancels the others. The tasks must be decided by different people: someone who decided one cannot claim, see or decide another (`SEPARATION_OF_DUTIES`). It cannot be combined with a time limit, reminders or escalation, or approver corrections, and a request waiting at such a step cannot be moved to another release. In a *Run in parallel* step, **Which paths run?** can be set to *Every path whose condition applies*: every path whose condition holds starts, overlapping conditions are allowed, one path is **Otherwise**, and the matching *Wait for all* waits only for the paths that started. Add inclusive paths from the inspector, not by dragging on the canvas. Not built: quorum fractions, sequential or per-person instances.

## Sending through an adapter and received events — 19 October 2026

[S47](build/S47-adapters-inbound-triggers.md), [S73](build/S73-adapter-step-inbound-events.md). A connection manager sets up **adapters** (email, Teams, database read, Kafka) under **Settings → Connections**. **This build delivers only to a local test stub: nothing is emailed, posted, queried or produced.** In Studio, **Send through an adapter** names the adapter and its kind and maps the message values; email recipients and Kafka topics are fixed values typed in the step and must be on the adapter's allowlist, never taken from the request. The step records `outcome` (`stubbed`) and, for a database read, `rowCount` (0). It cannot be placed inside a section or handler and has no Failed route. A refused or unavailable adapter needs operator attention.

Signed **inbound triggers** accept events (for example from the customer's own mail or topic relay) and keep only the mapped values. An event never starts a request by itself: on **My requests**, **Received events** (shown to people who can start workflows) lists them and **Start request** starts the workflow as you, using your normal start rights; an event starts at most one request.

## Promoting, environments and gradual rollout — 19 October 2026

[S54](build/S54-promotion-canary-environments.md), [S74](build/S74-promotion-ui-approval-gate.md). Each workspace has a stage (*development*, *test*, *staging*, *production*), set by workspace access managers. To move a published version to a later stage of the same organization:

1. On the workflow overview choose **Promote** on a release (needs `workflow.export` in this workspace and `workflow.edit` in the target; the target list only shows workspaces you can edit that are at a later stage).
2. **Run compatibility check**. It lists each connection (ready, binding missing, definition missing or differing), approval group, called workflow and compiler finding in the target. **Promote as draft** is offered only when the target's own compiler reports no blockers.
3. Promote saves the release as a **draft** in the target. It never publishes or activates, and never copies bindings, secrets, group members or permissions; the target publishes through its normal gates. If the target already has the workflow, the draft revision you checked must still be current (otherwise check again).

If the production workspace requires approval, **Promote as draft** is replaced by **Request approval**; someone holding `workflow.approve-promotion` in the target (grant-only, workspace-wide, different from the requester) approves or rejects on **Promotions**, with a reason for rejection. The requester can only withdraw. Approval re-checks the target and saves the draft; it does not publish. Not built: Git, signed bundles, other organizations, several releases at once.

**Gradual rollout.** On the workflow overview, publishers (`workflow.publish`) choose another version and a share of 1 to 50 percent of new starts. The two versions must have identical request fields and form layout. Requests stay on the version they started with; activating any other version ends the rollout. Anyone with `run.read` sees per-version counts and average completion time (no automatic evaluation or rollback).

## Request files: quarantine, retention and text extraction — 19 October 2026

[S52](build/S52-document-lifecycle.md). Operators (`run.operate`) can **Quarantine** a request file (reason of 3 to 300 characters): download and text extraction stop, the bytes stay, and the requester can no longer remove it. **Release** restores access but does not clear a virus-scan block. Workspace access managers can set a retention period under **Settings → File retention** (1 to 3,650 days; default none): an hourly sweep deletes the bytes and extracted text of files of finished requests older than that, keeps the metadata, and never touches running requests or quarantined files. Text extraction is a provider contract with a local stub that reads only plain text and CSV; images and PDFs report *unsupported* and no real OCR runs.

## Checking a request against the engine (operators)

[S49](build/S49-run-repair.md), [S72](build/S72-repair-waits-and-scan.md). On a request, **Check consistency** (operators, `run.operate`; hidden while the request is suspended) compares what the request shows with what the workflow engine holds. The dialog title tells you the state:

| Dialog title | Meaning | What to do |
|---|---|---|
| Everything matches | Engine and request agree | Nothing |
| Open steps are missing from this request | The engine still holds approvals, a date or duration wait, a message wait or a worker step the request does not show | Enter a reason and choose **Rebuild steps**. Only what the page shows is rewritten; the engine is not touched, so nothing runs twice |
| A step is stuck and nothing can move it | A token sits at a step but no task, job, timer, subscription, child request or worker job can move it | **Cancel request** only (re-triggering could repeat an external effect) |
| The engine holds no work for this request | No token and no work | **Cancel request** only |
| The workflow engine has no record of this request | Engine instance missing | **Cancel request** only |
| The difference cannot be repaired automatically | Anything else: an unknown timer or subscription, a child, call, document or signal wait, a job in flight, a dead-letter or suspended job, a task without its row, or a request needing attention for another reason | Read the message; resolve the cause (for example retry or reconcile) and check again |
| The request is suspended / has ended | Not checked | Resume first, or nothing to do |

A rebuild is refused (`RUN_NOT_REPAIRABLE` or a version conflict) if the request progressed since you opened the dialog; open it again. If the optional **Consistency scan** is on, **Runs** shows a panel of requests it found drifted (it reports only; it never repairs) and the request shows a warning pointing to **Check consistency**. Operators can also release a request waiting for a signal through the API (`POST /workspaces/{ws}/runs/{runId}/signals`, `run.operate`; there is no button yet); a signal nobody awaits is refused as `SIGNAL_NOT_AWAITED`.

## Agent write tools (operators and approvers)

[S75](build/S75-approved-agent-write-tools.md). An AI agent step can send an email or Teams message only through a write tool that was declared in the connection, with fixed recipients, and **approved** for that workflow by a person holding `agent.approve-write-tool` (grant-only, workspace-wide; revoking is also allowed to connection managers). The page **Agent writes** (shown to approvers and operators) lists declared tools with their fixed targets, lets approvers **Approve for a workflow** (workflow key and reason) and approvers or connection managers **Revoke** (reason). The approver of a tool can never trigger a write through it: a request they started is refused before the model is asked. Delivery goes only to the local test stub in this build. If a write ends with an unknown outcome, the agent stops, the request needs attention and **retry is refused** until an operator checks the target and uses **Record outcome** (delivered or not delivered, what was checked, optional reference) on **Agent writes**; this never resumes the request, so retry it afterwards. The step's ordinary **Reconcile** is not used for agent writes. If an approval is revoked after publishing, the agent step needs attention before the model is called. Message text is never shown or stored.

[S85](build/S85-agent-write-expiry-and-retention.md). When approving, an approver may set **Expires after (days, optional)**, from 1 to 90; empty means the approval has no end date (as before). The page shows the date an approval expires, and an expired approval as **Expired** (with the date) and without **Revoke**. From that moment the approval allows nothing, exactly like a revoked one: publishing the workflow fails, a new request's agent step needs attention before the model is asked (the message says the approval has expired), and every further send is refused. A send that was already checked is checked once more just before it leaves; if the approval was revoked or expired in between, nothing is sent and the step needs attention. To continue, someone allowed to approve write tools approves the tool again (with or without an end date) and an operator retries the request. Workspace administrators set **Settings → Agent write retention**: the number of days (1 to 3,650, or empty to keep them) after which the record of each write of a finished request is removed by an hourly sweep. Records of running requests, and all records of a request with a write whose outcome is unknown or not final, are kept; checking such a write with **Record outcome** makes them eligible.

## Importing a Pega Blueprint (PDF) (designers)

[S91](build/S91-pega-blueprint-pdf.md). In **Workflows → Import workflow**, choose **Pega Blueprint (PDF)** and upload the PDF (at most 25 MiB and 60 pages; encrypted or damaged PDFs are refused). Orqix reads only the page text; scripts, attachments and pictures in the PDF are ignored and never run. It proposes a transcription: case type, stages, roles, fields, steps, decisions, SLAs, outcomes and integrations, each shown next to the text of the page it came from. Nothing is trusted automatically: for each element correct what is wrong, then mark it reviewed, or exclude it with a reason. Fix every finding (for example a step without a role, a decision without outcomes or an SLA without a unit) and **Save transcription**; each save is a new revision and edited elements are recorded as manually resolved. **Ask AI for a proposal** is optional and only shows a suggestion you can load and review; it never changes the saved transcription. Then map each role to a workspace group, acknowledge the exclusions and **Convert to draft**. Only approvals, typed request fields, routes on field comparisons, outcomes and SLAs in minutes, hours or days are converted; manual or automated steps and integrations must be excluded and rebuilt in the studio. The draft still needs an independent review and a publisher. The reader is generic and is not a parser of Pega's own Blueprint export layout.

## The Studio: BPMN notation, the authoring panel, the Test tab and export (designers) - 27 October 2026

Notes: [S94](build/S94-bpmn-cmmn-notation.md), [S95](build/S95-conversational-authoring.md), [S96](build/S96-test-tab-and-integration-mocks.md), [S97](build/S97-bpmn-export.md).

**BPMN shapes.** The canvas and the run diagram draw each step in BPMN 2.0 notation: a diamond with X for **Choose a path**, a diamond with plus for parallel split and join, circles for events (a thin circle for the request form, a thick one for finishing, a clock for waiting), rounded rectangles for approvals, calls and notifications, and a thick-bordered rectangle for a child workflow. **Notation** switches to the earlier card view; the choice is remembered per person. **BPMN symbols** opens a legend. Meaning is never carried by shape alone: every step keeps its name and text. On the **Cases** page a read-only CMMN preview sits under the case designer (stages as rectangles with angled corners, human tasks, milestones, sentries and the manual, required and repeat markers); it is a preview, not a CMMN canvas.

**Studio layout (28 October 2026).** The canvas is in the middle and gets the width. The main menu sits behind the button at the top left while you edit. **View** in the header shows or hides three panes, and Orqix remembers your choice in this browser: the **Step list** (left, collapsed by default; its icons still add a step), **Ask AI** (a conversation docked under the canvas, like a chat box) and **Details and discussion** (two cards on the right that open and close on their own: **Details** for the selected step or the workflow itself, **Discussion** for comments). The header keeps **Test workflow**, **Check workflow** and **Publish** in view; **More** holds **Preview the request form**, **Explain this workflow** and the exports. Settings that rarely change, such as limits and the governance profile, sit under **Advanced** headings in plain words. A nearly empty draft shows four short tips (hide them with the cross). The Studio needs a screen at least 1,024 px wide to edit.

**Ask AI (editors only).** Describe a change, for example "add a finance approval when the amount is over 5,000", and follow up ("make it 10,000"). Each answer arrives as a list of proposed changes with unticked boxes; **Apply N selected** applies them as one edit that **Undo** reverses. Every proposal is checked by the compiler and a change that would add a blocker is not offered. **Ask about "step"** (or **Ask AI about this step** on the Details card) points the next instruction at the selected step. **Talk** dictates into the box using your browser's speech recognition (Chrome and Edge; the button is absent where the browser cannot do it; some browsers send the audio to their vendor); you still press **Send**, so speaking never changes the draft by itself. **Start over with AI** asks twice before it replaces the draft. The assistant can add approvals, choices, notifications, waits and finish steps; other step kinds come from the step list. It needs an AI key: without one the panel says "AI is not configured". The conversation is kept only while the page is open.

**Test tab.** Beside Canvas, Steps, Lanes and Source, visible to everyone who can read the workflow (saving, importing, sharing and editing checks need edit permission). A test is a dry run: nothing is started, called or stored.
- **Mock answers for integrations.** For a call or an agent choose an HTTP status, a JSON body (checked against the declared response schema; **Fill with a sample** writes one), a delay or a timeout. For an external worker, adapter or message wait choose a result, payload, row count, failure or delay.
- **Checks.** A case can assert the final outcome, output values (equals, contains, range), the path taken (exact or in order), which decision rule fired and which approver decision was used. A failed check shows expected against actual.
- **Suites.** **Run all saved cases** runs up to 20 in one action. Shared cases appear on the publish review page as "N of M shared tests passed". A shared case now passes only if its outcome **and** its checks hold, so a failing check can stop publishing through the existing gate; cases without checks behave as before. The decision-table **test grid** takes pasted rows in the CSV column format.

**Export.** **More > Download BPMN (.bpmn)** on the Studio header, or **Download BPMN** on the Source tab, gives the draft as a BPMN file with its diagram layout; a draft with blockers is refused. Each release row has a **BPMN** button for people with export permission. A header comment lists the Orqix version, the source, the hash and which Orqix extensions the file carries. The same menu links to the case-model XML download. Re-importing an Orqix file through the Camunda importer is not the way back in: use **Source > BPMN > Apply** (S60).

## Waiting for an outside system: callbacks, webhooks and Kafka (designers and operators) - 27 October 2026

Notes: [S98](build/S98-callback-tokens-and-triggers.md), [S99](build/S99-webhooks-and-kafka-triggers.md); setup in the [admin guide](admin-guide.md).

- **Wait for an outside system.** On a **Wait for a message** step, turn on the external-callback option and set how long a token stays valid (1 minute to 30 days). A person or relay with `run.operate` issues a token for the waiting request from the run page; it is shown **once** and stored only as a hash. The outside system **polls** the wait (waiting, resumed, expired or cancelled) and **resumes** it with a result and an idempotency key. One resume per wait; a repeat returns the first answer. Revoking stops the token; the step keeps waiting. A suspended request refuses a callback until an operator resumes it, which is a different action from this one.
- **Webhooks in.** A signed inbound webhook that carries the callback token resumes that wait, but only for a request of the trigger's own workflow. Without the token it only records an event; a person with start permission starts the run.
- **Webhooks out.** **Settings > Webhooks** subscribes an HTTPS endpoint to request events. Deliveries are signed, retried with backoff, and become dead letters that operators can replay. Payloads carry ids and states, not request data.
- **Kafka.** **Settings > Kafka** configures a topic per trigger. A record either **starts** a request (mapped fields, the normal start checks, as the trigger's run-as person) or **resumes** a wait by correlation key; the message never chooses which. The consumer is **off by default**; `stub` mode has a test-message button for demos; `live` mode reads real topics. Bad records become dead letters showing only an error code, partition, offset and size.

## Ask Orqix (Ctrl+K) and the Assistant - 28 October 2026

Press **Ctrl+K** (Cmd+K on a Mac) on any page. Type a page name to jump to it (only pages you may open are listed) or type a question and choose **Ask the Assistant**. Your question opens the **Assistant** page in its message box; **nothing is sent until you press Send there**, and the question never appears in the address bar. The Assistant page keeps the conversation in the middle with the composer at the bottom (**Talk** dictates), and two cards on the right: **This request** (what is being built or decided) and **Tips**. A list of earlier conversations is not shown because the server keeps one open conversation per person. List pages (workflows, requests, cases, insights) are unchanged: a chat box would not help there.

## Restoring workflows and per-item approval rules (designers) - 28 October 2026

[S104](build/S104-restore-callback-limits-per-item-quorum.md).

**Restore an archived workflow.** **Workflows → Archived workflows** lists archived workflows, newest first, with who archived them and the published version. Choose **Restore…**, say why and confirm: the workflow returns to the catalog with its folder, owner, draft and published version, and the same people can start it again. Needs `workflow.edit`.

**One approval per list item: new choices.** Under *One approval per list item* in the approval step:
- **Decide items one at a time, in list order**: the next item opens only after the previous one is decided. Items not reached when the step ends are recorded as `cancelled`.
- **Items that must be approved**: leave empty for every item. With a number (for example 2 of 3), the step is approved as soon as that many items are approved and rejected as soon as that number can no longer be reached; the open items are cancelled. The request list must always have at least that many items (set its minimum), otherwise Check workflow reports `WF_PER_ITEM_QUORUM`.
- **Proposed approver**: a text column of the list holding an email. When the item's task opens, that person gets it if they could pick it up themselves (right group, skills, permission, not their own request, not kept apart by separation of duties). Otherwise the item waits in the group queue and the timeline says so.

**Start a workflow once per row** ([S105](build/S105-row-iteration.md)). In a *Start another workflow* step, **Collection iteration** now also accepts a list of rows (for example order lines with an item and a price). Choose the list, then under **Fill from each row** pick which column fills each field of the started workflow. Fields not filled from the row take their value from **Request values** as before. Check workflow reports a column that does not exist (`WF_FOREACH_COLUMN`) or does not fit the field (`WF_FOREACH_ITEM_TYPE`), including an optional column feeding a required field.

**Promotion notifications** ([S106](build/S106-shapes-promotion-kafka-e2e.md)). If you can approve promotions into a workspace, the bell tells you when someone asks to promote a workflow there; the notification opens **Promotions**. If you asked, the bell in your own workspace tells you when your request was approved or rejected, with the approver's note.

## Shared forms (designers) - 30 October 2026

Notes: [S127](build/S127-shared-forms.md). A shared form is a set of request questions with their rules (*Show only when*, *Require an answer when*) and groups, kept for the whole workspace with numbered versions, an owner and an archived flag, like a shared decision table.

- **Save as shared form** on the **Form** tab saves this request form as version 1 under a name and key, and links this form to it. The submit label, currency, help position and any pinned request view stay with the workflow.
- **Insert a shared form** copies its questions, rules and groups into this request form and links the version (**Insert shared form vN**). If this form already has a question with the same key, rename the shared question or skip it to keep yours; nothing is overwritten, and **Insert** stays off until every clash is settled.
- Each linked form shows **Up to date**, **Version N available** or **Edited here**. **Update to vN** lists the added, removed and changed questions before you apply it. **Restore version N** undoes edits to the copied questions; **Save as shared version N+1** turns your edits into a new version that other workflows can adopt; **Unlink** keeps the questions as this workflow's own.
- The copied questions are ordinary questions: **Check workflow** and **Publish** check them like any other. Publishing is also blocked while the copy differs from its version, or the shared form is archived or missing, and warns when a newer version exists (the published version keeps the version it pinned). Reordering copied questions or moving them between groups is allowed.
- **Settings → Shared assets** lists shared forms (kind *Form*) with their questions, and **Where it is used** shows every workflow whose draft or active release uses one, with the version.
