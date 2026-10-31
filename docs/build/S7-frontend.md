# S7 — Frontend screens and studio
Build specification · revision 1.0 · 7 October 2026

Turns the [UX specification](../02-ux-design.md) into routes, components, data flows and states for R0. API calls refer to `operationId`s in [`openapi.yaml`](../../contracts/openapi.yaml).

## 1. Shell

- Left navigation (224 px, collapsible below 1024 px): **Home, My tasks, Workflows, Runs, Insights, Settings**. Items are shown only when the caller has a matching permission (`/me`): Runs and Insights need `run.read`; Workflows needs `workflow.read` or `workflow.start`; Settings needs any of `workspace.manage-access`, `connection.read`. Hiding is convenience; the server enforces.
- Top bar (brand color with the accent stripe in the `wf` palette): wordmark, workspace switcher (from `getMe`), notifications bell (`listMyNotifications`, unread badge), user menu (name, sign out → `POST /logout`). The left navigation is white with an indicator bar on the active item.
- Test workspaces show a persistent banner: "Test workspace — requests here use test connections."
- Routes are workspace-scoped: `/w/:workspaceId/...`. `/` redirects to the last used workspace (localStorage, wrapped in try/catch) or the first in `/me`.

## 2. Route map

| Route | Screen | Visible to | Main API calls | Ticket |
|---|---|---|---|---|
| `/w/:ws` | Home | member | `listWorkflows?startable=true`, `listTasks?view=assigned`, `listMyRequests` | B06.2 |
| `/w/:ws/start/:workflowId` | Request form | `workflow.start` | `getStartForm`, `startRun` | B06.2 |
| `/w/:ws/requests` | My requests | member | `listMyRequests` | B06.4 |
| `/w/:ws/runs/:runId` | Run detail (requester, approver or operator view); Timeline or Diagram | initiator, participant, `run.read` | `getRun`, `listRunEvents` (polling), `getRunPath` (Diagram only), operator commands | B06.4, OPS03 |
| `/w/:ws/tasks` and `/w/:ws/tasks/:taskId` | My tasks + task panel | `task.act` | `listTasks`, `getTask`, `claimTask`, `releaseTask`, `decideTask` | B06.3 |
| `/w/:ws/runs` | Runs (operator/viewer), filter "Needs attention" | `run.read` | `listRuns` | B06.4 |
| `/w/:ws/insights` | Insights: cards, daily throughput, cycle time, bottlenecks (CSV), oldest open, needs attention, time limits and overdue | `run.read` | `getInsightsSummary`, `getInsightsThroughput`, `getInsightsSteps` | OPS04 |
| `/w/:ws/workflows` | Workflow list + "New workflow" | `workflow.read` | `listWorkflows`, `listTemplates`, `createWorkflow` | B07.1 |
| `/w/:ws/workflows/:id` | Workflow overview: draft status, releases, active release, export | `workflow.read` | `getWorkflow`, `listReleases`, `setActiveRelease`, `exportRelease` | B07.1 |
| `/w/:ws/workflows/:id/edit` | Studio (canvas / steps) | `workflow.read` (read-only) / `workflow.edit` | `getDraft`, `saveDraft`, `validateDraft`, `evaluateDecision`, `listGroups`, `listConnections` | B07.2–B07.4 |
| `/w/:ws/workflows/:id/publish?revision=n` | Publish review | `workflow.publish` | `validateDraft`, `getRelease` (active), `publishDraft` | B07.5 |
| `/w/:ws/settings` | Members and groups (read-only in R0), connections | `workspace.manage-access` / `connection.read` | `getAccessOverview`, `listGroups`, `listConnections`, `getConnection`, `putConnectionBinding`, `testConnection` | B06.1 |
| `/forbidden`, `/not-found` | Error pages | all | — | B06.1 |

## 3. Data layer rules

- `src/api/client.ts` wraps openapi-fetch: same-origin, `credentials: 'same-origin'`, adds `X-XSRF-TOKEN` from the cookie on unsafe methods, adds `X-Correlation-Id`, parses `application/problem+json` into a typed `ProblemError` (`code`, `fieldErrors`, `issues`, `currentVersion`).
- `401` → full-page navigation to `/oauth2/authorization/orqix` (the return URL is kept by the server-side saved request).
- **Idempotency keys** are created with `crypto.randomUUID()` when the user first presses a command button and reused for every retry of that same command; a new key is created only when the user changes the input.
- Query keys: `['ws', ws, 'tasks', view]`, `['ws', ws, 'run', runId]`, `['ws', ws, 'run', runId, 'events']` etc. Mutations invalidate the affected keys. Polling intervals follow S3 §9 and pause when the tab is hidden.
- Money: inputs keep the typed string; valid pattern `^\d{1,9}(\.\d{1,2})?$`; converted to a JSON number only on submit; the UI never does arithmetic on amounts and formats with `Intl.NumberFormat('en-US', {style: 'currency', currency: 'USD'})`.

Problem code → user-facing behavior:

| Code | UI |
|---|---|
| `VALIDATION_FAILED`, `REASON_REQUIRED` | Inline field errors from `fieldErrors`; focus the first invalid field |
| `STALE_REVISION` | Studio conflict dialog: "A newer version was saved by {name}." Options: Reload latest / Download my version (JSON) |
| `TASK_ALREADY_CLAIMED`, `TASK_NOT_OPEN`, `TASK_VERSION_CONFLICT` | Refresh the task; show "Claimed by {name}" or "This task was completed by {name}"; disable actions |
| `SELF_APPROVAL_DENIED` | "You can't approve your own request." |
| `RELEASE_NOT_ACTIVE` | "This form was updated. Review it and submit again." Reload the form, keep values whose keys still exist |
| `NO_ACTIVE_RELEASE` | "This workflow isn't published yet." |
| `WORKFLOW_INVALID`, `WARNINGS_NOT_ACKNOWLEDGED` | Open the validation panel / warnings acknowledgement on the publish page |
| `QUOTA_EXCEEDED` | "Too many open requests for this workflow. Try again later." |
| `EFFECT_NOT_RECONCILED`, `RUN_BUSY` | Operator dialog message with the next allowed action |
| `DEPENDENCY_UNAVAILABLE` | "Service temporarily unavailable. Nothing was submitted." Retry button (same idempotency key) |
| `NOT_FOUND`, `FORBIDDEN` | Not-found page ("doesn't exist or you don't have access") / inline "You don't have permission to …" |

## 4. Screens (R0 behavior)

**Home.** Three sections: *Start a request* (cards: name, description, "Start"), *Waiting for you* (up to 5 assigned + claimable tasks), *Your recent requests* (up to 5). Empty state: "No requests yet. Start with Purchase approval." Skeletons while loading.

**Request form.** Rendered from `inputSchema` + `inputUi` (§6). Shows release number discreetly ("Version 2"). Submit shows a confirmation summary of entered values, then `startRun` with `expectedReleaseId`. Success page: request reference (`Run.reference`, e.g. ORQ-1043, shown in the body font with tabular numerals; the requester's own `businessKey` is shown as "your reference" when it differs), current step and owner ("Waiting for Managers"), link to the request. Errors keep entered data.

**My requests / Run detail.** Status uses icon + text + color: Waiting, Running, Needs attention, Completed · Fulfilled, Completed · Rejected, Cancelled. Header shows workflow name, version, current step with owner and due date ("Due 14 Oct — reminder not active" is never implied; due dates are labels). Sections: *Request* (labelled fields when data is visible), *Decisions* (who, when, action, reason/comment), *Timeline* (events in business language, table below), *Technical details* for operators (release number, effect table with state/attempts/last error, process identifiers). Operator actions open dialogs that require a reason: Retry, Reconcile (resolution radio, evidence note, remote reference, result fields generated from the node's `resultMapping`), Cancel (warns about completed effects), Reassign (eligible subject picker). Unknown outcomes read: "Procurement service may have created the order. Check it before retrying." — never just "Failed".

| Event | Timeline text |
|---|---|
| `RUN_STARTED` | "{initiator} submitted the request (version {n})" |
| `TASK_CREATED` | "Waiting for {group}: {label}" |
| `TASK_CLAIMED` / `TASK_RELEASED` / `TASK_REASSIGNED` | "{actor} picked up …" / "returned … to {group}" / "{actor} reassigned … to {to}" |
| `TASK_DECIDED` | "{actor} approved {label}" / "{actor} rejected {label}" |
| `DECISION_EVALUATED` | "{label}: {outputs in words}" |
| `EFFECT_SUCCEEDED` | "{label} completed (reference {remoteReference})" |
| `EFFECT_FAILED` | By cause (HTTP status wins over the error code): not sent — "{label} could not be sent: the other system could not be reached"; timed out — "{label} timed out"; 4xx — "{label} was rejected by the other system (HTTP {status})"; 429 — "{label} was rate limited"; 5xx — "{label} hit an error in the other system (HTTP {status})"; unknown — "the outcome of {label} is unknown"; no reason given — "{label} did not respond". Each adds "Attempt {a} of {max}. Next retry at {time}." when known. Timeline markers are neutral for ordinary events, status hues for outcomes and danger color only for failures |
| `EFFECT_UNKNOWN`, `RUN_NEEDS_ATTENTION` | "{label} needs attention — an operator has been alerted." |
| `OPERATOR_RETRY`, `EFFECT_RECONCILED` | "{actor} retried …" / "{actor} confirmed the outcome of …" |
| `RUN_COMPLETED` | "Finished: {outcome}" |
| `RUN_CANCELLED` | "{actor} cancelled the request: {reason}" |

**Run diagram (Timeline | Diagram).** A toggle in the card that holds the timeline switches to a read-only diagram of the workflow of the request's pinned release (`getRunPath`; the choice is remembered per browser, the timeline is the default and stays available). The diagram reuses the studio's step cards, route geometry and layout code (saved positions, ELK when none) on a canvas that does not load the editing store. Each step carries a text chip with an icon: the current step is outlined and pulses (not under reduced motion) and reads "Waiting here" or "Running here" for operators and "You are here" for the requester; steps passed read Done, Approved, Rejected, Timed out or Stopped; a failed call reads "Needs attention" (operators) or "Delayed" (requester); steps a finished request did not use are dimmed and read "Not used". Routes the request used are drawn thick, others thin and dashed. Selecting a step (click, or Enter on a focused step) shows a popover on the step and the "Step details" panel under the canvas (who it waits for, since when, due, times at the step, links to child requests); operators also see Step ID, step type and, for calls, state, attempts, last problem and remote reference. Every step is focusable, has an accessible name ("Approval: Finance review. Waiting here.") and a description; "Steps as a list" under the canvas is a table of the same facts. On a phone the diagram pans inside its box (the page does not scroll sideways) and starts at the current step at a readable zoom. When the history was truncated, only current steps are shown with a pointer to the timeline.

**Insights.** Cards (started, completed, fulfilled, rejected, still open, needing attention), then "Requests per day" (SVG: bars for completed, solid for fulfilled and hatched for rejected, a line with round markers for started; legend, axis labels, a text summary and a table of every day), cycle time per workflow (median, 90th percentile), "Where requests wait" (average and 90th percentile wait, count, waiting now and timeouts per approval and wait, "Download CSV" with the findings export's formula protection), needing attention, oldest open requests and "Time limits and due dates" (approvals that timed out, items overdue now). A workflow selector and a 7/30/90-day period (kept in the URL) filter everything. Colors come only from theme tokens; no chart library is used. Requests appear by reference only; titles and data never reach this page. People without `run.read` see "No access" and no navigation item.

**My tasks.** Tabs with counts: *Assigned to me*, *Available to claim*, *Completed*. Columns: Request, Step, Requested by, Due, Action. Desktop: selecting a row opens the task panel on the right; mobile: navigates to `/tasks/:taskId`. Task panel: request summary with labels and money formatting, "Why you're seeing this: you're in the {group} group", decision history, one *Comment* text area, buttons **Reject** and **Approve** (the action panel stays visible while scrolling). Unclaimed group task shows **Claim task** first. Reject sends the text as `reason` and requires it (inline error "Add a reason so the requester knows what to change"); Approve sends it as `comment`. Buttons disable while pending; double submission is impossible (same idempotency key). When the server reports another winner, show "This task was completed by {name}" and refresh. If the caller cannot act, actions are hidden with the reason from `allowedActions`.

**Workflows / overview.** List with name, active version, draft revision, last change. Overview shows releases (number, published by/at, active badge), "Make active" (rollback) with confirmation "New requests will use version {n}. Existing requests are unchanged.", export download, and "Edit draft".

**Settings.** R0 shows members, roles and groups read-only (changes through the API/seed; editing UI is R1). Connections: list, operations and effect class; for `connection.manage`, an edit-binding form (base URL, auth type, secret reference name — never a secret value) and "Test connection" for testable operations.

## 5. Studio

Header: `Workspace / Workflows / {name}`, "Draft revision {n}", save state (Saving… / Saved just now / Unsaved changes / Offline – retrying). Actions: **Preview form**, **Test workflow**, **Check workflow**, **Publish** (navigates to publish review; disabled without `workflow.publish`). Tabs: **Canvas** (default) and **Steps** (keyboard-first list). Desktop only (≥ 1024 px); smaller screens show the Steps list read-only with a note.

**Palette (business labels).** Ask for approval, Make a decision, Choose a path, Call a system, Notify someone, Finish. The start step always exists and cannot be deleted. Searchable; each item has a one-line explanation.

**Canvas (React Flow).** Custom node card (≈ 296 px wide; label 18 px, summary 17 px, kind badge 15 px): icon, kind badge, label, issue badge (count from the last validation); Finish cards are tinted by outcome (Fulfilled / Rejected) with icon and text. The first view fits the workflow but never below zoom 0.85 (on-screen text ≥ 14 px); a wider workflow starts at the request form and is panned, and *Fit view* still shows everything. The view refits after structural changes. New steps are placed right of or below the selected step without overlap. **Auto-layout** (ELK.js, lazy-loaded, layered left→right, Approved above Rejected) is one undoable edit saved in `uiLayout`; a stored layout with overlaps, missing positions or backward routes is arranged once on open (undoable, not autosaved until the next edit). Routes are orthogonal with labels on a background; the palette collapses to an icon rail; the minimap is small. Handles and connection rules (`isValidConnection`, convenience only — the server validates):
- No incoming connection to Start; no outgoing from Finish; no self-loops.
- Start, decision, call-a-system and notify: one outgoing.
- Approval: two labelled source handles, **Approved** and **Rejected** (text label plus color), one connection each.
- Choose a path: each new connection opens the condition editor; one route must be marked **Otherwise**.
- Deleting a node removes its transitions, its `uiLayout` position and any decision table used only by it (with confirmation listing what will be removed).
- Positions are saved in `uiLayout`; dragging records one undo entry at drag end.

**Inspector (right, 320 px).** Basic fields first, *Advanced* collapsed:

| Step | Basic | Advanced |
|---|---|---|
| Request form (start) | Field list: label, type (Text, Long text, Number, Money (USD), Whole number, Date, Yes/No, Choice), required, help text, choices; reorder | Field key (auto camelCase from label), min/max, length limits, earliest and latest date (Date) |
| Wait | **For a duration** (ISO duration with a plain-language reading) or **Until a date** (a Date question or guaranteed earlier date, time of day in UTC, "Try a date" preview: "Waits until 14 Oct 2026, 09:00 UTC.") | Step ID |
| Ask for approval | Assign to (group picker from `listGroups`), due after (hours, labelled "shown as a due date; no reminder is sent"), "Reason required when rejecting" (locked on) | Step ID |
| Make a decision | Input field (chips "Request → Amount"), rows: operator (is / at least / less than) + value → outputs; *Otherwise* outputs; **Test this rule** (sample value → `evaluateDecision`) | Table key, version |
| Choose a path | Routes: field, operator, value, label; which route is Otherwise | — |
| Call a system | Connection, operation, input mapping table (each request field, required badge, source chip filtered by type), result fields to keep | Timeout (≤ operation max); effect class shown read-only, e.g. "Safe to retry: the service de-duplicates repeated requests" |
| Notify someone | Message (≤ 200 chars); recipient: Requester | — |
| Finish | Outcome label; kind (Successful / Rejected) | — |

Field chips list the request fields and outputs of upstream steps; the server's dominance check (S4 §4) is authoritative and reports `WF_FIELDREF_NOT_GUARANTEED`.

**Autosave and conflicts.** Debounced 1.5 s after the last change: `saveDraft` with `If-Match`. On `412`, autosave stops and the conflict dialog appears. A failed save keeps the local draft and retries with backoff; the header shows the state honestly.

**Undo/redo.** Zustand + zundo temporal history on the definition only (not selection or viewport), 100 entries. Shortcuts: Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z or Ctrl+Y, Delete/Backspace on the selected node or edge (with confirmation when references exist), Ctrl/Cmd+S forces a save.

**Steps tab (keyboard alternative, required for FR03).** Steps in topological order. Each row: label, kind, and its routes as selects ("When approved → [step]", "Otherwise → [step]"). "Add step after…" menu, Rename, Delete. Everything the canvas can change about the graph can be changed here without a pointer device (WCAG 2.2 SC 2.5.7 Dragging Movements).

**Check workflow.** Saves if dirty, then `validateDraft`. Panel groups issues by step with "Go to step" (selects the node and focuses the field named by `path`). Blockers and warnings are visually distinct; warnings explain consequences.

**Test workflow.** The header button opens a panel in place of the inspector (desktop only). *Sample request* is the start step's form, rendered by the form renderer and pre-filled with plausible values. *What should happen at each step* lists only the steps where something is decided: approvals (approve, reject with an optional reason, or the time limit fires when one is set), calls to a system or agent (works with a sample or typed answer, refuses, does not answer, answer lost), child workflows (the outcome of each attempt or item, and "also walk through the other workflows") and message waits (arrives or not). **Run test** saves the draft and calls `simulateWorkflow` for that revision. The result is a **step-by-step playback**: an outcome banner, then one step at a time with its plain-language sentence and what it looked at (rules with the values used, routes, the group, what a call sent and kept, the notification text), **Previous step** and **Next step** buttons (the arrow keys, Home and End also work while one of them has focus), a polite live region announcing "Step n of m: ...", and the whole run as a text list (the alternative to the canvas). On the canvas the current step has a ring, the path so far is thick, steps later in the run are lighter and steps the run never reaches are faded; the canvas scrolls to the current step. *What this run covered* says "n of m routes tested" and lists the routes not tried; warnings (a group that does not exist, a connection without a binding, a rule that can never apply) have **Go to step**. **Try another case** returns to the form with the same values. A fixed note says it is a dry run and what it cannot show (S4 section 13.4). **Saved test cases:** up to 10 named cases per workflow in this browser's localStorage (per viewer, every access wrapped in try/catch, absent storage just means none), each with an expected outcome (a Finish outcome, Needs attention, or none); **Run all saved cases** lists Passed or Failed with the reason; **Export** and **Import** exchange them as JSON (`format: orqix-test-cases`, malformed cases are skipped). **Two kinds of saved cases, one panel.** Cases kept in the browser are personal and private to the viewer. **Share and test for publication** (S28) copies the same cases (each needs valid answers and an expected outcome) to the workflow's server-shared, versioned suite and runs it against the saved draft revision; once a non-empty shared suite exists, publishing requires it to pass for that exact draft and its pinned dependencies. The panel reuses the simulator and the case editor for both, so there is no second test screen: personal cases are for trying things, the shared suite is the designers' agreed regression set (it replaces the whole suite, needs `workflow.edit`, and refuses cases that ask for recursive child simulation). Playback state lives outside the studio store, so it never enters the undo history or the saved draft. Mock mode answers with `frontend/src/mocks/simulator.ts`.

**Publish review.** Summary (draft revision → "will become version {n+1}"), changed steps compared with the active release (node/transition/decision added, removed or changed — client-side diff of canonical JSON), dependencies (groups, connection + version + bound host, decision tables), warnings with an acknowledgement checkbox, "Make this the version used for new requests" (checked by default), **Publish version {n+1}**. Success: "Version {n+1} published. Existing requests continue on their original version."

## 6. Form renderer

| Schema | Widget | Validation (ajv 2020 dialect, `multipleOfPrecision: 2`) |
|---|---|---|
| string | text input | minLength, maxLength |
| string + `textarea` widget | textarea with counter | same |
| string + `enum` | select (≤ 7 options may use radio) | enum |
| number + `money` widget | text input with USD prefix, pattern above | minimum, maximum, multipleOf 0.01 |
| number / integer | numeric text input | minimum, maximum, integer |
| boolean | checkbox | — |

`title` is the label; `description` is help text linked with `aria-describedby`; required fields are marked in text ("required"), not by color alone. Client validation assists only; server `fieldErrors` are rendered on the same fields.

## 7. Accessibility (WCAG 2.2 AA)

Checked with keyboard-only runs, a screen reader pass and axe in Playwright on every screen:

- Visible focus on all interactive elements; focus never hidden behind sticky panels (SC 2.4.11).
- Pointer targets ≥ 24 × 24 px (SC 2.5.8); primary actions 44 px.
- Canvas has the Steps alternative (SC 2.5.7); custom nodes have accessible names ("Approval: Review request, 2 issues") and React Flow keyboard support is enabled.
- Status never relies on color alone; live regions announce save state, claim results and validation completion politely.
- Forms: labels, error text associated via `aria-describedby`, error summary on submit, no information lost on error (SC 3.3.7 Redundant Entry: the confirmation step reuses entered values).
- Authentication is delegated to the IdP; nothing in Orqix requires a cognitive test (SC 3.3.8).
- Light theme in R0; dark theme after its own contrast verification.

Reference: [WCAG 2.2](https://www.w3.org/TR/WCAG22/) (W3C Recommendation, current edition), [React Flow accessibility](https://reactflow.dev/learn/advanced-use/accessibility).

## 8. Visual tokens

From doc 02: token sets per brand palette in `src/index.css` (`:root` = `orqix`, `:root[data-brand='wf']` overrides every token), selected at build time by `VITE_ORQIX_BRAND` (default `wf`) and written into `index.html` (no inline script under the CSP, no flash). `wf` key values: primary #D71E28 (white text 5.1:1), text-red #A8161F, accent gold #FFCD41 (dark text only), page #F4F0ED, text #3B3331, muted text #675C58, border #D9D2CC, input border #857A75, focus ring #1F1A19 with a 2 px gap. Components contain no color literals (`no-hardcoded-colors.test.ts`); `contrast.test.ts` checks every text pair ≥ 4.5:1 and UI pair ≥ 3:1 for both palettes. 8 px spacing grid; 16 px body; 24–32 px page titles; 12 px card radius. Define them as Tailwind theme tokens (CSS variables) so dark mode can be added without component changes. Status colors: Waiting (amber), Running (blue), Needs attention (red), Completed · Fulfilled (green), Completed · Rejected (slate), Cancelled (gray) — each paired with an icon and text.

## 9. Frontend tests

- Unit (Vitest + Testing Library): form renderer against `purchase-scenarios.json` `inputValidation` cases; problem-code mapping; money parsing; studio store operations (add/connect/delete/undo) and connection rules.
- E2E (Playwright, S9): demo path, conflict UX, keyboard-only studio edit through the Steps tab, axe scan per route (no serious/critical violations), no CSP violations in the console.
