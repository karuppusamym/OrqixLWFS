# S90: Safe answer patterns (G5) and the AI form assistant (G4)

Updated 25 October 2026. Closes the two form gaps of the SnapFlow comparison (docs/24, G4 and G5) as bounded slices. Evidence is local (`evidence/s90/`, gitignored); results are in section 5.

## 1. G5: `pattern` on text questions

**Contract (backward compatible, no version bump).** `scalarFormField` in `contracts/workflow-v0.schema.json` gains two optional properties: `pattern` (2-200 characters) and `patternMessage` (1-200 characters, plain text; `dependentRequired` on `pattern`). `schemaVersion` stays `0.1`, every existing document stays valid and no golden file changes: a document without `pattern` compiles exactly as before. Decision taken by the backward-compatible rule of the brief (optional property, no bump).

**Engine: a strictly safe subset, implemented twice and tested against shared cases.** No regular-expression library is added (so no dependency, licence or SBOM change): `com.orqix.compiler.FormPattern` (Java) and `frontend/src/lib/form/pattern.ts` (a line-by-line TypeScript port) parse the pattern into a Thompson NFA and match by simulating it over the answer's code points. The work per answer is linear in its length (at most 4,000 code points × at most 2,000 states); the browser never hands a pattern to its backtracking `RegExp`. Limits: pattern ≤ 200 characters, repeat counts ≤ 100, automaton ≤ 2,000 states, a build-step cap of 50,000 (so `(((){100}){100}){100}` is refused quickly), group nesting ≤ 20, answers > 4,000 characters never match.

| Accepted | Refused with a reason |
|---|---|
| must start with `^` and end with `$`; literals; `.`; `[...]`, `[^...]` with ranges; `\d \D \w \W \s \S`; `\` before `^ $ \ . * + ? ( ) [ ] { } \| /` (and `-` inside a class); `(...)`, `(?:...)`; `\|` inside a group; greedy `* + ? {n} {n,} {n,m}` | missing anchors; top-level `a\|b` (anchors would bind only to the outer alternatives); backreferences; lookaround, named groups, inline flags; lazy or stacked quantifiers; `\b`, `\p{..}`, `\u`, other escapes; counts > 100 or out of order; malformed braces/classes/groups; control characters; too complex; patterns that no answer can match; a shortest match longer than the field's `maxLength` |

**Equal semantics.** The subset is chosen so that its meaning is identical in the server engine, the browser engine and ECMAScript `RegExp` with the `u` flag (the dialect JSON Schema tools use): anchors make search and whole-answer matching agree; `\d`=`[0-9]`, `\w`=`[A-Za-z0-9_]`, `\s` = the ECMAScript white-space and line-terminator set, `.` = any code point except `\n \r    `; case-sensitive; code points (astral characters count once). Constructs whose JS and Java meanings differ (for example `$` before a final newline in Java, `\s` sets, `\-` outside a class) are either defined by our own engine or refused. `examples/fixtures/form-pattern-cases.json` holds 23 accepted patterns (with accepted/rejected answers and the expected shortest example), 40 refused patterns (with the reason) and 8 catastrophic-backtracking cases; both engines run all of them, the browser test also checks every accepted case against native `RegExp(..., 'u')`, and the Java test against `java.util.regex` where the `\s` sets do not differ.

**Compiler.** `FormPatternRules` (after the JSON Schema stage) reports `WF_FORM_PATTERN_INVALID` (blocker, documented in S4) at `/inputSchema|outputSchema/properties/<key>[/items[/properties/<col>]]/pattern` or `/patternMessage`: pattern on a non-text question (choice, date, file, number, yes/no), unusable pattern with the engine's reason, no match within `maxLength`, a message with `${`/`#{` or control characters.

**Server validation is authoritative.** `DefaultFormValidator` (used by request start, child input, incident correction and simulation) adds field error code `pattern` with the author's message (default "Enter the answer in the expected format."), for top-level questions, scalar list items and row columns. A pattern that does not compile fails closed (never matches). Compiled patterns are cached (bounded). The simulation's and Test panel's sample answers use the pattern's shortest match.

**Browser.** `parseFormSchema` keeps `pattern`/`patternMessage` only on plain text questions and only when the pattern compiles in the safe engine (untrusted server data; the server still fails closed on such a pattern); `validateJsonInput` adds keyword `pattern`; `validateForm` shows the author's message. Messages are rendered as React text, never as HTML (test: `<b>` in a message stays text). Request start, pinned releases, incident correction and the studio preview all share this renderer; translated request views (`presentFields`) change labels only, so they show the same message. The mock compiler mirrors the rule.

**Studio.** Text and long-text questions have "Answer pattern" and "Message when the answer does not match" under Advanced. The pattern is checked as it is typed with the same engine: problems appear as the field error (`aria-invalid`, `aria-describedby`, plus a `role=alert` announcement), a valid pattern shows its shortest matching example. Clearing the pattern removes its message.

**Round trips.** BPMN does not carry the request form (S60: `inputSchema` is "kept from the draft"), so a draft with a pattern round-trips exactly and its BPMN differs only in the embedded semantic hash; no read-only marking is needed. The YAML source view round-trips patterns with backslashes unchanged (test).

## 2. G4: AI assistant in the request-form editor

`POST /workspaces/{ws}/workflows/{workflowId}/ai/form-suggestions` (`suggestFormChanges`, permission `workflow.edit`, audit `ai.form.suggested`). Body: `description` (10-2000), `inputSchema` (the form as the editor shows it, ≤ 30 questions, ≤ 64 KB), optional `profile`. Order of checks: workspace visible (404), `workflow.edit` (403), workflow visible (404), body (400), AI configured (`422 AI_NOT_CONFIGURED`), profile allowed, budget (`429 AI_BUDGET_EXCEEDED`), one suggestion at a time per person on the pod (`429 RATE_LIMITED`). No provider call happens before all of them pass.

**Reuse of S10.** Same provider client, profiles and model policy (routed-model check and breaker), per-request and monthly budgets, generation deadline, prompt fencing of untrusted text (`<description>`, `<form>`), structured output with JSON-mode fallback, error mapping (`AI_OUTPUT_INVALID` after one retry for unusable output, `503 AI_PROVIDER_UNAVAILABLE`), metering in `ai_generation` (kind `form`, migration **V145** extends the kind CHECK; no existing migration touched) and an audit event whose metadata holds only profile, model, prompt version, tokens, cost, duration, outcome and the counts `changesSuggested`/`changesRejected`. Versioned prompt `ai/prompts/form-v1.md`.

**Synchronous, not a job (decision).** S10 drafts are jobs because they create a workflow; a form suggestion creates nothing and its result is model output, which S10 never stores. A job row would have to hold it, so the endpoint answers synchronously like `explainWorkflow` and nothing is persisted except metering and audit counts.

**Validation of the reply (`FormSuggestions`).** At most 10 changes (`add`, `update`, `remove`), 5 notes. Each change is checked for operation, key (`^[a-z][A-Za-z0-9]{0,63}$`), existence (add must be new, update/remove must exist), one change per key, 30-question cap, then applied to a running candidate form that the real compiler validates inside a minimal start-to-finish workflow. A change that adds a blocker under `/inputSchema` (schema types, enums, bounds, dates, the pattern rules above, ...) is rejected with the compiler's messages and not applied to the candidate. Notes and reasons are reduced to one line of plain text. The response lists accepted `changes` (with the question `before` and `wasRequired` for review), `rejected` with reasons, `notes`, `notAForm` and the generation metadata.

**Never auto-applied.** The studio's "Suggest questions with AI" (Request form inspector) is rendered only when `getAiStatus.enabled` is true and the draft is editable. It shows each accepted change as a difference (removed/added lines, required/optional change) with an unticked checkbox; **Apply N selected changes** is disabled until the designer ticks changes, and applies them as one undoable edit. A change applies only to the form it was suggested for (an added key must still be free, an updated/removed question must still equal `before`); otherwise it is skipped and named. Rejected suggestions and notes are listed; problems use the shared `ProblemAlert` (`role=alert`), progress and results a polite `role=status`.

**Tests use no network.** Backend: WireMock `OpenRouterStub` (`AiApiTest`) and pure `FormSuggestionsTest`; frontend: MSW mock handler (`features/ai/mocks.ts`, keyword-driven, validated with the same pattern engine).

## 3. Files

Contracts: `contracts/workflow-v0.schema.json`, `contracts/openapi.yaml` (operation, `AiFormSuggestion`, `AiFormChange`, FieldError `pattern`), regenerated `frontend/src/api/schema.d.ts`. Backend: `FormPattern`, `FormPatternRules`, `Pipeline`, `DefaultFormValidator`, `FormValidator`, `SimulationRun`; `FormSuggestions`, `Generator.suggestForm`, `PromptLibrary`, `AiService.suggestForm`, `AiController`, `AiDtos`, `DraftAuthoring.requireWorkflow`, `AuditWriter` whitelist, `V145__ai_form_suggestions.sql`, `ai/prompts/form-v1.md`. Frontend: `lib/form/pattern.ts`, `schema.ts`, `validate.ts`, `values.ts`, studio `ops.ts`/`types.ts`/`StartInspector.tsx`/`sampleValues.ts`, `mocks/validate.ts`, `features/ai/FormAssistant.tsx`, `api.ts`, `mocks.ts`. Shared cases: `examples/fixtures/form-pattern-cases.json`.

## 4. Limits and open points

- Patterns are a deliberate subset (no lookaround, backreferences, flags or Unicode properties); case-insensitive matching needs explicit classes (`[Aa]`).
- The studio editor offers patterns for top-level text questions; list items and row columns accept `pattern` in the document (compiler, server and browser enforce it) but have no editor control yet.
- Field `title`/`description` text is not checked for `${` by the compiler (pre-existing; only step names and messages are). The AI path cannot introduce such text into BPMN because forms are not part of BPMN.
- The per-person suggestion limit is per pod (in memory), like the explanation endpoint it has no cross-pod lock; budgets still bound cost.
- V145 leaves V123-V144 unused (reserved by the brief); a later lower-numbered migration would need Flyway `outOfOrder` or renumbering.

## 5. Verification (25 October 2026, local; logs in `evidence/s90/`)

| Run | Result |
|---|---|
| Focused: `FormPatternTest` (80), `FormSuggestionsTest` (3), `AiApiTest` (23, incl. 2 new), `AiDisabledTest` (1), `SecurityMatrixTest` (4; the new operation is covered automatically from openapi) | all pass (`focused-1.log`) |
| Full backend `mvnw clean verify` (PostgreSQL/Flowable Testcontainers, JaCoCo gate) | BUILD SUCCESS: compiler 604 tests, 0 failures/errors; application 902 tests, 0 failures/errors, 4 skipped (pre-existing opt-in live/real-stack skips) (`backend-verify.log`) |
| Full frontend `vitest run` | 138 files, 1,052 tests pass (`frontend-full-2.log`). A first full run found 7 failures caused by this change (the assistant needed a workspace route in stand-alone inspector tests; an old test expected an unanchored `pattern` to be ignored); both fixed, affected files rerun (132 pass), then the full suite above |
| `tsc -b`, `eslint .`, `api:check`, `build:mock` | pass |

Catastrophic-backtracking cases such as `^(a+)+$`, `^(a|aa)+$`, `^(.*a){20}$` and `^(((a*)*)*)*b$` against 4,000-character non-matching answers finish well under the 1 s (Java) / 2 s (jsdom) test bounds; pathological compile attempts (`^((a{100}){100}){100}$`, `^(((){100}){100}){100}$`) are refused as too complex quickly. Not done: browser (Playwright) and Docker-stack journeys, a live provider run, prompt-quality evaluation.
