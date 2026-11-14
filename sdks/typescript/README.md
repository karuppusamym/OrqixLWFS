# Orqix SDK for TypeScript

`@orqix/sdk` 0.1.0: ES modules for Node.js 20+ and browsers. Typed client, external-worker loop, callback-token helper
and an embed helper for the Orqix authoring and task components. One runtime dependency: `openapi-fetch` (MIT).
Not yet published to a registry; build with `npm run build` and depend on the folder.

## Client

```ts
import { OrqixClient, ApiError } from '@orqix/sdk'

const orqix = new OrqixClient({
  baseUrl: 'https://orqix.example/api/v1',
  workspaceId,
  token: () => tokens.current(),      // called for every request; may be async
})

const run = await orqix.startRun(`order-${orderId}`, { workflowId, input: { amount: 1200 } })
const detail = await orqix.getRun(run.id)
const events = await orqix.listRunEvents(run.id, 0, 50)
await orqix.sendMessage(`payment-${orderId}`, { name: 'payment-received', correlationKey: orderId, payload: { paid: true } })
```

Types come from `contracts/openapi.yaml` through `openapi-typescript` (the same output as the frontend's). `orqix.api`
is the underlying `openapi-fetch` client, typed for every operation of the contract, for calls without a helper.
Errors are `ApiError` with `status`, `code`, `correlationId`, `retryAfterSeconds`, `isTransient` and
`isAuthenticationFailure`. Deprecated operations are reported once through `onDeprecation`.

## Worker

```ts
import { Worker, BusinessError, JobFailed } from '@orqix/sdk'

const worker = new Worker(orqix, { workerId: 'address-checker-1' })
await worker.run({
  'address-check': async job => {
    if (!(await directory.knows(job.input.postcode))) throw new BusinessError('ADDRESS_UNKNOWN', 'No such postcode')
    return { valid: true, score: 0.9 }
  },
}) // until worker.stop()
```

`throw new JobFailed(message, retryable, retryAfterSeconds)` for a technical failure. For asynchronous gateways use
`fetchAndLock`, `awaitCallback`, `completeCallback` and `failCallback` and persist the ticket.

## Callback tokens

```ts
const issued = await orqix.issueCallbackToken(waitId)               // run.operate; shown once
const wait = orqix.callbackTokenClient(issued.token)                 // or new CallbackTokenClient({ baseUrl, workspaceId, token })
const poll = await wait.poll()                                       // pass poll.etag next time
await wait.resume(`shipment-${shipmentId}`, { trackingNumber: '1Z999' })
```

## Embedding the components

```ts
import { mountOrqixComponent } from '@orqix/sdk/embed'

const component = await mountOrqixComponent(document.getElementById('workflow')!, {
  component: 'authoring',            // or 'tasks'
  workspaceId,
  workflowId,                        // optional, authoring only
  onNavigate: path => console.info(path),
})
component.unmount()
```

The helper loads `/components/<component>/orqix.css` once and the component module, then calls its `mount` with
`apiVersion: 'v1'` ([embedding](../../docs/hackathon-embedding.md)). The components use the signed-in application
session, so they are served from the page's own origin through the application's proxy; `baseUrl` may be a path prefix
(`/orqix`) or a same-origin URL, and another origin is refused. No token or role is passed to a component.

## Scripts

```bash
npm ci
npm run api:generate   # after a contract change
npm run api:check      # CI: the committed types match the contract
npm run build
npm test               # node:test against a scripted loopback server
```
