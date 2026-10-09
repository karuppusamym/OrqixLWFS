# Designer, connectors and migration next steps

Reviewed 9 October 2026. This is an implementation assessment and staged delivery proposal, not a claim of Camunda or Pega parity.

## Delivered in this pass

- The Call a system inspector uses the workflow's exact pinned connection version for operation choices and field schemas. An unavailable version is reported instead of silently showing a newer contract. The response-field lookup follows the same rule.
- Renaming a kept connector result updates all structured references: routes, decision rows, later connector inputs and workflow outputs. Name and reference changes are one undoable edit.
- Object response field types resolve nested JSON Pointers and escaped property names correctly. Unsupported or missing pointers remain unknown.
- The inspector distinguishes disabled connections from missing addresses and displays query failures with retry controls.
- Connection settings explain setup, read-only testing and designer mappings. Testing requires a configured, enabled saved binding. The operations table scrolls within its container on narrow screens.

## Where Orqix stands against Camunda

Orqix has a tested approval-workflow slice: business forms, task claims and decisions, decision tables, pinned releases, connector outcomes, operator recovery, workspace permissions and audit. The opportunity is a simpler business-facing experience for this slice; no user study or benchmark currently establishes superiority.

Camunda already documents a connector SDK, connector templates and integration tooling. Its Camunda 7-to-8 migration journey covers model, code and architecture changes, and its migration tooling includes model analysis/conversion and a runtime data migrator. Those tools target Camunda 8; they do not establish Orqix import compatibility. See [Camunda connectors](https://docs.camunda.io/docs/components/connectors/introduction-to-connectors/), [migration guide](https://docs.camunda.io/docs/guides/migrating-from-camunda-7/) and [migration tooling](https://docs.camunda.io/docs/8.7/guides/migrating-from-camunda-7/migration-tooling/).

The subsequent [migration workbench](17-migration-workbench.md) implements a Camunda subset converter, migration API and UI, plus Pega archive assessment and reviewed manual mappings. Automatic native Pega rule conversion remains unimplemented. The Flowable engine's wider capabilities do not automatically make those capabilities available through Orqix's canonical schema or designer.

## Recommended delivery order

| Priority | Deliverable | Completion evidence |
|---|---|---|
| 1 | Connector onboarding: select an OpenAPI operation, review input/output schema, register a versioned contract, then bind it per workspace | Positive/negative contracts; request mapping; response validation; endpoint policy; exact version pinning; credential-isolation tests |
| 2 | Designer mapping preview and saved test scenarios; highlight fields unavailable on a path; version comparison | Sample-input previews do not call external systems; real test runs are explicitly started; undo/reload and threshold/path regressions pass |
| 3 | Medium workflow patterns: bounded rework, durable timers/escalation, matched parallel branches and pinned child workflows | Schema/compiler/editor/runtime all support each pattern; restart, completion races and effect count tested |
| 4 | Camunda definition import with inventory, per-element issues, binding remediation and draft conversion | Separate Camunda 7/8 fixtures; unsupported constructs blocked; source maps and behavior-equivalence tests; publication remains explicit |
| 5 | Pega discovery and one representative process pilot | Source/version identified; dependency inventory; business owner verifies routing, rules, forms and connector outcomes |

Email/Teams, inbound webhooks, approved database operations and agent/tool adapters should each follow a versioned contract and acceptance tests. They need separate implementations; adding palette entries alone does not provide working connectors.

## Pega migration path

Pega exports applications, products or rulesets as archives containing rules and data for movement between Pega environments. That does not establish a portable executable format for Orqix. See [Pega Export wizard](https://academy.pega.com/topic/exporting-application-product-rule-or-ruleset-using-export-wizard/v5/in/53886/53561/53691) and [application migration](https://academy.pega.com/module/application-migration/v6).

1. Obtain the Pega version, a sanitized native archive, dependency inventory and expected behavior for one simple process and one case with stages/SLA. Preserve source bytes and checksums; identify content before choosing a parser.
2. Inventory flows, assignments, work queues, routing, decisions, data transforms, integration contracts, forms, stages, rule resolution and SLA/calendar behavior. Give every source element a mapped, manual or unsupported disposition.
3. Convert only supported definitions into drafts. Rebind connectors and groups explicitly. Declarative rules, procedural activities and adaptive case behavior need remediation or new target capabilities; do not flatten them silently into a sequential graph.
4. Compare approvals/rejections, boundary amounts, routing, timeouts, retries and external-effect counts with the source system. Publication requires resolved blockers and business approval.
5. Pilot new starts in Orqix while existing cases finish in Pega. Live case state, history, attachments, identities and credentials require separate migration plans.

The existing [migration strategy](05-migration.md) and [product backlog](10-full-product-backlog.md) provide the broader contracts. A native Pega parser and successful migration cannot be claimed until representative source artifacts have been inspected and tested.

## Validation of this pass

All 154 frontend unit/component tests passed, including five new regressions for pinned definitions, unavailable versions, JSON Pointers, reference rewriting and atomic undo. Frontend lint and TypeScript checks passed; browser-test TypeScript passed; the production Docker image rebuilt successfully. Seven existing real-stack keyboard/accessibility browser checks passed, and both new connector browser checks passed after correcting their test locators. The application is running at http://localhost:8080. No backend behavior was changed in this pass.

Evidence: [frontend tests](../evidence/ui-review/frontend-connector.log), [image build](../evidence/ui-review/connector-compose-build.log), [connector browser tests](../evidence/ui-review/browser-connector-final.log). Changes remain local and uncommitted.
