# Hackathon acceptance record

Qualification date: 18 October 2026. Scope: the opt-in `acceptanceProfile: "tier1"` defined by the [requirements baseline](23-hackathon-requirements-baseline.md). Broader product epics and organization approvals remain separate.

The implemented profile includes bounded sequential definitions, deterministic guided drafting, independent publication with source provenance, pinned business calendars and email templates, task authorization and reassignment, explicit closure policies, durable SLA detection and mail outbox, scoped evidence export, and independently built authoring/task components. The [tool guide](../tools/hackathon/README.md) provides reproducible commands. Local fixtures use synthetic policies and provenance; they are not customer migration evidence.

## Acceptance coverage

| Scenario | Evidence and qualification limit |
|---|---|
| H01 Guided creation | Real Edge journey verifies missing answers, keyboard operation, desktop/mobile accessibility and an unpublished editable Tier-1 draft. No model subscription is required. |
| H02 Definition limits | `Tier1AcceptanceTest` exercises ten-state/five-role boundaries and excess rejection. |
| H03 Deterministic routes | Compiler rule/golden tests validate exclusive routes and invalid structures; guided definition tests cover numeric gates. Customer golden routes still require owner data. |
| H04 Task eligibility | Runtime authorization/work-management tests cover eligibility, ownership, reassignment and denials. `PermissionRevocationTest` requires the very next request to deny on both instances after revocation. |
| H05 Concurrency/replay | Runtime concurrency tests plus the live synthetic demo cover exact replay and changed-payload conflicts. |
| H06 Business time | `Tier1AcceptanceTest` covers holidays, split intervals, minute durations and ambiguous DST boundaries. Calendars remain synthetic. |
| H07 SLA detection | Integration tests enforce one durable breach; the live demo waits for a real one-minute deadline and verifies the breach survives application restart. |
| H08 Mail | Integration tests exercise bounded retries and suppression; transport tests use real SMTP framing. Live capture verifies rendered messages and provider acceptance, which does not establish delivery to a real mailbox. |
| H09 Closure | Integration tests cover refuse-open and cancel-open policies, concurrent decision/closure and late decisions. Live demo confirms no remaining open task after cancellation. |
| H10 Pinned release | Runtime release-pinning tests and source preparation tests cover immutable runtime definitions and artifact/source linkage. Trusted pipeline review is still required; the runtime validates provenance shape, not the existence of a remote Git commit. |
| H11 Audit/evidence | Runtime audit tests cover append-only restrictions. Hackathon acceptance grants and revokes `audit.read` through the public API, checks JSON/CSV export and excludes input payload. |
| H12 Source reconstruction | Existing bounded import and migration acceptance tests apply to supported synthetic mappings. Genuine Pega equivalence remains pending HC04. |
| H13 Recovery | Existing resilience tests cover durable transitions and effect recovery. The local demo separately records graceful application restart after an SLA breach; that run alone does not prove crash-during-transition behavior. |
| H14 Components | Separate authoring/task library builds and the authenticated sample-host browser test. Browser bundles explicitly replace Node environment checks and select the matching component stylesheet. |
| H15 Local quality/load | Dated results below; small smoke runs do not qualify the declared 50-user dataset or production capacity. |
| H16 Dependency/compliance | Dependency artifacts are local evidence, subject to license/security and owner review. Orchesstra compliance remains pending. |

## Remaining owner gates

HC01?HC07 in the [baseline](23-hackathon-requirements-baseline.md#12-remaining-external-decisions-and-sign-off) remain open: authoritative Orchesstra checklist, license interpretation and review, enterprise identity/host contracts, genuine source and business sign-off, approved calendars and mail bindings, production retention/capacity/backup/support policy, and contractual savings evidence. Local coding cannot establish these facts. No OpenShift deployment is part of this hackathon completion claim.

## Dated verification results

- Full frontend suite: **877/877 tests passed across 114 files** on the final frontend source, using two thread workers and a 30-second per-test ceiling (`evidence/hackathon-frontend-final-green.log`). An earlier run had one 15-second timeout during competing builds; its isolated rerun also passed (`evidence/hackathon-studio-recheck.log`).
- Production SPA and both independent browser components: build passed, including TypeScript checks and sample-host packaging (`evidence/hackathon-build-final.log`).
- Contract checks: 88/88 passed (`evidence/hackathon-contract-final.log`). The read-only BPMN check is explicitly exempt from mutation audit requirements.
- Release preparation: three offline tests passed (`evidence/hackathon-tools-final.log`).
- Load harness smoke: two virtual users, forty initial cases, thirty seconds measured, zero unexpected errors. Read p95 102 ms and mutation p95 408 ms. This is not the full capacity qualification (`evidence/hackathon/load-smoke.json`).
- Frontend CycloneDX SBOM: 503 components from the locked dependency tree, with optional platform-specific packages omitted (`evidence/hackathon/frontend-sbom.json`). License and vulnerability approval remain pending.

Raw logs and generated dependency artifacts are intentionally ignored by Git. Preserve the local `evidence/hackathon*` files with the candidate receipt when sharing the qualification package. The checked-in record reports the observed outcomes; it does not imply that ignored artifacts accompany a fresh clone.

- Live local runtime scenario: passed publication, replay, real SLA, SMTP capture, closure and scoped evidence export. Application restart recovered in 28.42 seconds; 8 rendered messages captured. Provider states: providerAccepted. Evidence: `evidence/hackathon/local-demo.json`, `evidence/hackathon-demo-final.log`. This preceded the final frontend-only inbox guard; backend code was identical.

- Real Edge/browser acceptance: both guided creation and embedded authoring/task-host scenarios passed, including serious/critical axe checks and denied requester access with no browser errors (`evidence/hackathon-browser-final.log`). This uses the final frontend source.

The integrated case-management slice owns a separate CMMN execution boundary. Its controller follows the existing `cases.api` convention; only `CaseService` may import Flowable, and only public CMMN/task/identity-link APIs are allowed there. Architecture verification continues to reject Flowable dependencies elsewhere outside the workflow runtime.

## Dependency findings

The production frontend audit returned zero known vulnerabilities (`evidence/hackathon/frontend-audit.json`). Docker Scout indexed 361 runtime packages. The initial high/critical scan reported 44 distinct advisories (49 package findings): eight advisories in Tomcat/Jackson with available patches and 36 in base-image RPMs marked `not fixed` by the scanner. This is a failed security release gate until remediated or formally dispositioned; it is not an enterprise compliance pass.

The Maven parent overrides Tomcat to 11.0.25, Jackson 2 to 2.21.7 and Jackson 3 to 3.1.7, retaining the existing minor release lines. Patch references: [Apache Tomcat security advisories](https://tomcat.apache.org/security-11.html), [Jackson advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24), [Jackson 3.1.7 release](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7). No blanket suppression or unsupported base-image removal is used. The Red Hat image findings remain in the platform/dependency owner gate, with package-level details in `evidence/hackathon/runtime-cves.sarif`.

Load preparation exposed an unnecessary uploader advisory lock on every workflow start, including forms with no files. File-free starts now skip that lock; starts consuming staged files retain uploader-before-file ordering. A regression holds the upload lock on a separate database connection and verifies a file-free request can still start. The first full load preparation was stopped to apply this correction; it has no measured qualification result. Its synthetic cases remain in the fixture database.

Final architecture/case/revocation/hackathon API recheck before dependency patches: 17 tests passed, including all six architecture checks and public grant/export/revoke behavior (`evidence/hackathon-backend-recheck.log`). Security patch compatibility and the upload-lock regression are separately rechecked below.

## Final code verification checkpoint

- Backend full baseline: 495 compiler tests passed; 791 application tests ran with four intentional skips and three failures. The two architecture failures were corrected by the API package move and narrow CMMN adapter boundary. The remaining security-matrix failure came from missing well-formed request bodies for the new case/view create operations; both are now supplied so the test reaches authorization.
- On the corrected source and patched dependencies, final `verify` passed **30/30 focused tests**, packaging and the compiler coverage gate, reusing the full baseline coverage data (`evidence/hackathon-final-verification.log`). This is full-baseline-plus-corrections evidence, not a claim that the entire application suite was rerun after the patches. The separate patch compatibility run passed 18 tests; the attachment suite passed all 21 tests including the new lock regression.
- The security-patched image passed both real Edge acceptance scenarios (`evidence/hackathon-browser-patched.log`). Frontend lint and E2E TypeScript checks passed.
- The rescan confirms all eight fixable Tomcat/Jackson advisories are absent. **36 base-image advisories across 15 packages remain** (`evidence/hackathon/runtime-cves-patched.sarif`). The security/compliance release gate remains open.
- Full load qualification is running against `orqix/orqix:hackathon-security`; preparation is excluded from measurement. No pass is claimed before its result is recorded.

## Long-run fixture corrections

The load harness now writes a resumable workflow manifest, partitions an existing case census without shared ownership, restores eligible task ownership through the operator API when a fixture identity was retired, and authenticates all virtual-user session sets again after preparation. Identity repair and authentication are excluded from measured HTTP traffic. The workload uses independent session sets for four seeded role personas; it does not qualify fifty distinct enterprise directory identities.

The dedicated local stack retained seven subject rows from an earlier unpinned Keycloak realm incarnation. Those retired identities were marked disabled without deleting their business or audit history. The development realm now pins all twelve user IDs. An actual Keycloak container recreation preserved all twelve IDs (`evidence/hackathon/identity-recreation.json`), preventing that fixture mismatch from recurring. Enterprise identity lifecycle integration remains HC03.

Five offline tool tests pass, including disjoint/exhaustive case ownership and explicit unique realm identities. The refreshed-session smoke and reused-fixture smoke both passed with zero unexpected errors. The latter resumed 48 stored cases into a 60-case dataset; read p95 was 14 ms and mutation p95 137 ms (`evidence/hackathon/load-smoke-reused.json`). Full qualification resumes the existing 50-user fixture; these smoke results do not substitute for its declared warm-up and measurement periods.

The local evidence pack also contains 657 collected dependency notice/license text files and the base image's distributed license files (`evidence/hackathon/third-party-notices.txt`, `evidence/hackathon/base-image-licenses/`). Collection does not establish license compatibility or organizational approval. The prepared source artifact and sidecar reconcile the committed sample and SHA-256 values; independent business review and publication remain required.
