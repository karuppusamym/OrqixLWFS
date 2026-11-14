# S140 Lookup fields, guest response forms and new field types

6 November 2026. Wave C tickets of [doc 29 §9.4](../29-camunda-gap-implementation-plan.md#94-forms): **CG-F01**
(time and date-time with zone, checklist and tag list), **CG-F02** (lookup fields) and **CG-F05** (guest response
forms). Flyway **V330**. The compiler version is **not** bumped: a workflow that uses none of the new keywords
compiles to the same BPMN, and no golden file moved. D-CG7 is taken as recommended: an emailed guest link goes only
to an address whose domain is in the workspace's approved list, checked when it is sent.

## 1. Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Shape of a date-time value | RFC 3339 with an explicit offset (`2026-11-06T14:30:00+01:00`, seconds optional, no fraction). A field may name an IANA `timeZone`, used only to enter and show the value; the stored value always carries its offset. Bounds (`formatMinimum`/`formatMaximum`) compare instants. |
| D2 | Shape of a time value | `HH:MM` or `HH:MM:SS`, 24-hour, no zone. Bounds are inclusive on the same day. |
| D3 | Checklist and tag list | Existing scalar lists (`type: array`, scalar `items`) gain `uniqueItems: true`. A checklist is a list of text with `items.enum` and widget `checklist`; a tag list is free text items with widget `tags`. No new JSON type. |
| D4 | Times in conditions and decisions | Refused with `WF_OPERATOR_TYPE`, like dates today: ordering times needs a zone rule that belongs to the expression work (CG-X01). They map to text targets and to time targets of the same kind. List counts (`/input/<list>/count`) work for checklists and tags as for every list. |
| D5 | Where a lookup lives | A `lookup` object on a text question of the **request form only** (not result forms, rows, list items or step forms). It names a connection listed in `dependencies.connections` (its pinned version is used), a `readOnly` operation of a **1.0** HTTP definition, the `searchInput` that receives the typed text, an optional `valueInput` for the submit check (default `searchInput`), a row projection (`rows`, e.g. `/result/[*]`) and text `valueColumn`, `labelColumn`, optional `detailColumn`, and `minSearchLength` (0–5, default 2). The question may not have `enum`, `pattern` or `format`, cannot be incident-editable or approver-correctable. |
| D6 | Whose rights a lookup uses | The search API requires the caller to be allowed to start the workflow (the same check as `startRun`); the call itself uses the workspace's binding, egress policy, credential, circuit breaker and request limit exactly like a workflow call (S131). There is no per-user credential delegation for lookups in this slice. |
| D7 | No payloads at rest | Nothing of a lookup is written to the database, the effect journal, the audit log or the logs. Answers are cached **in memory** per pod: searches for `orqix.lookups.cache-ttl` (60 s) per release, question and lower-cased text; values offered by a search count as checked for `orqix.lookups.verified-ttl` (120 s). Both caches are bounded (5,000 entries, LRU). Only the connection health counters (status, duration) are recorded. |
| D8 | Server-side re-validation | `startRun` (and the assistant's `checkStart`) checks every visible lookup answer: a value offered in the verified window is accepted; otherwise the operation is called with `valueInput` = the value and must return a row whose value column equals it **exactly**. Unknown values fail with field error code `lookup`. While the system cannot be asked (open circuit, request limit, unavailable) the start is refused (429 or 503) and nothing is accepted unchecked. The check runs once **before** the workflow row is locked (so a slow system never holds the lock) and again after it from the cache. Child requests started by a workflow and incident corrections do not re-run lookups (no person typed the value; documented limitation). |
| D9 | Rate limits | Per person and workflow: 60 searches a minute on each pod (`orqix.lookups.requests-per-minute`); a remote call also takes a slot of the binding's `maxRequestsPerMinute` (S131). A text that the operation's input schema refuses (pattern, length) is not sent and gives an empty list. |
| D10 | Guest link model | The S98 model: `ogf_` + 43 base64url characters (256 bits), stored only as SHA-256, shown once. One `guest_response` row per (request, wait step), created when the request reaches a "Wait for a message" step with `config.guestResponse`. Issuing again replaces the link; revoking stops it and deletes unsent files; the wait goes on. |
| D11 | Where the token travels | The link is `<origin>/guest/<workspaceId>#<token>`: the token is in the URL **fragment**, which browsers never send, so it stays out of access logs. The page moves it into memory (and the tab's session storage), removes it from the address bar and sends it only in `X-Orqix-Guest-Token`. A token of another workspace, unknown, replaced, revoked or purged: 404, all alike. |
| D12 | What the guest sees | Only the step's `title`, plain-text `instructions`, the declared payload fields (string, number, integer, boolean, date) and the file rules; never the request's data. |
| D13 | Answer and resume | `submit` locks the link, checks state, suspension, size and shape, attaches the chosen files to the request (`run_attachment`, uploaded by the fixed guest subject `00000000-0000-4000-8000-00000000f140`), then delivers the answer through **`MessageDelivery.deliver`** (the message path, the same as S98). Invalid answers: 422 with `/result/...` pointers and the wait stays open. One answer per link: the same `Idempotency-Key` and body replays (`Idempotent-Replayed: true`); anything else is 409 `GUEST_RESPONSE_SUBMITTED`. A closed wait or an expired link: 409 `GUEST_RESPONSE_CLOSED`. A suspended request: 409 `RUN_SUSPENDED`. |
| D14 | Files | `attachments: {maxFiles 1–5, required?, acceptTypes?, maxFileBytes?}`. Uploads are checked by name, declared type and bytes (S17 `AttachmentTypes`), scanned, at most 10 a minute per link, and kept in `guest_response_file` until the answer is sent. Files of revoked, expired or closed links are deleted by the sweeper. |
| D15 | Emailing the link (D-CG7) | `recipientFrom` names a request text field (`/input/...` only). When the wait starts the link is `pending`; `GuestLinkSender` (every 15 s, `FOR UPDATE SKIP LOCKED`) reads the address from the stored request, never stores it, and sends only when its domain is in `guest_domain` **at that moment**. Otherwise the send state is `refused` (`domainNotApproved` or `noAddress`), audited and shown on the timeline; an administrator can approve the domain and an operator can email again, or the operator issues the link and shares it. Without SMTP (`orqix.mail.enabled=false`) the send state is `failed`. Up to 5 attempts on SMTP errors. Plain-text email via the operator-configured SMTP (`StageMailTransport`). |
| D16 | Rate limits and caps | 60 calls a minute per link token on each pod (resolved tokens only); 10,000 live links per workspace (the S98 cap); at most 50 approved domains. |
| D17 | Audit | `guest.link-issued`, `guest.link-revoked`, `guest.link-send-requested`, `guest.link-sent` (domain), `guest.link-refused` (reason), `guest.link-viewed` (once), `guest.file-added`, `guest.submitted` (field and file counts), `guest.link-expired` (once), `guest.links-purged`, `guest.domains.updated` (count). Ids, counts, domains and reasons only; never tokens, addresses, answers or file names. |
| D18 | Permissions | Search: `workflow.start` (the requester). List, issue, email, revoke links: `run.operate` (fresh for writes). Approved domains: read `connection.read`, replace `connection.manage` (fresh). Guest routes: possession of the token (`x-orqix-permission: guestToken`) on the stateless inbound chain without session or CSRF, like the callback routes. No new permission. |
| D19 | Retention | Closed links (answered, cancelled or request ended) are removed 30 days after closing with their files (`guest.links-purged`). |

## 2. Contract

`contracts/workflow-v0.schema.json`:

- `scalarFormField.format` gains `time` and `date-time`; new `timeZone` (date-time only), `lookup` (request text
  questions, see D5); `formatMinimum`/`formatMaximum` now take `$defs/formatBound` (a date, time or date-time
  literal; the compiler checks it matches the field). `acceptTypes`/`maxFileBytes` remain refused on date, time and
  date-time fields.
- `arrayFormField.uniqueItems` (`const: true`); `inputUi.widgets` gains `checklist` and `tags`.
- `waitForMessageNode.config.guestResponse`: `linkValidFor` (PT1H..P30D), `title`, `instructions`, `recipientFrom`,
  `attachments`.

Compiler codes: `WF_FORM_INVALID` (bounds not written like the field, unknown zone, zone on a non date-time field,
`uniqueItems` on rows, lookups outside the request form), `WF_LOOKUP_INVALID` (not plain text, not 1.0 HTTP, not
readOnly, async or agent, unknown or non-text inputs, extra required inputs, rows not a row projection, unknown
columns, incident-editable or approver-correctable), `WF_CONNECTION_UNKNOWN` (connection not declared),
`WF_UI_WIDGET_MISMATCH` (warning), `WF_GUEST_LINK_VALIDITY`, `WF_GUEST_FORM_INVALID` (markup or `${`/`#{` in title or
instructions; no questions and no files), `WF_GUEST_RECIPIENT` (not a request text field without choices).
`WF_OPERATOR_TYPE` for times in conditions. A connection used only by a lookup counts as used
(`WF_DEPENDENCY_UNUSED` is not raised; `WF_CONNECTION_UNBOUND` applies).

BPMN: `orqix:message` gains `guestResponseBase64` (canonical JSON) **only when set**; `BpmnReader` reads it back, so
BPMN source editing keeps the option. Form schemas round-trip as before.

`contracts/openapi.yaml`, one paths block tagged `FormsLookupGuest` (before the Waits block) and one schemas block
(before `CallbackWait`): `searchLookupOptions`, `listGuestLinks`, `issueGuestLink`, `revokeGuestLink`,
`sendGuestLink`, `listGuestDomains`, `replaceGuestDomains`, `getGuestForm`, `uploadGuestFile`,
`submitGuestResponse`. Problem codes `GUEST_RESPONSE_CLOSED`, `GUEST_RESPONSE_SUBMITTED`.

## 3. Implementation

| Area | Files |
|---|---|
| Compiler | `FieldKind.TIME`, `DATETIME` (+ `temporal()`), every exhaustive switch; `FormSchema.Field.json` (the field's JSON, old constructors kept); `Node.WaitForMessage.guestResponse` (old constructors kept); `DefaultFormValidator` (time, date-time, `uniqueItems`); new `FormFieldRules`; `TypeRules` (date bounds only for dates); `Conditions`, `FieldTypes`, `DmnGenerator`, `BindingRules`, `StepForms`; simulator samples; BPMN generator and reader |
| Migration | `V330__guest_response_forms.sql`: `guest_domain`, `guest_response`, `guest_response_file`, the guest subject row |
| Lookups | `integration.FormLookupCalls` (binding, egress, credential, `CallGuard`, executor; no journal), `runtime.internal.FormLookups` (access, caches, throttle, `verify`), `RunService` (pre-lock and post-lock checks, `checkStart`) |
| Guest forms | `GuestResponseStore`, `GuestResponseService`, `GuestLinkSender` (email, expiry audit, file cleanup, purge), `MessageWaits` hook, `RunAttachments.addGuestFile`, `SecurityConfiguration` (stateless guest routes), `SpaController` (`/guest/**`), `GuestResponseController`, `LookupDtos`, `GuestDtos`, `AuditWriter` whitelist, `ProblemCode` |
| Frontend | `lib/form/schema.ts` (formats, zone, lookup, `uniqueItems`, widgets), `validate.ts` and `values.ts` (parity with the server), `zoned.ts` (local entry ↔ offset), `display.ts`; `FormRenderer` (time, date-time, checklist, tag list, lookup combobox `LookupField`); start page passes the lookup context; Studio question kinds *Time of day*, *Date and time*, *Checklist*, *Tag list*, *Lookup* (`ops.ts`, `FieldListEditor`) and the *Guest response form* section of the message inspector; run detail *Guest responses* panel; guest page `/guest/:workspaceId`; timeline texts; API clients `api/guest.ts`; mock double `mocks/guestForms.ts` |
| Example | `examples/connections/servicenow-users.connection.json` (user search by email prefix for a lookup) |
| Contract check | `guestToken` qualifier; `searchLookupOptions` is a read-only POST (no audit, like `validateDraft`) |

## 4. Tests

| Test | Covers |
|---|---|
| `LookupGuestFieldTypesTest` (compiler, 7) | time and date-time validation and instant bounds; unique lists; publishing the new types and widgets, wrong bounds, zones, rows with `uniqueItems`, times in conditions; lookup rules (inputs, columns, rows, effect class, undeclared connection, choices); guest rules and the BPMN round trip; a wait without the option is unchanged |
| `LookupFieldsTest` (PostgreSQL, Flowable, ServiceNow-shaped WireMock stub, 3) | minimum length (no call), search, cache, refused input not sent, offered value accepted without a call, forged value refused with code `lookup`, unsearched real value checked and accepted, nothing journaled or audited; 403/404/422; the binding request limit (429 on search and on submit, nothing started), an unavailable system (503), an empty optional lookup |
| `GuestResponseFormsTest` (PostgreSQL, Flowable, mocked SMTP, 3) | issue once and hash at rest, cross-workspace 404, guest view without request data, file type refusal and upload, invalid answers keep the wait open, submit with file, replay and 409, the wait resumed through the message path with `WAIT_ENDED`, `ATTACHMENT_ADDED` and `GUEST_RESPONSE_SUBMITTED`, audit without token or answers; replace, revoke, expiry (409 `GUEST_RESPONSE_CLOSED`, expiry audited once); email refused for an unapproved domain, domain list validation and permissions, email again after approval, link from the email works, audit without the address |
| `SecurityMatrixTest` | every new operation for foreign principals (404) and no state change |
| `s140.test.ts`, `FormRenderer.s140.test.tsx`, `GuestResponsePage.test.tsx` (frontend) | validator parity for the new keywords, defensive parsing, zone conversion across daylight saving, Studio round trip without losing keywords; checklist, tag list, time, date-time and lookup controls; the guest page reads and hides the token, validates, submits once, and explains unknown or incomplete links |

Totals of the final runs are in the hand-over (§6).

## 5. Not in this slice

Comparing times in conditions and decision tables (CG-X01), lookups in rows, list items, step forms and case data,
per-user delegated credentials for lookups, re-checking lookups of child requests and incident corrections, guest
forms with their own form schema (they use the step's declared payload fields), guest files larger than the platform
limit or more than 5, reminders for unanswered guest links (CG-W06, waits session), signature capture (CG-F07) and form
translations (CG-F06). The guest page has no styling of its own beyond the app's base styles. Approved guest domains
are managed through the API (`listGuestDomains`, `replaceGuestDomains`); there is no Settings screen for them yet.

## 6. Merge notes

- **Shared enums and constraints**: `ProblemCode` gains `GUEST_RESPONSE_CLOSED`, `GUEST_RESPONSE_SUBMITTED` (after
  `CALLBACK_NOT_WAITING`, also in the OpenAPI `Problem.code` enum and `frontend/src/lib/problemMessages.ts`). No CHECK
  constraint of an existing table changed; V330 only creates tables and inserts the guest subject. `AuditWriter`
  whitelist +11 `guest.*` actions.
- **Compiler model**: `FieldKind` gains `TIME`, `DATETIME` (sessions that switch over `FieldKind` must add them);
  `Node.WaitForMessage` gains a 10th component `guestResponse` (9-argument constructor kept) — if the waits session
  also adds a component, merge both and keep both short constructors. `FormSchema.Field` gains an 18th component
  `json`. No compiler `VERSION` change; no golden file moved.
- **Shared files touched**: `RunService` (constructor + lookups), `MessageWaits` (constructor + hook),
  `RunAttachments.addGuestFile`, `SecurityConfiguration` (inbound chain matcher), `SpaController`,
  `SecurityMatrixTest` (3 sample bodies, `uploadGuestFile` multipart, `searchLookupOptions` audit exemption),
  `tools/contract-check` (`guestToken`, audit exemption), Studio `ops.ts` field kinds, `FormRenderer` props, the
  message inspector.
- OpenAPI blocks as in §2; regenerate `frontend/src/api/schema.d.ts` after merging (`npm run api:generate`).
- Pre-existing on the base: `contract_check.py` reports `participant` as an unknown permission qualifier for
  `listRunAiDecisions` and `recordAiDecisionOutcome` (Wave B); not touched here.
