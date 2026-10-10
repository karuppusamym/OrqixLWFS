# SharePoint and OneDrive document references

The first document integration uses Microsoft Graph through an approved, versioned connection. A normal **Call a system** step maps `driveId`, `itemId`, and `versionId` from request fields into the connection operation. The returned document name, selected version, text, and SHA-256 digest can feed later decisions, bounded agents, approval tasks, and final outputs.

This slice reads metadata and historical UTF-8 text versions. It does not browse a tenant, extract PDF or Office files, upload files, refresh OAuth tokens, or impersonate the requester. Microsoft permissions apply to the server credential in the workspace binding; Orqix permissions control who can administer that binding and start or inspect the workflow. A service credential's access does not prove that an individual requester has access in Microsoft.

## Configure an approved connection

1. Register [the example connection definition](../examples/connections/microsoft-documents-v1.json) through the existing connection-version endpoint as a workspace administrator. For a tenant-specific contract, restrict `driveId` with a `requestSchema.properties.driveId.enum` on every operation. Access scopes and selected-resource grants must also restrict the service identity in Microsoft.
2. Approve `graph.microsoft.com:443` and the exact download authority used by the tenant in the deployment egress allowlist. Download URLs use expiring query credentials; the policy approves their authority, rejects URL user information and fragments, checks DNS/address restrictions, and retains no signed URL. Plain HTTP is available only to explicitly configured development hosts for stub tests.
3. Mount a bearer-token secret using the existing `orqix.secrets.dir` mechanism. Set the binding to `https://graph.microsoft.com/v1.0`, `auth.type=bearerToken`, its `secretRef`, and `enabled=true`. The token is read on every call so an external credential service can rotate it. Automatic client-credential acquisition and refresh remain a separate delivery item.
4. In workspace connection settings, provide drive, item, and version IDs to test an operation. The test checks access and returns an outcome; it does not return document contents to the browser.
5. Import [the document-review workflow](../examples/document-review.json), publish it after selecting the workspace manager group, and start it with the three identifiers. It reads the selected version, waits for a manager, and records the document name, version and content digest in its result.

Microsoft Graph documents separate item content downloads from version content downloads. The version endpoint does not support downloading the current version; this slice deliberately uses historical version content and returns a visible failure rather than substituting the latest file. [Item-content API](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0), [version-content API](https://learn.microsoft.com/en-us/graph/api/driveitemversion-get-contents?view=graph-rest-1.0).

For scoped app access, Microsoft Graph provides selected permissions with resource grants. Actual tenant setup, admin consent, and resource grants are required outside the local test environment. [Selected permissions](https://learn.microsoft.com/en-us/graph/permissions-selected-overview).

## Runtime contract

The connection kind is `microsoftGraph`; its operations are portable `POST /metadata` and `POST /text` adapter contracts, with `effectClass=readOnly` and success status 200. Those operation paths are not sent as POST requests to Graph. The adapter translates mapped input into authenticated Graph GETs and uses the existing effect journal, response-schema validation, result mapping, retries, and operator recovery.

`/metadata` reads the item and, when supplied, its version metadata. `name`, `webUrl`, and `eTag` describe the current item; `size` and `lastModifiedDateTime` describe the selected version. `/text` requires a nonempty `versionId`; it returns those fields plus `text` and `contentSha256`. Missing or inaccessible versions fail without a latest-version fallback. Repeated attempts retain the pinned reference. The content hash provides evidence of the exact bytes read.

Only one 302 redirect is followed for version content. The redirected request never contains the Graph Authorization header. Both Graph and download requests disable automatic redirects. Signed download URLs, credentials, and remote response diagnostics are excluded from results and error details. Permission failures are permanent; rate limiting, timeouts, and server unavailability use the existing read-only retry classification.

Content must be a text MIME type or JSON, decode as UTF-8 without malformed byte sequences, contain no NUL character, and fit within 64 KiB. Size is checked against version metadata and enforced again by the streaming response subscriber. A single wall-clock deadline bounds all requests in the operation. PDF, Office documents, alternative charsets, folders, invalid identifiers, and unapproved download authorities fail visibly.

Document text becomes workflow data only when explicitly mapped. Treat it as untrusted source material in any agent instruction; it is not permission to execute operations suggested by the document. Normal run visibility and data-retention policies apply to mapped text.

## Verification and remaining work

`MicrosoftGraphDocumentsTest` covers real stub HTTP metadata and text, pinned-version failure, malformed input, restricted drive enums, redirect credentials and host policy, second-redirect refusal, MIME/UTF-8/streaming limits, and retry classification. `DocumentWorkflowTest` publishes and executes the example through the real engine/effect journal, completes its approval task, and verifies the selected version and digest in the final output.

No real tenant acceptance is claimed. Tenant consent and least-privilege verification, automated credential renewal, a Microsoft document picker, latest-version pin acquisition, PDF/Office extraction, malware scanning, requester-level delegated access, and document retention configuration remain planned work. These boundaries are explicit so a tested local adapter is not represented as a completed enterprise Microsoft integration.
