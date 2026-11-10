# Orqix SDKs

Client libraries for the Orqix machine API (`/api/v1`, [`contracts/openapi.yaml`](../contracts/openapi.yaml)) and the
external-worker protocol ([S64](../docs/build/S64-external-workers.md), business errors [S132](../docs/build/S132-secret-providers-and-worker-errors.md)).
Build notes: [S149](../docs/build/S149-sdks-and-api-compatibility.md). Compatibility promise:
[API compatibility policy](../docs/api-compatibility.md).

| SDK | Folder | Runtime | Dependencies |
|---|---|---|---|
| Java, Spring Boot starter | [`java`](java) | Java 21; Spring Boot 4 for the starter | Jackson 3 (Apache-2.0); Spring Boot (Apache-2.0) for the starter |
| TypeScript | [`typescript`](typescript) | Node.js 20+ or a browser, ESM | openapi-fetch (MIT) |
| .NET | [`dotnet`](dotnet) | .NET 8 | none |
| Python | [`../tools/worker-sdk`](../tools/worker-sdk) | Python 3.10+ | none (unchanged by S149) |

## What a machine identity can call

A bearer token reaches only the operations of the server's machine security chain; the callback-token operations take
the one-time token instead. The SDKs type exactly these 14 operations (generated operation tables), and the TypeScript
SDK additionally types every operation of the contract through `api`.

| Area | Operations |
|---|---|
| Requests | `startRun`, `getRun`, `listRunEvents` |
| Messages | `sendMessage` |
| External jobs | `fetchExternalJobs`, `extendExternalJobLock`, `completeExternalJob`, `failExternalJob` |
| Asynchronous hand-off | `awaitExternalJobCallback`, `completeExternalJobCallback`, `failExternalJobCallback` |
| Callback tokens | `issueCallbackToken` (bearer, `run.operate`), `pollCallbackWait`, `resumeCallbackWait` (token) |

## The same features in every SDK

| Feature | Python | Java | TypeScript | .NET |
|---|---|---|---|---|
| Worker | `Worker(base_url, ws, token, worker_id)` | `OrqixWorker.builder(client, workerId)` | `new Worker(client, { workerId })` | `new OrqixWorker(client, new OrqixWorkerOptions { WorkerId })` |
| Fetch and lock | `fetch_and_lock` | `fetchAndLock` | `fetchAndLock` | `FetchAndLockAsync` |
| Extend lease | `extend_lock` | `extendLock` | `extendLock` | `ExtendLockAsync` |
| Complete | `complete` | `complete` | `complete` | `CompleteAsync` |
| Fail (technical) | `fail`, `JobFailed` | `fail`, `JobFailedException` | `fail`, `JobFailed` | `FailAsync`, `JobFailedException` |
| Business error | `fail_with_error`, `BusinessError` | `failWithError`, `BusinessErrorException` | `failWithError`, `BusinessError` | `FailWithErrorAsync`, `BusinessErrorException` |
| Hand off to a gateway | `await_callback`, `complete_callback`, `fail_callback` | `awaitCallback`, `completeCallback`, `failCallback` | same as Java | `AwaitCallbackAsync`, `CompleteCallbackAsync`, `FailCallbackAsync` |
| Run one job / the loop | `handle`, `run`, `stop` | `handle`, `run`, `runOnce`, `stop` | `handle`, `run`, `runOnce`, `stop` | `HandleAsync`, `RunAsync`, `RunOnceAsync`, `Stop` (or cancel) |
| API errors | `ApiError` | `OrqixApiException` | `ApiError` | `OrqixApiException` |
| Typed client | — | `OrqixClient` | `OrqixClient` | `OrqixClient` |
| Callback-token helper | — | `CallbackTokenClient` | `CallbackTokenClient` | `CallbackTokenClient` |
| Message helper | — | `sendMessage(key, name, correlationKey, payload)` | `sendMessage(key, request)` | `SendMessageAsync(key, name, correlationKey, payload)` |
| Deprecation reports | — | `onDeprecation` | `onDeprecation` | `OnDeprecation` |
| Host integration | — | Spring Boot starter, `@OrqixWorker` | embed helper `mountOrqixComponent` | — (wrap `RunAsync` in a `BackgroundService`) |

Shared behaviour, as in the Python SDK:

- The lease is extended at half its length while a handler runs.
- Final reports use the idempotency keys `<job>:<fence>:complete` and `<job>:<fence>:fail` (`:await-callback` for a
  hand-off, `<callback>:complete|fail` for its delivery). A retried call after a lost answer cannot complete twice.
- `JOB_LEASE_LOST` and `JOB_NOT_OPEN` drop the job (another worker owns it); 401 and 403 stop the loop.
- An unexpected exception is reported as a retryable failure naming only its class, never its message.
- Business error codes are checked locally: `^[A-Z][A-Z0-9_]{1,39}$`, not starting with `ORQIX_`.
- The token is asked for on every call, so rotated tokens are picked up; it is never stored or logged.

Two additions in the new SDKs (decision recorded in S149): a final report that fails transiently (no answer, 429, 502,
503, 504) is repeated up to three times with the same idempotency key, and a result the server refuses (422) is reported
as a retryable failure naming the problem code, instead of waiting for the lease to run out.

## Generated code

```bash
python sdks/codegen/generate.py          # after a contract change
python sdks/codegen/generate.py --check  # CI
```

It writes the Java records and enum constants, the .NET models and the operation tables of all three SDKs. The
TypeScript types come from `openapi-typescript` (`npm run api:generate` in `sdks/typescript`, checked by
`npm run api:check`), like the frontend's.

## Tests

Each SDK's tests run against a scripted loopback HTTP server; no Orqix stack is needed. CI job `sdks`.

```bash
python sdks/codegen/generate.py --check
cd sdks/java && sh ./mvnw -B verify
cd sdks/typescript && npm ci && npm run api:check && npm test
cd sdks/dotnet && dotnet test Orqix.sln
```
