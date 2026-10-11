import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { db, type MockRun } from '@/mocks/state'
import { buildRunPath } from '@/mocks/runPath'
import { currentRelease, toRun } from '@/mocks/engine'
import { userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { buildStepViews, describeStep, statusLabel } from './pathModel'

function runByRef(ref: string): MockRun {
  for (const run of db.runs.values()) if (run.reference === ref) return run
  throw new Error(`No run ${ref}`)
}

function pathOf(run: MockRun) {
  return buildRunPath({
    runId: run.id,
    lifecycle: run.lifecycle,
    definition: currentRelease(run).definition,
    events: run.events,
    currentSteps: toRun(run, userByName('olga.operator')).currentSteps,
    attentionNodeId: run.attention?.nodeId ?? null,
  })
}

describe('run path (mock of getRunPath) and the step model', () => {
  it('marks the manager step as where a new request is, with the start step passed', () => {
    signInAs('olga.operator', { demo: true })
    const path = pathOf(runByRef('ORQ-1001'))
    const views = buildStepViews(path)
    expect(views.get('start')?.status).toBe('done')
    expect(views.get('manager')?.status).toBe('waiting')
    expect(views.get('finance')?.status).toBe('ahead')
    expect(path.taken).toEqual(['t1'])
  })

  it('infers the choice and takes the finance route for a request waiting at finance', () => {
    signInAs('olga.operator', { demo: true })
    const path = pathOf(runByRef('ORQ-1003'))
    const views = buildStepViews(path)
    expect(views.get('manager')?.status).toBe('approved')
    expect(views.get('route')?.status).toBe('done')
    expect(views.get('finance')?.status).toBe('waiting')
    expect(views.get('procure')?.status).toBe('ahead')
    expect(path.taken).toEqual(expect.arrayContaining(['t1', 't2', 't4', 't5']))
    expect(path.taken).not.toContain('t6')
  })

  it('dims the steps a finished request did not use and shows rejection', () => {
    signInAs('olga.operator', { demo: true })
    const views = buildStepViews(pathOf(runByRef('ORQ-1007')))
    expect(views.get('manager')?.status).toBe('rejected')
    expect(views.get('finance')?.status).toBe('skipped')
    expect(views.get('rejected')?.status).toBe('done')
    expect(statusLabel('skipped', false)).toBe('Not used')
  })

  it('flags a failed system call as needing attention, in words for operators and requesters', () => {
    signInAs('olga.operator', { demo: true })
    const run = runByRef('ORQ-1009')
    const views = buildStepViews(pathOf(run))
    const step = views.get('procure')!
    expect(step.status).toBe('attention')
    expect(statusLabel('attention', true)).toBe('Needs attention')
    expect(statusLabel('attention', false)).toBe('Delayed')
    const detail = toRun(run, userByName('olga.operator'))
    expect(detail.attention).not.toBeNull()
  })

  it('describes waiting steps in business words', () => {
    signInAs('olga.operator', { demo: true })
    const run = runByRef('ORQ-1001')
    const step = buildStepViews(pathOf(run)).get('manager')!
    const described = describeStep(step, { run: { tasks: [], children: [], effects: [] } as never, operator: false })
    expect(described.summary).toMatch(/Waiting for/)
    expect(described.technical).toEqual([])
    const operator = describeStep(step, { run: { tasks: [], children: [], effects: [] } as never, operator: true })
    expect(operator.technical.map((t) => t.label)).toContain('Step ID')
  })
})

describe('run detail diagram view', () => {
  it('shows current steps and a warning instead of an incomplete historical diagram', async () => {
    signInAs('rhea.requester', { demo: true })
    const run = runByRef('ORQ-1001')
    server.use(http.get('*/workspaces/:ws/runs/:id/path', () => HttpResponse.json({ ...pathOf(run), historyTruncated: true, visited: [], taken: [] })))
    renderApp(`/w/${WS}/runs/${run.id}`)
    await screen.findByTestId('timeline')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Diagram' }))
    expect(await screen.findByText(/more history than the diagram can display/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Current steps' })).toBeInTheDocument()
    expect(screen.queryByTestId('run-diagram-canvas')).not.toBeInTheDocument()
  })
  it('toggles to the diagram, shows the current step with text and keeps the timeline one click away', async () => {
    signInAs('rhea.requester', { demo: true })
    const user = userEvent.setup()
    const run = runByRef('ORQ-1001')
    renderApp(`/w/${WS}/runs/${run.id}`)
    expect(await screen.findByTestId('timeline')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Diagram' }))
    const canvas = await screen.findByTestId('run-diagram-canvas', {}, { timeout: 8000 })
    expect(canvas).toHaveAttribute('aria-label', expect.stringMatching(/read only/))
    // The step list (a non-canvas alternative) says the same in words.
    await waitFor(() => expect(screen.getByTestId('diagram-step-list')).toBeInTheDocument())
    const list = within(screen.getByTestId('diagram-step-list'))
    expect(list.getByText('You are here')).toBeInTheDocument()
    expect(list.getAllByText('Not reached yet').length).toBeGreaterThan(0)
    await user.click(screen.getByRole('button', { name: 'Timeline' }))
    expect(await screen.findByTestId('timeline')).toBeInTheDocument()
  })
})
