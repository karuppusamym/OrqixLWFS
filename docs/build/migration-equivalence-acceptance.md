# Reviewed migration acceptance and evidence

Implemented 9 October 2026. Tool: [migration-equivalence](../../tools/migration-equivalence/README.md).

## Acceptance boundary

The runner compares observed target behavior against a declared, reviewable scenario suite. It never evaluates source delegates, Pega activities, expressions or arbitrary source code. Matching a synthetic fixture proves target behavior for that fixture. Actual source-system equivalence and customer cutover acceptance require genuine exports, expected scenarios and source-owner review.

The suite records source format/path, source-validation status and owner acceptance. Every source file is included byte-for-byte in the evidence pack with its checksum. Status is carried into results instead of silently being upgraded after target checks pass.

## Scenarios and execution

The provided BPMN purchase suite covers manager approval, amounts below and at the finance boundary, manager and finance rejection, and invalid input. Checks compare HTTP admission status, terminal lifecycle/outcome, approval-node order, successful effect-journal count and matching remote stub orders. The live runner imports a new reviewed draft, publishes it separately, executes through normal role-checked APIs and reads technical effect evidence as an operator.

Live execution is restricted to the local development realm and a procurement binding to the development stub. It creates isolated workflows/runs and orders without resetting or deleting shared data. Remote orders are matched by their effect-journal remote references; unrelated existing orders do not affect the result. It does not implement a universal replay protocol for arbitrary customer source systems.

Offline mode accepts explicit captured observations. The included observations are labeled simulated and exercise comparison/export only. Missing, duplicate, unexpected and mismatching cases fail; booleans/numbers are compared with their JSON types preserved. Deadline failures stay visible in observations.

## Deterministic evidence pack

The ZIP includes source bytes, declared scenarios, observed results, per-check differences and a SHA-256/byte-size manifest. Live evidence also contains compatibility findings/source maps, approved conversion request, target draft, published release, sanitized stub endpoint metadata, operator-visible final runs and matching stub orders.

JSON ordering, archive entry ordering, timestamps and permissions are fixed. Identical evidence produces identical ZIP bytes. Separate executions differ because their workflow/run/release IDs differ. Packs contain request/run data and inherit its handling requirements; passwords and mounted secret values are not exported.

## Review UI

Compatibility findings are grouped by source kind. Kind/severity/unresolved filters help reviewers focus on blockers; complete-report counts stay visible. JSON/CSV exports and conversion continue using the full report. Filtered findings do not change acknowledgements or relax publishing blockers.

## Verification

- Four standalone Python tests verify comparison failures, missing/duplicate/unexpected cases and byte-for-byte reproducible archive checksums.
- Six live golden scenarios passed against the local PostgreSQL/Flowable stack and procurement stub; `evidence/migration-live.zip` contains their per-field results.
- Eight focused frontend migration/report-export tests passed, including grouped finding filters and complete-export controls.

External acceptance remains open for genuine customer Pega/Camunda exports, source owner baselines, live-case/history migration and production cutover/rollback.
