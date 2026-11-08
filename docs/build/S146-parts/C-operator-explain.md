## Part C - "Explain this request" for operators (CG-A07, doc 29 section 7.12)

Branch `claude/s146-operator-explain`, base 245d172c. Flyway V394 only (V395 unused).

### What is delivered

- **API** `POST /workspaces/{workspaceId}/runs/{runId}/explain` (operation `explainRun`, tag `Completion`,
  `x-orqix-permission: run.operate`, `x-orqix-audit: ai.run.explained`). Optional body `{preferRules}`. Returns
  `RunExplanation`: `source` (`ai` or `rules`), `summary`, `cause`, `evidence` (fact ids), `suggestedActions`
  (`{actionId, why}`), `facts`, `permittedActions`, `droppedActions`, `fallbackReason`, `ai` (profile, model,
  promptVersion, cached), `readOnly: true`, `stateDigest`, `generatedAt`.
- **Facts builder** `runtime.explain.RunFacts` (pure Java). Reads the run detail as built for the caller (current
  steps, attention, open tasks, effects, children, suspension, allowed actions), the newest 200 timeline entries, the
  open waits with their checks (S129/S139), the binding state of the connections the request uses (binding present or
  enabled, circuit state and pause, token-refresh failures in the last hour, last failure of the day; S131/S132) and
  the compiled model of the release (type, connection and operation, timeouts, due times, wait durations, retry
  attempts, routes of the stuck and current steps). Output: at most 40 facts `F1...` (12 at most from the timeline,
  each linked to its timeline `seq`), and the trailing failure streak of the stuck step ("has failed 3 time(s) in a
  row with HTTP_403 since 2026-11-08 09:12 UTC").
- **Permitted actions** computed for the caller from the same checks the actions use: `retryRun`,
  `reconcileEffect:<effectId>`, `resumeRun`, `cancelRun` (from `RunDetail.allowedActions`, which applies
  `run.operate`, lifecycle, suspension and effect state), `correctRunInput` (needs attention, request data visible,
  no effect captured, at least one `incidentEditable` field; the description names the field names only),
  `repairRun` (attention `RUN_WAITING_ON_NOTHING`), `checkWaitNow|extendWaitDeadline|stopWaiting:<waitId>` (open
  checks with `canOperate`), `reassignTask:<taskId>` (up to three open tasks), and for a connection whose binding or
  credential is the likely cause either `updateConnectionBinding:<key>` (kind `open`, caller holds
  `connection.manage`) or `askAdministrator:connection:<key>` (kind `askAdministrator`, no operation id: "someone with
  connection.manage must ... authorize it again after a token rotation").
- **Rules** `runtime.explain.RuleExplainer` (pure Java): ended, suspended, needs attention (credential refused 401/403
  or token refresh failing, unusable binding, unknown outcome, retries exhausted with or without an open circuit,
  rejected call, connection unavailable, waiting on nothing, generic), then running/waiting (paused circuit, failing
  check, overdue task, plain wait by kind). Parallel waits are added to the summary ("Meanwhile step "Cool-off period"
  waits for a timer until ..., which is expected."). Every cited id is a fact id; every suggestion is a permitted
  action (others are skipped).
- **AI explanation** `runtime.explain.AiAnswer` + `ai.tasks.OperatorExplainModel` behind the new port
  `integration.OperatorExplainPort` (same pattern as `AiTaskPort`; the runtime module may not depend on `ai`). Fixed
  system text (`explain-run-v1`) that declares the `<run_facts>` block to be data, never instructions; facts and
  permitted actions as JSON inside it; strict JSON schema (`summary`, `cause`, `evidence`, `suggestedActions` with at
  most 3 entries; all required, no extra properties); temperature 0; `orqix.ai.operator-explain.max-output-tokens`
  (800) and `timeout` (30 s). Profile resolved through `ProviderRegistry.resolve` under the workspace AI policy's
  residency rule; monthly budget checked before the call; one `ai_generation` row of kind `aiExplain` per model call
  (`success`, `invalidOutput`, `providerError` or `credentialsRejected`; metering facts only). The answer filter keeps
  only known fact ids and only permitted action ids; anything else goes to `droppedActions` (and the audit count),
  never to the UI as an action. A non-JSON answer or one without a summary falls back to the rules
  (`fallbackReason: invalidAnswer`).
- **Fallback** (`source: rules`, `fallbackReason`): `aiOff` (`orqix.ai.operator-explain.enabled=false`),
  `featureOff` (workspace AI switch off, S127), `notConfigured` (profile unknown or refused by policy), `residency`,
  `budget`, `unavailable` (provider failure), `invalidAnswer`. `preferRules: true` answers from the rules without a
  fallback reason.
- **Cache**: an AI answer is reused for the same caller, request and state digest (sha-256 of fact ids and texts and
  permitted action ids) for `orqix.ai.operator-explain.cache-ttl` (120 s, at most 10 min; 256 entries per pod, cleared
  when full). AI being switched off (deployment or workspace) is checked before the cache, so a cached answer is
  never served once AI is off. A reuse makes no model call and no metering row; `ai.cached` is true. A permission change changes the
  permitted actions and so the digest.
- **Audit** `ai.run.explained` on resource `run` for every explanation: `source`, `profile`, `model`,
  `promptVersion`, `cached`, `factCount`, `suggestedCount`, `droppedCount`, `fallbackReason`. No text.
- **Frontend**: `ExplainRunPanel` in run detail for callers with `run.operate` (below Operator actions): "Explain this
  request" button, badge "AI-generated, read-only" or "Rule-based, read-only" with the reason the rules answered,
  summary, likely cause, suggested actions (an `operate` action is a button that opens the existing dialog: retry,
  reconcile with the effect preselected, cancel, resume, consistency check, reassign; correct input and wait actions
  scroll to their panels; `updateConnectionBinding` opens Settings; `askAdministrator` is text only), "Show the
  rule-based explanation", and an Evidence list whose timeline facts link to `#event-<seq>` (timeline entries now
  carry that id). Mock mode: `src/mocks/runExplain.ts` (rule-based, from the mock run detail) wired in `handlers.ts`.

### Decisions

- **Read-only POST.** POST (not GET) because a model call is metered and audited; no Idempotency-Key, no
  `expectedVersion`. The service never calls an engine command or a run mutation: run detail, waits, events, release
  and connection state are read in one read-only transaction; the metering row and the audit entry are the only
  writes, each in its own transaction.
- **Permission `run.operate`** (as `getRunDiagnosis`): the explanation exists to choose an operator action and costs
  money; readers without it (viewers, initiators, approvers) get 403 (audited `auth.denied`), callers who cannot see
  the request 404. No new permission.
- **Not gated by the AI feature switch**: with AI off in the workspace the endpoint still answers from the rules
  (`featureOff`), so it is not in `WorkspaceFeatures.GATED_OPERATIONS`.
- **Redaction, conservative default: no values at all**, even for callers who may read request data. Fact texts are
  written by Orqix from codes, statuses, times, step labels, group keys and connection keys. Never read into a fact:
  request input/output, request title, wait subjects, correlation references, `attention.message` (it can carry a
  business message), effect `lastErrorDetail`, worker messages, event data outside a whitelist of keys
  (`errorCode`, `httpStatus`, `attempt`, `classification`, `reason`, `connectionKey`, `until`, `nextRetryAt`,
  `topic`, and only when the value is a short code), people's names (a task is "assigned to a person"), decision
  values, condition values of routes. Field names appear only in the `correctRunInput` action, and only when the caller
  may read request data.
- **Rules first-class**: the rule explanation is a full answer, not an error page, so AI being off, over budget or
  outside residency never removes the feature.
- **Metering through a port**: Spring Modulith forbids `runtime -> ai`; `OperatorExplainPort` (integration) is
  implemented in `ai.tasks`, and binding state for other modules is read through the new exposed
  `integration.ConnectionStatus` (codes and statuses only, never base URLs or secret references).
- **Profile**: `orqix.ai.operator-explain.profile` (empty = deployment default). Tests set `task-openai` (the
  `AiTaskStub`).

### Verification

- `RunFactsTest` (6, no Spring): facts bounded and free of request values, wait subjects, correlation refs and error
  details; first-failure fact with time and timeline seq; model facts (operation, timeout, routes); permitted actions
  per caller (operator: ask an administrator; with `connection.manage`: the binding action; correct input only with
  data access and no effect; nothing without `run.operate`); rules for a 403 with a parallel timer and for a plain
  timer wait (no suggestions); the AI filter (unknown fact ids, a non-permitted action and a binding action the caller
  lacks are dropped; non-JSON and summary-less answers are refused); prompt carries facts inside `<run_facts>` without
  values and the strict schema.
- `RunExplainTest` (1, real PostgreSQL and Flowable, AI from `AiTaskStub`): a request whose procurement call is
  refused with 403 (new `Stub.Mode.STATUS403`) while a parallel branch waits on a one-day timer. AI answer
  (`source ai`, stub summary, permitted actions exactly ask-admin, reconcile, retry, cancel; the stub's `deleteRequest`
  dropped; unknown fact `F999` dropped; facts mention HTTP_403, the timer step and procurement but no request values;
  the provider request has temperature 0, the `<run_facts>` block and no request values; one `aiExplain` row);
  second call reused (`cached`, no new call or row); `preferRules` (rule summary "procurement has refused the call with
  HTTP 403 since ... Meanwhile ... waits for a timer until ..."); workspace AI switched off (`featureOff`, no model
  call); after a `connection.manage` grant the binding action replaces "ask an administrator" and the digest changes;
  viewer and initiator 403, outsider and other workspace 404, unknown run 404; five audit rows with counts only; the
  request's lifecycle, version, current steps, attention, event count, effect states, timer and dead-letter jobs and
  the stub's call count are identical before and after. Responses are validated against the OpenAPI contract.
- `SecurityMatrixTest` (8, all passing; includes `explainRun` as foreign principals: 404 and no state change).
- `ArchitectureTest` (6): 5 pass; `modulithStructureIsValid` fails on a violation that is **not from this part**:
  `runtime.internal.WaitReminders.refusal` (S139, commit ae0af1b5) calls the non-exposed
  `compiler.internal.json.StrictJson`. The violations this part first caused (runtime -> ai, runtime ->
  integration.health) are fixed by the port and `ConnectionStatus`; no violation names an S146 part C class.
- Frontend: `npm run api:generate`, `npm run api:check`, `npm run typecheck`, ESLint on the changed files; Vitest
  `ExplainRunPanel.test.tsx` (3) and `RunDetailPage.test.tsx` (17).
- `python tools/contract-check/contract_check.py`: PASS. Redocly lint: the two errors it reports (struct errors at
  the `WorkingWeek` zone and another inline description with a comma, around lines 12242 and 12357) are not in this
  part's blocks; the coordinator may want to quote them.

### Not covered

- No streaming; one model call, no repair turn (an invalid answer falls back to the rules).
- The model sees only Orqix's own fact texts; deeper diagnosis (engine consistency check, external worker logs,
  remote error bodies) stays in the existing operator tools.
- The cache is per pod and in memory.
- Delegated OAuth consent state per user (S67) is not read; a 401/403 or token-refresh failure is reported as "check or
  authorize the credential again".
- No browser e2e run of the panel against the full stack (unit tests and mock mode only).

### Merge notes

- Flyway `V394__ai_operator_explain.sql` rebuilds `ai_generation_kind_check` with the full S146 superset
  `draft, explain, agent, form, draft-edit, aiTask, aiEval, aiExtract, aiOcr, aiExplain` (identical to what part B's
  migration should produce plus `aiExplain`).
- `AuditWriter.METADATA_WHITELIST`: new `ai.run.explained`.
- `contracts/openapi.yaml`: tag line `  - name: Completion` after `AiSkills`; paths block "S146 Completion: operator
  explain paths" after `end of S141 TaskActions paths`; schemas block (`ExplainRunRequest`, `ExplainFact`,
  `ExplainPermittedAction`, `ExplainSuggestedAction`, `ExplainAiInfo`, `RunExplanation`) after `end of S141
  TaskActions schemas`. `frontend/src/api/schema.d.ts` regenerated.
- New shared/support code: `integration.ConnectionStatus`, `integration.OperatorExplainPort`,
  `ai.tasks.OperatorExplainModel`; test support `Stub.Mode.STATUS403`, an `AiTaskStub` branch for `<run_facts>`
  requests, `AiTaskFixtures` property `orqix.ai.operator-explain.profile=task-openai`.
- Frontend shared files: `RunDetailPage.tsx` (panel, action mapping, timeline `id="event-<seq>"`),
  `mocks/handlers.ts` (one handler).
