import createClient, { type Middleware } from 'openapi-fetch'
import { uuid } from '@/lib/text'
import { readCookie, redirectToLogin } from './auth'
import { networkProblem, problemFromResponse } from './problem'
import type { paths } from './schema'

export const API_BASE = '/api/v1'

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'DELETE', 'PATCH'])

/**
 * Request/response middleware (docs/build/S7-frontend.md section 3):
 * - X-Correlation-Id on every request (matches ^[A-Za-z0-9-]{8,64}$, S1 section 9).
 * - X-XSRF-TOKEN from the XSRF-TOKEN cookie on unsafe methods (S3 section 2).
 */
export const requestMiddleware: Middleware = {
  onRequest({ request }) {
    if (!request.headers.has('X-Correlation-Id')) request.headers.set('X-Correlation-Id', uuid())
    if (UNSAFE_METHODS.has(request.method.toUpperCase()) && !request.headers.has('X-XSRF-TOKEN')) {
      const token = readCookie('XSRF-TOKEN')
      if (token) request.headers.set('X-XSRF-TOKEN', token)
    }
    return request
  },
}

function resolveBaseUrl(): string {
  // openapi-fetch builds absolute Request objects, so make the same-origin base absolute.
  const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost'
  return `${origin}${API_BASE}`
}

export function createApiClient() {
  const client = createClient<paths>({
    baseUrl: resolveBaseUrl(),
    credentials: 'same-origin',
    headers: { Accept: 'application/json, application/problem+json' },
    // Late-bound so test doubles (MSW) and the service worker see the call.
    fetch: (request) => globalThis.fetch(request),
  })
  client.use(requestMiddleware)
  return client
}

export const api = createApiClient()

interface ApiResult<T> {
  data?: T
  error?: unknown
  response: Response
}

/**
 * Unwrap an openapi-fetch result. Resolves with the body, or throws a typed ProblemError.
 * A 401 starts the OIDC redirect and then never settles, so no error UI flashes during navigation.
 */
export async function call<T>(promise: Promise<ApiResult<T>>): Promise<T> {
  const { data } = await callWithResponse(promise)
  return data
}

export async function callWithResponse<T>(promise: Promise<ApiResult<T>>): Promise<{ data: T; response: Response }> {
  let result: ApiResult<T>
  try {
    result = await promise
  } catch (cause) {
    throw networkProblem(cause)
  }
  const { data, error, response } = result
  if (response.status === 401) {
    redirectToLogin()
    return new Promise<{ data: T; response: Response }>(() => undefined)
  }
  if (!response.ok) throw problemFromResponse(response, error)
  return { data: data as T, response }
}

/** For operations that answer 204 No Content. */
export async function callVoid(promise: Promise<{ error?: unknown; response: Response }>): Promise<void> {
  let result: { error?: unknown; response: Response }
  try {
    result = await promise
  } catch (cause) {
    throw networkProblem(cause)
  }
  if (result.response.status === 401) {
    redirectToLogin()
    return new Promise<void>(() => undefined)
  }
  if (!result.response.ok) throw problemFromResponse(result.response, result.error)
}

/** ETag header value of a response, or null. */
export function etagOf(response: Response): string | null {
  return response.headers.get('ETag')
}

/** Strong ETag for a draft or binding revision, e.g. 12 -> "r12". */
export function revisionEtag(revision: number): string {
  return `"r${revision}"`
}
