/**
 * The server refused a call. `code` is the problem code of the RFC 9457 answer, for example `JOB_LEASE_LOST`;
 * `HTTP_ERROR` when the answer had none, `TRANSPORT_ERROR` when no answer arrived (status 0).
 */
export class ApiError extends Error {
  override readonly name = 'ApiError'

  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string | undefined,
    readonly correlationId: string | undefined,
    readonly retryAfterSeconds: number | undefined,
  ) {
    super(`${status} ${code}${detail ? `: ${detail}` : ''}`)
  }

  /** Worth repeating with the same idempotency key: no answer, 429, 502, 503 or 504. */
  get isTransient(): boolean {
    return [0, 429, 502, 503, 504].includes(this.status)
  }

  /** The server no longer accepts this caller: 401 or 403. */
  get isAuthenticationFailure(): boolean {
    return this.status === 401 || this.status === 403
  }
}

/** Builds an ApiError from a non-success answer. */
export async function problemFrom(response: Response, parsed?: unknown): Promise<ApiError> {
  let body = parsed
  if (body === undefined) {
    try {
      body = await response.clone().json()
    } catch {
      body = undefined
    }
  }
  const problem = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>
  const text = (key: string) => (typeof problem[key] === 'string' ? (problem[key] as string) : undefined)
  const retryAfter = response.headers.get('Retry-After')
  const seconds = retryAfter !== null && /^\d+$/.test(retryAfter.trim()) ? Number(retryAfter.trim()) : undefined
  return new ApiError(response.status, text('code') ?? 'HTTP_ERROR', text('detail'), text('correlationId'), seconds)
}

export function transportError(cause: unknown): ApiError {
  const name = cause instanceof Error ? cause.name : 'Error'
  const error = new ApiError(0, 'TRANSPORT_ERROR', name, undefined, undefined)
  ;(error as { cause?: unknown }).cause = cause
  return error
}
