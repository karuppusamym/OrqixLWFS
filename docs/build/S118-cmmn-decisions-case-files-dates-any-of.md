# S118 - Decision tasks, case file items, timer dates and "any of" start rules (WM04)

29 October 2026. Branch `claude/big-open-features-f6ffdd`, after [S113](S113-cmmn-nested-cases-timers-conditions.md). Migration **V182**. Built in parallel with UI02 round 3 (S116) and MIG11 round 3 (S117).

## What was delivered

### Decision tasks on shared decision tables

A standard `decisionTask` whose `decision` names a shared decision table (S61) as a QName in `urn:orqix:decision` (`xmlns:orqixdec="urn:orqix:decision"`, `externalRef="orqixdec:<decision-key>"`).

- **Pinned at publication** (`decisionTasks` in `CreateCaseDefinition`): the latest version of the shared table (archived tables are refused), with explicit mappings: every field the table reads (`/input/...` field references) from a case data field, and case data fields from table outputs with compatible types (`case_definition_decision`). The definition detail returns the pins.
- **Runtime**: when the task becomes active, Orqix evaluates the pinned version at once with the same evaluator the Studio uses, writes the mapped outputs into case data (validated against the pinned fields, with before/after history), completes the task (*Decision evaluated*, outcome *Row n* or *Default result*) and lets the result drive later rules (data conditions and case file events). A missing input or an output that does not fit the case data leaves the task active with the reason (*Decision could not be evaluated*); correct the data, then **Retry**. Dispatch repeats, bounded, while decisions release further work.

### Case file items (CMMN 5.2)

A `caseFileModel` whose `caseFileItem`s are named after declared case data fields (one value: multiplicity `ExactlyOne` or `ZeroOrOne`; a `caseFileItemDefinition` may describe them). Sentries may wait for `caseFileItemOnPart` events **create** (the field gets its first value) and **update** (its value changes), alone, with plan item events or with an if-part. The engine has no case file, so each such on-part becomes a hidden event in the engine model; Orqix completes it when a data change, a start value, a workflow, child case or decision result matches. Hidden events never show in the plan. An item named after an undeclared field is refused at publication.

### Timer dates

A timer may fire at a date and time with an offset (`2026-12-01T09:00:00Z`), a minute to five years ahead, besides the durations of S113. Cycles (`R3/PT1H`) stay refused.

### "Any of" start rules

An item may start after **any one** of several events (one entry criterion per event, each with the item's data condition) instead of all of them: a checkbox in the designer, shown as "or" on the shapes and in words; import keeps several entry criteria as "any of" when each waits for one event with the same condition.

### Designer, canvas and import

New kind **Decision task** (table icon; shared decision picker with the table's fields and outputs), **Start when case data changes** events on start rules, timer **Fire at a date**, and **any of**. Import reads decision tasks, case files, file events and "any of" criteria; anything else is still reported by path.

## Conformance matrix changes

The S113 matrix now reads:

| Clause | Construct | Status | Evidence |
|---|---|---|---|
| 5.2 | CaseFile, CaseFileItem (one value, named after a case data field), CaseFileItemDefinition | Executable | `CaseDecisionFileApiTest` |
| 5.4.4 | CaseFileItemOnPart create and update | Executable; other case file transitions (delete, replace, add/remove child or reference) refused | `CaseDecisionFileApiTest` |
| 5.4.5 | Several entry criteria ("any of") | Executable; authorable in the designer | `caseModelDecisions.test.ts` |
| 5.4.6 | TimerEventListener | Durations and dates executable; cycles and start triggers refused | `CaseDecisionFileApiTest`, `CaseNestingTriggersApiTest` |
| 5.4.11 | DecisionTask on a shared decision table | Executable; DMN files referenced directly and parameter mappings refused | `CaseDecisionFileApiTest` |

## Verification

| Check | Result |
|---|---|
| Backend case suite on real PostgreSQL (all `Case*Test`, `Cmmn*Test`, `ArchitectureTest`) | 68/69 on the first run; the one failure was the test acting as a manager without an active task, whom the server correctly refused; the corrected test is rerun with the integrated suite |
| New `CaseDecisionFileApiTest` | pinned decision evaluated at start, routed work, retry after a missing input, mapping refusals, case file create event (by a data change and at start), grammar, timer dates and refusals |
| Frontend case tests | **51/51 pass**; new `caseModelDecisions.test.ts` |
| TypeScript, ESLint, API types | pass |
| Live stack | the WM04 browser journeys ran against `orqix-integ-1029` (S110/S113 scope); two spec defects found there are fixed (planning history label, cancelling the right plan item) |

## Not covered

- Case file items holding lists, nested items, references and their transitions; case file items that are not case data fields.
- DMN files referenced directly by decision tasks; timer cycles; native suspension; repetition on termination (pinned engine).
