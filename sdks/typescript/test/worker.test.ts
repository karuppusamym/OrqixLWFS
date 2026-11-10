import assert from 'node:assert/strict'
import { afterEach, beforeEach, describe, test } from 'node:test'
import { ApiError, BusinessError, JobFailed, OrqixClient, Worker, type Job } from '../src/index.js'
import { ScriptedServer } from './scripted-server.js'

const WS = '00000000-0000-7000-8000-000000000201'
const JOB_ID = '0193a0c4-7777-7000-8000-000000000007'
const JOBS = { items: [{ id: JOB_ID, topic: 'address-check', input: { postcode: 'W1A 1AA' }, fence: 3, leaseExpiresAt: '2026-11-09T10:00:00Z', attempt: 1, maxAttempts: 3, runReference: 'ORQ-1043', nodeId: 'check', notYetKnown: 'ignored' }] }
const quiet = { warn: () => undefined, info: () => undefined }

describe('Worker', () => {
  let server: ScriptedServer
  let client: OrqixClient
  const worker = (extra: Partial<ConstructorParameters<typeof Worker>[1]> = {}) =>
    new Worker(client, { workerId: 'address-checker-1', pollIntervalMs: 10, finalCallBackoffMs: 5, logger: quiet, ...extra })

  beforeEach(async () => {
    server = await ScriptedServer.start()
    client = new OrqixClient({ baseUrl: server.baseUrl, workspaceId: WS, token: () => 'worker-token' })
  })
  afterEach(() => server.close())

  test('completes a job with the fence and a job:fence key', async () => {
    server.once('POST', '/external-jobs/fetch-and-lock', 200, JOBS)
    server.once('POST', `/external-jobs/${JOB_ID}/complete`, 204)
    let seen: Job | undefined
    const completed = await worker().runOnce({ 'address-check': job => { seen = job; return { valid: true, score: 0.9 } } })
    assert.equal(completed, 1)
    assert.equal(seen?.input.postcode, 'W1A 1AA')
    assert.deepEqual(server.requests('POST', '/fetch-and-lock')[0]!.json(), { workerId: 'address-checker-1', topics: ['address-check'], maxJobs: 5, leaseSeconds: 120 })
    const complete = server.requests('POST', '/complete')[0]!
    assert.equal(complete.headers['idempotency-key'], `${JOB_ID}:3:complete`)
    assert.deepEqual(complete.json(), { workerId: 'address-checker-1', fence: 3, result: { valid: true, score: 0.9 } })
  })

  test('a business error is a final fail with its code', async () => {
    server.once('POST', '/fetch-and-lock', 200, JOBS).once('POST', `/${JOB_ID}/fail`, 204)
    await worker().runOnce({ 'address-check': () => { throw new BusinessError('ADDRESS_UNKNOWN', 'No such street') } })
    const fail = server.requests('POST', '/fail')[0]!
    assert.equal(fail.headers['idempotency-key'], `${JOB_ID}:3:fail`)
    assert.deepEqual(fail.json(), { workerId: 'address-checker-1', fence: 3, message: 'No such street', retryable: false, errorCode: 'ADDRESS_UNKNOWN' })
  })

  test('a job failure keeps its retry choice and delay', async () => {
    server.once('POST', '/fetch-and-lock', 200, JOBS).once('POST', `/${JOB_ID}/fail`, 204)
    await worker().runOnce({ 'address-check': async () => { throw new JobFailed('Directory offline', true, 60) } })
    assert.deepEqual(server.requests('POST', '/fail')[0]!.json(), { workerId: 'address-checker-1', fence: 3, message: 'Directory offline', retryable: true, retryAfterSeconds: 60 })
  })

  test('an unexpected error reports only its class name', async () => {
    server.once('POST', '/fetch-and-lock', 200, JOBS).once('POST', `/${JOB_ID}/fail`, 204)
    await worker().runOnce({ 'address-check': () => { throw new RangeError('secret connection string') } })
    assert.equal(server.requests('POST', '/fail')[0]!.json().message, 'The worker raised RangeError')
  })

  test('a lost lease drops the job', async () => {
    server.once('POST', '/fetch-and-lock', 200, JOBS).once('POST', `/${JOB_ID}/complete`, 409, { code: 'JOB_LEASE_LOST' })
    assert.equal(await worker().runOnce({ 'address-check': () => ({}) }), 0)
    assert.equal(server.requests('POST', '/complete').length, 1)
  })

  test('transient failures of the final call repeat with the same key', async () => {
    server.once('POST', '/fetch-and-lock', 200, JOBS)
      .once('POST', `/${JOB_ID}/complete`, 503, { code: 'DEPENDENCY_UNAVAILABLE' })
      .once('POST', `/${JOB_ID}/complete`, 204)
    assert.equal(await worker().runOnce({ 'address-check': () => ({ valid: true }) }), 1)
    const [first, second] = server.requests('POST', '/complete')
    assert.equal(first!.headers['idempotency-key'], second!.headers['idempotency-key'])
    assert.equal(first!.body, second!.body)
  })

  test('a refused result is reported as a failure', async () => {
    server.once('POST', '/fetch-and-lock', 200, JOBS)
      .once('POST', `/${JOB_ID}/complete`, 422, { code: 'VALIDATION_FAILED' })
      .once('POST', `/${JOB_ID}/fail`, 204)
    assert.equal(await worker().runOnce({ 'address-check': () => ({ unexpected: 1 }) }), 0)
    assert.equal(server.requests('POST', '/fail')[0]!.json().message, "The worker's result was refused (VALIDATION_FAILED)")
  })

  test('the lease is extended while the handler runs', async () => {
    server.once('POST', '/fetch-and-lock', 200, JOBS)
      .always('POST', `/${JOB_ID}/extend-lock`, 200, { leaseExpiresAt: '2026-11-09T10:02:00Z' })
      .once('POST', `/${JOB_ID}/complete`, 204)
    let lease = ''
    await worker({ extendEveryMs: 20 }).runOnce({
      'address-check': async job => {
        await server.waitFor(r => r.path.endsWith('/extend-lock'))
        await new Promise(resolve => setTimeout(resolve, 30))
        lease = job.leaseExpiresAt
        return { valid: true }
      },
    })
    assert.deepEqual(server.requests('POST', '/extend-lock')[0]!.json(), { workerId: 'address-checker-1', fence: 3, leaseSeconds: 120 })
    assert.equal(lease, '2026-11-09T10:02:00Z')
  })

  test('authentication failure stops the loop', async () => {
    server.once('POST', '/fetch-and-lock', 401, { code: 'UNAUTHENTICATED' })
    const error = await worker().run({ 'address-check': () => ({}) }).catch(e => e)
    assert.ok(error instanceof ApiError)
    assert.equal(error.code, 'UNAUTHENTICATED')
  })

  test('run polls until stopped', async () => {
    server.always('POST', '/fetch-and-lock', 200, { items: [] })
    const w = worker()
    const running = w.run({ 'address-check': () => ({}) })
    await server.waitFor(() => server.requests('POST', '/fetch-and-lock').length >= 2)
    w.stop()
    assert.equal(await running, 0)
  })

  test('callback hand-off uses its own keys', async () => {
    const callbackId = '0193a0c4-8888-7000-8000-000000000008'
    server.once('POST', '/fetch-and-lock', 200, JOBS)
      .once('POST', `/${JOB_ID}/await-callback`, 200, { callbackId, deadline: '2026-11-09T11:00:00Z' })
      .once('POST', `/callbacks/${callbackId}/complete`, 204)
      .once('POST', `/callbacks/${callbackId}/fail`, 204)
    const w = worker()
    const [job] = await w.fetchAndLock(['address-check'])
    const ticket = await w.awaitCallback(job!, 3600)
    const restored = JSON.parse(JSON.stringify(ticket)) // a gateway outbox round trip
    await w.completeCallback(restored, { score: 0.7 })
    await w.failCallback(restored, 'x'.repeat(600))
    assert.equal(server.requests('POST', '/await-callback')[0]!.headers['idempotency-key'], `${JOB_ID}:3:await-callback`)
    assert.equal(server.requests('POST', `/callbacks/${callbackId}/complete`)[0]!.headers['idempotency-key'], `${callbackId}:complete`)
    assert.equal(server.requests('POST', `/callbacks/${callbackId}/fail`)[0]!.json().message.length, 500)
  })

  test('business error codes, topics and limits are checked', async () => {
    assert.throws(() => new BusinessError('ORQIX_INTERNAL', 'x'), TypeError)
    assert.throws(() => new BusinessError('lower', 'x'), TypeError)
    assert.throws(() => worker({ leaseSeconds: 10 }), RangeError)
    assert.throws(() => worker({ maxJobs: 11 }), RangeError)
    await assert.rejects(worker().runOnce({ 'Bad Topic': () => ({}) }), TypeError)
    await assert.rejects(worker().runOnce({}), RangeError)
  })
})
