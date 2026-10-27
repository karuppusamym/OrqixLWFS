# S96 - Test tab, mock data for integration points, assertions and suites (STU03)

Delivers STU03 of [docs/25](../25-studio-and-integration-roadmap.md#3-stu03---test-tab-mock-data-and-integration-points). Extends the dry run of [S26](S26-workflow-simulation.md) (S4 section 13, S7 section 5) and the shared workflow test suites (`TestSuiteService`, `WorkflowTestSuite`). Everything stays a dry run: nothing is started, called or stored beyond the audit counts and, for a shared suite run, the existing pass/fail row.

## 1. The Test tab

- **Test** is a tab beside Canvas, Steps, Lanes and Source (`StudioPage.tsx`), shown to everyone who can open the studio (`workflow.read`), also on narrow screens (where it replaces the canvas). The header button **Test this workflow** stays as a shortcut: it selects the tab (`aria-pressed` follows it) and returns to Canvas (Steps on a narrow screen) when pressed again.
- On a desktop the tab shows the canvas with the step-by-step playback beside the test panel. The panel stays mounted after the first visit, so the sample request, the step settings and the last run survive a visit to another tab; the canvas playback is shown only while the Test tab is selected.
- Readers (no `workflow.edit`) can run the sample request with any step settings, run all saved cases, look at results, export and test decision tables. Saving, deleting, importing, editing expectations and checks, and sharing the suite need `workflow.edit` (controls hidden or disabled, and the server enforces `workflow.edit` on the suite operations).

## 2. Mock data for integration points

The scenario of a dry run (`SimulationScenario`, contract) now takes a mocked answer per step. The simulator reads it; it never contacts a connection, adapter, worker or model provider, and writes no effect journal entry.

| Step | Scenario member | Mock |
|---|---|---|
| Call a system, AI agent (`http`) | `calls.<id>` | `status` (200-599), `body` (the full JSON body, checked against the operation's `responseSchema`), `delaySeconds` (above the step's time limit = timeout), `failure` now also `timeout`. Legacy `result` (only the mapped fields, not schema-checked) and `failure` keep working unchanged |
| External worker (`externalTask`) | `workers.<id>` | `result` (checked against the declared result fields: only declared fields, declared types), `failure: failed`, `delaySeconds` |
| Adapter send (`sendAdapter`) | `adapters.<id>` | `failure: unavailable \| unknownOutcome`, `rowCount` for a database read |
| Wait for a message | `messages.<id>` | the old `"arrives" \| "timeout"`, or `{outcome, payload, delaySeconds}`; a payload is checked against the declared fields, a delay longer than `timeoutAfter` takes the timed-out route |

**Classification is the runtime's** (`HttpExecutor`, S5 section 6.2): 429 retries then needs attention (`RETRIES_EXHAUSTED`); 408 and 5xx and timeouts are `RETRIES_EXHAUSTED`, or `EFFECT_UNKNOWN` for a non-idempotent operation; other 4xx is `EFFECT_REJECTED` (takes an authored failure route or raises the configured error, like a real rejection); 3xx, a 2xx that is not a declared success status, a body that breaks the response schema and a result pointer that does not resolve are unusable answers (`RESPONSE_INVALID` for read-only operations, `EFFECT_UNKNOWN` otherwise). `failureHttpStatus` on a failure route is the mocked status. Schema problems are reported as `{at, keyword}` (JSON pointer, keyword) in the step details, using the same JSON Schema 2020-12 validator as the runtime (networknt, remote references off). A worker result or message payload that does not fit stops with `TEST_MOCK_INVALID` and says that a real one would be refused.

Fixed on the way: the Java dry run did not keep an external worker's result for later steps (the mock port did); it now stores and shows it like every other step.

**Studio.** Each call has "Mock the whole answer": HTTP status, a JSON body with **Fill with a sample from the response schema** (the same deterministic sample the dry run uses), an inline check against the response schema (`lib/jsonSchemaLite.ts`, same `{at, keyword}` as the server for the keywords it covers) and a delay. Workers, adapters and messages have their own choices. The playback marks mocked answers ("Mocked answer: HTTP 201 ... Nothing was sent.") and lists schema problems. Decision steps keep showing the values looked at and the rule that applied.

## 3. Assertions

`SimulationAssertions` (compiler module, pure) checks a dry-run result: `outcome` (business outcome or `__needsAttention__`), `outputs` (`equals`, `contains`, `range`; a workflow output key or `/steps/<id>/<name>`), `path` (`exact`: the steps visited in order of first visit; `inOrder`: these steps in this order with others allowed between), `rules` (decision step and rule number or `otherwise`), `approvals` (approval step and `approved`/`rejected`/`timedOut`). Bounded (20 items per list, 100 path steps, 200 characters), validated (422 with pointer `/assertions`). Every result carries `expected` and `actual` in words, and a path also the first difference ("Step 5: expected "Finance review" but the run went to "Create purchase order"."). The studio shows a table (caption, header cells, Passed/Failed in words, failures first).

`simulateWorkflow` takes optional `assertions` and returns `assertions: {passed, results}`.

## 4. Suites

- **Run all saved cases** uses the new `simulateWorkflowCases` (`POST /workflows/{id}/simulate-cases`, `workflow.read`, audit `workflow.simulated` with `revision`, `cases`, `passed` only): up to 20 cases in one action, one rate-limit unit, per case `status`, `passed`, outcome and assertion results, or `invalid` with the problem; blockers in the workflow answer 422 `WORKFLOW_INVALID` for the whole call. No trace is returned (a case is opened step by step with Load and Run).
- **Shared suites** (`WorkflowTestCase`) accept optional `assertions`; the expected outcome stays `expect`. A shared run passes a case only when the outcome and all its assertions hold; the report returns `failures` (failed assertions, expected and actual) to the designer, while the stored `workflow_test_result.results` keeps only `{id, passed, status}`, as before. The suite hash is unchanged for cases without assertions, so existing recorded runs stay valid. The suite run now checks the rate limiter once and no longer writes one `workflow.simulated` event per case (the `workflow.tests.run` event remains).
- **`getWorkflowTestSummary`** (`GET /workflows/{id}/test-suite/summary`, `workflow.read`) gives counts only: suite version, number of cases and the latest run of the current suite (revision, passed, passed and total cases, time). The Test tab shows it, and the publish review shows **"Tests: N of M shared tests passed on this draft"** under the check results. The line is information only: whether publishing needs passing shared tests is the existing server gate (unchanged; a policy option is out of scope).
- **Saved cases** in the browser now carry `version: 3` with mocks and assertions; version 2 cases (and export files of version 1) are migrated when read, with no extra checks, so they keep testing exactly what they tested before. Export writes file version 2; import accepts 1 and 2.
- **Test a decision table**: a grid with the table's inputs and expected results per row (up to 50), run through `evaluateDecision` (the runtime's evaluator) against the saved draft, with Passed/Failed, the rule used and the differing results per row. Bulk paste uses the column headers of the decision CSV format ([S89](S89-decision-csv-io.md)): `in:<type>:<fieldRef>` and `out:<type>:<name>`, one case per line with plain values (RFC 4180 quoting, the S89 apostrophe protection is undone); a column the table does not have is refused with line and column.

## 5. Contract, mock mode, migrations

- `contracts/openapi.yaml`: `simulateWorkflowCases`, `getWorkflowTestSummary`, `SimulationAssertions`, `SimulationAssertionResult(s)`, `SimulateCasesRequest/Result`, `WorkflowTestSummary`; new scenario members; `assertions` on `SimulateRequest`, `SimulationResult` and `WorkflowTestCase`; `failures` on the suite report. `frontend/src/api/schema.d.ts` regenerated; `npm run api:check` green.
- Mock mode: `mocks/simulator.ts` implements every new scenario member with the same sentences; `mocks/assertions.ts` is the twin of `SimulationAssertions`; handlers for `simulate` (assertions), `simulate-cases`, `test-suite/summary` and the suite run (assertions, failures).
- Shared fixtures keep the two implementations identical: ten new golden traces (`purchase-mock-*`, `order-payment-mock-*` in `examples/fixtures/simulation/cases.json`) and `examples/fixtures/simulation/assertions.json`, replayed by `WorkflowSimulatorGoldenTest` / `SimulationAssertionsTest` (Java) and `simulator.test.ts` / `assertions.test.ts` (TypeScript). The existing golden traces are unchanged.
- No database migration (V165-V169 unused): shared assertions live in the existing `workflow_test_suite.cases` JSON, the summary reads `workflow_test_result`.
- Audit whitelist: `workflow.simulated` may now also carry `cases` and `passed` (counts).

## 6. Evidence

| Test | What it proves |
|---|---|
| `SimulationMocksTest` (compiler) | status classification table, non-idempotent timeout = unknown outcome, schema problems listed, malformed mocks refused, worker results kept/checked/failed, adapter rows and failures |
| `SimulationAssertionsTest` (compiler) + `assertions.test.ts` | shared fixture gives identical results in Java and TypeScript; readable differences; malformed assertions refused |
| `WorkflowSimulatorGoldenTest` + `simulator.test.ts` | the ten mock golden traces are reproduced by both simulators |
| `SimulationApiTest` | a mocked call with assertions passes; a 503 mock fails the outcome assertion; **no row is added to `effect_journal`, `external_job`, `notification`, `message_subscription`, `workflow_run`, `run_event`, and the procurement stub receives no request**; only the audit count moves; a reader runs three cases in one action (one audit event with counts only); duplicates and foreign workspaces refused |
| `WorkflowTestSuiteApiTest` | shared assertions with a mock fail with expected/actual in the report, stored results hold no sample value, readers get a count-only summary, foreign workspace 404 |
| `SimulationFidelityTest` (PostgreSQL, Flowable, WireMock stub) | **cross-check**: for a 201 success, a 400 rejection, a 201 with a body that breaks the response schema and 500 on every attempt, a real run of the published purchase workflow and a dry run with the equivalent mock reach the same lifecycle/outcome, the same attention reason and the same steps in the same order; the dry runs add no stub request |
| `SecurityMatrixTest` | both new operations declare their permission (and audit); foreign principals get 404 and change nothing |
| `TestPanel.test.tsx`, `scenarioModel.test.ts`, `savedCases.test.ts`, `PublishTestSummary.test.tsx` | the tab and shortcut, state kept across tabs, reader mode, call mocks and inline schema check, path/rule assertions with a readable diff through Run all, CSV-pasted decision grid, version 2 migration, the publish line |

Full runs at the end of the build: see section 7.

## 7. Results

Run on 2026-10-26 in the worktree `build/stu03-test-tab` (Docker up, PostgreSQL and the WireMock procurement stub through Testcontainers), backend and frontend not concurrently:

| Run | Result |
|---|---|
| `backend/mvnw clean verify` | BUILD SUCCESS: compiler 632 tests, 0 failures; application 952 tests, 0 failures, 0 errors, 4 skipped (existing skips) |
| `npx vitest run` (full frontend suite) | 146 files, 1101 tests passed |
| `npm run api:check` | ok, `schema.d.ts` matches `contracts/openapi.yaml` |
| `tsc -b --noEmit` | clean |
| `eslint .` | 1 error in `src/mocks/decisionCsv.ts` line 139 (irregular whitespace), a file this change does not touch; it is unchanged from the base commit 57a8d7e |

Not done here: a browser walk-through with a screen reader and an automated contrast scan of the new controls (the unit tests check names, roles, live regions and keyboard paths). Status in docs/22: **Verified locally (bounded)**.

## 8. Limits

- The dry run is still not the engine: delays are not waited for, and a mock says what the system answers, not whether the real system would. The cross-check covers the HTTP call kinds of the purchase example; agent steps share the call path but their own failure kinds (for example a tool server's) are summarised by the same categories.
- The mock port does not raise configured call errors (`raiseOnFailure`) for mocked failures; the server does. The inline schema check in the studio covers the common keywords; the server's check is authoritative.
- Readers cannot see shared samples (designer data, as before); they see counts only.
