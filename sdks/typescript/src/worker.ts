import { ApiError } from './errors.js'
import type { ExternalJob, OrqixClient } from './client.js'

const ERROR_CODE = /^[A-Z][A-Z0-9_]{1,39}$/
const TOPIC = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/

/** Result fields of a step: scalars only. */
export type JobResult = Record<string, string | number | boolean>

/** A job leased to this worker. `fence` numbers the lease; every later call presents it. */
export interface Job {
  readonly id: string
  readonly topic: string
  /** The values the step maps, by name. */
  readonly input: Readonly<Record<string, unknown>>
  readonly fence: number
  leaseExpiresAt: string
  readonly attempt: number
  readonly maxAttempts: number
  /** The human request reference, for example ORQ-1043. */
  readonly runReference: string
  readonly nodeId: string
  /** The job as the server sent it. */
  readonly raw: ExternalJob
}

/**
 * Does the work of one job and returns the declared result fields. Throw `BusinessError` for a business "no",
 * `JobFailed` for a technical failure; anything else is reported as a retryable failure naming only its class.
 */
export type Handler = (job: Job) => JobResult | void | Promise<JobResult | void>

/** Persist these fields in a gateway outbox. The callback ID is a correlation identifier, not a credential. */
export interface CallbackTicket {
  jobId: string
  callbackId: string
  deadline: string
}

/** A technical failure; retryable by default. `retryable: false` fails the job for good. */
export class JobFailed extends Error {
  override readonly name = 'JobFailed'
  constructor(message: string, readonly retryable = true, readonly retryAfterSeconds?: number) {
    super(message)
  }
}

/**
 * A business error (docs/build/S132): the work was done and the answer is "no", e.g.
 * `new BusinessError('ADDRESS_UNKNOWN', 'No such street')`. Final: never retried.
 */
export class BusinessError extends Error {
  override readonly name = 'BusinessError'
  constructor(readonly code: string, message: string) {
    super(`${checkErrorCode(code)}: ${message}`)
    this.businessMessage = message
  }
  readonly businessMessage: string
}

export function checkErrorCode(code: string): string {
  if (typeof code !== 'string' || !ERROR_CODE.test(code) || code.startsWith('ORQIX_')) {
    throw new TypeError('A business error code looks like ADDRESS_UNKNOWN (A-Z, 0-9, _; 2-40 characters; not starting with ORQIX_).')
  }
  return code
}

export interface WorkerOptions {
  /** Names this worker process in the server's records (1-100 characters). */
  workerId: string
  /** Longest a job is held without extending, 30-3600 seconds; default 120. */
  leaseSeconds?: number
  /** Jobs taken per fetch, 1-10; default 5. They are handled one after another. */
  maxJobs?: number
  /** Wait after a fetch that returned nothing; default 2000 ms. */
  pollIntervalMs?: number
  /** How often a running job's lease is extended; default half the lease. */
  extendEveryMs?: number
  /** Attempts of complete/fail on transient failures, with the same idempotency key; default 3. */
  finalCallAttempts?: number
  /** First delay between those attempts, doubled each time; default 1000 ms. */
  finalCallBackoffMs?: number
  /** Logs warnings of the loop; default `console`. */
  logger?: Pick<Console, 'warn' | 'info'>
}

const sleep = (ms: number, signal?: AbortSignal) => new Promise<void>(resolve => {
  if (signal?.aborted) return resolve()
  const timer = setTimeout(done, ms)
  function done() {
    clearTimeout(timer)
    signal?.removeEventListener('abort', done)
    resolve()
  }
  signal?.addEventListener('abort', done, { once: true })
})

function message(text: string): string {
  const value = text && text.trim() ? text : 'The worker reported a failure'
  return value.length > 500 ? value.slice(0, 500) : value
}

/**
 * External-worker loop (docs/build/S64). Same feature names as the Python SDK: fetchAndLock, extendLock, complete,
 * fail, failWithError, awaitCallback, completeCallback, failCallback, handle, run, stop.
 *
 * ```ts
 * const worker = new Worker(orqix, { workerId: 'address-checker-1' })
 * await worker.run({ 'address-check': job => ({ valid: true, score: 0.9 }) }) // until worker.stop()
 * ```
 */
export class Worker {
  readonly workerId: string
  readonly leaseSeconds: number
  readonly maxJobs: number
  private readonly pollIntervalMs: number
  private readonly extendEveryMs: number
  private readonly finalCallAttempts: number
  private readonly finalCallBackoffMs: number
  private readonly logger: Pick<Console, 'warn' | 'info'>
  private stopper = new AbortController()

  constructor(private readonly client: OrqixClient, options: WorkerOptions) {
    if (!options.workerId || options.workerId.length > 100) throw new TypeError('workerId is 1-100 characters')
    this.workerId = options.workerId
    this.leaseSeconds = options.leaseSeconds ?? 120
    if (this.leaseSeconds < 30 || this.leaseSeconds > 3600) throw new RangeError('leaseSeconds is 30-3600')
    this.maxJobs = options.maxJobs ?? 5
    if (this.maxJobs < 1 || this.maxJobs > 10) throw new RangeError('maxJobs is 1-10')
    this.pollIntervalMs = options.pollIntervalMs ?? 2000
    this.extendEveryMs = options.extendEveryMs ?? Math.max(1000, this.leaseSeconds * 500)
    this.finalCallAttempts = Math.max(1, options.finalCallAttempts ?? 3)
    this.finalCallBackoffMs = options.finalCallBackoffMs ?? 1000
    this.logger = options.logger ?? console
  }

  // ---------------------------------------------------------------- protocol calls

  async fetchAndLock(topics: string[]): Promise<Job[]> {
    const batch = await this.client.fetchExternalJobs({ workerId: this.workerId, topics, maxJobs: this.maxJobs, leaseSeconds: this.leaseSeconds })
    return (batch.items ?? []).map(raw => ({
      id: raw.id, topic: raw.topic, input: Object.freeze({ ...(raw.input ?? {}) }), fence: raw.fence,
      leaseExpiresAt: raw.leaseExpiresAt, attempt: raw.attempt, maxAttempts: raw.maxAttempts,
      runReference: raw.runReference, nodeId: raw.nodeId, raw,
    }))
  }

  async extendLock(job: Job, seconds = this.leaseSeconds): Promise<string> {
    const result = await this.client.extendExternalJobLock(job.id, { workerId: this.workerId, fence: job.fence, leaseSeconds: seconds })
    job.leaseExpiresAt = result.leaseExpiresAt
    return result.leaseExpiresAt
  }

  /**
   * Hands the job off before submitting remote work (asynchronous gateways). Use an outbox and a stable provider
   * dispatch key; neither a callback timeout nor a failure resubmits remote work automatically.
   */
  async awaitCallback(job: Job, timeoutSeconds = 3600): Promise<CallbackTicket> {
    const registration = await this.client.awaitExternalJobCallback(job.id, `${job.id}:${job.fence}:await-callback`,
      { workerId: this.workerId, fence: job.fence, timeoutSeconds })
    return { jobId: job.id, callbackId: registration.callbackId, deadline: registration.deadline }
  }

  /** Delivers the result as the subject that registered the callback; the process may have restarted since. */
  async completeCallback(ticket: CallbackTicket, result: JobResult): Promise<void> {
    await this.client.completeExternalJobCallback(ticket.jobId, ticket.callbackId, `${ticket.callbackId}:complete`, { result })
  }

  /** Reports a final remote failure. An operator inspects the remote outcome before authorizing a retry. */
  async failCallback(ticket: CallbackTicket, text: string): Promise<void> {
    await this.client.failExternalJobCallback(ticket.jobId, ticket.callbackId, `${ticket.callbackId}:fail`, { message: message(text) })
  }

  async complete(job: Job, result: JobResult): Promise<void> {
    await this.finalCall(() => this.client.completeExternalJob(job.id, `${job.id}:${job.fence}:complete`,
      { workerId: this.workerId, fence: job.fence, result }))
  }

  async fail(job: Job, text: string, options: { retryable?: boolean; retryAfterSeconds?: number } = {}): Promise<void> {
    await this.finalCall(() => this.client.failExternalJob(job.id, `${job.id}:${job.fence}:fail`, {
      workerId: this.workerId, fence: job.fence, message: message(text), retryable: options.retryable ?? true,
      ...(options.retryAfterSeconds === undefined ? {} : { retryAfterSeconds: options.retryAfterSeconds }),
    }))
  }

  /** Reports a business error (S132). Final; same idempotency key as `fail`: a lease ends with one final report. */
  async failWithError(job: Job, errorCode: string, text: string): Promise<void> {
    checkErrorCode(errorCode)
    await this.finalCall(() => this.client.failExternalJob(job.id, `${job.id}:${job.fence}:fail`,
      { workerId: this.workerId, fence: job.fence, message: message(text), retryable: false, errorCode }))
  }

  // ---------------------------------------------------------------- running handlers

  /** Runs one job with automatic lease extension; resolves true when the job was completed. */
  async handle(job: Job, handler: Handler): Promise<boolean> {
    const beat = setInterval(() => {
      this.extendLock(job).catch(() => undefined) // the lease is gone; the final call reports it
    }, this.extendEveryMs)
    let result: JobResult | void
    try {
      result = await handler(job)
    } catch (failure) {
      clearInterval(beat)
      if (failure instanceof BusinessError) {
        await this.failWithError(job, failure.code, failure.businessMessage)
      } else if (failure instanceof JobFailed) {
        await this.fail(job, failure.message, { retryable: failure.retryable, ...(failure.retryAfterSeconds === undefined ? {} : { retryAfterSeconds: failure.retryAfterSeconds }) })
      } else {
        // The class name is all the workflow's timeline gets to see.
        const name = failure instanceof Error ? failure.constructor.name : typeof failure
        await this.fail(job, `The worker raised ${name}`, { retryable: true })
      }
      return false
    } finally {
      clearInterval(beat)
    }
    try {
      await this.complete(job, result ?? {})
      return true
    } catch (refused) {
      if (!(refused instanceof ApiError) || refused.status !== 422) throw refused
      await this.fail(job, `The worker's result was refused (${refused.code})`, { retryable: true })
      return false
    }
  }

  /** Polls for the topics of `handlers` until `stop()`; resolves the number of jobs completed. */
  run(handlers: Record<string, Handler>): Promise<number> {
    return this.loop(handlers, false)
  }

  /** One fetch and the jobs it returned. */
  runOnce(handlers: Record<string, Handler>): Promise<number> {
    return this.loop(handlers, true)
  }

  /** Ends `run` after the current job. */
  stop(): void {
    this.stopper.abort()
  }

  private async loop(handlers: Record<string, Handler>, once: boolean): Promise<number> {
    const topics = Object.keys(handlers).sort()
    if (topics.length === 0 || topics.length > 10) throw new RangeError('A worker serves 1-10 topics')
    for (const topic of topics) if (!TOPIC.test(topic)) throw new TypeError(`A topic is lowercase words joined with dashes: ${topic}`)
    if (this.stopper.signal.aborted) this.stopper = new AbortController()
    const signal = this.stopper.signal
    let completed = 0
    while (!signal.aborted) {
      let jobs: Job[] = []
      let wait = this.pollIntervalMs
      try {
        jobs = await this.fetchAndLock(topics)
      } catch (e) {
        if (!(e instanceof ApiError) || e.isAuthenticationFailure) throw e
        this.logger.warn(`Fetching jobs failed: ${e.message}`)
        if (e.retryAfterSeconds !== undefined) wait = Math.min(300, e.retryAfterSeconds) * 1000
      }
      for (const job of jobs) {
        const handler = handlers[job.topic]
        if (!handler) continue
        try {
          if (await this.handle(job, handler)) completed++
        } catch (e) {
          if (!(e instanceof ApiError) || e.isAuthenticationFailure) throw e
          if (e.code === 'JOB_LEASE_LOST' || e.code === 'JOB_NOT_OPEN') {
            this.logger.info(`${job.runReference}/${job.nodeId} dropped: ${e.code}`) // another worker owns it now
          } else {
            this.logger.warn(`Reporting ${job.runReference}/${job.nodeId} failed: ${e.message}`) // the lease runs out
          }
        }
      }
      if (once) break
      if (jobs.length === 0) await sleep(wait, signal)
    }
    return completed
  }

  private async finalCall(call: () => Promise<void>): Promise<void> {
    let backoff = this.finalCallBackoffMs
    for (let attempt = 1; ; attempt++) {
      try {
        return await call()
      } catch (e) {
        if (!(e instanceof ApiError) || !e.isTransient || attempt >= this.finalCallAttempts) throw e
        await sleep(e.retryAfterSeconds !== undefined ? Math.min(60_000, e.retryAfterSeconds * 1000) : backoff)
        backoff *= 2
      }
    }
  }
}
