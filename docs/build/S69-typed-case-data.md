# S69 - Pinned typed case data and protected corrections

18 October 2026. This continues WM04's original [S56](S56-adaptive-cases-ui-designer-scope.md) case-data acceptance after the [S68](S68-case-work-controls.md) controls.

## Data and authoring

Each immutable definition version can declare up to 30 fields: text, number, integer, boolean, calendar date or enumerated choice. Field keys and labels are validated; choices are distinct and bounded. Requiredness, text length and numeric bounds are server-authoritative. Text defaults to at most 2000 characters (configurable to 4000), numeric values are bounded to plus/minus 10^15, and choice sets contain at most 30 values. Numbers must round-trip through browser JSON without losing their decimal value. The browser also rejects precision loss before submission. Zero and false remain values; clearing an optional control removes that value.

The definition form includes a field editor. A starter sees a typed start form whenever the pinned definition declares fields. Missing required data, unknown keys, incorrect types, invalid dates, unsupported choices and out-of-bounds values are rejected before starting the engine. Existing no-data definitions and bodyless start calls remain compatible. A later definition version cannot change an existing case's field contract.

This is Orqix-owned case data, stored separately from executable engine variables. The currently admitted CMMN model has only static group assignments and constant controls: changing this data cannot invalidate an active task predicate or execute author expressions. Native CMMN case-file items, data-triggered sentries and bindings into workflow case tasks still need their own semantics and conformance acceptance. Do not describe this as full standard case-file support.

## Authorization, concurrency and history

`GET /cases/instances/{id}/data` returns the pinned fields, current values, case version and current edit eligibility. The case starter, a currently eligible human-task worker, or a member with `run.read-data` can read data. A metadata viewer does not gain payload access. Current workspace membership is checked on every call. Before/after history requires `run.read-data` and is omitted for ordinary starters/workers. The endpoint returns the latest 200 data snapshots; older ledger records remain retained in the database.

An eligible worker with `task.act`, or an operator with both `run.operate` and `run.read-data`, can replace the data of an active case. The command requires a current case version, reason and idempotency key. It shares the same case row lock as task decisions and lifecycle commands. Competing edits cannot overwrite one another. Held or ended cases reject new edits. Identical retries replay only after current authorization is checked; changed intent conflicts. The update response contains metadata rather than protected values. Callers read values through the independently authorized data endpoint.

V88 stores fields on the immutable definition and values on the case. Every start and successful data edit writes an append-only protected before/after record, linked to the versioned case command event in the same transaction. Generic audit metadata contains changed field keys and the version, never field values or the reason. DTO logging redacts data and reasons. Data reads use a repeatable-read transaction so values, version, status and history belong to a consistent snapshot. Existing cases default to empty data without fabricated pre-V88 data history.

The case detail page loads data on demand, shows editable or read-only controls according to server eligibility, requires a reason for saving, refreshes the case version and displays protected change history to authorized readers. Parent case actions and data saves share pending state. The mock supports reading the empty demonstration data; typed deployment/editing explicitly requires the real backend.

## Verification

The isolated PostgreSQL/Flowable case, architecture and security gate passes 29/29 tests (`evidence/case-data-final-complete-backend.log`). Five new API tests cover schema pins, required start data, strict values and dates, roles, protected before/after history, idempotency, held edits, concurrency and invalid field shapes. The later read-isolation and numeric-bound refinements are covered by the final integrated process verification. The focused frontend gate passes 11/11 cases across two files (`evidence/case-data-ready-frontend.log`): typed start, authorized correction/history, held read-only rendering and decimal transport boundaries. Types, lint and production build were checked in the isolated checkout. The integrated contract checker passes all 88 checks. The final direct-process Maven `verify` passes all 119 selected application cases with zero failures/errors/skips, coverage checks and executable packaging, with process exit 0 (`evidence/case-data-final-process-verify.log`, `evidence/case-data-final-process-exit.txt`). A preceding five-case verification also covers the final data refinements. The direct process result resolves an inconsistent PowerShell redirection exit status from an earlier successful Maven run.

The complete frontend suite passes 893/893 tests across 117 files (`evidence/case-data-full-frontend.log`). Frontend types, full lint, API drift and E2E types pass in the integrated checkout. The image builds the production SPA and embedded surfaces. The earlier full backend baseline remains 495 compiler tests plus 831 application cases (827 pass/four intentional skips); it predates S69. No fresh full application run after S69 is claimed.

## Local rollout and browser acceptance

After a 37,845,109-byte custom-format database backup, the existing local stack migrated successfully through V88. The application image is `orqix/orqix:case-data-20261018`, ID `sha256:ea648dd55c8bb4354465aee70e771bf8f9630297e21e6ad5fde065e7980cce8c`. Source provenance is HEAD `4c39e87` plus the working tree, SHA-256 `c67a62e65dcd0a83288641a839c53ff469d09d44ade1802216c44bb457ad22d9`; the source was checked against the built manifest before deployment. Manifest, previous image, backup hash and build/migration/refresh logs are retained under ignored `evidence/case-data-*`. Readiness is healthy. This is local acceptance, not production or real-provider OAuth acceptance.

Real Edge against PostgreSQL, Flowable and Keycloak passes all five closeout journeys (`evidence/case-data-real-browser.log`): pinned reusable views, termination, OAuth denial cleanup, manual required work/hold/resume/guarded closure, and typed field authoring/start/correction/closure/history with mobile serious/critical accessibility checks. The typed-data journey uses the actual definition editor and role-separated browser sessions. It verifies data after closure and reload.

A subsequent application restart was followed by a fresh authenticated Edge read of the same completed case. Its corrected values, case version 5, read-only state and protected data snapshots at versions 2 and 1 all persist (`evidence/case-data-real-restart-browser.log`, `evidence/case-data-restart-result.json`).

A pre-V88 application cannot enforce typed definitions. A rollback to that application must use a matching database/definition recovery procedure; retaining its image alone is not a compatible typed-data rollback.

Full WM04 remains open for discretionary planning, pinned workflow tasks/results, repetition/listeners/nested cases, normative suspension, case-model designer and conformance/import/export. General application UI designer, additional agent protocols/A2A and approved write-capable tool acceptance remain separate unfinished original scope.
