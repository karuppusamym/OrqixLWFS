# S54 - Environment stages, release promotion, canary and per-release analytics

PL02 delivers one bounded slice of the "promotion/canary/environment lifecycle": a lifecycle **stage** per workspace, **promotion** of a release definition to a later-stage workspace of the same organization with a compatibility report against the target's own bindings, a **canary** share of new starts for one other release, and per-release **request figures**. It is not a Git integration, a deployment pipeline or production infrastructure.

## Behavior

- **Environment stage.** A workspace has a stage: `development`, `test`, `staging` or `production`. Without an explicit row it is `test` for workspaces flagged as test and `production` otherwise. People with `workspace.manage-access` set it (versioned: `expectedVersion`, 0 when none was set yet; stale is `409 WORKFLOW_VERSION_CONFLICT`). Any workspace member can read it.
- **Promotion check.** `checkReleasePromotion` is read-only. It needs `workflow.export` in the source workspace and `workflow.edit` in the target, the same organization, a different workspace and a strictly later stage (otherwise `422`; a target the caller cannot see, or in another organization, is `404`). The report lists each connection the release references with its status in the target (`ready`, `bindingMissing`, `definitionMissing`, `definitionDiffers`), each task-assignment group (exists, member count), each called workflow (published in the target or not), whether the target already has the workflow (and its draft revision) and the compiler's findings. `compatible` is true when the target's own compiler run has no blockers; it is the authority, the lists explain it.
- **Promote.** `promoteRelease` refuses with `422 WORKFLOW_INVALID` and the findings when the report is not compatible. Otherwise it saves the release's definition as a **draft** in the target: a new workflow (owner is the caller) or, when the key exists, a new draft revision (then `expectedDraftRevision` is required; stale is `412 STALE_REVISION`; missing is `422`). It never publishes, never activates, and copies no binding, secret, group membership or permission. The target publishes through its normal gates (validation, warnings, workflow tests, migration review). A provenance row records the source release number and semantic hash and the compatibility snapshot; `listWorkflowPromotions` shows promotions into the workflow and, only to someone who can also see the other workspace, promotions out of it.
- **Canary.** `setWorkflowCanary` sends 1 to 50 percent of new starts to one release other than the active one. The two releases must have identical request fields and form layout (`422` otherwise), so a person never sees a different form from the one validated. The share is a stable function of the run id. Setting or clearing it changes the workflow `version` (compare-and-set via `expectedVersion`). Selecting any other active release (including promoting the canary or rolling back) clears the canary. Runs stay pinned to the release they started on. Replayed starts (idempotency) keep their original release.
- **Release figures.** `getReleaseAnalytics` (`run.read`) returns, for the newest 20 releases and a window of 1 to 365 days (default 30): started, in progress, needs attention, succeeded, rejected, cancelled, average completion seconds and success share. Counts and durations only; no request data.
- **UI.** The workflow overview has a "Gradual rollout" section: publishers choose a version and share and start/stop a rollout; readers see the status; people with `run.read` see the per-version table.

## Authorization boundaries

| Operation | Requires | Notes |
|---|---|---|
| `getWorkspaceEnvironment` | workspace visibility | non-members 404 |
| `setWorkspaceEnvironment` | `workspace.manage-access` | denial audited |
| `checkReleasePromotion`, `promoteRelease` | `workflow.export` (source) and `workflow.edit` (target) | invisible or other-organization target is 404; denials audited |
| `getWorkflowRollout`, `listWorkflowPromotions` | `workflow.read` | |
| `setWorkflowCanary`, `clearWorkflowCanary` | `workflow.publish` | designers without publish are 403 |
| `getReleaseAnalytics` | `run.read` | |

Audit events: `workflow.canary.set|cleared`, `workspace.environment.changed`, `release.promoted` (source) and `release.promotion.received` (target); metadata is numbers and ids only.

## Data and API

Migrations `V65__release_canary.sql` (`workflow_canary`), `V66__workspace_environment.sql`, `V67__release_promotion.sql`. New operations are in `contracts/openapi.yaml` under the Releases tag. `ReleaseQueries.canary` and `inCanaryShare` expose the routing facts to the runtime; `RunService.start` picks the release after the run id is generated.

## Not included

- Git as a source of truth, branch/PR flows, repository sync, signed bundles, or cross-organization/cross-instance promotion (a bundle import path). Promotion is between workspaces of one installation.
- Automatic canary evaluation, automatic rollback, progressive stepping, metric thresholds, sticky per-person routing, a canary for different request forms, or a canary on child workflows.
- Scheduled promotions, promoting several releases or workflows at once, and copying connection bindings, secrets or groups. The promotion screen and an optional approval gate were added later in [S74](S74-promotion-ui-approval-gate.md).
- Analytics beyond per-release counts: no percentile latency, per-step timing, cohort or trend charts, cost, or cross-workflow comparison; no CSV/JSON export of these figures.
- Production infrastructure (blue/green hosts, traffic routers, HA) is out of scope by design.
- No browser (Playwright) journey was run for this slice.

## Verification - 17 October 2026

Backend (JDK 21, real PostgreSQL via Testcontainers and Flowable, every response validated against `openapi.yaml`):
- `ReleasePromotionTest` (4): promotion creates a draft that the target publishes normally; second promotion needs and checks `expectedDraftRevision`; history in both directions; a disabled target binding reports `bindingMissing`, blocks promotion with `422 WORKFLOW_INVALID` and creates nothing; export-here/edit-there boundaries, invisible and unknown targets 404, same-workspace and earlier-stage 422; stage management permission, versioning and effect on promotion.
- `ReleaseRolloutTest` (3): 24 real starts with a 50% canary land on both releases and the per-release counts equal the database and the analytics; publish-only, version, form-compatibility and range checks; clear; selecting an active release clears the canary; analytics permissions and window bounds; the share function is stable and within 2 points of the target over 20,000 ids at 1, 10, 25 and 50 percent.
- `SecurityMatrixTest` (3, extended with sample bodies for the new operations) confirms every new operation declares its permission/audit and returns 404 to foreign principals without changing state.
- Regression run: `ArchitectureTest`, `CatalogApiTest`, `DatabaseTest`, `RollbackPinningTest`, `ReleaseCompatibilityTest`, `RunPathApiTest`, `AccessApiTest`, `PermissionRevocationTest`, `InsightsApiTest`, `RunMigrationTest` pass. The full backend suite was not run.

Frontend: 3 new tests (`ReleaseRollout.test.tsx`: publisher starts and stops a rollout; run readers see read-only status and figures; the third test only asserted the section was absent for a requester and was weak because that user cannot reach the page at all; S74 replaced it). Full suite 800 tests: 797 passed on the first run and 3 timed out under load (`ai.test.tsx`, `StudioPage.test.tsx`), which pass when run alone (28 of 28); not caused by this change. `tsc -b`, `api:check`, scoped ESLint and `build:mock` pass. Mock handlers are a simplified double of the server (e.g. no form-compatibility check, no average duration).

Evidence (untracked, `evidence/`): `pl02-backend*.log`, `pl02-frontend.log`, `pl02-types.log`, `pl02-lint.log`, `pl02-apicheck.log`, `pl02-build.log`.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=ReleasePromotionTest,ReleaseRolloutTest,SecurityMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test` (JDK 21); from `frontend`: `npx vitest run src/features/workflows/ReleaseRollout.test.tsx`.
