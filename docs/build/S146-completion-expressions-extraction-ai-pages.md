# S146 - Completion: expressions in decision tables, document extraction, operator explain, AI skills pages

Closes the open gaps left by S137 and S134 against [doc 29](../29-camunda-gap-implementation-plan.md): CG-D04 (Orqix expressions as decision-table rule tests and results), CG-A06 (document extraction, section 7.11), CG-A07 (operator "Explain this request", section 7.12) and the AI skills Studio pages over the S134 API. Built as four parts on separate branches from 245d172c and merged on `claude/completion-ai-expr-8783db`.

| Part | Area | Flyway | New operations (tag `Completion`) |
|---|---|---|---|
| A | Decision-table formulas (CG-D04) | none (V397-V398 unused) | none (`DecisionEvaluateResult.expressions` added) |
| B | Document extraction (CG-A06) | V390 | 7 (OCR settings, verification tasks) |
| C | Operator explain (CG-A07) | V394 | 1 (`explainRun`) |
| D | AI skills Studio pages | none (V396 unused) | none (frontend only) |

Compiler version not bumped; golden BPMN/DMN files unchanged (only tables that use formulas produce new DMN content).

## Merge notes (coordinator)

- **Shared CHECK `ai_generation_kind_check`** is rebuilt twice: V390 (current list + `aiExtract`, `aiOcr`) and V394 (current list + `aiExtract`, `aiOcr`, `aiExplain`). V394 is the superset and runs last. Anyone widening it later must start from the V394 list.
- **S52 table `attachment_ocr_result`**: `requested_by` nullable, new `source` and `layout` columns (V390). New tables `ai_ocr_setting`, `ai_document_verification` (in the security-matrix snapshot).
- **OpenAPI enums**: `CurrentStep.kind` and `PathCurrent.kind` gain `verify`. Tag `Completion` after `AiSkills`. Blocks: part A after the `end of Expressions` schemas, part B after `end AiSkills`, part C after `end of S141 TaskActions`.
- **Audit whitelist (`AuditWriter`)**: `ai.ocr-settings.updated`, `ai.verification.submitted`, `ai.run.explained`.
- **New integration ports**: `OperatorExplainPort`, `ConnectionStatus`; `AiTaskPort.Outcome` gains `verification` (old constructor kept) and `recordVerification`.
- **Shared test support**: `AiTaskStub` (run-facts and document answers; merge conflict resolved by keeping both branches), `AiTaskFixtures`, new `OcrStub`, `Stub.Mode.STATUS403`.
- **Frontend shared files**: `RunDetailPage.tsx` (explain panel and document verifications panel; import conflict resolved), `routes.tsx`, `Shell.tsx` (AI skills, AI review, AI policy nav), `mocks/state.ts` (Dana gets `ai.evaluate`, Paul `ai.approve-skill`), `mocks/aiSkills.ts` rewritten, `mocks/handlers.ts`.
- **Fixes to base problems found while merging**: two unquoted OpenAPI descriptions with commas (`WorkingWeek.zone`, `ScheduleRequest.input`) that Redocly rejected; `runtime.internal.WaitReminders` used `compiler.internal.json.StrictJson` (failing `ArchitectureTest.modulithStructureIsValid`), now parses adapter config with a plain Jackson mapper; `notation.test.ts` expected 21 step kinds, Wave C has 22.

## Verification (merged branch)

- `backend/mvnw -o clean verify` (JDK 21): BUILD SUCCESS. Compiler 1221 tests, coverage met; application 1369 tests (5 skipped), including `SecurityMatrixTest` and `ArchitectureTest`.
- Frontend: `api:generate` + `api:check` ok, `typecheck` clean, `eslint .` clean, Vitest 232 files, 1763 tests, all passing.
- `python tools/contract-check/contract_check.py`: PASS, 142 checks. `redocly lint` (2.60.0): 0 errors, 72 warnings (same warnings as before).
- Not run: browser checks against the full stack; live OCR or model accounts (WireMock and stub providers only).

## S146 part A: Orqix expressions in decision tables (doc 29 CG-D04, via CG-X01)

Branch `claude/s146-decision-expressions` (base 245d172c). Closes the S137 follow-up "expression terms in decision-table rows and date comparisons in decision tables". No compiler version bump, no Flyway migration (V397-V398 unused); every table without formulas compiles to the same DMN and BPMN bytes (golden files unchanged).

### What is delivered

#### Model (`contracts/workflow-v0.schema.json`)

| Entry | Stored form | Meaning |
|---|---|---|
| Input entry (a row test) | `{"fieldRef": "/input/amount", "expression": "input.amount > input.limit"}`, as the row's `when` or as an item of `all`/`any` | The S137 language; must give true/false; true matches, false or no value (null) does not. `fieldRef` is the column it belongs to. |
| Output entry (a row result) | `"then": {"fee": {"expression": "round(input.amount * 0.025, 2)"}}` | Must give a value of the type of the default's literal for that result (text, number, true/false). |

New defs `decisionExpressionTerm` (added to `decisionTerm.oneOf`) and `decisionRowOutput` (rows' `then`; the default keeps `decisionOutput`, literals only). Route, form and poll conditions are unchanged: `{fieldRef, expression}` is accepted only in decision rows (`ModelReader.readRowCondition`).

#### Compiler

- `ExpressionRules.decision`: every entry is parsed and type checked (`WF_EXPRESSION_INVALID` with "(at character N)", `WF_EXPRESSION_TYPE`), at `/decisions/<i>/rows/<r>/when[/all|any/<k>]/expression` and `/decisions/<i>/rows/<r>/then/<key>/expression`. `${`/`#{` refused as for every expression.
- New codes: `WF_DECISION_EXPRESSION_COLUMN` (a test must use its column's field), `WF_DECISION_EXPRESSION_FIELD` (single values only: number, text, true/false, date; no lists, rows or unknown shapes), `WF_DECISION_EXPRESSION_UNSUPPORTED` (the PRIORITY table's priority result must stay a fixed value), warning `WF_DECISION_EXPRESSION_OVERLAP`, Tier-1 `WF_TIER1_RULE_EXPRESSION`. Step results used must always come before every decision step using the table (`WF_FIELDREF_NOT_GUARANTEED`, own result refused). Time and date-time fields are not in scope (S137 `ExpressionRules.ofField`), so a formula naming one is `WF_EXPRESSION_INVALID`.
- Results cannot use other results (decision formulas have no locals), so there is no cycle to detect.
- Overlap (UNIQUE/ANY): a formula test may hold for any value, so only the comparisons can prove two rows exclusive. Rows proven exclusive by their comparisons pass silently; otherwise the compiler warns (`WF_DECISION_EXPRESSION_OVERLAP`, one warning per table naming up to 5 pairs) instead of claiming an overlap or exclusiveness. Rows whose tests are all comparisons keep the S40/S121 proof and blocker, even with formula results. Under ANY, identical formula text counts as the same result. The "default never used" coverage warning is skipped for tables with formula tests.
- A result computed by a formula has the default's type but no known value set (and numbers may have decimals), so later conditions on it are no longer checked against a domain (`FieldTypes.outputType`).

#### Evaluation (identical in Java and TypeScript)

`com.orqix.compiler.internal.eval.ExpressionTable` (Java) and `frontend/src/lib/expr/decision.ts`; shared cases `examples/fixtures/decision-expression-cases.json` (28 cases), read by `DecisionExpressionCasesTest` and `decision.test.ts`.

- Every input entry of every row is evaluated (so the evidence lists all of them); literal comparisons keep their rule (the field needs a value).
- Output formulas are evaluated for every matching row; a selected row whose result has no value stops the evaluation.
- UNIQUE: two applying rows stop the evaluation (the proof is impossible at publish time, so it is checked per request). ANY: applying rows with different results stop it. FIRST, PRIORITY, COLLECT as before; COLLECT combines literal and computed numbers exactly.
- Values by fieldRef (dates as YYYY-MM-DD text or LocalDate); reserved `$now` (instant for `today()`) and `$asOf`. Types: the compiler's pinned types, or inferred from the values (number, text, true/false, date as LocalDate; a missing value may be anything) where no workflow context exists (shared decisions in cases and agent tools).
- `DecisionEvaluator.Result` gains `expressions` (`Entry(row, term, kind, target, expression, value)`, canonical order: by row, tests by term, then results by name); kept constructors.
- Tables without formulas take the unchanged S40/S121 evaluator.

#### Engine and runtime

- Engine DMN (`ExpressionDmn`, only for tables with formulas): the compared fields keep their `in<n>` columns; every formula test is a boolean column `x<k>` tested `== true`; every formula result is the output entry `#{y<k>}`. The app runs the canonical evaluation first (types pinned in the BPMN, engine clock for `today()`, submission day for effective windows), passes the results as variables (`DecisionTables.engineVariables`) and requires the pinned DMN to select the same rows; it stores the canonical exact values after checking the engine returned the same ones.
- BPMN, only for decision steps whose table uses formulas: `orqix:decision typesBase64` (pinned types, S137 notation) and `flowable:async="true" exclusive="true"` with the workflow's retry cycle, so a data-dependent failure (two rows under UNIQUE, a result without a value, an exceeded bound) makes the request need attention instead of undoing the approval before it.
- Evidence (S21, `DECISION_EVALUATED`): each candidate row may carry `expressions: [{term, fieldRef, expression, result}]` (true/false/null) and `outputExpressions: [{output, expression, value}]` for matching rows; `inputs` also lists the fields named only inside formulas (fields without a value are left out). Redaction is unchanged: metadata-only readers get the key, version and `evidenceRedacted` only.
- `evaluateDecision` (Test this rule): uses the compiler's types for the draft (`WorkflowCompiler.decisionTypes`, new default method), `$now` = now, returns `expressions`; a stopped evaluation answers 422 with the reason in the field error. OpenAPI: optional `DecisionEvaluateResult.expressions`, schema `DecisionExpressionEntry`.
- Shared decisions: `DecisionTables.check` also parses the formulas; case decision tasks must map the fields the formulas name too (`DecisionTables.fields`); agent decision tools list them as string/number/boolean arguments.

#### DMN download and upload (S121)

- Download: a formula entry is written with the standard DMN attribute `expressionLanguage="https://orqix.example/expression"` on its `inputEntry`/`outputEntry`, the Orqix text verbatim in `<text>`. It is not translated to FEEL: a lossy FEEL text could mean something else in another tool, while an unknown expression language is refused or flagged there. A column tested only by formulas has no `typeRef`. An Any row with a formula is written as one rule per alternative (UNIQUE exported as ANY, as for S121's multi-field Any rows); under COLLECT such a row is refused with a reason.
- Upload: entries with that language become formula entries again (text taken verbatim, edge spaces included); the compiler then checks them as for CSV. Another expression language is refused with a named problem; FEEL outside the S121 subset stays refused. Orqix's own download reads back to the identical table (except Any rows with formulas, which read back as one row per alternative with the same meaning).

#### CSV (S89)

- A formula cell is `=` followed by the expression verbatim, in an input cell or a result cell. Export applies the existing formula-injection neutralisation, so the file holds `'=input.amount > 5`; import strips the apostrophe; a hand-typed `=...` reads the same.
- A column of formulas only has the new type `expression` (`in:expression:/input/due`); a comparison in such a column is refused (`formula`). Formula cells may be up to 2002 characters (the 2000-character expression, `=` and the apostrophe); other cells keep 120.
- Refused, never changed: a formula with a line break, tab or other control character, or an edge space (export), and, because a cell starting with `=` is now a formula, a text result starting with `=` (export; the compiler never allowed such a text, `WF_LITERAL_UNSAFE`). The default line stays literal. Export still proves its own round trip.

#### Simulator and Studio

- Compiler simulator: decisions with formulas use the canonical evaluation with the compiler's types and `$now` = the test day at 12:00 UTC; decision data rows gain `expressions`/`outputExpressions`; a stopped evaluation stalls with `DECISION_FAILED`.
- Studio (frontend): each row test and each result cell can be switched to "Formula" using the S137 `ExpressionEditor` with live type check (true/false for tests, the default's type for results), shown with a Formula badge; the PRIORITY table's priority result offers no formula; the editor warns when a test does not use its column. Test this rule asks for every field the table reads. Mock compiler, simulator, engine, evidence, CSV and DMN doubles follow the rules above.

### Decisions

- **No new syntax for the column's value.** A test names its column by its ordinary field name (`input.amount`) and must use it; FEEL's `?` was not added (a new token in both engines and every editor). The text reads the same in the table, the CSV and the DMN file.
- **Fixed results stay where the engine needs them**: the default and the PRIORITY ranking result are literals.
- **UNIQUE/ANY with formulas** are warned at publish time and enforced per request (stop with an error), never silently resolved by order.
- **`today()` in a decision** uses the engine clock at the decision (as S137 conditions do); effective windows keep the submission day.
- **Formulas read single values only**, so evidence stays scalar and Test this rule keeps one value per field.
- **DMN uses `expressionLanguage`** rather than a FEEL translation with an `orqix:` extension (see above).
- **Async only for formula tables**: existing decision steps keep their synchronous BPMN.

### Verification

- Compiler module `mvnw -o -pl compiler verify`: 1218 tests, 0 failures, coverage met, golden BPMN/DMN unchanged. This ran before the last change (async BPMN for formula decision steps); after it `DecisionExpression*`, `GoldenFilesTest` and `DmnParityDecisionTest` were re-run and pass.
- New compiler tests: `DecisionExpressionCasesTest` (28 shared cases), `DecisionExpressionsCompilerTest` (7: checks and paths, dates, overlap warning vs proof, PRIORITY, engine DMN/BPMN/async/variables, standard DMN export, simulator).
- App (Testcontainers PostgreSQL + Flowable), last run after all changes: `DecisionExpressionsRuntimeTest` 3/3 (pinned decision with formula tests and results incl. a date, evidence and redaction; UNIQUE double match needs attention; Test this rule, CSV and DMN round trips through the API, foreign expression language refused), `DecisionCsvTest` 13, `DecisionDmnTest` 6, `DecisionCsvApiTest` 4, `CatalogApiTest` 8, `SharedAssetsApiTest` 5, `CaseDecisionFileApiTest` 4, `EngineSpikeTest` 9, `SecurityMatrixTest` 8 — all pass. `ArchitectureTest` 5/6: `modulithStructureIsValid` fails on `runtime.internal.WaitReminders` using `compiler.internal.json.StrictJson`, a file this part does not touch (from commit ae0af1b5 on the base). Earlier run (before the async change): `DecisionDmnApiTest` 4, `DmnParityRuntimeTest` 2, `DecisionEvidenceTest` 3, `DecisionHitPolicyTest` 2, `ExpressionsRuntimeTest` 4 — all pass.
- Frontend: `npm run typecheck` clean; eslint clean on changed paths; targeted vitest (lib/expr, decision mocks, simulator, evidence, DecisionInspector, CSV section): 11 files, 322 tests passed. `api:generate`/`api:check` pass; `tools/contract-check/contract_check.py` 142/142 after fixing CRLF line endings.
- Redocly lint reports 2 errors at lines 12202 and 12317 of `contracts/openapi.yaml`, outside this part's hunks (unquoted descriptions with commas from another slice); not fixed here.

### Not covered

- FEEL text for formulas in the DMN download (other tools see an unknown expression language); importing foreign FEEL functions or arithmetic.
- Lists and rows in decision formulas; formula defaults; formulas for the priority result; date-typed results.
- Overlap/completeness proofs involving formulas (warned instead).
- Renaming a field does not rewrite formula text (as in S137).
- The mock engine marks a failed formula decision with reason `DECISION_FAILED` (like its existing `CALCULATION_FAILED`); the real app reports the failed job through its usual incident reasons.
- Not run: full backend `clean verify`, full frontend suite, browser check of the Studio editor in mock mode.

### Merge notes

- No Flyway migration; no shared CHECK constraint, permission, audit kind or `ProblemCode` changed.
- OpenAPI: `DecisionEvaluateResult` (existing schema, Workflows) gains optional `expressions`; new schema `DecisionExpressionEntry` in a block right after the `end of Expressions` schemas comment. No new operation (so no `Completion` tag line and no SecurityMatrix change from this part).
- `workflow-v0.schema.json`: `decisionTerm.oneOf` + `decisionExpressionTerm`; rows' `then` now `decisionRowOutput` (new def); default unchanged.
- Shared Java records/interfaces extended with kept constructors/defaults: `DecisionEvaluator.Result` (+`expressions`), new `DecisionEvaluator.Entry`, `DecisionEvaluator.evaluate(..., typesJson)` default, `WorkflowCompiler.decisionTypes` default, `WorkflowDefinition.DecisionTable.Row` (+`thenExpressions`, helpers `outputKeys`, `terms`, `usesExpressions`), `DecisionTable.usesExpressions`, `CatalogDtos.DecisionEvaluateResult` (+`expressions`). `Expression.references` (parse-only field references).
- Touched shared compiler files: ModelReader, WorkflowDefinition, TypeRules, LogicalConditions, ExpressionRules (decision scope, pinned types `node/<decisionId>`), FieldTypes, BindingRules, Tier1Rules, BpmnGenerator (decision task), DmnGenerator (delegates formula tables to the new `ExpressionDmn`), DmnExport, DefaultDecisionEvaluator, SimulationRun, DefaultWorkflowCompiler, DecisionTables.
- Touched app files: DecisionTaskDelegate, WorkflowService.evaluateDecision, DecisionCsv (+ `DecisionCsvApiTest` expectation: `=cmd()` is now `WF_EXPRESSION_INVALID`; unsafe-text case uses `a;b`), DecisionDmn, DmnXml (`rawText`), CaseService (decision field list), AgentDecisionTools, CatalogDtos.
- Frontend: regenerated `src/api/schema.d.ts`; `studioScope` (`features/studio/model/expressionScope.ts`) now leaves out time and date-time request fields (matches the backend rule; also affects branch and Calculate formulas in mock mode). `notation.test.ts` expects 21 step kinds while 22 exist since Wave C (`waitForFirst`): a pre-existing failure, not touched here.

## S146 part B - Document extraction (CG-A06, doc 29 section 7.11)

Branch `claude/s146-document-extraction`, based on `245d172c` (Waves A-C). Flyway V390 only (V391-V393 unused).
OpenAPI: one paths block after `# ---- end AiSkills ----` and one schemas block after `# ---- end AiSkills schemas ----`
(tag `Completion`). Compiler version not bumped; no golden BPMN moves (the AI task BPMN is unchanged: a document skill
is read by the runtime from the pinned skill version).

### What is delivered

#### 1. OCR providers behind the S52 contract

- `OcrProvider` (package `integration`) gains a compatible default method `analyze(bytes, contentType, documentType,
  timeout)` returning `OcrDocument`: pages (number, size, unit), lines and words with a box normalized to the page
  (0..1 from the top left) and a 0..1 confidence, key-value pairs the service extracted itself (mapped to template
  fields where the adapter knows the service's field), and invoice line-item amounts. The default wraps the old
  `extract` (plain text, no geometry), so the shipped `local-stub` bean and the S52 manual OCR endpoint are unchanged.
  `OcrException` classifies failures (unsupported, too large, credentials rejected, rejected, unavailable, egress
  denied) without bodies.
- Adapters (`com.orqix.ai.ocr`, JDK `HttpClient`, no SDKs, redirects never followed, 8 MiB answer limit, every address
  checked against `EgressPolicy` before it is called):
  - `local`: text and CSV as stored; PDFs with a text layer through the isolated parser JVM of S91 (25 MiB, 60 pages;
    lines without boxes); PNG, JPEG and TIFF through `TesseractOcr` when `orqix.ocr.tesseract.path` is set: the binary
    runs as a bounded child process (`<path> <image> <base> -l <languages> tsv`, deadline then kill, at most two at a
    time, temporary directory always removed) and its TSV is parsed strictly (`parseTsv`).
  - `AzureDocumentIntelligenceOcr`: `POST {endpoint}/documentintelligence/documentModels/{model}:analyze?api-version=`
    (default `2024-11-30`) with `base64Source`, then polling `Operation-Location` (must be the endpoint's own scheme,
    host and port; `Retry-After` honoured, bounded by the deadline). Models: `prebuilt-invoice`, `prebuilt-idDocument`,
    otherwise `prebuilt-read`. Polygons become boxes; word confidences give line confidences; prebuilt fields
    (InvoiceId, InvoiceTotal with its currency, Items amounts, DocumentNumber, FirstName, ...) become key-value pairs.
  - `TextractOcr`: JSON protocol `POST /` with `X-Amz-Target: Textract.AnalyzeExpense` (invoice), `AnalyzeID` (ID
    document) or `AnalyzeDocument` with `FORMS`, signed with the Bedrock `SigV4` signer (made public; service
    `textract`, session token supported). Blocks become lines and words; expense summary fields, line items, identity
    fields and form keys become key-value pairs. Synchronous operations only (images, single-page PDFs, 10 MiB).
- Credentials: `orqix.ocr.providers.<id>.secret-ref` resolved through the S132 `SecretResolver` (via
  `ModelProviderSecrets`, so reserved references are refused and access is audited like the model endpoints).
- Configuration `orqix.ocr` (`OcrProperties`): `default-provider` (default `local`), `tesseract.{path, languages,
  timeout, max-bytes}`, `providers.<id>.{kind, base-url, secret-ref, region, api-version, residency[], label, timeout,
  poll-interval}`.
- Workspace selection: `GET/PUT /workspaces/{ws}/ai-ocr-settings` (`ai_ocr_setting`, `workspace.manage-access` to
  change, optimistic `expectedVersion`, audit `ai.ocr-settings.updated`); the GET lists every provider with its
  residency tags and whether the workspace may use it.

#### 2. Document-type templates and the skill contract

- `com.orqix.compiler.DocumentTemplates` (pure Java, shared by the contract, the compiler and the runtime): built-in
  `invoice` (12 fields), `idDocument` (9) and `bankStatement` (10) with types, required flags, descriptions, hints
  (`iban` mod-97, `currency` ISO 4217, `country` ISO 3166 alpha-2/3, `pastDate`, `futureDate`) and cross-field checks
  (`sum`: invoice total = subtotal + tax and closing = opening + credits - debits, tolerance 0.01; `lineItems`: the
  invoice subtotal, else total, equals the sum of the line items the OCR service read; `after`: due date not before
  invoice date, expiry after issue, statement end after start). `custom` documents use the skill's own fields, which
  may carry their own `hint` (the cheap form of workspace-defined templates).
- `contracts/ai-skill-v1.schema.json` (compatible): input type `attachment`; skill member
  `document: {type, attachmentInput}` (extract only); field `hint`; an extract skill needs `fields` unless its
  document type is built in. `AiSkillContracts.checkSkill`: exactly the document input may be of type attachment, and
  it must be; `custom` needs fields. `AiSkillContracts.facts` takes the template fields when the skill declares none,
  so step outputs, the field picker and type checks see them.
- `AiTaskRules`: an `attachment` input accepts only a request file field (`format: attachment`).
- `GET /workspaces/{ws}/ai-document-templates` lists the built-in templates (Studio).

#### 3. Pipeline (`DocumentStage` inside `AiTaskEngine`)

After admission (approved and pinned version, profile under the residency rule, evaluation gate, budget):

1. The mapped file is read through the new port `integration.RunDocuments` (implemented by runtime
   `RunDocumentsAdapter`): only files of the request; removed, quarantined and scan-blocked files are refused (the
   task ends on `aiUnavailable` with the reason).
2. The attachment input's minimization action applies to the document text: `send`, `mask` (card numbers and e-mail
   addresses) or `maskDigits`; `hash` and `drop` refuse the document (it cannot be extracted).
3. OCR through the workspace's provider after the residency check. A stored layout of the same bytes from the same
   provider is reused, so a retried task repeats no OCR call. The text and layout are stored only as the file's S52
   OCR result (`attachment_ocr_result.layout`, `source aiTask`), which quarantine, removal and the retention purge
   delete. Decision records never hold the text: with `redactedValues` retention the retained input is the minimized
   mapped values (the file id), not the document.
4. The model sees the lines as data, `[L<n>] text` per line with page markers, and answers the fields, a confidence
   per field and the line each value came from (`fieldSources`, strict schema).
5. Per field: value; raw confidence = the model's figure, capped by the agreement across samples, by the OCR line
   confidence, lowered by 20 % when the value cannot be found in the recognized text, by 10 % after a repair; when the
   OCR service's own key-value pair agrees, its confidence is used (max), when it disagrees the field is flagged
   `DIFFERS_FROM_OCR` and capped at 0.5, when the model found nothing the service's value is taken with its confidence.
   Each raw value is calibrated on the version's evaluation buckets (S134). Validation hints and checks flag fields and
   cap them at 0.3. Source location: `ocrKeyValue` or `ocrLine`, page, line, normalized box, character span in the OCR
   text. A missing required field has confidence 0.
6. The step confidence is the lowest field confidence; routing is S134's extract routing (below threshold, a missing
   required field or `suggest` autonomy: `needsReview`). Metering: the model calls as `ai_generation.kind aiExtract`,
   each cloud OCR analysis as `aiOcr` (no cost known).

#### 4. Verification task

Choice (recorded): built on the S134 AI decision outcome API and the AI task's triggerable step, not on a Flowable
user task or the S141 actionable-task machinery. Less invasive: no BPMN or compiler change, the step simply stays
waiting, and the outcome, candidate and audit paths already exist.

- When a document extraction routes `needsReview`, `AiTaskJobs.complete` writes the decision record, opens an
  `ai_document_verification` (fields with value, confidence, needs-attention flag, problems and source; the AI outputs;
  no recognized text) and changes the current step to kind `verify` instead of triggering the engine. Events
  `AI_TASK_COMPLETED` and `AI_VERIFICATION_OPENED` (counts only). If the engine cancels the step, the verification is
  cancelled with the job.
- `GET /workspaces/{ws}/ai-verifications` (queue for `run.operate` / `ai.evaluate`), `GET .../runs/{runId}/ai-verifications`,
  `GET .../runs/{runId}/ai-verifications/{id}` (needs request-data visibility; adds the attachment's availability and
  each field's `sourceText` from the S52 OCR result while it exists) and `POST .../submit`.
- Submit: `{expectedVersion, outcome: verified|escalated, fields: {name: {action: confirm|correct|clear, value}},
  reason, acknowledgeProblems}`. Every field that needs attention must be answered; values are type-checked; corrected
  values that still fail a hint or check are refused unless acknowledged; escalation needs a reason. Who verifies:
  participants, `run.operate` or `ai.evaluate` holders, never the request's initiator (403 `SELF_APPROVAL_DENIED`).
  A stale version or a closed task is 409 `TASK_VERSION_CONFLICT`.
- Effect, in one transaction: the verification closes; the decision's human outcome is `accepted` (all confirmed) or
  `overridden` with value `corrected: <fields>`; corrections create a pending `ai_eval_candidate` whose expected
  fields are the verified values (input only when the decision retained values, as S134); the step outputs become the
  verified values with `status: verified` and the engine continues on `next` (escalated: AI values on
  `needsReview`); event `AI_VERIFICATION_COMPLETED` and audit `ai.verification.submitted` carry counts only. A for-each
  item counts a verified extraction as successful.
- Evaluation suites of document skills give the recognized text of the document as the attachment input's value; the
  evaluation sends it through the same numbered-line prompt.

#### 5. Frontend

- Verification screen `/w/:ws/runs/:runId/verifications/:id` (`features/verification`): the document beside the
  fields; images with the selected field's source box drawn on the page, text files with the source line highlighted,
  PDFs in the browser viewer at the source page. Fields with confidence badge, problems, source text, value editing
  (typed parsing) and Confirm; keyboard: Arrow Up/Down or j/k between fields, Enter confirms, e edits, Escape leaves
  the input. Submit is disabled while fields still need the person; "Send for review" needs a note.
- Run detail: "Document verification" panel linking to the task.
- `AiTaskInspector`: a Document section for document skills (type, document input, the OCR provider in use and its
  refusal, what verification does); a document input offers only request file fields (and other inputs no longer
  offer file fields, which the compiler refuses anyway).
- Mock mode: `src/mocks/documentExtraction.ts` (OCR settings, one open verification over a text invoice bound to the
  newest active purchase request, submit with the same rules; unit tests opt in with `resetMockVerification`).

### Decisions

- **Residency (conservative).** `local` is always allowed and the deployment default. A cloud OCR provider is used only
  when the workspace AI policy's residency rule names one of its residency tags, or, in a workspace that is not
  regulated and has no residency rule, after an administrator explicitly chose it. A regulated workspace without a
  residency rule never sends a document to a cloud service. Checked at selection and again before every analysis.
  Plain text files never leave the deployment, whatever the choice.
- **Document text is the attachment input's data**: its classification action applies to the text; `hash`/`drop`
  (e.g. the default for `personal`) refuse extraction instead of sending something useless. An ID-document skill
  therefore needs an explicit, policy-approved classification.
- **Stored text**: only the S52 OCR result holds text and layout; verifications and decision records hold values,
  confidences and locations only.
- **Verification on the AI decision outcome API** (above), initiator excluded.
- **PDF geometry**: the local PDF path gives lines without boxes (the S91 parser output has no page size); highlights
  on PDFs show the page and the source text, not a box.
- **Model and OCR agreement** is a checkable signal (S134 section 7.5 style); the OCR service's own confidence is used
  only when it agrees with the model.

### Verification

- Compiler: `DocumentTemplatesTest` (3) and `AiTaskCompilerTest` (8) pass; full compiler suite: 1186 tests, all passing.
- Application (Testcontainers PostgreSQL, real Flowable; AI from `AiTaskStub`, OCR only from WireMock):
  - `OcrProvidersTest` (5, no Spring): Azure analyze + polling + field mapping + boxes, foreign operation host refused,
    failures without bodies, egress denied; Textract SigV4 (recomputed and compared, session token), AnalyzeExpense /
    AnalyzeID / AnalyzeDocument parsing, 400 and size limit; Tesseract TSV parsing; a fake `tesseract` executable
    (Windows `.cmd`, POSIX `.sh`) through the bounded process, failing engine, no engine.
  - `DocumentExtractionFlowTest` (1): regulated workspace refuses the cloud provider until an EU rule admits it; a
    published workflow with an extract AI task over an uploaded file; a confident invoice continues on `next` with the
    OCR key-value confidence and outputs; a smudged total (low OCR line confidence and a failed sum) opens a
    verification task with source box, span and text; initiator refused, incomplete / failing / mistyped / stale
    submissions refused; the correction becomes the step output, the decision's human outcome and an evaluation
    candidate; events and audit without values; S52 OCR result written by the AI task and deleted by quarantine;
    a quarantined file refused; a policy change ends the next task on `aiUnavailable` without an OCR call.
  - Also run in the same build: `AiTaskFlowTest` 3, `ModelProvidersTest` 7, `AttachmentsApiTest` 21,
    `DocumentLifecycleTest` 6, `SecurityMatrixTest` 8 (the 7 new operations as foreign principals, 404, new tables in
    the snapshot): all passing. `ArchitectureTest` 6: 5 pass; `modulithStructureIsValid` fails on a pre-existing
    violation at the base commit, not touched here: `runtime.internal.WaitReminders` (line 267) calls
    `compiler.internal.json.StrictJson`. This part adds no new violation (the only new cross-module uses are the
    exposed `com.orqix.compiler.DocumentTemplates` and `com.orqix.integration` types).
- Frontend: `api:generate` + `api:check`, `tsc -b --noEmit`, ESLint on the changed files, Vitest `src/features/runs`, `src/features/verification`, `src/features/studio/inspector`: 45 files, 195 tests (new `VerificationPage.test.tsx` 3, two new `AiTaskInspector.test.tsx` cases). The full frontend suite and a browser run of the screen were not done.
- `tools/contract-check/contract_check.py`: PASS; Redocly lint (`.github/redocly.yaml`): 0 errors (two pre-existing
  struct errors in `ScheduleRequest` descriptions were fixed by quoting).

### Not covered

- No live Azure, AWS or Tesseract installation was used: adapters are verified against WireMock and a fake
  executable only. Textract asynchronous (multi-page PDF) operations and Azure `keyValuePairs` add-on are not used.
- Scanned PDFs on the local provider (no rasterizer); box overlays on PDFs; multi-page TIFF on Tesseract.
- Line items as extracted fields (they are used only for the sum check); workspace-defined templates as a separate
  asset (a `custom` skill with hinted fields covers the cheap case).
- The verification task is not a Flowable user task: no inbox entry, escalation policy, actionable message or due
  date (the queue API lists open ones; the run detail links to it). No Studio page for the queue.
- OCR cost is not metered in money (no prices known); the `aiOcr` rows count analyses.

### Merge notes

- Shared CHECK rebuilt: `ai_generation_kind_check` in V390 = previous list + `aiExtract`, `aiOcr` (Part C rebuilds a
  superset with `aiExplain`; take C's).
- S52 table changed: `attachment_ocr_result.requested_by` now nullable, new `source` (`manual` | `aiTask`) and
  `layout` columns, constraint `attachment_ocr_requested`; `AttachmentLifecycle.extract` clears `layout` on a manual
  re-run.
- New tables: `ai_ocr_setting`, `ai_document_verification` (both added to the `SecurityMatrixTest` snapshot).
- OpenAPI shared enum: `CurrentStep.kind` and `PathCurrent.kind` gain `verify`; tag `Completion` added after
  `AiSkills`; ScheduleRequest descriptions quoted (lint fix).
- `AuditWriter` whitelist: `ai.ocr-settings.updated`, `ai.verification.submitted`.
- `AiTaskPort`: `Outcome` gains `verification` (old 3-argument constructor kept) and `recordVerification(...)`.
- `SigV4` (ai.provider) is now public (reused by Textract).
- `SkillAnswers.Answer` gains `fieldSources`; extract prompts and schemas use `DocumentTemplates.effectiveFields`.
- Test support: `AiTaskFixtures` declares OCR provider `stub-azure` (shared WireMock `Stub`, `/ocr/azure/`),
  `AiTaskStub` answers document extractions from the numbered lines, new `OcrStub`.
- Frontend: `mocks/callbacks.ts` registers `createDocumentExtractionHandlers`; route added in `app/routes.tsx`;
  `RunDetailPage` renders `DocumentVerificationsPanel`.

## Part C - "Explain this request" for operators (CG-A07, doc 29 section 7.12)

Branch `claude/s146-operator-explain`, base 245d172c. Flyway V394 only (V395 unused).

### What is delivered

- **API** `POST /workspaces/{workspaceId}/runs/{runId}/explain` (operation `explainRun`, tag `Completion`,
  `x-orqix-permission: run.operate`, `x-orqix-audit: ai.run.explained`). Optional body `{preferRules}`. Returns
  `RunExplanation`: `source` (`ai` or `rules`), `summary`, `cause`, `evidence` (fact ids), `suggestedActions`
  (`{actionId, why}`), `facts`, `permittedActions`, `droppedActions`, `fallbackReason`, `ai` (profile, model,
  promptVersion, cached), `readOnly: true`, `stateDigest`, `generatedAt`.
- **Facts builder** `runtime.explain.RunFacts` (pure Java). Reads the run detail as built for the caller (current
  steps, attention, open tasks, effects, children, suspension, allowed actions), the newest 200 timeline entries, the
  open waits with their checks (S129/S139), the binding state of the connections the request uses (binding present or
  enabled, circuit state and pause, token-refresh failures in the last hour, last failure of the day; S131/S132) and
  the compiled model of the release (type, connection and operation, timeouts, due times, wait durations, retry
  attempts, routes of the stuck and current steps). Output: at most 40 facts `F1...` (12 at most from the timeline,
  each linked to its timeline `seq`), and the trailing failure streak of the stuck step ("has failed 3 time(s) in a
  row with HTTP_403 since 2026-11-08 09:12 UTC").
- **Permitted actions** computed for the caller from the same checks the actions use: `retryRun`,
  `reconcileEffect:<effectId>`, `resumeRun`, `cancelRun` (from `RunDetail.allowedActions`, which applies
  `run.operate`, lifecycle, suspension and effect state), `correctRunInput` (needs attention, request data visible,
  no effect captured, at least one `incidentEditable` field; the description names the field names only),
  `repairRun` (attention `RUN_WAITING_ON_NOTHING`), `checkWaitNow|extendWaitDeadline|stopWaiting:<waitId>` (open
  checks with `canOperate`), `reassignTask:<taskId>` (up to three open tasks), and for a connection whose binding or
  credential is the likely cause either `updateConnectionBinding:<key>` (kind `open`, caller holds
  `connection.manage`) or `askAdministrator:connection:<key>` (kind `askAdministrator`, no operation id: "someone with
  connection.manage must ... authorize it again after a token rotation").
- **Rules** `runtime.explain.RuleExplainer` (pure Java): ended, suspended, needs attention (credential refused 401/403
  or token refresh failing, unusable binding, unknown outcome, retries exhausted with or without an open circuit,
  rejected call, connection unavailable, waiting on nothing, generic), then running/waiting (paused circuit, failing
  check, overdue task, plain wait by kind). Parallel waits are added to the summary ("Meanwhile step "Cool-off period"
  waits for a timer until ..., which is expected."). Every cited id is a fact id; every suggestion is a permitted
  action (others are skipped).
- **AI explanation** `runtime.explain.AiAnswer` + `ai.tasks.OperatorExplainModel` behind the new port
  `integration.OperatorExplainPort` (same pattern as `AiTaskPort`; the runtime module may not depend on `ai`). Fixed
  system text (`explain-run-v1`) that declares the `<run_facts>` block to be data, never instructions; facts and
  permitted actions as JSON inside it; strict JSON schema (`summary`, `cause`, `evidence`, `suggestedActions` with at
  most 3 entries; all required, no extra properties); temperature 0; `orqix.ai.operator-explain.max-output-tokens`
  (800) and `timeout` (30 s). Profile resolved through `ProviderRegistry.resolve` under the workspace AI policy's
  residency rule; monthly budget checked before the call; one `ai_generation` row of kind `aiExplain` per model call
  (`success`, `invalidOutput`, `providerError` or `credentialsRejected`; metering facts only). The answer filter keeps
  only known fact ids and only permitted action ids; anything else goes to `droppedActions` (and the audit count),
  never to the UI as an action. A non-JSON answer or one without a summary falls back to the rules
  (`fallbackReason: invalidAnswer`).
- **Fallback** (`source: rules`, `fallbackReason`): `aiOff` (`orqix.ai.operator-explain.enabled=false`),
  `featureOff` (workspace AI switch off, S127), `notConfigured` (profile unknown or refused by policy), `residency`,
  `budget`, `unavailable` (provider failure), `invalidAnswer`. `preferRules: true` answers from the rules without a
  fallback reason.
- **Cache**: an AI answer is reused for the same caller, request and state digest (sha-256 of fact ids and texts and
  permitted action ids) for `orqix.ai.operator-explain.cache-ttl` (120 s, at most 10 min; 256 entries per pod, cleared
  when full). AI being switched off (deployment or workspace) is checked before the cache, so a cached answer is
  never served once AI is off. A reuse makes no model call and no metering row; `ai.cached` is true. A permission change changes the
  permitted actions and so the digest.
- **Audit** `ai.run.explained` on resource `run` for every explanation: `source`, `profile`, `model`,
  `promptVersion`, `cached`, `factCount`, `suggestedCount`, `droppedCount`, `fallbackReason`. No text.
- **Frontend**: `ExplainRunPanel` in run detail for callers with `run.operate` (below Operator actions): "Explain this
  request" button, badge "AI-generated, read-only" or "Rule-based, read-only" with the reason the rules answered,
  summary, likely cause, suggested actions (an `operate` action is a button that opens the existing dialog: retry,
  reconcile with the effect preselected, cancel, resume, consistency check, reassign; correct input and wait actions
  scroll to their panels; `updateConnectionBinding` opens Settings; `askAdministrator` is text only), "Show the
  rule-based explanation", and an Evidence list whose timeline facts link to `#event-<seq>` (timeline entries now
  carry that id). Mock mode: `src/mocks/runExplain.ts` (rule-based, from the mock run detail) wired in `handlers.ts`.

### Decisions

- **Read-only POST.** POST (not GET) because a model call is metered and audited; no Idempotency-Key, no
  `expectedVersion`. The service never calls an engine command or a run mutation: run detail, waits, events, release
  and connection state are read in one read-only transaction; the metering row and the audit entry are the only
  writes, each in its own transaction.
- **Permission `run.operate`** (as `getRunDiagnosis`): the explanation exists to choose an operator action and costs
  money; readers without it (viewers, initiators, approvers) get 403 (audited `auth.denied`), callers who cannot see
  the request 404. No new permission.
- **Not gated by the AI feature switch**: with AI off in the workspace the endpoint still answers from the rules
  (`featureOff`), so it is not in `WorkspaceFeatures.GATED_OPERATIONS`.
- **Redaction, conservative default: no values at all**, even for callers who may read request data. Fact texts are
  written by Orqix from codes, statuses, times, step labels, group keys and connection keys. Never read into a fact:
  request input/output, request title, wait subjects, correlation references, `attention.message` (it can carry a
  business message), effect `lastErrorDetail`, worker messages, event data outside a whitelist of keys
  (`errorCode`, `httpStatus`, `attempt`, `classification`, `reason`, `connectionKey`, `until`, `nextRetryAt`,
  `topic`, and only when the value is a short code), people's names (a task is "assigned to a person"), decision
  values, condition values of routes. Field names appear only in the `correctRunInput` action, and only when the caller
  may read request data.
- **Rules first-class**: the rule explanation is a full answer, not an error page, so AI being off, over budget or
  outside residency never removes the feature.
- **Metering through a port**: Spring Modulith forbids `runtime -> ai`; `OperatorExplainPort` (integration) is
  implemented in `ai.tasks`, and binding state for other modules is read through the new exposed
  `integration.ConnectionStatus` (codes and statuses only, never base URLs or secret references).
- **Profile**: `orqix.ai.operator-explain.profile` (empty = deployment default). Tests set `task-openai` (the
  `AiTaskStub`).

### Verification

- `RunFactsTest` (6, no Spring): facts bounded and free of request values, wait subjects, correlation refs and error
  details; first-failure fact with time and timeline seq; model facts (operation, timeout, routes); permitted actions
  per caller (operator: ask an administrator; with `connection.manage`: the binding action; correct input only with
  data access and no effect; nothing without `run.operate`); rules for a 403 with a parallel timer and for a plain
  timer wait (no suggestions); the AI filter (unknown fact ids, a non-permitted action and a binding action the caller
  lacks are dropped; non-JSON and summary-less answers are refused); prompt carries facts inside `<run_facts>` without
  values and the strict schema.
- `RunExplainTest` (1, real PostgreSQL and Flowable, AI from `AiTaskStub`): a request whose procurement call is
  refused with 403 (new `Stub.Mode.STATUS403`) while a parallel branch waits on a one-day timer. AI answer
  (`source ai`, stub summary, permitted actions exactly ask-admin, reconcile, retry, cancel; the stub's `deleteRequest`
  dropped; unknown fact `F999` dropped; facts mention HTTP_403, the timer step and procurement but no request values;
  the provider request has temperature 0, the `<run_facts>` block and no request values; one `aiExplain` row);
  second call reused (`cached`, no new call or row); `preferRules` (rule summary "procurement has refused the call with
  HTTP 403 since ... Meanwhile ... waits for a timer until ..."); workspace AI switched off (`featureOff`, no model
  call); after a `connection.manage` grant the binding action replaces "ask an administrator" and the digest changes;
  viewer and initiator 403, outsider and other workspace 404, unknown run 404; five audit rows with counts only; the
  request's lifecycle, version, current steps, attention, event count, effect states, timer and dead-letter jobs and
  the stub's call count are identical before and after. Responses are validated against the OpenAPI contract.
- `SecurityMatrixTest` (8, all passing; includes `explainRun` as foreign principals: 404 and no state change).
- `ArchitectureTest` (6): 5 pass; `modulithStructureIsValid` fails on a violation that is **not from this part**:
  `runtime.internal.WaitReminders.refusal` (S139, commit ae0af1b5) calls the non-exposed
  `compiler.internal.json.StrictJson`. The violations this part first caused (runtime -> ai, runtime ->
  integration.health) are fixed by the port and `ConnectionStatus`; no violation names an S146 part C class.
- Frontend: `npm run api:generate`, `npm run api:check`, `npm run typecheck`, ESLint on the changed files; Vitest
  `ExplainRunPanel.test.tsx` (3) and `RunDetailPage.test.tsx` (17).
- `python tools/contract-check/contract_check.py`: PASS. Redocly lint: the two errors it reports (struct errors at
  the `WorkingWeek` zone and another inline description with a comma, around lines 12242 and 12357) are not in this
  part's blocks; the coordinator may want to quote them.

### Not covered

- No streaming; one model call, no repair turn (an invalid answer falls back to the rules).
- The model sees only Orqix's own fact texts; deeper diagnosis (engine consistency check, external worker logs,
  remote error bodies) stays in the existing operator tools.
- The cache is per pod and in memory.
- Delegated OAuth consent state per user (S67) is not read; a 401/403 or token-refresh failure is reported as "check or
  authorize the credential again".
- No browser e2e run of the panel against the full stack (unit tests and mock mode only).

### Merge notes

- Flyway `V394__ai_operator_explain.sql` rebuilds `ai_generation_kind_check` with the full S146 superset
  `draft, explain, agent, form, draft-edit, aiTask, aiEval, aiExtract, aiOcr, aiExplain` (identical to what part B's
  migration should produce plus `aiExplain`).
- `AuditWriter.METADATA_WHITELIST`: new `ai.run.explained`.
- `contracts/openapi.yaml`: tag line `  - name: Completion` after `AiSkills`; paths block "S146 Completion: operator
  explain paths" after `end of S141 TaskActions paths`; schemas block (`ExplainRunRequest`, `ExplainFact`,
  `ExplainPermittedAction`, `ExplainSuggestedAction`, `ExplainAiInfo`, `RunExplanation`) after `end of S141
  TaskActions schemas`. `frontend/src/api/schema.d.ts` regenerated.
- New shared/support code: `integration.ConnectionStatus`, `integration.OperatorExplainPort`,
  `ai.tasks.OperatorExplainModel`; test support `Stub.Mode.STATUS403`, an `AiTaskStub` branch for `<run_facts>`
  requests, `AiTaskFixtures` property `orqix.ai.operator-explain.profile=task-openai`.
- Frontend shared files: `RunDetailPage.tsx` (panel, action mapping, timeline `id="event-<seq>"`),
  `mocks/handlers.ts` (one handler).

## S146 part D - AI skills Studio

S134 (CG-A01/A02) delivered the complete AI skills API (27 operations, tag `AiSkills`) but showed skills only in the Studio's `AiTaskInspector` and decisions only in run detail ("Not covered" in [S134](S134-ai-decisions.md)). Part D adds the pages over that API. Frontend only: no backend, OpenAPI, Flyway (V396 unused) or compiler change.

### What is delivered

All under `frontend/src/features/aiskills/`; routes in `src/app/routes.tsx`, navigation in `src/app/Shell.tsx`.

- **API layer** (`api.ts`): one function per S134 operationId on the generated `schema.d.ts` types and the shared `openapi-fetch` client; query keys reuse `['ws', ws, 'ai-skills']` from the inspector, so Studio's skill picker refreshes after a change here.
- **AI skills** (`/w/:ws/ai-skills`, Design group, `workflow.read | ai.evaluate | ai.approve-skill`): a table of skills (mode, latest version and state, the approved version in use with its autonomy ceiling, drift and evaluation-gate warnings). Tabs for skills and **evaluation suites**. "New skill" (`workflow.edit`) opens the editor; saving creates version 1 as a draft and opens the skill.
- **Skill editor** (`SkillEditor.tsx`, `skillDocument.ts`): a structured form over `contracts/ai-skill-v1.schema.json` kind `skill`. It covers key, name, description, mode, instructions, model profile (with a datalist of the S10 profiles), typed inputs with classification, max length and required; labels with risk and adverse (classify); fields (extract); score range (score); outcomes with adverse (recommend); summary length (summarize); examples as JSON; confidence method and samples; evaluation suite, minimum accuracy, minimum cases and per-label minimum recall; limits; promotion and drift settings. The JSON view is the fallback for anything the form leaves out. Both views go through the same cheap checks before saving: required members, patterns, ranges, unknown top-level members, members that do not fit the mode, duplicate keys, score max above min, recall targets on unknown labels, and samples above 1 needing `selfConsistency` (the semantic checks of `AiSkillContracts.checkSkill`). Server field errors (`/document/...`) land on the same fields.
- **Skill detail** (`/w/:ws/ai-skills/:skillKey?v=n`): a version switcher and a version card showing state, author (you or another person), profile, submitted and approved times and the content hash. Actions, each shown only with its permission: edit draft (also a submitted version, which returns to draft), submit, approve (`ai.approve-skill`, reason required), new version (only when nothing is in progress; it starts from the latest document), and retire (the kill switch, with a confirmation). Tabs:
  - **Document**: instructions, inputs, labels or outcomes, and the raw JSON.
  - **Evaluations**: start a run (`ai.evaluate`) on a suite of the skill (default: the skill's `evaluation.suite`). The list and the selected run poll every 2 s while a run is running. The metrics view shows accuracy, correct and invalid answers, cost per case and total cost, p95 latency, a targets table (cases, accuracy, each `minRecall`) marked met or not met, the server's failure list, per-label precision, recall and support, the confusion matrix (rows expected, columns AI), calibration buckets (buckets under 5 cases marked as not used) and the threshold trade-off.
  - **Shadow and drift**: decisions, shadow decisions, compared and agreed, agreement, statuses, the confusion between AI and human value, per-label stats, cost, latency, the ceiling, drift, and progress badges against `minShadowCases` and `targetAgreement`. "Run drift check now" (`ai.evaluate`, approved versions) shows the result; "Clear drift" (`ai.approve-skill`, reason) appears while the fallback is on.
  - **Promotions**: history with evidence and decisions. A request (`workflow.edit`, approved version, next step only, none pending) needs a reason. A decision (`ai.approve-skill`) shows the stored evidence and needs a reason.
- **Four eyes in the UI**: an author who holds `ai.approve-skill` sees "You wrote this version, so you cannot approve it…" instead of the Approve button. The person who asked for a promotion sees the same rule instead of the decide buttons. People without the grant see who must approve. Server refusals are still shown properly (`AiProblem.tsx`): `SELF_APPROVAL_DENIED` (403) gets a skills-specific four-eyes message; `AI_SKILL_STATE_CONFLICT` (409), `AI_MODEL_NOT_ALLOWED` and `VALIDATION_FAILED` show the mapped message plus the server's reason line ("Only a submitted version can be approved."); `CONFIGURATION_VERSION_CONFLICT`, `REASON_REQUIRED` and `FORBIDDEN` get plain messages; other codes fall back to `ProblemAlert`.
- **AI review** (`/w/:ws/ai-review`, Operate group, `ai.evaluate | run.operate`): the review queue (`GET /ai-review-queue`), with links to the request (where the accept or override is recorded) and the skill version. Evaluation candidates (`ai.evaluate`) can be accepted into a suite of the same skill (suite, case id, redacted or synthetic input JSON, expected JSON, with a warning to redact) or discarded.
- **AI policy** (`/w/:ws/ai-policy`, Manage group for `workspace.manage-access | ai.approve-skill | ai.evaluate`; also linked from AI skills and readable by `workflow.read`). It shows the regulated flag, residency tags, input retention, retention days, low-risk labels (`skillKey:label`), allow autoWithReview, and the action per classification. It also lists the deployment's endpoints with their residency tags and whether the saved rule admits them. Only `workspace.manage-access` edits, and saves send `expectedVersion`. A concurrent change gives the conflict message and a "Reload the policy" button. The page checks before saving: tag and label patterns, days 1-3650, and that a regulated workspace never sends sensitive fields.
- **Mock mode** (`src/mocks/aiSkills.ts`, extended): an in-memory double of all 27 operations, reset with the mock database. It seeds an approved classify skill with a failing evaluation (recall target missed), a pending promotion, shadow figures, a suite of 60 cases, a review-queue item and a candidate, plus a submitted recommend skill. It keeps the lifecycle, four-eyes and optimistic-version rules. Started runs finish after 3 s. `src/mocks/state.ts` gives the demo personas the grant-only AI permissions: Dana Designer `ai.evaluate`, Paul Publisher `ai.approve-skill`. This lets the four-eyes flow be clicked through in `npm run dev:mock` (Dana authors, Paul approves).

### Decisions

- **No backend change.** Every read the pages need exists: list and get skills with versions, evaluation runs per version, one run, suites, the shadow dashboard, promotions per skill, the review queue, candidates and the policy. A workspace-wide "pending promotions" list would be convenient, but approvers find pending promotions per skill, and the skills list shows the state. Not added.
- **Navigation.** "AI skills" sits in Design next to Decisions, "AI review" in Operate next to Insights, and "AI policy" in Manage next to Promotions. This follows the existing permission-based groups. Designers reach the policy read-only through the link on AI skills, so `workflow.read` alone does not add a Manage entry. The pages are not gated by the `ai` workspace feature switch, because the S134 AiSkills API is not gated by it either.
- **Separate API module.** `features/aiskills/api.ts` is used instead of adding to the shared `src/api/endpoints.ts`, as `connectorPackagesApi.ts` does, to keep merges with the other S146 parts clean.
- **Messages.** The skills-specific messages for the codes above are shown instead of the generic `problemMessages` table entries ("You can't approve your own request." speaks of requests). The server's detail is shown only as a secondary "Reason:" line for state conflicts, model refusals and validation, never as the main message. This follows the rule in `problemMessages.ts`.
- **Client checks are a convenience.** They mirror the contract cheaply (no JSON Schema engine in the bundle); the server's `AiSkillContracts` check remains authoritative.
- **People are shown as "you" or "another person".** The skill APIs return ids only, and there is no member lookup the readers of AI skills may always call.
- **Suite editor checks.** The suite editor checks cases against the approved (else latest) version of its skill, mirroring `checkSuiteFor`: inputs the skill has, and the expected label, outcome, score, fields or phrases per mode.

### Verification

- `npm run typecheck` (tsc -b --noEmit): clean.
- `npx eslint src/features/aiskills src/mocks/aiSkills.ts src/mocks/state.ts src/app/Shell.tsx src/app/routes.tsx src/app/Shell.test.tsx`: clean.
- New Vitest files, 23 tests, all passing:
  - `skillDocument.test.ts` (5): round trip, contract and semantic problems at their pointers, score range and mode members, example JSON, server pointer mapping.
  - `AiSkillsPage.test.tsx` (3): list and reader without authoring; the new draft refused with field problems, then created and opened; JSON view problems and unparsable JSON.
  - `AiSkillDetailPage.test.tsx` (10): approval by a different approver with a reason required; the author with the grant sees why they cannot approve; 403 `SELF_APPROVAL_DENIED` and 409 `AI_SKILL_STATE_CONFLICT` messages in the dialog; submit and the approver hint; evaluation metrics against targets (accuracy, cost, latency, targets met and not met, per label, confusion, calibration, thresholds) and a new run followed until it passes; evaluate hidden without `ai.evaluate`; promotion decided by another approver, raising the ceiling; promotion requested by a designer; four-eyes notice for the requester; shadow dashboard and drift check.
  - `AiPolicyAndReview.test.tsx` (5): policy save with `expectedVersion`, the conflict and reload; client checks (regulated plus sensitive send, label pattern); read-only for non-administrators; review queue for operators; candidate accepted into a suite.
- `src/app/Shell.test.tsx` updated for the new navigation entries.
- Full frontend Vitest run (`npx vitest run` on base 245d172c plus this part): 228 files, 1713 tests; 1712 pass and 1 fails. The failure is `src/features/studio/model/notation.test.ts`, "maps every step kind to a shape" (`ALL_KINDS` length). This part changes no studio file, so the failure was already there at the base commit, and the coordinator should check it after the merge.
- Browser (mock mode, `vite --mode mock`): signed in as Paul Publisher, the submitted Refund reason version was approved with a reason; the page then showed "Approved", "Approved … by you" and "approved version 1 in use". After later reloads the hidden browser pane stopped answering the mock `/me` call (Home stayed on "Loading your workspace"). For that reason the other pages were checked through the Vitest page tests only, which render the real routes against the same mock handlers.

### Not covered

- A workspace-wide list of pending promotions or submitted versions for approvers. That needs a new read endpoint, and the per-skill views were judged enough.
- Display names for authors, approvers and requesters (ids only in the API).
- A complete JSON Schema validation in the browser (the cheap subset is listed above).
- The review queue does not record the review itself: that is the existing accept or override in the run's AI decisions panel (S134).
- Pages are not part of the Playwright e2e suites.

### Merge notes

- Shared files touched:
  - `frontend/src/app/routes.tsx`: four routes, `ai-skills`, `ai-skills/:skillKey`, `ai-review` and `ai-policy`, inserted before `decisions`.
  - `frontend/src/app/Shell.tsx`: three nav items and the group label lists; three lucide icons imported.
  - `frontend/src/app/Shell.test.tsx`: expected nav lists for the operator and the administrator.
  - `frontend/src/mocks/state.ts`: `AI_GRANTS` in `permissionsOf`. Dana gets `ai.evaluate` and Paul gets `ai.approve-skill` in the workspaces where they are members. Tests that list these personas' permissions or navigation would see them.
  - `frontend/src/mocks/aiSkills.ts`: rewritten as a stateful double. `MOCK_AI_SKILL` is still exported; its author and approver are now Dana and Paul. A second skill, `refund-reason`, exists, so anything that counts the mock skills sees two.
- No OpenAPI, Flyway, ProblemCode, audit, enum or CHECK constraint change. V396 is unused.
