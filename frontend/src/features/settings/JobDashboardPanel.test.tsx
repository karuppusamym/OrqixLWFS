import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { seedJobDashboard } from '@/mocks/bulk'
import { renderApp, signInAs, WS } from '@/test/utils'

const NOW = '2026-11-08T12:00:00Z'

describe('Settings, operations dashboard (S142)', () => {
  it('shows job queues with stale workers, polling checks, trigger lag and connection calls', async () => {
    seedJobDashboard({
      asOf: NOW,
      jobQueues: [
        { topic: 'address-check', available: 4, locked: 1, failedLastDay: 2, oldestAvailableAt: '2026-11-08T11:00:00Z', oldestAgeSeconds: 3600, workerLastSeenAt: '2026-11-08T10:00:00Z', workersSeenLastHour: 0 },
        { topic: 'scoring', available: 0, locked: 0, failedLastDay: 0, oldestAvailableAt: null, oldestAgeSeconds: null, workerLastSeenAt: '2026-11-08T11:59:30Z', workersSeenLastHour: 2 },
      ],
      pollChecks: [{ connectionKey: 'procurement', operation: 'getOrder', waiting: 3, checking: 0, needsAttention: 1, lateChecks: 2, nextCheckAt: NOW, lastCheckedAt: NOW }],
      packageTriggers: [{ id: '01a1aede-0000-7000-8000-000000000301', packageId: 'servicenow-itsm', triggerKey: 'incident-updated', kind: 'poll', enabled: true, lastRunAt: NOW, lastSuccessAt: NOW, lagSeconds: 120, lastErrorCode: null, deadLastDay: 1, nextRunAt: NOW }],
      connections: [{ connectionKey: 'procurement', name: 'Procurement API', bound: true, callsLastHour: 40, successRateLastHour: 0.95, callsLastDay: 400, failedLastDay: 12, lastErrorCode: 'HTTP_503', circuitState: 'open', notices: 1 }],
    })
    signInAs('olga.operator')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Operations' }))
    const queues = await screen.findByRole('table', { name: 'External job queues by topic' })
    const address = within(queues).getByText('address-check').closest('tr')!
    expect(within(address).getByText('No worker')).toBeInTheDocument()
    expect(within(address).getByText('60 min')).toBeInTheDocument()
    const scoring = within(queues).getByText('scoring').closest('tr')!
    expect(within(scoring).queryByText('No worker')).not.toBeInTheDocument()
    expect(within(scoring).getByText(/30 s ago \(2 in the last hour\)/)).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Open polling checks by connection operation' })).toHaveTextContent('getOrder')
    expect(screen.getByRole('table', { name: 'Package trigger lag' })).toHaveTextContent('servicenow-itsm / incident-updated')
    const calls = screen.getByRole('table', { name: 'Calls and errors per connection' })
    expect(within(calls).getByText('Calls paused')).toBeInTheDocument()
    expect(within(calls).getByText('HTTP_503')).toBeInTheDocument()
  })

  it('is not offered without run.operate', async () => {
    signInAs('vic.viewer')
    renderApp(`/w/${WS}/settings`)
    expect(await screen.findByRole('tablist', { name: 'Settings sections' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Operations' })).not.toBeInTheDocument()
  })
})
