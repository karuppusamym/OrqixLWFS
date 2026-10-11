# S25: request files and case stages

This completes the existing attachment and stage implementation with bounded lifecycle handling and Studio authoring. It retains the existing API/schema/database changes already present in the workspace. Source comments referring to the earlier S17 attachment/stage proposal are historical; S17 now documents task collaboration.

## Request files

Studio request questions offer the file type (`format: attachment`), accepted file types and a per-file size limit. Choosing a file uploads it before the request starts. The form value is an opaque UUID, not a local path or file content. Review waits until uploads finish. A failed or blocked scan cannot become a request value. Late results after leaving the form are ignored and their staged uploads are discarded where possible; abandoned staging also expires automatically.

At start, the server checks that every file belongs to this requester and workspace, is still staged and unexpired, has not been blocked by scanning, matches its question's allowed types and per-file size, and is not reused in two questions. The combined request also obeys the configured file-count and total-byte caps. These checks and attachment transfer happen in the run-start transaction; failure leaves the request uncreated and the uploads staged. Collections of attachment IDs and attachment outputs are unsupported and rejected by the compiler.

The default limits are 10 MiB per file, 25 files and 50 MiB per run, ten staged files per uploader/workspace, 30 uploads per minute per uploader, and a 24-hour staged TTL. Configured limits are validated at startup. Upload admission is serialized by uploader across pods; run quota admission is serialized by run. The run's active state is checked under its projection row lock before inserting a newly added file. Per-minute admission history survives staged discard until TTL cleanup. Successful idempotent replay bypasses new-upload admission checks.

Run files follow request-data visibility: initiators, task participants and permitted readers/operators see them. Adding requires participation/operation and an active request; removal follows initiator/operator policy. Metadata and bytes are stored separately in PostgreSQL. Removal deletes the bytes and retains audited metadata; staged discard removes bytes immediately and retains private admission metadata only until cleanup. Downloads are forced attachments with octet-stream, `nosniff`, sandbox CSP and no-store headers. Filenames are display labels, never filesystem paths. Audit records contain allowlisted metadata, not file content or names; request timelines are subject to normal data visibility.

The server checks extension, declared type and content signatures together for PDF, PNG, JPEG, UTF-8 text/CSV and supported OOXML Office files. OOXML classification reads bounded central-directory names without extracting archives and rejects macros/path tricks. Signature/type checking is not malware scanning and does not reconstruct document content.

## Scanner policy

The default scanner is disabled and explicitly returns `notScanned`; under the existing policy those files are downloadable. The form and run-file UI expose scan status. This mode must not be described as virus protection. Enabling the scanner does not retroactively scan existing uploads. Expired staging is logically inaccessible immediately, while byte cleanup runs in bounded batches every 15 minutes; attached files have no automatic retention purge.

When `orqix.attachments.clamav.enabled=true`, the ClamAV adapter sends bounded INSTREAM chunks, requires a NUL-terminated bounded response, and accepts only the expected clean/infected protocol outcomes. Unreachable scanners, errors, malformed/oversized responses, missing framing and timeouts return `error`, never `clean`. Both `infected` and `error` block download and request-form attachment acceptance. A deadline closes the socket to interrupt blocked reads, writes and connects; configured host resolution remains dependent on the deployment's DNS resolver. Enabling/configuring a real scanner and validating its deployment is customer infrastructure work; local scripted TCP tests do not claim a live production scanner was accepted.

## Case stages

In Studio, select the request-form/start step and open **Case stages**. **Add a case stage**, give it a **Stage name**, select its steps, and optionally set **Stage time limit** (for example `PT8H` or `P5D`). Reorder stages for progress presentation or remove them. A step belongs to at most one stage, up to 20 stages are supported, and each stage must have a step before publishing. SLA durations range from one minute to 90 days. Rename/delete of workflow steps updates stage membership; removing the last step leaves an explicit empty-stage publishing issue rather than silently removing the stage.

Stages are presentation and monitoring groups over the pinned workflow release, not new execution routes. They add no behavior to generated BPMN. Runtime projection changes record entry/completion; parallel branches may keep several stages active. The run/task views show progress and due/overdue state. The scheduled monitor records an overdue event and in-app notices once per visit/cycle using transactional row locks with `SKIP LOCKED`; reads can show overdue before the next monitor tick. Cancellation closes stages without sending new overdue notices. An SLA does not automatically reject, cancel or reroute work.

## Verification

Existing `AttachmentsApiTest`, `CaseStagesTest` and compiler `StagesAndFilesTest` cover upload/type/header/privacy/idempotency/expiry/scan behavior, sequential and parallel stages, SLA notices and cancellation, stage/compiler bounds and file-form validation. Added attachment regressions cover aggregate form-start byte limits, concurrent staging admission and discard history. `ClamAvScannerTest` uses a local scripted TCP server to check clean/infected framing, missing/malformed/oversized responses, unreachable service and blocked-write deadlines.

Frontend regressions cover upload lifecycle/scan errors/stale completion, waiting to review optional-file forms, stage editing/exclusive membership/SLA feedback/rename-delete preservation/read-only behavior, and pinned connector testability. Coordinated backend and browser checks are reported by the active build tracker; do not infer passing results from the presence of tests alone.
