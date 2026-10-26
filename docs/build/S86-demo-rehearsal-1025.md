# S86 - Demo rehearsal on a fresh isolated stack

Date: 25 October 2026. Branch `build/demo-rehearsal`, cut from `314d889` (= `feature/r0-ai-import-wf-theme`). Real backend only, Edge (Playwright, axe WCAG 2.2 AA), no mock. No product code was changed; the only additions are rehearsal specs and this note. Raw results: git-ignored `evidence/demo-1025/` (`results.jsonl`, run logs, 49 screenshots in `shots/`).

## Stack

Private Compose project `orqix-demo-1025`, image `orqix/orqix:demo-1025` (and `orqix/procurement-stub:demo-1025`, retagged in the override so the shared `:dev` tag is untouched), own volume `orqix-demo-1025_orqix-pgdata`. Ports: app 38080, management 38081, Keycloak 38180, PostgreSQL 35432, stub 38095. The other stacks (`orqix-hackathon-verified`, `analystos-*`) were not touched.

```
. evidence/demo-1025/stack-env.sh      # project, image tag, ports
docker compose -f deploy/compose/docker-compose.yml -f evidence/demo-1025/compose.override.yml --profile app build
docker compose -f deploy/compose/docker-compose.yml -f evidence/demo-1025/compose.override.yml --profile app up -d
```

`evidence/demo-1025/compose.override.yml` (private) mounts a secrets directory with two generated random test secrets and the stub image tag. The redirect URI `http://localhost:38080/login/oauth2/code/orqix` (+ web origin) was added to `orqix-web` through this Keycloak's admin API; the realm file is unchanged. The demo seed was run against it with a throwaway copy of `tools/demo/seed_demo.py` that points at ports 38080/38095 (the script hard-codes the stub on 8095; see observation 6). Flyway history: 68 migrations, latest **V122**. Sign-in verified for dana.designer, paul.publisher, rhea.requester, mani.manager (also fin.finance, olga.operator, wade.admin).

Playwright: `npm ci` in `tests/e2e` (inside the worktree), then
```
cd tests/e2e; . ../../evidence/demo-1025/e2e-env.sh   # E2E_BASE_URL=38080, msedge, realm file, stub URL
npx playwright test specs/t-demo-1025-pega specs/t-demo-1025-core specs/t-demo-1025-files specs/t-demo-1025-recovery --reporter=list
```
Specs added: `t-demo-1025-pega`, `-core`, `-files`, `-recovery`, helper `support/rehearsal.ts` (they record PASS/FAIL/INFO plus a non-failing full axe scan). Existing specs run: `t-ui-02-demo-path`, `t-ui-03-task-conflict`, `t-ui-05-accessibility`, `t-ui-06-csp-console`, `t-ui-09-migration`, `t-ui-12-conversation`, `t-ui-13-delegation`, `t-mig-03-pega-xpdl`, `t-doc-08-*`, `t-ui-19-insights-report`.

## Results

Every axe scan recorded (70 screens/dialogs) found no violations. Scans of the Pega, Camunda, DMN, loan, task, run and operator screens are in `results.jsonl`.

| Flow (doc) | Result | What was seen |
|---|---|---|
| Sign-in and roles (demo guide) | PASS | All seven demo users sign in; nav matches role (requester no Workflows editing/Runs; olga has Runs, Insights, Agent writes). |
| Seed (demo guide) | PASS (with a harness edit) | Nine requests ORQ-1001..1009 in the expected states. |
| Demo 1 core: publish, USD 1,800, manager approve, finance at 5,000, finance reject, version pinning, cross-workspace "not found" (`t-ui-02`) | PASS | |
| Demo 1 step 4 app restart | PASS | `docker restart` of the app: waiting task still present, manager decision accepted afterwards. |
| Demo 1 step 9 procurement 400 -> Needs attention -> operator Retry (reason required) | PASS | Business message "Procurement service rejected the request (HTTP 400)..."; retry -> Fulfilled. |
| Demo 1 commit-then-timeout | PASS | Exactly one new order (stub count 1 -> 2), request Fulfilled. |
| Pega `small.xpdl` | PASS | Convert, publish, run: Completed - Done. |
| Pega `medium.xpdl` | PASS | 4,999.99 Standard; 5,000 and 5,000.01 Finance. |
| Pega `review-branch.xpdl` | PASS | Managers mapped, routes JSON from the doc; approve -> Finance; reject -> Rejected. |
| Pega `unsupported-trigger.xpdl` | PASS (see D3) | Source-linked `PEGA_XPDL_EVENT_UNSUPPORTED` at Activity[8]; Convert is refused with 422 "Unsupported XPDL behavior must be reconstructed before conversion."; no workflow created. |
| Pega ZIP archives (small, medium, complex) | PASS | "Manual Pega mapping required", no Convert button. Note: `examples/migration/pega-synthetic-archive.zip` (used by `t-ui-09`) and `pega-synthetic/<size>/archive.zip` both exist. |
| Pega decision -> DMN (`pega-decision/generated/decision.dmn`, via Camunda DMN import; the UI has no Pega-decision button) | PASS | 4999.99 standard; 5000, 5000.01, 9999.99 finance; 10000, 10000.01 director. Outcome label reads "Decided". |
| Demo 5 Camunda import (`camunda7-purchase.bpmn`) | PASS | Map groups and connection, convert, publish; 4,999.99 -> Fulfilled with PO reference, no finance task; 5,000 -> finance task. |
| Camunda blockers `unsupported-features.bpmn`, `xxe-attack.bpmn` | PASS | Blockers listed (timers, scripts, parallel); conversion refused; XXE: "DOCTYPE or ENTITY declaration... not allowed", nothing read. |
| Task conflict, rejection reason, requester cannot self-decide, delegation, guided Assistant (existing specs) | PASS | |
| Documents: loan case template with two required files | PASS | A .txt is refused ("Choose a PDF, PNG image or JPEG image"); PNG attachments accepted; run shows stages. Operator quarantine/release and templates/retention specs pass functionally (`t-doc-08-*`; quarantine spec fails only on D1). |
| Insights (olga) and report export (`t-ui-19`) | PASS | |
| "Describe it" with no key | PASS (see D4) | Control is absent from the New workflow dialog and Settings; nothing says why. |
| Operator Runs screen | **FAIL** | D1. |
| Stage mail capture | NOT REHEARSED | Mail capture container exists only in the hackathon compose (and StageMail is another session's area). "Inspect stage email" exists on the run page; not exercised. |

Console errors: none on any screen except D1 (HTTP 500) and the expected 4xx refusals (403/422) of denial steps.

## Defects (no product code changed)

- **D1 (medium-high) 500 on the operator Runs screen of a fresh database.** Reproduce: fresh stack, sign in as `olga.operator`, open Runs. `GET /api/v1/workspaces/{ws}/consistency-findings?limit=50` returns 500; log `TypeMismatchDataAccessException: Result value is null but no null value expected` at `ConsistencyFindings.list(ConsistencyFindings.java:93)`. Cause: `SELECT max(checked_at) ... .single()` with a mapper that returns null when `run_consistency_scan` is empty (scan disabled by default, so nothing has been scanned); previous S84 runs had the scan enabled. Effect: console error and a missing panel on Runs for every operator; makes `t-ui-05 operator screens`, `t-doc-08-quarantine`, and any spec using olga fail on the console-error policy; in the demo it shows only if the browser console is watched, but it is a visible risk. Evidence: `evidence/demo-1025/app-1.log`, `existing-specs.log`, `core-run4.log`, `shots/operator-runs.png`.
- **D2 (low) Run page shows attachments as raw file ids.** Loan request run page lists "Photo ID 01a1749f-..." instead of the file name. Evidence `shots/loan-run.png`.
- **D3 (low, UX) Convert to draft stays offered when the report has a blocker.** Refused correctly on click (422), but the button is enabled; a presenter should say so or the button should be disabled. The refusal is shown in an alert.
- **D4 (low) No explanation when AI is off.** The doc says "Describe it" hides itself; the UI hides it silently (no AI section in Settings, no notice).
- **D5 (low) Flowable "Fontconfig head is null" WARN stack traces on every publish** (runtime image has no fontconfig; process diagram image not stored). Log noise only; the run diagram in the UI is not affected.
- **D6 (cosmetic, unconfirmed) Task list age line shows "Open for 0h ? Created ..."** in the screenshot (a "?" where a separator is expected). Not verified whether it is a font or character problem.

## Observations / harness and doc fixes

1. Harness: no product file edited. Specs identify tasks by request title or the ORQ reference; a request created without a title shows only its ORQ reference as title.
2. `docs/demo-guide.md` says run `python tools/demo/seed_demo.py` after `up`, but the script hard-codes the stub at `localhost:8095` and its client defaults to 8080; on a private stack it needs `ORQIX_BASE_URL` plus a stub URL edit (not made in the repo).
3. `docs/demo-pega-migration.md` says "currently V48" for the XPDL source-format migration; this build is V122. Not edited (doc owned elsewhere); the rest of the walkthrough matched the UI exactly. Small and medium requests show a collapsed "Add your own reference number" field; the start form for converted workflows has only the inferred fields.
4. The demo guide's Settings -> AI (wade.admin) does not exist without a key.
5. Data left behind: the rehearsal created many workflows (`rh-*`) and requests in the private database. Reset with `docker compose -p orqix-demo-1025 ... down -v` only when the stack is no longer needed.
6. The first-run failures in `results.jsonl` (Camunda finance count, DMN case matching, Pega request key field) were spec bugs fixed before the final runs; the logs `core-run1..5`, `pega-run1..3`, `recovery-run1..2` show the sequence.

## Not rehearsed / not checked

AI "Describe it" (no key, by design); stage mail capture; Demo 2 (agent connection, vendor risk) and its MCP faults; Demo 4 (onboarding template, parallel/timers/child) beyond the existing mock-only spec; Demo 3 racing managers only via `t-ui-03`; version-2 pinning beyond `t-ui-02`; `t-ui-05` designer/settings screens (serial run stopped at D1); axe on every screen of the older specs; mobile widths; Pega native RAP and the full 105-check S39 harness; independent migration review before publish (workspace policy is optional here).

## Presenter checklist

- Reset the stub (`POST /__reset`) before demo 1 faults; run the seed against the correct ports.
- Do not open the operator Runs page with the browser console visible until D1 is fixed; if asked, say the consistency panel needs the scan enabled.
- Pega: say "synthetic, supported XPDL subset"; call the unsupported-trigger refusal intentional and click Convert to show the message; use unique lowercase keys; approval routes JSON exactly as in the walkthrough.
- Archive ZIP: expect "Manual Pega mapping required", not a draft.
- Pega decision path: it is DMN import of the pre-generated file, not a Pega button; the outcome label is "Decided".
- Without an OpenRouter key skip "Describe it" and Settings -> AI; do not promise they will appear.
- Attachments: only PDF, PNG, JPEG (and Word for income proof) in the loan template; the run page shows file ids, not names.
- Do not claim the doc's "V48"; the build is at V122.
