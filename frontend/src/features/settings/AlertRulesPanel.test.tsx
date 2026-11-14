import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { grantAlertManage, seedAlertIncidents } from '@/mocks/alertsRetention'
import { userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('Settings, alerts (S144)', () => {
  it('adds a destination, creates a rule on it and sends a test', async () => {
    signInAs('olga.operator')
    grantAlertManage(WS, userByName('olga.operator').id)
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Alerts' }))
    expect(await screen.findByText(/local test stubs/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create rule' })).toBeDisabled()

    await user.type(screen.getByLabelText('Destination name'), 'Ops Slack')
    await user.type(screen.getByLabelText('Secret reference'), 'ops-slack-hook')
    await user.click(screen.getByRole('button', { name: 'Add destination' }))
    expect(await screen.findByRole('cell', { name: 'Ops Slack' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send a test to Ops Slack' }))
    expect(await screen.findByText('Sent (stubbed)')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Rule name'), 'Procurement paused')
    await user.selectOptions(screen.getByLabelText('Signal'), 'circuitOpen')
    await user.type(screen.getByLabelText('Connection key (optional)'), 'procurement')
    await user.click(screen.getByRole('checkbox', { name: 'Ops Slack' }))
    await user.click(screen.getByRole('button', { name: 'Create rule' }))
    expect(await screen.findByText('Procurement paused')).toBeInTheDocument()
    expect(screen.getByText(/Paused connections \(circuit open\) · procurement/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Silence Procurement paused for 4 hours' }))
    expect(await screen.findByText(/Silenced until/)).toBeInTheDocument()
  })

  it('shows incidents with suppressed and delivered notifications', async () => {
    signInAs('olga.operator')
    grantAlertManage(WS, userByName('olga.operator').id)
    seedAlertIncidents(WS, [{
      id: '01a1aede-0000-7000-8000-000000000201', ruleId: '01a1aede-0000-7000-8000-000000000202', ruleName: 'Attention backlog',
      signal: 'needsAttention', severity: 'warning', status: 'resolved', openedAt: '2026-11-08T08:00:00Z', resolvedAt: '2026-11-08T09:00:00Z',
      openingValue: 6, peakValue: 9, lastValue: 2, threshold: 5, suppressed: null,
      deliveries: [{ id: '01a1aede-0000-7000-8000-000000000203', destinationId: '01a1aede-0000-7000-8000-000000000204', destinationName: 'On call', kind: 'fired', status: 'sent', attempts: 1, outcome: 'stubbed', errorCode: null, createdAt: '2026-11-08T08:00:00Z', settledAt: '2026-11-08T08:00:01Z' }],
    }])
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Alerts' }))
    expect(await screen.findByText('Attention backlog')).toBeInTheDocument()
    expect(screen.getByText('6 (peak 9, threshold 5)')).toBeInTheDocument()
    expect(screen.getByText('On call: fired sent')).toBeInTheDocument()
  })

  it('is not offered without alert.manage', async () => {
    signInAs('wade.admin')
    renderApp(`/w/${WS}/settings`)
    expect(await screen.findByRole('tablist', { name: 'Settings sections' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Alerts' })).not.toBeInTheDocument()
  })
})
