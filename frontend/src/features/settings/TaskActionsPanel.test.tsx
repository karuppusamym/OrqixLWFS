import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { seedTaskActionMessages } from '@/mocks/taskActions'
import { userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('Settings, actionable tasks (S141)', () => {
  it('switches actionable tasks on with channels and refuses wildcard domains and missing secrets', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Actionable tasks' }))
    expect(await screen.findByText(/local test stubs/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Send actionable messages for new approval tasks' }))
    await user.click(screen.getByRole('checkbox', { name: 'Send email with a signed link' }))
    await user.type(screen.getByLabelText('Sender'), 'orqix@example.com')
    await user.type(screen.getByLabelText(/Approved recipient domains/), '*.example.com')
    await user.click(screen.getByRole('checkbox', { name: 'Send Slack cards' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    await user.clear(screen.getByLabelText(/Approved recipient domains/))
    await user.type(screen.getByLabelText(/Approved recipient domains/), 'example.com')
    await user.type(screen.getByLabelText('Slack signing secret reference'), 'slack-signing')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/Last changed by Wade Admin/)).toBeInTheDocument()
    expect(screen.getByText('On')).toBeInTheDocument()
  })

  it('links and unlinks chat accounts and lists deliveries without content', async () => {
    signInAs('wade.admin')
    const mani = userByName('mani.manager')
    seedTaskActionMessages(WS, [
      { id: '01a1aede-0000-7000-8000-000000000101', taskId: 't-1', person: { id: mani.id, displayName: mani.displayName }, channel: 'slack', reason: 'created', status: 'skipped', skipCode: 'NO_CHAT_IDENTITY', createdAt: '2026-11-06T10:00:00Z', sentAt: '2026-11-06T10:00:05Z' },
    ])
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Actionable tasks' }))
    await user.click(await screen.findByRole('checkbox', { name: 'Send actionable messages for new approval tasks' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Chat account not linked')).toBeInTheDocument()

    await user.type(screen.getByLabelText(/Chat user ID/), 'U-MANI')
    await user.type(screen.getByLabelText('Orqix person ID'), mani.id)
    await user.click(screen.getByRole('button', { name: 'Link account' }))
    const row = await screen.findByText('U-MANI')
    expect(within(row.closest('tr')!).getByText(mani.displayName)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Unlink U-MANI' }))
    expect(await screen.findByText('No chat accounts are linked yet.')).toBeInTheDocument()
  })

  it('is not offered to people who cannot manage access', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/settings`)
    expect(await screen.findByRole('tablist', { name: 'Settings sections' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Actionable tasks' })).not.toBeInTheDocument()
  })
})
