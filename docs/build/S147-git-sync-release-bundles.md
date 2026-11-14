# S147 - Git sync (CG-S01) and release bundles (CG-S02)

Wave E slice of [doc 29 §9.8](../29-camunda-gap-implementation-plan.md#98-modelling-and-collaboration-camunda-hub-web-modeler-desktop-modeler).
Builds on S54/S74 (stages and promotion), S61/S128 (shared assets), S114 (view pins), S132 (secret resolver) and the
release evidence pack. Hackathon baseline [§4](../23-hackathon-requirements-baseline.md#4-definition-authoring-and-git-lifecycle):
"The published artifact hash must match the reviewed Git content", and the release records the source Git commit.

## CG-S01 Git sync

### Connection

One Git connection per workspace (`git_connection`): provider (`github`, `gitlab`, `azure-devops`, `bitbucket`), API
root (empty = the public service), repository, the branch pull requests target (`main` by default), the folder Orqix
writes under (`orqix` by default) and a **credential reference**. The token is resolved through the S132 secret
providers on every call (`GitCredentials` in the integration module, so each resolution is counted in the secret-access
audit) and is never stored, logged, returned or written to a file. References in the `webhook-` namespace and the OAuth
keyring are refused. The API root must pass the egress policy (allow-list, private-address and plain-HTTP rules), so a
Git host is approved like any connection host. Versioned (`expectedVersion`, 0 creates); stale is
`409 CONFIGURATION_VERSION_CONFLICT`. `checkGitConnection` resolves the credential and reads the repository and base
branch without writing.

### Provider SPI

`com.orqix.catalog.git.GitProvider`: default branch, branch head, create branch, list and read files at a commit,
commit with an expected parent, find/open/read a pull request (GitLab: merge request) with the reviewers whose approval
applies to its head. `GitHttp` never follows redirects, caps answers at 8 MiB and puts only the provider, step and HTTP
status in errors (never a body or token). Implementations: GitHub (Git data API: trees, blobs, commits, refs; pulls and
reviews), GitLab (repository tree, blobs, commits with actions, merge requests and approvals), Azure DevOps (items,
blobs, pushes with `oldObjectId`, pull requests with reviewer votes ≥ 5) and Bitbucket Cloud (src listing and multipart
commits with `parents`, pull requests with participants; abbreviated commit ids are compared by prefix of at least 12).
**GitHub and GitLab are verified against WireMock stand-ins; Azure DevOps and Bitbucket are implemented against their
documented APIs but not verified by a stand-in in this slice.**

### Deterministic layout and content hash

Under `<basePath>/workflows/<workflowKey>/`:

| File | Hashed and imported | Content |
|---|---|---|
| `workflow.json` | yes | The canonical definition, object keys sorted, two-space indent, **without `provenance`** (provenance names the commit, so it cannot be inside it) |
| `tests.json` | yes, when the workflow has shared tests | `{"formatVersion": "1", "cases": [...]}` with the Studio's case members only |
| `generated/process.bpmn20.xml`, `generated/decisions/*.dmn` | no | The compiler's BPMN and DMN for review |
| `generated/references.json` | no | Rule tables, connection versions and hashes, child workflows, groups, shared asset and view pins |
| `generated/connections/<key>-v<n>.json` | no | Connection **definitions** (never bindings, base URLs or secrets) |
| `generated/assets/<kind>/<key>-v<n>.json`, `generated/views/<id>-v<n>.json` | no | Pinned shared decision/form and view versions |
| `orqix-sync.json` | no | Format version, draft revision, content hash, per-file hashes and the hash rule |

**Content hash** = SHA-256 over the sorted lines `path NUL sha256(RFC 8785 canonical JSON) LF` of the hashed files.
Re-indenting or reordering keys in Git does not change it. Orqix rewrites `generated/` on every push and ignores it on
import.

### Push, import, publish

- **Push** (`pushWorkflowToGit`, `workflow.export`): the draft must compile (so the generated BPMN is what Orqix would
  publish). Writes to a review branch (default `orqix/<workflowKey>`, never the base branch), created from the base
  branch tip when missing; deletes files Orqix no longer writes inside the workflow folder only; makes no commit when
  every byte is unchanged; reuses the open pull request or opens one whose description carries the content hash. A
  branch that moved while writing is `409 GIT_BRANCH_MOVED` (GitHub non-fast-forward, Azure `oldObjectId`, Bitbucket
  `parents`, GitLab tip re-check right before the commit).
- **Import** (`previewGitImport` then `importWorkflowFromGit`, `workflow.edit`): the preview lists added, removed and
  changed steps, routes, rule tables, request fields, other settings and test cases, both content hashes and the
  compiler findings in this workspace. The import needs the exact commit from the preview and the current draft
  revision (`412 STALE_REVISION`), checks schema version, workflow key, size and depth, saves a new draft revision with
  `provenance.gitCommit` (and the pull request as `reviewReference`) when the definition changed, and a new test suite
  version when the tests changed. Nothing is published.
- **Publish** (`publishFromGit`, `workflow.publish`): the commit must be the head of an **open** pull request with at
  least one approval on that head commit, or the head or merge commit of a **merged** pull request with an approval;
  otherwise `422 GIT_REVIEW_REQUIRED`. The content hash of the commit's source files must equal the draft's (definition
  without provenance plus the current shared tests), otherwise `409 GIT_CONTENT_MISMATCH`. Then the normal publish runs
  with every gate (validation, warnings, shared asset checks, publication tests, Tier-1 four eyes, migration
  governance). The release manifest gets `gitSource` {provider, repository, branch, commit, content hash, pull
  request, URL, review state, approvers, verified at}; the release evidence pack shows a "Reviewed Git commit" section
  and `controls.publishedFromReviewedCommit`. `asBundle` publishes a release bundle in the same transaction.

### Secrets and bindings

By construction no binding, base URL, secret reference or token is rendered. In addition every pushed file and every
pulled source file is scanned: members named like credentials (`secretRef`, `password`, `clientSecret`, `apiKey`,
`accessToken`, `refreshToken`, `privateKey`, `authorization`, ...) with a string value, well-known token shapes
(GitHub, GitLab, Slack, AWS keys, PEM private keys, JWTs) and the resolved token itself refuse the whole push or import
with `422`; nothing is sent or saved.

### Audit and history

Audit actions: `git.connection.saved|deleted|checked`, `git.pushed`, `git.pulled`, `git.published` (metadata: ids,
branch, commit, hashes, counts; never a token or file content). An import also writes `workflow.draft.saved` with
source `git` and, for tests, `workflow.tests.saved`. `git_sync_event` keeps every push, pull and publish (commit,
content hash, draft revision, release, pull request) and is listed by `listGitSyncEvents`.

## CG-S02 Release bundles

- **Publish** (`publishReleaseBundle`, `workflow.publish`, Idempotency-Key): the views the draft pins must be published
  versions; then the release is published by the normal path and, in the same transaction, `release_bundle` records
  every component the release pins with version and SHA-256: the workflow (portable hash), its rule tables, the request
  form, shared decisions and forms, document templates, working calendars, views, connection definitions, child
  workflows, AI skill versions and the publication test suite that was current at publish time.
- **Bundle hash** = SHA-256 over sorted `type NUL key NUL sha256 LF`. Workspace-local ids (view ids, release ids) and
  version numbers are not hashed, and the workflow's hash ignores view ids/versions, asset version numbers and
  provenance, so the same content has the same bundle hash in every stage.
- **Promotion as a unit** (`checkBundlePromotion`, `promoteReleaseBundle`; S54 rules: `workflow.export` here,
  `workflow.edit` there, same organization, strictly later stage). Per component: shared decisions and forms are reused
  when the target has a version with the same SHA-256, otherwise created or given a new version; views likewise (needs
  `workflow.publish` in the target; a target view with unpublished changes or of another kind blocks); rule tables,
  request form and tests are copied with the draft; connections must be `ready` in the target (S54 status); document
  templates and calendars must exist there with the same content; child workflows must be published there. One
  transaction: the draft is saved with its pins rewritten to the target's versions and ids, the test suite is copied,
  the target's compiler and shared-asset check must pass; any blocker leaves the target unchanged (`422`). The
  workflow arrives as a draft (new workflow, or a new draft revision with `expectedDraftRevision`). Audit
  `release.bundle.promoted` (source) and `release.bundle.received` (target); `release_bundle_promotion` keeps the plan.
- When the target publishes that draft as a bundle, its bundle records the source bundle and `sameContent` (equal
  bundle hashes). The evidence pack shows a "Release bundle" table (components, hash, source) and "Bundle promotions".
- UI: the workflow page has "Release bundles" (publish as bundle, components, promote with a plan) and "Git" (push,
  check and import a branch, publish from a reviewed commit, history); Settings > Connections has "Git repository".

## Authorization

| Operation | Requires | Notes |
|---|---|---|
| `getGitConnection` | `connection.read` | credential reference only for `connection.manage` |
| `saveGitConnection`, `deleteGitConnection`, `checkGitConnection` | `connection.manage` | audited, denials audited |
| `pushWorkflowToGit` | `workflow.export` | definitions leave the workspace |
| `previewGitImport`, `importWorkflowFromGit` | `workflow.edit` | |
| `publishFromGit` | `workflow.publish` | plus every publish gate |
| `listGitSyncEvents`, `listReleaseBundles`, `getReleaseBundle` | `workflow.read` | |
| `publishReleaseBundle` | `workflow.publish` | |
| `checkBundlePromotion`, `promoteReleaseBundle` | `workflow.export` here and `workflow.edit` there | views need `workflow.publish` there; foreign or unknown targets 404 |

## Decisions (doc 29 is silent; conservative choices)

1. **Existing permissions only.** No new grantable permission (security grants are another session's work): connection
   settings use `connection.manage`, push `workflow.export`, import `workflow.edit`, publish `workflow.publish`.
2. **Reviewed means approved on the exact commit.** GitHub reviews count only when `commit_id` is the head; GitLab and
   Azure DevOps approvals count while the head is the commit asked about; a merged pull request still needs an
   approval. Closed-unmerged pull requests never qualify. Git identities are recorded, not mapped to Orqix people.
3. **The hash covers source files only** (`workflow.json`, `tests.json`), with `provenance` outside it; generated files
   are review aids. Hashing canonical JSON makes formatting edits harmless.
4. **Push requires a compilable draft** so reviewers see the BPMN that would be deployed.
5. **The base branch is never pushed to directly**; changes reach it only through the pull request.
6. **Removing `tests.json` through Git is refused** while shared tests exist (it would switch off the publish test gate);
   unknown members in a case are refused instead of silently dropped.
7. **One connection per workspace**, one review branch per workflow by default; an existing open pull request is reused.
8. **Bundles pin published versions; promotion never copies connections, document templates, calendars, groups,
   bindings or secrets**: those belong to each environment's owners (as in S54). Shared decisions, forms and views are
   copied because the workflow carries their content.
9. **No bundle promotion into an approval-gated stage** (`409 PROMOTION_APPROVAL_REQUIRED`): promote the release with an
   S74 approval request instead. An approval request for whole bundles is not built.
10. **Problem codes** added: `GIT_CONTENT_MISMATCH` (409), `GIT_REVIEW_REQUIRED` (422), `GIT_BRANCH_MOVED` (409);
    provider failures are `503 DEPENDENCY_UNAVAILABLE` with the provider status, egress refusals `422
    EGRESS_NOT_ALLOWED`.

## Data and API

`V410__git_sync.sql` (`git_connection`, `git_sync_event`), `V411__release_bundles.sql` (`release_bundle`,
`release_bundle_promotion`). No table references a request (`run_id`), so `orqix_purge_run` is unchanged. Fourteen
operations under the `GitAndBundles` tag in `contracts/openapi.yaml` (one block at the end of `paths`, schemas just
before `InboundAccepted`); `ReleaseManifest` gains optional `gitSource`.

## Merge notes

- **No grantable permission, notification kind or `ai_generation` kind added**; `permission_grant` checks untouched.
- **Problem codes**: three added to `ProblemCode`, the contract `Problem.code` enum (after `BULK_LIMIT_EXCEEDED`) and
  `frontend/src/lib/problemMessages.ts`. Merge with any sibling's additions in all three.
- **Audit**: nine actions in `AuditWriter` (after `alert.rule-deleted`). `tools/contract-check/contract_check.py`
  `AUDIT_EXEMPT_OPERATIONS` gains `checkBundlePromotion` (read-only POST, like `checkReleasePromotion`).
- **Shared files touched (additive)**: `ReleaseService` (publish overload with manifest extras), `TestSuiteService`
  (`latest` package-private), `ReleaseEvidencePack` (Git and bundle sections), `IntegrationTestBase` (the Git stand-in
  port in the egress lists), `SecurityMatrixTest` (sample bodies, `bundleId`, query strings, snapshot tables),
  frontend `SettingsPage`, `WorkflowOverviewPage`, `api/endpoints.ts`, `api/types.ts`, `mocks/handlers.ts` (`releaseFull`
  exported), `mocks/browser.ts`, `test/server.ts`, `test/setup.ts`. Regenerate `frontend/src/api/schema.d.ts` after merging.
- Flyway V410-V411 used (range V410-V419). Compiler untouched (no version bump).

## Not done

- Azure DevOps and Bitbucket against a stand-in or live service; GitHub App / OAuth app authentication (a token
  reference only); webhooks from the Git host (Orqix reads on demand); signed commits; Bitbucket Server/Data Center.
- Importing a workflow that does not exist yet in the workspace (import updates an existing workflow), monorepo
  discovery of several workflows, and DMN/BPMN edited in Git flowing back (generated files are ignored).
- Bundle promotion into approval-gated stages, cross-organization or cross-instance bundle import, bundle export as a
  signed archive, and copying connection definitions or templates as part of a bundle.
- Browser (Playwright) journey; the frontend uses mock handlers that are a simplified double of the server.

## Verification - 9 and 10 November 2026

Backend (JDK 21, `backend/mvnw -o clean verify`, real PostgreSQL via Testcontainers and Flowable, every response
validated against `openapi.yaml`):

- New: `GitSyncApiTest` (2) against the WireMock stand-ins (`support/GitStandIn`, an in-memory GitHub and GitLab).
  GitHub: connection permissions, reserved reference, egress refusal and repository shape; push to `orqix/<key>`
  with a pull request; layout (workflow.json without provenance, generated BPMN, references, connection definitions,
  orqix-sync.json with the content hash); no token, binding URL, reference or `secretRef` in any file; every call
  authenticated; an unchanged push makes no commit; publish refused before approval; publish after approval with
  `gitSource` in the manifest and the evidence pack; a reviewer edit makes the old approval stale (422), then the
  hash mismatch (409); preview lists the change; import with a stale revision is 412; the import carries
  `provenance.gitCommit`, and publishing it from the reviewed commit succeeds; history and audit; a draft with a token
  is never pushed. GitLab: subgroup project, update actions on a second push, merged merge request refused without and
  published with an approval from the merge commit, `tests.json` added in Git imported as a suite version, removing it
  or adding an unknown member refused.
- New: `ReleaseBundleApiTest` (2): bundle with workflow, rule table, request form, shared decision and connection;
  idempotent replay; read back and list; plan (no change in the target), promotion creating the shared decision there
  and rewriting the pin, the target's bundle has the same hash and `sameContent`; second promotion reuses the decision
  version and needs the expected draft (422 without, 412 stale); evidence pack bundle and both promotions; audit in the
  target. A disabled target binding blocks the plan and the promotion and leaves the target unchanged; unknown
  target and bundle are 404; an operator is 403.
- `SecurityMatrixTest` covers the 14 new operations (foreign principals 404, nothing changes, permission and audit
  declared). `ArchitectureTest` passes (modulith boundaries; only integration resolves secrets).
- Full suites: compiler 1224 tests pass. Application 1416 tests (5 skipped). First full run: 2 failures, the matrix's
  missing audit exemption for the read-only `checkBundlePromotion` (fixed) and `PollWaitsTest.twoSchedulersNeverCheckTheSameRowAtOnce`
  (timing; passes on rerun). Second full run: 1415 pass, 1 failure: `SecurityMatrixTest` "nothing changed" snapshot,
  where rows moved only in engine and run tables (`act_ru_execution`, `run_event`, `task_reminder`, `notification`,
  `effect_journal`) from background job execution of earlier tests' runs; no S147 table changed. `SecurityMatrixTest`
  and `PollWaitsTest` pass when run together on their own (27 of 27).

Frontend: `GitSync.test.tsx` (5): connect a repository by secret reference and test it; members see the repository
but not the reference; push and import a branch as a new draft; publish refused until the commit is approved, then
published as a bundle; plan and promote a bundle. Full suite 1790 tests: 1788 pass, 2 in `AiSkillsPage.test.tsx` time
out under load and pass alone (3 of 3). `tsc -b --noEmit` and `eslint .` pass.

Contracts: `python tools/contract-check/contract_check.py` PASS (142 checks, schema.d.ts up to date);
`npx @redocly/cli@2.60.0 lint` valid with the 75 warnings that were there before (none from this slice).
