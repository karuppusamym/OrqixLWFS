import createClient, { type Client, type Middleware } from 'openapi-fetch'
import { ApiError, problemFrom, transportError } from './errors.js'
import { machineOperations } from './generated/operations.js'
import type { components, paths } from './generated/schema.js'

type Schemas = components['schemas']
export type Run = Schemas['Run']
export type RunDetail = Schemas['RunDetail']
export type EventPage = Schemas['EventPage']
export type StartRunRequest = Schemas['StartRunRequest']
export type SendMessageRequest = Schemas['SendMessageRequest']
export type MessageAccepted = Schemas['MessageAccepted']
export type ExternalJob = Schemas['ExternalJob']
export type ExternalJobBatch = Schemas['ExternalJobBatch']
export type ExternalJobFetchRequest = Schemas['ExternalJobFetchRequest']
export type ExternalJobExtendRequest = Schemas['ExternalJobExtendRequest']
export type ExternalJobExtendResult = Schemas['ExternalJobExtendResult']
export type ExternalJobCompleteRequest = Schemas['ExternalJobCompleteRequest']
export type ExternalJobFailRequest = Schemas['ExternalJobFailRequest']
export type ExternalJobAwaitCallbackRequest = Schemas['ExternalJobAwaitCallbackRequest']
export type ExternalJobCallbackRegistration = Schemas['ExternalJobCallbackRegistration']
export type ExternalJobCallbackResult = Schemas['ExternalJobCallbackResult']
export type ExternalJobCallbackFailure = Schemas['ExternalJobCallbackFailure']
export type CallbackTokenIssued = Schemas['CallbackTokenIssued']
export type CallbackWaitState = Schemas['CallbackWaitState']
export type CallbackResumed = Schemas['CallbackResumed']
export type { components, paths }

export const SDK_VERSION = '0.1.0'
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{8,128}$/

/** The server answered a call to a deprecated operation (docs/api-compatibility.md). */
export interface DeprecationNotice {
  /** The operationId, or `METHOD /path` for an operation outside the machine surface. */
  operation: string
  /** Raw `Deprecation` header (RFC 9745). */
  deprecation: string
  /** Raw `Sunset` header (RFC 8594), when sent. */
  sunset?: string
  /** `Link` header pointing to the replacement, when sent. */
  link?: string
}

export interface TransportOptions {
  /** Absolute URL of the API, ending in `/api/v1`. */
  baseUrl: string
  workspaceId: string
  /** A `fetch` implementation; default `globalThis.fetch`. Redirects are never followed. */
  fetch?: typeof globalThis.fetch
  /** Per-request timeout; default 30 000 ms. */
  timeoutMs?: number
  /** Called once per operation when the server marks it deprecated; default `console.warn`. */
  onDeprecation?: (notice: DeprecationNotice) => void
}

export interface OrqixClientOptions extends TransportOptions {
  /** Returns the current access token of the machine identity; called for every request. */
  token: () => string | Promise<string>
}

export function checkIdempotencyKey(key: string): string {
  if (typeof key !== 'string' || !IDEMPOTENCY_KEY.test(key)) {
    throw new TypeError("An idempotency key is 8-128 characters of A-Z, a-z, 0-9, '.', '_', ':' or '-'; use one key per logical command (a UUID is fine).")
  }
  return key
}

/** openapi-fetch client with timeout, transport errors as ApiError and deprecation reports. Shared by both clients. */
export function createApi(options: TransportOptions): Client<paths> {
  if (!/^https?:\/\//.test(options.baseUrl ?? '')) {
    throw new TypeError('baseUrl must be the absolute URL of /api/v1, for example https://orqix.example/api/v1')
  }
  if (!options.workspaceId) throw new TypeError('workspaceId is required')
  const fetchImpl = options.fetch ?? globalThis.fetch
  const timeoutMs = options.timeoutMs ?? 30_000
  const timedFetch = async (input: Request): Promise<Response> => {
    try {
      return await fetchImpl(input, { signal: AbortSignal.timeout(timeoutMs), redirect: 'manual' })
    } catch (cause) {
      throw transportError(cause)
    }
  }
  const api = createClient<paths>({
    baseUrl: options.baseUrl.replace(/\/+$/, ''),
    fetch: timedFetch,
    headers: { Accept: 'application/json, application/problem+json' },
  })
  const reported = new Set<string>()
  const report = options.onDeprecation ?? ((notice: DeprecationNotice) =>
    console.warn(`Orqix operation ${notice.operation} is deprecated (Deprecation: ${notice.deprecation}, Sunset: ${notice.sunset ?? 'not announced'}); see docs/api-compatibility.md`))
  const deprecations: Middleware = {
    onResponse({ request, response, schemaPath }) {
      const deprecation = response.headers.get('Deprecation')
      if (deprecation === null) return undefined
      const known = Object.entries(machineOperations).find(([, op]) => op.method === request.method && op.path === schemaPath)
      const operation = known ? known[0] : `${request.method} ${schemaPath}`
      if (!reported.has(operation)) {
        reported.add(operation)
        report({ operation, deprecation, sunset: response.headers.get('Sunset') ?? undefined, link: response.headers.get('Link') ?? undefined })
      }
      return undefined
    },
  }
  api.use(deprecations)
  return api
}

/** Returns `data` of a success answer, or throws the answer's ApiError. */
export async function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): Promise<T> {
  if (!result.response.ok) throw await problemFrom(result.response, result.error ?? undefined)
  return result.data as T
}

/**
 * Typed client for the Orqix machine API (`/api/v1`). Methods are named after the contract's operationIds. `api` is
 * the underlying openapi-fetch client, typed for every operation of `contracts/openapi.yaml`, for calls without a
 * helper here.
 *
 * ```ts
 * const orqix = new OrqixClient({ baseUrl: 'https://orqix.example/api/v1', workspaceId, token: () => tokens.current() })
 * const run = await orqix.startRun(crypto.randomUUID(), { workflowId, input: { amount: 1200 } })
 * ```
 */
export class OrqixClient {
  readonly api: Client<paths>
  readonly workspaceId: string
  private readonly options: OrqixClientOptions

  constructor(options: OrqixClientOptions) {
    if (typeof options.token !== 'function') throw new TypeError('token must be a function returning the access token')
    this.options = options
    this.workspaceId = options.workspaceId
    this.api = createApi(options)
    const token = options.token
    this.api.use({
      async onRequest({ request }) {
        const value = await token()
        if (!value) throw new Error('The token function returned no access token')
        request.headers.set('Authorization', `Bearer ${value}`)
        request.headers.set('User-Agent', `orqix-sdk-typescript/${SDK_VERSION}`)
        return request
      },
    })
  }

  private get ws() {
    return { workspaceId: this.workspaceId }
  }

  /** Starts a request of the workflow's active release. Repeating the key with the same body replays the answer. */
  async startRun(idempotencyKey: string, request: StartRunRequest): Promise<Run> {
    return unwrap(await this.api.POST('/workspaces/{workspaceId}/runs', {
      params: { path: this.ws, header: { 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } }, body: request,
    }))
  }

  async getRun(runId: string): Promise<RunDetail> {
    return unwrap(await this.api.GET('/workspaces/{workspaceId}/runs/{runId}', { params: { path: { ...this.ws, runId } } }))
  }

  /** Events after sequence number `after` (0 for the first page); pass `nextAfter` to poll on. */
  async listRunEvents(runId: string, after = 0, limit?: number): Promise<EventPage> {
    return unwrap(await this.api.GET('/workspaces/{workspaceId}/runs/{runId}/events', {
      params: { path: { ...this.ws, runId }, query: limit === undefined ? { after } : { after, limit } },
    }))
  }

  /** Tells waiting requests that something happened (a "Wait for a message" step with this name and key). */
  async sendMessage(idempotencyKey: string, request: SendMessageRequest): Promise<MessageAccepted> {
    return unwrap(await this.api.POST('/workspaces/{workspaceId}/messages', {
      params: { path: this.ws, header: { 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } }, body: request,
    }))
  }

  async fetchExternalJobs(request: ExternalJobFetchRequest): Promise<ExternalJobBatch> {
    return unwrap(await this.api.POST('/workspaces/{workspaceId}/external-jobs/fetch-and-lock', { params: { path: this.ws }, body: request }))
  }

  async extendExternalJobLock(jobId: string, request: ExternalJobExtendRequest): Promise<ExternalJobExtendResult> {
    return unwrap(await this.api.POST('/workspaces/{workspaceId}/external-jobs/{jobId}/extend-lock', {
      params: { path: { ...this.ws, jobId } }, body: request,
    }))
  }

  async completeExternalJob(jobId: string, idempotencyKey: string, request: ExternalJobCompleteRequest): Promise<void> {
    await unwrap(await this.api.POST('/workspaces/{workspaceId}/external-jobs/{jobId}/complete', {
      params: { path: { ...this.ws, jobId }, header: { 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } }, body: request,
    }))
  }

  async failExternalJob(jobId: string, idempotencyKey: string, request: ExternalJobFailRequest): Promise<void> {
    await unwrap(await this.api.POST('/workspaces/{workspaceId}/external-jobs/{jobId}/fail', {
      params: { path: { ...this.ws, jobId }, header: { 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } }, body: request,
    }))
  }

  async awaitExternalJobCallback(jobId: string, idempotencyKey: string, request: ExternalJobAwaitCallbackRequest): Promise<ExternalJobCallbackRegistration> {
    return unwrap(await this.api.POST('/workspaces/{workspaceId}/external-jobs/{jobId}/await-callback', {
      params: { path: { ...this.ws, jobId }, header: { 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } }, body: request,
    }))
  }

  async completeExternalJobCallback(jobId: string, callbackId: string, idempotencyKey: string, request: ExternalJobCallbackResult): Promise<void> {
    await unwrap(await this.api.POST('/workspaces/{workspaceId}/external-jobs/{jobId}/callbacks/{callbackId}/complete', {
      params: { path: { ...this.ws, jobId, callbackId }, header: { 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } }, body: request,
    }))
  }

  async failExternalJobCallback(jobId: string, callbackId: string, idempotencyKey: string, request: ExternalJobCallbackFailure): Promise<void> {
    await unwrap(await this.api.POST('/workspaces/{workspaceId}/external-jobs/{jobId}/callbacks/{callbackId}/fail', {
      params: { path: { ...this.ws, jobId, callbackId }, header: { 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } }, body: request,
    }))
  }

  /** Issues the one-time callback token of an open wait (needs a run.operate grant). Store it like a password. */
  async issueCallbackToken(waitId: string): Promise<CallbackTokenIssued> {
    return unwrap(await this.api.POST('/workspaces/{workspaceId}/callback-waits/{waitId}/token', { params: { path: { ...this.ws, waitId } } }))
  }

  /** A client that polls and resumes the wait of `callbackToken`; it sends no bearer token. */
  callbackTokenClient(callbackToken: string): CallbackTokenClient {
    const { token: _token, ...transport } = this.options
    return new CallbackTokenClient({ ...transport, token: callbackToken })
  }
}

/** The answer of a poll: `notModified` after a 304 for the ETag passed, otherwise the current state. */
export interface CallbackPoll {
  notModified: boolean
  state?: CallbackWaitState
  etag?: string
}

/**
 * Callback-token helper (docs/build/S98): an outside system with the one-time token of a wait polls and resumes it.
 * The token travels in the `X-Orqix-Callback-Token` header, never in a URL; no bearer token is sent.
 */
export class CallbackTokenClient {
  private readonly api: Client<paths>
  private readonly workspaceId: string
  private readonly token: string

  constructor(options: TransportOptions & { token: string }) {
    if (!options.token || !/^[\x21-\x7e]+$/.test(options.token)) throw new TypeError('A callback token is required (printable ASCII, as issued)')
    this.api = createApi(options)
    this.workspaceId = options.workspaceId
    this.token = options.token
  }

  /** Polls the wait. Pass the previous `etag` to get `notModified` while nothing changed. */
  async poll(etag?: string): Promise<CallbackPoll> {
    const result = await this.api.GET('/workspaces/{workspaceId}/callbacks/wait', {
      params: { path: { workspaceId: this.workspaceId }, header: etag ? { 'X-Orqix-Callback-Token': this.token, 'If-None-Match': etag } : { 'X-Orqix-Callback-Token': this.token } },
    })
    const newEtag = result.response.headers.get('ETag') ?? etag
    if (result.response.status === 304) return { notModified: true, etag: newEtag }
    return { notModified: false, state: await unwrap(result), etag: newEtag }
  }

  /** Resumes the wait once; a repeat with the same key and result returns the original answer. */
  async resume(idempotencyKey: string, result: Record<string, unknown>): Promise<CallbackResumed> {
    return unwrap(await this.api.POST('/workspaces/{workspaceId}/callbacks/wait/resume', {
      params: { path: { workspaceId: this.workspaceId }, header: { 'X-Orqix-Callback-Token': this.token, 'Idempotency-Key': checkIdempotencyKey(idempotencyKey) } },
      // The contract types `result` as an object without declared properties; the server checks it against the step.
      body: { result } as unknown as Schemas['CallbackResumeRequest'],
    }))
  }
}

export { ApiError }
