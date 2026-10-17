# AI evaluation (S10 section 7)

Manual tools that measure the AI assistance with real model calls. **They spend money** (a full run costs a few cents)
and are never part of CI. Results go to `evidence/ai-eval/<timestamp>/`.

| File | Purpose |
|---|---|
| `draft_eval.py` | Draft-quality eval: runs every case in `cases.json` through the product's own conversation and grades it |
| `cases.json` | Eight descriptions with deterministic expectations |
| `workspace.json` | The workspace the drafts are generated for (groups, the procurement connection) |
| `capture.mjs` | Screenshots of key screens from the frontend in **mock mode** (Playwright from `tests/e2e`) |
| `design_judge.py` | Scores the screenshots with a low-cost vision model against a rubric from docs/02-ux-design.md and docs/build/S7-frontend.md |
| `common.py` | OpenRouter client (usage accounting, spend cap) and the bridge to the backend rules |

## Prerequisites

- Python 3.11+ (standard library only), JDK 21 (`JAVA_HOME`), Node 24 with `tests/e2e` dependencies installed
  (`npm --prefix tests/e2e ci`) for the screenshots.
- `OPENROUTER_API_KEY` in the environment or in the repository's gitignored `.env`. The tools never print it.

## Same prompt, same rules as the product

`draft_eval.py` does not re-implement anything. It starts `com.orqix.ai.eval.AiEvalCli` (backend test sources) over
JSON lines, which uses the backend's `PromptLibrary` (`ai/prompts/draft-v1.md` with the workflow-v0 schema and the
purchase-approval example) and `DraftRules` (post-processing: self-approval forced to deny, unknown dependencies
removed; then `WorkflowCompiler.validate` and the binding-issue classification and repair message). The tool compiles
the backend first (`mvnw -pl app -am test-compile`) unless `--no-build` is given; `--backend <dir>` points at another
checkout or mirror, which avoids clashing with a build running in the repository.

The conversation mirrors `Generator`: first attempt, up to `--repairs` (default 2) repair turns with the compiler
issues. The escalation attempt is not used here, so each profile is measured on its own.

## Draft eval

```bash
# from the repository root
python tools/ai-eval/draft_eval.py --variants baseline,tuned --profiles economy \
    --auto-cases purchase-approval,vendor-onboarding-parallel --max-spend 1.5
```

- `--variants`: `baseline` = no provider preference and `max_tokens` 16000 (the first backend version);
  `tuned` = `provider.sort: throughput` and `max_tokens` 8000 (current backend defaults).
- `--profiles` run on every case; `--auto-cases` also run with the `auto` profile (Jev Router).
- `--judge-model` (default `anthropic/claude-haiku-5.5`, a different family from the generators), `--no-judge`.
- `--max-spend` stops new calls when the provider-reported spend of the run reaches the cap.

Dimensions (BEF4LLM, doc 14 section C), reported per case and per profile with tokens, cost and latency:

| Dimension | Pass when |
|---|---|
| validity | the final reply parses and its definition passes the workflow-v0 schema (or the model correctly declines) |
| syntactic | no compiler blockers other than binding items (missing groups or connections) |
| semantic | the case's `expect` checks hold: `minApprovals`, `groups` used, `thresholds` (a `gte`/`lt` condition at the value), `outcomes` (end-step outcome kinds), `connections` called, `minMissingConfiguration`, `minAssumptions`, `assumptionPattern`; always: self-approval deny, no `${`/`#{` in texts, no unknown connections; `forbidUrlsInText`, `forbidPromptLeak` for the injection case; `kind: notAWorkflow` expects a refusal |
| pragmatic | LLM judge score 1-5 with rationale: clear business labels, matches the description, honest and complete assumptions |

Outputs: `draft-results.json` (all rows with per-attempt log), `summary.md` (tables and judge rationales),
`drafts/<variant>-<profile>-<case>.json` (the post-processed definition the product would create).

## Design judge

```bash
cd frontend && npm run dev:mock -- --port 5176          # in another terminal
E2E_BROWSER_CHANNEL=msedge node tools/ai-eval/capture.mjs --base http://localhost:5176 --out evidence/ai-eval/<stamp>/screens
python tools/ai-eval/design_judge.py --screens evidence/ai-eval/<stamp>/screens
```

The judge confirms through `GET /models` that the model accepts images, scores each screen on visual hierarchy,
spacing rhythm, readability, status clarity, consistency, obvious next action and accessibility red flags, and lists
concrete issues. Output: `design-judge.json` and `design-judge.md` next to the screens. It feeds the design backlog
and never fails a build.

## Cost control

Every request asks OpenRouter for usage accounting; the tools add up `usage.cost` and refuse new calls past
`--max-spend`. Generation calls carry `provider.max_price` (USD 1 / 3 per million tokens), like the backend. Costly
model families are excluded by the backend policy; do not point `--judge-model` or `--model` at them.
