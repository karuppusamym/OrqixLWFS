# S119 - Integration of WM04, UI02 and MIG11

29 October 2026. Branch `claude/big-open-features-f6ffdd`. This record covers the combined testing of the three "big open features", built in parallel in three sessions and merged here:

| Feature | Records | Migrations | Source branch |
|---|---|---|---|
| WM04 - full CMMN cases | [S110](S110-cmmn-canvas-planning-workflow-tasks.md), [S113](S113-cmmn-nested-cases-timers-conditions.md), [S118](S118-cmmn-decisions-case-files-dates-any-of.md) | V180-V182 | this branch |
| UI02 - application designer | [S111](S111-application-designer.md), [S114](S114-application-designer-completion.md), [S116](S116-application-designer-round-3.md) | V190-V195 | `claude/ui02-app-designer-fe6ab7` |
| MIG11 - native Pega rule conversion | [S112](S112-pega-native-conversion.md), [S115](S115-pega-native-completion.md), [S117](S117-pega-native-round-3.md) | V200-V202 | `claude/mig11-pega-native-e5c641` |

`main` (S104-S106, V174-V177, image CVE patches, E2E CI fixes) was merged in last. The migration ranges do not overlap, and Flyway applies all of them in order on a fresh database.

## Merge conflicts and fixes

- **Studio canvas** (`CanvasView.tsx`): this branch and `main` both fixed BPMN text size at the first view. `main`'s version was kept (minimum zoom of 0.85 for cards and 0.94 for BPMN).
- **E2E support and specs**: `support/app.ts` and the specs that open tasks were taken from `main`. Both sides sort task lists newest first, so leftover open tasks no longer push new ones off the first page. Both sides added the same discussion helper to `t-ui-22`, and `main`'s copy was kept.
- **`t-ui-07`**: a global edit on this branch had changed the home-shortcut address that the spec expects. It is reverted.
- **React Flow attribution** (the case canvas): the link now has a 24 px target, so the axe target-size rule passes.

## Verification on the merged branch

| Check | Result |
|---|---|
| Backend `verify`, real PostgreSQL and Flowable (Testcontainers) | compiler **650/650**; application **1091 run, 0 failures, 0 errors, 4 skipped** (the same 4 are skipped on `main`) |
| Frontend unit and component tests (Vitest) | **1266/1266** in 177 files |
| ESLint, TypeScript, `api:check` (OpenAPI to generated types) | pass |
| Contract checks (`tools/contract-check`) | **88/88** |
| E2E TypeScript | pass |
| Full Playwright suite (Edge) on a fresh `docker compose` stack built from the merged branch | **85 passed, 0 failed, 65 skipped** (16.1 min); Flyway applied 94 migrations up to V202 |

The skipped tests are skipped on purpose and also skip on `main`:

- Mock-only specs, which run with `E2E_MOCK=true`.
- The demo rehearsal specs (`t-demo-1025-*`, `t-demo-1026-*`), which need `E2E_REHEARSAL=true` and the demo seed.
- Specs that need extra environment variables: a verification database container, inbound signing secrets, or the restart check.

Every new journey of this batch passes on the real stack:

- WM04: `t-wm04-cases-advanced`, three journeys (canvas planning, a workflow task with retry, nested cases with timers).
- UI02: `t-ui-02g-page-journey`, `t-ui-02g-action-chains`, `t-ui-02g-extensions`, `t-ui-02h-pin-migration`, `t-ui-02i-extension-bundle`, `t-ui-02i-send-back-and-undo`.
- MIG11: `t-mig-05`, `t-mig-06`, `t-mig-07`.

The earlier run before merging `main` (103 passed, 6 failed) had three causes, and none of them fails on this run:

- `t-ui-07`: the address edit described above, now reverted.
- `t-chat-01` and `t-ai-02`: leftover data on a reused stack (for example, the Assistant lists only the first 25 open tasks). Both pass on the fresh stack.
- `t-demo-1025-recovery` (2 tests) and `t-demo-1026-presenter-walk`: rehearsal specs, which `main` now gates behind `E2E_REHEARSAL`.

## Not covered

- Nothing has been pushed or merged to `main`. That needs the owner's go-ahead.
- The limits of each feature are listed in its own records (the "Not covered" sections and the CMMN 1.1 conformance matrix in S113).
