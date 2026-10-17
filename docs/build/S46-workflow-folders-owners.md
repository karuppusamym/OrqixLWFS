# S46 - Workflow folders and owners

PL01 delivers one bounded slice of "organization": a flat set of named **workflow folders** per workspace, an **owner filter** and **owner reassignment**, on top of the existing name/key search. Folders are labels. They never grant, narrow or inherit permissions.

## Behavior

- **Folders.** A workspace has up to 50 folders (one level, no nesting). Names are 1 to 80 characters, trimmed, and unique ignoring case. A workflow is in at most one folder or is "unfiled". Deleting a folder returns its workflows to unfiled; it never deletes a workflow.
- **Catalog.** People with `workflow.read` get a Folder filter (All, Unfiled, each folder with its workflow count), an Owner filter (each current owner with a count), a "Folder and owner" column, and the existing search. Filters live in the URL, combine with search and pagination, and "Clear filters" resets all three. A filter naming a deleted folder shows "Folder no longer exists" and an empty-state recovery.
- **Designers** (`workflow.edit`) get **Manage folders** (create, rename, delete) and **Organize** on each row (choose a folder and an owner).
- **Owners.** The owner shown already existed (the creator). A designer can reassign it, but only to a known, enabled person who can edit workflows in that workspace **now** (access is recomputed from current bindings and grants, not a cached session). Candidates come from `listWorkflowOwnerCandidates` (up to 50 of the first 300 known people).
- **Versioned references.** A folder has a `version` that increments on rename. Rename sends `expectedVersion`; moving a workflow into a folder sends `expectedFolderVersion`; changing an owner sends the workflow's `expectedVersion` (which then increments, so it conflicts with a concurrent activation or owner change). Every mismatch is `409 WORKFLOW_VERSION_CONFLICT` with the current version and nothing changes. Folder IDs are never reused, so a workflow cannot silently follow a deleted-and-recreated folder.

## Authorization boundaries

| Operation | Requires | Notes |
|---|---|---|
| `getWorkflowOrganization`, `?folder=`/`?owner=` on `listWorkflows` | `workflow.read` | Start-only requesters get 403 on both and never see `folderId`; counts describe workflows every reader can already list. |
| create/rename/delete folder, `setWorkflowFolder`, `setWorkflowOwner`, `listWorkflowOwnerCandidates` | `workflow.edit` | Denials are audited as `auth.denied`. |
| any of the above for a non-member of the workspace | none | 404, so folder existence is not disclosed. |

Folder IDs, workflow IDs and subject IDs are always resolved inside the path workspace; a folder from another workspace is 404 (composite foreign keys enforce this in the database too). Moving a workflow or changing its owner does not change `permissions`, `canStart`, grants or approver eligibility. Audit events: `workflow.folder.created|renamed|deleted|assigned`, `workflow.owner.changed`; folder names are not copied into audit metadata.

## Data and API

Migration `V33__workflow_folders.sql` adds `workflow_folder` and `workflow_folder_item` (one row per filed workflow, cascade-deleted with the folder). `Workflow`/`WorkflowSummary` gain an optional `folderId`. New operations (see `contracts/openapi.yaml`): `getWorkflowOrganization`, `listWorkflowOwnerCandidates`, `createWorkflowFolder`, `renameWorkflowFolder`, `deleteWorkflowFolder`, `setWorkflowFolder`, `setWorkflowOwner`.

## Not included

- **Shared assets.** Reusable decision tables, connections, form/view definitions or components shared across workflows are **not** built; neither are references from a workflow to a versioned shared asset. UI02 and the remainder of PL01 stay open.
- Nested folders, folder-level permissions or inheritance, bulk move, drag and drop, tags, saved views, full-text search beyond name/key, favorite folders.
- Cross-workspace folders; ownership groups or teams; notifying the old/new owner; "owner" granting any rights.
- Moving requests, runs or releases (only workflows).
- A Playwright browser journey and a real-stack (Docker) run were not executed for this slice.

## Verification - 17 October 2026

Backend (JDK 21, real PostgreSQL via Testcontainers, every response validated against `openapi.yaml`): `WorkflowOrganizationApiTest` (6), `SecurityMatrixTest` (3, now also calling the new operations as foreign principals and checking no folder row changes), `CatalogApiTest` (7) and `ArchitectureTest` (5) pass: 21 tests, 0 failures. An earlier larger run of the same plus `AccessApiTest`, `WorkflowTestSuiteApiTest`, `DatabaseTest` and `PermissionRevocationTest` passed 31 of 31 before the owner-candidate endpoint was added; a regression run of `ConversationApiTest` (12), `SimulationApiTest` (6), `RunPathApiTest` (4) and `MigrationProjectApiTest` (8) passes 30 of 30 (`evidence/pl01-backend-regress.log`). The full backend suite was not run.

The API test covers: folder filter/unfiled/owner filter against viewer reads; counts and owners; moving a workflow does not change the viewer's permissions; viewer, publisher and start-only requester denials; foreign and cross-workspace 404s; malformed folder filter 400; duplicate and case-insensitive names; stale rename and stale move 409; missing or inconsistent folder version 422; unknown folder 404; delete unfiles but keeps workflows; the 50-folder limit in a private workspace; owner change refused for a viewer target, an unknown ID and a non-designer caller; success increments the version and a replay of the old version is 409.

Frontend: 9 new component tests (`Organization.test.tsx`) plus the full suite (92 files, 788 tests) pass; `tsc -b`, scoped ESLint, `api:check` and the mock build pass. Not covered by a UI test: a *successful* owner reassignment (the mock has one designer per workspace), which is covered by the backend test instead. Mock handlers are a simplified double of the server.

Evidence (untracked, under `evidence/`): `pl01-backend.log`, `pl01-backend-regress.log`, `pl01-frontend.log`, `pl01-types.log`, `pl01-lint.log`, `pl01-apicheck.log`, `pl01-build.log`.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=WorkflowOrganizationApiTest,SecurityMatrixTest,CatalogApiTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test` (JDK 21); from `frontend`: `npx vitest run src/features/workflows/Organization.test.tsx`.
