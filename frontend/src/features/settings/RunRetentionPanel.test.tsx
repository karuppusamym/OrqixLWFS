import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { seedRetentionEligible } from '@/mocks/alertsRetention'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('Settings, run retention (S144)', () => {
  it('sets a policy, holds a request, runs a dry run and then archives and purges with confirmation', async () => {
    signInAs('wade.admin')
    seedRetentionEligible(WS, [
      { runId: '01a1aede-0000-7000-8000-000000000301', reference: 'ORQ-1001', workflowId: PURCHASE_WORKFLOW_ID },
      { runId: '01a1aede-0000-7000-8000-000000000302', reference: 'ORQ-1002', workflowId: PURCHASE_WORKFLOW_ID },
    ])
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Run retention' }))
    expect(await screen.findByText(/Purging cannot be undone/)).toBeInTheDocument()

    const days = await screen.findByLabelText(/Keep ended requests for/)
    await user.clear(days)
    await user.type(days, '90')
    const picker = await screen.findByLabelText(/^Workflow( ID)?$/)
    if (picker.tagName === 'SELECT') await user.selectOptions(picker, PURCHASE_WORKFLOW_ID)
    else await user.type(picker, PURCHASE_WORKFLOW_ID)
    await user.click(screen.getByRole('button', { name: 'Save policy' }))
    expect(await screen.findByText('90 days')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Request ID'), '01a1aede-0000-7000-8000-000000000301')
    await user.type(screen.getByLabelText('Reason'), 'Litigation notice 17')
    await user.click(screen.getByRole('button', { name: 'Place hold' }))
    expect(await screen.findByText('Litigation notice 17')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Dry run' }))
    expect(await screen.findByText(/Dry run: 1 due, 0 purged, 1 held/)).toBeInTheDocument()

    const purge = screen.getByRole('button', { name: 'Archive and purge now' })
    expect(purge).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /only be read from their archive/ }))
    await user.click(purge)
    expect(await screen.findByText(/Archive and purge: 1 due, 1 purged, 1 held/)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Download ORQ-1002' })).toHaveAttribute('href', expect.stringContaining('/run-archives/'))
  })

  it('is read-only for people without manage-access and hidden from others', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/settings`)
    expect(await screen.findByRole('tablist', { name: 'Settings sections' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Run retention' })).not.toBeInTheDocument()
  })
})
