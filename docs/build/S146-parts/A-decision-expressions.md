# S146 part A: Orqix expressions in decision tables (doc 29 CG-D04, via CG-X01)

Branch `claude/s146-decision-expressions` (base 245d172c). Closes the S137 follow-up "expression terms in decision-table rows and date comparisons in decision tables". No compiler version bump, no Flyway migration (V397-V398 unused); every table without formulas compiles to the same DMN and BPMN bytes (golden files unchanged).

## What is delivered

### Model (`contracts/workflow-v0.schema.json`)

| Entry | Stored form | Meaning |
|---|---|---|
| Input entry (a row test) | `{"fieldRef": "/input/amount", "expression": "input.amount > input.limit"}`, as the row's `when` or as an item of `all`/`any` | The S137 language; must give true/false; true matches, false or no value (null) does not. `fieldRef` is the column it belongs to. |
| Output entry (a row result) | `"then": {"fee": {"expression": "round(input.amount * 0.025, 2)"}}` | Must give a value of the type of the default's literal for that result (text, number, true/false). |

New defs `decisionExpressionTerm` (added to `decisionTerm.oneOf`) and `decisionRowOutput` (rows' `then`; the default keeps `decisionOutput`, literals only). Route, form and poll conditions are unchanged: `{fieldRef, expression}` is accepted only in decision rows (`ModelReader.readRowCondition`).

### Compiler

- `ExpressionRules.decision`: every entry is parsed and type checked (`WF_EXPRESSION_INVALID` with "(at character N)", `WF_EXPRESSION_TYPE`), at `/decisions/<i>/rows/<r>/when[/all|any/<k>]/expression` and `/decisions/<i>/rows/<r>/then/<key>/expression`. `${`/`#{` refused as for every expression.
- New codes: `WF_DECISION_EXPRESSION_COLUMN` (a test must use its column's field), `WF_DECISION_EXPRESSION_FIELD` (single values only: number, text, true/false, date; no lists, rows or unknown shapes), `WF_DECISION_EXPRESSION_UNSUPPORTED` (the PRIORITY table's priority result must stay a fixed value), warning `WF_DECISION_EXPRESSION_OVERLAP`, Tier-1 `WF_TIER1_RULE_EXPRESSION`. Step results used must always come before every decision step using the table (`WF_FIELDREF_NOT_GUARANTEED`, own result refused). Time and date-time fields are not in scope (S137 `ExpressionRules.ofField`), so a formula naming one is `WF_EXPRESSION_INVALID`.
- Results cannot use other results (decision formulas have no locals), so there is no cycle to detect.
- Overlap (UNIQUE/ANY): a formula test may hold for any value, so only the comparisons can prove two rows exclusive. Rows proven exclusive by their comparisons pass silently; otherwise the compiler warns (`WF_DECISION_EXPRESSION_OVERLAP`, one warning per table naming up to 5 pairs) instead of claiming an overlap or exclusiveness. Rows whose tests are all comparisons keep the S40/S121 proof and blocker, even with formula results. Under ANY, identical formula text counts as the same result. The "default never used" coverage warning is skipped for tables with formula tests.
- A result computed by a formula has the default's type but no known value set (and numbers may have decimals), so later conditions on it are no longer checked against a domain (`FieldTypes.outputType`).

### Evaluation (identical in Java and TypeScript)

`com.orqix.compiler.internal.eval.ExpressionTable` (Java) and `frontend/src/lib/expr/decision.ts`; shared cases `examples/fixtures/decision-expression-cases.json` (28 cases), read by `DecisionExpressionCasesTest` and `decision.test.ts`.

- Every input entry of every row is evaluated (so the evidence lists all of them); literal comparisons keep their rule (the field needs a value).
- Output formulas are evaluated for every matching row; a selected row whose result has no value stops the evaluation.
- UNIQUE: two applying rows stop the evaluation (the proof is impossible at publish time, so it is checked per request). ANY: applying rows with different results stop it. FIRST, PRIORITY, COLLECT as before; COLLECT combines literal and computed numbers exactly.
- Values by fieldRef (dates as YYYY-MM-DD text or LocalDate); reserved `$now` (instant for `today()`) and `$asOf`. Types: the compiler's pinned types, or inferred from the values (number, text, true/false, date as LocalDate; a missing value may be anything) where no workflow context exists (shared decisions in cases and agent tools).
- `DecisionEvaluator.Result` gains `expressions` (`Entry(row, term, kind, target, expression, value)`, canonical order: by row, tests by term, then results by name); kept constructors.
- Tables without formulas take the unchanged S40/S121 evaluator.

### Engine and runtime

- Engine DMN (`ExpressionDmn`, only for tables with formulas): the compared fields keep their `in<n>` columns; every formula test is a boolean column `x<k>` tested `== true`; every formula result is the output entry `#{y<k>}`. The app runs the canonical evaluation first (types pinned in the BPMN, engine clock for `today()`, submission day for effective windows), passes the results as variables (`DecisionTables.engineVariables`) and requires the pinned DMN to select the same rows; it stores the canonical exact values after checking the engine returned the same ones.
- BPMN, only for decision steps whose table uses formulas: `orqix:decision typesBase64` (pinned types, S137 notation) and `flowable:async="true" exclusive="true"` with the workflow's retry cycle, so a data-dependent failure (two rows under UNIQUE, a result without a value, an exceeded bound) makes the request need attention instead of undoing the approval before it.
- Evidence (S21, `DECISION_EVALUATED`): each candidate row may carry `expressions: [{term, fieldRef, expression, result}]` (true/false/null) and `outputExpressions: [{output, expression, value}]` for matching rows; `inputs` also lists the fields named only inside formulas (fields without a value are left out). Redaction is unchanged: metadata-only readers get the key, version and `evidenceRedacted` only.
- `evaluateDecision` (Test this rule): uses the compiler's types for the draft (`WorkflowCompiler.decisionTypes`, new default method), `$now` = now, returns `expressions`; a stopped evaluation answers 422 with the reason in the field error. OpenAPI: optional `DecisionEvaluateResult.expressions`, schema `DecisionExpressionEntry`.
- Shared decisions: `DecisionTables.check` also parses the formulas; case decision tasks must map the fields the formulas name too (`DecisionTables.fields`); agent decision tools list them as string/number/boolean arguments.

### DMN download and upload (S121)

- Download: a formula entry is written with the standard DMN attribute `expressionLanguage="https://orqix.example/expression"` on its `inputEntry`/`outputEntry`, the Orqix text verbatim in `<text>`. It is not translated to FEEL: a lossy FEEL text could mean something else in another tool, while an unknown expression language is refused or flagged there. A column tested only by formulas has no `typeRef`. An Any row with a formula is written as one rule per alternative (UNIQUE exported as ANY, as for S121's multi-field Any rows); under COLLECT such a row is refused with a reason.
- Upload: entries with that language become formula entries again (text taken verbatim, edge spaces included); the compiler then checks them as for CSV. Another expression language is refused with a named problem; FEEL outside the S121 subset stays refused. Orqix's own download reads back to the identical table (except Any rows with formulas, which read back as one row per alternative with the same meaning).

### CSV (S89)

- A formula cell is `=` followed by the expression verbatim, in an input cell or a result cell. Export applies the existing formula-injection neutralisation, so the file holds `'=input.amount > 5`; import strips the apostrophe; a hand-typed `=...` reads the same.
- A column of formulas only has the new type `expression` (`in:expression:/input/due`); a comparison in such a column is refused (`formula`). Formula cells may be up to 2002 characters (the 2000-character expression, `=` and the apostrophe); other cells keep 120.
- Refused, never changed: a formula with a line break, tab or other control character, or an edge space (export), and, because a cell starting with `=` is now a formula, a text result starting with `=` (export; the compiler never allowed such a text, `WF_LITERAL_UNSAFE`). The default line stays literal. Export still proves its own round trip.

### Simulator and Studio

- Compiler simulator: decisions with formulas use the canonical evaluation with the compiler's types and `$now` = the test day at 12:00 UTC; decision data rows gain `expressions`/`outputExpressions`; a stopped evaluation stalls with `DECISION_FAILED`.
- Studio (frontend): each row test and each result cell can be switched to "Formula" using the S137 `ExpressionEditor` with live type check (true/false for tests, the default's type for results), shown with a Formula badge; the PRIORITY table's priority result offers no formula; the editor warns when a test does not use its column. Test this rule asks for every field the table reads. Mock compiler, simulator, engine, evidence, CSV and DMN doubles follow the rules above.

## Decisions

- **No new syntax for the column's value.** A test names its column by its ordinary field name (`input.amount`) and must use it; FEEL's `?` was not added (a new token in both engines and every editor). The text reads the same in the table, the CSV and the DMN file.
- **Fixed results stay where the engine needs them**: the default and the PRIORITY ranking result are literals.
- **UNIQUE/ANY with formulas** are warned at publish time and enforced per request (stop with an error), never silently resolved by order.
- **`today()` in a decision** uses the engine clock at the decision (as S137 conditions do); effective windows keep the submission day.
- **Formulas read single values only**, so evidence stays scalar and Test this rule keeps one value per field.
- **DMN uses `expressionLanguage`** rather than a FEEL translation with an `orqix:` extension (see above).
- **Async only for formula tables**: existing decision steps keep their synchronous BPMN.

## Verification

- Compiler module `mvnw -o -pl compiler verify`: 1218 tests, 0 failures, coverage met, golden BPMN/DMN unchanged. This ran before the last change (async BPMN for formula decision steps); after it `DecisionExpression*`, `GoldenFilesTest` and `DmnParityDecisionTest` were re-run and pass.
- New compiler tests: `DecisionExpressionCasesTest` (28 shared cases), `DecisionExpressionsCompilerTest` (7: checks and paths, dates, overlap warning vs proof, PRIORITY, engine DMN/BPMN/async/variables, standard DMN export, simulator).
- App (Testcontainers PostgreSQL + Flowable), last run after all changes: `DecisionExpressionsRuntimeTest` 3/3 (pinned decision with formula tests and results incl. a date, evidence and redaction; UNIQUE double match needs attention; Test this rule, CSV and DMN round trips through the API, foreign expression language refused), `DecisionCsvTest` 13, `DecisionDmnTest` 6, `DecisionCsvApiTest` 4, `CatalogApiTest` 8, `SharedAssetsApiTest` 5, `CaseDecisionFileApiTest` 4, `EngineSpikeTest` 9, `SecurityMatrixTest` 8 — all pass. `ArchitectureTest` 5/6: `modulithStructureIsValid` fails on `runtime.internal.WaitReminders` using `compiler.internal.json.StrictJson`, a file this part does not touch (from commit ae0af1b5 on the base). Earlier run (before the async change): `DecisionDmnApiTest` 4, `DmnParityRuntimeTest` 2, `DecisionEvidenceTest` 3, `DecisionHitPolicyTest` 2, `ExpressionsRuntimeTest` 4 — all pass.
- Frontend: `npm run typecheck` clean; eslint clean on changed paths; targeted vitest (lib/expr, decision mocks, simulator, evidence, DecisionInspector, CSV section): 11 files, 322 tests passed. `api:generate`/`api:check` pass; `tools/contract-check/contract_check.py` 142/142 after fixing CRLF line endings.
- Redocly lint reports 2 errors at lines 12202 and 12317 of `contracts/openapi.yaml`, outside this part's hunks (unquoted descriptions with commas from another slice); not fixed here.

## Not covered

- FEEL text for formulas in the DMN download (other tools see an unknown expression language); importing foreign FEEL functions or arithmetic.
- Lists and rows in decision formulas; formula defaults; formulas for the priority result; date-typed results.
- Overlap/completeness proofs involving formulas (warned instead).
- Renaming a field does not rewrite formula text (as in S137).
- The mock engine marks a failed formula decision with reason `DECISION_FAILED` (like its existing `CALCULATION_FAILED`); the real app reports the failed job through its usual incident reasons.
- Not run: full backend `clean verify`, full frontend suite, browser check of the Studio editor in mock mode.

## Merge notes

- No Flyway migration; no shared CHECK constraint, permission, audit kind or `ProblemCode` changed.
- OpenAPI: `DecisionEvaluateResult` (existing schema, Workflows) gains optional `expressions`; new schema `DecisionExpressionEntry` in a block right after the `end of Expressions` schemas comment. No new operation (so no `Completion` tag line and no SecurityMatrix change from this part).
- `workflow-v0.schema.json`: `decisionTerm.oneOf` + `decisionExpressionTerm`; rows' `then` now `decisionRowOutput` (new def); default unchanged.
- Shared Java records/interfaces extended with kept constructors/defaults: `DecisionEvaluator.Result` (+`expressions`), new `DecisionEvaluator.Entry`, `DecisionEvaluator.evaluate(..., typesJson)` default, `WorkflowCompiler.decisionTypes` default, `WorkflowDefinition.DecisionTable.Row` (+`thenExpressions`, helpers `outputKeys`, `terms`, `usesExpressions`), `DecisionTable.usesExpressions`, `CatalogDtos.DecisionEvaluateResult` (+`expressions`). `Expression.references` (parse-only field references).
- Touched shared compiler files: ModelReader, WorkflowDefinition, TypeRules, LogicalConditions, ExpressionRules (decision scope, pinned types `node/<decisionId>`), FieldTypes, BindingRules, Tier1Rules, BpmnGenerator (decision task), DmnGenerator (delegates formula tables to the new `ExpressionDmn`), DmnExport, DefaultDecisionEvaluator, SimulationRun, DefaultWorkflowCompiler, DecisionTables.
- Touched app files: DecisionTaskDelegate, WorkflowService.evaluateDecision, DecisionCsv (+ `DecisionCsvApiTest` expectation: `=cmd()` is now `WF_EXPRESSION_INVALID`; unsafe-text case uses `a;b`), DecisionDmn, DmnXml (`rawText`), CaseService (decision field list), AgentDecisionTools, CatalogDtos.
- Frontend: regenerated `src/api/schema.d.ts`; `studioScope` (`features/studio/model/expressionScope.ts`) now leaves out time and date-time request fields (matches the backend rule; also affects branch and Calculate formulas in mock mode). `notation.test.ts` expects 21 step kinds while 22 exist since Wave C (`waitForFirst`): a pre-existing failure, not touched here.
