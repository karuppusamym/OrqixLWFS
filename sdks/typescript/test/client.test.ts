import assert from 'node:assert/strict'
import { afterEach, beforeEach, describe, test } from 'node:test'
import { ApiError, CallbackTokenClient, OrqixClient, machineOperations, type DeprecationNotice } from '../src/index.js'
import { ScriptedServer } from './scripted-server.js'

export const WS = '00000000-0000-7000-8000-000000000201'
export const RUN_ID = '0193a0c4-1111-7000-8000-000000000001'
export const RUN = {
  id: RUN_ID, reference: 'ORQ-1043', workflow: { id: '0193a0c4-2222-7000-8000-000000000002', key: 'purchase-approval', name: 'Purchase approval' },
  release: { id: '0193a0c4-3333-7000-8000-000000000003', releaseNumber: 4 }, businessKey: 'PO-7', requestTitle: null,
  initiator: { id: '0193a0c4-4444-7000-8000-000000000004', displayName: 'Order bot' }, lifecycle: 'waiting', outcome: null,
  outcomeKind: null, currentSteps: [], attention: null, createdAt: '2026-11-09T04:45:30Z', updatedAt: '2026-11-09T04:45:30Z',
  endedAt: null, isTest: false, version: 2, addedByALaterRelease: { nested: true },
}
const TOKEN = 'ocb_' + 'A'.repeat(43)

describe('OrqixClient', () => {
  let server: ScriptedServer
  let notices: DeprecationNotice[]
  let client: OrqixClient

  beforeEach(async () => {
    server = await ScriptedServer.start()
    notices = []
    client = new OrqixClient({ baseUrl: server.baseUrl, workspaceId: WS, token: async () => 'machine-token', onDeprecation: n => notices.push(n) })
  })
  afterEach(() => server.close())

  test('startRun sends the bearer token and idempotency key and returns the run', async () => {
    server.once('POST', `/workspaces/${WS}/runs`, 201, RUN)
    const run = await client.startRun('start-PO-7-0001', { workflowId: RUN.workflow.id, input: { amount: 1200 } })
    assert.equal(run.reference, 'ORQ-1043')
    const [request] = server.received
    assert.equal(request!.path, `/api/v1/workspaces/${WS}/runs`)
    assert.equal(request!.headers.authorization, 'Bearer machine-token')
    assert.equal(request!.headers['idempotency-key'], 'start-PO-7-0001')
    assert.deepEqual(request!.json(), { workflowId: RUN.workflow.id, input: { amount: 1200 } })
  })

  test('a malformed idempotency key is refused before any call', async () => {
    await assert.rejects(client.startRun('short', { workflowId: 'w', input: {} }), TypeError)
    assert.equal(server.received.length, 0)
  })

  test('getRun and listRunEvents use their paths and query', async () => {
    server.once('GET', `/runs/${RUN_ID}`, 200, { ...RUN, tasks: [], allowedActions: {} })
    server.once('GET', `/runs/${RUN_ID}/events`, 200, { items: [], nextAfter: 5, hasMore: false, lifecycle: 'waiting' })
    const run = await client.getRun(RUN_ID)
    const events = await client.listRunEvents(RUN_ID, 4, 50)
    assert.equal(run.lifecycle, 'waiting')
    assert.equal(events.nextAfter, 5)
    assert.equal(server.received[1]!.query, 'after=4&limit=50')
    assert.equal(server.received[0]!.headers['idempotency-key'], undefined)
  })

  test('sendMessage posts the message', async () => {
    server.once('POST', `/workspaces/${WS}/messages`, 202, { id: '0193a0c4-5555-7000-8000-000000000005', status: 'delivered', matched: 1, delivered: [], bufferedUntil: null })
    const accepted = await client.sendMessage('payment-PO-7', { name: 'payment-received', correlationKey: 'PO-7', payload: { paid: true } })
    assert.equal(accepted.status, 'delivered')
    assert.deepEqual(server.received[0]!.json(), { name: 'payment-received', correlationKey: 'PO-7', payload: { paid: true } })
  })

  test('problem answers become ApiError with code, correlation and Retry-After', async () => {
    server.once('POST', '/runs', 429, { status: 429, code: 'RATE_LIMITED', detail: 'Slow down', correlationId: 'c-42' }, { 'Retry-After': '7' })
    const error = await client.startRun('start-PO-7-0002', { workflowId: 'w', input: {} }).catch(e => e)
    assert.ok(error instanceof ApiError)
    assert.equal(error.code, 'RATE_LIMITED')
    assert.equal(error.correlationId, 'c-42')
    assert.equal(error.retryAfterSeconds, 7)
    assert.ok(error.isTransient)
  })

  test('an answer without a problem document keeps its status', async () => {
    server.once('GET', `/runs/${RUN_ID}`, 502, '<html>bad gateway</html>')
    const error = await client.getRun(RUN_ID).catch(e => e)
    assert.equal(error.code, 'HTTP_ERROR')
    assert.equal(error.status, 502)
  })

  test('an unreachable server is a transient transport error', async () => {
    await server.close()
    const error = await client.getRun(RUN_ID).catch(e => e)
    assert.ok(error instanceof ApiError)
    assert.equal(error.code, 'TRANSPORT_ERROR')
    assert.ok(error.isTransient)
    server = await ScriptedServer.start()
  })

  test('a deprecated operation is reported once', async () => {
    const headers = { Deprecation: '@1767225600', Sunset: 'Wed, 30 Jun 2027 00:00:00 GMT' }
    const page = { items: [], nextAfter: 0, hasMore: false, lifecycle: 'running' }
    server.once('GET', '/events', 200, page, headers).once('GET', '/events', 200, page, headers)
    await client.listRunEvents(RUN_ID)
    await client.listRunEvents(RUN_ID)
    assert.equal(notices.length, 1)
    assert.equal(notices[0]!.operation, 'listRunEvents')
    assert.equal(notices[0]!.sunset, 'Wed, 30 Jun 2027 00:00:00 GMT')
  })

  test('the token function is called for every request', async () => {
    let calls = 0
    const rotating = new OrqixClient({ baseUrl: server.baseUrl, workspaceId: WS, token: () => `token-${++calls}` })
    server.always('GET', '/events', 200, { items: [], nextAfter: 0, hasMore: false, lifecycle: 'running' })
    await rotating.listRunEvents(RUN_ID)
    await rotating.listRunEvents(RUN_ID)
    assert.equal(server.received[0]!.headers.authorization, 'Bearer token-1')
    assert.equal(server.received[1]!.headers.authorization, 'Bearer token-2')
  })

  test('the typed api reaches any operation with the bearer token', async () => {
    server.once('GET', `/runs/${RUN_ID}/path`, 200, { nodes: [] })
    const result = await client.api.GET('/workspaces/{workspaceId}/runs/{runId}/path', { params: { path: { workspaceId: WS, runId: RUN_ID } } })
    assert.equal(result.response.status, 200)
    assert.equal(server.received[0]!.headers.authorization, 'Bearer machine-token')
  })

  test('the machine operation table lists the callback-token operations', () => {
    assert.equal(machineOperations.resumeCallbackWait.credential, 'callbackToken')
    assert.equal(machineOperations.completeExternalJob.idempotencyKeyRequired, true)
    assert.equal(Object.keys(machineOperations).length, 14)
  })
})

describe('CallbackTokenClient', () => {
  let server: ScriptedServer
  beforeEach(async () => { server = await ScriptedServer.start() })
  afterEach(() => server.close())

  const state = { waitId: '0193a0c4-6666-7000-8000-000000000006', runId: RUN_ID, runReference: 'ORQ-1043', nodeId: 'ship', state: 'waiting', suspended: false, fields: [] }

  test('poll sends only the callback token and honours the ETag', async () => {
    server.once('GET', `/workspaces/${WS}/callbacks/wait`, 200, state, { ETag: '"v1"' })
    server.once('GET', `/workspaces/${WS}/callbacks/wait`, 304, undefined, { ETag: '"v1"' })
    const wait = new CallbackTokenClient({ baseUrl: server.baseUrl, workspaceId: WS, token: TOKEN })
    const first = await wait.poll()
    const second = await wait.poll(first.etag)
    assert.equal(first.state?.state, 'waiting')
    assert.equal(second.notModified, true)
    const request = server.received[1]!
    assert.equal(request.headers['x-orqix-callback-token'], TOKEN)
    assert.equal(request.headers['if-none-match'], '"v1"')
    assert.equal(request.headers.authorization, undefined)
    assert.equal(request.query, '', 'the token never travels in the URL')
  })

  test('resume posts the result with its key; issue goes through the bearer client', async () => {
    server.once('POST', '/callback-waits/0193a0c4-6666-7000-8000-000000000006/token', 201, { waitId: state.waitId, token: TOKEN, expiresAt: '2026-11-10T04:45:30Z', pollPath: 'p', resumePath: 'r', header: 'X-Orqix-Callback-Token' })
    server.once('POST', '/callbacks/wait/resume', 200, { waitId: state.waitId, runId: RUN_ID, runReference: 'ORQ-1043', nodeId: 'ship', state: 'resumed', resumedAt: '2026-11-09T05:00:00Z', acceptedFields: ['trackingNumber'] })
    const client = new OrqixClient({ baseUrl: server.baseUrl, workspaceId: WS, token: () => 't' })
    const issued = await client.issueCallbackToken(state.waitId)
    const resumed = await client.callbackTokenClient(issued.token).resume('shipment-0001', { trackingNumber: '1Z999' })
    assert.equal(resumed.state, 'resumed')
    assert.equal(server.received[0]!.headers.authorization, 'Bearer t')
    assert.equal(server.received[1]!.headers.authorization, undefined)
    assert.equal(server.received[1]!.headers['idempotency-key'], 'shipment-0001')
    assert.deepEqual(server.received[1]!.json(), { result: { trackingNumber: '1Z999' } })
  })

  test('a token with control characters is refused', () => {
    assert.throws(() => new CallbackTokenClient({ baseUrl: server.baseUrl, workspaceId: WS, token: 'ocb_x\r\nX-Evil: 1' }), TypeError)
  })
})
