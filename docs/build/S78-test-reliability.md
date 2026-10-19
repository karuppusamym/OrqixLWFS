# S78 - Test reliability (frontend timeouts, backend shared-database ordering)

Scope: test infrastructure only. No product behaviour change, no migration. Branch `build/test-reliability`, cut from 723c94c.

## Symptom
Intermittent `Test timed out in 15000ms` in the heavy frontend files (ai, StudioPage, GuidedWorkflowDialog, SourceLanesDiscussion,
GenerateDocumentInspector, RunDiagram, workflow lists) when the machine was busy; each file passed alone.

## Root causes found (profiled with vitest durations, per-step timing probes and a CPU profile)
1. **Real-time autosave timer in UI tests.** The studio's `DraftSaver` waits its 1.5 s quiet period after every edit. UI tests that
   wait for `Saved` sat idle (worker profile: ~80 % idle) for 1.5 s per edit burst. One MessageInspector test was ~13.7 s of which
   most was idle waiting. Fix: a test-only seam `draftSaverTestTuning` in `stores/draftSaver.ts` (undefined in the product, so the
   spec value 1500 ms always applies there); `test/setup.ts` sets 150 ms. Tests that assert a pre-save state
   (`Unsaved changes`, the undo-returns-to-`Saved` check) and `studioStore.test.ts` (fake-timer spec of the 1.5 s rule) restore 1.5 s.
   A first attempt with 150 ms everywhere produced a real race in the undo test (save slipped in between edit and assertion under
   load); that is why those tests keep the spec value.
2. **Fresh module graph and fresh jsdom for each of the 125 files.** Vitest reported `jsdom was created 125 times, 442 s total, 28 %`,
   setup (msw server plus every mock handler, seed state) 25 %, import 9 %. Under load each file spent seconds before its first test.
   Fix: `vite.config.ts` now has two projects. `shared` (`isolate: false`) runs files that do not replace modules; `isolated`
   (`isolate: true`) runs the 16 files that use `vi.mock/doMock/resetModules`, discovered by scanning the sources at config time so a
   new mocking file is isolated automatically. (An all-`isolate:false` run failed exactly those mocking files; `vmThreads` is not
   usable with msw: `TransformStream is not defined`.) Setup, per-test reseed and cleanup still run for every test and file.
3. **Cold lazy-route load inside the first test's 15 s.** `test/setup.ts` now pre-loads the studio chunk in a `beforeAll`
   (60 s hook budget) for `.tsx` tests under studio/, ai/, workflows/, so tests measure their own interaction. No per-test timeout
   was raised anywhere.

## Backend
Integration tests share one PostgreSQL per JVM, so first-page reads (`limit=100`) of lists that grow (claimable/assigned/completed
queues, requests, runs, notifications, workflows) can miss an item depending on which tests ran earlier (the SeparationOfDutiesTest
failure mode). Added `Api.getAllPages(path, vars)` in `IntegrationTestBase` (follows `nextCursor`, returns the first page body with
all items) and converted the 16 such reads in `RunsAndTasksTest` and `InclusiveAndMultiInstanceTest`. Left alone: run event streams
(`events?limit=100`, bounded per run), `limit=1` cursor tests, `AccessApiTest` admin workspaces list, `WorkflowOrganizationApiTest`
filtered list, `SecretLeakScenariosTest`, `RestartTest` (own database via ResilienceDatabase). Verified:
RunsAndTasksTest 17/17, InclusiveAndMultiInstanceTest 5/5, SeparationOfDutiesTest 3/3. The full backend suite was not re-run here.

## Measurements (frontend `vitest run`, 125 files / 931 tests, 8 cores, shared dev machine; noisy)
| Run | Before | After |
|---|---|---|
| idle machine, run 1 | 275 s (4m35) | 131 s |
| idle machine, run 2 | 215 s | 127 s |
| idle machine, run 3 | 239 s | 82 s |
| 8 CPU-spinning processes | 479 s, 1 timeout failure (ai.test, 15000 ms) | 217 s, 0 failures |
Before-numbers: run 1 is the untouched baseline, runs 2 and 3 are the baseline plus only the autosave fix (first change) and a plain
default run; wall time varied with other worktrees using the machine. After, three consecutive idle runs passed 931/931 with the final
configuration; one loaded run with an intermediate version (no studio pre-load) had 3 timeouts, which motivated item 3; the loaded
run with the final configuration passed. Flake rate: one sample of each loaded configuration is not a statistic; the honest claim is
"about 2x faster, and the loaded run no longer times out in the one sample taken", not "flake eliminated".

## Residual risk
`isolate:false` shares module state between files in a worker; the 109 shared files passed in every full run, and the mock database is
reseeded per test, but a future test that mutates module-level state without resetting it can leak. New files that call `vi.mock` are
auto-isolated. If that ever bites, set the `shared` project to `isolate: true` (cost: the old timings).
