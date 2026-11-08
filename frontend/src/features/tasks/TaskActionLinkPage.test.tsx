import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { seedTaskActionLink } from '@/mocks/taskActions'
import { renderApp, signInAs, WS } from '@/test/utils'

const available = { allowed: true, reasonCode: null }

function seed(token: string, rejectReasonRequired = true) {
  seedTaskActionLink(token, {
    workspaceId: WS, taskId: 'task-1', label: 'Review request', workflowName: 'Purchase approval', runReference: 'PR-1001',
    status: 'open', dueAt: '2026-11-07T17:00:00Z', version: 1, person: { id: 'p1', displayName: 'Mani Manager' }, channel: 'email',
    expiresAt: '2026-11-08T10:00:00Z', rejectReasonRequired, actions: { claim: available, approve: available, reject: available },
  })
}

describe('Signed action link page (S141)', () => {
  it('shows the task without request details and acts only after confirmation', async () => {
    signInAs('otto.outsider')
    seed('good-token')
    const user = userEvent.setup()
    renderApp('/act/good-token')
    expect(await screen.findByText('Review request')).toBeInTheDocument()
    expect(screen.getByText('PR-1001')).toBeInTheDocument()
    expect(screen.getByText(/This link is for Mani Manager/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Reject' }))
    expect(screen.getByText(/Reject .*Review request.* as Mani Manager\?/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    await user.click(screen.getByRole('button', { name: 'Approve' }))
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('You approved this task.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the task in Orqix' })).toHaveAttribute('href', `/w/${WS}/tasks/task-1`)
  })

  it('explains an unknown or expired link', async () => {
    signInAs('otto.outsider')
    renderApp('/act/forged-token')
    expect(await screen.findByText('This link cannot be used')).toBeInTheDocument()
  })
})
