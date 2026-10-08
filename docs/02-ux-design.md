# Business experience and screen design
Status: design specification. [Visual board](../design/business-workspace.svg) uses illustrative data.

## Design direction

Show the work, its owner and its next action. Use readable task cards and familiar form controls. Reserve BPMN terminology, credential bindings and raw payload inspection for technical users.

The first navigation has five destinations: Home, My tasks, Workflows, Runs and Settings. Templates sit inside Workflows. Migration is an Import action. Agents, tools, MCP and connections are grouped under Settings → Integrations when available. The full master-prompt navigation becomes grouped navigation as those capabilities ship; unfinished areas are not empty menu items.

## Visual system

- White content surfaces, cool gray page background, dark navy typography and restrained teal primary actions.
- 8 px spacing rhythm, 16 px body type, 24–32 px page titles, 12 px card radius.
- Status uses icon + text as well as color: Waiting, Running, Completed, Rejected, Needs attention.
- Desktop studio: 224 px navigation, flexible canvas, 320 px inspector. At smaller widths collapse navigation and open inspector as a drawer.
- Inbox and request forms support mobile layouts. Canvas editing is optimized for desktop; provide an accessible step list.
- Preserve visible focus, adequate contrast, descriptive labels and 44 px primary touch targets.
- Light theme first; dark theme after accessibility verification.

## Home and request submission

Home offers Start a request, My pending work and Recent requests. It does not open a diagram by default.

A template card names the business outcome, typical owner and required input. Clicking Start opens a form with plain descriptions and a concise summary before submission. The submitted request page immediately shows request ID, current owner and expected next action. Do not promise an estimated completion time without a defined SLA.

Empty state: “No requests yet. Start with Purchase approval.” Loading uses skeletons. Errors retain entered data and give a retry action.

## My tasks

Desktop layout:

    My tasks                    Search request or person
    [Assigned to me 8] [Available to claim 3] [Completed]
    Request             Step             Due          Action
    Laptop purchase     Manager review   Today        Open
    Supplier setup      Finance review   Tomorrow     Open

    Selected task: Laptop purchase · ORQ-1042
    Requester / amount / purpose / cost center
    Decision history and policy explanation
    [Comment]                                  [Reject] [Approve]

Show urgent/overdue work with explicit dates. Use “Claim task” for group tasks. The action panel remains visible while reading. Hide decisions if the user cannot act, with a clear reason.

Approval submits once; disable repeat action while pending. If another person completes the task, display “This task was completed by …” and refresh its state. Require a reason for rejection. Confirm only genuinely consequential actions according to connector/business policy, not every routine click.

## Workflow studio

Header: Workspace / Folder / Workflow name, Draft revision, save state.
Actions: Preview form, Check workflow, Test, Publish.
Views: Business flow by default; BPMN view and Definition in Advanced mode.

    Step library        Purchase approval                Step settings
    Start               [Request form]                   Manager approval
    Approval                   |                         Assign to: Managers
    Decision            [Manager approval]               Due after: 24 hours*
    Call a system          /          \                  Form: Review request
    Notify             Reject       Approved             On reject: End rejected
    End                  |             |                 Advanced >
                        End       [Amount policy]

* Due labels may ship before active reminders; UI must clearly distinguish them.

Business palette labels map to runtime constructs:

| Label | Meaning |
|---|---|
| Request form | Validated input before the process starts |
| Ask for approval | Human task assigned to a person/group |
| Make a decision | Versioned deterministic decision table |
| Choose a path | Exclusive gateway using approved structured conditions |
| Do work in parallel | Matched parallel fork/join, only once supported |
| Call a system | Approved connector operation |
| Wait | Durable timer, only once supported |
| Run another workflow | Pinned child workflow, only once supported |
| Notify someone | Durable in-app notification or explicit external connector |
| Finish | End with a named business outcome |

A selected node opens basic properties first: Name, Owner/Connection, Inputs and Result. Advanced properties contain retry, timeout, redaction and mapping details, constrained by policy. Show human-readable field chips such as “Request → Amount”; store structured references, never executable strings.

Schema configuration v0 uses an ordered field list: label, key, type, required, choices and help text. Reuse JSON Schema with a small UI metadata document. Build no unrestricted HTML or JavaScript form designer.

## Validation and publishing

Validation summary groups blockers by step, with “Go to step.” Example: “Finance review needs an approver group.” Warnings explain consequences and are retained with the release.

Publish review shows changed steps, form/rule versions, connection names, intended workspace, test evidence and revision. A user with publish permission can confirm release. Success says “Version 3 published. Existing requests continue on their original version.”

Connection secrets are never shown. Missing connection binding blocks publish. Check again on the server; browser validation is only assistance.

## Runs and operational recovery

Runs list filters by workflow, outcome, date, requester and owner. Run detail has Summary, Timeline and Technical details; engineers also see the graph. Display business outcome separately from run lifecycle.

Failure example: “Procurement service did not respond. Attempt 2 of 3. Next retry at 14:35.” After retries are exhausted: “Needs attention — contact an operator.” Raw stack traces are restricted and redacted.

Operator controls: inspect, reconcile outcome, retry when safe, suspend/resume when supported, cancel. Never label an unknown external outcome simply “Failed”; it may have succeeded remotely.

Refresh v0 by polling; show last updated time. SSE is an enhancement that uses the same persisted event feed, not a prerequisite for correct status.

## Migration review

Steps: Upload → Compatibility → Map → Review → Test → Publish.
Each source element gets a supported, mapped, manual-action or blocked status.
Show source step beside the converted step, with explanation. Unknown elements remain visible. “8 of 10 elements mapped” describes count, not 80% behavioral equivalence.

A missing source format is an honest “Adapter not available” state. Uploaded files never become executable automatically.

## AI draft experience

Offer “Describe your workflow” once a provider is configured. Output opens as an editable draft with assumptions, missing connections and validation issues. Require a human to review and publish. AI cannot create credentials, expand tool access, grant permissions or make production calls from the designer.

When no provider is configured, templates and manual creation remain fully usable. There is no fake AI generation success.

## User validation script

Give five business users the purchase example and observe: find a template, add a field, change approval routing, test a threshold, publish if authorized, submit and find progress. Record task completion, confusion points and time. Verify keyboard-only equivalent actions and a screen reader's reading order. This research is planned, not completed.
