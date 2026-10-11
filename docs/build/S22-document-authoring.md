# S22: approved document reference authoring

This adds a Studio authoring helper for existing Microsoft Graph document operations. It configures an exact approved reference using the existing scalar request-form and HTTP mapping contracts. It does not browse a tenant, introduce delegated Microsoft access, or provision OAuth credentials.

## Author experience

In Studio, select a **Call a system** step, an approved Microsoft Graph connection, and a historical **Read text** / **Extract document** operation or **Extract snapshot**. The inspector shows **Document reference authoring**. Select **Configure document reference**, choose **Approved document drive**, and provide **Known document item ID**. A nonempty drive enum on the selected operation is required.

For historical reads, supply **Exact historical version ID**. Connection administrators can instead use **Load document versions** and explicitly **Choose historical version**. Listing begins with no selection; an unavailable version fails at execution with no latest fallback.

For snapshot extraction, supply **Retained snapshot ID**. Connection administrators can explicitly **Capture current document as snapshot** when the pinned connection has an approved, testable `/pinLatest` operation. Capture displays the immutable SHA-256 digest, source eTag and expiry before applying the reference. A captured snapshot remains subject to normal quota/retention; editing a workflow does not keep it alive indefinitely. Canceling leaves captured bytes to expire normally. Expiry never causes an automatic recapture.

**Apply approved reference to request form** creates required single-choice string questions for the drive, item and selected pin, maps them into this step, and keeps supported safe provenance outputs. Requesters then choose the approved values in the normal request form. Server form validation rejects substituted IDs/version values, and normal run/workflow authorization still applies. This is a fixed approved reference for all requests using that published version, rather than requester file browsing.

Compatible existing mappings to exact single-choice questions are reused. A different existing source is not overwritten: the author must explicitly clear that target mapping first. Unrelated questions and result mappings are preserved; generated question names avoid collisions. Changes are atomic and obey the existing 30-question form bound. Cancellation discards the helper's pending fields, and late connector results after cancellation/document changes do not restore stale pins. The workflow remains editable through the ordinary request editor and field mapping controls.

## Permissions and contracts

Reading registered connection definitions still requires `connection.read`. Historical lookup and snapshot capture use the existing administrator-only test endpoint and require `connection.manage`; the helper hides these actions from other authors. Authors without connection-management permission can configure a known approved reference supplied by an administrator without making Graph calls. Runtime downloads continue using the workspace's server bearer secret, pinned connection version, schema, egress and retention policies. This does not prove that a requester has Microsoft access to the source file.

No API, workflow schema, permission or database changes are introduced. The UI uses the workflow's pinned connection version, never silently switching to the latest definition. Reference authoring is available only for `/text`, `/extract`, and `/extractSnapshot`; URL resolution and current capture workflows remain independently available through mapped operations.

## Validation

`documentReference.test.ts` verifies schema validity, exact questions and mappings, the committed compiler fixture, idempotent compatible reuse, atomic conflict refusal, question-name collisions, form bounds, and snapshot/invalid-pin handling. `DocumentReferenceAuthoring.test.tsx` verifies the applied request form, administrator action gating, explicit historical selection, source-change cleanup, snapshot provenance, mismatched-response refusal, cancellation with pending capture, and read-only behavior. The earlier settings picker tests verify bounded complete historical listings and no fallback.

[The authored example](../../examples/document-authoring-review.json) is generated from the real helper by `node frontend/scripts/document-authoring-fixture.mjs` (Node 24). `DocumentAuthoringTest` compiles that fixture against connection version 3 and checks server form rejection of changed pins. Backend checks are run by the coordinated build; no real Microsoft tenant acceptance is claimed.

Customer setup remains: an approved Microsoft application/tenant identity, consent and resource grants, approved drive/link policies, permitted download hosts, and a rotating mounted bearer token. Tenant file browsing, automatic OAuth acquisition/refresh, and requester-level delegated access require separate contracts and customer configuration.
