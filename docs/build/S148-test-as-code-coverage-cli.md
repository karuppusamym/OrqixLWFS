# S148: Test as code, test coverage, connector package contract tests and the orqix CLI

9 November 2026. Wave E of the Camunda gap plan ([doc 29](../29-camunda-gap-implementation-plan.md)): **CG-Q01** test
as code and **CG-Q03** coverage (§9.9), **CG-Q04** connector package contract tests (§9.9) and **CG-K01** the `orqix`
command line tool (§9.14). Builds on the dry run and Test tab ([S26](S26-workflow-simulation.md),
[S96](S96-test-tab-and-integration-mocks.md)), generated cases ([S122](S122-generated-test-cases.md)) and connector
packages ([S133](S133-connector-packages.md)). Migration **V420**. OpenAPI: one paths block and one schemas block tagged
**TestsAndCli**. No new permission, notification kind or AI generation kind. Compiler version not bumped.

## 1. Decisions taken

Doc 29 gives one line per ticket; where it is silent the conservative choice was taken.

1. **One file format, the server's case shape.** `orqix.workflow-tests/v1` (`contracts/workflow-test-suite-v1.schema.json`)
   is a list of the shared suite's cases (`id`, `name`, `input`, `scenario`, `expect`, `assertions`) plus optional
   `workflowKey`, `description` and `coverageMinimum`. The Test tab keeps its own editing state of a case (answers as
   typed, step settings) under `studio`, which the server and CI ignore. So a file exported from the Test tab runs
   unchanged in CI, `orqix test push` turns it into the shared suite, and importing it in the Test tab restores the
   cases exactly. A file case without `studio` (written by hand or by `orqix test pull`) runs in CI and as a shared
   test but is not editable in the Test tab (the import says so). The old export format (version 2) is still read.
2. **A test file runs with `workflow.read`; the shared suite needs `workflow.edit`.** A file run is a dry run like
   `simulateWorkflowCases` (nothing stored, audit `workflow.simulated` with counts). The shared suite holds designer
   samples (S96), so running it needs `workflow.edit`; against a draft that run is **recorded exactly like
   `runWorkflowTestSuite`**, so CI can satisfy the publication gate (`orqix test run --shared`, or
   `orqix workflow publish --run-tests`). Against the active release it is a dry run and is not recorded.
3. **JUnit XML comes from the server** (`junitXml` in the answer), so every client produces the same report: one
   `testsuite` named after the workflow key, a `testcase` per case (`classname` `orqix.workflow.<key>`, name
   `<case name> [<id>]`), a failed assertion as `failure` (expected and actual), a case that could not run as `error`,
   and a `Coverage minimum` test case when a minimum applies. No time stamps (the report does not depend on the clock
   or the time zone).
4. **Up to 50 cases per file, 1 MiB.** The shared suite keeps its limit of 20. A run counts once for the rate limit.
5. **Coverage (CG-Q03) is computed from the simulator's own per-run coverage**, so no compiler change and no golden
   trace moved. A step, route (a choice between ways out) or decision rule (including "Otherwise") is covered when one
   case reached it. A **form rule** (a field's `visibleWhen` or `requiredWhen`, a section's `visibleWhen`) is covered
   when the cases hold **both sides**: at least one case where it holds and one where it does not, because a rule
   tested on one side only says nothing about the other. Cross-field validation rules are not counted: a case that
   broke one would not run. Percentages are rounded down; nothing to cover counts as 100%.
6. **The minimum belongs to the shared suite version**, set in the Test tab or by `orqix test push`, and is part of the
   suite hash only when present (existing recorded runs stay valid). A recorded run keeps the coverage **counts** (never
   lists or samples); a run below the minimum is recorded as not passed, and the publication gate then refuses with
   the shortfall named ("The shared tests do not cover enough of this workflow to publish it: routes 66% (at least
   80%)."). A minimum in a test file applies to that run only.
7. **Package contract tests replay on the server** (`testConnectorPackageContract`): the archive is read and checked as
   on import and every fixture is replayed through `HttpExecutor.replay`, but nothing is imported, stored, installed or
   called. A trusted signature is not required (CI builds are unsigned; signing stays the publisher's), the signature
   state is reported, and a signature that does not match is a problem. Needs `connector.manage`, as an import does.
   Problems other than fixtures are listed separately (at most 50).
8. **The smoke run reuses `testConnection`**, which already runs only read-only, testable operations through the real
   binding and the egress policy. `orqix package test --smoke` calls it for each read-only operation of the package's
   connections at the newest registered version in the workspace (`--operation` narrows, `--smoke-input` supplies
   inputs). No new server path can call a write operation.
9. **The CLI signs in as the person.** Bearer tokens on the REST API were machine-only (S6). A new security chain takes
   API requests whose bearer token names a CLI audience (`orqix-cli`, `orqix.cli.audiences`): the token is verified
   (signature from the issuer's keys, issuer, expiry required, 30 s skew, audience) by `CliTokens`, the caller is the
   same subject with the same IdP groups as the person's browser sign-in (`CallerResolver.fromDelegatedToken`, as for
   MCP), and the services check permissions as for a session. The chain is stateless (no cookie, so no CSRF) and
   additionally **limits the routes** to what the tool uses (`/me`, workflows and everything under them, runs list,
   get and start, tasks list, package contract tests, connection list, get and test); every other route is 403. A token
   for the machine clients (`orqix-api`) or MCP (`orqix-mcp`) never takes this chain; routing reads the unverified
   audience, and a forged one only reaches a chain that refuses the token. `orqix.cli.enabled=false` turns it off.
10. **Device code with the dev realm's new public client `orqix-cli`** (device grant only: no redirect, no direct
    password grant, no secret; groups and `aud=orqix-cli` mappers). The single-VM realm is unchanged: an operator adds
    the client when they want the tool (decision kept conservative). CI uses `--token-file` or `ORQIX_TOKEN_FILE`; the
    file is read on each call and never copied into the settings file.
11. **CLI behaviour that changes data is idempotent where the API allows it**: `workflow import` saves a draft only
    when the file differs (canonical JSON), `workflow publish` sends an Idempotency-Key derived from the workflow and
    draft revision, and `run start` takes `--idempotency-key` (a fresh one otherwise). `workflow validate <file>`
    saves the file as a draft first when it differs, because the compiler checks saved revisions (S5); saving never
    changes a release.

## 2. Test runs (CG-Q01)

`POST /workspaces/{ws}/workflows/{id}/test-runs` (`runWorkflowTests`, `workflow.read | workflow.edit`):

| Request | Runs | Permission | Recorded |
|---|---|---|---|
| `{suite, revision?}` | the file's cases against a draft (current when omitted) | `workflow.read` | no |
| `{suite, useActiveRelease: true}` | the file against the active release | `workflow.read` | no |
| `{revision?}` | the shared suite against a draft | `workflow.edit` | yes (publication gate) |
| `{useActiveRelease: true}` | the shared suite against the active release | `workflow.edit` | no |

A file is checked before anything runs (422 with pointers under `/suite`): the v1 schema (networknt, the same validator
as packages), at most 1 MiB and nesting 24, `workflowKey` naming this workflow, unique case IDs, and each case's shape
and assertions as in `simulateWorkflowCases`. Each case runs as `expect` plus its assertions; a case whose answers do
not fit is `invalid` (an `error` in JUnit). The answer (`WorkflowTestRun`) has per case `status`, `passed`, `outcome`,
`failures` (expected and actual) and `problem`; `passed` (cases and coverage), `casesPassed`, counts, `coverage`,
`coverageMinimum`, `coverageShortfalls` and `junitXml`.

## 3. Coverage (CG-Q03)

`SuiteCoverage` (Java) and `mocks/suiteCoverage.ts` (TypeScript, mock mode) aggregate the runs; the shared fixture
`examples/fixtures/simulation/suite-coverage.json` holds them identical. The coverage document is
`{cases, steps, routes, rules, formRules, summary}`, each kind `{covered, total, percent, notCovered}` (at most 100
items: steps `{id, label}`, routes `{transitionId, from, to, description}`, decision rules `{decision, row, condition}`,
form rules `{rule, field|section, label, missing: holds|fails}`).

It is returned by `runWorkflowTests`, by `simulateWorkflowCases` ("Run all saved cases") and by `runWorkflowTestSuite`
(with `casesPassed`, `coverageMinimum`, `coverageShortfalls`). `saveWorkflowTestSuite` and `getWorkflowTestSuite` carry
`coverageMinimum`; `getWorkflowTestSummary` returns it with the last run's counts (`lastRun.coverage`).

**Studio.** After **Run all saved cases** the Test tab shows "What these cases cover together": the summary, the
percentage per kind and, per kind, what no case covers (steps with **Go to step**; a form rule as "No case where
Justification is not required"). The shared tests section shows the coverage of the last recorded run and, for
designers, **Least coverage for publication** (four optional whole percentages, checked as typed, saved with **Share
and test for publication**); readers see the minimum as a sentence. **Export** now writes a v1 test file
(`<key>.tests.json`) and says how many cases were left out (a case needs an expected outcome and answers that fit);
**Import** reads v1 files and the older format.

## 4. Package contract tests (CG-Q04)

`POST /workspaces/{ws}/connector-packages/contract-tests` (`testConnectorPackageContract`, `connector.manage`, audit
`connector.package-tested` with package id, version, archive hash, fixture count and pass flag). `PackageValidator`
gained `contractTest(archive)`: every check of `validate`, collected instead of thrown, with a `FixtureResult` per
fixture (path, connection, operation, passed, replayed outcome and code, message). The answer
(`PackageContractTestReport`) has `signatureStatus`, `passed`, fixture counts, results, the other problems and
`junitXml` (a `Package checks` test case plus one per fixture). Import still refuses a package with a failing fixture,
unchanged.

## 5. The orqix CLI (CG-K01)

`tools/orqix-cli` (package `orqix_cli`, console script `orqix`, or `python tools/orqix-cli/orqix.py`), standard library
only. Commands: `login` (device code, or `--token-file`), `logout`, `whoami`, `workspace list|use`,
`workflow list|export|import|validate|publish`, `run start|list`, `task list`, `test run|pull|push`, `package test`
(`--smoke`). `--json` prints the API answer; `--junit` writes the server's JUnit XML (several files or a smoke run are
merged into one `testsuites`). Exit codes 0 done, 1 a check failed, 2 usage, 3 refused or unreachable. Details in
[tools/orqix-cli/README.md](../../tools/orqix-cli/README.md). Not in scope here: Git sync (CG-S01, another Wave E
slice) and running workers (the Python worker SDK, `tools/worker-sdk`, already does that).

## 6. Merge notes for the coordinator

- Flyway: `V420__test_coverage_minimum.sql` only (two nullable jsonb columns with object checks on
  `workflow_test_suite` and `workflow_test_result`). No `run_id` table, so `orqix_purge_run` is unchanged.
- No new grantable permission, notification kind or `ai_generation` kind: the `permission_grant` checks (V400) are
  untouched. No compiler change and no version bump; `backend/compiler/pom.xml` also packages
  `workflow-test-suite-v1.schema.json` (a resource, like the package schema).
- OpenAPI: tag `TestsAndCli` (after `AlertsAndRetention`); the paths block sits just before
  `/workspaces/{workspaceId}/workflows/{workflowId}/simulate`; the schemas block just before `RoleBinding`. Existing
  schemas gained optional members: `WorkflowTestSummary` (`coverageMinimum`, `lastRun.coverage`),
  `SimulateCasesResult` (`coverage`), `WorkflowTestSuite` and `SaveWorkflowTestSuite` (`coverageMinimum`),
  `WorkflowTestReport` (`casesPassed`, `coverage`, `coverageMinimum`, `coverageShortfalls`). Regenerate
  `frontend/src/api/schema.d.ts` after merging.
- Audit whitelist: `connector.package-tested`.
- Security: new package `com.orqix.identity.cli` (`CliSettings` under `orqix.cli`, `CliTokens`, `CliAuthFilter`,
  `CliAuthentication`, `CliSecurityConfiguration` with `@Order(-2)`); `CallerResolver.current()` resolves
  `CliAuthentication` first. `TestJwtDecoder` adds a `@Primary` `CliTokens` bean on the MCP test key.
- Shared code touched (additive): `TestSuiteService` (records gained members with old constructors kept),
  `SimulationService.CaseOutcome` (a `coverage` component), `PackageValidator` (`contractTest`, a private
  `validate(archive, results)`), `SecurityMatrixTest` sample bodies, `shared/junit/JUnitXml`.
- `deploy/compose/keycloak/orqix-dev-realm.json`: client `orqix-cli` appended (file re-serialized with indent 2, which
  round-trips the existing file byte for byte; the diff is additions only).
- Frontend: `features/studio/test/{TestPanel,savedCases,sharedTests,SuiteCoverage}`, `mocks/handlers.ts` (suite PUT,
  run, summary and simulate-cases), `mocks/state.ts` (`testPass.coverage`), new `mocks/suiteCoverage.ts`.

## 7. Evidence

| Test | What it proves |
|---|---|
| `SuiteCoverageTest` + `suiteCoverage.test.ts` | the shared fixture gives the same coverage and shortfalls in Java and TypeScript (overlapping misses, both sides of form rules, empty kinds, no run); JUnit escaping and counts |
| `WorkflowTestRunApiTest` | a reader runs a file (pass, fail, invalid) with JUnit XML and coverage and nothing is recorded; a file minimum fails the run with a coverage test case; schema, workflow key, duplicate IDs and foreign workspaces refused; the shared run is recorded with counts only, a coverage minimum blocks publication with the shortfall named, a reachable one lets it through; the shared suite on the release is not recorded; Run all returns coverage |
| `PackageContractTestApiTest` | the committed ServiceNow package passes all five fixtures and nothing is imported; a changed expectation fails that fixture alone and a manifest mismatch is a separate problem; `connector.manage` required, foreign workspace 404, unreadable archive 422 |
| `CliTokenAccessTest` | a CLI token acts as the person (`/me`, create a workflow without CSRF, read the draft); a viewer cannot publish, a foreign person gets 404; other routes 403; expired, foreign-signed and wrong-issuer tokens 401; MCP and machine tokens do not get the tool's routes |
| `SecurityMatrixTest` | both new operations are called as foreign principals (404) and change nothing |
| `WorkflowTestSuiteApiTest`, `SimulationApiTest` | unchanged behaviour of the S96 suite and dry runs |
| `TestPanel.test.tsx`, `savedCases.test.ts` | Run all shows the suite coverage; Export writes the v1 file with `studio`; the minimum is checked as typed and sent with the share; v1 import restores cases and counts cases without Test tab state |
| `tools/orqix-cli/tests/test_unit.py` | packing like the server (paths, checksums, fixed times, CRLF), device flow (pending, slow_down, declined), refresh, token sources, JUnit merge, input parsing, usage exit code, private settings file |
| `tools/orqix-cli/tests/test_scripted_e2e.py` | a whole session against a scripted server: token-file sign-in, workspace, import (create, unchanged), validate blockers (exit 1), publish with a stable idempotency key, export of the release, start, list runs and tasks, test run with JUnit (pass, then fail with exit 1 and a merged report), push without `studio` and pull, the shared run, package test with a failing smoke call (exit 1); the token only in the header |
| `tools/orqix-cli/tests/e2e_live.py` | the same against a running stack (skipped without `ORQIX_E2E_URL`) |

A CLI-packed archive of `examples/packages/servicenow-itsm` was also read by the server's `PackageArchive.read` (all 14
files, unsigned) during the build.

## 8. Results

Run on 2026-11-09 in the worktree `wave-e-tests-cli-caef6f` (Docker up, Testcontainers), backend and frontend not
concurrently:

| Run | Result |
|---|---|
| `backend/mvnw -o clean verify` | BUILD SUCCESS: compiler 1224 tests, 0 failures; application 1424 tests, 0 failures, 0 errors, 5 skipped (existing skips) |
| `npx vitest run` (full frontend suite) | 240 files, 1790 tests passed |
| `npm run typecheck`, `npm run lint`, `npm run api:check`, `npm run build` | clean |
| `python -m unittest discover -s tests` (tools/orqix-cli) | 14 tests passed; `e2e_live.py` skipped (no running stack with a CLI token) |
| `python tools/contract-check/contract_check.py` | PASS, 142 checks |
| `npx @redocly/cli@2.60.0 lint --config .github/redocly.yaml contracts/openapi.yaml` | valid; 75 warnings, the same count as the base commit |

Not done here: the live CLI run against a stack (it needs a person to confirm the device sign-in in a browser) and a
browser look at the new Test tab sections (the browser pane was hidden; the jsdom tests cover names, roles and
content).

## 9. Limits

- Coverage is the dry run's (S96 section 8): it is not the engine, and a covered route is a route the simulator took.
  Steps inside child workflows count for the child, not the parent.
- Form rules with expressions are evaluated server-side with the form expression engine; the mock-mode twin treats an
  expression condition as never holding (mock mode only).
- The CLI does not run workers or sync Git; `workflow export` writes the canonical definition (BPMN export stays in
  Studio and `exportRelease`).
- The live end-to-end test needs a CLI token from a running stack; the device sign-in needs a person at a browser.
