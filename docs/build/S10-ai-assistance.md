# S10 — AI assistance: draft generation, explanation, model policy and LLM judges
Build specification · revision 1.2 · 9 October 2026 · pulls the first R2 slice (E09, Demo 7) forward

AI is optional. With no key configured the product is **rules-only**: templates, the studio and every workflow keep working, AI entry points are hidden and AI endpoints return `422 AI_NOT_CONFIGURED`. AI never publishes, never creates groups, connections or credentials, and never executes anything; a person reviews and publishes every draft (doc 02, E09).

## 1. Provider and configuration

One provider adapter in R2-slice: **OpenRouter** through its OpenAI-compatible Chat Completions API (`POST {base-url}/chat/completions`), which satisfies the master prompt's "OpenAI-compatible endpoints" requirement and gives access to many model families. A native Anthropic adapter (official Java SDK) and others follow in E09.

| Property | Default | Notes |
|---|---|---|
| `orqix.ai.api-key` | `${OPENROUTER_API_KEY:}` | Read **unprefixed** on purpose (local `.env`, gitignored). Empty → AI disabled |
| `orqix.ai.base-url` | `https://openrouter.ai/api/v1` | Egress allowlist must include `openrouter.ai:443` |
| `orqix.ai.profiles.economy.model` | `deepseek/deepseek-v3.2` | Default profile for drafts and explanations |
| `orqix.ai.profiles.auto.model` | `typesafe/jev-router` | Jev Router: picks model and reasoning effort per request; variable price |
| `orqix.ai.default-profile` | `economy` | |
| `orqix.ai.escalation-profile` | `auto` | One extra attempt when the default profile cannot produce a valid draft; empty disables |
| `orqix.ai.judge-model` | `anthropic/claude-haiku-5.5` | Low-cost judge from a different model family than the generator |
| `orqix.ai.vision-judge-model` | `anthropic/claude-haiku-5.5` | Design review of screenshots (confirm image input via `/models`; else `deepseek/deepseek-v4.1-flash`) |
| `orqix.ai.excluded-models` | `anthropic/claude-opus-*, anthropic/claude-fable-*, anthropic/claude-mythos-*, openai/*-pro*, *:batch` | Glob list; any configured or requested model matching it is refused |
| `orqix.ai.allowed-models` | empty (= all not excluded) | Optional allowlist |
| `orqix.ai.max-price.prompt-per-million` / `completion-per-million` | `1.00` / `3.00` USD | Checked against OpenRouter `/models` pricing (cached 1 h) for fixed-price models, and sent as OpenRouter `provider.max_price` on every request. Variable-price routers (pricing `-1`) are allowed only through an explicit profile and are bounded by the budgets below |
| `orqix.ai.budget.monthly-usd` | `10.00` | Sum of `usage.cost` reported by OpenRouter this calendar month (UTC); exceeded → `429 AI_BUDGET_EXCEEDED` |
| `orqix.ai.budget.per-request-max-usd` | `0.50` | A single generation (all attempts) stops when reached |
| `orqix.ai.timeout` | `120s` per call | Draft generation can take tens of seconds; capped by the time left before the deadline |
| `orqix.ai.generation-deadline` | `150s` | Total wall-clock time for one generation, all attempts included. No attempt starts if the time left is shorter than the longest attempt so far. Deadline after an invalid answer → `422 AI_OUTPUT_INVALID`; plain timeout → `503`. The OpenShift Route timeout is 180 s |
| `orqix.ai.provider-sort` | `throughput` | Sent as OpenRouter `provider.sort` together with `max_price`; halved economy p50 latency in the eval |
| `orqix.ai.jobs.threads` / `queue` | `4` / `16` | Async generation executor per pod (section 3): concurrent generations and accepted jobs waiting for a thread; beyond that `429 RATE_LIMITED` |
| `orqix.ai.jobs.active-per-user` | `2` | Queued or running generations one person may have; beyond that `429 RATE_LIMITED` (`Retry-After: 15`) |
| `orqix.ai.jobs.orphan-margin` | `30s` | A job whose pod died is marked failed this long after its deadline |
| `orqix.ai.max-repair-attempts` | `2` | |
| `orqix.ai.max-output-tokens` | `8000` | Measured draft envelopes are 1.0–2.5 k tokens |

**Restricting a router (researched 9 October 2026, a few cents of real calls).** `typesafe/jev-router` takes the usual OpenRouter request but its candidate models cannot be restricted by an allowlist:

| Mechanism | Honored by Jev Router? |
|---|---|
| `provider.max_price` | **Yes.** Candidates above the cap are filtered out: caps of 0.05 USD per million made it answer `404 No models satisfy the decisions policy and request restrictions`; 0.2 / 1.0 routed normally |
| `provider.only`, `provider.ignore` | Yes, but at provider level (`only: [DeepSeek]` → 404; `ignore: [OpenAI]` served the same model from Azure), not by model |
| `plugins: [{id: "auto-router", allowed_models / excluded_models}]` (documented for `openrouter/auto`) | **No.** Excluding the model it had just chosen did not change the choice |
| `models` (fallback list) | No |

So the price caps are the router-side guarantee ("within caps" is the only allowed-set filter available; the costly families excluded by `excluded-models` are priced far above the caps), and the policy is enforced after the fact: every response's model is checked against `excluded-models`, `allowed-models` and the price caps (`ModelPolicy.routedViolation`). On a violation:

- the generation is **kept** when it is valid (the call is already paid and passed the compiler) but the violation is recorded on its `ai_generation` row (`policy_violation`, V11) and returned as `generation.policyViolation`;
- a **circuit breaker** pauses that profile until the next UTC midnight: it is no longer offered, requests for it get `AI_MODEL_NOT_ALLOWED`, the escalation attempt skips it, and the reason is logged at ERROR. The pause is derived from the recorded violation, so every pod enforces it (cached for 10 s) without extra state;
- `getAiStatus` lists it under `disabledProfiles` (id, label, routed model, reason, until) and Settings → AI shows it.

Startup validation (all profiles): model not excluded, in the allowlist when one is set, and — for fixed-price models — within the price caps. A violating profile is disabled with a logged reason; the others keep working. Requests ask OpenRouter for usage accounting (`usage: {include: true}`) so the response carries `usage.cost`.

## 2. Data sent to the model

Allowed: the user's description; the supported node catalog and rules (S4 §2–§5) and `workflow-v0.schema.json`; one example (`examples/purchase-approval.json`); the workspace's group keys and names; connection keys, operation names, labels and request/response schemas. **Never:** run data, request inputs, bindings (URLs), secrets, user lists, audit data. The description is untrusted input: the system prompt instructs the model to output only the JSON contract, and all output passes the compiler (literal and label safety rules included). Post-processing forces `security.selfApproval = "deny"` and removes any dependency the workspace does not have.

## 3. Draft generation (`POST /workspaces/{ws}/ai/drafts`, async)

A generation takes tens of seconds and up to the generation deadline, which is longer than a proxy or a person should wait on one request. So the endpoint **accepts a job** and the client polls it.

`POST /ai/drafts`: permission `workflow.edit`; `Idempotency-Key` required; audit `ai.draft.generated` (metadata: profile, model, promptVersion, attempts, tokens, costUsd, outcome — never the description).

1. **Synchronously**, before anything is accepted: authorize, validate the body (400), check AI enabled (`422 AI_NOT_CONFIGURED`), profile allowed (`422 AI_MODEL_NOT_ALLOWED`), budget available (`429 AI_BUDGET_EXCEEDED`, `Retry-After` until the next month), the per-person and executor limits (`429 RATE_LIMITED`).
2. Persist a **job** (`ai_generation_job`, V11: status `queued`, profile, deadline; no description) together with the Idempotency-Key record, and answer **`202 Accepted`** with the `AiGenerationJob` (`id`, `status`, `startedAt`, `profile`) and a `Location` to poll. An identical request with the same key returns the same job with `200` and `Idempotent-Replayed: true` (its current state, possibly already succeeded); the same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`.
3. The job runs on a **bounded executor** (`orqix.ai.jobs`). The description lives only in the memory of the pod running it. Steps: build messages (versioned system prompt `ai/prompts/draft-v1.md`, `promptVersion = "draft-v1"`, workspace context, the description; `response_format` `json_schema` when the model supports structured outputs, else `json_object`, for the envelope `{definition, assumptions[], missingConfiguration[]}`), parse and run `WorkflowCompiler.validate` with the workspace `CompileContext`. Binding-layer blockers (`WF_GROUP_UNBOUND`, `WF_CONNECTION_*`, `WF_OPERATION_UNKNOWN`) are not repaired: they become `missingConfiguration` items. Other blockers or unparseable JSON → repair turn with the issues (code, path, message), up to `max-repair-attempts`; then one escalation attempt with `escalation-profile` if configured, within budget and not paused; all of it within `generation-deadline`.
4. **Success:** in one transaction create the workflow (key from the name, made unique) with draft revision 1 (layout added if absent), record the `ai_generation` row (V8) and the audit event, and mark the job `succeeded` with its review notes (assumptions and missing configuration, at most 15 items of 300 characters each: the AI's remarks about the draft it created, returned to its author). **Failure:** the job becomes `failed` with a `problem` (`AI_OUTPUT_INVALID` with the last issues, `AI_PROVIDER_UNAVAILABLE`, `AI_BUDGET_EXCEEDED`, ...); nothing is created. No prompt or response text is stored.
5. **Polling:** `GET /ai/generations/{id}` (`getAiGeneration`) returns the `AiGenerationJob`: `queued` → `running` → `succeeded` (with `result`, an `AiDraftResult`: the draft as created, its current validation report, assumptions, missing configuration, generation metadata) or `failed` (with `problem`). State is in the database, so any pod can answer. Poll every 2 seconds.
6. **Visibility** (decision): permission `workflow.read` or `workflow.edit` on the workspace. A person always sees their own generations; generations of other people are visible only to holders of `workflow.edit` (they can open every draft in the workspace anyway); everyone else gets `404`, as does anyone outside the workspace. The job carries no description.
7. **Orphans:** a job whose pod died or restarted stays `running` in the database. It is marked `failed` (`AI_PROVIDER_UNAVAILABLE`, "interrupted") once its deadline has passed (`started + generation-deadline + orphan-margin`; a queued job gets twice the deadline to wait for a thread), by a reaper on every pod and on every poll. A late finisher notices the job is no longer running, records its spend, and creates nothing.
8. **Retention:** the reaper removes terminal jobs older than `orqix.idempotency.retention` (30 days by default), except jobs referenced by an unexpired replay record. Drafts, usage and audit records remain. Admission uses a subject row lock compatible with idempotency foreign-key locks, preventing concurrent submissions from bypassing the active-job limit. Editing access is rechecked before draft creation.

Provider errors: 429/5xx/timeouts → `503 AI_PROVIDER_UNAVAILABLE` on the job's `problem` (no `Retry-After` on a polled job); 401/403 from the provider → AI reported as disabled with reason "credentials rejected".

## 4. Explanation (`POST /workspaces/{ws}/workflows/{id}/explain`)

Permission `workflow.read`. Input: the canonical definition of the requested draft revision or release only. Output: `{summary, steps: [{nodeId, text}], model, profile}` in plain business language (≤ 1,500 characters summary). Not stored. Audit `ai.workflow.explained`.

## 4a. Request-form suggestions

`POST /workspaces/{ws}/workflows/{id}/ai/form-suggestions` (`suggestFormChanges`, `workflow.edit`, audit `ai.form.suggested`, metering kind `form`): validated, reviewable request-form changes that are never applied by the server. See [S90](S90-form-pattern-and-ai-assistant.md).

## 4b. Conversational draft edits

`POST /workspaces/{ws}/workflows/{id}/ai/draft-edits` (`proposeDraftEdits`, `workflow.edit`, audit `ai.draft.edits.proposed`, metering kind `draft-edit`): structured, compiler-validated edit operations for the current draft, reviewed and applied in the studio's left panel. See [S95](S95-conversational-authoring.md).

## 5. Status (`GET /workspaces/{ws}/ai/status`)

Permission `workflow.read`, `workflow.edit` or `workspace.manage-access` (Settings → AI is for workspace admins and designers). Returns `{enabled, reason, defaultProfile, profiles: [{id, label, model, variablePrice}], budget: {monthlyUsd, spentUsd}, disabledProfiles: [{id, label, model, reason, until}]}` (profiles paused by the circuit breaker, section 1). The UI uses it to show or hide AI entry points.

## 6. User interface

- **Workflows → New workflow:** options *Template*, *Start blank*, and — only when AI is enabled — *Describe it*. A description box with two examples, a profile selector (Economy — default; Auto (Jev Router) — "picks the model per request; cost varies"), and **Generate draft** with progress text ("This can take up to a minute"). The dialog polls the job; the person can **close it ("Close and keep drafting")**, and a notice on every page of the workspace follows the job ("We'll let you know when the draft is ready. You can keep working."), turning into **Open draft** when it succeeds or into the reason when it fails. Nothing opens by itself. The result opens the studio with an "AI-drafted — review every step before publishing" panel listing assumptions and missing configuration (each with "Go to step" when it maps to a node).
- **Studio header:** *Explain* (AI enabled) opens a side panel with the explanation.
- **Settings → AI** (workspace admins and designers): provider, profiles and models, excluded models, price caps, month-to-date spend. Read-only in this slice (configuration is deployment-owned).

## 7. Evaluation and LLM judges (`tools/ai-eval`)

Manual runs only (they spend money); never in CI. Results are written to `evidence/ai-eval/<timestamp>/`.

1. **Draft quality eval** — `tools/ai-eval/cases.json`: about 8 descriptions (purchase approval, access request, document review, expense claim with finance threshold, vendor onboarding needing parallel work — expected to surface assumptions because v0 has no parallel steps, a prompt-injection attempt, a vague request, a non-workflow request). Dimensions after BEF4LLM (doc 14 §C): **validity** (parses, schema-valid), **syntactic** (no compiler blockers other than binding items), **semantic** (deterministic expectations per case: approval groups, decision thresholds, outcomes, forbidden constructs), **pragmatic** (LLM judge, 1–5 with rationale, rubric: labels are clear business actions, matches the description, assumptions honest and complete). Reports per case and per profile with tokens, cost and latency.
2. **Design judge** — Playwright captures the key screens (as `capture` in the E2E kit) and a vision model scores each against a rubric from doc 02 / S7 (visual hierarchy, spacing rhythm, readability, status clarity, consistency, obvious next action, accessibility red flags) and lists concrete issues. Output feeds the design backlog; it never fails a build.

## 7a. Implementation notes (revision 1.1, 9 October 2026)

- **Review notes are stored with the job** (revision 1.2), because a poll can be answered by any pod: at most 15 assumptions and 15 missing-configuration items of 300 characters, no description or raw model output. The browser keeps them in `sessionStorage` for the studio panel.
- **Extra error mappings:**
  - provider `402` (no credit) → `503 AI_PROVIDER_UNAVAILABLE`;
  - provider `404` (unknown model) → `AI_MODEL_NOT_ALLOWED`;
  - a description that is not a workflow → `422 AI_OUTPUT_INVALID` without repair turns;
  - an exhausted monthly budget also shows as `enabled = false` with a reason.
- **Post-processing also** fills boilerplate, drops model-written layout (the server generates it), removes invalid optional limits, resets out-of-range execution values, pins unknown connection versions to the latest, and tolerates Markdown fences.
- **Router risk (closed in revision 1.2):** see the router research and circuit breaker in section 1. Cost stays bounded by `max_price` (honored by Jev Router), the per-request and monthly budgets, and a credit limit on the provider key.
- **Request bodies are parsed after authorization**, so foreign callers get 404 even with a malformed body (security matrix).
- **Tests and evaluation:** test configuration forces `orqix.ai.api-key: ""`, so a developer's key never enables AI in the shared test context. The live test runs only with `-Dorqix.ai.live=true`. Eval results are local (`evidence/` is gitignored); see `tools/ai-eval/README.md`.

## 8. Tests

WireMock-stubbed provider in integration tests (`AiApiTest`; every response, including the polled jobs, is validated against the contract): success with post-processing, metering and audit; `202` with `Location`, polling, idempotent replay and visibility; invalid JSON then repaired; blockers persisting after repairs → escalation → success; persisting failure → failed job `AI_OUTPUT_INVALID`; provider 429/500/timeout/402 → failed job `AI_PROVIDER_UNAVAILABLE`; the generation deadline (with delays); the per-person job limit (`429`), including concurrent submissions; access revoked during generation; terminal-job retention and protected live replay records; an orphaned job failed after its deadline; excluded or over-cap model refused; a router choosing an excluded model recorded and its profile paused (status, refusal, escalation skips it); budget exhausted → `429`; disabled (no key) → `422` and status `enabled=false`; post-processing forces `selfApproval = deny`; nothing sensitive in audit, `ai_generation` or the job row. Pure unit tests cover `DraftRules` and `ModelPolicy` (routed-model check, breaker, UTC midnight). Frontend tests cover hidden entry points when disabled, the draft-review panel, the background notice and the paused-profile display; Playwright (mock mode) covers Describe it → review → Go to step → publish. `tools/api-e2e/e2e_ai.py` (scenarios 30-31) runs Demo 7 against the real stack and provider and skips cleanly when AI is off; the opt-in `live-ai` test hits the real provider.
