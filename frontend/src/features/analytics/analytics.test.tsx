import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { Providers } from '@/app/providers'
import { db, userByName, type MockRun } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, testQueryClient, WS } from '@/test/utils'
import { HumanWorkCard } from './HumanWorkCard'
import { KpiOverviewCard } from './KpiOverviewCard'
import { heatIndex, levelOf, routeHeatText, stepHeatText } from './heatmap'

type S = components['schemas']

const RANGE = { from: '2026-10-10', to: '2026-11-08', days: 30, generatedAt: '2026-11-08T12:00:00Z' }
const FRESH = { lastProjectedAt: '2026-11-08T11:59:00Z', pendingUpdates: 0 }

function runByRef(ref: string): MockRun {
  for (const run of db.runs.values()) if (run.reference === ref) return run
  throw new Error(`No run ${ref}`)
}

describe('heatmap model (CG-N01, docs/build/S145)', () => {
  it('ranks steps by p95 time and connections by use, and says the numbers in words', () => {
    const index = heatIndex({
      range: RANGE, workflowId: 'wf', releaseId: null, runs: 10, finishedRuns: 8, freshness: FRESH,
      steps: [
        { nodeId: 'manager', kind: 'approval', visits: 9, runs: 9, averageSeconds: 600, p95Seconds: 3600, maxSeconds: 4000 },
        { nodeId: 'route', kind: 'branch', visits: 8, runs: 8, averageSeconds: 0, p95Seconds: 0, maxSeconds: 0 },
      ],
      routes: [{ transitionId: 't1', runs: 10, share: 1 }, { transitionId: 't2', runs: 2, share: 0.2 }],
    })
    expect(index.steps.get('manager')?.level).toBe(4)
    expect(index.steps.get('route')?.level).toBe(0)
    expect(index.routes.get('t1')?.level).toBe(4)
    expect(index.routes.get('t2')?.level).toBe(1)
    expect(stepHeatText(index.steps.get('manager')!)).toBe('9 visits · avg 10 min · p95 1 h')
    expect(routeHeatText(index.routes.get('t2')!)).toBe('2 requests (20%)')
    expect(levelOf(null, 10)).toBe(0)
  })
})

describe('heatmap on the run diagram', () => {
  it('overlays visits and route use for people with run.read', { timeout: 40000 }, async () => {
    signInAs('olga.operator', { demo: true })
    const run = runByRef('ORQ-1003')
    renderApp(`/w/${WS}/runs/${run.id}`)
    await screen.findByTestId('timeline')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Diagram' }))
    // The diagram is a lazy chunk; on a busy machine its first load takes several seconds.
    await screen.findByTestId('run-step-manager', {}, { timeout: 15000 })
    await user.click(screen.getByRole('checkbox', { name: /Heatmap/ }))
    expect(await screen.findByTestId('heatmap-status')).toHaveTextContent(/requests? started/)
    await waitFor(() => expect(screen.getByTestId('step-heat-manager')).toHaveTextContent(/visit/))
    const table = screen.getByTestId('heatmap-table')
    expect(within(table).getAllByText(/requests? \(\d+%\)/).length).toBeGreaterThan(0)
  })

  it('is not offered to a requester, who may not see all requests', { timeout: 40000 }, async () => {
    signInAs('rhea.requester', { demo: true })
    const rhea = userByName('rhea.requester').id
    const run = [...db.runs.values()].find((r) => r.initiatorId === rhea)
    expect(run).toBeDefined()
    renderApp(`/w/${WS}/runs/${run!.id}`)
    await screen.findByTestId('timeline')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Diagram' }))
    await screen.findByTestId('run-diagram', {}, { timeout: 15000 })
    expect(screen.queryByTestId('heatmap-controls')).not.toBeInTheDocument()
  })
})

describe('targets and human work on Insights (CG-N03, CG-N05)', () => {
  it('lists off-target workflows with their delay cost', async () => {
    server.use(http.get('/api/v1/workspaces/:ws/analytics/kpis', () => HttpResponse.json({
      range: RANGE, freshness: FRESH,
      items: [{
        workflow: { id: 'wf1', key: 'purchase', name: 'Purchase approval' },
        targets: { workflowId: 'wf1', cycleP50Seconds: null, cycleP95Seconds: 3600, automationRate: null, slaAttainment: 0.9, delayCostPerHour: 50, currency: 'EUR', version: 2, updatedAt: '2026-11-01T00:00:00Z' },
        measures: [
          { measure: 'cycleP95', target: 3600, actual: 7200, samples: 6, status: 'offTarget', gap: 3600 },
          { measure: 'slaAttainment', target: 0.9, actual: 0.95, samples: 20, status: 'onTarget', gap: 0 },
        ],
        offTarget: 1, completedRuns: 6, excessHours: 6, delayCost: 300, currency: 'EUR',
      }],
    } satisfies S['AnalyticsKpiOverview'])))
    render(<Providers client={testQueryClient()}><KpiOverviewCard ws="ws1" query={{ from: RANGE.from, to: RANGE.to }} enabled canEdit={false} /></Providers>)
    const row = await screen.findByTestId('kpi-purchase-cycleP95')
    expect(row).toHaveTextContent('Purchase approval')
    expect(row).toHaveTextContent('Off target')
    expect(row).toHaveTextContent('2 h')
    expect(row).toHaveTextContent(/300/)
    expect(screen.getByTestId('kpi-purchase-slaAttainment')).toHaveTextContent('On target')
  })

  it('saves the targets of the selected workflow with the version read', async () => {
    let sent: S['AnalyticsKpiTargetsUpdate'] | null = null
    server.use(
      http.get('/api/v1/workspaces/:ws/analytics/kpis', () => HttpResponse.json({ range: RANGE, freshness: FRESH, items: [] } satisfies S['AnalyticsKpiOverview'])),
      http.get('/api/v1/workspaces/:ws/workflows/:wf/kpi-targets', () => HttpResponse.json({ workflowId: 'wf1', cycleP50Seconds: null, cycleP95Seconds: null, automationRate: null, slaAttainment: null, delayCostPerHour: null, currency: null, version: 0, updatedAt: null } satisfies S['AnalyticsKpiTargets'])),
      http.put('/api/v1/workspaces/:ws/workflows/:wf/kpi-targets', async ({ request }) => {
        sent = (await request.json()) as S['AnalyticsKpiTargetsUpdate']
        return HttpResponse.json({ workflowId: 'wf1', cycleP50Seconds: null, cycleP95Seconds: 7200, automationRate: 0.5, slaAttainment: null, delayCostPerHour: null, currency: null, version: 1, updatedAt: '2026-11-08T12:00:00Z' } satisfies S['AnalyticsKpiTargets'])
      }),
    )
    render(<Providers client={testQueryClient()}><KpiOverviewCard ws="ws1" query={{ from: RANGE.from, to: RANGE.to, workflowId: 'wf1' }} enabled canEdit /></Providers>)
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('95th percentile cycle time (hours)'), '2')
    await user.type(screen.getByLabelText('Automation rate (%)'), '50')
    await user.click(screen.getByRole('button', { name: 'Save targets' }))
    await screen.findByText('Targets saved.')
    expect(sent).toEqual({ cycleP50Seconds: null, cycleP95Seconds: 7200, automationRate: 0.5, slaAttainment: null, delayCostPerHour: null, currency: null, expectedVersion: 0 })
  })

  it('shows load, waiting versus working time and reassignments per group', async () => {
    server.use(http.get('/api/v1/workspaces/:ws/analytics/human-work', () => HttpResponse.json({
      range: RANGE, freshness: FRESH, truncated: false,
      groups: [{ groupKey: 'managers', groupName: 'Managers', openNow: 4, openUnassigned: 3, oldestOpenSeconds: 7200, finished: 10, averageWaitingSeconds: 1800, averageWorkingSeconds: 600, slaMet: 9, slaMissed: 1, slaAttainment: 0.9, reassignments: 2 }],
      steps: [{ workflow: { id: 'wf1', key: 'purchase', name: 'Purchase approval' }, nodeId: 'manager', label: 'Manager review', groupKey: 'managers', groupName: 'Managers', finished: 10, averageWaitingSeconds: 1800, p95WaitingSeconds: 3600, averageWorkingSeconds: 600, p95WorkingSeconds: 1200, slaMet: 9, slaMissed: 1, slaAttainment: 0.9, reassignments: 2, approximate: 0 }],
    } satisfies S['AnalyticsHumanWork'])))
    render(<Providers client={testQueryClient()}><HumanWorkCard ws="ws1" query={{ from: RANGE.from, to: RANGE.to }} enabled /></Providers>)
    const row = await screen.findByTestId('group-managers')
    expect(row).toHaveTextContent('Managers')
    expect(row).toHaveTextContent('30 min')
    expect(row).toHaveTextContent('10 min')
    expect(row).toHaveTextContent('90%')
    expect(screen.getByText('Manager review')).toBeInTheDocument()
  })
})
