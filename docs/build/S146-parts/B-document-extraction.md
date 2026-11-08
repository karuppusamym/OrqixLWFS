# S146 part B - Document extraction (CG-A06, doc 29 section 7.11)

Branch `claude/s146-document-extraction`, based on `245d172c` (Waves A-C). Flyway V390 only (V391-V393 unused).
OpenAPI: one paths block after `# ---- end AiSkills ----` and one schemas block after `# ---- end AiSkills schemas ----`
(tag `Completion`). Compiler version not bumped; no golden BPMN moves (the AI task BPMN is unchanged: a document skill
is read by the runtime from the pinned skill version).

## What is delivered

### 1. OCR providers behind the S52 contract

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

### 2. Document-type templates and the skill contract

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

### 3. Pipeline (`DocumentStage` inside `AiTaskEngine`)

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

### 4. Verification task

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

### 5. Frontend

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

## Decisions

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

## Verification

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

## Not covered

- No live Azure, AWS or Tesseract installation was used: adapters are verified against WireMock and a fake
  executable only. Textract asynchronous (multi-page PDF) operations and Azure `keyValuePairs` add-on are not used.
- Scanned PDFs on the local provider (no rasterizer); box overlays on PDFs; multi-page TIFF on Tesseract.
- Line items as extracted fields (they are used only for the sum check); workspace-defined templates as a separate
  asset (a `custom` skill with hinted fields covers the cheap case).
- The verification task is not a Flowable user task: no inbox entry, escalation policy, actionable message or due
  date (the queue API lists open ones; the run detail links to it). No Studio page for the queue.
- OCR cost is not metered in money (no prices known); the `aiOcr` rows count analyses.

## Merge notes

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
