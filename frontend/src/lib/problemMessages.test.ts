import { describe, expect, it } from 'vitest'
import openapi from '../../../contracts/openapi.yaml?raw'
import { ProblemError } from '@/api/problem'
import type { ProblemCode } from '@/api/types'
import { describeProblem, fieldErrorMap, fieldKeyFromPointer, MAPPED_PROBLEM_CODES } from './problemMessages'

const problem = (code: ProblemCode | 'NETWORK_ERROR' | 'UNKNOWN_ERROR', extra: Partial<ConstructorParameters<typeof ProblemError>[0]> = {}) =>
  new ProblemError({ code, status: 400, title: 'x', ...extra })

/** Problem codes listed in the OpenAPI contract (the enum under components.schemas.Problem.properties.code). */
function contractCodes(): string[] {
  const normalized = openapi.replace(/\r\n/g, '\n')
  const start = normalized.indexOf('        code:\n          type: string\n          enum:')
  expect(start).toBeGreaterThan(0)
  const block = normalized.slice(start).split('\n').slice(3)
  const codes: string[] = []
  for (const line of block) {
    const m = /^\s+- ([A-Z_]+)\s*$/.exec(line)
    if (!m) break
    codes.push(m[1] as string)
  }
  return codes
}

describe('problem code -> user-facing behavior (docs/build/S7-frontend.md section 3)', () => {
  it('has an entry for every code in the OpenAPI contract', () => {
    const codes = contractCodes()
    expect(codes.length).toBeGreaterThan(30)
    expect([...MAPPED_PROBLEM_CODES].sort()).toEqual([...codes].sort())
  })

  it('VALIDATION_FAILED and REASON_REQUIRED show inline field errors', () => {
    expect(describeProblem(problem('VALIDATION_FAILED')).behavior).toBe('inline-fields')
    expect(describeProblem(problem('REASON_REQUIRED')).behavior).toBe('inline-fields')
    expect(describeProblem(problem('REASON_REQUIRED')).message).toBe('Add a reason so the requester knows what to change.')
  })

  it('STALE_REVISION opens the studio conflict dialog and names who saved', () => {
    const ui = describeProblem(problem('STALE_REVISION', { status: 412, currentRevision: 7 }), { otherUserName: 'Dana Designer' })
    expect(ui.behavior).toBe('studio-conflict')
    expect(ui.message).toBe('A newer version was saved by Dana Designer.')
  })

  it('task conflicts refresh the task and say who won', () => {
    for (const code of ['TASK_ALREADY_CLAIMED', 'TASK_NOT_OPEN', 'TASK_VERSION_CONFLICT'] as const) expect(describeProblem(problem(code, { status: 409 })).behavior).toBe('refresh-task')
    const done = { status: 'completed', assignee: null, completedBy: { id: 'u1', displayName: 'Mani Manager' } } as const
    expect(describeProblem(problem('TASK_NOT_OPEN'), { task: done }).message).toBe('This task was completed by Mani Manager.')
    const claimed = { status: 'open', assignee: { id: 'u2', displayName: 'Max Manager' }, completedBy: null } as const
    expect(describeProblem(problem('TASK_ALREADY_CLAIMED'), { task: claimed }).message).toBe('Claimed by Max Manager.')
    expect(describeProblem(problem('TASK_ALREADY_CLAIMED'), { task: claimed, currentUserId: 'u2' }).message).toBe('You already claimed this task.')
  })

  it('has the exact texts of the spec table', () => {
    expect(describeProblem(problem('SELF_APPROVAL_DENIED')).message).toBe("You can't approve your own request.")
    expect(describeProblem(problem('RELEASE_NOT_ACTIVE')).message).toBe('This form was updated. Review it and submit again.')
    expect(describeProblem(problem('RELEASE_NOT_ACTIVE')).behavior).toBe('reload-form')
    expect(describeProblem(problem('NO_ACTIVE_RELEASE')).message).toBe("This workflow isn't published yet.")
    expect(describeProblem(problem('WORKFLOW_INVALID')).behavior).toBe('open-validation')
    expect(describeProblem(problem('WARNINGS_NOT_ACKNOWLEDGED')).behavior).toBe('acknowledge-warnings')
    expect(describeProblem(problem('QUOTA_EXCEEDED')).message).toBe('Too many open requests for this workflow. Try again later.')
    expect(describeProblem(problem('EFFECT_NOT_RECONCILED')).behavior).toBe('operator-message')
    expect(describeProblem(problem('RUN_BUSY')).behavior).toBe('operator-message')
    const down = describeProblem(problem('DEPENDENCY_UNAVAILABLE', { status: 503 }))
    expect(down.message).toBe('Service temporarily unavailable. Nothing was submitted.')
    expect(down.retryable).toBe(true)
    expect(describeProblem(problem('NOT_FOUND', { status: 404 })).behavior).toBe('not-found')
    expect(describeProblem(problem('FORBIDDEN', { status: 403 }), { action: 'publish this workflow' }).message).toBe("You don't have permission to publish this workflow.")
  })

  it('falls back sensibly for network failures and unknown responses', () => {
    expect(describeProblem(problem('NETWORK_ERROR')).retryable).toBe(true)
    expect(describeProblem(problem('UNKNOWN_ERROR')).behavior).toBe('message')
    expect(describeProblem(problem('SOMETHING_NEW' as ProblemCode)).message).toMatch(/Something went wrong/)
  })
})

describe('field errors', () => {
  it('addresses fields by key whether the pointer is /input/amount or /amount', () => {
    expect(fieldKeyFromPointer('/input/amount')).toBe('amount')
    expect(fieldKeyFromPointer('/amount')).toBe('amount')
    expect(fieldKeyFromPointer('/input')).toBe('')
    const error = problem('VALIDATION_FAILED', {
      status: 422,
      fieldErrors: [
        { pointer: '/input/amount', code: 'multipleOf', message: 'Use cents.' },
        { pointer: '/input/amount', code: 'minimum', message: 'Second message for the same field is ignored.' },
        { pointer: '/input/costCenter', code: 'enum', message: 'Pick one.' },
      ],
    })
    expect(fieldErrorMap(error)).toEqual({ amount: 'Use cents.', costCenter: 'Pick one.' })
  })
})
