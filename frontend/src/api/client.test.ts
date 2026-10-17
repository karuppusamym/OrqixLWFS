import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import * as auth from './auth'
import type * as AuthModule from './auth'
import { api, call, callVoid, callWithResponse, revisionEtag } from './client'
import { IdempotencyKeyStore, fingerprintOf } from './idempotency'
import { ProblemError } from './problem'

vi.mock('./auth', async (importOriginal) => ({ ...(await importOriginal<typeof AuthModule>()), redirectToLogin: vi.fn() }))

const ME = '*/api/v1/me'

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  vi.mocked(auth.redirectToLogin).mockClear()
})

describe('request headers', () => {
  it('adds a correlation id to every request', async () => {
    let seen: string | null = null
    server.use(
      http.get(ME, ({ request }) => {
        seen = request.headers.get('X-Correlation-Id')
        return HttpResponse.json({ subject: { id: 'u', displayName: 'U' }, platformAdmin: false, workspaces: [] })
      }),
    )
    await call(api.GET('/me'))
    expect(seen).toMatch(/^[A-Za-z0-9-]{8,64}$/)
  })

  it('sends X-XSRF-TOKEN from the XSRF-TOKEN cookie on unsafe methods only', async () => {
    document.cookie = 'XSRF-TOKEN=token%2Fvalue; path=/'
    const seen: Record<string, string | null> = {}
    server.use(
      http.get(ME, ({ request }) => {
        seen.get = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ subject: { id: 'u', displayName: 'U' }, platformAdmin: false, workspaces: [] })
      }),
      http.post('*/api/v1/workspaces/:ws/notifications/:id/read', ({ request }) => {
        seen.post = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
      http.put('*/api/v1/workspaces/:ws/workflows/:id/draft', ({ request }) => {
        seen.put = request.headers.get('X-XSRF-TOKEN')
        seen.ifMatch = request.headers.get('If-Match')
        return HttpResponse.json({ workflowId: 'w', revision: 2, savedAt: 'now', savedBy: { id: 'u', displayName: 'U' }, definition: {} }, { headers: { ETag: '"r2"' } })
      }),
    )
    await call(api.GET('/me'))
    await callVoid(api.POST('/workspaces/{workspaceId}/notifications/{notificationId}/read', { params: { path: { workspaceId: 'a'.repeat(8), notificationId: 'b'.repeat(8) } } }))
    const { response } = await callWithResponse(
      api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/draft', {
        params: { path: { workspaceId: 'a'.repeat(8), workflowId: 'b'.repeat(8) }, header: { 'If-Match': revisionEtag(1) } },
        body: { definition: {} },
      }),
    )
    expect(seen.get).toBeNull()
    expect(seen.post).toBe('token/value')
    expect(seen.put).toBe('token/value')
    expect(seen.ifMatch).toBe('"r1"')
    expect(response.headers.get('ETag')).toBe('"r2"')
  })

  it('keeps cookies same-origin and never reads response headers it was not given', async () => {
    let credentials = ''
    server.use(
      http.get(ME, ({ request }) => {
        credentials = request.credentials
        return HttpResponse.json({ subject: { id: 'u', displayName: 'U' }, platformAdmin: false, workspaces: [] })
      }),
    )
    await call(api.GET('/me'))
    expect(credentials).toBe('same-origin')
  })
})

describe('problem details', () => {
  const problem = (extra: Record<string, unknown>, status = 409, headers: Record<string, string> = {}) =>
    HttpResponse.json(
      { type: 'https://orqix.example/problems/x', title: 'Conflict', status, correlationId: 'c0a8017e-6f1b-4b7e-9d0c-2a4f5e0d9b11', ...extra },
      { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
    )

  it('parses application/problem+json into a typed ProblemError', async () => {
    server.use(http.get(ME, () => problem({ code: 'TASK_VERSION_CONFLICT', currentVersion: 4, detail: 'Reload the task.' })))
    const error = await call(api.GET('/me')).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ProblemError)
    const p = error as ProblemError
    expect(p.code).toBe('TASK_VERSION_CONFLICT')
    expect(p.status).toBe(409)
    expect(p.currentVersion).toBe(4)
    expect(p.correlationId).toBe('c0a8017e-6f1b-4b7e-9d0c-2a4f5e0d9b11')
    expect(p.is('TASK_VERSION_CONFLICT', 'TASK_NOT_OPEN')).toBe(true)
  })

  it('carries field errors, issues and revisions', async () => {
    server.use(
      http.get(ME, () =>
        problem({ code: 'VALIDATION_FAILED', fieldErrors: [{ pointer: '/input/amount', code: 'multipleOf', message: 'cents' }], issues: [{ code: 'WF_CYCLE', severity: 'blocker', message: 'loop', path: '/transitions' }], currentRevision: 9 }, 422),
      ),
    )
    const p = (await call(api.GET('/me')).catch((e: unknown) => e)) as ProblemError
    expect(p.fieldErrors).toHaveLength(1)
    expect(p.issues[0]?.code).toBe('WF_CYCLE')
    expect(p.currentRevision).toBe(9)
  })

  it('reads Retry-After', async () => {
    server.use(http.get(ME, () => problem({ code: 'DEPENDENCY_UNAVAILABLE' }, 503, { 'Retry-After': '7' })))
    const p = (await call(api.GET('/me')).catch((e: unknown) => e)) as ProblemError
    expect(p.retryAfterSeconds).toBe(7)
  })

  it('turns a non-problem failure into UNKNOWN_ERROR and a dropped connection into NETWORK_ERROR', async () => {
    server.use(http.get(ME, () => new HttpResponse('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } })))
    expect(((await call(api.GET('/me')).catch((e: unknown) => e)) as ProblemError).code).toBe('UNKNOWN_ERROR')
    server.use(http.get(ME, () => HttpResponse.error()))
    expect(((await call(api.GET('/me')).catch((e: unknown) => e)) as ProblemError).code).toBe('NETWORK_ERROR')
  })

  it('401 starts the OIDC redirect and never settles', async () => {
    server.use(http.get(ME, () => problem({ code: 'UNAUTHENTICATED' }, 401)))
    const outcome = await Promise.race([call(api.GET('/me')).then(() => 'resolved', () => 'rejected'), new Promise((resolve) => setTimeout(() => resolve('pending'), 300))])
    expect(outcome).toBe('pending')
    expect(auth.redirectToLogin).toHaveBeenCalledTimes(1)
  })
})

describe('idempotency keys', () => {
  it('reuses the key for the same command and creates a new one when the input changes', () => {
    const store = new IdempotencyKeyStore()
    const a = store.keyFor(fingerprintOf('start', { amount: 1800 }))
    expect(store.keyFor(fingerprintOf('start', { amount: 1800 }))).toBe(a)
    const b = store.keyFor(fingerprintOf('start', { amount: 1900 }))
    expect(b).not.toBe(a)
    expect(b).toMatch(/^[A-Za-z0-9._:-]{8,128}$/)
    store.reset()
    expect(store.keyFor(fingerprintOf('start', { amount: 1900 }))).not.toBe(b)
  })
})

describe('cookies', () => {
  it('reads and decodes a cookie by name', () => {
    expect(auth.readCookie('B', 'A=1; B=two%20words; C=3')).toBe('two words')
    expect(auth.readCookie('Z', 'A=1')).toBeNull()
  })
})
