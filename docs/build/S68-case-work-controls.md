# S68 - Required case work, manual controls and protected history

18 October 2026. This delivers WM04e within the original [S56](S56-adaptive-cases-ui-designer-scope.md) case-management scope. Full WM04 and UI02 remain open.

## Delivered behavior

CMMN definitions can declare literal required and manual-activation rules on plan items or definition defaults. The server admits only the validated, bounded subset and retains the pinned definition. Required work is marked in the case plan. An eligible worker can start a manual human task or disable and re-enable optional work. Required work cannot be disabled to bypass closure. An operator can explicitly cancel a human task or stage, recording the reason for the waiver. Milestones cannot be manually cancelled or activated through these commands.

Operators can activate and complete stages, place cases on hold, resume them, and close them only when Flowable's native completeness guard permits it. Every mutation checks current workspace membership, permission, eligibility, expected version, case/item ownership and idempotency. Commands for another case's plan item return not found. Concurrent starts produce one task and one version conflict. Required and active work prevents ordinary closure; stage completion uses the engine's guard rather than force-completing work.

The hold is an Orqix command hold for the admitted synchronous case subset. It blocks task and plan changes and displays held tasks as ineligible until resume. Termination remains available to an operator. This is not an implementation of the full normative CMMN suspended plan-item lifecycle and must be extended before admitting asynchronous case work or external event listeners.

V87 adds an append-only, versioned case command ledger. Start, task completion, termination and the new commands retain actor, item, status transition and timestamp. Reasons are returned only with `run.read-data`; other authorized readers see metadata. Reasons are redacted from DTO logging and omitted from generic audit metadata. Existing cases retain their existing engine history; no pre-V87 command events are fabricated. Task-completion events reference the actual plan item rather than its engine task ID.

The case detail UI shows server-authorized actions, required markers, confirmation/reason dialogs and command history. Holds persist across reload. Workers cannot complete held tasks. Model controls and groups are read from the immutable definition. The lightweight mock supports case holds and history; arbitrary manual models require the real engine.

## Verification and local rollout

The final integrated Maven `verify` passes 114 application tests, zero failures/errors/skips, packaging and compiler coverage checks using accumulated coverage data: `evidence/case-controls-integrated-verify.log`. The isolated case/architecture/security regression previously passed 24 cases. Five new API tests exercise manual work, close guards, holds, protected history, stage controls, concurrent starts, replay and cross-case isolation. Validator tests cover rule precedence and rejection. Frontend cases/OAuth/connections/events pass 31 tests across four files; full typecheck, lint, production build, 88 contract checks and E2E types pass. Logs use the `evidence/case-controls-final-*` and `evidence/case-controls-integrated-*` prefixes. The preceding full S65-S67 gate remains 495 compiler tests, 825 application cases (821 pass/four intentional skips), and 886 frontend tests; it predates this increment.

The existing local `orqix-hackathon-verified` stack was refreshed after a 37,788,930-byte PostgreSQL custom-format backup. Dependencies and database volumes were retained. The migration job succeeded through V87 and application readiness is healthy. The new image is `orqix/orqix:engineering-20261018`, ID `sha256:9753d7af6881a8be4a50f53debfa8e0f4d792340f35f0f5d3958cfbe0003e4d3`. Its source is HEAD `1051017` plus the working-tree case controls, identified by source SHA-256 `bdfdf132ef759c3b7d6a56ab9b9dff457a6082d48359bfa11e6203f0f080e347`.

The exact machine-readable source hash, previous image and database-backup hash are retained in ignored `evidence/engineering-image-source.json` and `evidence/engineering-previous-image.json`; those records are authoritative for reproduction. Build, migration and refresh logs are also retained there. The previous image remains available. This local rollout is separate from production deployment or real-provider OAuth consent acceptance.

Real Edge against the refreshed PostgreSQL/Flowable/Keycloak stack passes all four closeout journeys, including Studio-to-runtime pinned request views, termination/reload, OAuth denial/query cleanup, and manual required work through hold/reload/resume/completion/guarded closure with history and mobile serious/critical axe checks: `evidence/case-controls-real-browser-ready.log` (4/4). An initial attempt began before application readiness and recorded three empty-response failures; all four pass after readiness. No mock API is used in the successful run.

## Remaining original acceptance

Full case acceptance still includes typed case data, discretionary planning, pinned workflow case tasks and result mapping, repetition/listeners/nested cases, full suspension semantics, a case designer and source-linked import/export/conformance coverage. General application UI designer acceptance and approved write-capable agent tools remain separate unfinished scope. This increment closes WM04e only.

The subsequent full S68 regression gate passes 495 compiler tests and 831 application cases (827 pass/four intentional skips), with Maven verify/coverage/package success (`evidence/case-controls-full-backend.log`), plus all 888 frontend tests across 116 files (`evidence/case-controls-full-frontend.log`). This snapshot precedes typed case-data work.

[S69](S69-typed-case-data.md) subsequently delivers pinned scalar case data, validated start/corrections and protected before/after history. The remaining-scope paragraph above describes the S68 checkpoint; native CMMN case-file semantics and the other full WM04 requirements remain open.
