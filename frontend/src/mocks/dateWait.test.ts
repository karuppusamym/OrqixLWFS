import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import example from '../../../examples/start-date-onboarding.json'
import { dateWaitDue, latestWaitDate, waitHorizonErrors } from './dateWait'
import { createRun, decide, engineConfig, toRunDetail } from './engine'
import { makeRelease, seedDb } from './seed'
import { clock, db, userByName, WS_PURCHASING, type MockRun, type MockWorkflow } from './state'
import { validateDefinition } from './validate'

const pending: Array<() => void> = []
const original = engineConfig.schedule

function definition(): Definition {
  return normalizeDefinition(structuredClone(example))
}

function workflow(): MockWorkflow {
  const designer = userByName('dana.designer')
  const wf: MockWorkflow = {
    id: 'cccccccc-0000-4000-8000-0000000000d1', wsId: WS_PURCHASING, key: 'start-date-onboarding', name: 'Start date',
    description: null, ownerId: designer.id, drafts: [], releases: [], activeReleaseId: null, version: 1,
    createdAt: clock.iso(), updatedAt: clock.iso(),
  }
  const release = makeRelease(wf.id, 1, 1, definition(), designer.id)
  wf.releases.push(release)
  wf.activeReleaseId = release.id
  db.workflows.set(wf.id, wf)
  return wf
}

function startAndApprove(startDate: string): MockRun {
  const wf = workflow()
  const run = createRun({ wsId: WS_PURCHASING, workflow: wf, release: wf.releases[0]!, initiator: userByName('rhea.requester'), businessKey: null, input: { employeeName: 'Ada', startDate } })
  const task = run.tasks.find((t) => t.nodeId === 'confirm' && t.status === 'open')!
  decide(run, task, userByName('mani.manager'), 'approve', null, null)
  return run
}

describe('mock engine: wait until a date', () => {
  beforeEach(() => {
    seedDb({ sampleRuns: false })
    pending.length = 0
    engineConfig.schedule = (fn) => { pending.push(fn) }
  })
  afterEach(() => { engineConfig.schedule = original })

  it('shows the real due moment and finishes when the timer fires', () => {
    const run = startAndApprove('2031-10-14')
    const steps = toRunDetail(run, userByName('rhea.requester')).currentSteps
    expect(steps.map((s) => [s.kind, s.dueAt])).toEqual([['wait', '2031-10-14T09:00:00.000Z']])
    const started = run.events.find((e) => e.type === 'WAIT_STARTED')!
    expect(started.data).toMatchObject({ until: '2031-10-14T09:00:00.000Z', duration: null })
    pending.splice(0).forEach((fn) => fn())
    expect(run.lifecycle).toBe('completed')
    expect(run.events.map((e) => e.type)).toContain('WAIT_ENDED')
  })

  it('ends at once when the date is in the past', () => {
    const run = startAndApprove('2020-01-01')
    pending.splice(0).forEach((fn) => fn())
    expect(run.lifecycle).toBe('completed')
  })

  it('uses the time of day of the step', () => {
    expect(dateWaitDue('2026-10-14', undefined).toISOString()).toBe('2026-10-14T09:00:00.000Z')
    expect(dateWaitDue('2026-10-14', '17:45').toISOString()).toBe('2026-10-14T17:45:00.000Z')
  })
})

describe('mock engine: horizon', () => {
  it('refuses dates more than 366 days ahead and accepts the last day', () => {
    const now = new Date('2026-10-10T12:00:00Z')
    expect(latestWaitDate(now)).toBe('2027-10-11')
    expect(waitHorizonErrors(definition(), { startDate: '2027-10-11' }, now)).toEqual([])
    const errors = waitHorizonErrors(definition(), { startDate: '2027-10-12' }, now)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatchObject({ pointer: '/input/startDate', code: 'formatMaximum' })
    expect(errors[0]!.message).toContain('11 Oct 2027')
    expect(waitHorizonErrors(definition(), { startDate: '2020-01-01' }, now)).toEqual([])
  })
})

describe('mock validation of date waits', () => {
  const codes = (d: Definition) => validateDefinition(d, { groups: ['managers'], connections: [], isTestWorkspace: false }).map((i) => `${i.code} ${i.path}`)

  it('accepts the example', () => {
    expect(codes(definition())).toEqual([])
  })

  it('needs exactly one of duration and date, and a date question that is required', () => {
    const both = definition()
    both.nodes.find((n) => n.id === 'untilStart')!.config.duration = 'PT1H'
    expect(codes(both)).toEqual(['WF_WAIT_CHOICE /nodes/2/config/untilField'])
    const neither = definition()
    neither.nodes.find((n) => n.id === 'untilStart')!.config = {}
    expect(codes(neither)).toEqual(['WF_WAIT_CHOICE /nodes/2/config/duration'])
    const text = definition()
    text.nodes.find((n) => n.id === 'untilStart')!.config.untilField = '/input/employeeName'
    expect(codes(text)).toEqual(['WF_WAIT_FIELD_TYPE /nodes/2/config/untilField'])
    const optional = definition()
    optional.inputSchema.required = ['employeeName']
    expect(codes(optional)).toEqual(['WF_WAIT_FIELD_OPTIONAL /nodes/2/config/untilField'])
    const badTime = definition()
    badTime.nodes.find((n) => n.id === 'untilStart')!.config.atTime = '25:00'
    expect(codes(badTime)).toEqual(['WF_SCHEMA_INVALID /nodes/2/config/atTime'])
  })

  it('checks date limits of a form question', () => {
    const d = definition()
    d.inputSchema.properties.startDate = { type: 'string', format: 'date', title: 'Start date', formatMinimum: '2026-12-31', formatMaximum: '2026-01-01' }
    expect(codes(d)).toEqual(['WF_FORM_INVALID /inputSchema/properties/startDate/formatMinimum'])
  })
})
