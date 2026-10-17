# S52 - Document template registry, attachment quarantine/retention and the OCR provider contract (DOC08)

Builds on S25 (request files) and S29 (generated documents). DOC08 adds three bounded pieces: a versioned library of document templates, an operator quarantine and a per-workspace retention period for request files, and a provider contract for text extraction with a local stub. No live OCR service is called.

## Template registry

A workspace has up to 200 named templates (unique ignoring case). Each template has immutable, numbered versions (at most 100): a format (PDF or Word), plain text up to 16,000 characters using the same `{{field}}` syntax and character rules as the Generate a document step, the placeholders found, a hash and an optional change note. A bad placeholder is refused with a message and nothing is written.

| Operation | Requires | Notes |
| --- | --- | --- |
| `listDocumentTemplates`, `getDocumentTemplate` | `workflow.read` | Archived templates are hidden unless asked for. |
| `createDocumentTemplate`, `addDocumentTemplateVersion`, `setDocumentTemplateArchived` | `workflow.edit` | Adding a version sends the latest version the caller saw: `409 WORKFLOW_VERSION_CONFLICT` otherwise. A version identical to the latest and a version of an archived template are `422`. |

Requesters and approvers have no `workflow.read` (403); a caller outside the workspace gets 404. Audit actions `document.template.created|versioned|archived|restored` carry only counts (version, placeholder count); template text and names never reach the audit log.

Studio: in the Generate a document step, "Start from a saved template" copies the latest version (text, format) into the step and keeps existing field mappings for placeholders that remain. The step then holds its own text. **The registry is a library, not a live reference**: a published release keeps the text it was published with, and a later registry change never alters it (no `templateRef` is stored in the workflow, so there is no per-release provenance link to a template version). Settings > Document templates lists templates, shows every version, and lets designers create, add versions and archive/restore.

## Attachment quarantine

`quarantineAttachment` / `releaseAttachment` (both `run.operate`, idempotent in effect) block download and text extraction of one file while keeping its bytes. A reason of 3 to 300 characters is required and is shown to people who can see the request's files; audit and timeline carry no reason text. The initiator can no longer remove a quarantined file (operators can). Release does not clear a virus-scan block. Timeline events: `ATTACHMENT_QUARANTINED`, `ATTACHMENT_RELEASED` (details redacted for readers without request data). The file list gains `quarantinedAt` and `quarantineReason`; `downloadable` is false while quarantined. Run detail shows the state and operators get Quarantine and Release.

## Retention

`getAttachmentRetention` / `setAttachmentRetention` (`workspace.manage-access`): `retentionDays` 1 to 3650, or null to keep files until removed (the default, unchanged behavior). The setting is versioned (`expectedVersion`, 409 on conflict) and the read includes `eligibleNow`, the number of files the next sweep would remove. An hourly sweep (`orqix.attachments.retention-delay`) and `AttachmentLifecycle.purgeExpired()` soft-remove files of **ended** requests (completed or cancelled) older than the setting: the bytes and any extracted text are deleted, the metadata stays, `purged_at` is set, a system `attachment.purged` audit and an `ATTACHMENT_PURGED` timeline event are written (batch of 200, `FOR UPDATE SKIP LOCKED`, safe on several pods). Files of running requests and quarantined files are never purged; releasing a file makes it eligible on the next sweep. Migration `V56` relaxes the removed-by check so a system purge has no deleting person.

## OCR provider contract

`OcrProvider` (package `integration`): `id()` and `extract(bytes, contentType)`; a deployment replaces it with its own bean. The shipped `local-stub` returns the stored text of plain text and CSV files (truncated at 100,000 characters) and reports every other type, including images and PDFs, as `unsupported`. It performs no recognition. `requestAttachmentOcr` (`run.operate` plus request-data visibility) stores one result per file (a new request replaces it); `getAttachmentOcr` is visible like the file. Quarantined, scan-blocked and removed files are refused; the stored text is deleted on quarantine, removal and purge. Audit `attachment.ocr.requested` records provider and status only.

## Data

Migrations `V55__document_template_registry.sql`, `V56__attachment_lifecycle.sql`, `V57__attachment_ocr_results.sql`.

## Evidence (run locally on 17 October 2026)

- `DocumentLifecycleTest` (6 cases, PostgreSQL through the public API) passes: version immutability and ordering, stale-version 409, identical/invalid/oversized refusals, archive/restore rules, audit contains counts only; template read/edit permission split (viewer reads, requester 403, foreign 404); quarantine blocks download and OCR, initiator cannot quarantine/release/remove, release restores, timeline events written, reason absent from audit; OCR stub extracts text, reports PDF unsupported, initiator reads but cannot start, viewer 403, quarantine deletes the text; retention admin-only, validation, version conflict, `eligibleNow`, purge removes only old unquarantined files of ended requests, quarantined and running-request files stay until released, clearing the setting. `SecurityMatrixTest` (generated from the OpenAPI) covers all eleven new operations for foreign principals (404, no state change); `ArchitectureTest`, `AttachmentsApiTest` and `GeneratedDocumentsTest` still pass.
- Full clean `verify` passes: compiler 454 cases, application 734 cases (730 pass, the same four intentional skips), coverage gate and packaging.
- Frontend: 96 files, 807 tests pass (typecheck, lint, `api:check` clean). New: `FileLifecycle.test.tsx` (3), `DocumentLibrary.test.tsx` (6) and the Studio picker case (1), using the mock API with overridden responses for conflict and validation paths.

## Not covered

- No real OCR: images and scanned PDFs yield `unsupported` until a deployment supplies a provider. No layout, language or confidence handling; no automatic extraction on upload.
- Templates are plain text only (as S29); no rich designer, no uploaded Office templates, no approval workflow for template versions, no per-version "pin" of a workflow to a template, and no usage report of which workflows copied which template.
- Retention applies to request files of ended requests only: not to staged uploads (existing expiry), audit/timeline records or exports; no legal-hold object beyond quarantine; no per-workflow or per-file-type periods; the sweep is hourly and bounded, not a purge guarantee at an exact time. Removal is a soft delete of content, not secure storage erasure of database backups.
- Quarantine is manual; it is not wired to the virus scanner result or to automatic rules. No browser (Edge/axe) or real-Docker stack run for this ticket.
