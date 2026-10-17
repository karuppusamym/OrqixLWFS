import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cancelRun, createRun, decide, engineConfig, toRunDetail } from './engine'
import { makeRelease, seedDb } from './seed'
import { clock, db, onboardingTemplate, userByName, WS_PURCHASING, type MockRun, type MockWorkflow } from './state'

const pending: Array<() => void> = []
const original = engineConfig.schedule

function onboarding(): MockWorkflow {
  const designer = userByName('dana.designer')
  const definition = onboardingTemplate()
  const wf: MockWorkflow = {
    id: 'cccccccc-0000-4000-8000-000000000001', wsId: WS_PURCHASING, key: 'new-hire-onboarding', name: 'New hire onboarding',
    description: null, ownerId: designer.id, drafts: [], releases: [], activeReleaseId: null, version: 1,
    createdAt: clock.iso(), updatedAt: clock.iso(),
  }
  const release = makeRelease(wf.id, 1, 1, definition, designer.id)
  wf.releases.push(release)
  wf.activeReleaseId = release.id
  db.workflows.set(wf.id, wf)
  return wf
}

function start(wf: MockWorkflow): MockRun {
  return createRun({
    wsId: WS_PURCHASING, workflow: wf, release: wf.releases[0]!, initiator: userByName('rhea.requester'), businessKey: null,
    input: { employeeName: 'Ada Lovelace', role: 'Engineer', department: 'IT', equipment: 'Laptop', equipmentBudget: 1800, justification: 'New starter' },
  })
}

function approve(run: MockRun, nodeId: string, user: string): void {
  const task = run.tasks.find((t) => t.nodeId === nodeId && t.status === 'open')!
  decide(run, task, userByName(user), 'approve', null, null)
}

function fireTimers(): void {
  while (pending.length > 0) pending.shift()!()
}

describe('workflow patterns in mock mode (S12)', () => {
  beforeEach(() => {
    seedDb({ sampleRuns: false })
    pending.length = 0
    engineConfig.schedule = (fn) => { pending.push(fn) }
  })
  afterEach(() => { engineConfig.schedule = original })

  it('runs both approvals in parallel, waits, starts the purchase as a child and finishes with its result', () => {
    const wf = onboarding()
    const run = start(wf)
    expect(run.tasks.filter((t) => t.status === 'open').map((t) => t.nodeId).sort()).toEqual(['facilities', 'it'])
    approve(run, 'it', 'mani.manager')
    expect(run.lifecycle).toBe('waiting')
    approve(run, 'facilities', 'fin.finance')
    expect(run.waits).toHaveProperty('leadTime')
    expect(toRunDetail(run, userByName('rhea.requester')).currentSteps.map((s) => s.kind)).toEqual(['wait'])

    fireTimers()
    const children = toRunDetail(run, userByName('rhea.requester')).children!
    expect(children).toHaveLength(1)
    expect(children[0]!.workflow.key).toBe('purchase-approval')
    const child = db.runs.get(children[0]!.id)!
    expect(toRunDetail(child, userByName('rhea.requester')).parentRun?.reference).toBe(run.reference)

    approve(child, 'manager', 'mani.manager')
    expect(child.outcome).toBe('Fulfilled')
    expect(run.lifecycle).toBe('completed')
    expect(run.outcome).toBe('Ready')
    expect(run.output).toHaveProperty('equipmentOrderId')
    expect(run.events.map((e) => e.type)).toEqual(expect.arrayContaining(['WAIT_STARTED', 'WAIT_ENDED', 'CHILD_STARTED', 'CHILD_ENDED']))
  })

  it('escalates an approval nobody decides in time', () => {
    const run = start(onboarding())
    fireTimers()
    expect(run.tasks.find((t) => t.nodeId === 'it')?.status).toBe('cancelled')
    expect(run.events.some((e) => e.type === 'TASK_TIMED_OUT')).toBe(true)
    expect(run.tasks.some((t) => t.nodeId === 'itEscalation' && t.status === 'open')).toBe(true)
  })

  it('ends the whole request when one branch is rejected', () => {
    const run = start(onboarding())
    const task = run.tasks.find((t) => t.nodeId === 'facilities')!
    decide(run, task, userByName('fin.finance'), 'reject', 'No desk free', null)
    expect(run.lifecycle).toBe('completed')
    expect(run.outcomeKind).toBe('rejected')
    expect(run.tasks.filter((t) => t.status === 'open')).toHaveLength(0)
  })

  it('cancelling the parent cancels the active child', () => {
    const wf = onboarding()
    const run = start(wf)
    approve(run, 'it', 'mani.manager')
    approve(run, 'facilities', 'fin.finance')
    fireTimers()
    const child = [...db.runs.values()].find((r) => r.parentRunId === run.id)!
    cancelRun(run, userByName('olga.operator'), 'Hire withdrawn')
    expect(child.lifecycle).toBe('cancelled')
    expect(run.lifecycle).toBe('cancelled')
  })
})
