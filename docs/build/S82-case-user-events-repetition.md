# S82 - Native case user events and sequential human-task repetition

23 October 2026. This work continues the full original non-infrastructure objective retained in [S81](S81-full-non-infrastructure-completion.md). It does not close full CMMN (WM04), the general application designer (UI02), or narrow their acceptance.

## Implemented behavior

Native `userEventListener` definitions and standard `caseRoles/role/authorizedRoleRefs` are admitted with explicit authorization. A role name maps to an existing workspace group key. Every event needs one to ten resolved authorized roles; those groups are checked during definition publication. Current task permission and current group membership are checked again on each occurrence and replay. Role references cannot address tasks, roles outside the case or unresolved identifiers. No browser script, beans, listener classes or arbitrary expressions are accepted.

An available event exposes a reason-confirmed Trigger event action in the case plan. Its occurrence goes through the same workspace/case lock, expected version, idempotency and protected history boundary as existing case commands, then calls the native engine operation. The native occur transition evaluates sentries and can release a human task or milestone. A held case refuses new events. A losing concurrent event cannot create duplicate downstream work. Event-role eligibility grants case-plan metadata visibility while the listener is available; it does not grant protected data reads or corrections. The existing read-access hint handles loss of the metadata grant after the event fires.

Human tasks support a finite sequential repetition rule with 2 to 30 executions, including manual activation and required work. Each execution has a distinct engine plan-item/task identity. Retries do not advance the counter. Conditions use only the allowlisted native `repetitionCounter < N` expression; there is no caller-supplied executable expression language. The visual designer authors execution counts and user events, resolves workspace groups, generates stable role IDs and occurrence sentries, and preserves native CMMN controls-before-criteria order. Entry-conditioned tasks use one execution in this delivery. Each completed occurrence emits its own completion event; a sentry attached to that task observes each occurrence, not an implicit final-only aggregate.

V114 extends the append-only command response operation constraint for the new event command. It adds no new runtime-role write grants. Existing command/history/audit protections remain in place; reasons stay outside audit metadata.

## Standard and pinned-engine boundaries

The normative target remains [OMG CMMN 1.1](https://www.omg.org/spec/CMMN/1.1/PDF), notably event listeners, PlanItem criteria/control rules and repetition in 5.4.11.3. Listener entry/exit criteria and required/manual/repetition controls are refused according to those rules. The [Flowable guide](https://www.flowable.com/open-source/docs/cmmn/ch06-cmmn) documents native user-event operations and repetition; implementation evidence comes from the pinned 8.0.0 API and actual execution tests.

Two pinned-engine limitations are deliberately visible:

- Subsequent required manual repetitions can be reported as completable by the engine. Case close and stage completion therefore also check live required plan items from the pinned definition, excluding terminal states. Containers with repeated work must explicitly use `autoComplete=false`, preventing unchecked automatic container completion.
- The pinned engine's termination operation does not evaluate repetition. Individual cancellation of a repeated item is withheld by both allowed-actions metadata and the command boundary. Whole-case termination remains available and ends all work. Full completion/termination repetition conformance remains open; no extra ad hoc repetition engine is introduced.

Repeated stages/milestones, event-conditioned repetition, general expressions, timers, native suspension, planning, nested cases, case-file triggers and full exchange/notation/conformance are not delivered here and remain required under WM04. These are implementation gaps, not scope exclusions.

## Verification

Final local verification is recorded below. The initial engine run exposed XML ordering and missing command-operation admission; both are corrected. Native execution also exposed the two engine limitations above; tests exercise the actual supported boundary rather than assuming engine behavior from documentation.


## Executed acceptance

- Broad selected backend `verify`: 35/35 pass across case API/data/lifecycle, event/repetition rules and execution, architecture and the generated security matrix, with coverage and packaging. The final nested-stage regression was added afterward; a separate 8/8 rerun of `CaseEventApiTest` and `CmmnEventRulesTest` passes, including that fifth execution scenario. This is 36 unique selected tests, not a fresh full backend/compiler run.
- Full frontend: 959/959 across 133 files. Frontend/E2E TypeScript, full lint, API drift, and 88 contract checks pass. The production and embedded frontend/backend image builds pass.
- Real Edge: role-separated user-event authoring/occurrence/task activation/completion/history and repeated-task authoring/sequential activation pass (2/2). Desktop authoring and mobile work/history accessibility pass. The shared browser fixture reports no console errors, uncaught exceptions or CSP violations.
- Restart: after two of three required task executions, the application container alone is restarted. Readiness recovers; a fresh signed-in worker sees case version 5, executes the remaining task, and completes at version 7. A requester read confirms exactly three completed task occurrences and seven retained history entries (1/1 post-restart journey). The post-restart test is explicitly opt-in; a normal browser run skips it rather than implying a restart occurred.
- PostgreSQL: migration 114 is current. The application role cannot UPDATE or DELETE command-response snapshots or case-event history.

The source-identified local image is `orqix/orqix:case-events-20261023`, SHA-256 `ee412d07cf36bf3d99c53816e3c318cf492edfec2ae7ef358104c9ca23f90d3a`. Current source, manifest and image label match. A 38,131,270-byte custom-format database backup precedes migration/refresh. Dependencies, volumes and infrastructure topology are retained. The final application restart keeps the same image and data; production infrastructure/customer qualification is not claimed.

Evidence: `evidence/case-events-backend.log`, `case-events-nested-guard.log`, `case-events-frontend.log`, `case-events-types.log`, `case-events-e2e-types.log`, `case-events-lint.log`, `case-events-api.log`, `case-events-contracts.log`, `case-events-browser-before-restart.log`, `case-events-application-restart.log`, `case-events-browser-after-restart.log`, image build/rollout manifests and `case-events-source-attestation.json`.

WM04h is verified within this native user-event/finite sequential human-task scope. Full WM04 and UI02 remain open under the original acceptance. In particular, the pinned-engine termination/completion issues above are not a claim of full repetition conformance.
