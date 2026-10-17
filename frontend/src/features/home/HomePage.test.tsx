import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('home workspace shortcuts', () => {
  it('opens a group task in the claimable list', async () => {
    signInAs('mani.manager')
    const { router } = renderApp(`/w/${WS}`)
    const waiting = await screen.findByRole('region', { name: 'Waiting for you' })
    const link = await within(waiting).findByRole('link', { name: /Replacement laptop/ })
    expect(link).toHaveAttribute('href', expect.stringContaining('?view=claimable'))
    await userEvent.setup().click(link)
    await waitFor(() => expect(router.state.location.search).toBe('?view=claimable'))
    expect(await screen.findByRole('button', { name: 'Claim task' })).toBeInTheDocument()
  })

  it('gives requesters a tracking shortcut without approver counts', async () => {
    signInAs('rhea.requester')
    renderApp(`/w/${WS}`)
    const shortcuts = await screen.findByRole('region', { name: 'Workspace shortcuts' })
    expect(within(shortcuts).getByRole('link', { name: /Track your requests/ })).toHaveAttribute('href', `/w/${WS}/requests`)
    expect(within(shortcuts).queryByText('Assigned to you')).not.toBeInTheDocument()
  })
})
