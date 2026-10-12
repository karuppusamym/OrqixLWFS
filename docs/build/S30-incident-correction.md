# S30: Controlled correction of incident input

Updated 12 October 2026. This is a deliberately conservative recovery command, not arbitrary live-process editing. Combined verification and unresolved findings are recorded in the product build tracker.

## Authoring and use

A designer can opt a scalar request field into recovery by setting `incidentEditable: true`. Studio exposes this under a question's Advanced controls and preserves the flag when fields are reordered, renamed or edited. The compiler rejects the flag on result fields, files and collection items; collection fields do not accept it. Publishing pins the permission in the immutable release. Editing a later draft cannot make an existing run's fields correctable.

On a request needing attention, an operator who can also see its request data sees **Correct incident input** and the pinned editable fields. The operator changes values and supplies a reason. The browser sends only changed fields, a current run version and an idempotency key. It blocks its editor when the loaded request already shows a captured effect or task history. The server performs authoritative safety checks even if the browser has stale or incomplete history.

`POST /workspaces/{workspaceId}/runs/{runId}/correct-input` accepts:

```json
{
  "expectedVersion": 7,
  "reason": "Remove an unsupported control character from the document title",
  "changes": { "title": "Corrected request title" }
}
```

Success returns the run ID, accepted new version, changed-field count and replay flag. An identical authorized replay returns the accepted version and count without changing input or appending another event. Reusing the key for a different body is rejected. A fresh command with a stale version is rejected.

## Safety boundary

All conditions must hold:

- The caller has fresh `run.operate` and request-data visibility in the named workspace.
- The run is `needsAttention` and the expected version matches the locked projection.
- No executable or timer jobs remain for its process; correction cannot run while another step may still capture input.
- **No effect-journal record exists for the run**, even if pending, failed or unfinished. Stored effect request hashes and results are never rewritten.
- No task, successful decision, generated document, prior wait or child/effect completion has observed the input.
- No potentially preceding data-consuming node exists in the pinned graph. This conservative ancestry check also refuses earlier branches or notifications, even when their actual path is uncertain. Incident notices themselves do not block correction.
- Each change is a non-null scalar field explicitly opted in by that pinned release. The merged complete input passes the pinned form validator; unknown fields, wrong types and violated bounds are rejected.

The command locks the run and writes the Flowable process input and Orqix projection in one transaction. Its lifecycle remains `needsAttention`. It does **not** change published definitions, dependency pins, tasks, prior step outputs, remote effects or engine position. It does **not** retry automatically. The operator must review the correction and issue the separate existing Retry command.

A supported example is a **first** document-generation step that rejects an input control character before any file or effect is committed. Correcting an opted-in title permits a later explicit retry to generate the document. A request that has already undergone an approval, branch, decision, wait, child invocation or system call generally requires a new corrected request instead.

## Evidence and privacy

`INPUT_CORRECTED` records the before/after values, reason, actor, count and accepted version in the protected request timeline. The UI shows a corrected-values table to data-authorized readers. Metadata-only readers receive just count/version and a redaction marker; neither old/new values nor the reason is returned. Audit `run.input-corrected` contains **counts and version only**, with no field names, values or reason.

Existing requester/participant visibility still applies; an operator permission by itself does not bypass request-data controls. Authorization is checked again before accepting an idempotent replay.

## Verification

`IncidentCorrectionTest` covers a real PostgreSQL/Flowable first-step document failure, atomic engine/projected correction, stable replay, stale-version rejection, request-data redaction, audit minimization and explicit retry through completion with one attachment. It also exercises non-editable fields, wrong types, completed requests and refusal of an unfinished durable effect capture.

`IncidentCorrectionPanel.test.tsx` covers changed-field-only submission with reason/version/idempotency, no implicit retry, missing-reason and permission guards, captured-effect UI blocking and before/after evidence redaction. Combined backend results are recorded in the build tracker; UI-only tests do not prove engine recovery.

This slice does not implement Camunda-style token modification, in-flight version migration, broad variable editing or undo of externally observed values. Customer recovery procedures and production acceptance remain open.
