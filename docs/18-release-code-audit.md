# Current release code audit

Reviewed 9 October 2026. This audit distinguishes reproducible defects, implemented features and future scope; it does not claim universal Camunda/Pega compatibility or completion of production gates.

## Fixed and tested

- My tasks chooses available work when no task is assigned. Explicit tab choices remain selected, and switching tabs closes the previous task panel.
- Operators can open recovery guidance directly from an attention row on desktop or mobile. Viewers and requesters do not receive operator controls. Unknown outcomes lead with reconciliation; retry remains governed by server permissions and effect state. Cancellation retains separate confirmation.
- Published and unpublished workflow states have icons and text. Start and Edit retain distinct emphasis; the attention filter already had a pressed state, so those stale review notes were corrected without duplicating work.
- AI explanations use `explain-v2` with compiler readiness and findings for the current workspace. The response exposes validation separately, and the UI shows that authoritative result even if AI prose claims a missing group is ready. The explanation remains model-generated and may be inaccurate; validation does not prove that production endpoints work. Provider verification in this audit uses a stub, with no live model spend.
- Pega reviewed mappings reject the unchanged version placeholder and non-string versions. Excluded files cannot also claim mapped target nodes, and explanation/target IDs retain explicit types. These checks assess manual mapping integrity, not native Pega semantic equivalence.

## Verification evidence

- [Full compiler and application run](../evidence/ui-review/release-audit-backend.log): 204 compiler tests; 125 application tests run, with one opt-in live-provider test skipped. The later Pega guard change is covered by [the final four safety regressions](../evidence/ui-review/release-audit-pega.log).
- [Frontend](../evidence/ui-review/release-audit-frontend.log): all 401 tests pass; TypeScript, lint and API type checks pass.
- [Production image build](../evidence/ui-review/release-audit-build.log) and [real-stack browser suite](../evidence/ui-review/release-audit-browser.log): 18 checks passed in the broad run; an old request-reference selector failed and prevented four serial accessibility checks. The selector was corrected to use current request references, and [all five accessibility checks then passed](../evidence/ui-review/release-audit-accessibility.log). Together, all 23 real-stack checks pass; the one mock-only scenario is skipped.

The app runs locally at http://localhost:8080. Existing work, database contents and unrelated changes were preserved. Changes remain uncommitted.

## Still open

| Scope | Remaining work | Completion evidence needed |
|---|---|---|
| AI generation transport | Durable asynchronous jobs with 202/polling, restart behavior, idempotent retries, retention and access checks | Slow-provider, restart, access-revocation and repeated-submit tests; current generation remains synchronous with deadlines |
| Broader workflow patterns | Timers/escalation, matched parallel branches, pinned child workflows and bounded rework | Canonical schema, compiler, editor and runtime support plus race/restart/behavior tests |
| Native Pega automatic conversion | Inspect representative real exports and implement supported rule semantics | Source version/dependencies and source-versus-target routing, SLA, stages and effect outcomes |
| Full Camunda coverage | Constructs currently reported as unsupported | Explicit supported-subset extensions and equivalence fixtures; unsupported constructs continue to block conversion |
| Production deployment | Real identity provider, procurement endpoint, cluster, restore exercise and CI execution | Environment access and production-gate results; local/stub checks do not substitute for these |

The remaining items are visible scope, not completed features. See [feature status](feature-status.md), [migration workbench](17-migration-workbench.md) and [full backlog](10-full-product-backlog.md).
