# S115 - Native Pega conversion: remaining MIG11 items (29 October 2026)

Second MIG11 round after [S112](S112-pega-native-conversion.md). The user asked to convert what has a sound target among the items S112 left "blocked when referenced", keep refusals with reasons for the rest, and test fully. **AC01/EXT01 stay open**: the grammar is still synthetic and no genuine export or Pega system was available.

Migration **V201**. No other number used.

## What now converts

| Source construct | Target | Conditions |
|---|---|---|
| **Property circumstance** on a decision table or tree (`pyCircumstanceProp`/`pyCircumstanceVal`) | One FIRST decision table: the variant's rows test the case field and the input together (`all`), then a row for the variant's otherwise, then the base rows; base otherwise as default. Pega picks the variant first and then evaluates it; this row order does the same | Variant and base test the same property; the circumstance property becomes a required request field; value fits its type; at most 50 rows in total |
| **Date range** (`pyStartDateTime`/`pyEndDateTime`) or **date circumstance** on `.pxCreateDateTime` on a decision table or tree | Rows with an effective window (S41), judged by the request's submission day | Bounds at midnight GMT; a range ending at midnight includes the day before. The variant's otherwise row has an always-true condition on the required input (`any` of equal / not equal) because the default cannot be dated |
| **Property circumstance** on a when rule used by a fork | A gateway on the circumstance field in front of the fork, and one copy of the fork per variant with the variant's condition | Date-qualified when rules stay refused: branch conditions read request fields, not the submission date |
| **Subflow** (`Subprocess` on the current case) | Child workflow step (`startWorkflow`), mapped explicitly through `childWorkflowMappings` (workflowRef, inputMapping, resultMapping) to a published workflow. The workbench lists every call to map | The child is converted and published separately (a starting flow of the archive can be converted on its own); reported for review because the child is a separate request with mapped data |
| **Split-join** with join All over 2-5 subflows | Parallel split, one child workflow step per subflow, parallel join (all). An exclusive merge is put in front when several routes reach it | Any and Some joins stay refused |
| **Business calendar** (`pyUseBusinessDays` true + `pyBusinessCalendar`) on an assignment service level | Approval `calendar` (zone, weekdays, working hours, holidays) from a decoded `Data-Admin-Calendar` instance; N business days become N workdays of working hours (`dueAfterHours` or `dueAfterMinutes` in working time) | Only for assignments; reported for review. No named calendar (the default comes from the operator's organization) or a missing one stays refused |
| **NotifyAssignee deadline escalation**, optional passed-deadline repeats | Approval `escalation`: overdue notices to the current eligible assignee, 1 + repeat count notices at the passed-deadline interval | Reported for review: Pega's correspondence is not reproduced. Other escalation activities, goal escalations and stage escalations stay refused |
| **Case-level service level** (`pyServiceLevelName` on the case type) | Time limit of the case type's only stage | Only with exactly one stage that has no limit of its own; reported for review. Otherwise refused: workflows only have per-stage limits |
| Explicit **USD** on a currency cell (`pyCurrency`) | Money widget without the "assumed USD" review | Other currencies refused (the form supports USD only) |
| **Rule delegation** records (`System-User-MyRules`) | Provenance only: the delegated rule's result lists the access groups; the record is reported for acknowledgement | Never converted |

Decision trees convert to DMN through the MIG14 table shape since S112.

**Compiler fix found by the live run.** Canonical workflows merge implicitly, so a parallel split reached by two routes (here the decision's Otherwise route and the when-rule route both lead to the split-join) is valid. The BPMN generator emitted it as one parallel gateway with two incoming flows, which the engine treats as a join too: the request waited forever at the split. A parallel or inclusive split reached by more than one route now gets an exclusive merge in front (`<id>__merge`, with a diagram shape when the workflow has a layout). Splits with one incoming route generate exactly the same BPMN as before. Regression test: `GeneratedXmlTest.parallelSplitReachedByTwoRoutesGetsAMergeInFront`.

## Resolution changes

Rules may carry a qualifier: a property circumstance, a date circumstance (`pyCircumstanceDateProp`, `pyCircumstanceDate` YYYYMMDD) or a date range (`pyStartDateTime`, `pyEndDateTime`, at least one, in order). Several on one rule are multivariate. The base rules of a key are resolved as in S112. Qualified candidates in the **same class** as the chosen base, grouped by identical qualifier, become **variants** (the best of each group by ruleset and version; the rest lower ranked). Qualified candidates in a farther class are lower ranked. The key stays `circumstanceUnresolved` (and blocks when referenced) when there is no base rule, a qualified rule is in a closer class than the base, a rule is multivariate, a variant group is blocked or tied, or the variants use more than one dimension (for example one on `.Category` and one on `.Region`, or a property and a date). Withdrawn rules mask only lower versions with the same qualifier. The Python oracle (`pega_native.py`) implements the same rules and `PegaNativeTest` still requires identical results on all three fixtures.

Variants of other rule types (flows, flow actions, sections, harnesses, properties, service levels, case types) are refused with `PEGA_NATIVE_CIRCUMSTANCE_UNSUPPORTED`: the target has no run-time selection between them.

## Still refused, with reasons

| Construct | Why |
|---|---|
| Spin-offs (`pySpinOff`, `Spinoff` shape) | The target's child workflow step always waits for the child; there is no "start and continue" step |
| Split-join with Any/Some join, split-for-each | No join that cancels or ignores branches; page-list iteration has no converted field |
| **Alternate stages** | Entered by Change Stage outside the primary path; a workflow has no out-of-sequence stage |
| **Optional processes** (`pyOptional`) | Started on demand by a user; a workflow runs its steps in sequence |
| Stage service levels on business days, case-level service levels on several stages, goal and non-NotifyAssignee escalations | Stage limits are elapsed time; workflows have no case-wide deadline; the target only reacts when a step is overdue, by notices |
| Currencies other than USD | The request form shows money in USD only |
| Default values | The request form has no default values; literal `pyDefault` values stay field descriptions for review (unchanged from S112) |
| Date circumstances on other properties, date-qualified when rules, ranges not at midnight | The target dates decision rows by submission day only |
| Multivariate circumstances, variants in closer classes, circumstanced rules of other types | Not selectable statically, or no run-time selection in the target |

**Alternate stages and optional processes as CMMN cases.** (Done in [S117](S117-pega-native-round-3.md) after the S110 branch was merged.) WM04's branch (`claude/big-open-features-f6ffdd`, S110) adds discretionary items and workflow tasks to case definitions. Using them would mean converting a case type into a case definition rather than a workflow: every process converted and published as its own workflow first, then a case definition pinning those releases with discretionary items for optional processes. That is a multi-artifact migration project the single-draft pipeline does not do, and S110 has no discretionary stages for alternate stages. This round therefore keeps both refused with explicit reasons and does not merge the S110 branch; it is the natural next step once the three branches are merged.

## Fixtures

`examples/migration/pega-native/purchase-advanced.zip` (synthetic, regenerated by `generate.py`): case types PurchaseOrder (two stages) and QuickOrder (one stage with a case-level service level); a decision table with a Services circumstance variant; a decision tree with a 2020 date-range variant (expired today); a when rule with a Software variant; a split-join over two subflows; a subflow call; three starting child flows; a business calendar (New York, Monday to Friday 09:00-17:00, two holidays) with a NotifyAssignee escalation and three passed-deadline repeats; an explicit USD cell; a delegation record. The S112 fixtures are unchanged; their expected resolutions now include the variant fields.

## Verification - 29 October 2026

| Check | Result |
|---|---|
| Python tool tests (`python -m unittest discover -s tools/migration-equivalence -p "test_*.py"`) | 42 pass (17 native: 4 new for variants, date qualifiers and delegation; 25 other tools) |
| `PegaNativeTest` (resolution equal to the oracle on three fixtures; the advanced case type converts and compiles with split/join, child steps, variant gateways, calendar and escalation; decision boundaries 1,999.99/2,000 for Services and 4,999.99/5,000 otherwise; the dated variant on 2020-06-01, 2020-12-30 and 2020-12-31 via `$asOf`; QuickOrder stage limit P5D; 16 refusal mutations) | 7/7 pass |
| `PegaNativeApiTest` on PostgreSQL/Flowable: child flow converted and published; case converted with child mappings (refused without them), published, and run: Services 3,000 through the parallel child checks to finance and order confirmation to completed; Hardware 3,000 to manager review; Software 600 through the when-rule variant to finance; Hardware 50 Express to completed | 3/3 pass on PostgreSQL/Flowable (S112's two scenarios plus this one) |
| Full backend suite (compiler and application modules) | `mvnw clean verify`: compiler 641/641, application 1,023 run, 0 failures, 4 intentional skips; coverage gate passes. Includes the new `GeneratedXmlTest` merge case |
| Full frontend suite, typecheck, ESLint, `api:check`, contract checks | 162 files, 1,191/1,191 tests (including 3 native workbench tests with variants, delegation and the child-workflow guide); typecheck, full ESLint, `api:check` and 88/88 contract checks pass |

`MigrationPage.test.tsx` (the failure noted in S112) passes when the machine is idle and failed only while Maven ran in parallel: its wait for navigation after conversion used the 1 s default. The wait now allows 8 s; no product code changed.

No Docker stack was started, stopped or rebuilt, and no real browser was used. Live-stack journeys are left to the session that merges the three branches.

## Real-stack E2E specs (round 3)

`tests/e2e/specs/t-mig-05-pega-native.spec.ts` (purchase-request archive: inventory, effective rules and provenance, review and conversion, separate publication on the publish page, runs at 4,999.99 through manager review and order confirmation to Completed and at 5,000 to a finance rejection; the refusals archive shows its blockers with source paths and creates no workflow; requester and outsider access) and `t-mig-06-pega-native-advanced.spec.ts` (purchase-advanced archive: a starting flow converted and published as the child, then the case type with the Services variant, the split-join and the subflow mapped to it; Services 3,000 runs through the parallel child checks, finance and order confirmation; Hardware 50 takes the Express route). Shared helpers are in `tests/e2e/support/pega-native.ts`. They type-check (`npx tsc --noEmit`) and are skipped in mock mode; they have not been run here, because the real stack is run by the session that merges the branches.

## Not covered

- AC01/EXT01: real exports, source-runtime equivalence, source-owner sign-off.
- Alternate stages, optional processes, spin-offs, Any/Some joins and the other refusals above.
- Circumstance variants of non-decision rules, multivariate circumstances, date circumstances on fields other than the creation date.
- Escalation correspondence, transfers and flow advancement; goal-time behavior; business-day stage limits.
