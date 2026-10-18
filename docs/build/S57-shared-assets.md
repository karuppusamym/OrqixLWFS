# S57 - Shared assets: decisions, document templates and connections with owners, versioned references, where-used and search

PL01 remainder after S48 (folders/owners). One minimal model covers three kinds of reusable workspace asset. Each asset is identified by **kind and key inside one workspace**, has immutable numbered versions, an **owner** (a label) and an **archived** flag:

| Kind | Store | New in this slice |
|---|---|---|
| `decision` | `decision_asset` + `decision_asset_version` (V80) | Everything: shared decision tables did not exist |
| `documentTemplate` | DOC08 registry (`document_template`) | `key`, owner and `revision` (V81); existing templates get `template-<id prefix>` keys and their creator as owner |
| `connection` | Existing immutable `connection_definition` versions | Optional header `connection_asset` (V82) for owner/archive; without it the owner is whoever registered the first version |

## Decisions taken

1. **A reference pins a copy; it is never a live link.** A workflow references an asset with `dependencies.assets: [{kind, key, version, target}]` in the definition (schema change in `contracts/workflow-v0.schema.json`). `target` is the element that holds the copy: a table in `decisions` (kind `decision`) or a Generate a document step id (kind `documentTemplate`). Connections already had a versioned reference (`dependencies.connections[].definitionVersion`), so they reuse it. The compiler and runtime are unchanged: they keep working on the copy, and a published release contains exactly what was published. This is why a later asset change can never reach a running or published workflow silently.
2. **Checked at validate and publish** by `AssetReferenceCheck` (server side, against the workflow's own workspace only):
   - `WF_ASSET_MISSING` (blocker): the key or pinned version does not exist here (deleted, or the definition came from another workspace, for example through promotion).
   - `WF_ASSET_ARCHIVED` (blocker): the asset (including a connection) is archived.
   - `WF_ASSET_DIVERGED` (blocker): the copy no longer equals the pinned version (decision: RFC 8785 hash of hitPolicy/rows/default; template: SHA-256 of format + body, the registry's own hash).
   - `WF_ASSET_REFERENCE_INVALID` (blocker): the target element does not exist or two references claim it.
   - `WF_ASSET_STALE` (warning): a newer version exists (also for connection pins). Publish needs the usual warning acknowledgement and keeps the pinned version; the release stores the warning, and its manifest lists `assets` (kind, key, version, target) as provenance.
3. **Archive, not delete.** No asset can be hard-deleted through the API; archiving keeps every version, changes no release and no connection binding, and blocks validation/publish of workflows that still reference it. "Deleted" targets are therefore only those missing from the workspace (case 1 above).
4. **Authorization is per kind and never derived from a reference or an owner.** Decisions and templates: read `workflow.read`, manage `workflow.edit`. Connections: read `connection.read`, manage `connection.manage` (so designers can use but not archive a connection, and viewers do not see connections in search). Where-used additionally needs `workflow.read`. A new owner must hold the kind's manage permission at that moment (recomputed fresh, as S48); the owner gains nothing. Non-members get 404; requesters get 403. Validation findings name keys and versions only.
5. **Versioning/concurrency:** new decision versions send `expectedVersion` (409 when stale, 422 when identical or archived); owner and archive changes send `expectedRevision` (409 `WORKFLOW_VERSION_CONFLICT` when stale; a connection's first change uses revision 0 and creates its header).
6. **Registry checks of decision content are structural only** (hit policy UNIQUE/FIRST, 1-50 rows with when/then, 1-10 default results, 64 KiB, no extra properties). Conditions name fields of the workflow that uses the table, so the compiler validates each copy in full in that workflow.
7. **Search** covers name, key and owner display name (case-insensitive substring), kind and owner filters, archived hidden by default, at most 300 results with `truncated`. Only readable kinds are searched (`searchedKinds`).
8. **Where-used** lists non-archived workflows whose current draft or active release references the asset, with each pinned version and a `stale` flag. Older releases are not listed.

## API (contracts/openapi.yaml, tag Workflows)

`listSharedAssets`, `getSharedAsset`, `getSharedAssetUsage`, `listSharedAssetOwnerCandidates`, `createDecisionAsset`, `addDecisionAssetVersion`, `setSharedAssetOwner`, `setSharedAssetArchived`. Document template schemas gain `key` (optional on create; derived from the name, numbered when taken; explicit duplicate is 409), `owner`, `revision`. Audit: `shared.decision.created|versioned`, `shared.asset.owner.changed|archived|restored` (resource `sharedAsset`, id `kind:key`; metadata is kind, version, row count or subject ids; never content). The AI draft rules keep `dependencies.assets` when they rewrite dependencies.

## UI

- **Settings > Shared assets**: search (name/key/owner), kind and owner filters, show archived; each asset shows where it is used (draft and active release versions, Older version badge), versions (decision row count and hit policy), and for managers an owner change (eligible people only) and archive/restore with a conflict message.
- **Studio decision step > Shared decision**: use a shared decision (copies the latest version and links), share this table (creates version 1 and links), and for a linked table: link status, archived/differs/newer notices, Use version N, Save as shared version N+1, Unlink. Renaming a table key or deleting its step keeps links consistent.
- **Generate a document step**: "Start from a saved template" now also links the step; a linked step shows status, Use version N and Unlink. Renaming or deleting the step updates or drops the link.

## Not included (exclusions)

- Shared form/view definitions, UI components and actions (UI02), nested folders, folder permissions, tags, full-text search.
- Live references resolved at run time, automatic upgrade of workflows to a newer version, bulk update of all users of an asset, notifications to owners or users.
- Hard delete of assets, per-asset permissions or ACLs, ownership by groups/teams, cross-workspace or organization-wide asset libraries (promotion carries keys; the target must have its own asset with the same key and identical version content).
- A decision table editor in Settings (decisions are authored in the Studio and shared from there), field-type checks of a shared decision outside a workflow.
- Asset findings in the promotion compatibility report (they appear when the target validates or publishes the promoted draft).
- Connection archive does not hide the connection from the Studio connection picker or affect bindings and dispatch.
- Where-used for older (non-active) releases and archived workflows.
- No Playwright browser journey and no real Docker stack run.

## Verification - 17 October 2026

- Backend: full clean `verify` (`backend/mvnw.cmd -o clean verify`, JDK 21, PostgreSQL via Testcontainers, Flowable) passes: compiler 461 tests, application 763 (759 pass, the same four intentional skips), coverage gate and packaging (`evidence/pl01-assets-backend-verify.log`). New `SharedAssetsApiTest` (5): validate/publish with clean, stale (warning, unacknowledged publish refused, acknowledged publish keeps version 1 in definition and manifest), diverged, archived and restored references; where-used with stale flag; missing key, unknown version, duplicate target, wrong target kinds; the same definition in a newly created workspace reports `WF_ASSET_MISSING` and cannot read the original asset; template references by key with format divergence and derived/duplicate keys; connection stale and archived pins, viewer search excluding connections, designer 403 and manager success on archive, revision conflicts; search by key/name/owner, filters, 400s, requester 403, foreign 404, create/version permission and content checks, owner eligibility, conflict and that the former owner keeps only her role; audit metadata without content. `SecurityMatrixTest` covers the eight new operations for foreign principals (404, no row or revision change). After a final contract tweak (`additionalProperties: true` on decision content for client types) `SharedAssetsApiTest`, `SecurityMatrixTest`, `DocumentLifecycleTest` and `ArchitectureTest` were rerun: 19/19 (`evidence/pl01-assets-backend-focused.log`).
- Frontend: full suite 103 files, 845 tests pass (`evidence/pl01-assets-frontend.log`); new `SharedAssets.test.tsx` (4), `SharedDecisionSection.test.tsx` (5) and an extended Generate a document case; `tsc -b`, full ESLint, `api:check` and `build:mock` pass. Mock handlers (`src/mocks/sharedAssets.ts`) are a simplified double of the server (key-sorted JSON instead of SHA-256, connections only at version 1). Not covered by a UI test: a successful owner change (the mock has one eligible person per kind), covered by the backend test.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=SharedAssetsApiTest,SecurityMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test`; from `frontend`: `npx vitest run src/features/settings/SharedAssets.test.tsx src/features/studio/inspector/SharedDecisionSection.test.tsx`.
