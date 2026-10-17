# S28 — Shared workflow tests and publication checks

Implemented 11 October 2026. Personal saved cases remain browser-local. Designers may explicitly replace the shared suite using **Share and test for publication** in Studio's Test workflow panel. Every shared case requires valid sample answers and an expected business outcome (or Needs attention). Shared samples are designer data: the API requires `workflow.edit`, including reads. Publishers receive only publication errors and pass/fail attestations, never the samples through these endpoints.

## Storage and limits

V26 stores append-only suite revisions and count-only case results. Updates lock the workflow and compare `expectedVersion`; stale saves receive 409. At most 20 cases, unique IDs, 64 KiB combined serialized cases and nesting no deeper than 16 are accepted. Existing per-person dry-run limits apply to each case. A rate-limit response rolls back the attempted suite run; callers retry later.

`GET/PUT /workspaces/{workspaceId}/workflows/{workflowId}/test-suite` reads or saves the suite. `POST .../test-suite/run` runs the current suite against the requested immutable draft revision. Case validation failures produce failed cases without storing payloads or traces in results. Audit actions `workflow.tests.saved` and `workflow.tests.run` carry suite/draft revisions, counts and overall pass status only.

## Publication gate

When a nonempty shared suite exists, publication requires the most recent attestation for that exact suite revision and draft revision to pass. The attestation also binds the canonical suite content hash, compiler version, semantic definition hash, pinned connection definition hashes, and direct child release references. Publication checks this fingerprint against the artifact it actually compiled before deployment. Changed dependencies invalidate the attestation. Empty suites deliberately disable this optional gate; saving an empty suite is an explicit designer action.

Shared publication suites require explicit child outcome scenarios. Recursive child simulation remains available in personal dry runs but is rejected in shared suites until transitive simulation pins can be guaranteed. Tests simulate approvals, external systems and timers; a passing suite is not evidence of live credentials, source-product equivalence, production reliability or customer acceptance.

## Verification

`WorkflowTestSuiteApiTest` covers suite CAS, sample visibility, workspace isolation, successful gating, new-draft invalidation, changed-suite invalidation and failed expectations. The TestPanel browser-unit integration covers sharing structured cases and requesting a result for the exact saved draft. Root verification records final Maven and combined-suite outcomes in the product tracker.

12 October integration checkpoint: the shared-suite API tests (2) and conjunctive compiler tests (2) passed their focused backend run. Focused frontend checks passed 69 tests and the full TypeScript build passed. Both fresh mock browser journeys passed on port 4181: sharing, count-only results, publisher sample denial, tested-draft publication, edited-draft invalidation, and conjunctive authoring retained after reload. Evidence: `evidence/shared-rules-browser-1012.log` and `evidence/shared-rules-mock-build-1012.log`. These mock journeys exercise the UI; the production pinned-DMN evidence tests belong to the combined backend verification.

## Remaining decision/UI scope

Decision rows now support either the legacy single-field comparison or a flat **All comparisons** conjunction of 2–8 distinct fields. Rows must remain provably disjoint; there is an explicit default, and canonical UNIQUE behavior is compiled as FIRST plus a trailing default. DMN receives one input column per distinct field. Draft evaluator, simulator and pinned production DMN execution use the same row conditions; decision evidence retains all candidate rows and all input values under existing data permissions. Duplicate fields, nested groups and OR decision rows are rejected. [S40](S40-decision-hit-policies.md) later added the FIRST hit policy and OR rows. This increment does not add arbitrary FEEL, other hit policies, a Pega-style UI designer, reusable form layouts or conditional form rules.
