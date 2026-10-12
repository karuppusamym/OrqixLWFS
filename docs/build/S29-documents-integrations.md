# S29: Generated documents and approved Microsoft folder browsing

Implemented scope, 12 October 2026. Verification evidence is recorded by the product build tracker after the combined checks.

## Generate a document

Studio exposes a **Generate a document** step, PDF or Word format, a simple file name, a plain-text template and a field picker for each `{{placeholder}}`. Templates are retained in the immutable published workflow; execution does not fetch a mutable external template. Placeholders name explicitly mapped fields. The compiler requires exact placeholder/mapping agreement, scalar values, required input fields, and graph availability of earlier step results. Expressions, scripts, uploaded Office templates, macros, recursive evaluation and network resources are unsupported and rejected.

The async engine delegate renders the template and stores the result using existing request attachments. Downloads retain request-data authorization, safe download headers, attachment type verification, quotas and configured virus scanning. The generated document remains available after the run completes. A unique `(run_id, generated_node_id)` constraint and a transaction-level run attachment lock prevent duplicate files on retries. Removed generated files retain their deduplication identity; retrying cannot recreate a removed artifact. The canonical workflow graph is acyclic; repeated child workflows get separate run identities.

Limits: template 16,000 characters; rendered text 32,000; individual replacement 8,000; 50 mapped fields; file name 100 characters before extension; PDF at most 1,000 wrapped lines, 45 per page. Output also obeys the configured request attachment size/count/aggregate limits. DOCX retains Unicode and escapes XML markup. PDF uses Helvetica's standard font character set and rejects unsupported glyphs with a visible incident; designers can choose DOCX for Unicode. PDF is a readable plain-text document, without rich layout, images or signatures. DOCX output contains only content types, the internal document relationship and Word text paragraphs. No external relationships or macros are generated. DOCX ZIP output is deterministic; PDF content is stable but PDFBox can generate document identifiers, so PDF byte equality is not promised.

Successful execution writes `ATTACHMENT_ADDED`, metadata-only `attachment.added` audit, and `DOCUMENT_GENERATED` with attachment ID and format. The audit does not contain the template, file name or substituted request data. Generation failure stops the job with an incident. Configured scanner failure blocks progress; scanner-disabled behavior remains explicitly `notScanned`, matching all other attachments. Simulation walks the step and explains its mapped-field count and format, without creating a file. It does not prove scanner acceptance or PDF glyph support.

## Microsoft folder browsing

`microsoft-documents-v4.json` preserves v3 operations and adds `/browseFolder`. Tenant administrators must replace the example approved-drive/folder identifiers with actual grants. Both drive and folder must be explicit nonempty enums (at most 100 identifiers) in the immutable approved operation request schema. The backend checks both grants before any Graph call.

The operation lists **direct files only** from `/drives/{drive}/items/{folder}/children`; it does not recursively descend, search the tenant or follow folders. Listing is limited to 100 entries and 10 pages. All paging must retain the exact approved origin and folder path, and repeated cursors, duplicate IDs, oversized/incomplete data or out-of-scope file provenance fail visibly. Caller-provided cursors are unsupported. Returned data includes item ID, name, size and content type, never preauthenticated download URLs, credentials or arbitrary Graph fields.

The administrator connection test panel provides approved folder selection and an explicit file chooser. Selection clears the earlier version and snapshot pins; historical version selection is a separate explicit action. The browser uses the existing `connection.test` permission and existing workspace credential/egress boundary. Requester browsing uses separate `/document-sources` endpoints, never the administrator test API. A requester must have an explicit `document.browse` workspace grant **and** match the source's subject/IdP group audience. No standard role includes this permission. Every browse uses freshly computed permissions and current source configuration, so disabled sources and revoked permissions take effect without the ordinary session policy cache. A workspace administrator manages at most 50 version-pinned sources through Settings > Approved documents, specifying the exact approved drive/folder, browse/version-list operation pins, audience and availability. Optimistic `expectedRevision` prevents lost updates; changes write metadata-only `document.source.updated` audit.

Public descriptors contain source ID, display name, connection key/version and approved drive/folder IDs; credentials, binding URLs and audiences are absent. Before listing versions, the server re-lists the approved folder and confirms the requested item is still a direct file. A guessed item outside the source receives 404. Request forms expose an optional chooser only for `document.browse` holders: choose source, file and historical version explicitly, then map drive/item/version to three distinct compatible scalar text fields. Enum and length constraints are respected, and file/date fields are excluded. No reference is selected or submitted automatically. The normal review and submission validation still applies. The workflow must contain an approved pinned document-read step to use the reference.

The mock environment supports explicit document-source fixtures and workspace/subject grants; it does not simulate a successful Microsoft tenant connection or invent files. Source audience mismatch and missing permissions are refused. Real Microsoft consent, tenant grants, credential provisioning and tenant acceptance remain deployment prerequisites.

## Checks

- `GenerateDocumentTest`: compiler accepts safe mapping and emits bounded async delegate configuration; invalid expressions and missing mappings are blockers.
- `DocumentGenerationTest`: PDF content can be read back, DOCX escapes markup and retains Unicode, invalid/missing/oversized fields and file paths are rejected.
- `GeneratedDocumentsTest`: published workflows generate and download PDF/DOCX with existing data authorization; repeated node artifact storage does not add files.
- `MicrosoftGraphDocumentsTest`: approved direct-folder listing and cross-folder paging refusal through a real stub HTTP boundary.
- `GenerateDocumentInspector.test.tsx`: edits format and mapped request fields in Studio.
- `DocumentFolderPicker.test.tsx`: exact grant operation, explicit file selection, unapproved drive refusal and incomplete listing rejection.
- `DocumentSourcesApiTest`: real API, workspace permission plus resource audience, safe descriptor fields, direct-file version selection, guessed-item refusal, immediate disable/revocation and cross-workspace checks.
- `RequestDocumentPicker.test.tsx`: requester source/file/version selection, explicit three-field mapping and resource-audience exclusion.

This closes bounded document generation, administrator folder discovery and explicitly granted requester browsing. It does not claim rich document design, e-signatures, mail delivery, unrestricted tenant search or Microsoft tenant acceptance.
