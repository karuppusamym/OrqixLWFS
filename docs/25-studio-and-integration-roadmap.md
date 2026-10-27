# Studio and integration roadmap: analysis and plan

26 October 2026. Seven work items raised after the demo rehearsal, each with the current state (checked in the code on 26 October, release `r0-2026-10-26`), the gap, the design decision, the delivery plan and the acceptance. Tracker rows: `STU01` to `STU04`, `EX10`, `IN05` and `STD01` in the [delivery register](22-competitive-gap-delivery.md). Scope rule as elsewhere: a delivered slice is recorded as bounded, with its exclusions, and does not close a wider epic.

## 0. Summary

| ID | Item | Today | Gap | Size |
|---|---|---|---|---|
| STU01 | BPMN and CMMN notation on the canvas | every step is the same 296 px card with a coloured edge | no decision diamond, event circles or task shapes | medium |
| STU02 | Conversational authoring panel | "Describe it" is a one-shot dialog; the chat assistant is for requesters | no way to regenerate, add or modify a draft by conversation | medium to large |
| STU03 | Test tab, mock data and integration points | dry-run simulation, saved cases and shared test suites behind a header button | not a tab; no mock responses for calls, adapters and agents; no whole-workflow assertions | medium to large |
| STU04 | Export to BPMN and CMMN | BPMN source view and edit; CMMN XML preview and download for case models | no BPMN file download with diagram interchange | small to medium |
| EX10 | Resume and poll API | operator `resumeRun`, signals, messages, external-worker callbacks | no single callback token or poll endpoint for an external system waiting on a request | medium |
| IN05 | Webhook and Kafka triggers | signed inbound webhook starts a run; Kafka produce to a stub | no webhook to resume, no outbound webhooks, no Kafka consumer | large |
| STD01 | Standards conformance (BPMN 2.0, CMMN 1.1, DMN, UML) | generated BPMN is standard; the canvas is not BPMN notation | no written conformance statement | analysis |

Suggested order: STD01 (this document), then STU01, STU03 and STU04 in parallel (they touch different parts of the Studio), then STU02, then EX10 and IN05 (they share the callback model, so they are designed together and built in that order).

## 1. STU01 - BPMN and CMMN notation on the canvas

**Today.** `frontend/src/features/studio/StepNode.tsx` renders every step as one rounded card of a fixed width with a coloured left edge, a kind badge and outcome chips. There are 19 step kinds (`model/kinds.ts`). The case designer has its own tree-style form, not a canvas.

**Gap.** Users who know BPMN expect a decision to be a diamond, events to be circles and tasks to be rounded rectangles with a type icon. The card gives the right information but not the notation.

**Design.**
- Render each step kind with the BPMN 2.0 symbol it compiles to, keeping the card's information (label, outcomes, validation marks) readable: a short label inside or beneath the shape, and the full detail in the inspector.
- Mapping (the same table is the conformance record for STD01):

| Step kind | Shape | BPMN element it compiles to |
|---|---|---|
| Request form | thin circle (plain BPMN start event, no marker) | none event start |
| Ask for approval | rounded rectangle, person icon | user task |
| Make a decision | rounded rectangle, table icon | business rule task |
| Choose a path | diamond, X | exclusive gateway |
| Run in parallel / Wait for all | diamond, plus | parallel gateway (inclusive: diamond, O) |
| Call a system | rounded rectangle, gear | service task |
| Notify someone, Send through an adapter | rounded rectangle, envelope | send task |
| Generate a document | rounded rectangle, page icon | service task |
| Work by an external worker | rounded rectangle, gear and arrows | external service task |
| Wait | circle, clock | intermediate timer catch event |
| Wait for a message / signal | circle, envelope / triangle | intermediate message / signal catch event |
| Send a signal | circle, filled triangle | signal throw event |
| Raise an error | thick circle, lightning | error end event |
| Undo completed calls | circle, double arrow | compensation throw event |
| Start another workflow | thick-bordered rounded rectangle, plus | call activity |
| Finish | thick circle | end event |
| Sections and handlers | dashed rounded frame (existing frames) | sub-process and event sub-process |

- CMMN notation for the case designer: stage as a rectangle with angled corners and a collapse marker (CMMN 1.1), human task as a rounded rectangle with a person icon, milestone as a stadium, sentries as small diamonds on the edge, required and manual markers per CMMN 1.1.
- Accessibility is a requirement, not a polish step: every shape keeps an accessible name, keyboard focus and a visible focus ring, meaning is never carried by colour or shape alone, and contrast meets WCAG 2.2 AA.
- A toggle keeps the current card view for dense workflows; the choice is remembered per person.
- The run diagram uses the same shapes so a request's path reads in the same notation.

**Plan.** (1) shape components and the mapping, behind the toggle; (2) handles and edge routing for diamonds and circles; (3) run diagram and legend; (4) CMMN case canvas notation; (5) tests, axe and a visual check on the example workflows.

**Acceptance.** Every step kind renders its mapped shape on the Studio canvas and the run diagram; connection handles work for every shape; axe reports no serious violation; existing canvas tests pass; a legend explains the symbols.

**Risks.** Edge routing around diamonds and circles; label fit in small shapes; the cost of re-laying out saved diagrams (shapes keep the same bounding box so saved positions stay valid).

## 2. STU02 - Conversational authoring panel

**Today.** "Describe it" (S10) drafts a workflow once, with validation and human review. The Assistant (`features/conversation`) helps requesters start and track requests. The form assistant (S90) suggests field changes as a reviewable diff. Nothing lets an author regenerate, add or modify an existing draft by conversation.

**Gap.** After the first draft the author edits by hand; there is no "add a finance step if the amount is over 5,000" or "make the second approval optional".

**Design.**
- A collapsible panel on the **left** of the Studio, next to the step palette, with a multi-turn conversation. Each turn proposes changes to the **current draft** as a reviewable list (add, change, remove, reconnect), never applied without an explicit click, and applied as one undoable edit.
- The model receives a bounded, redacted view of the draft and the last few turns; it returns structured edit operations, not free text. Every operation is applied to a candidate copy and run through the real compiler. An edit that adds a blocker is rejected with the compiler's reason and is not offered.
- Reuse the S10 and S90 infrastructure: profiles and budgets, prompt fencing, deadline, metering. No prompt, response or draft text in logs or audit, only counts. `AI_NOT_CONFIGURED` hides the panel and says why (this also closes the "AI controls vanish silently" finding from the rehearsal).
- Authors with `workflow.edit` only; read-only drafts show nothing.
- The first message can also start from nothing (replacing the one-shot dialog as the entry point, while keeping it as a shortcut).

**Plan.** (1) backend edit-operation schema and endpoint with compiler validation; (2) the left panel UI with history, diff preview and undo; (3) regenerate-from-scratch with confirmation; (4) tests with the existing stub provider (no network); (5) accessibility and keyboard use.

**Acceptance.** From an existing draft a user can add, modify and remove steps by conversation; every proposal is validated; nothing is applied without a click; undo reverses a whole applied turn; no content in logs.

**Risks.** Quality of structured edits from the model (mitigated by validation and review, and by evaluating against a fixed set of instructions); token cost of large drafts (bounded view).

## 3. STU03 - Test tab, mock data and integration points

**Today.** The Studio has a "Test this workflow" panel (`features/studio/test`) opened by a header button: a dry-run simulation of the saved draft with scenario settings per step, saved cases kept per person, and shared test suites (`TestSuiteService`). The decision inspector can test one rule. Nothing is started, called or stored in a dry run.

**Gap.** It is not a tab, so it is easy to miss. Calls to systems, adapters and agents cannot be given mock responses, so a whole workflow with integrations cannot be tested with realistic data. Cases assert only the outcome, not outputs or the path taken.

**Design.**
- A **Test** tab beside Canvas, Steps, Lanes and Source, always visible to everyone who can read the workflow (editing needs `workflow.edit`), holding the current panel plus the new capabilities. The header button stays as a shortcut.
- **Mock data for integration points:** for each call, adapter send, external worker, agent and message wait, a case can define the response to use (status, body, delay or failure), with a library of samples taken from the call's own response schema. The simulation uses the mock and never makes a real call; the same rule already holds for the stub transports.
- **Scenarios** combine request data, mocks, decision inputs and approver choices; they can be saved, shared with the team as a suite, imported and exported.
- **Assertions** on the final outcome, on output values, on the path taken (steps visited in order) and on rules fired, with a readable diff when one fails.
- **Run the suite** in one action with a pass or fail summary; optionally show the result next to the publish check (a policy choice, off by default).
- Decision tables: "test this table" with a grid of inputs and expected results (reusing the CSV format from S89 for bulk cases).

**Plan.** (1) the tab and layout; (2) mock model in the scenario schema and the simulator; (3) assertions and the results view; (4) suites and the decision-table grid; (5) tests.

**Acceptance.** The tab is visible to readers; a scenario with mocked calls runs the whole workflow with no real call; failed assertions say what differed; suites run in one action; existing saved cases keep working.

**Risks.** The simulator must stay faithful to the runtime (mitigated by running the same compiled definition and by cross-checking examples against real runs in the existing runtime tests).

## 4. STU04 - Export to BPMN and CMMN

**Today.** The BPMN source tab shows the compiled BPMN, accepts exact edits and checks the round trip (S60, S77). Case models can be previewed and downloaded as XML (S81). There is no "download .bpmn" action, and the generated BPMN includes diagram positions only when every step has a canvas position.

**Gap.** Users want to open the workflow in another tool (a BPMN modeller) and hand a file to a colleague.

**Design.** A **Download BPMN (.bpmn)** action on the Source tab and the workflow menu, for a draft or a published release, with a complete BPMN diagram interchange section (shapes and edges with bounds, so the file opens laid out) and correct names and ids. Orqix-specific settings that BPMN cannot express are carried as documented extension elements and listed in a header comment, so nothing is silently lost. For case models the existing CMMN download is kept and linked from the same menu. Permissions: `workflow.export` for releases, `workflow.read` for drafts, audited as an export.

**Plan.** (1) download endpoint and UI; (2) diagram interchange for every shape (depends on STU01 sizes); (3) round-trip tests with a standard BPMN parser; (4) a statement of what is and is not preserved.

**Acceptance.** The downloaded file parses with a standard BPMN library, keeps the layout, and re-importing it through the Camunda importer reports only the documented Orqix extensions.

## 5. EX10 - Resume and poll API for waiting requests

**Today.** An operator can `resumeRun` a suspended request. A request can wait for a message (`sendMessage`), a signal (`sendRunSignal`) or an external worker job, and external workers can lease, complete and fail jobs and await callbacks. Inbound webhooks start new requests (S47). A caller must know the right internal operation and have a user session or a worker grant.

**Gap.** An outside system that a request is waiting for has no single, simple contract: "this request is waiting for you; poll its state; resume it with this result".

**Design.**
- A **callback token** issued when a request reaches a wait that an outside system will answer. It is scoped to one request and one wait, expires, is shown once, stored only as a hash, and is revocable.
- **Poll:** `GET` the wait's state with the token (waiting, resumed, expired, cancelled) and a bounded, versioned ETag so polling is cheap.
- **Resume:** `POST` a bounded, schema-checked result with the token and an idempotency key; one resume per wait, later ones answered with the original result. Mapped to the existing message and signal machinery so no second path into the engine exists.
- Decide explicitly how this relates to the operator `resumeRun` (suspension) so the two are never confused in the API and UI.
- Audit: token created, resumed, expired, revoked, with counts and ids only.

**Plan.** (1) model and migration; (2) issue, poll and resume endpoints and the engine mapping; (3) Studio: a "Wait for an external system" step option showing the contract; (4) tests including replay, expiry and cross-request misuse.

**Acceptance.** An external client can poll and resume a waiting request using only the token; a stolen token cannot touch another request; replays are safe; expiry works.

## 6. IN05 - Webhook and Kafka triggers

**Today.** A signed inbound webhook records an event and a person with `workflow.start` starts the run (S47, S73); the HMAC, timestamp window, deduplication and pending cap are in place. The adapters can send to Kafka, a database, email or Teams, but only through a local stub transport. IN02 deliberately left out any consumer: "a customer relay has to produce the signed POST".

**Gap.** No Kafka consumer, no webhook that resumes a waiting request (EX10), and no outbound webhooks for request events.

**Design.**
- **Webhook to resume:** the signed inbound endpoint can carry a callback token (EX10) to resume a wait instead of recording a start event.
- **Outbound webhooks:** a workspace subscribes an HTTPS endpoint to request events (started, task opened, completed, needs attention). Signed deliveries, retries with backoff, a dead-letter list the operator can see and replay, egress allow-list, bounded payloads that carry ids and states and not request data by default.
- **Kafka consumer trigger:** a configured topic (allow-listed, per workspace) from which messages either start a request (mapped fields) or resume a wait (correlation key). Offsets are committed only after the message is durably recorded; duplicates are dropped by key; malformed or unmapped messages go to a dead-letter record and never stop the consumer; throughput and lag are bounded and visible. Credentials come from secret references only.
- A local, no-live-system mode for the demo and tests (an embedded or container Kafka for tests, stub transport in the demo), consistent with the stance in the release record.
- Security: message content is data and never changes routing; sizes and rates are capped; the same pending cap and audit as the webhook path.

**Plan.** Design and decisions first (consumer library and licence, offset and dead-letter model, ordering and retries, multi-replica behaviour: one consumer per partition group), then (1) webhook resume and outbound webhooks; (2) Kafka consumer with a test container; (3) operator UI for lag and dead letters; (4) tests and a fail-over check with two replicas.

**Acceptance.** A message on an allowed topic starts or resumes the right request exactly once, including after a restart and with two replicas; bad messages are dead-lettered and visible; outbound deliveries retry and can be replayed; nothing leaves the egress allow-list.

**Risks.** Exactly-once semantics (we target at-least-once delivery with idempotent handling); a new dependency (licence and security review before adding); operational surface (lag, rebalancing).

## 7. STD01 - Standards conformance (BPMN, CMMN, DMN, UML)

**Position.** The standards for process, case and decision modelling are **BPMN 2.0, CMMN 1.1 and DMN** (all OMG). **UML** is a different OMG standard for software modelling; UML activity diagrams overlap with BPMN conceptually, but BPMN is the industry standard for executable process notation and is what Flowable executes. This product follows BPMN, CMMN and DMN, not UML, and says so.

| Standard | What is followed | What is not (yet) |
|---|---|---|
| BPMN 2.0 execution | generated BPMN is valid and runs on Flowable; round trip checked (S60, S77); Camunda 7 and 8 files import | not all BPMN elements are admitted (unsupported ones are reported on import); Orqix settings use extension attributes |
| BPMN 2.0 notation | not on the canvas today; the BPMN source view is standard | STU01 delivers the notation; STU04 delivers diagram interchange |
| CMMN 1.1 | a validated subset runs on Flowable's CMMN engine; exact XML export (S81) | full import, discretionary planning and conformance (WM04) |
| DMN | decision tables with hit policies; DMN import; CSV (S89) | not full DMN decision services or boxed expressions |
| UML | not followed | an activity-diagram view could be generated from a workflow if wanted; it would be a separate, optional export |

Conformance is claimed per row, for the admitted subset only, and never as certification.

## 8. Cross-cutting rules for all items

- Permissions and audit follow the existing model; any new permission is grant-only unless clearly role-appropriate.
- No existing migration is edited, not even a comment; new migrations use the next free numbers.
- Tests run on PostgreSQL and Flowable where the runtime is involved, with a real-browser check and accessibility scan for each UI change.
- Each item gets a build note in `docs/build/`, a register row, and an honest list of what is excluded.
