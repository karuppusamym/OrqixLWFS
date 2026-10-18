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

- Full frontend suite: 876/877 passed in the combined run; one Studio HTTP-failure-route test exceeded its 15-second limit while builds competed for resources. The same test passed on its isolated rerun (one pass, sixteen filtered cases). This is a combined evidence result, not a clean all-green full run. Logs: `evidence/hackathon-frontend-final-full.log`, `evidence/hackathon-studio-recheck.log`.
- Production SPA and both independent browser components: build passed, including TypeScript checks and sample-host packaging (`evidence/hackathon-build-final.log`).
- Contract checks: 88/88 passed (`evidence/hackathon-contract-final.log`). The read-only BPMN check is explicitly exempt from mutation audit requirements.
- Release preparation: three offline tests passed (`evidence/hackathon-tools-final.log`).
- Load harness smoke: two virtual users, forty initial cases, thirty seconds measured, zero unexpected errors. Read p95 102 ms and mutation p95 408 ms. This is not the full capacity qualification (`evidence/hackathon/load-smoke.json`).
- Frontend CycloneDX SBOM: 503 components from the locked dependency tree, with optional platform-specific packages omitted (`evidence/hackathon/frontend-sbom.json`). License and vulnerability approval remain pending.

Raw logs and generated dependency artifacts are intentionally ignored by Git. Preserve the local `evidence/hackathon*` files with the candidate receipt when sharing the qualification package. The checked-in record reports the observed outcomes; it does not imply that ignored artifacts accompany a fresh clone.
