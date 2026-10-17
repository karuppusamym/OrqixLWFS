# S18: cross-file migration findings review

Migration projects now aggregate the project dependency findings and every available per-file compatibility report in one read-only review panel. Reviewers can group by reason code, source kind or file and filter severity or unresolved findings. File and element identities remain visible inside each group. The existing per-file mapping and ordered conversion form remains the place to resolve mappings; filtering does not acknowledge a finding, alter a report or relax conversion/governance checks.

The panel uses the existing workspace-scoped project GET and migration GET endpoints, with at most ten file-report requests under the project upload limit. It shares the migration query cache with the conversion form, so successful conversion updates the aggregate without a separate reporting API. The `ProjectFile` UI type directly uses the published contract, including required status and nullable converted workflow ID.

## Export contract

`project-findings.json` has format `orqix-project-findings-1` and source validation status `adapterAnalysisOnly`. It contains the project metadata, dependency issues, conversion order, all file compatibility reports (including assumptions, adapter versions, mapping requirements and generated decision checks), and the complete flattened findings. Each file report retains its full source hash. Files that could not be analyzed have a null report; that absence is explicit rather than an invented source hash.

`project-findings.csv` includes project ID, file, migration ID, source system/hash, adapter version and every issue field, including target node ID and suggested action. Files without any per-file issue receive a `FILE_PROVENANCE` row so their source hash is still represented. Project dependency rows use source system `project` and a null source hash; source evidence remains in the per-file rows. All cells are quoted and escape embedded quotes. Spreadsheet formula prefixes, including whitespace followed by `=`, `+`, `-` or `@` and leading tab/newline controls, receive an apostrophe prefix.

Both exports ignore the active review filters. They remain disabled while any report is loading/refetching or failed. The UI labels partial analysis and offers retry for failed file requests. Export construction also rejects a missing report for an analyzed file, preventing a partial export from being called complete.

Only current API read responses are used. Raw source files, stored project source payloads, conversion mapping requests, source maps and connection credentials are excluded. JSON output contains report evidence; it does not attest that a Pega or Camunda source engine ran or that source execution equivalence was established. Genuine source-owner acceptance and infrastructure/tenant validation remain separate work.

## Verification

Focused frontend tests cover all three groupings; report identity/provenance; complete reports and files without issues; CSV formula and syntax escaping; credential-bearing source-map exclusion; deterministic file order independent of job fetch completion order; rejection of incomplete exports; multi-file viewer review/filter controls; report-load failure/export disabling and retry recovery. Existing child-first project conversion tests remain in the focused suite. No backend change, new endpoint, database migration or Maven build is required for this slice.

Local verification on 2026-10-10: **14 focused tests passed** across these three files, and focused ESLint passed for the changed migration sources/tests. Shared workspace typecheck/lint encountered concurrent task/studio changes; the coordinated integrated build records the final repository-wide result separately.

Run:

```powershell
npm --prefix frontend run test -- --run src/features/migration/projectFindings.test.ts src/features/migration/ProjectPage.test.tsx src/features/migration/reportExport.test.ts
npm --prefix frontend run typecheck
npm --prefix frontend run lint
```
