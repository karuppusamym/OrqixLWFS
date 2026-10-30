# S121 - DMN parity: lists and ranges, ANY/PRIORITY/COLLECT, standard DMN download and upload

29 October 2026. Branch `claude/dmn-support-11f6cd`. Extends [S28](S28-rules-authoring.md), [S40](S40-decision-hit-policies.md), [S41](S41-separation-and-dated-rules.md), [S61](S61-shared-assets.md) and [S89](S89-decision-csv-io.md). No database migration. Existing tables compile to byte-identical engine DMN (the golden files are unchanged), and existing shared-decision hashes are unchanged.

BPMN had a download (S97) and CMMN an XML export (S81); decision tables could only be imported through migration (S20, S45), and a table held a narrow subset of DMN. This slice gives DMN the same standard file in/out and the hit policies and conditions real DMN files use most.

## What was delivered

### Lists and ranges in decision rows

Two decision-only comparisons, usable alone, in an All (AND) row and in an Any (OR) row:

| Comparison | Stored form | FEEL in the download | Fits |
|---|---|---|---|
| **is one of** | `{"operator":"in","value":["EU","UK"]}` (1-20 distinct values, all texts or all numbers) | `"EU","UK"` | text and number fields; each value must be a choice of the field |
| **is between** | `{"operator":"between","value":{"from":1000,"to":5000,"includeTo":false}}` (`from < to`; bounds included unless `includeFrom`/`includeTo` is false) | `[1000..5000)` | number fields |

Route, form and section conditions keep the six comparisons: the workflow schema allows `in`/`between` only under `decisions[].rows[].when`, and the reader refuses them anywhere else. The overlap proof of UNIQUE (and ANY) understands both: `[1000..5000)` and `>= 5000` are exclusive, `[1000..5000]` and `>= 5000` are not. The simulator says "Region is one of EU, UK" and "Amount is between USD 1,000 and USD 5,000 (excluding USD 5,000)".

### Hit policies ANY, PRIORITY and COLLECT

| Policy | Meaning | Rules | Deployed DMN |
|---|---|---|---|
| UNIQUE, FIRST | unchanged (S40) | | FIRST + catch-all default rule |
| **ANY** | rows may overlap when they give the same results | overlapping rows with different results: `WF_DECISION_OVERLAP` | FIRST + catch-all (equivalent, proven) |
| **PRIORITY** | of the matching rows, the one whose priority result ranks highest wins; equal ranks keep table order | `priority: {output, order}`; every value a row or the default gives must be in the order (`WF_DECISION_PRIORITY_ORDER`) | FIRST + catch-all, rows sorted by rank (stable) |
| **COLLECT** | every matching row counts; its one number result is combined by `aggregation` SUM, MIN, MAX or COUNT (number of matching rows); no match gives the default | exactly one number result (`WF_DECISION_COLLECT_OUTPUT`); aggregation required | COLLECT + aggregation, no catch-all; an Any row is one rule so it counts once |

`aggregation` outside COLLECT, `priority` outside PRIORITY, or either missing where required is `WF_DECISION_POLICY_OPTION`. Tier-1 workflows still require UNIQUE.

One evaluator serves Test this rule, the simulator, CMMN decision tasks and the runtime check. The runtime executes the pinned DMN and confirms it against the canonical evaluation: the selected rows must agree (fails loudly otherwise). Flowable combines COLLECT results in `double`; the runtime checks the engine's number equals the exact decimal within double precision and stores the exact decimal (0.1 + 0.2 is stored as 0.3). Run evidence names every selected row under COLLECT (`matched: "r1,r3"`) and records `hitPolicy` and `aggregation`. Test this rule returns `matchedRows`; a CMMN decision task records "Rows 1, 3" for a COLLECT table.

### Standard DMN download

`DecisionTables.exportDmn` (compiler) writes a portable DMN 1.3 file, separate from the engine file:

- the authored hit policy, `aggregation` for COLLECT and `outputValues` on the priority result for PRIORITY;
- FEEL unary tests (`>= 5000`, `"EU","UK"`, `[1000..5000)`, `not("x")`), column types from the compared literals, input expressions named after the field (`amount`, `steps.check.score`);
- the default as each output's `defaultOutputEntry`;
- effective windows as a `submissionDate` date column (`[date("2026-01-01")..date("2026-12-31")]`).

Two shapes are rewritten without changing the meaning: an Any row over one field is one rule with a list of tests (`< 10, > 900000`); an Any row over several fields is one rule per field, which can overlap, so a UNIQUE table with such a row is written as ANY (with a description saying so), and under COLLECT the rules are made exclusive with `not(...)` of the earlier alternatives.

| Endpoint | Permission | Audit |
|---|---|---|
| `GET /workflows/{id}/decisions/{key}/dmn?revision=` (`exportDecisionDmn`) | `workflow.read` | `workflow.exported` `{format: dmn, revision}` |
| `GET /workflows/{id}/releases/{releaseId}/decisions/{key}/dmn` (`exportReleaseDecisionDmn`) | `workflow.export` (like the release BPMN) | `workflow.exported` `{format: dmn, releaseNumber}` |
| `GET /shared-assets/decision/{key}/versions/{version}/dmn` (`exportSharedDecisionDmn`) | `workflow.read` | none (like reading the version) |

All answer `application/xml`, `Content-Disposition: attachment`, `Cache-Control: no-store`.

### Standard DMN upload

`DecisionDmn` reads one decision table of a DMN 1.1-1.5 file through a hardened parser of its own (`DmnXml`, the same protections as the migration importer: no DTD or entities, 512 KiB, 10,000 elements, depth 32). It is strict: what it cannot represent exactly is a problem naming the decision, rule, input or output, never approximated. It takes UNIQUE, FIRST, ANY, PRIORITY (one output with `outputValues`) and COLLECT with an aggregation; literals, comparisons with a literal, ranges, lists and `not(...)` of one literal or comparison (inverted); several equalities in one entry become a list; a list of other comparisons in one entry becomes an Any row when it is the rule's only entry. The default is the outputs' `defaultOutputEntry` or, for FIRST, a last rule that tests nothing. Annotations, descriptions, labels, allowed input values and layout are listed as notes. Refused with a reason: RULE ORDER, OUTPUT ORDER, COLLECT without aggregation, decision requirements (chained decisions), business knowledge models, literal-expression and other non-table decisions, FEEL functions, arithmetic or other variables, `not(...)` of a list or range, an OR inside an AND, input expressions that are not field names, other types than string/number/boolean.

| Endpoint | Permission | Effect |
|---|---|---|
| `PUT /workflows/{id}/decisions/{key}/dmn?decisionId=` (`importDecisionDmn`), body `application/xml`, If-Match | `workflow.edit` | replaces hit policy, options, rows and default of the table (key and version stay) and saves a draft revision (audit `workflow.draft.saved`, source `decisionDmn`) only when the compiler finds no blocker in the table, as for CSV import. Never publishes. |
| `POST /shared-assets/decision/dmn?decisionId=` (`convertDecisionDmn`), body `application/xml` | `workflow.edit` | returns the decisions of the file, the converted shared-decision content and notes; nothing is stored. The client creates the shared decision or its next version with the existing endpoints. |

A file with several decisions and no `decisionId` answers 422 with a `decisions` list (import) or 200 with `decisionId: null` (convert). Orqix's own download reads back to the same table (unit-tested for UNIQUE with lists, ranges, a dated row and not-equal; PRIORITY with a step result and booleans; COLLECT with an Any row).

### CSV

CSV accepts `hitPolicy:ANY`. Tables with lists, ranges, PRIORITY or COLLECT cannot be written as CSV; the refusal says to download the table as DMN instead.

### Shared decisions

Shared content may carry `aggregation` and `priority`, and is checked with the compiler's reader (operators, lists, ranges, hit policy options) when stored. The content hash includes the two only when present, so every existing pin keeps its hash.

## Not covered (deliberate)

- RULE ORDER, OUTPUT ORDER and COLLECT without aggregation (list results), multi-output aggregation.
- Decision requirements graphs, decision services, business knowledge models, boxed and literal expressions.
- FEEL beyond literals, comparisons, ranges, lists and single negation: functions, arithmetic, dates in conditions (other than the effective window), strings functions, comparing two fields, null tests.
- Nested groups (an OR of ANDs); `not in`.
- Lists and ranges in route, form and section conditions.
- CSV for lists, ranges, PRIORITY and COLLECT.
- A written DMN conformance level or the DMN TCK.
- The Camunda migration importer (`dmn-0.1`, S20/S45) is unchanged: it still converts UNIQUE/FIRST tables with one tested input, with its report and generated boundary tests. Broader tables can be brought in with the DMN upload above.

## Verification - 30 October 2026

Backend with JDK 21 on PostgreSQL (Testcontainers) and Flowable 8:

- Compiler: all 657 cases pass, including the unchanged golden DMN files. New `DmnParityDecisionTest` (7): lists and ranges in the evaluator, the overlap proof, the engine DMN and the simulator; sets refused in route conditions; ANY overlaps; PRIORITY ranking, row order and options; COLLECT exact sums, counts and maxima with one rule per Any row; the standard export; malformed sets.
- New `DecisionDmnTest` (5, pure): Orqix's export reads back to the same content for UNIQUE/PRIORITY/COLLECT tables; Any rows over several fields keep their meaning; the FIRST catch-all default; named refusals; multi-decision choice.
- New `DmnParityRuntimeTest` (2, engine): PRIORITY with a list and a range picks the higher-ranked second row in the pinned DMN; COLLECT stores 0.1 + 0.2 as exactly 0.3, counts an Any row once and applies the default when nothing matches.
- New `DecisionDmnApiTest` (4): Camunda-style PRIORITY file into a draft, evaluator, download, re-upload to the identical table, audit; multi-decision choice; FEEL refusals and compiler blockers with no draft change; permissions, If-Match, content type and DOCTYPE refusal; release download (`workflow.export`), shared conversion, creation and version download.
- Full backend run: 1102 cases. After two fixes found by that run (a module cycle from reusing the migration XML parser, now `DmnXml` in the catalog; shared content now refuses COLLECT without aggregation and PRIORITY without an order), `ArchitectureTest`, `SharedAssetsApiTest`, `SecurityMatrixTest` (with `convertDecisionDmn` listed as a read-only POST like `checkDraftBpmn`) and the decision suites pass. `BankingWorkflowsTest` failed twice under full-suite load with a 409 on cancel and passes 42/42 alone. `RunRepairWaitsTest.lostMessageWaitProjectionIsRebuiltAndTheMessageStillArrives` fails by exactly one hour across the 1 November daylight-saving change; it fails identically on the base commit `348252d`, so it is not caused by this slice.

Frontend: `api:generate` and `api:check` pass; `tsc -b --noEmit` is clean; eslint is clean on the changed files; all 179 files / 1282 tests pass. Browser check in mock mode (Edge pane, 1440x900): the policy list offers all five policies, PRIORITY shows the deciding result and its order, decision rows offer "is one of" and "between" (route conditions do not), the range editor fits the panel after a layout fix, and the Standard file (DMN) section shows Download and Import; no console errors.

Not run: the real Docker stack, the mock-browser E2E suite, an image refresh, and DMN files from customer models.
