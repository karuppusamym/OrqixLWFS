import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import { db } from '@/mocks/state'

async function openTab(user: ReturnType<typeof userEvent.setup>) {
  renderApp(`/w/${WS}/settings`)
  await user.click(await screen.findByRole('tab', { name: /Migration review/ }))
  return screen.findByRole('form', { name: 'Migration review setting' })
}

describe('migration review setting', () => {
  it('requires a reason and saves the setting against the version the page loaded', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    const form = await openTab(user)
    expect(screen.getByTestId('migration-policy-state')).toHaveTextContent('Independent review not required')
    expect(screen.getByTestId('migration-policy-state')).toHaveTextContent('Setting version 0, never changed')
    const save = within(form).getByRole('button', { name: 'Save migration review setting' })
    expect(save).toBeDisabled()
    await user.click(within(form).getByRole('checkbox', { name: /Require an independent review/ }))
    expect(save).toBeDisabled() // a reason is still missing
    await user.type(within(form).getByLabelText('Reason for this setting'), 'Regulator asked for four eyes')
    expect(save).toBeEnabled()
    await user.click(save)
    await waitFor(() => expect(screen.getByTestId('migration-policy-state')).toHaveTextContent('Independent review required'))
    expect(screen.getByTestId('migration-policy-state')).toHaveTextContent('Setting version 1')
    expect(screen.getByText('Regulator asked for four eyes')).toBeInTheDocument()
    expect(db.migrationPolicies.get(WS)).toMatchObject({ required: true, version: 1, reason: 'Regulator asked for four eyes' })
    expect(screen.getByTestId('announcer')).toHaveTextContent('Independent migration review is now required.')
    // The form starts again from the saved state, so switching it off is a new, reasoned change.
    expect(screen.getByRole('checkbox', { name: /Require an independent review/ })).toBeChecked()
    expect(screen.getByLabelText('Reason for this setting')).toHaveValue('')
  })

  it('switches the requirement off again with a new reason', async () => {
    signInAs('wade.admin')
    db.migrationPolicies.set(WS, { required: true, version: 3, reason: 'Four eyes', updatedAt: new Date().toISOString() })
    const user = userEvent.setup()
    const form = await openTab(user)
    expect(screen.getByTestId('migration-policy-state')).toHaveTextContent('Setting version 3')
    await user.click(within(form).getByRole('checkbox', { name: /Require an independent review/ }))
    await user.type(within(form).getByLabelText('Reason for this setting'), 'Pilot finished')
    await user.click(within(form).getByRole('button', { name: 'Save migration review setting' }))
    await waitFor(() => expect(db.migrationPolicies.get(WS)).toMatchObject({ required: false, version: 4, reason: 'Pilot finished' }))
    expect(await screen.findByText('Independent review not required')).toBeInTheDocument()
  })

  it('shows the newer setting when somebody else saved first, without overwriting it', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    const form = await openTab(user)
    // Another administrator saves while this page is open.
    db.migrationPolicies.set(WS, { required: true, version: 1, reason: 'Set by a colleague', updatedAt: new Date().toISOString() })
    await user.type(within(form).getByLabelText('Reason for this setting'), 'My change')
    await user.click(within(form).getByRole('checkbox', { name: /Require an independent review/ }))
    await user.click(within(form).getByRole('button', { name: 'Save migration review setting' }))
    expect(await screen.findByText('The setting changed while you were editing')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('migration-policy-state')).toHaveTextContent('Setting version 1'))
    expect(screen.getByText('Set by a colleague')).toBeInTheDocument()
    expect(db.migrationPolicies.get(WS)?.reason).toBe('Set by a colleague')
  })

  it('shows the server’s message for any other failure', async () => {
    signInAs('wade.admin')
    server.use(http.put('*/api/v1/workspaces/:ws/migration-policy', () => HttpResponse.json({ code: 'FORBIDDEN', status: 403, title: 'Forbidden', detail: 'You do not have permission to do this.' }, { status: 403 })))
    const user = userEvent.setup()
    const form = await openTab(user)
    await user.type(within(form).getByLabelText('Reason for this setting'), 'Try')
    await user.click(within(form).getByRole('button', { name: 'Save migration review setting' }))
    expect(await within(form).findByText('You do not have permission to do this.')).toBeInTheDocument()
  })

  it('is reachable with the keyboard', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    const tab = await screen.findByRole('tab', { name: /Migration review/ })
    tab.focus()
    await user.keyboard('{Enter}')
    const checkbox = await screen.findByRole('checkbox', { name: /Require an independent review/ })
    checkbox.focus()
    await user.keyboard(' ')
    expect(checkbox).toBeChecked()
    await user.tab()
    expect(screen.getByLabelText('Reason for this setting')).toHaveFocus()
  })

  it('is not offered to people who cannot manage access', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/settings`)
    await screen.findByRole('heading', { level: 1, name: 'Settings' })
    expect(screen.queryByRole('tab', { name: /Migration review/ })).not.toBeInTheDocument()
  })
})
