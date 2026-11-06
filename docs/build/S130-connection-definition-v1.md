# S130: Connection definition v1 and lookup reconciliation

5 November 2026. Wave A of the Camunda gap plan ([doc 29](../29-camunda-gap-implementation-plan.md) §6.2, §6.4, §6.5, §6.14, §11): **CG-C01** (a declarative connection contract rich enough for ServiceNow-shaped APIs) and **CG-C02** (`idempotentByLookup` and `idempotentNatural`). Migration **V230**. Definitions with `schemaVersion "0.1"` are read, validated, compiled and executed exactly as before.

## 1. Decisions taken

1. **A sibling schema, not an edit of v0.** `contracts/connection-definition-v1.schema.json` (`schemaVersion "1.0"`, kind `http` only). `connection-definition-v0.schema.json` is unchanged; registration, the compiler and the contract checker pick the schema by `schemaVersion`. Agent and Microsoft Graph connections stay on 0.1.
2. **Inputs stay flat; the wire shape is declared.** A workflow still maps flat inputs (`inputMapping` keys are field keys, the workflow schema is unchanged). The inputs of a v1 operation are the `requestSchema` fields plus every path parameter that is not a `const`. Path, query, headers and the body reference inputs by name, so remote names such as `short_description` never become workflow keys. Without a body template, inputs no other part uses form the flat JSON body: a v0-shaped operation is a valid v1 operation with the same request.
3. **Shape rules in JSON Schema, cross-field rules in code.** Rules the schema cannot express are registration rules `CONN_V1_*` (`com.orqix.compiler.ConnectionContracts.check`), run by registration (field errors with the code as keyword and a pointer under `/definition`) and again by the compiler when it pins a definition. The executor reads definitions through the same class.
4. **Error mapping makes known rejections, not new routes.** A mapped 4xx becomes a permanent, known rejection whose error code is the business code (for example `INCIDENT_NOT_FOUND`). It takes the step's `failed` route or `raiseErrorOnFailure` exactly like an unmapped 4xx (S32, S66), and the new failure fact `failureCode` carries the code (empty when unmapped); `failureMessage` is the remote message at `bodyPointer` (200 characters, never logged) or the fixed `message`. 401, 403, 408 and 429 keep their platform meaning; 5xx cannot be mapped.
5. **Pagination is bounded and journaled per page.** Page 1 is the step's own effect; pages 2..n are separate `readOnly` journal rows with node id `<step>#a<attempt>p<page>`, so a retry reads a fresh, consistent set and the operator sees each read. Lists projected with `/[*]` collect items from every page; other results come from page 1. Hard caps: `maxPages` ≤ 10, `maxItems` ≤ 100; cut lists are reported on `EFFECT_SUCCEEDED` (`truncated`, `pages`).
6. **Reconciliation decides before anything is recorded as unknown.** For `idempotentByLookup` an uncertain outcome (timeout, reset, 408, 5xx, or a crashed attempt found with an expired lease) runs the lookup: found records success with the found record (`effect_journal.resolution = {kind: lookup, outcome: found, …}`), not found allows a resend with the same key (retryable), a failed lookup leaves `unknown` exactly as before. A 2xx with an unusable body stays `unknown` (the system answered; a lookup is no proof).
7. **`async` is declared and validated only.** Execution belongs to CG-W02. `rateLimit` is accepted as a declaration for the binding rate limiter of CG-W04; this slice does not enforce it. No retry, back-off or circuit-breaker code is added here.
8. **Compiler version not bumped.** v0 workflows compile to the same BPMN; the single D-CG1 bump is done when the Wave A branches are merged.

## 2. What v1 adds

| Addition | Contract | Runtime rule |
|---|---|---|
| Path templates | `"path": "/api/now/table/incident/{sysId}"`, `pathParams: {sysId: {type, pattern \| enum \| const}}` | Values must match pattern/enum/maxLength, are percent-encoded (RFC 3986) and can never be empty, `.`/`..`, contain `/`, `\` or control characters; a text parameter needs a pattern or enum (`CONN_V1_PATH_PARAMS`); a `const` cannot be mapped |
| Query | `query: {name: {const} \| {input} \| {template, encoder}}` | Encoders are reviewed code: `rfc3986`, `servicenowEncodedQuery` (refuses `^`, line breaks and control characters in a value; its inputs need a pattern or enum, `CONN_V1_TEMPLATE`). Absent optional inputs leave the parameter out |
| Headers | `headers: {Name: {const} \| {input}}`, `responseHeaders: {Name: scalar}` | `Authorization`, `Cookie`, `Host`, `Content-*`, hop-by-hop, `Proxy-*`, `Sec-*`, `X-Orqix-*`, correlation and the idempotency header are refused; `Set-Cookie` and auth challenges cannot be read (`CONN_V1_HEADER_FORBIDDEN`) |
| DELETE | `method: DELETE` | Cannot be `readOnly` (`CONN_V1_EFFECT_CLASS`); sends a JSON body only if inputs are left for it |
| Bodies | `body` template (`{input}`, `{const}`, `{object}`, `{array}`), `contentType` `json` \| `form` \| `multipart` \| `binary`, `binaryInput` | Depth ≤ 5, ≤ 200 leaves; form and multipart are flat; files only from inputs with `format: attachment`, read through `RequestFiles` (runtime `RunRequestFiles`): a file of this very request, not removed or quarantined, scan not blocking, ≤ 10 MiB. Every declared field must be sent (`CONN_V1_INPUT_UNUSED`) |
| Results | Pointers with array indexes (`/result/0/sys_id`), `<list>/[*]` rows of the item's scalar columns, `<list>/[*]/<column>` single values, `/@headers/<Name>` | ≤ 100 items per list; response depth ≤ 5 (`CONN_V1_RESPONSE_DEPTH`); compiler types: rows are `array` of row, values `array` of the column type |
| Errors | `errors: [{status, code, bodyPointer?, message?}]` | Decision 4; `CONN_V1_ERROR_STATUS` for unmappable or repeated statuses |
| Pagination | `pagination: {style: offset \| cursor \| pageToken \| linkHeader, itemsPointer, pageSize, offsetParam, limitParam, cursorPointer, cursorParam, maxPages, maxItems}` | GET and `readOnly` only; a `Link: rel="next"` target must stay under the binding base URL (same origin, path prefix, no user info) or the read fails as an invalid response (`CONN_V1_PAGINATION`) |
| Timeouts | `maxTimeoutSeconds` ≤ 120 with `long: true` above 30; workflow `timeoutSeconds` maximum raised to 120 | The effect lease covers timeout × pages plus lookups and settle time; when that exceeds the async job lock, the HTTP delegate re-locks its own job row (`act_ru_job.lock_exp_time_`) inside the job transaction |
| Async | `async: {acceptedStatuses, statusFrom: {header} \| {bodyPointer}, statusOperation, pollIntervalSeconds, maxWaitSeconds}` | Validation only (`CONN_V1_ASYNC`: the status operation must be `readOnly`) |
| Reconcile | `reconcile: {operation, keyField, keyFrom: request.reference \| effectKey, lookupField?, found, resultFrom: found, foundAs?, lookupSettleSeconds?}` | Decision 6; `CONN_V1_RECONCILE`: the lookup is another `readOnly` GET without pagination that requires only the key; `keyField` is a text field of at least 64 characters; `found` and `foundAs` resolve |

`idempotentNatural` (PUT, PATCH or DELETE only) retries like `idempotentWithKey` without a header. Appending operations (work notes, comments) must stay `nonIdempotent`; the approver sees the class on the review screen.

## 3. Lookup reconciliation in detail

- **Key.** `request.reference` gives `<request reference>-<step id>-<occurrence>`, e.g. `ORQ-1042-procure-1`; `effectKey` gives the 64-character effect key. It is written into `keyField` on every send (the request hash covers only the mapped inputs, so it is stable). Workflows cannot map `keyField` (`WF_MAPPING_RECONCILE_KEY`); it is not a required mapping.
- **Settling.** The lookup is tried three times over `lookupSettleSeconds` (default 30, at most 60) before "not found".
- **Results.** With `resultFrom: found` the found value is placed at `foundAs` (default: the whole answer), so the step's own result pointers work for both a normal answer and a reconciliation. A found record that cannot give every result is treated as a failed lookup (unknown).
- **Timeline.** `EFFECT_SUCCEEDED` carries `reconciled: true`, the lookup operation and the summary "Reconciled automatically: found INC0012345 created by an earlier attempt, so nothing was sent again."
- **Crash.** `EffectJournal.begin` answers `RECONCILE_FIRST` for an `idempotentByLookup` entry left `inFlight` with an expired lease: the lookup runs before anything is sent.

## 4. Implementation

| Area | Files |
|---|---|
| Contracts | `contracts/connection-definition-v1.schema.json`; `contracts/workflow-v0.schema.json` (`http.config.timeoutSeconds` ≤ 120); `contracts/openapi.yaml` (new `ConnectionDefinitionDocument` = v0 or v1 in one block; effect-class enums gain `idempotentByLookup`, `idempotentNatural`; OpenAPI import selections may choose `idempotentNatural`) |
| Migration | `V230__connection_v1_effect_classes.sql`: `effect_journal.effect_class` check |
| Shared rules | `backend/compiler/.../ConnectionContracts.java` (inputs, result pointers, `CONN_V1_*`) |
| Compiler | `SchemaValidation` (schema by version), `Connections` (inputs with path parameters, array and header pointers, `responseList`), `FieldTypes` (list results), `BindingRules` (`WF_MAPPING_RECONCILE_KEY`, file inputs), `FailureRules` and the simulator (`failureCode`) |
| Registration | `ConnectionAdminService` (v1 schema plus rules; the connection test passes `input` to v1 reads); workspace export includes the v1 schema |
| Execution | `V1Requests` (path, query, encoders, headers, bodies, files), `V1Results` (rows, headers, `Link`), `HttpExecutor` (v1 request and response, error mapping), `EffectDispatcher` (key, lookup, pagination, lease), `EffectJournal` (`RECONCILE_FIRST`, `finishReconciled`), `HttpTaskDelegate` (mapped codes as known rejections, `failureCode`, timeline facts, job re-lock), `RequestFiles` / `RunRequestFiles` |
| OpenAPI import (S36) | `OpenApiConverter` emits v1 when needed: path parameters (input names in camelCase, the file's pattern or enum, else one safe segment with a note), scalar query and header parameters as inputs, DELETE (suggested `nonIdempotent`); `definition()` writes `1.0` only when an operation needs it |
| Studio | `connectionSchema.ts` (`resultCandidates`, `resultPointerType`, `operationInputs`), `HttpInspector` (path parameters "in the address", file inputs only from file questions, the reconcile note, nested values, rows, lists and headers to keep), effect-class labels |
| Example | `examples/connections/servicenow-table.connection.json` (get, find by correlation, list with pagination, create by lookup, update natural, work note, attach file, DELETE); `examples/fixtures/servicenow-table.wiremock.json` |
| Contract check | `tools/contract-check/contract_check.py` validates 1.0 examples against the v1 schema |

## 5. Tests

| Test | Covers |
|---|---|
| `ConnectionV1CasesTest` (compiler) over `examples/fixtures/connection-v1-cases.json` | 46 registration cases (each `CONN_V1_*` rule and schema refusals), 13 workflow compile cases (path parameter required, reconcile key, rows, headers, file inputs, timeouts, `failureCode` on the failed route), every v0 example still valid with no v1 rule |
| `ServiceNowConnectionTest` (PostgreSQL, Flowable, public API, WireMock) | Get by sys_id with constant query; 404 mapped to `INCIDENT_NOT_FOUND` on the failed route; a path value with `../` never sent; list with rows, `X-Total-Count`, two journaled pages and the cap; create whose answer is lost after commit reconciled by lookup (one create); create lost before commit resent with the same key after three empty lookups |
| `V1RequestBodiesTest` (PostgreSQL) | Binary and multipart bodies from an uploaded request file, a file of another request refused, form and nested JSON bodies, encoders, next-page links inside the binding |
| `ServiceNowFixturesTest` | The example against its WireMock fixture file through the executor |
| `ConnectionV1ApiTest` | v1 registration, `CONN_V1_*` field errors with pointers (no secret values echoed), v1 schema errors, connection test with input |
| `OpenApiConverterTest` | Path, query, header and DELETE import to a v1 definition that passes the schema and rules; static imports stay 0.1 |
| `HttpInspector.v1.test.tsx` | Studio inputs and results for the ServiceNow example |

## 6. Not in this slice

XML/SOAP templates, `odataFilter` and `jql` encoders, executing `async` (CG-W02), enforcing `rateLimit` (CG-W04), lookups for `attachFile` by file name and SHA-256 (the example declares it `nonIdempotent`), row lists in the OpenAPI import (arrays are still dropped with a note), Basic, JWT bearer and mutual TLS bindings (CG-C08), and the connector package around this definition (CG-C03).
