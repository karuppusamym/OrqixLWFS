import type { FieldError, ProblemCode, ValidationIssue } from './types'
import type { components } from './schema'
type CompatibilityIssue = components['schemas']['CompatibilityIssue']
/** A decision of an uploaded DMN file (docs/build/S121). */
export type DmnDecisionChoice = components['schemas']['DecisionDmnChoice']

/** Codes produced by the client itself when the server did not answer with a problem document. */
export type ClientProblemCode = 'NETWORK_ERROR' | 'UNKNOWN_ERROR'

export interface ProblemBody {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
  correlationId?: string
  fieldErrors?: FieldError[]
  issues?: ValidationIssue[]
  openItems?: CompatibilityIssue[]
  currentRevision?: number
  currentVersion?: number
  decisions?: DmnDecisionChoice[]
}

/**
 * RFC 9457 problem details (docs/build/S3-api.md section 4) as a typed error.
 * Clients switch on `code` only; title and detail are human text and may change.
 */
export class ProblemError extends Error {
  readonly code: ProblemCode | ClientProblemCode
  readonly status: number
  readonly title: string
  readonly detail: string | undefined
  readonly correlationId: string | undefined
  readonly fieldErrors: FieldError[]
  readonly issues: ValidationIssue[]
  readonly openItems: CompatibilityIssue[]
  readonly currentRevision: number | undefined
  readonly currentVersion: number | undefined
  /** importDecisionDmn / convertDecisionDmn: the decisions of the file to choose from. */
  readonly decisions: DmnDecisionChoice[]
  readonly retryAfterSeconds: number | undefined

  constructor(init: {
    code: ProblemCode | ClientProblemCode
    status: number
    title: string
    detail?: string
    correlationId?: string
    fieldErrors?: FieldError[]
    issues?: ValidationIssue[]
    openItems?: CompatibilityIssue[]
    currentRevision?: number
    currentVersion?: number
    decisions?: DmnDecisionChoice[]
    retryAfterSeconds?: number
  }) {
    super(init.detail ?? init.title)
    this.name = 'ProblemError'
    this.code = init.code
    this.status = init.status
    this.title = init.title
    this.detail = init.detail
    this.correlationId = init.correlationId
    this.fieldErrors = init.fieldErrors ?? []
    this.issues = init.issues ?? []
    this.openItems = init.openItems ?? []
    this.currentRevision = init.currentRevision
    this.currentVersion = init.currentVersion
    this.decisions = init.decisions ?? []
    this.retryAfterSeconds = init.retryAfterSeconds
  }

  is(...codes: Array<ProblemCode | ClientProblemCode>): boolean {
    return codes.includes(this.code)
  }
}

export function isProblemError(value: unknown): value is ProblemError {
  return value instanceof ProblemError
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Build a ProblemError from an HTTP response and its (already parsed) body. */
export function problemFromResponse(response: Response, body: unknown): ProblemError {
  const retryAfter = Number(response.headers.get('Retry-After'))
  const retryAfterSeconds = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined
  if (isRecord(body) && typeof body.code === 'string') {
    const p = body as ProblemBody
    return new ProblemError({
      code: p.code as ProblemCode,
      status: typeof p.status === 'number' ? p.status : response.status,
      title: p.title ?? response.statusText ?? 'Request failed',
      detail: p.detail,
      correlationId: p.correlationId ?? response.headers.get('X-Correlation-Id') ?? undefined,
      fieldErrors: Array.isArray(p.fieldErrors) ? p.fieldErrors : [],
      issues: Array.isArray(p.issues) ? p.issues : [],
      openItems: Array.isArray(p.openItems) ? p.openItems : [],
      currentRevision: p.currentRevision,
      currentVersion: p.currentVersion,
      decisions: Array.isArray(p.decisions) ? p.decisions : [],
      retryAfterSeconds,
    })
  }
  return new ProblemError({
    code: 'UNKNOWN_ERROR',
    status: response.status,
    title: response.statusText || 'Unexpected response',
    correlationId: response.headers.get('X-Correlation-Id') ?? undefined,
    retryAfterSeconds,
  })
}

export function networkProblem(cause: unknown): ProblemError {
  const message = cause instanceof Error ? cause.message : 'Network request failed'
  return new ProblemError({ code: 'NETWORK_ERROR', status: 0, title: 'Network error', detail: message })
}
