# Orqix API changelog (`/api/v1`)

Changes to the public HTTP API in [`contracts/openapi.yaml`](../contracts/openapi.yaml), under the rules of the
[API compatibility policy](api-compatibility.md). Newest first.

The **Unreleased** section is generated: `python tools/contract-check/contract_diff.py --changelog` compares the
contract with the recorded baseline (`contracts/compat/openapi-v1-baseline.json`) and rewrites it. CI fails when the
section is out of date or lists a breaking change. At a release, the release owner runs
`python tools/contract-check/contract_diff.py --record <release>`, which records the new baseline and empties the
section, after copying the section's content under a heading for that release below.

<!-- BEGIN GENERATED: unreleased changes (python tools/contract-check/contract_diff.py --changelog) -->

### Unreleased (compared with the baseline recorded at r0-2026-11-08)

#### Breaking changes (CI fails while this lists any)

- None.

#### Deprecated

- None.

#### Removed after the deprecation window

- None.

#### Added

- New operation `DELETE /workspaces/{workspaceId}/access/resource-grants/{grantId}` (deleteResourceGrant)
- New operation `DELETE /workspaces/{workspaceId}/git-connection` (deleteGitConnection)
- New operation `GET /admin/admission` (listAdmissionLimits)
- New operation `GET /admin/workspaces/{workspaceId}/admission` (getWorkspaceAdmission)
- New operation `GET /workspaces/{workspaceId}/access/resource-grants` (listResourceGrants)
- New operation `GET /workspaces/{workspaceId}/audit/chain-verifications` (listAuditChainChecks)
- New operation `GET /workspaces/{workspaceId}/audit/export` (exportAuditLog)
- New operation `GET /workspaces/{workspaceId}/audit/signing-keys` (listAuditSigningKeys)
- New operation `GET /workspaces/{workspaceId}/git-connection` (getGitConnection)
- New operation `GET /workspaces/{workspaceId}/workflows/{workflowId}/bundles` (listReleaseBundles)
- New operation `GET /workspaces/{workspaceId}/workflows/{workflowId}/git/events` (listGitSyncEvents)
- New operation `GET /workspaces/{workspaceId}/workflows/{workflowId}/git/import-preview` (previewGitImport)
- New operation `GET /workspaces/{workspaceId}/workflows/{workflowId}/releases/{releaseId}/bundle` (getReleaseBundle)
- New operation `POST /workspaces/{workspaceId}/access/resource-grants` (createResourceGrant)
- New operation `POST /workspaces/{workspaceId}/audit/chain-verifications` (verifyAuditChain)
- New operation `POST /workspaces/{workspaceId}/bundles/{bundleId}/promote` (promoteReleaseBundle)
- New operation `POST /workspaces/{workspaceId}/bundles/{bundleId}/promotion-check` (checkBundlePromotion)
- New operation `POST /workspaces/{workspaceId}/connector-packages/contract-tests` (testConnectorPackageContract)
- New operation `POST /workspaces/{workspaceId}/git-connection/check` (checkGitConnection)
- New operation `POST /workspaces/{workspaceId}/runs/{runId}/reveal` (revealRunData)
- New operation `POST /workspaces/{workspaceId}/workflows/{workflowId}/bundles` (publishReleaseBundle)
- New operation `POST /workspaces/{workspaceId}/workflows/{workflowId}/git/import` (importWorkflowFromGit)
- New operation `POST /workspaces/{workspaceId}/workflows/{workflowId}/git/publish` (publishFromGit)
- New operation `POST /workspaces/{workspaceId}/workflows/{workflowId}/git/push` (pushWorkflowToGit)
- New operation `POST /workspaces/{workspaceId}/workflows/{workflowId}/test-runs` (runWorkflowTests)
- New operation `PUT /admin/workspaces/{workspaceId}/admission` (setWorkspaceAdmission)
- New operation `PUT /workspaces/{workspaceId}/git-connection` (saveGitConnection)
- shared response `BadRequest` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `Conflict` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `FeatureDisabled` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `Forbidden` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `NotFound` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `PayloadTooLarge` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `PreconditionFailed` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `PreconditionRequired` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `TooManyRequests` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `Unauthenticated` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `Unavailable` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- shared response `Unprocessable` application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `GET /me` 200 application/json response `workspaces[].permissions[]` may also return "data.reveal"
- `GET /workspaces/{workspaceId}` 200 application/json response `permissions[]` may also return "data.reveal"
- `GET /workspaces/{workspaceId}/access` 200 application/json response `grants[].permission` may also return "data.reveal"
- `GET /workspaces/{workspaceId}/ai/generations/{generationId}` 200 application/json response `problem.code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `GET /workspaces/{workspaceId}/ai/generations/{generationId}` 200 application/json response `result.workflow.permissions[]` may also return "data.reveal"
- `GET /workspaces/{workspaceId}/document-sources/{sourceId}/files` 503 application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `GET /workspaces/{workspaceId}/document-sources/{sourceId}/files/{itemId}/versions` 503 application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `GET /workspaces/{workspaceId}/runs/{runId}` 200 application/json new response field `protectedFields`
- `GET /workspaces/{workspaceId}/workflows/{workflowId}` 200 application/json response `permissions[]` may also return "data.reveal"
- `GET /workspaces/{workspaceId}/workflows/{workflowId}/releases/{releaseId}` 200 application/json new response field `manifest.gitSource`
- `GET /workspaces/{workspaceId}/workflows/{workflowId}/test-suite` 200 application/json new response field `coverageMinimum`
- `GET /workspaces/{workspaceId}/workflows/{workflowId}/test-suite/summary` 200 application/json new response field `coverageMinimum`
- `GET /workspaces/{workspaceId}/workflows/{workflowId}/test-suite/summary` 200 application/json new response field `lastRun.coverage`
- `POST /workspaces/{workspaceId}/access/grants` 201 application/json response `permission` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/ai/drafts` 200 application/json response `problem.code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `POST /workspaces/{workspaceId}/ai/drafts` 200 application/json response `result.workflow.permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/ai/drafts` 202 application/json response `problem.code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `POST /workspaces/{workspaceId}/ai/drafts` 202 application/json response `result.workflow.permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/connector-packages/{packageVersionId}/workflow-templates/{templateKey}/workflows` 201 application/json response `permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/migrations/projects/{projectId}/convert-next` 201 application/json response `converted.workflow.permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/migrations/{migrationId}/convert` 201 application/json response `workflow.permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/runs/{runId}/cancel` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/runs/{runId}/migrate` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/runs/{runId}/migrate-with-mapping` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/runs/{runId}/repair` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/runs/{runId}/resume` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/runs/{runId}/retry` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/runs/{runId}/signals` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/runs/{runId}/suspend` 200 application/json new response field `protectedFields`
- `POST /workspaces/{workspaceId}/workflows` 201 application/json response `permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/active-release` 200 application/json response `permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/publish` 200 application/json new response field `manifest.gitSource`
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/publish` 201 application/json new response field `manifest.gitSource`
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/restore` 200 application/json response `permissions[]` may also return "data.reveal"
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/simulate-cases` 200 application/json new response field `coverage`
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/test-suite/run` 200 application/json new response field `casesPassed`
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/test-suite/run` 200 application/json new response field `coverage`
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/test-suite/run` 200 application/json new response field `coverageMinimum`
- `POST /workspaces/{workspaceId}/workflows/{workflowId}/test-suite/run` 200 application/json new response field `coverageShortfalls`
- `PUT /workspaces/{workspaceId}/document-sources/grants/{sourceId}` 400 application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `PUT /workspaces/{workspaceId}/document-sources/grants/{sourceId}` 412 application/problem+json response `code` may also return "GIT_BRANCH_MOVED", "GIT_CONTENT_MISMATCH", "GIT_REVIEW_REQUIRED"
- `PUT /workspaces/{workspaceId}/workflows/{workflowId}/folder` 200 application/json response `permissions[]` may also return "data.reveal"
- `PUT /workspaces/{workspaceId}/workflows/{workflowId}/owner` 200 application/json response `permissions[]` may also return "data.reveal"
- `PUT /workspaces/{workspaceId}/workflows/{workflowId}/test-suite` new optional request field `coverageMinimum`
- `PUT /workspaces/{workspaceId}/workflows/{workflowId}/test-suite` 200 application/json new response field `coverageMinimum`

<!-- END GENERATED -->

## r0-2026-11-08 (Waves A to D, baseline)

First recorded baseline of `/api/v1`: 456 operations, as described in the change record of 2026-11-08
(`docs/release/orqix-change-record-2026-11-08.pdf`). Earlier changes are in the R0 release notes in `docs/release`.
