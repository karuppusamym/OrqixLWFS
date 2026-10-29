# S112 - Native Pega rule archive conversion, MIG11 (29 October 2026)

**Extended by [S115](S115-pega-native-completion.md)** (circumstances and date ranges of decision rules, subflows, split-joins, business calendars, escalations, case-level service levels, delegation provenance) and [S117](S117-pega-native-round-3.md) (connectors as call steps, simple transforms, creation validation, case types as CMMN case definitions); the "blocked when referenced" lists below are as of S112.

MIG11 was deferred on purpose ([ADR 0003](../adr/0003-minimum-demo-pega-scope.md)): the synthetic demo did not need it, and no genuine Pega export was available to define a grammar. The user has now asked for it to be built. This record describes what is implemented, what it was tested against, and what it does not establish.

**The grammar is synthetic.** No genuine Pega or customer export was available. The decoder reads the clipboard XML shape that Pega documents for rule and data instances (a `<pagedata>` page, one element per property, PageList and PropertyList values as `REPEATINGTYPE` lists of numbered `<rowdata>`), with property names taken from Pega's public rule properties where they are known. The fixtures follow that shape. Whether real RAP/JAR exports of a given Pega release match it is **AC01/EXT01 customer qualification** and is not established here. No Pega release is certified.

## Delivered

A new source format `pegaRules` (base64 ZIP, RAP or JAR) goes through the existing migration pipeline: upload, compatibility report, explicit mappings, compiler-gated draft, separate publication, optional independent migration review.

| Part | What it does | Where |
|---|---|---|
| Archive decoder | Same bounds as the archive inventory (200 entries, 1 MiB each, 4 MiB total, safe paths). Every entry keeps its SHA-256. `<pagedata>` pages are read with a strict grammar: text values, PageList and PropertyList only; rows numbered from 1; no attributes other than `REPEATINGTYPE`/`REPEATINGINDEX`; no namespaces, embedded single pages, repeated properties or DTDs. Rule identity (`pxObjClass`, `pyClassName`, `pyRuleName`, `pyRuleSet`, `pyRuleSetVersion` NN-NN-NN, `pyRuleAvailable`, `pzInsKey` that must match the identity) is required. Anything else is a refused entry with a reason. | `pega/rules/RuleArchive.java`, `Page.java` |
| Effective-rule resolution | See "Rule resolution" below. Every candidate of every rule key gets an outcome, so the chosen rule and each shadowed one are recorded. | `pega/rules/RuleResolution.java` |
| Conversion | Per starting point (an effective case type, or an effective flow marked `pyIsStartingFlow`), every referenced rule is resolved in the case class context and converted or blocked. See "Rule types". | `pega/rules/NativeConversion.java`, `NativeDecisions.java` |
| Pipeline | Upload stores the source, the report of the first starting point and an inventory row per entry. Conversion takes only `workflowKey`, `name`, `groupMappings`, `approvalRoutes`, the acknowledgements and `pegaStartRule`; empty mapping objects are ignored, anything else is refused. It recomputes the plan from the immutable bytes, builds the definition through the shared BPMN analyzer and converter, the normal compiler gates the draft, and the per-rule results are recorded. Publication stays separate. The draft source is `pega-native-import`. | `internal/PegaRuleArchives.java`, `MigrationService` |
| Storage | **V200**: `pegaRules` source format; `migration_pega_rule` (one immutable row per entry with SHA-256, identity or refusal reason) and `migration_pega_rule_conversion` (per-rule resolution, conversion outcome, target kind and ids, target fragment SHA-256, reason codes for the converted starting point). | `V200__pega_native_rules.sql` |
| API | `POST /migrations` with `sourceFormat: pegaRules`; `GET /migrations/{id}/pega-rules` (inventory, ruleset stack, class chains with chosen and shadowed candidates, every starting point with its report, per-rule results and assignments to map, and the recorded conversion); `pegaStartRule` on convert. | `contracts/openapi.yaml`, regenerated `schema.d.ts` |
| Workbench | **Import workflow > Native Pega rule archive**. The review shows the application and ruleset stack, archive blockers, a starting-point choice, and three tabs: per-rule results (resolution, result badge, reasons, target kind and ids, source and target SHA-256, filter by result), effective rules (class chain, every key with its candidates and outcomes) and the archive inventory. Each assignment gets a choice of which of its two flow actions approves; nothing is inferred from action names. Work queues are mapped to workspace groups in the shared form. Mock mode refuses the upload and points to the real backend, as for the other Pega formats. | `features/migration/PegaNativeReview.tsx`, `JobReview.tsx` |
| Offline oracle and fixtures | `tools/migration-equivalence/pega_native.py` is an independent Python implementation of the decoding and resolution rules (it does not convert). `examples/migration/pega-native/generate.py` builds two deterministic synthetic archives and the oracle's expected results. The backend test compares its own resolution with those files. | `pega_native.py`, `examples/migration/pega-native/` |

## Rule resolution

The archive must contain exactly one available `Rule-Application`. Its `pyRuleSets` list is the ruleset stack in precedence order; `pyBuiltOnApplications` may name only `PegaRULES`, whose rules are not in the archive. A missing, duplicated or unsupported application blocks the whole archive.

1. **Class chain** of the case class: the class, then its pattern-inheritance prefixes (split on `-`) when the class rule allows pattern inheritance, then its directed parent from `Rule-Obj-Class` (`pyDerivesFrom`), repeated for the parent; `@baseclass` last. A class without a class rule in the archive ends the chain. Cycles block.
2. **Candidates** of a key (rule type + name, name case-insensitive) are the rules whose applies-to class is in the chain.
3. **Filters**: ruleset not in the stack (`rulesetNotInApplication`); a different major version, or a minor/patch above the stack's (`versionNotInApplication`); availability No (`notAvailable`).
4. **Withdrawn** rules are never chosen and hide lower versions of the same class, ruleset and major version (`maskedByWithdrawn`).
5. **Ranking**: closest class, then higher ruleset in the stack, then higher version.
6. **Outcome**: a circumstanced candidate leaves the key unresolved (`circumstanceUnresolved`; circumstances depend on case data and are never evaluated). Two candidates with the same rank are `ambiguous`. A Blocked best candidate stops resolution (`blocked`). A Final candidate overridden from another ruleset is `finalOverridden`. Otherwise the best is `selected` and the rest `lowerRanked`.

Date-range qualifiers, access/privilege rules, built-on application rules and rule delegation are not evaluated. This ranking is our reading of Pega's published rule-resolution description, not a certified reproduction of every release's behavior; that is part of AC01/EXT01.

## Rule types

| Source rule | Target | Converted when | Blocked when |
|---|---|---|---|
| `Rule-Obj-CaseType` | One workflow; stages become workflow `stages` grouping their flows' steps; stage service level becomes the stage `sla` | 1-20 stages with keys, 1-5 processes each, a New harness | Alternate stages, conditions, or any other property |
| `Rule-Obj-Flow` | Steps and routes, via generated BPMN through the shared analyzer and converter (the XPDL and Blueprint path). Flows of a case type are joined end to start in stage order | Start, End (no status, Resolved-Completed or Resolved-Rejected), Assignment, Decision shapes | Utility (activity), Integrator (connector), Subprocess, Split-Join, any other shape; ends with other statuses; reused flows |
| Assignment shape | Approval step | Routed to a literal work queue (`ToWorkbasket`), exactly two flow actions | Worklist, current-operator or custom routers; other action counts |
| `Rule-Obj-FlowAction` | Approval outcome (reviewer chooses which approves) | Only a section reference (reported for review: the standard decision form is used) | Pre/post activities, validation, data transforms |
| `Rule-Declare-DecisionTable`, `Rule-Declare-DecisionTree` | DMN FIRST table (the [S55](S55-pega-decisions.md) table shape) checked by the DMN analyzer, then a decision step and a branch on its result | One condition column / one tested property; text equality or literal numeric comparisons; plain text results; explicit otherwise; explicit first-match | More columns, expressions, functions, mismatched types |
| `Rule-Obj-When` | Branch condition on a Fork decision shape | 1-8 comparisons of a property with a literal, joined only by AND or only by OR | Mixed connectives, functions, other forms; forks with more than one When (likelihood order is not reproduced) |
| `Rule-Obj-Property` | Typed request field (Text/Identifier, Integer, Decimal/Double, Date, TrueFalse; local list as enum; max length) | Single Value, or one level of an embedded data-class Page property (flattened, e.g. `.Vendor.Name` to `vendorName`) | Lists, groups, deeper pages, declare expressions, other types |
| `Rule-HTML-Harness`, `Rule-HTML-Section` | Request form layout (`inputUi` order, sections, columns, widgets, required, labels) | Simple cells with standard controls matching the field type; currency shown in USD with whole cents (review) | Visibility conditions, other controls, any other property |
| `Rule-Obj-ServiceLevel` | Approval due time (`dueAfterHours`/`dueAfterMinutes`) or stage `sla` from the deadline; a goal interval is reported for review | Elapsed time explicitly (`pyUseBusinessDays` false), no escalation | Business days, escalation activities, no deadline |
| `Rule-Obj-Model` `pyDefault` | Reported for review; literal defaults are shown as field descriptions, because request forms have no default values | Literal Set steps only | Functions, property references, other actions |
| `Rule-Declare-Expressions/Trigger/OnChange/Constraints/Index` on the case class chain | Nothing | Never | Always: they run for every case, so leaving them out would silently change behavior |
| Activities, connectors, other rule types | Nothing | Never | When referenced. Unreferenced effective rules are reported as not used and need acknowledgement |
| Data instances (`Data-*`) | Nothing | Never | Reported as excluded (operators, access groups, work queues are not migrated) |

Every entry gets one finding: converted, converted for review, blocked (with the source path of the exact property), shadowed (with the outcome), not used, or used for resolution (application and class rules, manifest). Converter findings on the generated BPMN keep the source path of the shape or connector they came from. Per-rule results carry the entry's SHA-256 and the SHA-256 of the mapping-independent target fragment (DMN bytes, field schema, layout section, due time, stages or generated flow fragment).

## Fixtures

`examples/migration/pega-native/` (synthetic, labelled in every page and the archive comment):

- `purchase-request.zip`: application Purchasing with stack PurchasingOrg:01-02-01, Purchasing:01-01-10; a case type with Review (3-day stage limit) and Fulfilment stages. Resolution cases: a newer property version wins; a decision table's newer version is not available so the older one is used; a withdrawn section version hides the older version in the request class so the work-class version is used; a when rule in the request class wins over the work class; a flow in a sandbox ruleset outside the stack is shadowed. Conversion covers a decision table, a fork with a two-term AND when rule over a flattened data-page property, three assignments with due times of 24 hours and 150 minutes, rejected and continuing ends, a two-section harness and a literal `pyDefault`. An unreferenced activity, an unreferenced property and a data instance are reported, not converted.
- `refusals.zip`: a starting flow with a Utility (activity), an Integrator (connector), a business-day service level, a decision table whose higher-ruleset version is Blocked, a circumstanced flow action, an unresolvable flow action, a declare expression on the class and a binary entry.

## Verification - 29 October 2026

| Check | Result |
|---|---|
| Python oracle and fixtures (`python -m unittest discover -s tools/migration-equivalence -p "test_*.py"`) | 38 pass (13 new: grammar and identity refusals, archive refusals, every resolution rule, reproducible fixtures, CLI; 25 existing) |
| Fixture reproducibility (`python examples/migration/pega-native/generate.py --check`) | 4 files match |
| `PegaNativeTest` (resolution equals the oracle on both fixtures; case type converts and compiles with stages, due times, layout, flattened fields and decision boundaries 4,999.99/5,000/5,000.01; every refusal fixture item blocks with a source path; 11 grammar mutations are refused; unresolvable archives and XXE) | 5/5 pass |
| `PegaNativeApiTest` on PostgreSQL/Flowable (upload, inventory rows, view; refusals for missing acknowledgement, missing routes, non-empty `inputSchema`, unknown starting point, requester; conversion, replay, conflict, recorded per-rule hashes, separate publication; runs: 4,999.99 manager then procurement to Completed, 5,000 finance then procurement, 1,000 with a non-preferred vendor routed by the when rule to finance; the refusals archive blocks with source paths and creates no workflow; requester upload denied; outsider and other formats get no view) | 2/2 pass (206 s) |
| Existing migration regressions (`PegaXpdlApiTest`, `MigrationApiTest`, `MigrationSafetyTest`, `PegaBlueprintApiTest`) | 29/29 pass (3 + 14 + 6 + 6) |
| Frontend (`PegaNativeReview.test.tsx` plus the existing migration tests) | 3/3 new pass; migration folder and mock migration tests 69 of 70 pass. The one failure, `MigrationPage.test.tsx` "analyzes an uploaded file, maps a source group and opens the created draft", also fails with every frontend file of this change reverted, so it predates S112 (follow-up) |
| Typecheck, lint on changed files, `api:check`, contract checks | Typecheck, ESLint on the 6 changed frontend files, `api:check` and 88/88 contract checks pass |

These are synthetic target scenarios. No Pega system was contacted, no genuine export was decoded, and no source-runtime behavior was observed. No Docker image rebuild or real-browser run is claimed for this change.

## Not covered

- **Grammar fidelity against real exports (AC01/EXT01).** Real RAP/JAR exports may serialize rules differently (binary instances, other property names, embedded pages, flow shape encodings). Each difference will be refused, not guessed, until the grammar is extended against representative exports.
- Circumstances, date ranges, rule delegation, access rules, built-on applications other than PegaRULES and their rules.
- Activities, Java, connectors, declarative rules, validation rules, data pages, subflows, split-joins, spin-offs, alternate stages, optional processes, case-level service levels, business calendars and escalations: blocked when referenced.
- Flow action screens (the standard approval form is used), currencies other than USD, default values (shown as descriptions only).
- Multi-application archives, live cases, history, operators, access groups, work queues, attachments and credentials.
