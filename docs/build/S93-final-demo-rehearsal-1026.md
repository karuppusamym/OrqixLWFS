# S93 - Final demo rehearsal on stack orqix-demo-1026

Date 2026-10-26. Stack: Compose project `orqix-demo-1026`, built from commit **79de7a7** with `tools/demo/demo-stack.sh`, image tags `orqix/orqix:demo-1026`, `orqix/procurement-stub:demo-1026` (Keycloak 26.8.0, PostgreSQL 18). App http://localhost:39080, Keycloak :39180, stub :39095, PostgreSQL :39432. Browser: Microsoft Edge (Playwright channel `msedge`), serial, 1440x900. Nothing on the stack was stopped, rebuilt or deleted; no product code changed.

## Commands and environment

```bash
cd tests/e2e && npm ci
export E2E_BASE_URL=http://localhost:39080 E2E_BROWSER_CHANNEL=msedge \
  E2E_REALM_FILE=../../deploy/compose/keycloak/orqix-dev-realm.json \
  E2E_STUB_URL=http://localhost:39095 REHEARSAL_OUT=../../evidence/rehearsal-1026
npx playwright test specs/t-demo-1026-blueprint.spec.ts        # NEW (Blueprint PDF)
npx playwright test specs/t-demo-1026-presenter-walk.spec.ts   # NEW (7 personas, every sidebar screen)
npx playwright test specs/t-demo-1025-pega.spec.ts
npx playwright test specs/t-demo-1025-core.spec.ts
npx playwright test specs/t-demo-1025-files.spec.ts
npx playwright test specs/t-demo-1025-recovery.spec.ts --grep fault   # restart test deliberately skipped, see below
```

Harness change: `support/rehearsal.ts` reads `REHEARSAL_OUT` (default unchanged). The negative-upload test counts `orqix.migration_job` read-only through `docker exec <postgres> psql` (`E2E_DB_CONTAINER`, default `orqix-demo-1026-postgres-1`). Evidence: `evidence/rehearsal-1026/shots/*.png` (113 screenshots; the `evidence/` directory is git-ignored) and `docs/build/S93-rehearsal-1026-results.jsonl` (every recorded step, including superseded attempts; its first FAIL line comes from my first attempt, which asserted 20 elements instead of the real 19).

## Result per flow

| Flow | Result | Notes |
|---|---|---|
| Blueprint: Workflows > Import workflow > Pega Blueprint (PDF), upload synthetic PDF | PASS | review screen opens, revision 1 |
| Review shows 19 elements each next to its source-page text with the excerpt highlighted | PASS | 19/19 with source text, 19 highlighted |
| Findings listed: SLA_UNIT_MISSING, INTEGRATION_UNSUPPORTED, INTEGRATION_TARGET_MISSING, TEXT_UNCLASSIFIED | PASS | |
| Edit Finance SLA unit = hours, exclude integration with reason, mark rest reviewed, save | PASS | revision 2, "No open blocking findings" |
| Map roles (Line Manager -> Managers, Finance Officer -> Finance approvers), acknowledge, Convert to draft | PASS | studio opens |
| Publish by paul.publisher | PASS | policy badge "review optional"; publish not blocked |
| Independent migration review path (policy required) | NOT REHEARSED | see O2 |
| 6,000: manager then finance -> Completed, Approved | PASS | |
| 4,999.99: manager only, never in finance queue -> Approved | PASS | |
| Manager reject -> Rejected | PASS | |
| Negative: XML file and text file renamed .pdf refused ("The file is not a PDF document (it does not start with %PDF-)."), stays on page, no job | PASS | migration_job count 5 before and after |
| Axe WCAG 2.2 AA on every blueprint screen and run page (serious/critical fails) | PASS | 0 serious/critical |
| 1025-pega (small, medium 4,999.99/5,000/5,000.01, review-branch approve/reject, unsupported-trigger refusal, ZIP assessment) | PASS 5/5 | |
| 1025-core (Camunda, blockers, Pega DMN, AI hidden check, operator Runs + Insights) | PASS 5/5 | one recorded FAIL line is an expectation mismatch, see O3 |
| 1025-files (loan case template with attachments) | PASS 1/1 | |
| 1025-recovery fault test (HTTP 400 -> Needs attention -> operator retry -> Fulfilled; commit-then-timeout -> one order) | PASS 1/1 | |
| 1025-recovery restart test | SKIPPED on purpose | it approves the seeded ORQ-1002 task and would consume demo data |
| Presenter walk: dana, paul, rhea, mani, fin, olga, wade - home plus every sidebar screen, axe, console, HTTP | PASS 7/7 | zero console errors, zero HTTP >= 400 on /api, zero axe serious/critical |
| D1: operator Runs / Insights / ORQ-1009 run no longer return the consistency-findings 500 | PASS | no consistency 4xx/5xx; ORQ-1009 shows its needs-attention message |

Sidebar per persona: dana Home, My requests, Assistant, Workflows, Cases, Views, Promotions, Settings; paul the same without Promotions; rhea Home, My requests, Assistant, Workflows, Cases; mani adds My tasks; fin Home, My requests, Assistant, My tasks, Cases; olga Home, My requests, Assistant, Workflows, Runs, Cases, Views, Insights, Agent writes, Settings; wade Home, My requests, Assistant, Promotions, Settings.

## Defects and observations (no blocker found)

| ID | Severity | What | Reproduce | Evidence | Suspected cause |
|---|---|---|---|---|---|
| O1 | Low (cosmetic, presenter-visible) | Requests of the blueprint-derived workflow are titled by their cost center: every row in My tasks reads "OPS", not the item description. Start form field order is Amount, Urgent, Cost center, Item description, unlike the blueprint order. | Run the blueprint flow, submit as rhea with cost center OPS, open mani's My tasks | `shots/bp-start-*-6000a.png` | Title taken from the first text field in an alphabetical field order instead of source order or the item-description field. Workaround: use a distinct cost center per request. |
| O2 | Info / doc accuracy | `docs/demo-pega-migration.md` says a second person approves the migration review and publishes. On this stack the policy is "review optional" and paul publishes directly. The required-review path cannot be shown with the seeded users: only paul holds publish rights; wade.admin has no workflow permissions (no Workflows menu; the publish URL shows "Page not found" with HTTP 404). | Open `/w/<ws>/workflows/<id>/publish` as wade.admin | `shots/walk-wade.admin-*.png` | Seed design, not a code fault. Do not switch the policy on live. |
| O3 | Info | `OPENROUTER_API_KEY` is set in the demo container, so "Describe it" is visible; the 1025-core step "AI hidden without key" records FAIL by its own expectation. AI calls cost a fraction of a cent and need network. | New workflow dialog as dana | `shots/ai-hidden-new-workflow.png` | Environment. |

## Side effects on the demo stack

- Seeded ORQ-1001..1009 untouched (1009 still Needs attention). Rehearsal requests start at ORQ-1010. ORQ-1010 and ORQ-1011 (from a first failed attempt) were rejected by me through the manager UI as cleanup.
- Still open from the 1025 specs: ORQ-1022 (imported purchase approval at Finance review) and ORQ-1029 (loan, "Check the documents"); they appear under Insights "Oldest open requests" and "Still open".
- Workflows created, keyed `rh-*` or `rh26-*` (several Pega XPDL, Camunda, DMN, loan and blueprint workflows) plus the Workflows list entries named small, medium, review-branch.
- The recovery spec called `POST /__reset` on the procurement stub: earlier stub order history was cleared, then refilled by the fault test (orders before 1, after 2). No fault is pending.
- Insights counters (31 started at the time of the walk) include rehearsal traffic.

## Not rehearsed

- Restart scenario with the seeded waiting ORQ-1002, and any container restart (forbidden here).
- Required independent migration review (O2) and the self-approval refusal in the browser (covered only by the backend `MigrationGovernanceApiTest`).
- AI drafting itself (Describe it, blueprint "Ask AI for a proposal").
- A real Pega Blueprint export (none exists; the PDF is synthetic).
- Mobile widths, dark mode, other browsers, screen reader use, the fallback stack 1025.
- Encrypted or oversize PDFs and a PDF without an outline (the doc's optional step) in the browser.
- fred.facilities, otto.outsider, pat.platform, vic.viewer, max.manager.

## Presenter checklist (one page)

Before you start (2 minutes)
1. Open http://localhost:39080 in separate private windows per persona; sign-in is through Keycloak, passwords in `deploy/compose/keycloak/orqix-dev-realm.json`.
2. `curl -X POST localhost:39095/__reset` clears any stub fault if you want a clean stub.
3. Check as olga (Runs) that ORQ-1009 still shows Needs attention.

Order and what to say
1. Business approval: rhea submits a purchase, mani approves, fin decides; show the timeline. Say: "the request waits in the database; a restart does not lose it". Do not restart anything live.
2. Failure and recovery: olga opens ORQ-1009 (Needs attention, business message), Retry step with a reason. Say: "the operator sees a business message, not a stack trace; the retry is audited and creates no second order".
3. Pega XPDL import (small or medium): dana imports and converts, paul publishes, rhea runs 4,999.99 vs 5,000.
4. Blueprint PDF: Workflows > Import workflow > Pega Blueprint (PDF) > `examples/migration/pega-blueprint/synthetic-blueprint.pdf`. Say first: "synthetic outline, not a Pega export; the reader is generic". Show elements beside source text and the findings; set the Finance unit to hours, exclude the integration with a reason, "Mark ... as reviewed", Save (revision 2), map the two roles, tick both boxes, Convert to draft. paul publishes (no second reviewer is needed under the default policy; do not claim one happened). rhea submits 6,000 (manager then finance) and 4,999.99 (manager only); a manager rejection ends Rejected. Show a text file renamed .pdf being refused.
5. Insights (olga): figures and the needs-attention list.

Avoid
- Claiming automatic conversion of every Pega rule, a certified Pega version, or that the PDF is a real Blueprint export.
- Saying AI is off: the key is set here. Click AI buttons only if you accept network use and a small cost.
- Giving several requests the same cost center (O1: the task list shows only "OPS").
- Switching the migration-review policy on; wade.admin cannot publish.
- Restarting, stopping or `down`-ing the stack; `down` deletes the database volume.

If something goes wrong (never delete volumes)
- A wrong turn in a workflow: create a new one with a fresh unique key (for example `demo-<time>`); never reuse a key.
- A stuck or noisy request: approve or reject it from the task list as mani/fin, or ignore it; extra rows do not break the demo.
- Stub misbehaves: `curl -X POST localhost:39095/__reset`.
- Need more seeded requests: `tools/demo/demo-stack.sh seed demo-1026 39000` from a checkout of the same commit (adds requests, safe to repeat, removes nothing).
- Browser stuck on sign-in: close the window, open a new private window, sign in again.
- Last resort: switch to the fallback stack orqix-demo-1025 (ports 38xxx), which was not touched.
