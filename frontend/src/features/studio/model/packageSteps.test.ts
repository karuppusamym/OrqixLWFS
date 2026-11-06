import { describe, expect, it } from 'vitest'
import { addPresetStep, asyncResultNames, callAndWait, isoSeconds, type AsyncOperation } from './packageSteps'
import type { Definition } from './types'

function base(): Definition {
  return {
    schemaVersion: '0.1',
    workflowKey: 'reports',
    name: 'Reports',
    inputSchema: { type: 'object', additionalProperties: false, required: [], properties: { title: { type: 'string', title: 'Title', maxLength: 100 } } },
    inputUi: { order: ['title'], widgets: {} },
    outputSchema: { type: 'object', additionalProperties: false, properties: {} },
    triggers: ['manual'],
    decisions: [],
    execution: { maxConcurrentRuns: 10, maxAttempts: 3, retryDelaySeconds: 30 },
    security: { selfApproval: 'deny' },
    dependencies: { connections: [{ key: 'report-jobs', definitionVersion: 1 }] },
    outputMapping: {},
    nodes: [
      { id: 'start', type: 'start', label: 'Start', config: {} },
      { id: 'startReport', type: 'http', label: 'Start the report', config: { connectionRef: 'report-jobs', operation: 'startReport', inputMapping: { title: '/input/title' }, resultMapping: {} } },
      { id: 'done', type: 'end', label: 'Done', config: { businessOutcome: 'Done', outcomeKind: 'success' } },
    ],
    transitions: [
      { id: 't1', from: 'start', to: 'startReport', on: 'next' },
      { id: 't2', from: 'startReport', to: 'done', on: 'next' },
    ],
    uiLayout: { positions: { start: { x: 0, y: 0 }, startReport: { x: 300, y: 0 }, done: { x: 600, y: 0 } } },
  } as unknown as Definition
}

const OPERATIONS: Record<string, AsyncOperation> = {
  startReport: { label: 'Start a report', async: { acceptedStatuses: [202], statusFrom: { header: 'Location' }, statusOperation: 'getJob', pollIntervalSeconds: 30, maxWaitSeconds: 7200 } },
  getJob: { label: 'Get a report job', maxTimeoutSeconds: 20, pathParams: { jobId: {}, version: { const: 'v1' } } },
}

describe('Call and wait for completion (CG-W02)', () => {
  it('keeps the status reference and adds a check on the status operation right after the call', () => {
    const result = callAndWait(base(), 'startReport', OPERATIONS)
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const def = result.def
    const call = def.nodes.find((n) => n.id === 'startReport')!
    expect(call.config.resultMapping).toEqual({ jobId: '/@async/jobId' })
    const check = def.nodes.find((n) => n.id === result.id)!
    expect(check.label).toBe('Wait for Get a report job')
    expect(check.config).toMatchObject({
      connectionRef: 'report-jobs',
      operation: 'getJob',
      inputMapping: { jobId: '/steps/startReport/jobId' },
      timeoutSeconds: 10,
      poll: { every: 'PT30S', giveUpAfter: 'PT2H' },
    })
    // The call now leads to the check, and the check takes over the old target.
    expect(def.transitions.find((t) => t.from === 'startReport')?.to).toBe(result.id)
    expect(def.transitions.find((t) => t.from === result.id && t.on === 'next')?.to).toBe('done')
  })

  it('refuses steps whose operation does not finish later', () => {
    const plain = { ...OPERATIONS, startReport: { label: 'Start' } }
    expect(callAndWait(base(), 'startReport', plain)).toMatchObject({ ok: false })
    expect(callAndWait(base(), 'start', OPERATIONS)).toMatchObject({ ok: false })
  })

  it('names only the non-constant path parameters as results and formats durations', () => {
    expect(asyncResultNames(OPERATIONS, 'startReport')).toEqual(['jobId'])
    expect([isoSeconds(86_400), isoSeconds(7200), isoSeconds(120), isoSeconds(45)]).toEqual(['P1D', 'PT2H', 'PT2M', 'PT45S'])
  })
})

describe('package step templates', () => {
  it('adds the template with its references pointed at the new step and pins the installed connection version', () => {
    const { def, id } = addPresetStep(base(), {
      label: 'Wait until the incident is resolved',
      config: { operation: 'getIncident', inputMapping: { sysId: '/input/title' }, resultMapping: { state: '/result/state' },
        poll: { until: { fieldRef: '/steps/templateStep/state', operator: 'in', value: ['6', '7'] } } },
      connectionKey: 'servicenow-itsm',
      connectionVersion: 3,
    })
    const node = def.nodes.find((n) => n.id === id)!
    expect(node.type).toBe('http')
    expect(node.label).toBe('Wait until the incident is resolved')
    expect(node.config.connectionRef).toBe('servicenow-itsm')
    expect((node.config.poll as { until: { fieldRef: string } }).until.fieldRef).toBe(`/steps/${id}/state`)
    expect(def.dependencies.connections).toContainEqual({ key: 'servicenow-itsm', definitionVersion: 3 })
  })
})
