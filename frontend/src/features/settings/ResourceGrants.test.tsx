import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { clock, db } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

const FOLDER_ID = 'cccccccc-0000-4000-8000-000000000051'

async function openAccess() {
  signInAs('wade.admin')
  db.workflowFolders.set(FOLDER_ID, { id: FOLDER_ID, wsId: WS, name: 'HR', version: 1, createdAt: clock.iso(), updatedAt: clock.iso() })
  const user = userEvent.setup()
  renderApp(`/w/${WS}/settings`)
  await screen.findByRole('heading', { name: 'Access to single resources' })
  return user
}

describe('Settings → Access to single resources (CG-I01)', () => {
  it('lists no grants at first, then adds edit access on one folder and removes it after confirming', async () => {
    const user = await openAccess()
    expect(await screen.findByText('No access to single resources is granted.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add access' }))
    const form = screen.getByRole('form', { name: 'Add access to a single resource' })
    expect(within(form).getByLabelText('Resource type')).toHaveValue('folder')
    // Wade cannot list folders (no workflow read access), so he pastes the folder ID.
    await user.type(await within(form).findByLabelText('Folder ID'), FOLDER_ID)
    await user.selectOptions(within(form).getByLabelText('Permission'), 'workflow.edit')
    await user.type(within(form).getByLabelText('Sign-in group name'), 'orqix-hr-designers')
    await user.click(within(form).getByRole('button', { name: 'Add access' }))

    const row = (await screen.findByRole('cell', { name: /orqix-hr-designers/ })).closest('tr')!
    expect(within(row).getByText('HR')).toBeInTheDocument()
    expect(within(row).getByText('Folder')).toBeInTheDocument()
    expect(within(row).getByText('Can edit')).toBeInTheDocument()

    await user.click(within(row).getByRole('button', { name: 'Remove Can edit on HR from orqix-hr-designers' }))
    const dialog = await screen.findByRole('dialog', { name: 'Remove this access?' })
    expect(within(dialog).getByText(/orqix-hr-designers loses "can edit" on HR/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Remove access' }))
    await waitFor(() => expect(screen.queryByRole('cell', { name: /orqix-hr-designers/ })).not.toBeInTheDocument())
    expect(screen.getByText('No access to single resources is granted.')).toBeInTheDocument()
  })

  it('offers only the permissions a resource type allows and picks connections from the list', async () => {
    const user = await openAccess()
    await user.click(screen.getByRole('button', { name: 'Add access' }))
    const form = screen.getByRole('form', { name: 'Add access to a single resource' })
    const values = () => within(within(form).getByLabelText('Permission')).getAllByRole('option').map((o) => o.getAttribute('value'))
    await user.selectOptions(within(form).getByLabelText('Resource type'), 'decision')
    expect(values()).toEqual(['workflow.read', 'workflow.edit'])
    expect(within(form).getByLabelText('Shared decision table key')).toBeInTheDocument()

    await user.selectOptions(within(form).getByLabelText('Resource type'), 'connection')
    expect(values()).toEqual(['connection.read', 'connection.manage'])
    const picker = await within(form).findByRole('combobox', { name: 'Connection' })
    await user.selectOptions(picker, 'procurement')
    await user.selectOptions(within(form).getByLabelText('Permission'), 'connection.manage')
    await user.type(within(form).getByLabelText('Sign-in group name'), 'orqix-integration')
    await user.click(within(form).getByRole('button', { name: 'Add access' }))
    const row = (await screen.findByRole('cell', { name: /orqix-integration/ })).closest('tr')!
    expect(within(row).getByText('procurement')).toBeInTheDocument()
    expect(within(row).getByText('Can change the address and test it')).toBeInTheDocument()
  })

  it('offers the grant-only data.reveal permission', async () => {
    const user = await openAccess()
    await user.click(screen.getByRole('button', { name: 'Add permission' }))
    const form = screen.getByRole('form', { name: 'Add permission' })
    await user.selectOptions(within(form).getByLabelText('Permission'), 'data.reveal')
    await user.type(within(form).getByLabelText('Sign-in group name'), 'orqix-hr-case-workers')
    await user.click(within(form).getByRole('button', { name: 'Add permission' }))
    const row = (await screen.findByRole('cell', { name: /orqix-hr-case-workers/ })).closest('tr')!
    expect(within(row).getByText(/Can reveal masked personal and sensitive answers/)).toBeInTheDocument()
  })
})
