# S77 - BPMN source: sections, handlers, error/signal/undo steps, call-failure errors and adapter steps (UI03)

[S60](S60-bpmn-source.md) and [S70](S70-bpmn-defaults-and-examples.md) made a draft BPMN-editable only when the round trip document -> BPMN -> document is identical. Drafts that used the constructs added later ([S59](S59-subprocesses-errors-signals-compensation.md) sections, handlers, raise error, send/wait for signal, undo; [S71](S71-scope-frames-and-call-failure-errors.md) `raiseErrorOnFailure`; [S73](S73-adapter-step-inbound-events.md) `sendAdapter`) were always BPMN read-only, because the BPMN reader did not read them. This slice makes the reader the exact inverse of what the generator already writes for them.

Nothing in the compiled output changed: no generator change, no new `orqix:*` extension, no compiler version bump, golden files unchanged (`GoldenFilesTest`, `GeneratedXmlTest` pass). No migration (V115-V117 unused). Permission boundaries are those of S60. [S75](S75-approved-agent-write-tools.md) added no step kind (agent write tools are part of the connection definition), so there is nothing new for BPMN there.

## What is now read back exactly

| Construct | BPMN the generator writes (unchanged) | Read back as |
|---|---|---|
| Section (`subprocesses[]`, `type: embedded`) | `subProcess` with `orqix:scope kind="embedded"`, generated `<S>__start`, `<S>__begin`, one `<S>__end__<step>` per exit, exit flow or `<S>__exit` gateway; time limit `<S>__timeout` (`orqix:scopeTimeout after`) + `<S>__onTimeout`; error route `<S>__error` (`orqix:scopeError errorCode?`) + `<S>__onError` | `id`, `label`, `type`, `nodes` (the steps inside the element), `timeoutAfter`, `onTimeout`, `onError {to, errorCode?}`; a path to the section is a path to its first step, a path to `<S>__end__X` is a path to `X` |
| Handler (`type: event`) | `subProcess triggeredByEvent="true"` with `orqix:scope kind="event"`; start event `<S>__start` with `orqix:scopeEvent trigger interrupting` and the error/signal/timer definition; `<S>__end` and `<S>__done__<step>` for a handler that runs alongside | `trigger {type, errorCode? / signal / after}`, `interrupting` |
| Raise an error | error end event with `orqix:raise errorCode` | `raiseError {errorCode}` |
| Send / wait for a signal | `intermediateThrowEvent` / `intermediateCatchEvent` with `orqix:signal action="send"/"wait" name` | `sendSignal` / `waitForSignal {signal}` |
| Undo completed calls | service task `${orqixCompensationTask}` with `orqix:compensation` | `compensate {}` |
| Automatic undo call | `isForCompensation="true"` on the call | `http.compensation: "automatic"` |
| Call failure as section error | `orqix:http raiseOnFailure` | `http.raiseErrorOnFailure` |
| Send through an adapter | service task `${orqixAdapterTask}` with `orqix:adapter adapter kind topic? <member>Base64`, `orqix:to address`, `orqix:input` | `sendAdapter {adapter, kind, to?, subject?, body?, title?, text?, topic?, key?, inputMapping}` |

Error and signal definitions under `definitions` and the `errorRef`/`signalRef` of the events are not read: they are derived, and the regenerated BPMN is compared with the submitted one, so an inconsistent edit (for example a raise step's code changed but not the error definition and the section's error route) is refused with the element and the value the document would write. Renaming the code everywhere it appears is accepted.

`subprocesses` moved from "kept from the draft" to the sections BPMN carries; applying BPMN without sections removes them.

## Order of steps and connections

The process body puts the elements of each section and handler inside its `subProcess`, so the body alone loses the document order. The diagram does not: the generator writes one shape per step and one edge per connection in document order. The reader merges the body sequences (process first, then each subprocess, each keeping its own order) in diagram order, which gives the document order back exactly. Without a diagram (some step has no canvas position) the body order is used; a draft that lists its steps or connections differently then does not round-trip and the view says so in one line each, with the reason ("without a diagram ... BPMN lists the steps of each section and handler after the others. Give every step a canvas position ..."), instead of one difference per index. Studio drafts have a position for every step.

## Decisions taken (no user input was available)

1. **The order in which a section or handler lists its steps is kept from the draft.** BPMN cannot show it, and nothing depends on it: the section's entry step must be unique and is found from the paths (`Graph.entry`), the generator and validation do not use the list order, and the runtime only uses it to list members on the run path. The studio stores members in the order they were ticked, so without this most studio sections would stay read-only. Like field titles (S70), the draft's order is kept for steps that are still members; steps added to a section in BPMN follow in document order. The view lists it ("the step order of section 'review'") under "Kept from the draft and not in BPMN" when it differs from document order.
2. **`subprocesses[].interrupting: false` is a documented default** (added to `BpmnDefaults`, now ten entries): the schema allows leaving it out, the model reader uses false, the generator writes a handler's flag either way and never uses it for a section, and the studio shows an absent flag as unchecked. `interrupting: true` is carried.
3. **The undo calls an Undo step lists (`orqix:undo`) are recomputed**, like generated helper names: they are derived from the calls marked as automatic undo calls, so a stale list is a note, not a refusal.
4. **Section and handler frames in the diagram are recomputed** from the step positions (notes), like other helper shapes.
5. **Adapter steps are BPMN-editable.** S73 had left them read-only. Applying BPMN compiles the result with the workspace's compile context (the same as Check workflow), so recipients and topics must still be on the adapter's allowlist (`WF_ADAPTER_RECIPIENT`, tested: a recipient in another domain is refused as a blocker), kinds must match, and placeholders are checked. Message texts are Base64 in the BPMN as before.
6. **Generated wiring stays strict.** As for approval helpers in S60, the generated parts of a section (start/end events, the exit gateway and its conditions, `markExit` listeners, boundary events) must be present and exactly as the document would write them; adding an exit in BPMN means writing those parts too. Anything else is refused with the element named.

## What still cannot be applied from BPMN (listed on screen and in `roundTrip.differences`)

- `"subprocesses": []`: not the same as no subprocesses (release moves are refused for any document that has the member, `ReleaseCompatibility`), and BPMN cannot show an empty list. Reported at `/subprocesses`. The studio never writes it.
- A draft without a diagram whose steps or connections are not listed with each section's and handler's last (see above).
- Unchanged from S70: `startWorkflow` `repeat`, `forEach`, `collectionMapping` and approval `separateFrom`, `correctable`, `dispatch`; also approval `notifications` (not examined before; the generator does not write it, so a draft using it reports it at `/nodes/<i>/config/notifications`). Making these editable needs new `orqix:*` output and a compiler version bump: a decision for the user, not taken here.
- Drafts with blockers (no BPMN at all).

## Implementation

- Compiler: `BpmnReader` (walks the process and each `subProcess` with `orqix:scope`; reads scopes, routes and triggers; new step kinds; diagram-order merge; scope helpers recognised; nested scopes and plain sub-processes refused with reasons), `BpmnCompare` (children of a `subProcess` keyed by id; recomputed patterns allow nesting; `orqix:undo` and scope frames recomputed), `BpmnDefaults` (`adoptScopes`, `keptMemberOrder`, the interrupting default), `BpmnSource` (`subprocesses` carried, merged, compared and listed in changes; one-line order differences). `BpmnGenerator` is unchanged.
- Application: no code change; `BpmnSourceApiTest` extended. Contract: the `getDraftBpmn` description now names sections and handlers (`schema.d.ts` regenerated, text only).
- Frontend: the BPMN view's sentence names sections and handlers; a test for the read-only reasons. The mock double is unchanged (it only applies name edits).

## Verification - 19 October 2026

JDK 21; Docker/Testcontainers PostgreSQL; logs in `evidence/s77/` (git-ignored).

- New compiler test `BpmnSourceScopesTest` (9): each construct (section with time limit, error route, raised error and two exits; signal and timer handlers; Undo step with automatic undo call; call failure raised as section error; send/wait for signal; email, Teams, Kafka and JDBC adapter steps; a section plus two handlers) round-trips identically, its own BPMN applies with no changes and no notes, the applied draft is canonically identical and regenerates byte-identical BPMN; section member order kept and listed; `interrupting` omitted or written false is identical and keeps its spelling, true is carried; `subprocesses: []` reported at `/subprocesses`; without a diagram one order line each for steps and connections, and a draft already in body order round-trips; `separateFrom` and approval `notifications` still reported at their paths; accepted edits (section label and time limit, handler timer, adapter subject, raised error code renamed everywhere) and refusals (half-renamed code, unknown scope helper, plain sub-process, nested section, removed `markExit` listener, path into a handler, unknown trigger, adapter recipient outside the allowlist as blocker `WF_ADAPTER_RECIPIENT`). `BpmnSourceTest` (20) passes with the default list now ten entries; the example test still finds 17 identical and the same four differing documents.
- Mutation checks: with `adoptScopes` disabled 7 of 9 new tests fail (`mutation-no-adopt-scopes.log`); with the diagram order ignored 7 of 9 fail (`mutation-no-diagram-order.log`).
- `BpmnSourceApiTest` (5, one new: `sectionsHandlersAndErrorStepsAreBpmnEditable`): a saved draft with a section, a call with `raiseErrorOnFailure`, a signal handler and a raised-error route round-trips for a viewer; keptFromDraft lists the member order, not `subprocesses`; viewer check/apply 403; outsiders 404 on read and apply; apply without If-Match 428; an edited section label is applied and everything else of the subprocesses (including the draft's member order) is unchanged; a script task inside the section is refused 422 naming `s1`. `SecurityMatrixTest` (3) passes (`app-focused-1.log`).
- Full backend `clean verify` (`backend-verify.log`): BUILD SUCCESS - compiler 524 tests, application 869 (865 pass, the same four intentional skips), coverage gate and packaging. One assertion (approval `notifications`) was added to `BpmnSourceScopesTest` after that run had compiled the tests; it first failed because the fixture's overdue e-mail had no supervisor/operator recipient (`WF_MAIL_ESCALATION_REQUIRED`, a fixture error), and after fixing the fixture the class passes (`compiler-focused-5.log`: 29 of 29 with `BpmnSourceTest`). The full verify was not repeated for that test-only change.
- Frontend: full `vitest run` run 1 had 930 of 932 pass; both failures were in `ai.test.tsx` (a 15 s timeout and a follow-on assertion) while another session's Java process was running; the file passes alone (11/11, `frontend-rerun-ai.log`) and full run 2 passed 932 of 932 in 125 files (`frontend-full-2.log`). `tsc -b --noEmit`, ESLint on the changed files and `api:check` pass (`frontend-static.log`); `tools/contract-check/contract_check.py` PASS, 88 checks.
- Not run: browser journeys, Docker stack, mock-mode bundle, image refresh.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=BpmnSourceScopesTest,BpmnSourceTest,BpmnSourceApiTest -Dsurefire.failIfNoSpecifiedTests=false test` (JDK 21); from `frontend`: `npx vitest run src/features/studio/BpmnSourceView.test.tsx`.
