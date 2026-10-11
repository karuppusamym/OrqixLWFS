# Product closeout and remaining work

Updated 10 October 2026. This is the current remaining-item register. The R0 implementation checklist, current build batch, future product roadmap and customer acceptance are separate scopes. Closing the batch does not mean every R1–R3 epic is implemented. [Build evidence](19-product-build-tracker.md) records the checks for the combined tree.

## Current build batch - complete

| Item | Result | Acceptance |
|---|---|---|
| Cross-file migration findings | Complete; frontend checks pass | Reason/source-kind/file grouping; full JSON/CSV with hashes; formula-safe CSV; exports blocked when a file report fails. [S18](build/S18-project-findings.md) |
| Task priority and aging | Complete; real backend and frontend checks pass | Durable 0–100 priority, assignee/operator authorization, version conflicts, required reason, audit/timeline, filter-bound signed pagination, oldest/newest sort and displayed age. Aging does not automatically escalate tasks. [S19](build/S19-task-priority.md) |
| Microsoft historical version selection | Complete with scripted Graph and frontend checks | Integration administrator explicitly loads an approved drive/item history and chooses a pin. Complete bounded results, stale response rejection and no latest fallback. This is a connection test/configuration tool; requester tenant browsing is not implemented. [Documents](microsoft-documents.md) |
| Compare published workflow versions | Complete; frontend and browser checks pass | Choose any two loaded immutable releases; load more to select older releases; display definition changes and fetch failures; layout ignored. Same-version comparison is supported. |
| Step notes | Complete; compiler, frontend and browser checks pass | Plain text, 2,000 characters per step, at most 100 entries; draft autosave, rename/delete cleanup, immutable published definition/export and version diff. Compiler rejects orphan notes. Notes change source hash, including exact-draft migration review, but do not change generated execution semantics. These are annotations, not threaded discussions. |
| Existing projects/review, DMN/operators, banking examples, date waits and Assistant | Retained | Existing committed implementation and regressions are included in coordinated checks. Banking examples remain illustrative. |

Verification: 281 compiler cases; 457 unique application cases (455 pass, 2 optional live-provider skips), with priority/security corrections verified in a focused rerun; 554 frontend tests; 69 contract checks; 39 mock browser passes and 4 real-stack-only skips. Packaging, typecheck, lint, build and API drift pass. Detailed logs and the limits of combined evidence are in [doc 19](19-product-build-tracker.md#closeout-verification).

## Customer and source acceptance still open

These are not infrastructure-only tasks and cannot be closed using synthetic fixtures.

| ID | Required input and acceptance | Current boundary |
|---|---|---|
| EXT01 | Genuine Pega/LWFS exports, versions, resolved rules and owner-approved expected scenarios (D03/D04, B2/B3) | Pega inventory and reviewed reconstruction exist; universal native Pega rule conversion and source-runtime equivalence are unbuilt/unaccepted. Synthetic target evidence is explicitly labeled. |
| EXT02 | Microsoft tenant app consent, resource grants, approved files/drives/download hosts and securely provisioned credentials | Scripted Graph tests establish adapter behavior, not customer access. OAuth provisioning/automatic refresh needs a defined credential contract. |
| EXT03 | Approved procurement/banking/MCP contracts and observed outcomes (D07/D09) | Stub/provider checks are not acceptance against customer systems. |
| EXT04 | Banking process owner, policy, jurisdiction and representative business scenarios (B1–B6) | KYC/credit/dispute examples are configurable samples; domain or regulatory approval is not inferred. |

## Product roadmap still open

These are engineering features, not infrastructure. They remain in the [full backlog](10-full-product-backlog.md) and must not be checked off because the current build passes.

| Area | Remaining functionality |
|---|---|
| Migration breadth | Form import, optional reviewed AI mapping proposals, connector element-template recognition, DMN files within multi-file projects, native user-task multi-instance and broader BPMN loss-aware import |
| Execution and cases | Boundary timer/error events, message correlation, mutable correction forms, nested/object collections, adaptive case stages, business calendars and recurring escalation |
| Banking and rules | Full automated decision evidence with candidate rows/input, effective-dated rule packs, cross-stage separation of duties and multi-currency rules |
| Operations and authoring | Audited incident variable correction, waiting-on-nothing detection, swimlanes, threaded element discussion, further authoring modes, in-flight migration and canary releases |
| Documents and integrations | OCR, requester file browsing/attachments, OAuth lifecycle automation, Teams/mail and approved write-capable agent tools |
| Platform organization and delivery | Remaining R1–R3 folder/shared-asset organization, advanced triggers/search/analytics, Git promotion and environment lifecycle contracts described by the backlog |

## Infrastructure excluded from this build

Production OpenShift deployment, PingFederate, enterprise network/secret setup, CI execution, HA database/restore exercises, production load/capacity/retention/on-call acceptance and a refreshed deployment of these changes remain open. Testcontainers integration checks do not establish these gates. No existing Compose volumes or user data are reset by this closeout.

## How to run the implemented subset

The simple, medium and complex definitions in `examples/simple-review.json`, `examples/medium-bounded-rework.json` and `examples/complex-batch-review.json` are exercised by `WorkflowPatternsTest` on PostgreSQL/Flowable. Conversational requests/tasks use the Assistant with explicit final confirmation. Workflow document references use registered approved connection operations with stable drive/item identifiers and an explicit version or retained snapshot. Iteration uses bounded scalar `forEach` children (sequential or parallel), ordered collected outputs and bounded repeated children. The outer graph remains acyclic.

For local setup and demo execution, use the repository README and [demo guide](demo-guide.md). Deployment validation belongs to the excluded infrastructure stream; customer source and tenant checks require the inputs above.
