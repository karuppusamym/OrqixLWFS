# S68 - Adapter workflow step, received-event UI, adapter/trigger editing and event retention (IN02 follow-up)

Extends [S47](S47-adapters-inbound-triggers.md). Everything still delivers only to the `LocalStubTransport`: no SMTP, Microsoft Graph/Teams, JDBC driver or Kafka client is linked, and `secretRef` contents are never read by an adapter.

## "Send through an adapter" step (`sendAdapter`)

Config (`contracts/workflow-v0.schema.json`, `sendAdapterNode`): `adapter` (key), `kind` (email, teams, kafka, jdbc), and per kind `to` + `subject` + `body` (email), `text` + optional `title` (teams), `topic` + optional `key` (kafka), nothing extra (jdbc); `inputMapping` names request or earlier-step values. Text members may use `{{name}}` placeholders for mapped names only (`AdapterMessages`). The Kafka value is the mapped values; database parameters are the mapped values.

Decisions:

- **Recipients and topics are literals of the step, never request data.** `to` holds fixed addresses (no placeholders, no field references); `topic` is a fixed name. Both must be on the adapter's allowlist at compile time (`WF_ADAPTER_RECIPIENT`, `WF_ADAPTER_TOPIC`) and again at every send. An adapter being available never authorizes a recipient or topic by itself; a wider allowlist adds nothing to existing steps.
- The step states the adapter's kind; a different kind is `WF_ADAPTER_KIND`, so a step is never silently re-pointed. Unknown/disabled adapters are `WF_ADAPTER_UNKNOWN`/`WF_ADAPTER_DISABLED`; members of another kind, missing members and line breaks in subject/title are `WF_ADAPTER_MESSAGE`; unmapped or malformed placeholders `WF_ADAPTER_PLACEHOLDER`; database steps must map exactly the declared parameters (`WF_ADAPTER_PARAMETERS`); mapped values follow the normal availability rules and must be required single values (`WF_ADAPTER_INPUT_UNKNOWN/TYPE/OPTIONAL`).
- Outputs: `outcome` (text; `stubbed` in this build, `confirmed` after an operator confirmed an unknown send) and, for jdbc, `rowCount` (0 from the stub).
- Compile context: `CompileContext.adapters` (new component, old constructors kept) filled by `CompileContextFactory` from the new integration port `AdapterSteps`. Publishing pins no adapter version; the runtime reads the adapter again for every attempt.
- Runtime: async service task `${orqixAdapterTask}` (`AdapterTaskDelegate`, added to the engine expression allow-list) using the existing effect journal (`EffectJournal.begin/finish`, same effect key as HTTP steps). `connection_key` = adapter key, `connection_version` = adapter version when the effect was first registered, `operation` = `email.send`/`teams.post`/`kafka.produce`/`jdbc.read`, `effect_class` = `readOnly` for jdbc, `nonIdempotent` otherwise. Missing/disabled/other-kind adapter or a message the current allowlist refuses is recorded as `permanentFailure` / `CONNECTION_UNAVAILABLE` (attention; an operator retry re-checks). A recorded success is reused, never resent. An email/Teams/Kafka attempt without a recorded outcome becomes `unknown` and is never repeated automatically (operator reconciliation as for HTTP effects). The journal stores `{outcome, detail}` from the stub: no message, subject or recipient. Line breaks in mapped values are replaced by spaces in single-line members.
- Run view: current-step kind `adapter` (contract enum extended), shown as running on the run path; success is an `EFFECT_SUCCEEDED` event with `adapterKey`, `adapterKind`, `attempt`, `outcome` only (so incident correction treats it as having consumed input). Timeline text says the local test stub accepted it and nothing left Orqix.
- Simulation (server and mock): describes the message, sends nothing, `rowCount` 0.
- Not allowed inside sections or handlers, and added to the not-interruptible kinds (a send may be in flight). BPMN source view: adapter steps make the draft BPMN read-only with a named reason. No failed route.
- `SchemaValidation.BRANCH_ORDER` now also lists the S59 kinds (raiseError, sendSignal, waitForSignal, compensate) and `sendAdapter`; before, schema errors inside those step kinds were dropped instead of reported.

Studio: palette entry, `SendAdapterInspector` (adapter chooser from `GET /adapters` when the designer has `connection.read`, otherwise a typed key; recipients checked against the approved domains; Kafka topics chosen from the allowlist; jdbc parameters fixed to the declared names; placeholder hints), field references and summaries.

## Editing, disabling and retention

Migration `V96__adapter_lifecycle.sql`: `version`, `updated_by`, `updated_at` on `adapter_config` and `inbound_trigger`; partial index for the pending-event count; `UPDATE` grants on both tables and `DELETE` on `inbound_event`.

- `PUT /adapters/{adapterKey}` and `PUT /inbound-triggers/{triggerId}` (fresh `connection.manage`; audit `adapter.updated`, `inbound.trigger-updated` with key/kind or source, version, enabled). Kind, key, trigger source and target workflow are immutable. `expectedVersion` must match, otherwise 409 `CONFIGURATION_VERSION_CONFLICT` (new problem code). Validation is identical to creation. A disabled trigger answers 404 to senders; its received events stay startable.
- `POST /inbound-events/purge` (fresh `connection.manage`; audit `inbound.events-purged` with olderThanDays, includeUnstarted, deleted): deletes events received more than 1-3650 days ago; started events always, unstarted ones only with `includeUnstarted`. Returns a count only. A purged event id is accepted again as a new event (deduplication lasts as long as the event is kept). No automatic schedule.
- UI: Settings > Adapters panel has Edit (with enabled toggle) for adapters and triggers, and a retention purge form. My requests shows a "Received events" panel (only when there are events and the person can start workflows) listing events with mapped values and a "Start request" action that calls the existing start API as the signed-in person.

## Evidence - 18 October 2026

All runs in this worktree (JDK 21, Testcontainers PostgreSQL/Flowable). Logs in `evidence/s68/` (git-ignored).

- Full clean `backend/mvnw.cmd -f backend/pom.xml clean verify` (`backend-verify-2.log`): BUILD SUCCESS - compiler 501 cases, application 800 cases (796 pass, the same four intentional skips), coverage gate and packaging. `ArchitectureTest` (6) and `SecurityMatrixTest` (3) pass in this worktree; the three pre-existing failures reported for the other session's CMMN work did not occur here.
- An earlier full run (`backend-verify.log`) failed only `GracefulShutdownTest` (2) while the frontend suite ran on the same machine; the class passes alone (`graceful-rerun.log`) and in the second full run. Recorded as load-sensitive, not as fixed.
- New backend cases: `AdapterStepRulesTest` (6, compiler: BPMN, allowlisted recipients/topics, no request-data recipients, kind/unknown/disabled, placeholders, jdbc parameters and typed `rowCount`, simulation); `AdapterStepTest` (4, PostgreSQL/Flowable: journaled stub delivery with the exact payload digest, outputs feeding a later step, nothing of the message in journal or events, reuse instead of resend, an in-flight send with an expired lease becomes `unknown` and is not resent, disabled adapter -> attention -> re-enable -> operator retry sends once, narrowed allowlist stops later sends and new publishes, unknown/wrong-kind adapters blocked at validation); `AdapterInboundApiTest` +3 (edit/disable with version conflicts and permissions, trigger disable/edit, **1,000-pending cap**: 1,000 signed events through the real endpoint are accepted, the 1,001st is 429 with `Retry-After: 60`, duplicates are still acknowledged when full, the cap is per trigger, starting one event frees one place; retention purge permissions, started-only vs. `includeUnstarted`, run untouched, purged id accepted again). The existing S47 event-list assertion was narrowed to its own trigger because other tests now share the workspace's events.
- Frontend: full `vitest run` 887 passed in 116 files (`frontend-full-2.log`); `tsc -b --noEmit`, `eslint .` and `api:check` clean. New: `SendAdapterInspector.test.tsx` (4), `InboundEventsPanel.test.tsx` (4), `AdaptersPanel.test.tsx` +2 (edit/version conflict, trigger edit and purge), one timeline case. A first full run during the backend build had 5 timeouts in four existing Studio/AI files; they pass when the machine is idle (`frontend-rerun4-idle.log`) and in the full rerun.
- `tools/contract-check/contract_check.py`: PASS, 88 checks.
- Not run: browser journeys (mock or real), Docker stack, image refresh.

## Not included

- Any live transport or reading of `secretRef` contents; live acceptance against real mail, Teams, brokers and databases remains external (AC02).
- Recipients or topics taken from request data, CC/BCC, attachments, Teams cards, Kafka headers, real database rows (the stub returns none) or result-row mapping.
- A failed route for adapter steps, adapter steps inside sections/handlers, BPMN editing of adapter steps, pinning an adapter version at publish, adapter deletion.
- Scheduled/automatic retention, per-sender rate limiting beyond the 1,000-unstarted-event cap per trigger, editing a trigger's source or target workflow, re-mapping already received events.
- Browser journeys, Docker rollout and real-stack runs for this slice.
